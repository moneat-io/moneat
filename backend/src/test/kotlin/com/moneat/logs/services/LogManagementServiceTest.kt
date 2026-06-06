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

package com.moneat.logs.services

import com.moneat.logs.models.CreateLogMetricRuleRequest
import com.moneat.logs.models.CreateLogMonitorRequest
import com.moneat.logs.models.CreateLogPipelineRequest
import com.moneat.logs.models.CreateLogSavedViewRequest
import com.moneat.logs.models.LogPipelinePreviewEntry
import com.moneat.logs.models.LogPipelinePreviewRequest
import com.moneat.logs.models.LogPipelineResponse
import com.moneat.logs.models.LogPipelineStep
import com.moneat.logs.models.LogSavedViewState
import com.moneat.logs.models.QueuedLogEntry
import com.moneat.logs.models.UpdateLogMetricRuleRequest
import com.moneat.logs.models.UpdateLogMonitorRequest
import com.moneat.logs.models.UpdateLogPipelineRequest
import com.moneat.logs.models.UpdateLogSavedViewRequest
import com.moneat.shared.models.LogMetricRules
import com.moneat.shared.models.LogMonitors
import com.moneat.shared.models.LogPipelines
import com.moneat.shared.models.LogSavedViews
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_ORGANIZATION_ID = 1
private const val TEST_USER_ID = 1
private const val OTHER_USER_ID = 2
private const val DEFAULT_TEST_TIMESTAMP_MS = 1_720_000_000_000L

