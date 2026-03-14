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

import com.moneat.billing.services.PricingTierService
import com.moneat.config.ClickHouseClient
import com.moneat.events.services.DashboardQueryHelper
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.respond
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardQueryHelperTest {
    private val retentionPolicyService = mockk<RetentionPolicyService>()
    private val pricingTierService = mockk<PricingTierService>()
    private lateinit var helper: DashboardQueryHelper

    @BeforeTest
    fun setup() {
        helper = DashboardQueryHelper(retentionPolicyService, pricingTierService)
    }

    // ============ normalizeUuid ============

    @Test
    fun `normalizeUuid returns standard UUID unchanged`() {
        val uuid = "01234567-89ab-cdef-0123-456789abcdef"
        assertEquals(uuid, helper.normalizeUuid(uuid))
    }

    @Test
    fun `normalizeUuid converts 32-char hex to standard format`() {
        val hex = "0123456789abcdef0123456789abcdef"
        val expected = "01234567-89ab-cdef-0123-456789abcdef"
        assertEquals(expected, helper.normalizeUuid(hex))
    }

    @Test
    fun `normalizeUuid handles uppercase input`() {
        val uuid = "01234567-89AB-CDEF-0123-456789ABCDEF"
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", helper.normalizeUuid(uuid))
    }

    @Test
    fun `normalizeUuid handles uppercase hex input`() {
        val hex = "0123456789ABCDEF0123456789ABCDEF"
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", helper.normalizeUuid(hex))
    }

    @Test
    fun `normalizeUuid returns null for invalid input`() {
        assertNull(helper.normalizeUuid("not-a-uuid"))
        assertNull(helper.normalizeUuid(""))
        assertNull(helper.normalizeUuid("12345"))
        assertNull(helper.normalizeUuid("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"))
    }

    @Test
    fun `normalizeUuid trims whitespace`() {
        val uuid = "  01234567-89ab-cdef-0123-456789abcdef  "
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", helper.normalizeUuid(uuid))
    }

    // ============ getPeriodConfig ============

    @Test
    fun `getPeriodConfig returns correct config for 24h`() {
        val config = helper.getPeriodConfig("24h")
        assertEquals(24, config.hoursBack)
        assertEquals(60, config.intervalMinutes)
        assertEquals(24 * 60, config.periodMinutes)
    }

    @Test
    fun `getPeriodConfig returns correct config for 7d`() {
        val config = helper.getPeriodConfig("7d")
        assertEquals(168, config.hoursBack)
        assertEquals(360, config.intervalMinutes)
        assertEquals(7 * 24 * 60, config.periodMinutes)
    }

    @Test
    fun `getPeriodConfig returns correct config for 30d`() {
        val config = helper.getPeriodConfig("30d")
        assertEquals(720, config.hoursBack)
        assertEquals(1440, config.intervalMinutes)
        assertEquals(30 * 24 * 60, config.periodMinutes)
    }

    @Test
    fun `getPeriodConfig returns correct config for 90d`() {
        val config = helper.getPeriodConfig("90d")
        assertEquals(2160, config.hoursBack)
        assertEquals(4320, config.intervalMinutes)
        assertEquals(90 * 24 * 60, config.periodMinutes)
    }

    @Test
    fun `getPeriodConfig returns default 7d config for unknown period`() {
        val config = helper.getPeriodConfig("unknown")
        assertEquals(168, config.hoursBack)
        assertEquals(360, config.intervalMinutes)
        assertEquals(7 * 24 * 60, config.periodMinutes)
    }

    // ============ demoNowClause ============

    @Test
    fun `demoNowClause returns now() when no epoch provided`() {
        assertEquals("now()", helper.demoNowClause(null))
    }

    @Test
    fun `demoNowClause returns toDateTime64 when epoch provided`() {
        val result = helper.demoNowClause(1705316445000L)
        assertTrue(result.startsWith("toDateTime64("), "Expected toDateTime64, got: $result")
        assertTrue(result.contains(", 3)"), "Expected precision 3 in: $result")
    }

    // ============ timestampRetentionClause ============

    @Test
    fun `timestampRetentionClause uses now() without demo epoch`() {
        val clause = helper.timestampRetentionClause("timestamp", 30)
        assertEquals("timestamp >= now() - INTERVAL 30 DAY", clause)
    }

    @Test
    fun `timestampRetentionClause uses demo epoch when provided`() {
        val clause = helper.timestampRetentionClause("timestamp", 30, 1705316445000L)
        assertTrue(clause.contains("toDateTime64"))
        assertTrue(clause.contains("INTERVAL 30 DAY"))
    }

    // ============ buildTransactionFilterClause ============

    @Test
    fun `buildTransactionFilterClause returns empty for null params`() {
        assertEquals("", helper.buildTransactionFilterClause(null, null))
    }

    @Test
    fun `buildTransactionFilterClause returns empty for blank params`() {
        assertEquals("", helper.buildTransactionFilterClause("", ""))
    }

    @Test
    fun `buildTransactionFilterClause adds environment filter`() {
        val clause = helper.buildTransactionFilterClause("production", null)
        assertTrue(clause.contains("environment = 'production'"))
        assertTrue(clause.startsWith("AND "))
    }

    @Test
    fun `buildTransactionFilterClause adds operation filter`() {
        val clause = helper.buildTransactionFilterClause(null, "http.server")
        assertTrue(clause.contains("transaction_op = 'http.server'"))
        assertTrue(clause.startsWith("AND "))
    }

    @Test
    fun `buildTransactionFilterClause adds both filters`() {
        val clause = helper.buildTransactionFilterClause("staging", "db.query")
        assertTrue(clause.contains("environment = 'staging'"))
        assertTrue(clause.contains("transaction_op = 'db.query'"))
    }

    // ============ parseStringMap ============

    @Test
    fun `parseStringMap returns empty map for null`() {
        val result = helper.parseStringMap(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseStringMap parses JsonObject to HashMap`() {
        val obj = buildJsonObject {
            put("key1", "value1")
            put("key2", "value2")
        }
        val result = helper.parseStringMap(obj)
        assertEquals("value1", result["key1"])
        assertEquals("value2", result["key2"])
        assertEquals(2, result.size)
    }

    // ============ parseTraceContext ============

    @Test
    fun `parseTraceContext extracts trace from contexts JSON`() {
        val contexts = """{"trace":{"trace_id":"abc123","span_id":"def456"}}"""
        val trace = helper.parseTraceContext(contexts)
        assertNotNull(trace)
        assertEquals("abc123", trace["trace_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parseTraceContext returns null for invalid JSON`() {
        assertNull(helper.parseTraceContext("not json"))
    }

    @Test
    fun `parseTraceContext returns null when trace key missing`() {
        assertNull(helper.parseTraceContext("""{"os":{"name":"Linux"}}"""))
    }

    // ============ extractUserInfo ============

    @Test
    fun `extractUserInfo returns UserInfo when fields present`() {
        val obj = buildJsonObject {
            put("user_id", "u-123")
            put("user_email", "test@example.com")
            put("user_username", "testuser")
        }
        val user = helper.extractUserInfo(obj)
        assertNotNull(user)
        assertEquals("u-123", user.id)
        assertEquals("test@example.com", user.email)
        assertEquals("testuser", user.username)
    }

    @Test
    fun `extractUserInfo returns null when no user fields`() {
        val obj = buildJsonObject { put("other_field", "value") }
        assertNull(helper.extractUserInfo(obj))
    }

    @Test
    fun `extractUserInfo returns UserInfo with partial fields`() {
        val obj = buildJsonObject { put("user_email", "only@email.com") }
        val user = helper.extractUserInfo(obj)
        assertNotNull(user)
        assertNull(user.id)
        assertEquals("only@email.com", user.email)
    }

    // ============ mapEventRow ============

    @Test
    fun `mapEventRow maps all fields correctly`() {
        val obj = buildJsonObject {
            put("event_id", "evt-1")
            put("timestamp", "2026-01-15T10:00:00.000Z")
            put("message", "test error")
            put("platform", "kotlin")
            put("level", "error")
            put("environment", "prod")
            put("release", "1.0.0")
            put("user_id", "u-1")
            put("user_email", "u@test.com")
            put("user_username", "user")
            put("contexts", "{}")
            put("breadcrumbs", "[]")
        }
        val event = helper.mapEventRow(obj)
        assertEquals("evt-1", event.eventId)
        assertEquals("2026-01-15T10:00:00.000Z", event.timestamp)
        assertEquals("test error", event.message)
        assertEquals("kotlin", event.platform)
        assertEquals("error", event.level)
        assertEquals("prod", event.environment)
        assertEquals("1.0.0", event.release)
        val user = event.user
        assertNotNull(user)
        assertEquals("u-1", user.id)
    }

    @Test
    fun `mapEventRow uses custom timestamp key`() {
        val obj = buildJsonObject {
            put("event_id", "evt-1")
            put("custom_ts", "2026-03-01T00:00:00Z")
            put("message", "msg")
            put("platform", "jvm")
            put("level", "info")
            put("contexts", "{}")
        }
        val event = helper.mapEventRow(obj, "custom_ts")
        assertEquals("2026-03-01T00:00:00Z", event.timestamp)
    }

    @Test
    fun `mapEventRow defaults level to error when missing`() {
        val obj = buildJsonObject {
            put("event_id", "evt-1")
            put("timestamp", "2026-01-01T00:00:00Z")
            put("message", "msg")
            put("platform", "jvm")
            put("contexts", "{}")
        }
        val event = helper.mapEventRow(obj)
        assertEquals("error", event.level)
    }

    // ============ ClickHouse query execution (MockHttpServer) ============

    @Test
    fun `executeProjectIdQuery returns project_id from response`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, """{"project_id":42}""", "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val result = helper.executeProjectIdQuery(
                "SELECT project_id FROM test FORMAT JSONEachRow",
                "Test",
                "test-id"
            )
            assertEquals(42L, result)
        }
    }

    @Test
    fun `executeProjectIdQuery returns null on empty response`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, "", "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val result = helper.executeProjectIdQuery(
                "SELECT project_id FROM test FORMAT JSONEachRow",
                "Test",
                "test-id"
            )
            assertNull(result)
        }
    }

    @Test
    fun `executeProjectIdQuery returns null on ClickHouse error`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, "Code: 62. DB::Exception: ...", "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val result = helper.executeProjectIdQuery(
                "SELECT project_id FROM test FORMAT JSONEachRow",
                "Test",
                "test-id"
            )
            assertNull(result)
        }
    }

    @Test
    fun `executeJsonEachRowQuery parses multiple rows`() = runBlocking {
        val responseBody = """{"id":1,"name":"first"}
{"id":2,"name":"second"}"""
        MockHttpServer { exchange ->
            exchange.respond(200, responseBody, "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val rows = helper.executeJsonEachRowQuery("SELECT * FROM test FORMAT JSONEachRow")
            assertNotNull(rows)
            assertEquals(2, rows.size)
            assertEquals("first", rows[0]["name"]?.jsonPrimitive?.content)
            assertEquals("second", rows[1]["name"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `executeJsonEachRowQuery returns null on HTTP error`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(500, "Internal Server Error", "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val rows = helper.executeJsonEachRowQuery("SELECT * FROM test FORMAT JSONEachRow")
            assertNull(rows)
        }
    }

    @Test
    fun `executeScalarQuery returns total from response`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, """{"total":99}""", "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val result = helper.executeScalarQuery("SELECT count() as total FORMAT JSONEachRow")
            assertEquals(99L, result)
        }
    }

    @Test
    fun `executeScalarQuery returns 0 on error`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(500, "error", "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val result = helper.executeScalarQuery("SELECT count() as total FORMAT JSONEachRow")
            assertEquals(0L, result)
        }
    }

    @Test
    fun `executeTimelineQuery parses timeline points`() = runBlocking {
        val body = """{"time":"2026-01-01T00:00:00Z","count":5}
{"time":"2026-01-01T01:00:00Z","count":10}"""
        MockHttpServer { exchange ->
            exchange.respond(200, body, "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val points = helper.executeTimelineQuery("SELECT time, count FORMAT JSONEachRow")
            assertEquals(2, points.size)
            assertEquals("2026-01-01T00:00:00Z", points[0].timestamp)
            assertEquals(5L, points[0].count)
            assertEquals(10L, points[1].count)
        }
    }

    @Test
    fun `executeMapQuery returns key-count map`() = runBlocking {
        val body = """{"level":"error","count":15}
{"level":"warning","count":3}"""
        MockHttpServer { exchange ->
            exchange.respond(200, body, "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val result = helper.executeMapQuery("SELECT level, count FORMAT JSONEachRow", "level")
            assertEquals(15L, result["error"])
            assertEquals(3L, result["warning"])
        }
    }

    @Test
    fun `executeSlowestTransactionsQuery parses slowest transactions`() = runBlocking {
        val body = """{"event_id":"evt-1","name":"GET /api","op":"http.server","duration":1500.0,"timestamp_iso":"2026-01-01T00:00:00.000Z"}
        """.trimIndent()
        MockHttpServer { exchange ->
            exchange.respond(200, body, "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val result = helper.executeSlowestTransactionsQuery("SELECT ... FORMAT JSONEachRow")
            assertEquals(1, result.size)
            assertEquals("evt-1", result[0].eventId)
            assertEquals("GET /api", result[0].name)
            assertEquals(1500.0, result[0].duration)
        }
    }

    @Test
    fun `executeTopIssuesQuery parses top issues`() = runBlocking {
        val body = """{"issue_id":"iss-1","title":"NullPointerException","count":42}
{"issue_id":"iss-2","title":"IndexOutOfBounds","count":7}"""
        MockHttpServer { exchange ->
            exchange.respond(200, body, "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val result = helper.executeTopIssuesQuery("SELECT ... FORMAT JSONEachRow")
            assertEquals(2, result.size)
            assertEquals("iss-1", result[0].issueId)
            assertEquals(42L, result[0].count)
        }
    }

    @Test
    fun `executeMutation succeeds on 200 response`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, "", "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            helper.executeMutation("ALTER TABLE ... UPDATE ...", "test mutation")
        }
    }

    @Test
    fun `executeMutation throws on ClickHouse error`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, "Code: 62. DB::Exception: Syntax error", "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            try {
                helper.executeMutation("ALTER TABLE bad query", "test mutation")
                assertTrue(false, "Should have thrown")
            } catch (e: IllegalStateException) {
                assertTrue(e.message?.contains("ClickHouse mutation failed") == true)
            }
        }
    }
}
