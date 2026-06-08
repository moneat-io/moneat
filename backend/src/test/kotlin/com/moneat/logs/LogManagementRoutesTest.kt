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

package com.moneat.logs

import com.moneat.dashboards.models.DashboardAlertResponse
import com.moneat.dashboards.services.DashboardAlertService
import com.moneat.logs.models.LogAggregateBucket
import com.moneat.logs.models.LogAggregateResponse
import com.moneat.logs.models.LogIndexUsageResponse
import com.moneat.logs.models.LogMetricRuleResponse
import com.moneat.logs.models.LogMonitorResponse
import com.moneat.logs.models.LogPipelinePreviewEntry
import com.moneat.logs.models.LogPipelinePreviewResult
import com.moneat.logs.models.LogPipelineResponse
import com.moneat.logs.models.LogPipelineStep
import com.moneat.logs.models.LogSavedViewResponse
import com.moneat.logs.models.LogSavedViewState
import com.moneat.logs.routes.logRoutes as installLogRoutes
import com.moneat.logs.services.LogIndexService
import com.moneat.logs.services.LogManagementService
import com.moneat.logs.services.LogService
import com.moneat.org.services.OrgMembershipService
import com.moneat.org.services.OrgRole
import com.moneat.otlp.services.OtlpApiKeyService
import com.moneat.otlp.services.OtlpServiceRoutingService
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TEST_ORG_ID = 7
private const val TEST_USER_ID = 42
private const val TEST_CREATED_AT = "2026-06-04T00:00:00Z"
private const val TEST_UPDATED_AT = "2026-06-04T00:01:00Z"

class LogManagementRoutesTest {
    private val logService = mockk<LogService>(relaxed = true)
    private val otlpApiKeyService = mockk<OtlpApiKeyService>(relaxed = true)
    private val logIndexService = mockk<LogIndexService>(relaxed = true)
    private val otlpServiceRoutingService = mockk<OtlpServiceRoutingService>(relaxed = true)
    private val logManagementService = mockk<LogManagementService>(relaxed = true)
    private val membershipService = mockk<OrgMembershipService>(relaxed = true)
    private val dashboardAlertService = mockk<DashboardAlertService>(relaxed = true)

    @BeforeTest
    fun setup() {
        clearMocks(
            logService,
            otlpApiKeyService,
            logIndexService,
            otlpServiceRoutingService,
            logManagementService,
            membershipService,
            dashboardAlertService
        )
        every { membershipService.requireRole(TEST_ORG_ID, TEST_USER_ID, OrgRole.ADMIN) } just Runs
    }

    // ──── Index management ────

    @Test
    fun `GET log permissions returns coarse fallback permissions`() =
        testApplication {
            application { installLogManagementRoutes() }

            val response = client.get("/v1/logs/permissions") { withAuth(token()) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("can_manage"))
            assertTrue(body.contains("can_live_tail"))
            assertTrue(body.contains("can_create_metrics"))
            assertTrue(body.contains("can_create_monitors"))
        }

    @Test
    fun `GET log index usage and POST retention run return index management payloads`() =
        testApplication {
            coEvery { logIndexService.usageStats(TEST_ORG_ID) } returns listOf(
                LogIndexUsageResponse(
                    indexName = "errors",
                    bytesToday = 1024,
                    countToday = 12,
                    quotaGb = 1.5f,
                    retentionDays = 14
                )
            )
            coEvery { logIndexService.enforceRetention(TEST_ORG_ID) } returns 2
            application { installLogManagementRoutes() }

            val usage = client.get("/v1/logs/indexes/usage") { withAuth(token()) }
            val retention = client.post("/v1/logs/indexes/retention/run") { withAuth(token()) }

            assertEquals(HttpStatusCode.OK, usage.status)
            assertTrue(usage.bodyAsText().contains("errors"))
            assertEquals(HttpStatusCode.OK, retention.status)
            assertTrue(retention.bodyAsText().contains("indexes_processed"))
        }

    @Test
    fun `POST retention run returns forbidden when admin fallback rejects`() =
        testApplication {
            every {
                membershipService.requireRole(TEST_ORG_ID, TEST_USER_ID, OrgRole.ADMIN)
            } throws IllegalStateException("no")
            application { installLogManagementRoutes() }

            val response = client.post("/v1/logs/indexes/retention/run") { withAuth(token()) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("Insufficient permissions"))
        }