class LogManagementServiceTest {
    private val service = LogManagementService()

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:log_management_service;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver"
        )
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            LogPipelines,
            LogSavedViews,
            LogMetricRules,
            LogMonitors
        )
        seedUser(TEST_USER_ID, "owner@example.com")
        seedUser(OTHER_USER_ID, "other@example.com")
        seedOrganization(TEST_ORGANIZATION_ID, "Test Org")
    }

    // ──── Pipeline transforms ────

    @Test
    fun `previewPipeline applies parse remap enrich redact and conditional drop steps`() {
        val steps = listOf(
            LogPipelineStep(type = "unknown"),
            LogPipelineStep(
                type = "parse",
                sourceField = "message",
                pattern = """([a-z_]+)=([A-Za-z0-9-]+)"""
            ),
            LogPipelineStep(type = "remap", sourceField = "tags.request_id", targetField = "trace_id"),
            LogPipelineStep(
                type = "enrich",
                targetField = "environment",
                value = "prod",
                tags = mapOf("team" to "payments")
            ),
            LogPipelineStep(
                type = "redact",
                sourceField = "message",
                pattern = """token=[A-Za-z0-9-]+""",
                replacement = "token=[redacted]"
            ),
            LogPipelineStep(type = "drop", enabled = false),
            LogPipelineStep(type = "drop", condition = "level:error service:checkout")
        )
        val keepSample = previewEntry(
            level = "info",
            message = "request_id=req-1 token=secret-123",
            service = "checkout"
        )
        val dropSample = previewEntry(
            level = "error",
            message = "request_id=req-2 token=secret-456",
            service = "checkout"
        )

        val result = service.previewPipeline(
            LogPipelinePreviewRequest(steps = steps, sampleLogs = listOf(keepSample, dropSample))
        )

        assertFalse(result[0].dropped)
        val transformed = assertNotNull(result[0].after)
        assertEquals("request_id=req-1 token=[redacted]", transformed.message)
        assertEquals("prod", transformed.environment)
        assertEquals("req-1", transformed.tags["request_id"])
        assertEquals("payments", transformed.tags["team"])
        assertTrue(result[1].dropped)
        assertNull(result[1].after)
    }

    @Test
    fun `previewPipeline redacts body and tolerates missing fields`() {
        val sample = previewEntry(message = "visible secret=123", body = "body secret=456")

        val result = service.previewPipeline(
            LogPipelinePreviewRequest(
                steps = listOf(
                    LogPipelineStep(type = "redact", sourceField = "body", pattern = """secret=\d+"""),
                    LogPipelineStep(type = "redact", sourceField = "tags.any", pattern = """secret=\d+"""),
                    LogPipelineStep(type = "remap", sourceField = "tags.missing", targetField = "service"),
                    LogPipelineStep(type = "enrich", tags = mapOf("release" to "2026.06"))
                ),
                sampleLogs = listOf(sample)
            )
        )

        val transformed = assertNotNull(result.single().after)
        assertEquals("visible [redacted]", transformed.message)
        assertEquals("body [redacted]", transformed.body)
        assertEquals("api", transformed.service)
        assertEquals("2026.06", transformed.tags["release"])
    }

    @Test
    fun `previewPipeline rejects malformed regex patterns before ingestion`() {
        val error = assertFailsWith<IllegalArgumentException> {
            service.previewPipeline(
                LogPipelinePreviewRequest(
                    steps = listOf(LogPipelineStep(type = "parse", pattern = "[")),
                    sampleLogs = listOf(previewEntry())
                )
            )
        }

        assertEquals("Pipeline regex pattern is invalid", error.message)
    }

    @Test
    fun `previewPipeline rejects enabled unconditional drop steps`() {
        val error = assertFailsWith<IllegalArgumentException> {
            service.previewPipeline(
                LogPipelinePreviewRequest(
                    steps = listOf(LogPipelineStep(type = " drop ")),
                    sampleLogs = listOf(previewEntry())
                )
            )
        }

        assertEquals("Drop pipeline steps require a condition", error.message)
    }

    @Test
    fun `previewPipeline rejects unsafe regex patterns before ingestion`() {
        val error = assertFailsWith<IllegalArgumentException> {
            service.previewPipeline(
                LogPipelinePreviewRequest(
                    steps = listOf(LogPipelineStep(type = "redact", pattern = "(a+)+$")),
                    sampleLogs = listOf(previewEntry(message = "aaaaaaaaaaaaaaaa"))
                )
            )
        }

        assertEquals("Pipeline regex pattern uses unsupported high-cost constructs", error.message)
    }

    @Test
    fun `applyPipelines transforms queued entries and drops matching entries`() {
        val pipelines = listOf(
            pipelineResponse(
                steps = listOf(
                    LogPipelineStep(
                        type = "redact",
                        sourceField = "body",
                        pattern = """password=\S+""",
                        replacement = "password=[redacted]"
                    ),
                    LogPipelineStep(type = "remap", sourceField = "resource_attributes.k8s.pod", targetField = "host"),
                    LogPipelineStep(type = "remap", sourceField = "service", targetField = "tags.original_service"),
                    LogPipelineStep(type = "remap", sourceField = "tags.tenant", targetField = "service"),
                    LogPipelineStep(type = "enrich", targetField = "container_name", value = "app-container"),
                    LogPipelineStep(type = "drop", condition = "drop:true")
                )
            )
        )

        val kept = queuedEntry(
            logId = "keep",
            body = "password=swordfish",
            service = "checkout",
            tags = mapOf("tenant" to "enterprise"),
            resourceAttributes = mapOf("k8s.pod" to "pod-a")
        )
        val dropped = queuedEntry(logId = "drop", tags = mapOf("drop" to "true"))

        val result = service.applyPipelines(listOf(kept, dropped), pipelines)

        assertEquals(1, result.size)
        val transformed = result.single()
        assertEquals("keep", transformed.logId)
        assertEquals("password=[redacted]", transformed.body)
        assertEquals("enterprise", transformed.service)
        assertEquals("pod-a", transformed.host)
        assertEquals("app-container", transformed.containerName)
        assertEquals("checkout", transformed.tags["original_service"])
    }

    @Test
    fun `applyPipelines returns original entries when there are no active steps`() {
        val entry = queuedEntry()

        assertEquals(listOf(entry), service.applyPipelines(listOf(entry), emptyList()))
        assertEquals(
            listOf(entry),
            service.applyPipelines(
                entries = listOf(entry),
                pipelines = listOf(pipelineResponse(steps = emptyList()))
            )
        )
        assertEquals(emptyList(), service.applyPipelines(emptyList(), listOf(pipelineResponse())))
    }

    // ──── Pipeline persistence ────

    @Test
    fun `pipeline CRUD trims stores orders updates and deletes organization pipelines`() {
        val first = service.createPipeline(
            organizationId = TEST_ORGANIZATION_ID,
            createdBy = TEST_USER_ID,
            request = CreateLogPipelineRequest(
                name = "  Error cleanup  ",
                description = "  remove noisy lines  ",
                steps = listOf(LogPipelineStep(type = "redact", pattern = "secret")),
                priority = 20,
                isActive = true
            )
        )
        val second = service.createPipeline(
            organizationId = TEST_ORGANIZATION_ID,
            createdBy = TEST_USER_ID,
            request = CreateLogPipelineRequest(name = "Parser", priority = 10, isActive = false)
        )

        assertEquals(listOf(second.id, first.id), service.listPipelines(TEST_ORGANIZATION_ID).map { it.id })
        assertEquals("Error cleanup", first.name)
        assertEquals("remove noisy lines", first.description)

        val updated = service.updatePipeline(
            organizationId = TEST_ORGANIZATION_ID,
            pipelineId = first.id,
            request = UpdateLogPipelineRequest(
                name = "  Cleanup v2  ",
                description = "  updated  ",
                steps = listOf(LogPipelineStep(type = "parse")),
                priority = 5,
                isActive = false
            )
        )

        assertNotNull(updated)
        assertEquals("Cleanup v2", updated.name)
        assertEquals("updated", updated.description)
        assertEquals(5, updated.priority)
        assertFalse(updated.isActive)
        assertEquals(listOf("parse"), updated.steps.map { it.type })
        assertNull(service.updatePipeline(TEST_ORGANIZATION_ID, 999, UpdateLogPipelineRequest(name = "missing")))
        assertTrue(service.deletePipeline(TEST_ORGANIZATION_ID, second.id))
        assertFalse(service.deletePipeline(TEST_ORGANIZATION_ID, second.id))
    }

    @Test
    fun `pipeline CRUD rejects blank duplicate and unsafe drop requests`() {
        service.createPipeline(TEST_ORGANIZATION_ID, TEST_USER_ID, CreateLogPipelineRequest(name = "Cleanup"))

        assertEquals(
            "Pipeline name is required",
            assertFailsWith<IllegalArgumentException> {
                service.createPipeline(TEST_ORGANIZATION_ID, TEST_USER_ID, CreateLogPipelineRequest(name = " "))
            }.message
        )
        assertEquals(
            "Pipeline name already exists",
            assertFailsWith<IllegalArgumentException> {
                service.createPipeline(TEST_ORGANIZATION_ID, TEST_USER_ID, CreateLogPipelineRequest(name = "Cleanup"))
            }.message
        )
        assertEquals(
            "Drop pipeline steps require a condition",
            assertFailsWith<IllegalArgumentException> {
                service.createPipeline(
                    TEST_ORGANIZATION_ID,
                    TEST_USER_ID,
                    CreateLogPipelineRequest(name = "Drop all", steps = listOf(LogPipelineStep(type = "drop")))
                )
            }.message
        )
    }

    // ──── Saved views and metrics ────

    @Test
    fun `saved view CRUD keeps private views scoped to the creator`() {
        val shared = service.createSavedView(
            organizationId = TEST_ORGANIZATION_ID,
            createdBy = TEST_USER_ID,
            request = CreateLogSavedViewRequest(
                name = "  Errors  ",
                state = LogSavedViewState(query = "level:error", levels = listOf("error")),
                isShared = true
            )
        )
        val private = service.createSavedView(
            organizationId = TEST_ORGANIZATION_ID,
            createdBy = TEST_USER_ID,
            request = CreateLogSavedViewRequest(
                name = "Mine",
                state = LogSavedViewState(groupBy = "service", topField = "host"),
                isShared = false
            )
        )

        assertEquals(
            listOf("Errors", "Mine"),
            service.listSavedViews(TEST_ORGANIZATION_ID, TEST_USER_ID).map { it.name }
        )
        assertEquals(
            listOf("Errors"),
            service.listSavedViews(TEST_ORGANIZATION_ID, OTHER_USER_ID).map { it.name }
        )
        assertNull(
            service.updateSavedView(
                organizationId = TEST_ORGANIZATION_ID,
                viewId = private.id,
                userId = OTHER_USER_ID,
                request = UpdateLogSavedViewRequest()
            )
        )

        val updated = service.updateSavedView(
            organizationId = TEST_ORGANIZATION_ID,
            viewId = private.id,
            userId = TEST_USER_ID,
            request = UpdateLogSavedViewRequest(
                name = "  Mine updated  ",
                state = LogSavedViewState(query = "service:api", timePreset = "24h"),
                isShared = true
            )
        )

        assertNotNull(updated)
        assertEquals("Mine updated", updated.name)
        assertEquals("service:api", updated.state.query)
        assertTrue(updated.isShared)
        assertTrue(service.deleteSavedView(TEST_ORGANIZATION_ID, shared.id, OTHER_USER_ID))
        assertFalse(service.deleteSavedView(TEST_ORGANIZATION_ID, shared.id, OTHER_USER_ID))
    }

    @Test
    fun `saved view CRUD rejects blank and duplicate names`() {
        service.createSavedView(
            TEST_ORGANIZATION_ID,
            TEST_USER_ID,
            CreateLogSavedViewRequest(name = "Errors", state = LogSavedViewState())
        )

        assertEquals(
            "Saved view name is required",
            assertFailsWith<IllegalArgumentException> {
                service.createSavedView(
                    TEST_ORGANIZATION_ID,
                    TEST_USER_ID,
                    CreateLogSavedViewRequest(name = " ", state = LogSavedViewState())
                )
            }.message
        )
        assertEquals(
            "Saved view name already exists",
            assertFailsWith<IllegalArgumentException> {
                service.createSavedView(
                    TEST_ORGANIZATION_ID,
                    TEST_USER_ID,
                    CreateLogSavedViewRequest(name = "Errors", state = LogSavedViewState())
                )
            }.message
        )
    }

    @Test
    fun `metric rule CRUD normalizes group by stores levels updates and deletes rules`() {
        val created = service.createMetricRule(
            organizationId = TEST_ORGANIZATION_ID,
            createdBy = TEST_USER_ID,
            request = CreateLogMetricRuleRequest(
                name = "  Error rate  ",
                query = " level:error ",
                levels = listOf("error"),
                groupBy = " service ",
                interval = "1m",
                isActive = true
            )
        )

        assertEquals("Error rate", created.name)
        assertEquals("level:error", created.query)
        assertEquals(listOf("error"), created.levels)
        assertEquals("service", created.groupBy)
        assertEquals(listOf(created.id), service.listMetricRules(TEST_ORGANIZATION_ID).map { it.id })

        val updated = service.updateMetricRule(
            organizationId = TEST_ORGANIZATION_ID,
            ruleId = created.id,
            request = UpdateLogMetricRuleRequest(
                name = "  Warnings  ",
                query = "level:warn",
                levels = listOf("warn", "error"),
                groupBy = "environment",
                interval = "5m",
                isActive = false
            )
        )

        assertNotNull(updated)
        assertEquals("Warnings", updated.name)
        assertEquals("level:warn", updated.query)
        assertEquals(listOf("warn", "error"), updated.levels)
        assertEquals("environment", updated.groupBy)
        assertEquals("5m", updated.interval)
        assertFalse(updated.isActive)
        assertNull(service.updateMetricRule(TEST_ORGANIZATION_ID, 999, UpdateLogMetricRuleRequest(name = "missing")))
        assertTrue(service.deleteMetricRule(TEST_ORGANIZATION_ID, created.id))
        assertFalse(service.deleteMetricRule(TEST_ORGANIZATION_ID, created.id))
    }

    @Test
    fun `metric rule CRUD rejects invalid names duplicates and group by fields`() {
        service.createMetricRule(TEST_ORGANIZATION_ID, TEST_USER_ID, CreateLogMetricRuleRequest(name = "Errors"))

        assertNull(LogManagementService.normalizeMetricGroupBy(" "))
        assertEquals("level", LogManagementService.normalizeMetricGroupBy("level"))
        assertEquals(
            "Metric rule name is required",
            assertFailsWith<IllegalArgumentException> {
                service.createMetricRule(TEST_ORGANIZATION_ID, TEST_USER_ID, CreateLogMetricRuleRequest(name = " "))
            }.message
        )
        assertEquals(
            "Metric rule name already exists",
            assertFailsWith<IllegalArgumentException> {
                service.createMetricRule(
                    TEST_ORGANIZATION_ID,
                    TEST_USER_ID,
                    CreateLogMetricRuleRequest(name = "Errors")
                )
            }.message
        )
        assertEquals(
            "Metric group_by must be one of: level, service, environment",
            assertFailsWith<IllegalArgumentException> {
                service.createMetricRule(
                    TEST_ORGANIZATION_ID,
                    TEST_USER_ID,
                    CreateLogMetricRuleRequest(name = "Bad group", groupBy = "trace_id")
                )
            }.message
        )
    }

    @Test
    fun `log monitor CRUD normalizes query group by condition window and deletes monitors`() {
        val created = service.createLogMonitor(
            organizationId = TEST_ORGANIZATION_ID,
            createdBy = TEST_USER_ID,
            request = CreateLogMonitorRequest(
                name = "  Error burst  ",
                query = " service:api level:error ",
                levels = listOf("error"),
                groupBy = " service ",
                condition = ">=",
                threshold = 10.0,
                warningThreshold = 5.0,
                windowMinutes = 15,
                isActive = true
            )
        )

        assertEquals("Error burst", created.name)
        assertEquals("service:api level:error", created.query)
        assertEquals(listOf("error"), created.levels)
        assertEquals("service", created.groupBy)
        assertEquals(">=", created.condition)
        assertEquals(10.0, created.threshold)
        assertEquals(5.0, created.warningThreshold)
        assertEquals(15, created.windowMinutes)
        assertEquals(listOf(created.id), service.listLogMonitors(TEST_ORGANIZATION_ID).map { it.id })

        val updated = service.updateLogMonitor(
            organizationId = TEST_ORGANIZATION_ID,
            monitorId = created.id,
            request = UpdateLogMonitorRequest(
                name = "  Warnings  ",
                query = "level:warn",
                levels = listOf("warn", "error"),
                groupBy = "environment",
                condition = "<",
                threshold = 2.5,
                warningThreshold = 1.5,
                windowMinutes = 30,
                isActive = false
            )
        )

        assertNotNull(updated)
        assertEquals("Warnings", updated.name)
        assertEquals("level:warn", updated.query)
        assertEquals(listOf("warn", "error"), updated.levels)
        assertEquals("environment", updated.groupBy)
        assertEquals("<", updated.condition)
        assertEquals(2.5, updated.threshold)
        assertEquals(1.5, updated.warningThreshold)
        assertEquals(30, updated.windowMinutes)
        assertFalse(updated.isActive)
        assertNull(service.updateLogMonitor(TEST_ORGANIZATION_ID, 999, UpdateLogMonitorRequest(name = "missing")))
        assertTrue(service.deleteLogMonitor(TEST_ORGANIZATION_ID, created.id))
        assertFalse(service.deleteLogMonitor(TEST_ORGANIZATION_ID, created.id))
    }

    @Test
    fun `log monitor CRUD rejects invalid names duplicates group by condition threshold and window`() {
        service.createLogMonitor(
            TEST_ORGANIZATION_ID,
            TEST_USER_ID,
            CreateLogMonitorRequest(name = "Errors", threshold = 1.0)
        )

        assertEquals(">", LogManagementService.normalizeMonitorCondition(" "))
        assertEquals("<=", LogManagementService.normalizeMonitorCondition("<="))
        assertEquals(
            "Log monitor name is required",
            assertFailsWith<IllegalArgumentException> {
                service.createLogMonitor(
                    TEST_ORGANIZATION_ID,
                    TEST_USER_ID,
                    CreateLogMonitorRequest(name = " ", threshold = 1.0)
                )
            }.message
        )
        assertEquals(
            "Log monitor name already exists",
            assertFailsWith<IllegalArgumentException> {
                service.createLogMonitor(
                    TEST_ORGANIZATION_ID,
                    TEST_USER_ID,
                    CreateLogMonitorRequest(name = "Errors", threshold = 1.0)
                )
            }.message
        )
        assertEquals(
            "Metric group_by must be one of: level, service, environment",
            assertFailsWith<IllegalArgumentException> {
                service.createLogMonitor(
                    TEST_ORGANIZATION_ID,
                    TEST_USER_ID,
                    CreateLogMonitorRequest(name = "Bad group", threshold = 1.0, groupBy = "trace_id")
                )
            }.message
        )
        assertEquals(
            "Log monitor condition must be one of: >, >=, <, <=, ==",
            assertFailsWith<IllegalArgumentException> {
                service.createLogMonitor(
                    TEST_ORGANIZATION_ID,
                    TEST_USER_ID,
                    CreateLogMonitorRequest(name = "Bad condition", threshold = 1.0, condition = "!=")
                )
            }.message
        )
        assertEquals(
            "Log monitor threshold must be finite",
            assertFailsWith<IllegalArgumentException> {
                service.createLogMonitor(
                    TEST_ORGANIZATION_ID,
                    TEST_USER_ID,
                    CreateLogMonitorRequest(name = "Bad threshold", threshold = Double.NaN)
                )
            }.message
        )
        assertEquals(
            "Log monitor window_minutes must be at least 1",
            assertFailsWith<IllegalArgumentException> {
                service.createLogMonitor(
                    TEST_ORGANIZATION_ID,
                    TEST_USER_ID,
                    CreateLogMonitorRequest(name = "Bad window", threshold = 1.0, windowMinutes = 0)
                )
            }.message
        )
    }

    private fun previewEntry(
        level: String = "info",
        message: String = "request_id=req-1",
        body: String = "",
        service: String = "api",
        environment: String = "dev",
        host: String = "host-a",
        tags: Map<String, String> = emptyMap(),
        resourceAttributes: Map<String, String> = emptyMap()
    ): LogPipelinePreviewEntry =
        LogPipelinePreviewEntry(
            level = level,
            message = message,
            body = body,
            service = service,
            environment = environment,
            host = host,
            tags = tags,
            resourceAttributes = resourceAttributes
        )

    private fun queuedEntry(
        logId: String = "log-1",
        body: String = "",
        service: String = "api",
        tags: Map<String, String> = emptyMap(),
        resourceAttributes: Map<String, String> = emptyMap()
    ): QueuedLogEntry =
        QueuedLogEntry(
            logId = logId,
            timestampMs = DEFAULT_TEST_TIMESTAMP_MS,
            level = "info",
            message = "message",
            body = body,
            service = service,
            environment = "dev",
            host = "host-a",
            source = "agent",
            containerName = "container",
            containerId = "container-id",
            containerImage = "image",
            traceId = "trace-id",
            spanId = "span-id",
            tags = tags,
            resourceAttributes = resourceAttributes
        )

    private fun pipelineResponse(
        steps: List<LogPipelineStep> = listOf(LogPipelineStep(type = "parse"))
    ): LogPipelineResponse =
        LogPipelineResponse(
            id = 1,
            name = "Pipeline",
            steps = steps,
            priority = 0,
            isActive = true,
            createdAt = "2026-06-04T00:00:00Z",
            updatedAt = "2026-06-04T00:00:00Z"
        )

    private fun seedUser(id: Int, email: String) {
        transaction {
            Users.insert {
                it[Users.id] = id
                it[Users.email] = email
                it[Users.password_hash] = "hash"
            }
        }
    }

    private fun seedOrganization(id: Int, name: String) {
        transaction {
            Organizations.insert {
                it[Organizations.id] = id
                it[Organizations.name] = name
                it[Organizations.slug] = name.lowercase().replace(" ", "-")
            }
        }
    }
}
