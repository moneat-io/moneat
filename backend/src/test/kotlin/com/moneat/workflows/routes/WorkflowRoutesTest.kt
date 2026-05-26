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
import com.moneat.workflows.engine.WorkflowCatalog
import com.moneat.workflows.models.CreateWorkflowRequest
import com.moneat.workflows.models.UpdateWorkflowRequest
import com.moneat.workflows.models.WorkflowConditionConfig
import com.moneat.workflows.models.WorkflowResponse
import com.moneat.workflows.models.WorkflowRunResponse
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.services.WorkflowService
import io.ktor.client.request.delete
import io.ktor.client.request.get
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

class WorkflowRoutesTest {
    companion object {
        private var db: Database? = null
        private const val ORGANIZATION_NAME = "Workflow Routes"
        private const val WORKFLOW_ID = 42
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
        startKoin {
            modules(
                module {
                    single { workflowService }
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
                withAuth(RouteTestSupport.createToken(userId = userId + 100))
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
    fun `get route validates workflow id and handles missing or found workflows`() =
        testApplication {
            setupApp()
            every { workflowService.getWorkflow(organizationId, WORKFLOW_ID) } returns workflowResponse()
            every { workflowService.getWorkflow(organizationId, WORKFLOW_ID + 1) } returns null

            val invalid = client.get("/v1/workflows/not-a-number") {
                withAuth(token())
            }
            val missing = client.get("/v1/workflows/${WORKFLOW_ID + 1}") {
                withAuth(token())
            }
            val found = client.get("/v1/workflows/$WORKFLOW_ID") {
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
                workflowService.updateWorkflow(organizationId, WORKFLOW_ID + 1, renameRequest)
            } returns null
            every {
                workflowService.updateWorkflow(organizationId, WORKFLOW_ID + 2, invalidConfigRequest)
            } throws IllegalArgumentException("Unknown workflow condition reference")

            val invalidId = client.put("/v1/workflows/nope") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(renameRequest))
            }
            val missing = client.put("/v1/workflows/${WORKFLOW_ID + 1}") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(renameRequest))
            }
            val invalidConfig = client.put("/v1/workflows/${WORKFLOW_ID + 2}") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(invalidConfigRequest))
            }
            val updated = client.put("/v1/workflows/$WORKFLOW_ID") {
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
            every { workflowService.deleteWorkflow(organizationId, WORKFLOW_ID + 1) } returns false
            every {
                workflowService.deleteWorkflow(organizationId, WORKFLOW_ID + 2)
            } throws IllegalArgumentException("Default workflows cannot be modified")

            val invalidId = client.delete("/v1/workflows/nope") {
                withAuth(token())
            }
            val missing = client.delete("/v1/workflows/${WORKFLOW_ID + 1}") {
                withAuth(token())
            }
            val deleted = client.delete("/v1/workflows/$WORKFLOW_ID") {
                withAuth(token())
            }
            val defaultWorkflow = client.delete("/v1/workflows/${WORKFLOW_ID + 2}") {
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
            val lowerBound = client.get("/v1/workflows/$WORKFLOW_ID/runs?limit=0") {
                withAuth(token())
            }
            val defaultLimit = client.get("/v1/workflows/$WORKFLOW_ID/runs?limit=not-a-number") {
                withAuth(token())
            }
            val upperBound = client.get("/v1/workflows/$WORKFLOW_ID/runs?limit=1000") {
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

    private fun ApplicationTestBuilder.setupApp() {
        application {
            installJwtAuth()
            routing { workflowRoutes() }
        }
    }

    private fun token(): String =
        RouteTestSupport.createToken(userId = userId, orgId = organizationId)

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
            id = WORKFLOW_ID,
            name = name,
            triggerName = "alert.triggered",
            enabled = true,
            version = 1,
            conditions = emptyList(),
            steps = listOf(WorkflowStepConfig("notification.email_org", mapOf("subject" to "Alert"))),
            onceForTemplate = listOf("alert.deduplication_key"),
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )

    private fun runResponse(status: String = "complete"): WorkflowRunResponse =
        WorkflowRunResponse(
            id = 7,
            workflowId = WORKFLOW_ID,
            workflowVersionId = 1,
            triggerName = "alert.triggered",
            onceFor = "alert.deduplication_key=host-1",
            status = status,
            progress = emptyList(),
            createdAt = "2026-01-01T00:00:00Z"
        )
}