    // ──── Pipelines ────

    @Test
    fun `pipeline routes list create update delete and preview`() =
        testApplication {
            val pipeline = pipelineResponse(id = 11, name = "Cleanup")
            every { logManagementService.listPipelines(TEST_ORG_ID) } returns listOf(pipeline)
            every { logManagementService.createPipeline(eq(TEST_ORG_ID), eq(TEST_USER_ID), any()) } returns pipeline
            every { logManagementService.updatePipeline(eq(TEST_ORG_ID), eq(11), any()) } returns pipeline
            every { logManagementService.deletePipeline(TEST_ORG_ID, 11) } returns true
            every { logManagementService.previewPipeline(any()) } returns listOf(
                LogPipelinePreviewResult(
                    before = LogPipelinePreviewEntry(message = "token=secret"),
                    after = LogPipelinePreviewEntry(message = "token=[redacted]"),
                    dropped = false
                )
            )
            application { installLogManagementRoutes() }

            val list = client.get("/v1/logs/pipelines") { withAuth(token()) }
            val create = client.postJson("/v1/logs/pipelines", """{"name":"Cleanup","steps":[]}""")
            val update = client.putJson("/v1/logs/pipelines/11", """{"name":"Cleanup v2"}""")
            val preview = client.postJson(
                "/v1/logs/pipelines/preview",
                """{"steps":[],"sample_logs":[{"message":"token=secret"}]}"""
            )
            val deleted = client.delete("/v1/logs/pipelines/11") { withAuth(token()) }

            assertEquals(HttpStatusCode.OK, list.status)
            assertTrue(list.bodyAsText().contains("Cleanup"))
            assertEquals(HttpStatusCode.Created, create.status)
            assertEquals(HttpStatusCode.OK, update.status)
            assertEquals(HttpStatusCode.OK, preview.status)
            assertTrue(preview.bodyAsText().contains("token=[redacted]"))
            assertEquals(HttpStatusCode.NoContent, deleted.status)
        }

    @Test
    fun `pipeline routes map invalid id not found and bad request`() =
        testApplication {
            every { logManagementService.createPipeline(eq(TEST_ORG_ID), eq(TEST_USER_ID), any()) } throws
                IllegalArgumentException("Pipeline name is required")
            every { logManagementService.updatePipeline(eq(TEST_ORG_ID), eq(99), any()) } returns null
            every { logManagementService.deletePipeline(TEST_ORG_ID, 99) } returns false
            application { installLogManagementRoutes() }

            val invalidId = client.putJson("/v1/logs/pipelines/bad", """{"name":"x"}""")
            val notFound = client.putJson("/v1/logs/pipelines/99", """{"name":"x"}""")
            val deleteMissing = client.delete("/v1/logs/pipelines/99") { withAuth(token()) }
            val badRequest = client.postJson("/v1/logs/pipelines", """{"name":""}""")

            assertEquals(HttpStatusCode.BadRequest, invalidId.status)
            assertEquals(HttpStatusCode.NotFound, notFound.status)
            assertEquals(HttpStatusCode.NotFound, deleteMissing.status)
            assertEquals(HttpStatusCode.BadRequest, badRequest.status)
        }

    // ──── Saved views ────

    @Test
    fun `saved view routes list create update and delete`() =
        testApplication {
            val view = savedViewResponse(id = 21, name = "Errors")
            every { logManagementService.listSavedViews(TEST_ORG_ID, TEST_USER_ID) } returns listOf(view)
            every { logManagementService.createSavedView(eq(TEST_ORG_ID), eq(TEST_USER_ID), any()) } returns view
            every {
                logManagementService.updateSavedView(eq(TEST_ORG_ID), eq(21), eq(TEST_USER_ID), any())
            } returns view
            every { logManagementService.deleteSavedView(TEST_ORG_ID, 21, TEST_USER_ID) } returns true
            application { installLogManagementRoutes() }

            val list = client.get("/v1/logs/saved-views") { withAuth(token()) }
            val create = client.postJson(
                "/v1/logs/saved-views",
                """{"name":"Errors","state":{"query":"level:error"},"is_shared":true}"""
            )
            val update = client.putJson("/v1/logs/saved-views/21", """{"name":"Errors v2"}""")
            val deleted = client.delete("/v1/logs/saved-views/21") { withAuth(token()) }

            assertEquals(HttpStatusCode.OK, list.status)
            assertEquals(HttpStatusCode.Created, create.status)
            assertEquals(HttpStatusCode.OK, update.status)
            assertEquals(HttpStatusCode.NoContent, deleted.status)
        }

