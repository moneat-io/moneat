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

import com.moneat.org.repositories.OrgMembershipRepository
import com.moneat.org.repositories.OrgMembershipRepositoryImpl
import com.moneat.org.services.OrgMembershipService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.stopTestKoin
import com.moneat.workflows.engine.temporal.LinearGraphAdapter
import com.moneat.workflows.models.InstantiateBlueprintRequest
import com.moneat.workflows.models.WorkflowAuditEventResponse
import com.moneat.workflows.models.WorkflowExportResponse
import com.moneat.workflows.models.WorkflowGraphResource
import com.moneat.workflows.models.WorkflowImportRequest
import com.moneat.workflows.models.WorkflowOverviewResponse
import com.moneat.workflows.models.WorkflowResponse
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.WorkflowUsageResponse
import com.moneat.workflows.services.WorkflowBlueprintCatalog
import com.moneat.workflows.services.WorkflowGovernanceService
import com.moneat.workflows.services.WorkflowService
import io.ktor.client.request.get
import io.ktor.client.request.post
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

class WorkflowGovernanceRoutesTest {
    companion object {
        private var db: Database? = null
        private const val ORGANIZATION_NAME = "Workflow Governance Routes"
        private const val WORKFLOW_ID = 55
    }

    private val json = Json { encodeDefaults = true }
    private lateinit var workflowService: WorkflowService
    private lateinit var governanceService: WorkflowGovernanceService
    private var userId: Int = 0
    private var memberUserId: Int = 0
    private var organizationId: Int = 0

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_workflow_governance_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships)
        userId = seedUser("owner@workflow-governance.test")
        memberUserId = seedUser("member@workflow-governance.test")
        organizationId = seedOrganization(ORGANIZATION_NAME)
        seedMembership(organizationId, userId, "owner")
        seedMembership(organizationId, memberUserId, "member")

        stopTestKoin()
        workflowService = mockk()
        governanceService = mockk()
        startKoin {
            modules(
                module {
                    single<OrgMembershipRepository> { OrgMembershipRepositoryImpl() }
                    single { OrgMembershipService(get()) }
                    single { workflowService }
                    single { governanceService }
                }
            )
        }
    }

    @AfterTest
    fun teardown() {
        stopTestKoin()
    }

    @Test
    fun `blueprint catalog routes require organization and surface curated blueprints`() =
        testApplication {
            setupApp()
            val sample = WorkflowBlueprintCatalog.list().first()

            val unauthenticated = client.get("/v1/workflows/blueprints")
            val noOrg = client.get("/v1/workflows/blueprints") {
                withAuth(RouteTestSupport.createToken(userId = userId))
            }
            val list = client.get("/v1/workflows/blueprints") { withAuth(token()) }
            val detail = client.get("/v1/workflows/blueprints/${sample.key}") { withAuth(token()) }
            val missing = client.get("/v1/workflows/blueprints/not-a-real-blueprint") { withAuth(token()) }

            assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
            assertEquals(HttpStatusCode.Forbidden, noOrg.status)
            assertEquals(HttpStatusCode.OK, list.status)
            assertTrue(list.bodyAsText().contains(sample.key))
            assertEquals(HttpStatusCode.OK, detail.status)
            assertTrue(detail.bodyAsText().contains("\"trigger_name\""))
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertTrue(missing.bodyAsText().contains("Workflow blueprint not found"))
        }

    @Test
    fun `instantiate blueprint requires admin and maps service outcomes`() =
        testApplication {
            setupApp()
            val memberToken = RouteTestSupport.createToken(userId = memberUserId, orgId = organizationId)
            every {
                governanceService.instantiateBlueprint(organizationId, "alerting-basic", null, userId)
            } returns workflowResponse(name = "Alerting basic")
            every {
                governanceService.instantiateBlueprint(organizationId, "unknown", null, userId)
            } throws IllegalArgumentException("Unknown workflow blueprint unknown")

            val forbidden = client.post("/v1/workflows/blueprints/alerting-basic/instantiate") {
                withAuth(memberToken)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(InstantiateBlueprintRequest()))
            }
            val created = client.post("/v1/workflows/blueprints/alerting-basic/instantiate") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(InstantiateBlueprintRequest()))
            }
            val invalid = client.post("/v1/workflows/blueprints/unknown/instantiate") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(InstantiateBlueprintRequest()))
            }

            assertEquals(HttpStatusCode.Forbidden, forbidden.status)
            assertEquals(HttpStatusCode.Created, created.status)
            assertTrue(created.bodyAsText().contains("Alerting basic"))
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertTrue(invalid.bodyAsText().contains("Unknown workflow blueprint"))
            verify(exactly = 0) {
                governanceService.instantiateBlueprint(organizationId, "alerting-basic", null, memberUserId)
            }
        }

    @Test
    fun `overview and usage routes return organization summaries`() =
        testApplication {
            setupApp()
            every { governanceService.overview(organizationId, any()) } returns overviewResponse()
            every { governanceService.usage(organizationId, any()) } returns usageResponse()

            val noOrg = client.get("/v1/workflows/overview") {
                withAuth(RouteTestSupport.createToken(userId = userId))
            }
            val overview = client.get("/v1/workflows/overview") { withAuth(token()) }
            val usage = client.get("/v1/workflows/usage") { withAuth(token()) }

            assertEquals(HttpStatusCode.Forbidden, noOrg.status)
            assertEquals(HttpStatusCode.OK, overview.status)
            assertTrue(overview.bodyAsText().contains("\"total_workflows\":3"))
            assertEquals(HttpStatusCode.OK, usage.status)
            assertTrue(usage.bodyAsText().contains("\"unlimited\":false"))
            verify { governanceService.overview(organizationId, any()) }
            verify { governanceService.usage(organizationId, any()) }
        }

    @Test
    fun `audit routes bound the limit and validate workflow id`() =
        testApplication {
            setupApp()
            every { governanceService.listAudit(organizationId, null, 100) } returns listOf(auditResponse())
            every { governanceService.listAudit(organizationId, null, 500) } returns listOf(auditResponse())
            every { governanceService.listAudit(organizationId, WORKFLOW_ID, 100) } returns listOf(auditResponse())

            val orgAudit = client.get("/v1/workflows/audit") { withAuth(token()) }
            val clampedAudit = client.get("/v1/workflows/audit?limit=100000") { withAuth(token()) }
            val invalidWorkflow = client.get("/v1/workflows/not-a-number/audit") { withAuth(token()) }
            val workflowAudit = client.get("/v1/workflows/$WORKFLOW_ID/audit") { withAuth(token()) }

            assertEquals(HttpStatusCode.OK, orgAudit.status)
            assertTrue(orgAudit.bodyAsText().contains("run_started"))
            assertEquals(HttpStatusCode.OK, clampedAudit.status)
            assertEquals(HttpStatusCode.BadRequest, invalidWorkflow.status)
            assertEquals(HttpStatusCode.OK, workflowAudit.status)
            verify { governanceService.listAudit(organizationId, null, 500) }
            verify { governanceService.listAudit(organizationId, WORKFLOW_ID, 100) }
        }

    @Test
    fun `export route validates id and maps missing workflows`() =
        testApplication {
            setupApp()
            every { governanceService.export(organizationId, WORKFLOW_ID, userId) } returns exportResponse()
            every { governanceService.export(organizationId, WORKFLOW_ID + 1, userId) } returns null

            val invalidId = client.get("/v1/workflows/nope/export") { withAuth(token()) }
            val missing = client.get("/v1/workflows/${WORKFLOW_ID + 1}/export") { withAuth(token()) }
            val exported = client.get("/v1/workflows/$WORKFLOW_ID/export") { withAuth(token()) }

            assertEquals(HttpStatusCode.BadRequest, invalidId.status)
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals(HttpStatusCode.OK, exported.status)
            assertTrue(exported.bodyAsText().contains("moneat_workflow"))
            assertTrue(exported.bodyAsText().contains("\"schema_version\":1"))
        }

    @Test
    fun `import route requires admin and maps service outcomes`() =
        testApplication {
            setupApp()
            val memberToken = RouteTestSupport.createToken(userId = memberUserId, orgId = organizationId)
            val request = importRequest("Imported workflow")
            val invalidRequest = importRequest("   ")
            every { governanceService.import(organizationId, request, userId) } returns
                workflowResponse(name = "Imported workflow")
            every {
                governanceService.import(organizationId, invalidRequest, userId)
            } throws IllegalArgumentException("Workflow name is required")

            val forbidden = client.post("/v1/workflows/import") {
                withAuth(memberToken)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
            }
            val imported = client.post("/v1/workflows/import") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
            }
            val invalid = client.post("/v1/workflows/import") {
                withAuth(token())
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(invalidRequest))
            }

            assertEquals(HttpStatusCode.Forbidden, forbidden.status)
            assertEquals(HttpStatusCode.OK, imported.status)
            assertTrue(imported.bodyAsText().contains("Imported workflow"))
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertTrue(invalid.bodyAsText().contains("Workflow name is required"))
            verify(exactly = 0) { governanceService.import(organizationId, request, memberUserId) }
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

    private fun workflowResponse(name: String = "Governance workflow"): WorkflowResponse =
        WorkflowResponse(
            id = WORKFLOW_ID,
            name = name,
            triggerName = "alert.triggered",
            enabled = false,
            version = 1,
            published = false,
            conditions = emptyList(),
            steps = listOf(WorkflowStepConfig("notification.email_org", mapOf("subject" to "Alert"))),
            graph = sampleGraph(),
            onceForTemplate = listOf("alert.deduplication_key"),
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )

    private fun overviewResponse(): WorkflowOverviewResponse =
        WorkflowOverviewResponse(
            totalWorkflows = 3,
            enabledWorkflows = 2,
            publishedWorkflows = 2,
            runsLast30d = 12,
            successRate = 0.75,
            failedLast30d = 3
        )

    private fun usageResponse(): WorkflowUsageResponse =
        WorkflowUsageResponse(period = "2026-01", used = 12, limit = 1000, remaining = 988, unlimited = false)

    private fun auditResponse(): WorkflowAuditEventResponse =
        WorkflowAuditEventResponse(
            id = 1,
            workflowId = WORKFLOW_ID,
            action = "run_started",
            createdAt = "2026-01-01T00:00:00Z"
        )

    private fun exportResponse(): WorkflowExportResponse =
        WorkflowExportResponse(
            schemaVersion = 1,
            resource = WorkflowGraphResource(
                name = "Governance workflow",
                triggerName = "alert.triggered",
                enabled = false,
                graph = sampleGraph(),
                onceForTemplate = listOf("alert.deduplication_key")
            ),
            terraform = "resource \"moneat_workflow\" \"governance_workflow\" {}"
        )

    private fun importRequest(name: String): WorkflowImportRequest =
        WorkflowImportRequest(
            name = name,
            triggerName = "alert.triggered",
            graph = sampleGraph(),
            enabled = false,
            onceForTemplate = listOf("alert.deduplication_key")
        )

    private fun sampleGraph() =
        LinearGraphAdapter.graphFromLegacy(
            "alert.triggered",
            emptyList(),
            listOf(WorkflowStepConfig("notification.email_org", mapOf("subject" to "Alert")))
        )
}
