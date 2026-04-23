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
import com.moneat.events.services.DashboardQueryHelper
import com.moneat.events.services.TransactionService
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.testsupport.OrgProjectTestFixtures
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import com.moneat.testsupport.withClickHouseMockServer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionServiceTest {

    // ──── Constants ────
    companion object {
        private const val TXN_UUID = "01234567-89ab-cdef-0123-456789abcdef"
        private const val CONTENT_TYPE_TEXT_PLAIN = "text/plain"
        private const val TRACE_1 = "trace-1"
        private var db: Database? = null
    }

    private val retentionPolicyService = mockk<RetentionPolicyService>()
    private val pricingTierService = mockk<PricingTierService>()
    private lateinit var queryHelper: DashboardQueryHelper
    private lateinit var service: TransactionService

    // ──── Test Setup ────
    @BeforeTest
    fun setup() {
        db = OrgProjectTestFixtures.connectResetAndSeedDefaultOrgProject(db, "moneat_txn_service")

        coEvery { retentionPolicyService.getRetentionDaysForProject(any()) } returns 30
        queryHelper = DashboardQueryHelper(retentionPolicyService, pricingTierService)
        service = TransactionService(queryHelper)
    }

    // ──── getProjectIdForTransaction Tests ────
    @Test
    fun `getProjectIdForTransaction returns project id`() = runBlocking {
        val eventId = TXN_UUID
        withClickHouseMockServer({ exchange ->
            exchange.respond(200, """{"project_id":42}""", CONTENT_TYPE_TEXT_PLAIN)
        }) {
            val result = service.getProjectIdForTransaction(eventId)
            assertEquals(42L, result)
        }
    }

    @Test
    fun `getProjectIdForTransaction returns null for invalid uuid`() = runBlocking {
        val result = service.getProjectIdForTransaction("not-a-uuid")
        assertNull(result)
    }

    @Test
    fun `getProjectIdForTransaction normalizes compact uuid`() = runBlocking {
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        withClickHouseMockServer({ exchange ->
            queries += exchange.requestBodyText()
            exchange.respond(200, """{"project_id":10}""", CONTENT_TYPE_TEXT_PLAIN)
        }) {
            val result = service.getProjectIdForTransaction("0123456789abcdef0123456789abcdef")
            assertEquals(10L, result)
            assertTrue(queries.any { it.contains(TXN_UUID) })
        }
    }

    // ──── getTransactions Tests ────
    @Test
    fun `getTransactions returns parsed transaction summaries`() = runBlocking {
        val row = """
{"name":"GET /api/users","op":"http.server","latest_event_id":"evt-1","count":100,"p50":150.5,"p75":250.0,"p95":500.0,"failure_rate":0.05,"tpm":12.5}
        """.trimIndent()
        withClickHouseMockServer({ exchange ->
            exchange.respond(200, row, CONTENT_TYPE_TEXT_PLAIN)
        }) {
            val result = service.getTransactions(projectId = 1, period = "24h")
            assertEquals(1, result.size)
            assertEquals("GET /api/users", result[0].name)
            assertEquals("http.server", result[0].op)
            assertEquals(100L, result[0].count)
            assertEquals(150.5, result[0].p50)
            assertEquals(0.05, result[0].failureRate)
            assertEquals(12.5, result[0].tpm)
        }
    }

    @Test
    fun `getTransactions returns empty list on error`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.respond(500, "Internal Server Error", CONTENT_TYPE_TEXT_PLAIN)
        }) {
            val result = service.getTransactions(projectId = 1)
            assertTrue(result.isEmpty())
        }
    }

    // ──── getPerformanceStats Tests ────
    @Test
    fun `getPerformanceStats returns apdex and stats`() = runBlocking {
        var requestCount = 0
        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            requestCount++
            when {
                query.contains("satisfied") && query.contains("tolerated") -> {
                    exchange.respond(
                        200,
                        """{"total":1000,"avg_duration":250.0,"satisfied":800,"tolerated":150}""",
                        CONTENT_TYPE_TEXT_PLAIN
                    )
                }
                query.contains("toStartOfInterval") -> {
                    exchange.respond(
                        200,
                        """{"time":"2026-01-01T00:00:00.000Z","count":50}""",
                        CONTENT_TYPE_TEXT_PLAIN
                    )
                }
                query.contains("ORDER BY duration_ms DESC") -> {
                    exchange.respond(
                        200,
                        """{"event_id":"evt-1","name":"GET /slow","op":"http","duration":5000.0,"timestamp_iso":"2026-01-01T00:00:00.000Z"}
                        """.trimIndent(),
                        CONTENT_TYPE_TEXT_PLAIN
                    )
                }
                else -> exchange.respond(200, "", CONTENT_TYPE_TEXT_PLAIN)
            }
        }) {
            val stats = service.getPerformanceStats(projectId = 1, period = "7d")
            assertEquals(1000L, stats.totalTransactions)
            assertEquals(250.0, stats.avgDuration)
            // APDEX = (800 + 150 * 0.5) / 1000 = 0.875
            assertEquals(0.875, stats.apdex, 0.001)
            assertTrue(stats.throughput.isNotEmpty())
            assertTrue(stats.slowestTransactions.isNotEmpty())
        }
    }

    @Test
    fun `getPerformanceStats returns zeros on error`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.respond(500, "error", CONTENT_TYPE_TEXT_PLAIN)
        }) {
            val stats = service.getPerformanceStats(projectId = 1)
            assertEquals(0.0, stats.apdex)
            assertEquals(0L, stats.totalTransactions)
            assertEquals(0.0, stats.avgDuration)
            assertTrue(stats.throughput.isEmpty())
            assertTrue(stats.slowestTransactions.isEmpty())
        }
    }

    @Test
    fun `getPerformanceStats apdex is 0 when no transactions`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("satisfied") -> {
                    exchange.respond(
                        200,
                        """{"total":0,"avg_duration":0.0,"satisfied":0,"tolerated":0}""",
                        CONTENT_TYPE_TEXT_PLAIN
                    )
                }
                else -> exchange.respond(200, "", CONTENT_TYPE_TEXT_PLAIN)
            }
        }) {
            val stats = service.getPerformanceStats(projectId = 1)
            assertEquals(0.0, stats.apdex)
            assertEquals(0L, stats.totalTransactions)
        }
    }

    // ──── getTransaction Tests ────
    @Test
    fun `getTransaction returns detail for valid event`() = runBlocking {
        val eventId = TXN_UUID
        val row = """
{"event_id":"$eventId","name":"GET /api","op":"http.server","start_ts_ms":1000,"duration":250.0,"trace_id":"$TRACE_1","timestamp":"2026-01-01T00:00:00.000Z","environment":"prod","release":"1.0","status":"ok","tags":{"env":"prod"},"contexts":{},"breadcrumbs":[],"request":{}}
        """.trimIndent()
        withClickHouseMockServer({ exchange ->
            exchange.respond(200, row, CONTENT_TYPE_TEXT_PLAIN)
        }) {
            val detail = service.getTransaction(eventId)
            assertNotNull(detail)
            assertEquals(eventId, detail.eventId)
            assertEquals("GET /api", detail.name)
            assertEquals("http.server", detail.op)
            assertEquals(TRACE_1, detail.traceId)
            assertEquals("prod", detail.environment)
            assertEquals(1.0, detail.startTimestamp)
        }
    }

    @Test
    fun `getTransaction returns null for invalid uuid`() = runBlocking {
        assertNull(service.getTransaction("bad-id"))
    }

    @Test
    fun `getTransaction returns null when not found`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.respond(200, "", CONTENT_TYPE_TEXT_PLAIN)
        }) {
            assertNull(service.getTransaction(TXN_UUID))
        }
    }

    // ──── Trace and Span Tests ────
    @Test
    fun `getTraceDetails assembles spans into trace`() = runBlocking {
        val body =
            """{"span_id":"s1","parent_span_id":"","trace_id":"$TRACE_1","meta":""" +
                """{"sentry.transaction_id":"tx-1","sentry.project_id":"1"},""" +
                """"op":"http.server","description":"GET /","start_ns":"1000000000",""" +
                """"duration_ns":"1500000000","error":0}""" +
                "\n" +
                """{"span_id":"s2","parent_span_id":"s1","trace_id":"$TRACE_1","meta":""" +
                """{"sentry.transaction_id":"tx-1","sentry.project_id":"1"},""" +
                """"op":"db","description":"SELECT","start_ns":"1200000000",""" +
                """"duration_ns":"600000000","error":0}"""
        withClickHouseMockServer({ exchange ->
            exchange.respond(200, body, CONTENT_TYPE_TEXT_PLAIN)
        }) {
            val trace = service.getTraceDetails(projectId = 1, traceId = TRACE_1)
            assertNotNull(trace)
            assertEquals(TRACE_1, trace.traceId)
            assertEquals(2, trace.spans.size)
            assertEquals(1.0, trace.startTimestamp)
            assertEquals(2.5, trace.endTimestamp)
            assertEquals(1500.0, trace.duration)
        }
    }

    @Test
    fun `getTraceDetails returns null when no spans`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.respond(200, "", CONTENT_TYPE_TEXT_PLAIN)
        }) {
            assertNull(service.getTraceDetails(projectId = 1, traceId = "missing"))
        }
    }

    // ──── getSpanDetails Tests ────
    @Test
    fun `getSpanDetails returns span with transaction`() = runBlocking {
        var callCount = 0
        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            callCount++
            when {
                query.contains("apm_spans") && query.contains("span_id_hex") -> {
                    exchange.respond(
                        200,
                        """{"span_id":"s1","parent_span_id":"","trace_id":"t1","meta":{"sentry.transaction_id":"01234567-89ab-cdef-0123-456789abcdef","sentry.project_id":"1"},"op":"db","description":"SELECT","start_ns":"1000000000","duration_ns":"500000000","error":0}
                        """.trimIndent(),
                        CONTENT_TYPE_TEXT_PLAIN
                    )
                }
                query.contains("events") && query.contains("transaction") -> {
                    exchange.respond(
                        200,
                        """{"event_id":"01234567-89ab-cdef-0123-456789abcdef","name":"GET /","op":"http","start_ts_ms":500,"duration":2000.0,"trace_id":"t1","timestamp":"2026-01-01T00:00:00.000Z","environment":"prod","release":"1.0","status":"ok","tags":{},"contexts":{},"breadcrumbs":[],"request":{}}
                        """.trimIndent(),
                        CONTENT_TYPE_TEXT_PLAIN
                    )
                }
                else -> exchange.respond(200, "", CONTENT_TYPE_TEXT_PLAIN)
            }
        }) {
            val detail = service.getSpanDetails(projectId = 1, spanId = "s1")
            assertNotNull(detail)
            assertEquals("s1", detail.span.spanId)
            assertEquals("db", detail.span.op)
            assertNotNull(detail.transaction)
        }
    }

    // ──── getRelatedErrorsForTransaction Tests ────
    @Test
    fun `getRelatedErrorsForTransaction returns errors for trace`() = runBlocking {
        var callCount = 0
        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            callCount++
            when {
                // getTransaction call
                query.contains("event_type = 'transaction'") &&
                    query.contains("LIMIT 1") && !query.contains("project_id") -> {
                    exchange.respond(
                        200,
                        """{"event_id":"01234567-89ab-cdef-0123-456789abcdef","name":"GET /","op":"http","start_ts_ms":1000,"duration":500.0,"trace_id":"trace-abc","timestamp":"2026-01-01T00:00:00.000Z","environment":"prod","release":"1.0","status":"ok","tags":{},"contexts":{},"breadcrumbs":[],"request":{}}
                        """.trimIndent(),
                        CONTENT_TYPE_TEXT_PLAIN
                    )
                }
                // getProjectIdForTransaction
                query.contains("toInt64(project_id)") -> {
                    exchange.respond(200, """{"project_id":1}""", CONTENT_TYPE_TEXT_PLAIN)
                }
                // related errors
                query.contains("event_type = 'error'") && query.contains("trace_id") -> {
                    exchange.respond(
                        200,
                        """{"event_id":"err-1","timestamp":"2026-01-01T00:01:00.000Z","message":"NPE","platform":"jvm","level":"error","environment":"prod","release":"1.0","user_id":"","user_email":"","user_username":"","tags":{},"contexts":{},"exception":"NullPointerException","breadcrumbs":[]}
                        """.trimIndent(),
                        CONTENT_TYPE_TEXT_PLAIN
                    )
                }
                else -> exchange.respond(200, "", CONTENT_TYPE_TEXT_PLAIN)
            }
        }) {
            val errors = service.getRelatedErrorsForTransaction(TXN_UUID)
            assertEquals(1, errors.size)
            assertEquals("err-1", errors[0].eventId)
            assertEquals("NPE", errors[0].message)
        }
    }

    @Test
    fun `getRelatedErrorsForTransaction returns empty for invalid event`() = runBlocking {
        val result = service.getRelatedErrorsForTransaction("not-a-uuid")
        assertTrue(result.isEmpty())
    }
}