    @Test
    fun `saved view routes map invalid id and missing view`() =
        testApplication {
            every {
                logManagementService.updateSavedView(eq(TEST_ORG_ID), eq(404), eq(TEST_USER_ID), any())
            } returns null
            every { logManagementService.deleteSavedView(TEST_ORG_ID, 404, TEST_USER_ID) } returns false
            application { installLogManagementRoutes() }

            val invalidId = client.putJson("/v1/logs/saved-views/nope", """{"name":"x"}""")
            val notFound = client.putJson("/v1/logs/saved-views/404", """{"name":"x"}""")
            val deleteMissing = client.delete("/v1/logs/saved-views/404") { withAuth(token()) }

            assertEquals(HttpStatusCode.BadRequest, invalidId.status)
            assertEquals(HttpStatusCode.NotFound, notFound.status)
            assertEquals(HttpStatusCode.NotFound, deleteMissing.status)
        }

    // ──── Metrics and monitors ────

    @Test
    fun `metric routes list create update delete preview and rollup`() =
        testApplication {
            val rule = metricRuleResponse(id = 31, name = "Errors")
            val aggregate = LogAggregateResponse(
                buckets = listOf(LogAggregateBucket(timestamp = TEST_CREATED_AT, count = 3)),
                totalCount = 3,
                interval = "5m"
            )
            every { logManagementService.listMetricRules(TEST_ORG_ID) } returns listOf(rule)
            every { logManagementService.createMetricRule(eq(TEST_ORG_ID), eq(TEST_USER_ID), any()) } returns rule
            every { logManagementService.updateMetricRule(eq(TEST_ORG_ID), eq(31), any()) } returns rule
            every { logManagementService.deleteMetricRule(TEST_ORG_ID, 31) } returns true
            every { logManagementService.getMetricRule(TEST_ORG_ID, 31) } returns rule
            coEvery {
                logService.aggregateLogs(
                    organizationId = any(),
                    from = any(),
                    to = any(),
                    interval = any(),
                    query = any(),
                    levels = any(),
                    service = any(),
                    environment = any(),
                    tags = any(),
                    excludeService = any(),
                    excludeEnvironment = any(),
                    excludeContainerName = any(),
                    excludeTags = any(),
                    groupBy = any()
                )
            } returns aggregate
            coEvery { logManagementService.recordMetricPoints(TEST_ORG_ID.toLong(), rule, aggregate) } returns 3
            application { installLogManagementRoutes() }

            val list = client.get("/v1/logs/metrics/rules") { withAuth(token()) }
            val create = client.postJson("/v1/logs/metrics/rules", """{"name":"Errors","group_by":"service"}""")
            val update = client.putJson("/v1/logs/metrics/rules/31", """{"name":"Errors v2"}""")
            val preview = client.postJson("/v1/logs/metrics/preview", """{"name":"Preview","interval":"2h"}""")
            val rollup = client.post("/v1/logs/metrics/rules/31/rollup") { withAuth(token()) }
            val deleted = client.delete("/v1/logs/metrics/rules/31") { withAuth(token()) }

            assertEquals(HttpStatusCode.OK, list.status)
            assertEquals(HttpStatusCode.Created, create.status)
            assertEquals(HttpStatusCode.OK, update.status)
            assertEquals(HttpStatusCode.OK, preview.status)
            assertTrue(preview.bodyAsText().contains("total_count"))
            assertEquals(HttpStatusCode.OK, rollup.status)
            assertTrue(rollup.bodyAsText().contains("points_inserted"))
            assertEquals(HttpStatusCode.NoContent, deleted.status)
        }

