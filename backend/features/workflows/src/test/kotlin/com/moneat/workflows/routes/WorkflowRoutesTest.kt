// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.moneat.workflows.routes

import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.stopTestKoin
import com.moneat.org.repositories.OrgMembershipRepository
import com.moneat.org.repositories.OrgMembershipRepositoryImpl
import com.moneat.org.services.OrgMembershipService
import com.moneat.workflows.engine.WorkflowCatalog
import com.moneat.workflows.engine.temporal.LinearGraphAdapter
import com.moneat.workflows.models.CreateWorkflowRequest
import com.moneat.workflows.models.UpdateWorkflowRequest
import com.moneat.workflows.models.WorkflowConditionConfig
import com.moneat.workflows.models.WorkflowPreviewRequest
import com.moneat.workflows.models.WorkflowPreviewResponse
import com.moneat.workflows.models.WorkflowResponse
import com.moneat.workflows.models.WorkflowRunCancelResponse
import com.moneat.workflows.models.WorkflowRunInstanceRequest
import com.moneat.workflows.models.WorkflowRunResponse
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.WorkflowStepPreview
import com.moneat.workflows.models.WorkflowTestMessageResponse
import com.moneat.workflows.models.WorkflowTestMessageResult
import com.moneat.workflows.models.WorkflowWebhookSigningResponse
import com.moneat.workflows.services.WorkflowGovernanceService
import com.moneat.workflows.services.WorkflowService
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.startKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class WorkflowRoutesTest {
    companion object {
        private var db: Database? = null
        private const val ORGANIZATION_NAME = "Workflow Routes"
        private const val WORKFLOW_ID = 42
        private const val MISSING_WORKFLOW_ID = 43
        private const val DEFAULT_WORKFLOW_ID = 44
        private const val RUN_ID = 7
        private const val MISSING_RUN_ID = 8
        private const val WORKFLOW_RESOURCE_ID = "11111111-1111-1111-1111-111111111111"
        private const val MISSING_WORKFLOW_RESOURCE_ID = "22222222-2222-2222-2222-222222222222"
        private const val DEFAULT_WORKFLOW_RESOURCE_ID = "33333333-3333-3333-3333-333333333333"
        private const val RUN_RESOURCE_ID = "44444444-4444-4444-4444-444444444444"
        private const val MISSING_RUN_RESOURCE_ID = "55555555-5555-5555-5555-555555555555"
        private const val VERSION_RESOURCE_ID = "66666666-6666-6666-6666-666666666666"
    }

    private val json = Json { encodeDefaults = true }
    private lateinit var workflowService: WorkflowService
    private var userId: Int = 0
    private var memberUserId: Int = 0
    private var organizationId: Int = 0

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_workflow_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships)
        userId = seedUser("owner@workflow-routes.test")
        memberUserId = seedUser("member@workflow-routes.test")
        organizationId = seedOrganization(ORGANIZATION_NAME)
        seedMembership(organizationId, userId, "owner")
        seedMembership(organizationId, memberUserId, "member")

        stopTestKoin()
        workflowService = mockk()
        stubWorkflowResourceResolution()
        startKoin {
            modules(
                module {
                    single<OrgMembershipRepository> { OrgMembershipRepositoryImpl() }
                    single { OrgMembershipService(get()) }
                    single { workflowService }
                    single { WorkflowGovernanceService(workflowService) }
                }
            )
        }
    }

    @AfterTest
    fun teardown() {
        stopTestKoin()
    }

    @Test
    fun `catalog route requires authentication and returns workflow catalog`() =
        testApplication {
            setupApp()
            every { workflowService.catalog() } returns WorkflowCatalog.response()

            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/workflows/catalog").status)

            val response = client.get("/v1/workflows/catalog") {
                withAuth(token())
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("alert.triggered"))
            verify { workflowService.catalog() }
        }

    @Test
    fun `list route returns forbidden without active organization claim`() =
        testApplication {
            setupApp()

            val response = client.get("/v1/workflows") {
                withAuth(RouteTestSupport.createToken(userId = userId + 100, orgId = null))
            }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `list route allows organization member workflows`() =
        testApplication {
            setupApp()
            val memberToken = RouteTestSupport.createToken(userId = memberUserId, orgId = organizationId)
            every { workflowService.listWorkflows(organizationId) } returns listOf(workflowResponse())

            val list = client.get("/v1/workflows") {
                withAuth(memberToken)
            }

            assertEquals(HttpStatusCode.OK, list.status)
            assertTrue(list.bodyAsText().contains("Route workflow"))
        }

    @Test
    fun `list route returns organization workflows`() =
        testApplication {
            setupApp()
            every { workflowService.listWorkflows(organizationId) } returns listOf(workflowResponse())

            val response = client.get("/v1/workflows") {
                withAuth(token())
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Route workflow"))
            verify { workflowService.listWorkflows(organizationId) }
        }

    @Test
    fun `create route returns created workflow and validation errors`() =
        testApplication {
            setupApp()
            val request = createRequest("Route workflow")
            val invalidRequest = createRequest("")
            every { workflowService.createWorkflow(organizationId, request) } returns workflowResponse()
            every {
                workflowService.createWorkflow(organizationId, invalidRequest)
            } throws IllegalArgumentException("Workflow name is required")

            val created = client.post("/v1/workflows") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
            }
            val invalid = client.post("/v1/workflows") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(invalidRequest))
            }

            assertEquals(HttpStatusCode.Created, created.status)
            assertTrue(created.bodyAsText().contains("Route workflow"))
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertTrue(invalid.bodyAsText().contains("Workflow name is required"))
        }

    @Test
    fun `mutation routes require organization admin role`() =
        testApplication {
            setupApp()
            val memberToken = RouteTestSupport.createToken(userId = memberUserId, orgId = organizationId)

            val create = client.post("/v1/workflows") {
                withAuth(memberToken)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(createRequest("Route workflow")))
            }
            val update = client.put("/v1/workflows/$WORKFLOW_RESOURCE_ID") {
                withAuth(memberToken)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(UpdateWorkflowRequest(name = "Nope")))
            }
            val delete = client.delete("/v1/workflows/$WORKFLOW_RESOURCE_ID") {
                withAuth(memberToken)
            }

            assertEquals(HttpStatusCode.Forbidden, create.status)
            assertEquals(HttpStatusCode.Forbidden, update.status)
            assertEquals(HttpStatusCode.Forbidden, delete.status)
            verify(exactly = 0) { workflowService.createWorkflow(any(), any()) }
            verify(exactly = 0) {
                workflowService.updateWorkflow(any<Int>(), any<Int>(), any<UpdateWorkflowRequest>())
            }
            verify(exactly = 0) { workflowService.deleteWorkflow(any<Int>(), any<Int>()) }
        }

    @Test
    fun `preview route returns rendered workflow messages`() =
        testApplication {
            setupApp()
            val request =
                WorkflowPreviewRequest(
                    triggerName = "alert.triggered",
                    steps = listOf(WorkflowStepConfig("notification.slack"))
                )
            val preview =
                WorkflowPreviewResponse(
                    scope = mapOf("alert.status" to "FIRING"),
                    previews = listOf(
                        WorkflowStepPreview(
                            step = "notification.slack",
                            channel = "slack",
                            title = "[P1] Worker failures detected",
                            body = "Worker failures crossed the threshold",
                            textBody = "[P1] Worker failures detected",
                            color = "#E01E5A",
                            ctaLabel = "View",
                            ctaUrl = "https://moneat.io/dashboards/13",
                            fallbackText = "[P1] Worker failures detected"
                        )
                    )
                )
            every { workflowService.previewWorkflow(request) } returns preview

            val response = client.post("/v1/workflows/preview") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"cta_label\":\"View\""))
            verify { workflowService.previewWorkflow(request) }
        }

    @Test
    fun `test message route sends rendered workflow messages`() =
        testApplication {
            setupApp()
            val request =
                WorkflowPreviewRequest(
                    triggerName = "alert.triggered",
                    steps = listOf(WorkflowStepConfig("notification.slack"))
                )
            val result =
                WorkflowTestMessageResponse(
                    scope = mapOf("alert.status" to "FIRING"),
                    results = listOf(
                        WorkflowTestMessageResult(
                            step = "notification.slack",
                            channel = "slack",
                            status = "sent"
                        )
                    )
                )
            coEvery { workflowService.testWorkflowMessage(organizationId, request) } returns result

            val response = client.post("/v1/workflows/test-message") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"status\":\"sent\""))
            coVerify { workflowService.testWorkflowMessage(organizationId, request) }
        }

    @Test
    fun `get route validates workflow id and handles missing or found workflows`() =
        testApplication {
            setupApp()
            every { workflowService.getWorkflow(organizationId, WORKFLOW_ID) } returns workflowResponse()
            every { workflowService.getWorkflow(organizationId, MISSING_WORKFLOW_ID) } returns null

            val invalid = client.get("/v1/workflows/not-a-uuid") {
                withAuth(token())
            }
            val missing = client.get("/v1/workflows/$MISSING_WORKFLOW_RESOURCE_ID") {
                withAuth(token())
            }
            val found = client.get("/v1/workflows/$WORKFLOW_RESOURCE_ID") {
                withAuth(token())
            }

            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertTrue(invalid.bodyAsText().contains("Invalid workflow ID"))
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertTrue(missing.bodyAsText().contains("Workflow not found"))
            assertEquals(HttpStatusCode.OK, found.status)
            assertTrue(found.bodyAsText().contains("Route workflow"))
        }

    @Test
    fun `update route validates workflow id and maps service outcomes`() =
        testApplication {
            setupApp()
            val renameRequest = UpdateWorkflowRequest(name = "Renamed workflow")
            val invalidConfigRequest =
                UpdateWorkflowRequest(conditions = listOf(WorkflowConditionConfig("alert.missing", "eq", "value")))
            every {
                workflowService.updateWorkflow(organizationId, WORKFLOW_ID, renameRequest)
            } returns workflowResponse(name = "Renamed workflow")
            every {
                workflowService.updateWorkflow(organizationId, MISSING_WORKFLOW_ID, renameRequest)
            } returns null
            every {
                workflowService.updateWorkflow(organizationId, DEFAULT_WORKFLOW_ID, invalidConfigRequest)
            } throws IllegalArgumentException("Unknown workflow condition reference")

            val invalidId = client.put("/v1/workflows/nope") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(renameRequest))
            }
            val missing = client.put("/v1/workflows/$MISSING_WORKFLOW_RESOURCE_ID") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(renameRequest))
            }
            val invalidConfig = client.put("/v1/workflows/$DEFAULT_WORKFLOW_RESOURCE_ID") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(invalidConfigRequest))
            }
            val updated = client.put("/v1/workflows/$WORKFLOW_RESOURCE_ID") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(renameRequest))
            }

            assertEquals(HttpStatusCode.BadRequest, invalidId.status)
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals(HttpStatusCode.BadRequest, invalidConfig.status)
            assertTrue(invalidConfig.bodyAsText().contains("Unknown workflow condition reference"))
            assertEquals(HttpStatusCode.OK, updated.status)
            assertTrue(updated.bodyAsText().contains("Renamed workflow"))
        }

    @Test
    fun `delete route validates workflow id and handles delete outcomes`() =
        testApplication {
            setupApp()
            every { workflowService.deleteWorkflow(organizationId, WORKFLOW_ID) } returns true
            every { workflowService.deleteWorkflow(organizationId, MISSING_WORKFLOW_ID) } returns false
            every {
                workflowService.deleteWorkflow(organizationId, DEFAULT_WORKFLOW_ID)
            } throws IllegalArgumentException("Default workflows cannot be modified")

            val invalidId = client.delete("/v1/workflows/nope") {
                withAuth(token())
            }
            val missing = client.delete("/v1/workflows/$MISSING_WORKFLOW_RESOURCE_ID") {
                withAuth(token())
            }
            val deleted = client.delete("/v1/workflows/$WORKFLOW_RESOURCE_ID") {
                withAuth(token())
            }
            val defaultWorkflow = client.delete("/v1/workflows/$DEFAULT_WORKFLOW_RESOURCE_ID") {
                withAuth(token())
            }

            assertEquals(HttpStatusCode.BadRequest, invalidId.status)
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertEquals(HttpStatusCode.BadRequest, defaultWorkflow.status)
            assertTrue(defaultWorkflow.bodyAsText().contains("Default workflows cannot be modified"))
        }

    @Test
    fun `runs route validates workflow id and bounds limit parameters`() =
        testApplication {
            setupApp()
            every { workflowService.listRuns(organizationId, WORKFLOW_ID, 1) } returns listOf(runResponse())
            every { workflowService.listRuns(organizationId, WORKFLOW_ID, 50) } returns
                listOf(runResponse(status = "pending"))
            every { workflowService.listRuns(organizationId, WORKFLOW_ID, 100) } returns
                listOf(runResponse(status = "failed"))

            val invalidId = client.get("/v1/workflows/nope/runs") {
                withAuth(token())
            }
            val lowerBound = client.get("/v1/workflows/$WORKFLOW_RESOURCE_ID/runs?limit=0") {
                withAuth(token())
            }
            val defaultLimit = client.get("/v1/workflows/$WORKFLOW_RESOURCE_ID/runs?limit=not-a-number") {
                withAuth(token())
            }
            val upperBound = client.get("/v1/workflows/$WORKFLOW_RESOURCE_ID/runs?limit=1000") {
                withAuth(token())
            }

            assertEquals(HttpStatusCode.BadRequest, invalidId.status)
            assertEquals(HttpStatusCode.OK, lowerBound.status)
            assertTrue(lowerBound.bodyAsText().contains("complete"))
            assertEquals(HttpStatusCode.OK, defaultLimit.status)
            assertTrue(defaultLimit.bodyAsText().contains("pending"))
            assertEquals(HttpStatusCode.OK, upperBound.status)
            assertTrue(upperBound.bodyAsText().contains("failed"))
        }

    @Test
    fun `instance routes create list get and cancel workflow runs`() =
        testApplication {
            setupApp()
            val request = WorkflowRunInstanceRequest()
            every { workflowService.listRuns(organizationId, WORKFLOW_ID, 50) } returns listOf(runResponse())
            every { workflowService.getRun(organizationId, WORKFLOW_ID, RUN_ID) } returns runResponse()
            coEvery {
                workflowService.createWorkflowInstance(organizationId, WORKFLOW_ID, request, userId)
            } returns runResponse(status = "pending")
            coEvery {
                workflowService.cancelRun(organizationId, WORKFLOW_ID, RUN_ID)
            } returns WorkflowRunCancelResponse(RUN_RESOURCE_ID, "canceled")

            val list = client.get("/v1/workflows/$WORKFLOW_RESOURCE_ID/instances") {
                withAuth(token())
            }
            val detail = client.get("/v1/workflows/$WORKFLOW_RESOURCE_ID/instances/$RUN_RESOURCE_ID") {
                withAuth(token())
            }
            val created = client.post("/v1/workflows/$WORKFLOW_RESOURCE_ID/instances") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
            }
            val canceled = client.put("/v1/workflows/$WORKFLOW_RESOURCE_ID/instances/$RUN_RESOURCE_ID/cancel") {
                withAuth(token())
            }

            assertEquals(HttpStatusCode.OK, list.status)
            assertTrue(list.bodyAsText().contains("complete"))
            assertEquals(HttpStatusCode.OK, detail.status)
            assertTrue(detail.bodyAsText().contains("host-1"))
            assertEquals(HttpStatusCode.Accepted, created.status)
            assertTrue(created.bodyAsText().contains("pending"))
            assertEquals(HttpStatusCode.OK, canceled.status)
            assertTrue(canceled.bodyAsText().contains("canceled"))
        }

    @Test
    fun `instance routes validate identifiers and map missing runs`() =
        testApplication {
            setupApp()
            every { workflowService.getRun(organizationId, WORKFLOW_ID, MISSING_RUN_ID) } returns null
            coEvery { workflowService.cancelRun(organizationId, WORKFLOW_ID, MISSING_RUN_ID) } returns null

            val invalidWorkflow = client.get("/v1/workflows/nope/instances/$RUN_RESOURCE_ID") {
                withAuth(token())
            }
            val invalidInstance = client.get("/v1/workflows/$WORKFLOW_RESOURCE_ID/instances/nope") {
                withAuth(token())
            }
            val missingDetail = client.get(
                "/v1/workflows/$WORKFLOW_RESOURCE_ID/instances/$MISSING_RUN_RESOURCE_ID"
            ) {
                withAuth(token())
            }
            val missingCancel = client.put(
                "/v1/workflows/$WORKFLOW_RESOURCE_ID/instances/$MISSING_RUN_RESOURCE_ID/cancel"
            ) {
                withAuth(token())
            }

            assertEquals(HttpStatusCode.BadRequest, invalidWorkflow.status)
            assertEquals(HttpStatusCode.BadRequest, invalidInstance.status)
            assertEquals(HttpStatusCode.NotFound, missingDetail.status)
            assertEquals(HttpStatusCode.NotFound, missingCancel.status)
        }

    @Test
    fun `webhook signing route requires admin role and returns signing metadata`() =
        testApplication {
            setupApp()
            val memberToken = RouteTestSupport.createToken(userId = memberUserId, orgId = organizationId)
            every {
                workflowService.webhookSigningInfo(organizationId, WORKFLOW_ID)
            } returns WorkflowWebhookSigningResponse(
                workflowId = WORKFLOW_RESOURCE_ID,
                webhookUrl = "https://api.moneat.io/v1/workflows/$WORKFLOW_RESOURCE_ID/webhook",
                signingSecret = "secret",
                signatureHeader = "X-Moneat-Workflow-Signature",
                signatureFormat = "sha256=<hex HMAC-SHA256 of raw body>"
            )

            val forbidden = client.get("/v1/workflows/$WORKFLOW_RESOURCE_ID/webhook-signing") {
                withAuth(memberToken)
            }
            val response = client.get("/v1/workflows/$WORKFLOW_RESOURCE_ID/webhook-signing") {
                withAuth(token())
            }

            assertEquals(HttpStatusCode.Forbidden, forbidden.status)
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("X-Moneat-Workflow-Signature"))
        }

    @Test
    fun `signed webhook route validates signature before creating a run`() =
        testApplication {
            setupApp()
            every { workflowService.verifyWebhookSignature(WORKFLOW_ID, "{}", null) } returns false
            every {
                workflowService.verifyWebhookSignature(WORKFLOW_ID, "{}", "sha256=valid")
            } returns true
            coEvery {
                workflowService.createWebhookRun(WORKFLOW_ID, "{}", "event-1")
            } returns runResponse(status = "pending")

            val unsigned = client.post("/v1/workflows/$WORKFLOW_RESOURCE_ID/webhook") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            val signed = client.post("/v1/workflows/$WORKFLOW_RESOURCE_ID/webhook") {
                header("X-Moneat-Workflow-Signature", "sha256=valid")
                header("X-Moneat-Webhook-Event", "event-1")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }

            assertEquals(HttpStatusCode.Unauthorized, unsigned.status)
            assertEquals(HttpStatusCode.Accepted, signed.status)
            assertTrue(signed.bodyAsText().contains("pending"))
            coVerify { workflowService.createWebhookRun(WORKFLOW_ID, "{}", "event-1") }
        }

    private fun ApplicationTestBuilder.setupApp() {
        application {
            installJwtAuth()
            routing { workflowRoutes() }
        }
    }

    private fun token(): String =
        RouteTestSupport.createToken(userId = userId, orgId = organizationId)

    private fun stubWorkflowResourceResolution() {
        every { workflowService.resolveWorkflowId(organizationId, Uuid.parse(WORKFLOW_RESOURCE_ID)) } returns
            WORKFLOW_ID
        every { workflowService.resolveWorkflowId(organizationId, Uuid.parse(MISSING_WORKFLOW_RESOURCE_ID)) } returns
            MISSING_WORKFLOW_ID
        every { workflowService.resolveWorkflowId(organizationId, Uuid.parse(DEFAULT_WORKFLOW_RESOURCE_ID)) } returns
            DEFAULT_WORKFLOW_ID
        every { workflowService.resolveWorkflowId(Uuid.parse(WORKFLOW_RESOURCE_ID)) } returns WORKFLOW_ID
        every { workflowService.resolveRunId(organizationId, WORKFLOW_ID, Uuid.parse(RUN_RESOURCE_ID)) } returns RUN_ID
        every {
            workflowService.resolveRunId(organizationId, WORKFLOW_ID, Uuid.parse(MISSING_RUN_RESOURCE_ID))
        } returns MISSING_RUN_ID
    }

    private fun seedUser(email: String): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hash"
                it[name] = email.substringBefore("@")
                it[email_verified] = true
            } get Users.id
        }

    private fun seedOrganization(name: String): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedMembership(
        orgId: Int,
        memberUserId: Int,
        role: String
    ) {
        transaction {
            Memberships.insert {
                it[organization_id] = orgId
                it[user_id] = memberUserId
                it[Memberships.role] = role
            }
        }
    }

    private fun createRequest(name: String): CreateWorkflowRequest =
        CreateWorkflowRequest(
            name = name,
            triggerName = "alert.triggered",
            steps = listOf(WorkflowStepConfig("notification.email_org", mapOf("subject" to "Alert")))
        )

    private fun workflowResponse(name: String = "Route workflow"): WorkflowResponse =
        WorkflowResponse(
            id = WORKFLOW_RESOURCE_ID,
            name = name,
            triggerName = "alert.triggered",
            enabled = true,
            version = 1,
            published = true,
            conditions = emptyList(),
            steps = listOf(WorkflowStepConfig("notification.email_org", mapOf("subject" to "Alert"))),
            graph = LinearGraphAdapter.graphFromLegacy(
                "alert.triggered",
                emptyList(),
                listOf(WorkflowStepConfig("notification.email_org", mapOf("subject" to "Alert")))
            ),
            onceForTemplate = listOf("alert.deduplication_key"),
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )

    private fun runResponse(status: String = "complete"): WorkflowRunResponse =
        WorkflowRunResponse(
            id = RUN_RESOURCE_ID,
            workflowId = WORKFLOW_RESOURCE_ID,
            workflowVersionId = VERSION_RESOURCE_ID,
            triggerName = "alert.triggered",
            onceFor = "alert.deduplication_key=host-1",
            status = status,
            progress = emptyList(),
            createdAt = "2026-01-01T00:00:00Z"
        )
}
