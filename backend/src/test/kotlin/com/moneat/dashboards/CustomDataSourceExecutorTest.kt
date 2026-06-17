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

import com.moneat.dashboards.models.CustomDataSourceResponse
import com.moneat.dashboards.models.CustomDataSourceType
import com.moneat.dashboards.services.CustomDataSourceExecutor
import com.moneat.dashboards.services.handlers.PostgresHandler
import com.moneat.dashboards.services.handlers.PrometheusHandler
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CustomDataSourceExecutorTest {

    private companion object {
        private const val PROMETHEUS_EXAMPLE_URL = "https://example.com"
        private const val CUSTOM_SOURCE_RESOURCE_ID = "11111111-1111-1111-1111-111111111111"
        private const val DISABLED_SOURCE_RESOURCE_ID = "22222222-2222-2222-2222-222222222222"
        private const val ORG_RESOURCE_ID = "33333333-3333-3333-3333-333333333333"
        private const val USER_RESOURCE_ID = "44444444-4444-4444-4444-444444444444"
    }

    private val executor = CustomDataSourceExecutor()
    private val postgresHandler = PostgresHandler(ConcurrentHashMap())
    private val prometheusHandler = PrometheusHandler()

    // ──── SQL Validation Tests (PostgresHandler/JdbcHandler) ────

    @Test
    fun `validateSqlQuery allows SELECT queries`() {
        postgresHandler.validateSqlQuery("SELECT * FROM users LIMIT 10")
    }

    @Test
    fun `validateSqlQuery rejects INSERT`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery("INSERT INTO users VALUES (1, 'test')")
        }
        assertTrue(ex.message?.contains("INSERT") == true || ex.message?.contains("Only SELECT") == true)
    }

    @Test
    fun `validateSqlQuery rejects DELETE`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery("DELETE FROM users WHERE id = 1")
        }
        assertTrue(ex.message?.contains("DELETE") == true || ex.message?.contains("Only SELECT") == true)
    }

    @Test
    fun `validateSqlQuery rejects DROP`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery("DROP TABLE users")
        }
        assertTrue(ex.message?.contains("DROP") == true || ex.message?.contains("Only SELECT") == true)
    }

    @Test
    fun `validateSqlQuery rejects UPDATE`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery("UPDATE users SET name = 'hacked'")
        }
        assertTrue(ex.message?.contains("UPDATE") == true || ex.message?.contains("Only SELECT") == true)
    }

    @Test
    fun `validateSqlQuery rejects ALTER`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery("ALTER TABLE users ADD COLUMN hack TEXT")
        }
        assertTrue(ex.message?.contains("ALTER") == true || ex.message?.contains("Only SELECT") == true)
    }

    @Test
    fun `validateSqlQuery rejects TRUNCATE`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery("TRUNCATE TABLE users")
        }
        assertTrue(
            ex.message?.contains("TRUNCATE") == true || ex.message?.contains("Only SELECT") == true
        )
    }

    @Test
    fun `validateSqlQuery allows complex SELECT with subqueries`() {
        postgresHandler.validateSqlQuery(
            "SELECT a.*, b.count FROM users a JOIN (SELECT user_id, count(*) as count FROM orders GROUP BY user_id) b" +
                " ON a.id = b.user_id"
        )
    }

    @Test
    fun `validateSqlQuery is case insensitive`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery("delete from users")
        }
        assertTrue(ex.message?.contains("DELETE") == true || ex.message?.contains("Only SELECT") == true)
    }

    @Test
    fun `validateSqlQuery allows semicolon inside string literal`() {
        postgresHandler.validateSqlQuery("SELECT 'a;b' FROM t")
    }

    @Test
    fun `validateSqlQuery allows comment as column name`() {
        postgresHandler.validateSqlQuery("SELECT comment FROM posts")
    }

    @Test
    fun `validateSqlQuery allows lock as column name`() {
        postgresHandler.validateSqlQuery("SELECT lock FROM sessions")
    }

    @Test
    fun `validateSqlQuery rejects stacked statements`() {
        assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery("SELECT 1; DROP TABLE users")
        }
    }

    @Test
    fun `validateSqlQuery rejects LOCK TABLE`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery("LOCK TABLE users")
        }
        assertTrue(
            ex.message?.contains("LOCK") == true || ex.message?.contains("Only SELECT") == true
        )
    }

    @Test
    fun `validateSqlQuery rejects COMMENT ON`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery("SELECT 1 FROM t WHERE COMMENT ON TABLE t IS 'x'")
        }
        assertTrue(ex.message?.contains("COMMENT") == true)
    }

    @Test
    fun `validateSqlQuery rejects pg_read_file call`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery("SELECT pg_read_file('/etc/passwd')")
        }
        assertTrue(ex.message?.contains("PG_READ_FILE") == true)
    }

    @Test
    fun `validateSqlQuery allows column name containing dblink substring`() {
        postgresHandler.validateSqlQuery("SELECT my_dblink_config FROM settings")
    }

    // ──── Encoding / comment-injection bypass tests ────

    @Test
    fun `validateSqlQuery rejects comment-split INSERT keyword`() {
        // INS/**/ERT is parsed as INSERT by MySQL/MariaDB; must be caught after comment stripping
        assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery("SELECT 1 UNION ALL INS/**/ERT INTO users VALUES ('x')")
        }
    }

    @Test
    fun `validateSqlQuery rejects comment-split DROP keyword`() {
        assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery("SELECT 1; DRO/**/P TABLE users")
        }
    }

    @Test
    fun `validateSqlQuery rejects MySQL conditional comment wrapping forbidden function`() {
        // /*!pg_read_file*/ strips to pg_read_file which must still be caught
        assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery("SELECT /*!pg_read_file*/('/etc/passwd')")
        }
    }

    @Test
    fun `validateSqlQuery rejects full-width Unicode forbidden keyword`() {
        // Full-width ＩＮＳＥＲＴ normalises to INSERT via NFKC and must be caught
        assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery("SELECT 1 FROM t WHERE ＩＮＳＥＲＴ = 1")
        }
    }

    @Test
    fun `validateSqlQuery allows legitimate inline comment`() {
        postgresHandler.validateSqlQuery("SELECT /* fetch all */ * FROM users LIMIT 10")
    }

    // ──── Prometheus URL building (PrometheusHandler uses HttpApiHandler.buildUrl) ────

    @Test
    fun `buildPrometheusUrl with plain host and explicit port`() {
        val url = prometheusHandler.buildUrlString("prometheus.example.com", 9090)
        assertEquals("http://prometheus.example.com:9090", url)
    }

    @Test
    fun `buildPrometheusUrl with plain host and null port`() {
        val url = prometheusHandler.buildUrlString("prometheus.example.com", null)
        assertEquals("http://prometheus.example.com", url)
    }

    @Test
    fun `buildPrometheusUrl with http prefix`() {
        val url = prometheusHandler.buildUrlString("http://prometheus.example.com", 9090)
        assertEquals("http://prometheus.example.com:9090", url)
    }

    @Test
    fun `buildPrometheusUrl with https prefix and default port`() {
        val url = prometheusHandler.buildUrl(PROMETHEUS_EXAMPLE_URL, 443)
        assertEquals(PROMETHEUS_EXAMPLE_URL, url)
    }

    @Test
    fun `buildPrometheusUrl with https prefix and custom port`() {
        val url = prometheusHandler.buildUrl(PROMETHEUS_EXAMPLE_URL, 9090)
        assertEquals("https://example.com:9090", url)
    }

    @Test
    fun `buildPrometheusUrl with host already containing port`() {
        val url = prometheusHandler.buildUrlString("prometheus.example.com:9090", 9090)
        assertEquals("http://prometheus.example.com:9090", url)
    }

    @Test
    fun `buildPrometheusUrl strips trailing slash`() {
        val url = prometheusHandler.buildUrlString("prometheus.example.com/", 9090)
        assertEquals("http://prometheus.example.com:9090", url)
    }

    // ──── SSRF validation (HttpApiHandler.buildUrl) ────

    @Test
    fun `buildUrl blocks cloud metadata address`() {
        assertFailsWith<IllegalArgumentException> {
            prometheusHandler.buildUrl("169.254.169.254", 80)
        }
    }

    @Test
    fun `buildUrl blocks loopback address`() {
        assertFailsWith<IllegalArgumentException> {
            prometheusHandler.buildUrl("127.0.0.1", 9090)
        }
    }

    @Test
    fun `buildUrl blocks private RFC1918 address`() {
        assertFailsWith<IllegalArgumentException> {
            prometheusHandler.buildUrl("192.168.1.1", 9090) // NOSONAR - must use real RFC1918 IP to test blocking
        }
    }

    // ──── Prometheus step resolution ────

    @Test
    fun `resolvePrometheusStep for 1 hour range`() {
        assertEquals("15s", prometheusHandler.resolvePrometheusStep(3600L))
    }

    @Test
    fun `resolvePrometheusStep for 6 hour range`() {
        assertEquals("1m", prometheusHandler.resolvePrometheusStep(21600L))
    }

    @Test
    fun `resolvePrometheusStep for 24 hour range`() {
        assertEquals("5m", prometheusHandler.resolvePrometheusStep(86400L))
    }

    @Test
    fun `resolvePrometheusStep for 7 day range`() {
        assertEquals("1h", prometheusHandler.resolvePrometheusStep(604800L))
    }

    @Test
    fun `resolvePrometheusStep for 30 day range`() {
        assertEquals("1d", prometheusHandler.resolvePrometheusStep(2592000L))
    }

    // ──── Relative time resolution ────

    @Test
    fun `resolveRelativeTimeSec for now`() {
        val nowSec = 1700000000L
        assertEquals(nowSec, prometheusHandler.resolveRelativeTimeSec("now", nowSec))
    }

    @Test
    fun `resolveRelativeTimeSec for now-1h`() {
        val nowSec = 1700000000L
        assertEquals(nowSec - 3600, prometheusHandler.resolveRelativeTimeSec("now-1h", nowSec))
    }

    @Test
    fun `resolveRelativeTimeSec for now-24h`() {
        val nowSec = 1700000000L
        assertEquals(nowSec - 86400, prometheusHandler.resolveRelativeTimeSec("now-24h", nowSec))
    }

    @Test
    fun `resolveRelativeTimeSec for now-7d`() {
        val nowSec = 1700000000L
        assertEquals(nowSec - 604800, prometheusHandler.resolveRelativeTimeSec("now-7d", nowSec))
    }

    // ──── Prometheus response parsing ────

    @Test
    fun `parsePrometheusResponse handles vector result`() {
        val body = """{"status":"success","data":{"resultType":"vector","result":
            |[{"metric":{"__name__":"up","job":"api"},"value":[1700000000,"1"]}]}}
        """.trimMargin()

        val rows = prometheusHandler.parsePrometheusResponse(body, 100)
        assertEquals(1, rows.size)
        assertTrue(rows[0].containsKey("up"))
        assertEquals(1.0, (rows[0]["up"] as kotlinx.serialization.json.JsonPrimitive).double)
        assertEquals(1700000000000L, (rows[0]["time_bucket"] as kotlinx.serialization.json.JsonPrimitive).long)
        assertEquals("api", (rows[0]["job"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `parsePrometheusResponse handles matrix result`() {
        val body = """{"status":"success","data":{"resultType":"matrix","result":
            |[{"metric":{"__name__":"http_requests_total"},"values":[[1700000000,"100"],[1700000060,"105"]]}]}}
        """.trimMargin()

        val rows = prometheusHandler.parsePrometheusResponse(body, 100)
        assertEquals(2, rows.size)
        assertEquals(100.0, (rows[0]["http_requests_total"] as kotlinx.serialization.json.JsonPrimitive).double)
        assertEquals(1700000000000L, (rows[0]["time_bucket"] as kotlinx.serialization.json.JsonPrimitive).long)
        assertEquals(105.0, (rows[1]["http_requests_total"] as kotlinx.serialization.json.JsonPrimitive).double)
    }

    @Test
    fun `parsePrometheusResponse respects limit`() {
        val body = """{"status":"success","data":{"resultType":"matrix",""" +
            """"result":[{"metric":{"__name__":"m"},"values":[[1,"1"],[2,"2"],[3,"3"],[4,"4"],[5,"5"]]}]}}"""

        val rows = prometheusHandler.parsePrometheusResponse(body, 3)
        assertEquals(3, rows.size)
    }

    @Test
    fun `parsePrometheusResponse handles empty result`() {
        val body = """{"status":"success","data":{"resultType":"vector","result":[]}}"""

        val rows = prometheusHandler.parsePrometheusResponse(body, 100)
        assertEquals(0, rows.size)
    }

    @Test
    fun `parsePrometheusResponse includes label dimensions`() {
        val body = """{"status":"success","data":{"resultType":"vector","result":
            |[{"metric":{"__name__":"cpu","host":"web01","region":"us-east"},"value":[1700000000,"0.85"]}]}}
        """.trimMargin()

        val rows = prometheusHandler.parsePrometheusResponse(body, 100)
        assertEquals(1, rows.size)
        assertContains(rows[0].keys, "host")
        assertContains(rows[0].keys, "region")
    }

    // ──── Query engine custom data source detection ────

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
    fun `parseCustomDataSourceId extracts resource ID`() {
        val engine = com.moneat.dashboards.services.DashboardQueryEngine()
        assertEquals("source-42", engine.parseCustomDataSourceId("custom:source-42"))
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
            id = CUSTOM_SOURCE_RESOURCE_ID, orgId = ORG_RESOURCE_ID, name = "My PG", sourceType = "postgresql",
            host = "localhost", port = 5432, databaseName = "test",
            enabled = true, createdBy = USER_RESOURCE_ID, createdAt = "", updatedAt = "", numericId = 1L
        )
        val sources = engine.getDataSources(listOf(customSource))
        assertTrue(sources.any { it.name == "custom:$CUSTOM_SOURCE_RESOURCE_ID" })
        assertTrue(sources.any { it.name == "events" })
    }

    @Test
    fun `getDataSources excludes disabled custom sources`() {
        val engine = com.moneat.dashboards.services.DashboardQueryEngine()
        val disabledSource = CustomDataSourceResponse(
            id = DISABLED_SOURCE_RESOURCE_ID, orgId = ORG_RESOURCE_ID, name = "Disabled PG",
            sourceType = "postgresql",
            host = "localhost", port = 5432, enabled = false, createdBy = USER_RESOURCE_ID,
            createdAt = "", updatedAt = "", numericId = 2L
        )
        val sources = engine.getDataSources(listOf(disabledSource))
        assertTrue(sources.none { it.name == "custom:$DISABLED_SOURCE_RESOURCE_ID" })
    }

    // ──── Custom data source model tests ────

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
        assertEquals(null, CustomDataSourceType.fromString("unknown_db"))
        assertEquals(null, CustomDataSourceType.fromString(""))
    }
}