    @Test
    fun `metric routes map invalid id missing rule and bad request`() =
        testApplication {
            every { logManagementService.createMetricRule(eq(TEST_ORG_ID), eq(TEST_USER_ID), any()) } throws
                IllegalArgumentException("Metric rule name is required")
            every { logManagementService.updateMetricRule(eq(TEST_ORG_ID), eq(404), any()) } returns null
            every { logManagementService.deleteMetricRule(TEST_ORG_ID, 404) } returns false
            every { logManagementService.getMetricRule(TEST_ORG_ID, 404) } returns null
            application { installLogManagementRoutes() }

            val badCreate = client.postJson("/v1/logs/metrics/rules", """{"name":""}""")
            val invalidId = client.putJson("/v1/logs/metrics/rules/bad", """{"name":"x"}""")
            val updateMissing = client.putJson("/v1/logs/metrics/rules/404", """{"name":"x"}""")
            val rollupMissing = client.post("/v1/logs/metrics/rules/404/rollup") { withAuth(token()) }
            val deleteMissing = client.delete("/v1/logs/metrics/rules/404") { withAuth(token()) }

            assertEquals(HttpStatusCode.BadRequest, badCreate.status)
            assertEquals(HttpStatusCode.BadRequest, invalidId.status)
            assertEquals(HttpStatusCode.NotFound, updateMissing.status)
            assertEquals(HttpStatusCode.NotFound, rollupMissing.status)
            assertEquals(HttpStatusCode.NotFound, deleteMissing.status)
        }

    @Test
    fun `monitor route creates drafts with and without dashboard alerts`() =
        testApplication {
            every {
                dashboardAlertService.createAlert(
                    dashboardId = 12,
                    orgId = TEST_ORG_ID.toLong(),
                    createdBy = TEST_USER_ID.toLong(),
                    request = any()
                )
            } returns dashboardAlertResponse()
            application { installLogManagementRoutes() }

            val draftOnly = client.postJson(
                "/v1/logs/monitors/from-query",
                """{"name":"Errors","query":"level:error","threshold":10.0}"""
            )
            val dashboardDraft = client.postJson(
                "/v1/logs/monitors/from-query",
                """{"name":"Errors","threshold":10.0,"dashboard_id":12,"widget_id":34}"""
            )

            assertEquals(HttpStatusCode.OK, draftOnly.status)
            assertTrue(draftOnly.bodyAsText().contains(""""dashboard_alert_created":false"""))
            assertEquals(HttpStatusCode.OK, dashboardDraft.status)
            assertTrue(dashboardDraft.bodyAsText().contains(""""dashboard_alert_id":55"""))
        }

    @Test
    fun `monitor route maps dashboard alert validation errors to bad request`() =
        testApplication {
            every {
                dashboardAlertService.createAlert(
                    dashboardId = 12,
                    orgId = TEST_ORG_ID.toLong(),
                    createdBy = TEST_USER_ID.toLong(),
                    request = any()
                )
            } throws IllegalArgumentException("Widget not found in this dashboard")
            application { installLogManagementRoutes() }

            val response = client.postJson(
                "/v1/logs/monitors/from-query",
                """{"name":"Errors","threshold":10.0,"dashboard_id":12,"widget_id":34}"""
            )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Widget not found"))
        }

    @Test
    fun `monitor routes list create update and delete standalone monitors`() =
        testApplication {
            val monitor = logMonitorResponse(id = 41, name = "Error burst")
            every { logManagementService.listLogMonitors(TEST_ORG_ID) } returns listOf(monitor)
            every { logManagementService.createLogMonitor(eq(TEST_ORG_ID), eq(TEST_USER_ID), any()) } returns monitor
            every { logManagementService.updateLogMonitor(eq(TEST_ORG_ID), eq(41), any()) } returns
                monitor.copy(isActive = false)
            every { logManagementService.deleteLogMonitor(TEST_ORG_ID, 41) } returns true
            application { installLogManagementRoutes() }

            val list = client.get("/v1/logs/monitors") { withAuth(token()) }
            val create = client.postJson(
                "/v1/logs/monitors",
                """{"name":"Error burst","threshold":10.0,"group_by":"service"}"""
            )
            val update = client.putJson("/v1/logs/monitors/41", """{"is_active":false}""")
            val deleted = client.delete("/v1/logs/monitors/41") { withAuth(token()) }

            assertEquals(HttpStatusCode.OK, list.status)
            assertTrue(list.bodyAsText().contains("Error burst"))
            assertEquals(HttpStatusCode.Created, create.status)
            assertEquals(HttpStatusCode.OK, update.status)
            assertTrue(update.bodyAsText().contains(""""is_active":false"""))
            assertEquals(HttpStatusCode.NoContent, deleted.status)
        }

