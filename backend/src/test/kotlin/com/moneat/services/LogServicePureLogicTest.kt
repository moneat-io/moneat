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

import com.moneat.logs.models.LogEntryResponse
import com.moneat.logs.models.LogIngestEntry
import com.moneat.logs.models.LogTailFilters
import com.moneat.logs.models.QueuedLogBatch
import com.moneat.logs.models.QueuedLogEntry
import com.moneat.logs.repositories.LogRepositoryImpl
import com.moneat.logs.services.LogService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogServicePureLogicTest {
    private val service = LogService(LogRepositoryImpl())

    // ==================== estimateBillableBytes (SDK entries) ====================

    @Test
    fun `estimateBillableBytes returns 0 for empty list`() {
        assertEquals(0L, service.estimateBillableBytes(emptyList<LogIngestEntry>()))
    }

    @Test
    fun `estimateBillableBytes sums message and body lengths`() {
        val entries = listOf(
            LogIngestEntry(message = "Hello", body = "World"), // 5+5=10
            LogIngestEntry(message = "Test", body = "Log"), // 4+3=7
        )
        assertEquals(17L, service.estimateBillableBytes(entries))
    }

    @Test
    fun `estimateBillableBytes handles null message and body`() {
        val entries = listOf(
            LogIngestEntry(message = null, body = null),
        )
        // normalizeSdkEntry skips null message entries
        assertEquals(0L, service.estimateBillableBytes(entries))
    }

    @Test
    fun `estimateBillableBytes handles entries with only message`() {
        val entries = listOf(
            LogIngestEntry(message = "OnlyMessage", body = ""),
        )
        assertEquals(11L, service.estimateBillableBytes(entries))
    }

    // ==================== estimateBillableBytes (Agent entries) ====================

    @Test
    fun `estimateBillableBytes agent returns 0 for empty list`() {
        assertEquals(0L, service.estimateBillableBytes(emptyList<LogIngestEntry>()))
    }

    @Test
    fun `estimateBillableBytes agent sums message and body lengths`() {
        val entries = listOf(
            LogIngestEntry(message = "AgentMsg", body = "AgentBody"), // 8+9=17
            LogIngestEntry(message = "Short", body = "Txt"), // 5+3=8
        )
        assertEquals(25L, service.estimateBillableBytes(entries))
    }

    @Test
    fun `estimateBillableBytes agent handles null message`() {
        val entries = listOf(
            LogIngestEntry(message = null, body = "body"),
        )
        // normalizeAgentEntry returns null when message is null
        assertEquals(0L, service.estimateBillableBytes(entries))
    }

    // ==================== decodeQueueMessage / encodeQueueMessage ====================

    @Test
    fun `encodeQueueMessage then decodeQueueMessage round-trips correctly`() {
        val entry = QueuedLogEntry(
            logId = "log-uuid-1",
            timestampMs = 1738000000000L,
            level = "error",
            message = "Something failed",
            body = "Detailed failure",
            service = "api",
            environment = "production",
            host = "server-1",
            source = "sdk",
            containerName = "",
            containerId = "",
            containerImage = "",
            traceId = "trace-abc",
            spanId = "span-xyz",
            tags = mapOf("region" to "us-east"),
            resourceAttributes = mapOf("service.name" to "api")
        )
        val batch = QueuedLogBatch(
            organizationId = 42L,
            systemId = null,
            source = "sdk",
            logs = listOf(entry)
        )

        val encoded = service.encodeQueueMessage(batch)
        val decoded = service.decodeQueueMessage(encoded)

        assertEquals(42L, decoded.effectiveOrganizationId)
        assertNull(decoded.systemId)
        assertEquals("sdk", decoded.source)
        assertEquals(1, decoded.logs.size)
        assertEquals("log-uuid-1", decoded.logs.first().logId)
        assertEquals("Something failed", decoded.logs.first().message)
        assertEquals("trace-abc", decoded.logs.first().traceId)
        assertEquals("us-east", decoded.logs.first().tags["region"])
    }

    @Test
    fun `encodeQueueMessage produces JSON string`() {
        val batch = QueuedLogBatch(organizationId = 1L, source = "sdk", logs = emptyList())
        val encoded = service.encodeQueueMessage(batch)
        assertTrue(encoded.startsWith("{"))
        assertTrue(encoded.contains("organization_id"))
    }

    @Test
    fun `decodeQueueMessage accepts legacy project_id and uses effectiveOrganizationId`() {
        val legacyJson =
            """{"project_id":77,"system_id":null,"source":"sdk","logs":[{"log_id":"x",""" +
                """ "timestamp_ms":1,"level":"info","message":"m","body":"b","service":"s",""" +
                """ "environment":"e","host":"h","source":"sdk","container_name":"",""" +
                """ "container_id":"","container_image":"","trace_id":"","span_id":""}]}"""
        val decoded = service.decodeQueueMessage(legacyJson)
        assertEquals(77L, decoded.effectiveOrganizationId)
        assertEquals(77L, decoded.legacyProjectId)
        assertNull(decoded.organizationId)
    }

    // ==================== parseLiveLog ====================

    @Test
    fun `parseLiveLog returns null for invalid JSON`() {
        assertNull(service.parseLiveLog("not-json"))
    }

    @Test
    fun `parseLiveLog returns null for empty string`() {
        assertNull(service.parseLiveLog(""))
    }

    @Test
    fun `parseLiveLog deserializes valid LogEntryResponse`() {
        val json =
            """{"log_id":"abc","timestamp":"2026-01-01T00:00:00.000Z","level":"info","message":"hello",""" +
                """"body":"world","service":"api","environment":"prod","host":"h1","source":"sdk",""" +
                """"container_name":"","container_id":"","container_image":"","trace_id":"","span_id":""}"""
        val result = service.parseLiveLog(json)
        assertNotNull(result)
        assertEquals("abc", result.logId)
        assertEquals("info", result.level)
        assertEquals("hello", result.message)
    }

    // ==================== matchesTailFilters ====================

    private fun makeLog(
        level: String = "info",
        service: String = "api",
        environment: String = "prod",
        message: String = "hello world",
        body: String = "body content"
    ) = LogEntryResponse(
        logId = "test-id",
        timestamp = "2026-01-01T00:00:00.000Z",
        level = level,
        message = message,
        body = body,
        service = service,
        environment = environment,
        host = "host-1",
        source = "sdk",
        containerName = "",
        containerId = "",
        containerImage = "",
        traceId = "",
        spanId = ""
    )

    @Test
    fun `matchesTailFilters returns true for empty filters`() {
        val log = makeLog()
        val filters = LogTailFilters()
        assertTrue(service.matchesTailFilters(log, filters))
    }

    @Test
    fun `matchesTailFilters filters by level`() {
        val log = makeLog(level = "debug")
        assertTrue(service.matchesTailFilters(log, LogTailFilters(levels = setOf("debug", "info"))))
        assertFalse(service.matchesTailFilters(log, LogTailFilters(levels = setOf("error", "warning"))))
    }

    @Test
    fun `matchesTailFilters level matching is case-insensitive`() {
        val log = makeLog(level = "WARNING")
        assertTrue(service.matchesTailFilters(log, LogTailFilters(levels = setOf("warning"))))
    }

    @Test
    fun `matchesTailFilters filters by service`() {
        val log = makeLog(service = "api")
        assertTrue(service.matchesTailFilters(log, LogTailFilters(service = "api")))
        assertFalse(service.matchesTailFilters(log, LogTailFilters(service = "worker")))
    }

    @Test
    fun `matchesTailFilters service matching is case-insensitive`() {
        val log = makeLog(service = "MyService")
        assertTrue(service.matchesTailFilters(log, LogTailFilters(service = "myservice")))
    }

    @Test
    fun `matchesTailFilters filters by environment`() {
        val log = makeLog(environment = "production")
        assertTrue(service.matchesTailFilters(log, LogTailFilters(environment = "production")))
        assertFalse(service.matchesTailFilters(log, LogTailFilters(environment = "staging")))
    }

    @Test
    fun `matchesTailFilters filters by text query in message`() {
        val log = makeLog(message = "Database connection failed")
        assertTrue(service.matchesTailFilters(log, LogTailFilters(query = "database")))
        assertFalse(service.matchesTailFilters(log, LogTailFilters(query = "timeout")))
    }

    @Test
    fun `matchesTailFilters query searches body too`() {
        val log = makeLog(message = "Error occurred", body = "detailed stack trace here")
        assertTrue(service.matchesTailFilters(log, LogTailFilters(query = "stack trace")))
    }

    @Test
    fun `matchesTailFilters query is case-insensitive`() {
        val log = makeLog(message = "NullPointerException")
        assertTrue(service.matchesTailFilters(log, LogTailFilters(query = "nullpointerexception")))
    }

    @Test
    fun `matchesTailFilters combines multiple filters with AND logic`() {
        val log = makeLog(level = "error", service = "api", environment = "prod")
        assertTrue(
            service.matchesTailFilters(
                log,
                LogTailFilters(
                    levels = setOf("error"),
                    service = "api",
                    environment = "prod",
                    query = "hello"
                )
            )
        )
        // Fails if any single filter doesn't match
        assertFalse(
            service.matchesTailFilters(
                log,
                LogTailFilters(
                    levels = setOf("error"),
                    service = "api",
                    environment = "staging" // doesn't match
                )
            )
        )
    }

    @Test
    fun `matchesTailFilters ignores blank service filter`() {
        val log = makeLog(service = "api")
        assertTrue(service.matchesTailFilters(log, LogTailFilters(service = "")))
        assertTrue(service.matchesTailFilters(log, LogTailFilters(service = null)))
    }

    // ==================== autoInterval ====================

    @Test
    fun `autoInterval returns 1h for null inputs`() {
        assertEquals("1h", service.autoInterval(null, null))
        assertEquals("1h", service.autoInterval(1000L, null))
        assertEquals("1h", service.autoInterval(null, 1000L))
    }

    @Test
    fun `autoInterval returns 1m for range up to 1 hour`() {
        val from = 0L
        val to = 3_600_000L // exactly 1 hour
        assertEquals("1m", service.autoInterval(from, to))
    }

    @Test
    fun `autoInterval returns 5m for range 1h to 6h`() {
        val from = 0L
        val to = 4 * 3_600_000L // 4 hours
        assertEquals("5m", service.autoInterval(from, to))
    }

    @Test
    fun `autoInterval returns 15m for range 6h to 24h`() {
        val from = 0L
        val to = 12 * 3_600_000L // 12 hours
        assertEquals("15m", service.autoInterval(from, to))
    }

    @Test
    fun `autoInterval returns 1h for range 24h to 7d`() {
        val from = 0L
        val to = 3 * 86_400_000L // 3 days
        assertEquals("1h", service.autoInterval(from, to))
    }

    @Test
    fun `autoInterval returns 1d for range over 7d`() {
        val from = 0L
        val to = 14 * 86_400_000L // 14 days
        assertEquals("1d", service.autoInterval(from, to))
    }

    // ==================== parseOtlpJson ====================

    @Test
    fun `parseOtlpJson returns empty for invalid JSON`() {
        val result = service.parseOtlpJson("not-json")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseOtlpJson returns empty for empty JSON object`() {
        val result = service.parseOtlpJson("{}")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseOtlpJson returns empty for missing resourceLogs`() {
        val result = service.parseOtlpJson("""{"otherField": "value"}""")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseOtlpJson parses single log record`() {
        val otlpPayload = """
        {
            "resourceLogs": [{
                "resource": {
                    "attributes": [
                        {"key": "service.name", "value": {"stringValue": "my-service"}},
                        {"key": "deployment.environment", "value": {"stringValue": "production"}}
                    ]
                },
                "scopeLogs": [{
                    "logRecords": [{
                        "timeUnixNano": "1738000000000000000",
                        "severityText": "ERROR",
                        "body": {"stringValue": "Connection refused"},
                        "attributes": [
                            {"key": "component", "value": {"stringValue": "db"}}
                        ],
                        "traceId": "abc123",
                        "spanId": "def456"
                    }]
                }]
            }]
        }
        """.trimIndent()

        val result = service.parseOtlpJson(otlpPayload)
        assertEquals(1, result.size)
        val entry = result.first()
        assertEquals("Connection refused", entry.message)
        assertEquals("ERROR", entry.level)
        assertEquals("my-service", entry.service)
        assertEquals("production", entry.environment)
        assertEquals("abc123", entry.traceId)
        assertEquals("def456", entry.spanId)
        assertEquals("db", entry.tags?.get("component"))
        assertEquals("otlp", entry.source)
        assertEquals(1738000000000L, entry.timestampMs)
    }

    @Test
    fun `parseOtlpJson parses multiple records across resource logs`() {
        val otlpPayload = """
        {
            "resourceLogs": [
                {
                    "resource": {"attributes": []},
                    "scopeLogs": [{
                        "logRecords": [
                            {"body": {"stringValue": "First log"}},
                            {"body": {"stringValue": "Second log"}}
                        ]
                    }]
                },
                {
                    "resource": {"attributes": []},
                    "scopeLogs": [{
                        "logRecords": [
                            {"body": {"stringValue": "Third log"}}
                        ]
                    }]
                }
            ]
        }
        """.trimIndent()

        val result = service.parseOtlpJson(otlpPayload)
        assertEquals(3, result.size)
    }

    @Test
    fun `parseOtlpJson uses instrumentationLibraryLogs fallback`() {
        val otlpPayload = """
        {
            "resourceLogs": [{
                "resource": {"attributes": []},
                "instrumentationLibraryLogs": [{
                    "logRecords": [{
                        "body": {"stringValue": "Legacy format log"}
                    }]
                }]
            }]
        }
        """.trimIndent()

        val result = service.parseOtlpJson(otlpPayload)
        assertEquals(1, result.size)
        assertEquals("Legacy format log", result.first().message)
    }

    @Test
    fun `parseOtlpJson uses default message when body is blank`() {
        val otlpPayload = """
        {
            "resourceLogs": [{
                "resource": {"attributes": []},
                "scopeLogs": [{
                    "logRecords": [{
                        "body": {"stringValue": ""}
                    }]
                }]
            }]
        }
        """.trimIndent()

        val result = service.parseOtlpJson(otlpPayload)
        assertEquals(1, result.size)
        assertEquals("OTLP log record", result.first().message)
    }

    @Test
    fun `parseOtlpJson merges resource and record attributes`() {
        val otlpPayload = """
        {
            "resourceLogs": [{
                "resource": {
                    "attributes": [
                        {"key": "host.name", "value": {"stringValue": "server-1"}}
                    ]
                },
                "scopeLogs": [{
                    "logRecords": [{
                        "body": {"stringValue": "test"},
                        "attributes": [
                            {"key": "request.id", "value": {"stringValue": "req-123"}}
                        ]
                    }]
                }]
            }]
        }
        """.trimIndent()

        val result = service.parseOtlpJson(otlpPayload)
        assertEquals(1, result.size)
        assertEquals("server-1", result.first().host)
        assertEquals("req-123", result.first().tags?.get("request.id"))
    }
}
