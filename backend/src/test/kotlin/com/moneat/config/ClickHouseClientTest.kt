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

package com.moneat.config

import com.moneat.monitoring.OperationalMetrics
import com.moneat.testsupport.respond
import com.moneat.testsupport.withClickHouseMockServer
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val READ_QUERY_MAX_EXECUTION_SECONDS = "10"

class ClickHouseClientTest {

    @AfterTest
    fun teardown() {
        ClickHouseClient.close()
        OperationalMetrics.resetForTest()
    }

    @Test
    fun `execute adds server-side timeout settings to read queries`() = runBlocking {
        val requestQueries = mutableListOf<String?>()
        withClickHouseMockServer({ exchange ->
            requestQueries.add(exchange.requestURI.rawQuery)
            exchange.respond(200, "1\n", "text/plain")
        }) {
            val response = ClickHouseClient.execute("SELECT 1")
            assertEquals(200, response.status.value)
            assertEquals("1\n", response.bodyAsText())
        }

        val params = parseQueryParams(requestQueries.single())
        assertEquals("test", params["database"])
        assertEquals(READ_QUERY_MAX_EXECUTION_SECONDS, params["max_execution_time"])
        assertEquals("throw", params["timeout_overflow_mode"])
        assertEquals("0", params["timeout_before_checking_execution_speed"])
    }

    @Test
    fun `execute does not add read query timeout settings to inserts`() = runBlocking {
        val requestQueries = mutableListOf<String?>()
        withClickHouseMockServer({ exchange ->
            requestQueries.add(exchange.requestURI.rawQuery)
            exchange.respond(200, "", "text/plain")
        }) {
            val response = ClickHouseClient.execute("INSERT INTO events VALUES (1)")
            assertEquals(200, response.status.value)
            response.bodyAsText()
        }

        val params = parseQueryParams(requestQueries.single())
        assertEquals("test", params["database"])
        assertFalse(params.containsKey("max_execution_time"))
        assertFalse(params.containsKey("timeout_overflow_mode"))
        assertFalse(params.containsKey("timeout_before_checking_execution_speed"))
    }

    @Test
    fun `executeMigration does not add regular read query timeout settings`() = runBlocking {
        val requestQueries = mutableListOf<String?>()
        withClickHouseMockServer({ exchange ->
            requestQueries.add(exchange.requestURI.rawQuery)
            exchange.respond(200, "", "text/plain")
        }) {
            val response = ClickHouseClient.executeMigration("SELECT 1")
            assertEquals(200, response.status.value)
            response.bodyAsText()
        }

        val params = parseQueryParams(requestQueries.single())
        assertEquals("test", params["database"])
        assertFalse(params.containsKey("max_execution_time"))
        assertFalse(params.containsKey("timeout_overflow_mode"))
        assertFalse(params.containsKey("timeout_before_checking_execution_speed"))
    }

    @Test
    fun `execute records HTTP failures and preserves the response body`() = runBlocking {
        OperationalMetrics.resetForTest()
        val errorBody = "Code: 36. DB::Exception: Invalid query"
        withClickHouseMockServer({ exchange ->
            exchange.responseHeaders.add("X-ClickHouse-Exception-Code", "36")
            exchange.responseHeaders.add("X-ClickHouse-Query-Id", "query-id-123")
            exchange.respond(400, errorBody, "text/plain")
        }) {
            val response = ClickHouseClient.execute("SELECT * FROM logs")
            assertEquals(400, response.status.value)
            assertEquals(errorBody, response.bodyAsText())
        }

        val rendered = OperationalMetrics.scrape()
        assertContains(rendered, "moneat_clickhouse_requests_total")
        assertContains(rendered, "operation=\"execute\"")
        assertContains(rendered, "status=\"http_400\"")
        assertContains(rendered, "moneat_clickhouse_query_errors_total")
        assertContains(rendered, "code=\"36\"")
    }

    @Test
    fun `executeWithFormat records ClickHouse error body codes`() = runBlocking {
        OperationalMetrics.resetForTest()
        withClickHouseMockServer({ exchange ->
            exchange.respond(
                200,
                "Code: 159. DB::Exception: Timeout exceeded",
                "text/plain"
            )
        }) {
            val ex = assertFailsWith<ClickHouseQueryException> {
                ClickHouseClient.executeWithFormat("SELECT 1", "TabSeparated")
            }
            assertEquals(true, ex.isTimeout)
            assertEquals("Query timed out", ex.message)
        }

        val rendered = OperationalMetrics.scrape()
        assertContains(rendered, "moneat_clickhouse_query_errors_total")
        assertContains(rendered, "operation=\"execute\"")
        assertContains(rendered, "code=\"159\"")
    }