    @Test
    fun `monitor routes map invalid id missing monitor and bad request`() =
        testApplication {
            every { logManagementService.createLogMonitor(eq(TEST_ORG_ID), eq(TEST_USER_ID), any()) } throws
                IllegalArgumentException("Log monitor name is required")
            every { logManagementService.updateLogMonitor(eq(TEST_ORG_ID), eq(404), any()) } returns null
            every { logManagementService.deleteLogMonitor(TEST_ORG_ID, 404) } returns false
            every { logManagementService.updateLogMonitor(eq(TEST_ORG_ID), eq(42), any()) } throws
                IllegalArgumentException("Log monitor condition must be one of: >, >=, <, <=, ==")
            application { installLogManagementRoutes() }

            val badCreate = client.postJson("/v1/logs/monitors", """{"name":"","threshold":10.0}""")
            val invalidId = client.putJson("/v1/logs/monitors/bad", """{"name":"x"}""")
            val updateMissing = client.putJson("/v1/logs/monitors/404", """{"name":"x"}""")
            val badUpdate = client.putJson("/v1/logs/monitors/42", """{"condition":"!="}""")
            val deleteMissing = client.delete("/v1/logs/monitors/404") { withAuth(token()) }

            assertEquals(HttpStatusCode.BadRequest, badCreate.status)
            assertEquals(HttpStatusCode.BadRequest, invalidId.status)
            assertEquals(HttpStatusCode.NotFound, updateMissing.status)
            assertEquals(HttpStatusCode.BadRequest, badUpdate.status)
            assertEquals(HttpStatusCode.NotFound, deleteMissing.status)
        }

    private fun Application.installLogManagementRoutes() {
        installJwtAuth()
        routing {
            installLogRoutes(
                logService = logService,
                otlpApiKeyService = otlpApiKeyService,
                logIndexService = logIndexService,
                otlpServiceRoutingService = otlpServiceRoutingService,
                logManagementService = logManagementService,
                membershipService = membershipService,
                dashboardAlertService = dashboardAlertService
            )
        }
    }

    private suspend fun io.ktor.client.HttpClient.postJson(
        path: String,
        body: String
    ) = post(path) {
        withAuth(token())
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun io.ktor.client.HttpClient.putJson(
        path: String,
        body: String
    ) = put(path) {
        withAuth(token())
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun token(): String =
        RouteTestSupport.createToken(userId = TEST_USER_ID, orgId = TEST_ORG_ID)

    private fun pipelineResponse(id: Int, name: String): LogPipelineResponse =
        LogPipelineResponse(
            id = id,
            name = name,
            description = "",
            steps = listOf(LogPipelineStep(type = "redact", pattern = "secret")),
            priority = 10,
            isActive = true,
            createdAt = TEST_CREATED_AT,
            updatedAt = TEST_UPDATED_AT
        )

    private fun savedViewResponse(id: Int, name: String): LogSavedViewResponse =
        LogSavedViewResponse(
            id = id,
            name = name,
            state = LogSavedViewState(query = "level:error", levels = listOf("error")),
            isShared = true,
            createdAt = TEST_CREATED_AT,
            updatedAt = TEST_UPDATED_AT
        )

    private fun metricRuleResponse(id: Int, name: String): LogMetricRuleResponse =
        LogMetricRuleResponse(
            id = id,
            name = name,
            query = "level:error",
            levels = listOf("error"),
            groupBy = "service",
            interval = "5m",
            isActive = true,
            createdAt = TEST_CREATED_AT,
            updatedAt = TEST_UPDATED_AT
        )

    private fun logMonitorResponse(id: Int, name: String): LogMonitorResponse =
        LogMonitorResponse(
            id = id,
            name = name,
            query = "level:error",
            levels = listOf("error"),
            groupBy = "service",
            condition = ">",
            threshold = 10.0,
            warningThreshold = 5.0,
            windowMinutes = 5,
            isActive = true,
            createdAt = TEST_CREATED_AT,
            updatedAt = TEST_UPDATED_AT
        )

    private fun dashboardAlertResponse(): DashboardAlertResponse =
        DashboardAlertResponse(
            id = 55,
            widgetId = 34,
            dashboardId = 12,
            name = "Errors",
            condition = ">",
            threshold = 10.0,
            createdAt = TEST_CREATED_AT,
            updatedAt = TEST_UPDATED_AT
        )
}
