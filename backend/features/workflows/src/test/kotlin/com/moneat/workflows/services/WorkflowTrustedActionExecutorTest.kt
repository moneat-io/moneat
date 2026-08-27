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

package com.moneat.workflows.services

import com.moneat.enterprise.FeatureRegistry
import com.moneat.enterprise.OnCallBridge
import com.moneat.enterprise.OnCallIncidentActionRequest
import com.moneat.enterprise.OnCallIncidentDeclaration
import com.moneat.events.services.DashboardService
import com.moneat.logs.models.LogAggregateResponse
import com.moneat.logs.models.LogQueryResponse
import com.moneat.logs.services.LogService
import com.moneat.monitor.models.HistoricalMetricsResponse
import com.moneat.monitor.models.SilencePeriodResponse
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.statuspage.models.IncidentResponse
import com.moneat.statuspage.services.StatusPageService
import com.moneat.testsupport.TestDatabaseHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class WorkflowTrustedActionExecutorTest {
    companion object {
        private var db: Database? = null
    }

    private val logService = mockk<LogService>()
    private val dashboardService = mockk<DashboardService>()
    private val monitorService = mockk<MonitorService>()
    private val statusPageService = mockk<StatusPageService>()
    private val monitorAlertService = mockk<MonitorAlertService>()

    private lateinit var executor: WorkflowTrustedActionExecutor
    private var orgId: Int = 0
    private var otherOrgId: Int = 0
    private var projectId: Long = 0
    private var projectResourceId: Uuid = Uuid.random()
    private var hostId: Int = 0
    private var hostResourceId: Uuid = Uuid.random()
    private var otherHostResourceId: Uuid = Uuid.random()

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_trusted_actions;MODE=MYSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.dropAndPatchJsonb(Users, Organizations, Memberships, Projects, Hosts)
        transaction {
            SchemaUtils.create(Users, Organizations, Memberships, Projects, Hosts)
            val userId =
                Users.insert {
                    it[email] = "actor@moneat.io"
                    it[password_hash] = "x"
                    it[email_verified] = true
                } get Users.id
            orgId =
                Organizations.insert {
                    it[name] = "Org"
                    it[slug] = "org"
                } get Organizations.id
            otherOrgId =
                Organizations.insert {
                    it[name] = "Other"
                    it[slug] = "other"
                } get Organizations.id
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
            projectId =
                Projects.insert {
                    projectResourceId = Uuid.parse("33333333-3333-4333-8333-333333333333")
                    it[resource_id] = projectResourceId
                    it[organization_id] = orgId
                    it[name] = "Checkout"
                    it[slug] = "checkout"
                } get Projects.id
            val now = Clock.System.now()
            hostResourceId = Uuid.parse("11111111-1111-4111-8111-111111111111")
            otherHostResourceId = Uuid.parse("22222222-2222-4222-8222-222222222222")
            hostId =
                Hosts.insert {
                    it[resource_id] = hostResourceId
                    it[organization_id] = orgId
                    it[hostname] = "checkout-host"
                    it[first_seen_at] = now
                    it[last_seen_at] = now
                } get Hosts.id
            Hosts.insert {
                it[resource_id] = otherHostResourceId
                it[organization_id] = otherOrgId
                it[hostname] = "other-host"
                it[first_seen_at] = now
                it[last_seen_at] = now
            }
        }
        executor =
            WorkflowTrustedActionExecutor(
                logService = logService,
                dashboardService = dashboardService,
                monitorService = monitorService,
                monitorAlertServiceProvider = { monitorAlertService },
                statusPageService = statusPageService,
                nativeIncidentEntitlement = { true },
            )
    }

    private fun run(
        stepName: String,
        params: Map<String, String>,
        organizationId: Int = orgId,
        actorUserId: Int? = null,
        idempotencyKey: String? = null,
    ): Map<String, JsonElement> =
        runBlocking { executor.execute(organizationId, stepName, params, actorUserId, idempotencyKey) }

    // ──── supports / dispatch ────

    @Test
    fun `supports recognizes trusted actions only`() {
        assertTrue(executor.supports(WorkflowTrustedActionExecutor.LOGS_SEARCH_STEP))
        assertTrue(executor.supports(WorkflowTrustedActionExecutor.ON_CALL_PAGE_STEP))
        assertTrue(executor.supports(WorkflowTrustedActionExecutor.ON_CALL_DECLARE_INCIDENT_STEP))
        assertTrue(executor.supports(WorkflowTrustedActionExecutor.ON_CALL_CREATE_INCIDENT_ACTION_STEP))
        assertFalse(executor.supports("notification.slack"))
    }

    @Test
    fun `unknown step throws`() {
        assertFailsWith<IllegalArgumentException> { run("no.such.step", emptyMap()) }
    }

    // ──── log search / aggregate ────

    @Test
    fun `log search applies defaults and returns logs key`() {
        coEvery { logService.queryLogs(any(), any()) } returns
            LogQueryResponse(logs = emptyList(), hasMore = false)
        val result = run(WorkflowTrustedActionExecutor.LOGS_SEARCH_STEP, mapOf("levels" to "ERROR, warn ,"))
        assertTrue(result.containsKey("logs"))
    }

    @Test
    fun `log aggregate returns aggregate key`() {
        coEvery {
            logService.aggregateLogs(
                any(), any(), any(), any()
            )
        } returns LogAggregateResponse(buckets = emptyList(), totalCount = 0, interval = "1h")
        val result = run(WorkflowTrustedActionExecutor.LOGS_AGGREGATE_STEP, mapOf("query" to "level:error"))
        assertTrue(result.containsKey("aggregate"))
    }

    // ──── metrics query ────

    @Test
    fun `metrics query requires the host to belong to the organization`() {
        assertFailsWith<IllegalArgumentException> {
            run(WorkflowTrustedActionExecutor.METRICS_QUERY_STEP, mapOf("host_id" to otherHostResourceId.toString()))
        }
    }

    @Test
    fun `metrics query succeeds for an in-org host`() {
        coEvery { monitorService.getHistoricalMetrics(any(), any(), any(), any()) } returns
            HistoricalMetricsResponse(
                systemId = "sys",
                hostId = hostResourceId.toString(),
                from = 0,
                to = 1,
                intervalSeconds = 60,
                dataPoints = emptyList()
            )
        val result =
            run(
                WorkflowTrustedActionExecutor.METRICS_QUERY_STEP,
                mapOf("host_id" to hostResourceId.toString(), "hours" to "1")
            )
        assertTrue(result.containsKey("metrics"))
        coVerify(exactly = 1) {
            monitorService.getHistoricalMetrics(hostId, any(), any(), intervalSeconds = null)
        }
    }

    @Test
    fun `metrics query rejects a non-uuid host id`() {
        assertFailsWith<IllegalArgumentException> {
            run(WorkflowTrustedActionExecutor.METRICS_QUERY_STEP, mapOf("host_id" to "abc"))
        }
    }

    // ──── traces / span / issues (project access) ────

    @Test
    fun `trace search rejects projects outside the organization`() {
        assertFailsWith<IllegalArgumentException> {
            run(
                WorkflowTrustedActionExecutor.TRACES_SEARCH_STEP,
                mapOf("project_id" to projectResourceId.toString()),
                organizationId = otherOrgId
            )
        }
    }

    @Test
    fun `trace search succeeds for an accessible project`() {
        coEvery { dashboardService.getTransactions(any(), any(), any(), any()) } returns emptyList()
        val result = run(
            WorkflowTrustedActionExecutor.TRACES_SEARCH_STEP,
            mapOf("project_id" to projectResourceId.toString())
        )
        assertTrue(result.containsKey("traces"))
    }

    @Test
    fun `issues list applies pagination defaults`() {
        coEvery { dashboardService.getIssues(any(), any(), any(), any()) } returns emptyList()
        val result =
            run(
                WorkflowTrustedActionExecutor.ISSUES_LIST_STEP,
                mapOf("project_id" to projectResourceId.toString(), "status" to "unresolved")
            )
        assertTrue(result.containsKey("issues"))
    }

    @Test
    fun `span get requires the span id parameter`() {
        assertFailsWith<IllegalArgumentException> {
            run(WorkflowTrustedActionExecutor.SPAN_GET_STEP, mapOf("project_id" to projectResourceId.toString()))
        }
    }

    @Test
    fun `trace search rejects a non-uuid project id`() {
        assertFailsWith<IllegalArgumentException> {
            run(WorkflowTrustedActionExecutor.TRACES_SEARCH_STEP, mapOf("project_id" to projectId.toString()))
        }
    }

    // ──── status page ────

    @Test
    fun `status page update throws when the page is missing`() {
        every { statusPageService.updateStatusPage(any(), any(), any()) } returns null
        assertFailsWith<IllegalArgumentException> {
            run(
                WorkflowTrustedActionExecutor.STATUS_PAGE_UPDATE_STEP,
                mapOf(
                    "status_page_id" to "11111111-1111-1111-1111-111111111111",
                    "is_public" to "true"
                )
            )
        }
    }

    @Test
    fun `status page incident create returns incident key`() {
        every { statusPageService.createIncident(any(), any(), any()) } returns
            IncidentResponse(
                id = "inc-1",
                statusPageId = "page-1",
                title = "Outage",
                status = "investigating",
                type = "incident",
                impact = "minor",
                createdAt = "2026-05-29T00:00:00Z",
                updatedAt = "2026-05-29T00:00:00Z",
                updates = emptyList()
            )
        val result =
            run(
                WorkflowTrustedActionExecutor.STATUS_PAGE_INCIDENT_CREATE_STEP,
                mapOf(
                    "status_page_id" to "11111111-1111-1111-1111-111111111111",
                    "title" to "Outage",
                    "message" to "We are investigating"
                )
            )
        assertTrue(result.containsKey("incident"))
    }

    @Test
    fun `status page update rejects an invalid boolean parameter`() {
        assertFailsWith<IllegalArgumentException> {
            run(
                WorkflowTrustedActionExecutor.STATUS_PAGE_UPDATE_STEP,
                mapOf(
                    "status_page_id" to "11111111-1111-1111-1111-111111111111",
                    "is_public" to "maybe"
                )
            )
        }
    }

    // ──── alert silence ────

    @Test
    fun `alert silence rejects a window that ends before it starts`() {
        assertFailsWith<IllegalArgumentException> {
            run(
                WorkflowTrustedActionExecutor.ALERT_SILENCE_STEP,
                mapOf("starts_at" to "2000", "ends_at" to "1000")
            )
        }
    }

    @Test
    fun `alert silence falls back to an org member when no actor is supplied`() {
        every { monitorAlertService.createSilencePeriod(any(), any(), any()) } returns silenceResponse()
        val result = run(WorkflowTrustedActionExecutor.ALERT_SILENCE_STEP, emptyMap())
        assertTrue(result.containsKey("silence"))
    }

    @Test
    fun `alert silence uses the explicit actor when provided`() {
        every { monitorAlertService.createSilencePeriod(any(), any(), any()) } returns silenceResponse()
        val result =
            run(
                WorkflowTrustedActionExecutor.ALERT_SILENCE_STEP,
                mapOf("reason" to "maintenance"),
                actorUserId = 99
            )
        assertTrue(result.containsKey("silence"))
    }

    @Test
    fun `alert silence rejects a non-numeric timestamp`() {
        assertFailsWith<IllegalArgumentException> {
            run(WorkflowTrustedActionExecutor.ALERT_SILENCE_STEP, mapOf("starts_at" to "soon"))
        }
    }

    // ──── on-call paging (no enterprise bridge) ────

    @Test
    fun `on-call paging reports missing enterprise bridge`() {
        val result =
            run(
                WorkflowTrustedActionExecutor.ON_CALL_PAGE_STEP,
                mapOf("escalation_policy_id" to "1", "title" to "Page")
            )
        assertEquals(true, result["requires_enterprise"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `on-call incident declaration reports missing enterprise bridge`() {
        val result =
            run(
                WorkflowTrustedActionExecutor.ON_CALL_DECLARE_INCIDENT_STEP,
                mapOf("title" to "Checkout outage", "incident_severity" to "SEV-1")
            )

        assertEquals(true, result["requires_enterprise"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `on-call incident declaration fails closed before invoking enterprise bridge`() {
        val bridge = mockk<OnCallBridge>()
        val disabledExecutor =
            WorkflowTrustedActionExecutor(
                logService = logService,
                dashboardService = dashboardService,
                monitorService = monitorService,
                monitorAlertServiceProvider = { monitorAlertService },
                statusPageService = statusPageService,
                nativeIncidentEntitlement = { false },
            )
        mockkObject(FeatureRegistry)
        try {
            every { FeatureRegistry.getOnCallBridge() } returns bridge

            assertFailsWith<IllegalStateException> {
                runBlocking {
                    disabledExecutor.execute(
                        orgId,
                        WorkflowTrustedActionExecutor.ON_CALL_DECLARE_INCIDENT_STEP,
                        mapOf("title" to "Checkout outage", "incident_severity" to "SEV-1"),
                        actorUserId = 99,
                    )
                }
            }
            coVerify(exactly = 0) { bridge.declareIncident(any()) }
        } finally {
            unmockkObject(FeatureRegistry)
        }
    }

    @Test
    fun `on-call incident declaration sends explicit canonical severity`() {
        val bridge = mockk<OnCallBridge>()
        val incidentResourceId = "77777777-7777-4777-8777-777777777777"
        val declaration =
            OnCallIncidentDeclaration(
                organizationId = orgId,
                userId = 99,
                alertId = null,
                title = "Checkout outage",
                description = "Investigating elevated checkout errors",
                severity = "SEV-1",
                commandKey = "workflow:73:declare-incident",
            )
        mockkObject(FeatureRegistry)
        try {
            every { FeatureRegistry.getOnCallBridge() } returns bridge
            coEvery { bridge.declareIncident(declaration) } returns incidentResourceId

            val result =
                run(
                    WorkflowTrustedActionExecutor.ON_CALL_DECLARE_INCIDENT_STEP,
                    mapOf(
                        "title" to "Checkout outage",
                        "description" to "Investigating elevated checkout errors",
                        "incident_severity" to "sev1"
                    ),
                    actorUserId = 99,
                    idempotencyKey = declaration.commandKey,
                )

            assertEquals(incidentResourceId, result["incident_id"]?.jsonPrimitive?.content)
            coVerify(exactly = 1) { bridge.declareIncident(declaration) }
        } finally {
            unmockkObject(FeatureRegistry)
        }
    }

    @Test
    fun `on-call incident declaration resolves alert resource id`() {
        val bridge = mockk<OnCallBridge>()
        val alertResourceId = "66666666-6666-4666-8666-666666666666"
        val incidentResourceId = "77777777-7777-4777-8777-777777777777"
        val declaration =
            OnCallIncidentDeclaration(
                organizationId = orgId,
                userId = 99,
                alertId = 7,
                title = "Checkout outage",
                description = null,
                severity = "SEV-1",
                commandKey = "workflow:74:declare-from-alert",
            )
        mockkObject(FeatureRegistry)
        try {
            every { FeatureRegistry.getOnCallBridge() } returns bridge
            every { bridge.resolveAlertId(orgId, alertResourceId) } returns 7
            coEvery { bridge.declareIncident(declaration) } returns incidentResourceId

            val result =
                run(
                    WorkflowTrustedActionExecutor.ON_CALL_DECLARE_INCIDENT_STEP,
                    mapOf(
                        "title" to "Checkout outage",
                        "incident_severity" to "SEV-1",
                        "alert_id" to alertResourceId
                    ),
                    actorUserId = 99,
                    idempotencyKey = declaration.commandKey,
                )

            assertEquals(incidentResourceId, result["incident_id"]?.jsonPrimitive?.content)
            coVerify(exactly = 1) { bridge.declareIncident(declaration) }
        } finally {
            unmockkObject(FeatureRegistry)
        }
    }

    @Test
    fun `workflow incident action uses the canonical bridge request`() {
        val bridge = mockk<OnCallBridge>()
        val actionResourceId = "88888888-8888-4888-8888-888888888888"
        val request = OnCallIncidentActionRequest(
            organizationId = orgId,
            incidentResourceId = "77777777-7777-4777-8777-777777777777",
            userId = 99,
            description = "Verify the fallback provider",
            assigneeUserResourceId = "66666666-6666-4666-8666-666666666666",
            source = "WORKFLOW",
            commandKey = "workflow:75:incident-action",
        )
        mockkObject(FeatureRegistry)
        try {
            every { FeatureRegistry.getOnCallBridge() } returns bridge
            coEvery { bridge.createIncidentAction(request) } returns actionResourceId

            val result = run(
                WorkflowTrustedActionExecutor.ON_CALL_CREATE_INCIDENT_ACTION_STEP,
                mapOf(
                    "incident_id" to request.incidentResourceId,
                    "description" to request.description,
                    "assignee_user_id" to request.assigneeUserResourceId!!,
                ),
                actorUserId = request.userId,
                idempotencyKey = request.commandKey,
            )

            assertEquals(actionResourceId, result["action_id"]?.jsonPrimitive?.content)
            coVerify(exactly = 1) { bridge.createIncidentAction(request) }
        } finally {
            unmockkObject(FeatureRegistry)
        }
    }

    @Test
    fun `on-call incident action reports missing enterprise bridge`() {
        val result = run(
            WorkflowTrustedActionExecutor.ON_CALL_CREATE_INCIDENT_ACTION_STEP,
            mapOf(
                "incident_id" to "77777777-7777-4777-8777-777777777777",
                "description" to "Verify the fallback provider",
            ),
        )

        assertEquals(true, result["requires_enterprise"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `on-call incident action fails closed before invoking enterprise bridge`() {
        val bridge = mockk<OnCallBridge>()
        val disabledExecutor = WorkflowTrustedActionExecutor(
            logService = logService,
            dashboardService = dashboardService,
            monitorService = monitorService,
            monitorAlertServiceProvider = { monitorAlertService },
            statusPageService = statusPageService,
            nativeIncidentEntitlement = { false },
        )
        mockkObject(FeatureRegistry)
        try {
            every { FeatureRegistry.getOnCallBridge() } returns bridge

            assertFailsWith<IllegalStateException> {
                runBlocking {
                    disabledExecutor.execute(
                        orgId,
                        WorkflowTrustedActionExecutor.ON_CALL_CREATE_INCIDENT_ACTION_STEP,
                        mapOf(
                            "incident_id" to "77777777-7777-4777-8777-777777777777",
                            "description" to "Verify the fallback provider",
                        ),
                    )
                }
            }
            coVerify(exactly = 0) { bridge.createIncidentAction(any()) }
        } finally {
            unmockkObject(FeatureRegistry)
        }
    }

    @Test
    fun `on-call incident action rejects a bridge without a resource id`() {
        val bridge = mockk<OnCallBridge>()
        mockkObject(FeatureRegistry)
        try {
            every { FeatureRegistry.getOnCallBridge() } returns bridge
            coEvery { bridge.createIncidentAction(any()) } returns null

            assertFailsWith<IllegalStateException> {
                run(
                    WorkflowTrustedActionExecutor.ON_CALL_CREATE_INCIDENT_ACTION_STEP,
                    mapOf(
                        "incident_id" to "77777777-7777-4777-8777-777777777777",
                        "description" to "Verify the fallback provider",
                    ),
                )
            }
        } finally {
            unmockkObject(FeatureRegistry)
        }
    }

    // ──── Helpers ────

    private fun silenceResponse(): SilencePeriodResponse =
        SilencePeriodResponse(
            id = "33333333-3333-4333-8333-333333333333",
            organizationId = "44444444-4444-4444-8444-444444444444",
            reason = "Workflow automation",
            startsAt = 1,
            endsAt = 2,
            createdBy = "55555555-5555-4555-8555-555555555555",
            createdAt = 1
        )
}
