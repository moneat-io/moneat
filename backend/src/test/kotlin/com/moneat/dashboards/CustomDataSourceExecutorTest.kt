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

package com.moneat.dashboards

import com.moneat.dashboards.models.*
import com.moneat.dashboards.services.CustomDataSourceExecutor
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CustomDataSourceExecutorTest {

    private val executor = CustomDataSourceExecutor()

    // --- SQL Validation Tests ---

    @Test
    fun `validateSqlQuery allows SELECT queries`() {
        // Should not throw
        val method = executor.javaClass.getDeclaredMethod("validateSqlQuery", String::class.java)
        method.isAccessible = true
        method.invoke(executor, "SELECT * FROM users LIMIT 10")
    }

    @Test
    fun `validateSqlQuery rejects INSERT`() {
        val method = executor.javaClass.getDeclaredMethod("validateSqlQuery", String::class.java)
        method.isAccessible = true
        val ex = assertFailsWith<Exception> {
            method.invoke(executor, "INSERT INTO users VALUES (1, 'test')")
        }
        assertTrue(ex.cause?.message?.contains("INSERT") == true || ex.cause?.message?.contains("Only SELECT") == true)
    }

    @Test
    fun `validateSqlQuery rejects DELETE`() {
        val method = executor.javaClass.getDeclaredMethod("validateSqlQuery", String::class.java)
        method.isAccessible = true
        val ex = assertFailsWith<Exception> {
            method.invoke(executor, "DELETE FROM users WHERE id = 1")
        }
        assertTrue(ex.cause?.message?.contains("DELETE") == true || ex.cause?.message?.contains("Only SELECT") == true)
    }

    @Test
    fun `validateSqlQuery rejects DROP`() {
        val method = executor.javaClass.getDeclaredMethod("validateSqlQuery", String::class.java)
        method.isAccessible = true
        val ex = assertFailsWith<Exception> {
            method.invoke(executor, "DROP TABLE users")
        }
        assertTrue(ex.cause?.message?.contains("DROP") == true || ex.cause?.message?.contains("Only SELECT") == true)
    }

    @Test
    fun `validateSqlQuery rejects UPDATE`() {
        val method = executor.javaClass.getDeclaredMethod("validateSqlQuery", String::class.java)
        method.isAccessible = true
        val ex = assertFailsWith<Exception> {
            method.invoke(executor, "UPDATE users SET name = 'hacked'")
        }
        assertTrue(ex.cause?.message?.contains("UPDATE") == true || ex.cause?.message?.contains("Only SELECT") == true)
    }

    @Test
    fun `validateSqlQuery rejects ALTER`() {
        val method = executor.javaClass.getDeclaredMethod("validateSqlQuery", String::class.java)
        method.isAccessible = true
        val ex = assertFailsWith<Exception> {
            method.invoke(executor, "ALTER TABLE users ADD COLUMN hack TEXT")
        }
        assertTrue(ex.cause?.message?.contains("ALTER") == true || ex.cause?.message?.contains("Only SELECT") == true)
    }

    @Test
    fun `validateSqlQuery rejects TRUNCATE`() {
        val method = executor.javaClass.getDeclaredMethod("validateSqlQuery", String::class.java)
        method.isAccessible = true
        val ex = assertFailsWith<Exception> {
            method.invoke(executor, "TRUNCATE TABLE users")
        }
        assertTrue(ex.cause?.message?.contains("TRUNCATE") == true || ex.cause?.message?.contains("Only SELECT") == true)
    }

    @Test
    fun `validateSqlQuery allows complex SELECT with subqueries`() {
        val method = executor.javaClass.getDeclaredMethod("validateSqlQuery", String::class.java)
        method.isAccessible = true
        // Should not throw
        method.invoke(executor, "SELECT a.*, b.count FROM users a JOIN (SELECT user_id, count(*) as count FROM orders GROUP BY user_id) b ON a.id = b.user_id")
    }

    @Test
    fun `validateSqlQuery is case insensitive`() {
        val method = executor.javaClass.getDeclaredMethod("validateSqlQuery", String::class.java)
        method.isAccessible = true
        val ex = assertFailsWith<Exception> {
            method.invoke(executor, "delete from users")
        }
        assertTrue(ex.cause?.message?.contains("DELETE") == true || ex.cause?.message?.contains("Only SELECT") == true)
    }

    // --- Prometheus URL building ---

    @Test
    fun `buildPrometheusUrl with plain host and explicit port`() {
        val method = executor.javaClass.getDeclaredMethod("buildPrometheusUrl", String::class.java, Int::class.javaObjectType)
        method.isAccessible = true
        val url = method.invoke(executor, "prometheus.example.com", 9090) as String
        assertEquals("http://prometheus.example.com:9090", url)
    }

    @Test
    fun `buildPrometheusUrl with plain host and null port`() {
        val method = executor.javaClass.getDeclaredMethod("buildPrometheusUrl", String::class.java, Int::class.javaObjectType)
        method.isAccessible = true
        val url = method.invoke(executor, "prometheus.example.com", null) as String
        assertEquals("http://prometheus.example.com", url)
    }

    @Test
    fun `buildPrometheusUrl with http prefix`() {
        val method = executor.javaClass.getDeclaredMethod("buildPrometheusUrl", String::class.java, Int::class.javaObjectType)
        method.isAccessible = true
        val url = method.invoke(executor, "http://prometheus.example.com", 9090) as String
        assertEquals("http://prometheus.example.com:9090", url)
    }

    @Test
    fun `buildPrometheusUrl with https prefix and default port`() {
        val method = executor.javaClass.getDeclaredMethod("buildPrometheusUrl", String::class.java, Int::class.javaObjectType)
        method.isAccessible = true
        val url = method.invoke(executor, "https://prometheus.example.com", 443) as String
        assertEquals("https://prometheus.example.com", url)
    }

    @Test
    fun `buildPrometheusUrl with https prefix and custom port`() {
        val method = executor.javaClass.getDeclaredMethod("buildPrometheusUrl", String::class.java, Int::class.javaObjectType)
        method.isAccessible = true
        val url = method.invoke(executor, "https://prometheus.example.com", 9090) as String
        assertEquals("https://prometheus.example.com:9090", url)
    }

    @Test
    fun `buildPrometheusUrl with host already containing port`() {
        val method = executor.javaClass.getDeclaredMethod("buildPrometheusUrl", String::class.java, Int::class.javaObjectType)
        method.isAccessible = true
        val url = method.invoke(executor, "prometheus.example.com:9090", 9090) as String
        assertEquals("http://prometheus.example.com:9090", url)
    }

    @Test
    fun `buildPrometheusUrl strips trailing slash`() {
        val method = executor.javaClass.getDeclaredMethod("buildPrometheusUrl", String::class.java, Int::class.javaObjectType)
        method.isAccessible = true
        val url = method.invoke(executor, "prometheus.example.com/", 9090) as String
        assertEquals("http://prometheus.example.com:9090", url)
    }

    // --- Prometheus step resolution ---

    @Test
    fun `resolvePrometheusStep for 1 hour range`() {
        val method = executor.javaClass.getDeclaredMethod("resolvePrometheusStep", Long::class.java)
        method.isAccessible = true
        assertEquals("15s", method.invoke(executor, 3600L))
    }

    @Test
    fun `resolvePrometheusStep for 6 hour range`() {
        val method = executor.javaClass.getDeclaredMethod("resolvePrometheusStep", Long::class.java)
        method.isAccessible = true
        assertEquals("1m", method.invoke(executor, 21600L))
    }

    @Test
    fun `resolvePrometheusStep for 24 hour range`() {
        val method = executor.javaClass.getDeclaredMethod("resolvePrometheusStep", Long::class.java)
        method.isAccessible = true
        assertEquals("5m", method.invoke(executor, 86400L))
    }

    @Test
    fun `resolvePrometheusStep for 7 day range`() {
        val method = executor.javaClass.getDeclaredMethod("resolvePrometheusStep", Long::class.java)
        method.isAccessible = true
        assertEquals("1h", method.invoke(executor, 604800L))
    }

    @Test
    fun `resolvePrometheusStep for 30 day range`() {
        val method = executor.javaClass.getDeclaredMethod("resolvePrometheusStep", Long::class.java)
        method.isAccessible = true
        assertEquals("1d", method.invoke(executor, 2592000L))
    }

    // --- Relative time resolution ---

    @Test
    fun `resolveRelativeTimeSec for now`() {
        val method = executor.javaClass.getDeclaredMethod("resolveRelativeTimeSec", String::class.java, Long::class.java)
        method.isAccessible = true
        val nowSec = 1700000000L
        assertEquals(nowSec, method.invoke(executor, "now", nowSec))
    }

    @Test
    fun `resolveRelativeTimeSec for now-1h`() {
        val method = executor.javaClass.getDeclaredMethod("resolveRelativeTimeSec", String::class.java, Long::class.java)
        method.isAccessible = true
        val nowSec = 1700000000L
        assertEquals(nowSec - 3600, method.invoke(executor, "now-1h", nowSec))
    }

    @Test
    fun `resolveRelativeTimeSec for now-24h`() {
        val method = executor.javaClass.getDeclaredMethod("resolveRelativeTimeSec", String::class.java, Long::class.java)
        method.isAccessible = true
        val nowSec = 1700000000L
        assertEquals(nowSec - 86400, method.invoke(executor, "now-24h", nowSec))
    }

    @Test
    fun `resolveRelativeTimeSec for now-7d`() {
        val method = executor.javaClass.getDeclaredMethod("resolveRelativeTimeSec", String::class.java, Long::class.java)
        method.isAccessible = true
        val nowSec = 1700000000L
        assertEquals(nowSec - 604800, method.invoke(executor, "now-7d", nowSec))
    }

    // --- Prometheus response parsing ---

    @Test
    fun `parsePrometheusResponse handles vector result`() {
        val method = executor.javaClass.getDeclaredMethod("parsePrometheusResponse", String::class.java, Int::class.java)
        method.isAccessible = true
        val body = """{"status":"success","data":{"resultType":"vector","result":[{"metric":{"__name__":"up","job":"api"},"value":[1700000000,"1"]}]}}"""
        @Suppress("UNCHECKED_CAST")
        val rows = method.invoke(executor, body, 100) as List<Map<String, Any>>
        assertEquals(1, rows.size)
        // Metric name "up" is used as the value column key; value is parsed to numeric
        assertTrue(rows[0].containsKey("up"))
        assertEquals(1.0, (rows[0]["up"] as kotlinx.serialization.json.JsonPrimitive).double)
        // Timestamp is converted to milliseconds
        assertEquals(1700000000000L, (rows[0]["time_bucket"] as kotlinx.serialization.json.JsonPrimitive).long)
        // Labels are preserved
        assertEquals("api", (rows[0]["job"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `parsePrometheusResponse handles matrix result`() {
        val method = executor.javaClass.getDeclaredMethod("parsePrometheusResponse", String::class.java, Int::class.java)
        method.isAccessible = true
        val body = """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{"__name__":"http_requests_total"},"values":[[1700000000,"100"],[1700000060,"105"]]}]}}"""
        @Suppress("UNCHECKED_CAST")
        val rows = method.invoke(executor, body, 100) as List<Map<String, Any>>
        assertEquals(2, rows.size)
        // Values are numeric, timestamps are in ms
        assertEquals(100.0, (rows[0]["http_requests_total"] as kotlinx.serialization.json.JsonPrimitive).double)
        assertEquals(1700000000000L, (rows[0]["time_bucket"] as kotlinx.serialization.json.JsonPrimitive).long)
        assertEquals(105.0, (rows[1]["http_requests_total"] as kotlinx.serialization.json.JsonPrimitive).double)
    }

    @Test
    fun `parsePrometheusResponse respects limit`() {
        val method = executor.javaClass.getDeclaredMethod("parsePrometheusResponse", String::class.java, Int::class.java)
        method.isAccessible = true
        val body = """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{"__name__":"m"},"values":[[1,"1"],[2,"2"],[3,"3"],[4,"4"],[5,"5"]]}]}}"""
        @Suppress("UNCHECKED_CAST")
        val rows = method.invoke(executor, body, 3) as List<Map<String, Any>>
        assertEquals(3, rows.size)
    }

    @Test
    fun `parsePrometheusResponse handles empty result`() {
        val method = executor.javaClass.getDeclaredMethod("parsePrometheusResponse", String::class.java, Int::class.java)
        method.isAccessible = true
        val body = """{"status":"success","data":{"resultType":"vector","result":[]}}"""
        @Suppress("UNCHECKED_CAST")
        val rows = method.invoke(executor, body, 100) as List<Map<String, Any>>
        assertEquals(0, rows.size)
    }

    @Test
    fun `parsePrometheusResponse includes label dimensions`() {
        val method = executor.javaClass.getDeclaredMethod("parsePrometheusResponse", String::class.java, Int::class.java)
        method.isAccessible = true
        val body = """{"status":"success","data":{"resultType":"vector","result":[{"metric":{"__name__":"cpu","host":"web01","region":"us-east"},"value":[1700000000,"0.85"]}]}}"""
        @Suppress("UNCHECKED_CAST")
        val rows = method.invoke(executor, body, 100) as List<Map<String, Any>>
        assertEquals(1, rows.size)
        assertContains(rows[0].keys, "host")
        assertContains(rows[0].keys, "region")
    }

    // --- Query engine custom data source detection ---

    @Test
    fun `isCustomDataSource returns true for custom prefix`() {
        val engine = com.moneat.dashboards.services.DashboardQueryEngine()
        assertTrue(engine.isCustomDataSource("custom:123"))
    }

    @Test
    fun `isCustomDataSource returns false for built-in sources`() {
        val engine = com.moneat.dashboards.services.DashboardQueryEngine()
        assertEquals(false, engine.isCustomDataSource("events"))
        assertEquals(false, engine.isCustomDataSource("logs"))
    }

    @Test
    fun `parseCustomDataSourceId extracts numeric ID`() {
        val engine = com.moneat.dashboards.services.DashboardQueryEngine()
        assertEquals(42L, engine.parseCustomDataSourceId("custom:42"))
    }

    @Test
    fun `parseCustomDataSourceId returns null for non-custom`() {
        val engine = com.moneat.dashboards.services.DashboardQueryEngine()
        assertEquals(null, engine.parseCustomDataSourceId("events"))
    }

    @Test
    fun `getDataSources includes custom sources`() {
        val engine = com.moneat.dashboards.services.DashboardQueryEngine()
        val customSource = CustomDataSourceResponse(
            id = 1, orgId = 1, name = "My PG", sourceType = "postgresql",
            host = "localhost", port = 5432, databaseName = "test",
            enabled = true, createdBy = 1, createdAt = "", updatedAt = ""
        )
        val sources = engine.getDataSources(listOf(customSource))
        assertTrue(sources.any { it.name == "custom:1" })
        assertTrue(sources.any { it.name == "events" }) // Built-in still present
    }

    @Test
    fun `getDataSources excludes disabled custom sources`() {
        val engine = com.moneat.dashboards.services.DashboardQueryEngine()
        val disabledSource = CustomDataSourceResponse(
            id = 2, orgId = 1, name = "Disabled PG", sourceType = "postgresql",
            host = "localhost", port = 5432, enabled = false, createdBy = 1,
            createdAt = "", updatedAt = ""
        )
        val sources = engine.getDataSources(listOf(disabledSource))
        assertTrue(sources.none { it.name == "custom:2" })
    }

    // --- Custom data source model tests ---

    @Test
    fun `CustomDataSourceType fromString works for postgresql`() {
        assertEquals(CustomDataSourceType.POSTGRESQL, CustomDataSourceType.fromString("postgresql"))
    }

    @Test
    fun `CustomDataSourceType fromString works for prometheus`() {
        assertEquals(CustomDataSourceType.PROMETHEUS, CustomDataSourceType.fromString("prometheus"))
    }

    @Test
    fun `CustomDataSourceType fromString is case insensitive`() {
        assertEquals(CustomDataSourceType.POSTGRESQL, CustomDataSourceType.fromString("PostgreSQL"))
        assertEquals(CustomDataSourceType.PROMETHEUS, CustomDataSourceType.fromString("PROMETHEUS"))
    }

    @Test
    fun `CustomDataSourceType fromString returns null for unknown`() {
        assertEquals(null, CustomDataSourceType.fromString("mysql"))
        assertEquals(null, CustomDataSourceType.fromString(""))
    }
}
