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

import com.moneat.dashboards.models.TestConnectionRequest
import com.moneat.dashboards.services.handlers.JdbcHandlerCommon
import com.moneat.dashboards.services.handlers.PrometheusHandler
import com.moneat.dashboards.services.handlers.UnsupportedHandler
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DashboardHandlersCoverageTest {

    // ──── UnsupportedHandler ────

    @Test
    fun `unsupported handler testConnection returns failure`() {
        val handler = UnsupportedHandler("ClickHouse")
        val result = kotlinx.coroutines.runBlocking {
            handler.testConnection(
                TestConnectionRequest(sourceType = "clickhouse", host = "localhost")
            )
        }
        assertFalse(result.success)
        assertTrue(result.message.contains("ClickHouse"))
        assertTrue(result.message.contains("not yet implemented"))
    }

    @Test
    fun `unsupported handler executeQuery throws`() {
        val handler = UnsupportedHandler("Snowflake")
        val ex = assertFailsWith<IllegalArgumentException> {
            kotlinx.coroutines.runBlocking {
                handler.executeQuery(
                    sourceId = 1L,
                    host = "localhost",
                    port = null,
                    databaseName = null,
                    credentials = com.moneat.dashboards.services.DataSourceCredentials(),
                    query = "SELECT 1",
                    limit = 10,
                    timeRange = null
                )
            }
        }
        assertTrue(ex.message!!.contains("Snowflake"))
    }

    @Test
    fun `unsupported handler getSchema throws`() {
        val handler = UnsupportedHandler("Redis")
        val ex = assertFailsWith<IllegalArgumentException> {
            kotlinx.coroutines.runBlocking {
                handler.getSchema(
                    host = "localhost",
                    port = null,
                    databaseName = null,
                    credentials = com.moneat.dashboards.services.DataSourceCredentials()
                )
            }
        }
        assertTrue(ex.message!!.contains("Redis"))
    }

    // ──── JdbcHandlerCommon — forbidden keyword detection ────

    @Test
    fun `jdbc common wb matches whole words case insensitively`() {
        val pattern = JdbcHandlerCommon.wb("INSERT")
        assertTrue(pattern.containsMatchIn("INSERT INTO users"))
        assertTrue(pattern.containsMatchIn("insert into users"))
        assertFalse(pattern.containsMatchIn("REINSERT_DATA"))
    }

    @Test
    fun `jdbc common forbidden list covers write operations`() {
        val keywords = JdbcHandlerCommon.JDBC_COMMON_FORBIDDEN.map { it.second }
        assertTrue("INSERT" in keywords)
        assertTrue("UPDATE" in keywords)
        assertTrue("DELETE" in keywords)
        assertTrue("DROP" in keywords)
        assertTrue("ALTER" in keywords)
        assertTrue("CREATE" in keywords)
        assertTrue("TRUNCATE" in keywords)
        assertTrue("GRANT" in keywords)
        assertTrue("REVOKE" in keywords)
        assertTrue("EXEC" in keywords)
        assertTrue("EXECUTE" in keywords)
        assertTrue("COPY" in keywords)
    }

    @Test
    fun `jdbc common forbidden patterns do not match substrings`() {
        for ((regex, _) in JdbcHandlerCommon.JDBC_COMMON_FORBIDDEN) {
            assertFalse(regex.containsMatchIn("NODELETE_COLUMN"), "Pattern should not match substring")
        }
    }

    @Test
    fun `jdbc common forbidden patterns match simple statements`() {
        val testStatements = mapOf(
            "INSERT INTO t VALUES (1)" to "INSERT",
            "UPDATE t SET x=1" to "UPDATE",
            "DELETE FROM t" to "DELETE",
            "DROP TABLE t" to "DROP",
            "ALTER TABLE t ADD x INT" to "ALTER",
            "CREATE TABLE t (id INT)" to "CREATE",
            "TRUNCATE TABLE t" to "TRUNCATE",
        )
        for ((sql, keyword) in testStatements) {
            val match = JdbcHandlerCommon.JDBC_COMMON_FORBIDDEN.find { it.second == keyword }
            assertTrue(match!!.first.containsMatchIn(sql), "$keyword should match '$sql'")
        }
    }

    // ──── PrometheusHandler — resolveRelativeTimeSec ────

    @Test
    fun `resolveRelativeTimeSec returns nowSec for now`() {
        val handler = PrometheusHandler()
        assertEquals(1000L, handler.resolveRelativeTimeSec("now", 1000L))
    }

    @Test
    fun `resolveRelativeTimeSec subtracts seconds`() {
        val handler = PrometheusHandler()
        assertEquals(970L, handler.resolveRelativeTimeSec("now-30s", 1000L))
    }

    @Test
    fun `resolveRelativeTimeSec subtracts minutes`() {
        val handler = PrometheusHandler()
        assertEquals(1000L - 5 * 60, handler.resolveRelativeTimeSec("now-5m", 1000L))
    }

    @Test
    fun `resolveRelativeTimeSec subtracts hours`() {
        val handler = PrometheusHandler()
        assertEquals(1000L - 2 * 3600, handler.resolveRelativeTimeSec("now-2h", 1000L))
    }

    @Test
    fun `resolveRelativeTimeSec subtracts days`() {
        val handler = PrometheusHandler()
        assertEquals(1000L - 7 * 86400, handler.resolveRelativeTimeSec("now-7d", 1000L))
    }

    @Test
    fun `resolveRelativeTimeSec returns nowSec for invalid expr`() {
        val handler = PrometheusHandler()
        assertEquals(1000L, handler.resolveRelativeTimeSec("garbage", 1000L))
    }

    // ──── PrometheusHandler — resolvePrometheusStep ────

    @Test
    fun `resolvePrometheusStep returns 15s for short range`() {
        val handler = PrometheusHandler()
        assertEquals("15s", handler.resolvePrometheusStep(1800L))
    }

    @Test
    fun `resolvePrometheusStep returns 1m for medium range`() {
        val handler = PrometheusHandler()
        assertEquals("1m", handler.resolvePrometheusStep(10_800L))
    }

    @Test
    fun `resolvePrometheusStep returns 5m for day range`() {
        val handler = PrometheusHandler()
        assertEquals("5m", handler.resolvePrometheusStep(86_400L))
    }

    @Test
    fun `resolvePrometheusStep returns 1h for week range`() {
        val handler = PrometheusHandler()
        assertEquals("1h", handler.resolvePrometheusStep(604_800L))
    }

    @Test
    fun `resolvePrometheusStep returns 1d for long range`() {
        val handler = PrometheusHandler()
        assertEquals("1d", handler.resolvePrometheusStep(2_592_000L))
    }

    // ──── PrometheusHandler — parsePrometheusResponse ────

    @Test
    fun `parsePrometheusResponse returns empty for limit zero`() {
        val handler = PrometheusHandler()
        val result = handler.parsePrometheusResponse("""{"data":{"resultType":"vector","result":[]}}""", 0)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parsePrometheusResponse parses vector result`() {
        val handler = PrometheusHandler()
        val body = """
        {
          "data": {
            "resultType": "vector",
            "result": [
              {
                "metric": {"__name__": "cpu_usage", "instance": "host1"},
                "value": [1625000000, "0.75"]
              }
            ]
          }
        }
        """.trimIndent()
        val rows = handler.parsePrometheusResponse(body, 100)
        assertEquals(1, rows.size)
        assertEquals(JsonPrimitive(0.75), rows[0]["cpu_usage"])
        assertEquals(JsonPrimitive("host1"), rows[0]["instance"])
    }

    @Test
    fun `parsePrometheusResponse parses matrix result`() {
        val handler = PrometheusHandler()
        val body = """
        {
          "data": {
            "resultType": "matrix",
            "result": [
              {
                "metric": {"__name__": "mem_usage"},
                "values": [
                  [1625000000, "100"],
                  [1625000060, "200"]
                ]
              }
            ]
          }
        }
        """.trimIndent()
        val rows = handler.parsePrometheusResponse(body, 100)
        assertEquals(2, rows.size)
        assertEquals(JsonPrimitive(100.0), rows[0]["mem_usage"])
        assertEquals(JsonPrimitive(200.0), rows[1]["mem_usage"])
    }

    @Test
    fun `parsePrometheusResponse respects limit on matrix`() {
        val handler = PrometheusHandler()
        val body = """
        {
          "data": {
            "resultType": "matrix",
            "result": [
              {
                "metric": {"__name__": "val"},
                "values": [
                  [1, "1"], [2, "2"], [3, "3"], [4, "4"], [5, "5"]
                ]
              }
            ]
          }
        }
        """.trimIndent()
        val rows = handler.parsePrometheusResponse(body, 3)
        assertEquals(3, rows.size)
    }

    @Test
    fun `parsePrometheusResponse returns empty for missing data`() {
        val handler = PrometheusHandler()
        val result = handler.parsePrometheusResponse("""{"status":"success"}""", 100)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parsePrometheusResponse handles NaN values`() {
        val handler = PrometheusHandler()
        val body = """
        {
          "data": {
            "resultType": "vector",
            "result": [
              {
                "metric": {"__name__": "cpu"},
                "value": [1625000000, "NaN"]
              }
            ]
          }
        }
        """.trimIndent()
        val rows = handler.parsePrometheusResponse(body, 100)
        assertEquals(1, rows.size)
        assertEquals(kotlinx.serialization.json.JsonNull, rows[0]["cpu"])
    }
}
