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

package com.moneat.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.datadog.models.DdActivityDump
import com.moneat.datadog.models.DdActivityDumpPayload
import com.moneat.datadog.models.DdComplianceFinding
import com.moneat.datadog.models.DdCompliancePayload
import com.moneat.datadog.models.DdSecurityEvent
import com.moneat.datadog.models.DdSecurityEventPayload
import com.moneat.datadog.security.QueuedSecurityBatch
import com.moneat.datadog.security.SecurityIngestionService
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecurityServiceTest {

    companion object {
        private const val TEXT_PLAIN = "text/plain"
        private const val WEB_01 = "web-01"
    }

    private val mockRedisCommands = mockk<RedisCommands<String, String>>(relaxed = true)

    @BeforeTest
    fun setup() {
        mockkObject(RedisConfig)
        every { RedisConfig.sync() } returns mockRedisCommands
    }

    @AfterTest
    fun teardown() {
        unmockkObject(RedisConfig)
        ClickHouseClient.close()
    }

    // ==================== parseDdTagList ====================

    @Test
    fun `parseDdTagList parses colon-separated key-value tags`() {
        val tags = listOf("env:production", "service:web")
        val result = SecurityIngestionService.parseDdTagList(tags)
        assertEquals(mapOf("env" to "production", "service" to "web"), result)
    }

    @Test
    fun `parseDdTagList handles tags with multiple colons`() {
        val tags = listOf("url:http://example.com:8080/path")
        val result = SecurityIngestionService.parseDdTagList(tags)
        assertEquals(mapOf("url" to "http://example.com:8080/path"), result)
    }

    @Test
    fun `parseDdTagList handles tags without value`() {
        val tags = listOf("standalone")
        val result = SecurityIngestionService.parseDdTagList(tags)
        assertEquals(mapOf("standalone" to ""), result)
    }

    @Test
    fun `parseDdTagList returns empty map for empty input`() {
        val result = SecurityIngestionService.parseDdTagList(emptyList())
        assertEquals(emptyMap(), result)
    }

    @Test
    fun `parseDdTagList handles mixed tag formats`() {
        val tags = listOf("env:staging", "flagged", "host:web-01")
        val result = SecurityIngestionService.parseDdTagList(tags)
        assertEquals(
            mapOf("env" to "staging", "flagged" to "", "host" to "web-01"),
            result
        )
    }

    @Test
    fun `parseDdTagList ignores empty strings`() {
        val tags = listOf("env:prod", "", "host:db-01")
        val result = SecurityIngestionService.parseDdTagList(tags)
        assertEquals(mapOf("env" to "prod", "host" to "db-01"), result)
    }

    @Test
    fun `parseDdTagList handles tag with colon at start`() {
        val tags = listOf(":value")
        val result = SecurityIngestionService.parseDdTagList(tags)
        // colonIdx == 0, so condition colonIdx > 0 is false; falls to else (non-empty)
        assertEquals(mapOf(":value" to ""), result)
    }

    // ==================== decodeBatch ====================

    @Test
    fun `decodeBatch decodes security events batch`() {
        val encoded = """
            {"organization_id":1,"batch_type":"events",
             "events":[{"rule_id":"r1","rule_name":"TestRule",
             "rule_category":"threat","severity":"high",
             "agent_rule_version":"1.0","event_type":"signal",
             "process_name":"java","file_path":"/tmp/x",
             "host":"web-01","env":"prod","tags":{},"timestamp_ms":1700000000000}],
             "dumps":[],"findings":[]}
        """.trimIndent()
        val batch = SecurityIngestionService.decodeBatch(encoded)
        assertEquals(1, batch.organizationId)
        assertEquals("events", batch.batchType)
        assertEquals(1, batch.events.size)
        assertEquals("r1", batch.events[0].ruleId)
        assertEquals("high", batch.events[0].severity)
        assertEquals(WEB_01, batch.events[0].host)
    }

    @Test
    fun `decodeBatch decodes activity dumps batch`() {
        val encoded = """
            {"organization_id":2,"batch_type":"dumps",
             "events":[],"dumps":[{"activity_type":"exec","process_name":"bash",
             "host":"srv-01","duration_ns":500000,"dump_data":"some-data",
             "tags":{},"timestamp_ms":1700000000000}],
             "findings":[]}
        """.trimIndent()
        val batch = SecurityIngestionService.decodeBatch(encoded)
        assertEquals(2, batch.organizationId)
        assertEquals("dumps", batch.batchType)
        assertEquals(1, batch.dumps.size)
        assertEquals("exec", batch.dumps[0].activityType)
    }

    @Test
    fun `decodeBatch decodes compliance findings batch`() {
        val encoded = """
            {"organization_id":3,"batch_type":"findings",
             "events":[],"dumps":[],
             "findings":[{"framework":"cis","rule_id":"cis-1.1",
             "rule_name":"Check1","status":"failed",
             "resource_type":"ec2","resource_id":"i-123",
             "resource_name":"myvm","tags":{},"timestamp_ms":1700000000000}]}
        """.trimIndent()
        val batch = SecurityIngestionService.decodeBatch(encoded)
        assertEquals(3, batch.organizationId)
        assertEquals("findings", batch.batchType)
        assertEquals(1, batch.findings.size)
        assertEquals("failed", batch.findings[0].status)
    }

    @Test
    fun `decodeBatch throws on invalid JSON`() {
        assertFailsWith<Exception> {
            SecurityIngestionService.decodeBatch("not-json")
        }
    }

    @Test
    fun `decodeBatch handles empty collections`() {
        val encoded = """
            {"organization_id":1,"batch_type":"events",
             "events":[],"dumps":[],"findings":[]}
        """.trimIndent()
        val batch = SecurityIngestionService.decodeBatch(encoded)
        assertTrue(batch.events.isEmpty())
        assertTrue(batch.dumps.isEmpty())
        assertTrue(batch.findings.isEmpty())
    }

    // ==================== enqueueSecurityEvents ====================

    @Test
    fun `enqueueSecurityEvents pushes to Redis and returns count`() {
        val payload = DdSecurityEventPayload(
            events = listOf(
                DdSecurityEvent(
                    ruleId = "rule-1",
                    ruleName = "Test Rule",
                    ruleCategory = "threat",
                    severity = "critical",
                    host = "host-01",
                    tags = listOf("env:prod")
                ),
                DdSecurityEvent(
                    ruleId = "rule-2",
                    ruleName = "Rule Two",
                    ruleCategory = "workload",
                    severity = "low",
                    host = "host-02"
                )
            )
        )

        val count = SecurityIngestionService.enqueueSecurityEvents(1, payload)

        assertEquals(2, count)
        val captured = slot<String>()
        verify {
            mockRedisCommands.lpush(
                "moneat:dd:security:queue",
                capture(captured)
            )
        }
        val decoded = SecurityIngestionService.decodeBatch(captured.captured)
        assertEquals("events", decoded.batchType)
        assertEquals(1, decoded.organizationId)
        assertEquals(2, decoded.events.size)
        assertEquals("rule-1", decoded.events[0].ruleId)
        assertEquals("critical", decoded.events[0].severity)
    }

    @Test
    fun `enqueueSecurityEvents returns zero for empty payload`() {
        val payload = DdSecurityEventPayload(events = emptyList())
        val count = SecurityIngestionService.enqueueSecurityEvents(1, payload)
        assertEquals(0, count)
        verify(exactly = 0) { mockRedisCommands.lpush(any(), any<String>()) }
    }

    @Test
    fun `enqueueSecurityEvents preserves parsed tags`() {
        val payload = DdSecurityEventPayload(
            events = listOf(
                DdSecurityEvent(
                    ruleId = "r1",
                    tags = listOf("env:staging", "region:us-east-1")
                )
            )
        )

        SecurityIngestionService.enqueueSecurityEvents(5, payload)

        val captured = slot<String>()
        verify { mockRedisCommands.lpush(any(), capture(captured)) }
        val decoded = SecurityIngestionService.decodeBatch(captured.captured)
        val tags = decoded.events[0].tags
        assertEquals("staging", tags["env"])
        assertEquals("us-east-1", tags["region"])
    }

    // ==================== enqueueActivityDumps ====================

    @Test
    fun `enqueueActivityDumps pushes to Redis and returns count`() {
        val payload = DdActivityDumpPayload(
            dumps = listOf(
                DdActivityDump(
                    activityType = "exec",
                    processName = "bash",
                    host = "srv-01",
                    durationNs = 100000,
                    dumpData = "data-here",
                    tags = listOf("env:dev")
                )
            )
        )

        val count = SecurityIngestionService.enqueueActivityDumps(2, payload)

        assertEquals(1, count)
        val captured = slot<String>()
        verify { mockRedisCommands.lpush(any(), capture(captured)) }
        val decoded = SecurityIngestionService.decodeBatch(captured.captured)
        assertEquals("dumps", decoded.batchType)
        assertEquals(2, decoded.organizationId)
        assertEquals("exec", decoded.dumps[0].activityType)
        assertEquals("bash", decoded.dumps[0].processName)
    }

    @Test
    fun `enqueueActivityDumps returns zero for empty payload`() {
        val payload = DdActivityDumpPayload(dumps = emptyList())
        val count = SecurityIngestionService.enqueueActivityDumps(2, payload)
        assertEquals(0, count)
        verify(exactly = 0) { mockRedisCommands.lpush(any(), any<String>()) }
    }

    // ==================== enqueueCompliance ====================

    @Test
    fun `enqueueCompliance pushes to Redis and returns count`() {
        val payload = DdCompliancePayload(
            findings = listOf(
                DdComplianceFinding(
                    framework = "cis",
                    ruleId = "cis-1.1",
                    ruleName = "Check Root",
                    status = "failed",
                    resourceType = "iam",
                    resourceId = "root",
                    resourceName = "root-account",
                    tags = listOf("scope:aws")
                ),
                DdComplianceFinding(
                    framework = "pci",
                    ruleId = "pci-3.4",
                    ruleName = "Encrypt Data",
                    status = "passed"
                )
            )
        )

        val count = SecurityIngestionService.enqueueCompliance(3, payload)

        assertEquals(2, count)
        val captured = slot<String>()
        verify { mockRedisCommands.lpush(any(), capture(captured)) }
        val decoded = SecurityIngestionService.decodeBatch(captured.captured)
        assertEquals("findings", decoded.batchType)
        assertEquals(3, decoded.organizationId)
        assertEquals(2, decoded.findings.size)
        assertEquals("cis", decoded.findings[0].framework)
        assertEquals("failed", decoded.findings[0].status)
    }

    @Test
    fun `enqueueCompliance returns zero for empty payload`() {
        val payload = DdCompliancePayload(findings = emptyList())
        val count = SecurityIngestionService.enqueueCompliance(3, payload)
        assertEquals(0, count)
        verify(exactly = 0) { mockRedisCommands.lpush(any(), any<String>()) }
    }

    // ==================== insertBatch ====================

    @Test
    fun `insertBatch dispatches security events to ClickHouse`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        MockHttpServer { exchange ->
            capturedSql.add(exchange.requestBodyText())
            exchange.respond(200, "Ok", contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test_db", "default", "")

            val batch = QueuedSecurityBatch(
                organizationId = 1,
                batchType = "events",
                events = listOf(
                    com.moneat.datadog.security.QueuedSecurityEventEntry(
                        ruleId = "r1",
                        ruleName = "Rule One",
                        ruleCategory = "threat",
                        severity = "high",
                        agentRuleVersion = "1.0",
                        eventType = "signal",
                        processName = "java",
                        filePath = "/tmp/test",
                        host = WEB_01,
                        env = "prod",
                        tags = mapOf("env" to "prod"),
                        timestampMs = 1700000000000L
                    )
                )
            )

            SecurityIngestionService.insertBatch(batch)

            assertEquals(1, capturedSql.size)
            val sql = capturedSql[0]
            assertTrue(sql.contains("INSERT INTO"))
            assertTrue(sql.contains("security_events"))
            assertTrue(sql.contains("'r1'"))
            assertTrue(sql.contains("'Rule One'"))
            assertTrue(sql.contains("'high'"))
        }
    }

    @Test
    fun `insertBatch dispatches activity dumps to ClickHouse`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        MockHttpServer { exchange ->
            capturedSql.add(exchange.requestBodyText())
            exchange.respond(200, "Ok", contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test_db", "default", "")

            val batch = QueuedSecurityBatch(
                organizationId = 2,
                batchType = "dumps",
                dumps = listOf(
                    com.moneat.datadog.security.QueuedActivityDumpEntry(
                        activityType = "exec",
                        processName = "bash",
                        host = "srv-01",
                        durationNs = 500000,
                        dumpData = "dump-payload",
                        tags = emptyMap(),
                        timestampMs = 1700000000000L
                    )
                )
            )

            SecurityIngestionService.insertBatch(batch)

            assertEquals(1, capturedSql.size)
            assertTrue(capturedSql[0].contains("security_dumps"))
            assertTrue(capturedSql[0].contains("'exec'"))
            assertTrue(capturedSql[0].contains("'bash'"))
        }
    }

    @Test
    fun `insertBatch dispatches compliance findings to ClickHouse`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        MockHttpServer { exchange ->
            capturedSql.add(exchange.requestBodyText())
            exchange.respond(200, "Ok", contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test_db", "default", "")

            val batch = QueuedSecurityBatch(
                organizationId = 3,
                batchType = "findings",
                findings = listOf(
                    com.moneat.datadog.security.QueuedComplianceEntry(
                        framework = "cis",
                        ruleId = "cis-1.1",
                        ruleName = "Root Check",
                        status = "failed",
                        resourceType = "iam",
                        resourceId = "root",
                        resourceName = "root-account",
                        tags = mapOf("scope" to "aws"),
                        timestampMs = 1700000000000L
                    )
                )
            )

            SecurityIngestionService.insertBatch(batch)

            assertEquals(1, capturedSql.size)
            assertTrue(capturedSql[0].contains("compliance_findings"))
            assertTrue(capturedSql[0].contains("'cis'"))
            assertTrue(capturedSql[0].contains("'failed'"))
        }
    }

    @Test
    fun `insertBatch skips empty events batch`() = runBlocking {
        var callCount = 0
        MockHttpServer { exchange ->
            callCount++
            exchange.requestBodyText()
            exchange.respond(200, "Ok", contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test_db", "default", "")

            val batch = QueuedSecurityBatch(
                organizationId = 1,
                batchType = "events",
                events = emptyList()
            )
            SecurityIngestionService.insertBatch(batch)
            assertEquals(0, callCount)
        }
    }

    @Test
    fun `insertBatch skips empty dumps batch`() = runBlocking {
        var callCount = 0
        MockHttpServer { exchange ->
            callCount++
            exchange.requestBodyText()
            exchange.respond(200, "Ok", contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test_db", "default", "")

            val batch = QueuedSecurityBatch(
                organizationId = 1,
                batchType = "dumps",
                dumps = emptyList()
            )
            SecurityIngestionService.insertBatch(batch)
            assertEquals(0, callCount)
        }
    }

    @Test
    fun `insertBatch skips empty findings batch`() = runBlocking {
        var callCount = 0
        MockHttpServer { exchange ->
            callCount++
            exchange.requestBodyText()
            exchange.respond(200, "Ok", contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test_db", "default", "")

            val batch = QueuedSecurityBatch(
                organizationId = 1,
                batchType = "findings",
                findings = emptyList()
            )
            SecurityIngestionService.insertBatch(batch)
            assertEquals(0, callCount)
        }
    }

    @Test
    fun `insertBatch throws on ClickHouse error for events`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.requestBodyText()
            exchange.respond(500, "Internal Server Error")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test_db", "default", "")

            val batch = QueuedSecurityBatch(
                organizationId = 1,
                batchType = "events",
                events = listOf(
                    com.moneat.datadog.security.QueuedSecurityEventEntry(
                        ruleId = "r1",
                        timestampMs = 1700000000000L
                    )
                )
            )
            assertFailsWith<IllegalStateException> {
                SecurityIngestionService.insertBatch(batch)
            }
        }
    }

    @Test
    fun `insertBatch normalizes unknown severity to info`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        MockHttpServer { exchange ->
            capturedSql.add(exchange.requestBodyText())
            exchange.respond(200, "Ok", contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test_db", "default", "")

            val batch = QueuedSecurityBatch(
                organizationId = 1,
                batchType = "events",
                events = listOf(
                    com.moneat.datadog.security.QueuedSecurityEventEntry(
                        ruleId = "r1",
                        severity = "unknown_sev",
                        timestampMs = 1700000000000L
                    )
                )
            )
            SecurityIngestionService.insertBatch(batch)
            assertTrue(capturedSql[0].contains("'info'"))
        }
    }

    @Test
    fun `insertBatch normalizes unknown compliance status to passed`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        MockHttpServer { exchange ->
            capturedSql.add(exchange.requestBodyText())
            exchange.respond(200, "Ok", contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test_db", "default", "")

            val batch = QueuedSecurityBatch(
                organizationId = 1,
                batchType = "findings",
                findings = listOf(
                    com.moneat.datadog.security.QueuedComplianceEntry(
                        framework = "custom",
                        status = "banana",
                        timestampMs = 1700000000000L
                    )
                )
            )
            SecurityIngestionService.insertBatch(batch)
            assertTrue(capturedSql[0].contains("'passed'"))
        }
    }

    @Test
    fun `insertBatch generates correct SQL for tags map`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        MockHttpServer { exchange ->
            capturedSql.add(exchange.requestBodyText())
            exchange.respond(200, "Ok", contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test_db", "default", "")

            val batch = QueuedSecurityBatch(
                organizationId = 1,
                batchType = "events",
                events = listOf(
                    com.moneat.datadog.security.QueuedSecurityEventEntry(
                        ruleId = "r1",
                        tags = mapOf("env" to "prod", "team" to "security"),
                        timestampMs = 1700000000000L
                    )
                )
            )
            SecurityIngestionService.insertBatch(batch)

            val sql = capturedSql[0]
            assertTrue(sql.contains("map("))
            assertTrue(sql.contains("'env'"))
            assertTrue(sql.contains("'prod'"))
        }
    }

    @Test
    fun `insertBatch generates empty map for no tags`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        MockHttpServer { exchange ->
            capturedSql.add(exchange.requestBodyText())
            exchange.respond(200, "Ok", contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test_db", "default", "")

            val batch = QueuedSecurityBatch(
                organizationId = 1,
                batchType = "events",
                events = listOf(
                    com.moneat.datadog.security.QueuedSecurityEventEntry(
                        ruleId = "r1",
                        tags = emptyMap(),
                        timestampMs = 1700000000000L
                    )
                )
            )
            SecurityIngestionService.insertBatch(batch)
            assertTrue(capturedSql[0].contains("map()"))
        }
    }

    @Test
    fun `insertBatch ignores unknown batch type`() = runBlocking {
        var callCount = 0
        MockHttpServer { exchange ->
            callCount++
            exchange.requestBodyText()
            exchange.respond(200, "Ok", contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test_db", "default", "")

            val batch = QueuedSecurityBatch(
                organizationId = 1,
                batchType = "unknown_type"
            )
            SecurityIngestionService.insertBatch(batch)
            assertEquals(0, callCount)
        }
    }

    @Test
    fun `insertBatch includes database name in SQL`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        MockHttpServer { exchange ->
            capturedSql.add(exchange.requestBodyText())
            exchange.respond(200, "Ok", contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test_db", "default", "")

            val batch = QueuedSecurityBatch(
                organizationId = 1,
                batchType = "events",
                events = listOf(
                    com.moneat.datadog.security.QueuedSecurityEventEntry(
                        ruleId = "r1",
                        timestampMs = 1700000000000L
                    )
                )
            )
            SecurityIngestionService.insertBatch(batch)
            // The lazy clickhouseDb captures the database from first init
            val sql = capturedSql[0]
            assertTrue(sql.contains("`.security_events"))
        }
    }

    @Test
    fun `insertBatch includes multiple rows in single INSERT`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        MockHttpServer { exchange ->
            capturedSql.add(exchange.requestBodyText())
            exchange.respond(200, "Ok", contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test_db", "default", "")

            val batch = QueuedSecurityBatch(
                organizationId = 1,
                batchType = "events",
                events = listOf(
                    com.moneat.datadog.security.QueuedSecurityEventEntry(
                        ruleId = "r1",
                        severity = "high",
                        timestampMs = 1700000000000L
                    ),
                    com.moneat.datadog.security.QueuedSecurityEventEntry(
                        ruleId = "r2",
                        severity = "low",
                        timestampMs = 1700000000001L
                    )
                )
            )
            SecurityIngestionService.insertBatch(batch)

            val sql = capturedSql[0]
            assertTrue(sql.contains("'r1'"))
            assertTrue(sql.contains("'r2'"))
            // Single INSERT with VALUES containing both rows
            assertEquals(1, capturedSql.size)
        }
    }
}