    @Test
    fun `executeWithFormat records header error code only once`() = runBlocking {
        OperationalMetrics.resetForTest()
        withClickHouseMockServer({ exchange ->
            exchange.responseHeaders.add("X-ClickHouse-Exception-Code", "43")
            exchange.respond(500, "Code: 43. DB::Exception: Invalid argument type", "text/plain")
        }) {
            assertFailsWith<ClickHouseQueryException> {
                ClickHouseClient.executeWithFormat("SELECT 1", "TabSeparated")
            }
        }

        val samples = OperationalMetrics.scrape().lineSequence()
            .filter { line ->
                line.startsWith("moneat_clickhouse_query_errors_total") &&
                    line.contains("operation=\"execute\"") &&
                    line.contains("code=\"43\"")
            }
            .toList()
        assertEquals(1, samples.size)
        assertTrue(samples.single().endsWith(" 1.0"))
    }

    @Test
    fun `executeWithFormat detects timeout messages case insensitively`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.respond(
                200,
                "Code: 999. DB::Exception: Timeout exceeded while reading",
                "text/plain"
            )
        }) {
            val ex = assertFailsWith<ClickHouseQueryException> {
                ClickHouseClient.executeWithFormat("SELECT 1", "TabSeparated")
            }
            assertEquals(true, ex.isTimeout)
            assertEquals("Query timed out", ex.message)
        }
    }

    @Test
    fun `executeWithFormat sends default format without rewriting query body`() = runBlocking {
        val requestQueries = mutableListOf<String?>()
        val requestBodies = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            requestQueries.add(exchange.requestURI.rawQuery)
            requestBodies.add(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            exchange.respond(200, "1\n", "text/plain")
        }) {
            assertEquals("1\n", ClickHouseClient.executeWithFormat("SELECT 1", "JSONEachRow"))
        }

        val params = parseQueryParams(requestQueries.single())
        assertEquals("JSONEachRow", params["default_format"])
        assertEquals("SELECT 1", requestBodies.single())
    }

    @Test
    fun `executeWithFormat sends default format when format appears outside a clause`() = runBlocking {
        val requestQueries = mutableListOf<String?>()
        val requestBodies = mutableListOf<String>()
        val query = "SELECT 'FORMAT' AS label, formatDateTime(now(), '%F') AS rendered"
        withClickHouseMockServer({ exchange ->
            requestQueries.add(exchange.requestURI.rawQuery)
            requestBodies.add(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            exchange.respond(200, "1\n", "text/plain")
        }) {
            assertEquals("1\n", ClickHouseClient.executeWithFormat(query, "JSONEachRow"))
        }

        val params = parseQueryParams(requestQueries.single())
        assertEquals("JSONEachRow", params["default_format"])
        assertEquals(query, requestBodies.single())
    }

    @Test
    fun `executeWithFormat rejects unsupported format before request`() = runBlocking {
        val requestCount = AtomicInteger(0)
        withClickHouseMockServer({ exchange ->
            requestCount.incrementAndGet()
            exchange.respond(200, "1\n", "text/plain")
        }) {
            assertFailsWith<IllegalArgumentException> {
                ClickHouseClient.executeWithFormat("SELECT 1", "CSV")
            }
        }
        assertEquals(0, requestCount.get())
    }

    @Test
    fun `executeLongRunning completes for a successful response`() = runBlocking {
        val requestQueries = mutableListOf<String?>()
        withClickHouseMockServer({ exchange ->
            requestQueries.add(exchange.requestURI.rawQuery)
            exchange.respond(200, "", "text/plain")
        }) {
            // Should not throw on success.
            ClickHouseClient.executeLongRunning("INSERT INTO apm_traces_final SELECT 1")
        }
        // Uses the migration client path, so no read-query timeout settings are attached.
        val params = parseQueryParams(requestQueries.single())
        assertFalse(params.containsKey("max_execution_time"))
    }

    @Test
    fun `executeLongRunning throws and records the operation on a ClickHouse error`() = runBlocking {
        OperationalMetrics.resetForTest()
        withClickHouseMockServer({ exchange ->
            exchange.respond(200, "Code: 159. DB::Exception: Timeout exceeded", "text/plain")
        }) {
            val ex = assertFailsWith<ClickHouseQueryException> {
                ClickHouseClient.executeLongRunning(
                    "INSERT INTO apm_traces_final SELECT 1",
                    operation = "trace_finalize",
                )
            }
            assertEquals(true, ex.isTimeout)
        }
        val rendered = OperationalMetrics.scrape()
        assertContains(rendered, "operation=\"trace_finalize\"")
        assertContains(rendered, "code=\"159\"")
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").associate { part ->
            val tokens = part.split("=", limit = 2)
            decode(tokens[0]) to decode(tokens.getOrElse(1) { "" })
        }
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)
}
