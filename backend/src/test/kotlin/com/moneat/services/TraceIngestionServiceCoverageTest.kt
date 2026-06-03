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
import com.moneat.datadog.models.DdSketchSummary
import com.moneat.datadog.models.DdSpan
import com.moneat.datadog.models.DdStatsBucket
import com.moneat.datadog.models.DdStatsEntry
import com.moneat.datadog.models.DdStatsPayload
import com.moneat.datadog.services.DdApmQueryTimeRange
import com.moneat.datadog.services.DdApmQueryTimeUnit
import com.moneat.datadog.services.DdResourceStatsQuery
import com.moneat.datadog.services.DdTraceListQuery
import com.moneat.datadog.services.TraceIngestionService
import io.sentry.ISpan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.msgpack.core.MessagePack
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for TraceIngestionService covering:
 *  - parseJsonTraces: basic, multi-trace, empty, meta/metrics
 *  - parseMsgpackTraces: pack → parse round-trip
 *  - parseTraceId: valid, invalid, null
 *  - insertTraces: SQL generation via mock ClickHouse
 *  - insertTraceStats: SQL generation
 *  - listTraces / getTraceDetail / getServiceMap / getApmErrors / listResourceStats
 */
class TraceIngestionServiceCoverageTest {

    @BeforeTest
    fun setup() {
        mockkObject(ClickHouseClient)
        every { ClickHouseClient.getDatabase() } returns "test_db"
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ClickHouseClient)
    }

    // ===================== parseJsonTraces =====================

    @Test
    fun `parseJsonTraces parses single trace with one span`() {
        val json = """[[{
            "trace_id": "12345",
            "span_id": "67890",
            "parent_id": "0",
            "name": "web.request",
            "service": "api",
            "resource": "GET /users",
            "type": "web",
            "start": 1700000000000000000,
            "duration": 500000000,
            "error": 0,
            "meta": {"http.method": "GET", "http.url": "/users"},
            "metrics": {"_sampling_priority_v1": 1.0}
        }]]"""

        val traces = TraceIngestionService.parseJsonTraces(json)
        assertEquals(1, traces.size)
        assertEquals(1, traces[0].size)

        val span = traces[0][0]
        assertEquals("web.request", span.name)
        assertEquals("api", span.service)
        assertEquals("GET /users", span.resource)
        assertEquals("web", span.type)
        assertEquals(0, span.error)
        assertEquals("GET", span.meta["http.method"])
        assertEquals(1.0, span.metrics["_sampling_priority_v1"])
    }

    @Test
    fun `parseJsonTraces parses multiple traces`() {
        val json = """[
            [{"trace_id":"1","span_id":"1","parent_id":"0","name":"a","service":"s1",
              "resource":"r1","type":"","start":0,"duration":100,"error":0}],
            [{"trace_id":"2","span_id":"2","parent_id":"0","name":"b","service":"s2",
              "resource":"r2","type":"","start":0,"duration":200,"error":1}]
        ]"""

        val traces = TraceIngestionService.parseJsonTraces(json)
        assertEquals(2, traces.size)
        assertEquals("a", traces[0][0].name)
        assertEquals("b", traces[1][0].name)
        assertEquals(1, traces[1][0].error)
    }

    @Test
    fun `parseJsonTraces handles trace with multiple spans`() {
        val json = """[[
            {"trace_id":"100","span_id":"1","parent_id":"0","name":"root","service":"web",
             "resource":"/","type":"web","start":0,"duration":1000,"error":0},
            {"trace_id":"100","span_id":"2","parent_id":"1","name":"db.query","service":"postgres",
             "resource":"SELECT","type":"sql","start":100,"duration":500,"error":0}
        ]]"""

        val traces = TraceIngestionService.parseJsonTraces(json)
        assertEquals(1, traces.size)
        assertEquals(2, traces[0].size)
        assertEquals("root", traces[0][0].name)
        assertEquals("db.query", traces[0][1].name)
    }

    @Test
    fun `parseJsonTraces handles empty meta and metrics`() {
        val json = """[[{
            "trace_id":"1","span_id":"1","parent_id":"0",
            "name":"test","service":"svc","resource":"res","type":"",
            "start":0,"duration":0,"error":0
        }]]"""

        val traces = TraceIngestionService.parseJsonTraces(json)
        assertTrue(traces[0][0].meta.isEmpty())
        assertTrue(traces[0][0].metrics.isEmpty())
    }

    @Test
    fun `parseJsonTraces handles empty array`() {
        val traces = TraceIngestionService.parseJsonTraces("[]")
        assertTrue(traces.isEmpty())
    }

    // ===================== parseMsgpackTraces =====================

    @Test
    fun `parseMsgpackTraces parses single trace round-trip`() {
        val bytes = packTraces(
            listOf(
                listOf(
                    TestSpanData(
                        traceId = 12345L,
                        spanId = 67890L,
                        parentId = 0L,
                        name = "http.request",
                        service = "frontend",
                        resource = "GET /",
                        type = "web",
                        start = 1700000000000000000L,
                        duration = 500000000L,
                        error = 0,
                        meta = mapOf("http.status_code" to "200"),
                        metrics = mapOf("_dd.measured" to 1.0)
                    )
                )
            )
        )

        val traces = TraceIngestionService.parseMsgpackTraces(bytes)
        assertEquals(1, traces.size)
        assertEquals(1, traces[0].size)

        val span = traces[0][0]
        assertEquals("http.request", span.name)
        assertEquals("frontend", span.service)
        assertEquals("GET /", span.resource)
        assertEquals("200", span.meta["http.status_code"])
        assertEquals(1.0, span.metrics["_dd.measured"])
    }

    @Test
    fun `parseMsgpackTraces parses multiple traces`() {
        val bytes = packTraces(
            listOf(
                listOf(
                    TestSpanData(name = "span-a", service = "svc-a"),
                ),
                listOf(
                    TestSpanData(name = "span-b", service = "svc-b"),
                    TestSpanData(name = "span-c", service = "svc-b"),
                ),
            )
        )

        val traces = TraceIngestionService.parseMsgpackTraces(bytes)
        assertEquals(2, traces.size)
        assertEquals(1, traces[0].size)
        assertEquals(2, traces[1].size)
        assertEquals("span-a", traces[0][0].name)
        assertEquals("span-b", traces[1][0].name)
        assertEquals("span-c", traces[1][1].name)
    }

    @Test
    fun `parseMsgpackTraces handles empty traces`() {
        val bytes = packTraces(emptyList())
        val traces = TraceIngestionService.parseMsgpackTraces(bytes)
        assertTrue(traces.isEmpty())
    }

    @Test
    fun `parseMsgpackTraces handles span with empty meta and metrics`() {
        val bytes = packTraces(
            listOf(
                listOf(
                    TestSpanData(
                        name = "minimal",
                        meta = emptyMap(),
                        metrics = emptyMap()
                    )
                )
            )
        )

        val traces = TraceIngestionService.parseMsgpackTraces(bytes)
        assertTrue(traces[0][0].meta.isEmpty())
        assertTrue(traces[0][0].metrics.isEmpty())
    }

    // ===================== parseTraceId =====================

    @Test
    fun `parseTraceId with valid numeric string`() {
        val result = TraceIngestionService.parseTraceId("12345")
        assertEquals(12345uL, result)
    }

    @Test
    fun `parseTraceId with zero`() {
        val result = TraceIngestionService.parseTraceId("0")
        assertEquals(0uL, result)
    }

    @Test
    fun `parseTraceId with large number`() {
        val result = TraceIngestionService.parseTraceId("18446744073709551615")
        assertEquals(ULong.MAX_VALUE, result)
    }

    @Test
    fun `parseTraceId with invalid string returns null`() {
        assertNull(TraceIngestionService.parseTraceId("not-a-number"))
    }

    @Test
    fun `parseTraceId with empty string returns null`() {
        assertNull(TraceIngestionService.parseTraceId(""))
    }

    @Test
    fun `parseTraceId with hex string returns null`() {
        assertNull(TraceIngestionService.parseTraceId("0xDEAD"))
    }

    // ===================== insertTraces via mock ClickHouse =====================

    @Test
    fun `insertTraces sends correct SQL to ClickHouse`() = runBlocking {
        val capturedSql = mutableListOf<String>()

        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            val query = firstArg<String>()
            capturedSql.add(query)
            val response = mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.OK
            response
        }

        val traces = listOf(
            listOf(
                DdSpan(
                    traceId = 1u, spanId = 10u, parentId = 0u,
                    name = "web.request", service = "api", resource = "GET /test",
                    type = "web", start = 1700000000000000000L,
                    duration = 500000000L, error = 0,
                    meta = mapOf("env" to "prod", "http.method" to "GET"),
                    metrics = mapOf("_dd.measured" to 1.0)
                ),
                DdSpan(
                    traceId = 1u, spanId = 11u, parentId = 10u,
                    name = "db.query", service = "postgres", resource = "SELECT 1",
                    type = "sql", start = 1700000000100000000L,
                    duration = 250000000L, error = 1,
                    meta = mapOf("env" to "prod"),
                    metrics = emptyMap()
                )
            )
        )

        TraceIngestionService.insertTraces(
            organizationId = 1,
            traces = traces,
            hostname = "web-01",
            env = "production",
            appVersion = "1.0.0"
        )

        assertTrue(capturedSql.isNotEmpty())
        val sql = capturedSql.first()
        assertTrue(sql.contains("INSERT INTO"))
        assertTrue(sql.contains("apm_spans"))
        assertTrue(capturedSql.any { it.contains("apm_service_stats_hourly") })
        assertTrue(capturedSql.any { it.contains("apm_service_edges_hourly") })
        assertTrue(capturedSql.any { it.contains("'api'") && it.contains("'postgres'") })
    }

    @Test
    fun `insertTraces skips when traces are empty`() = runBlocking {
        // Should not call ClickHouse at all
        TraceIngestionService.insertTraces(
            organizationId = 1,
            traces = emptyList()
        )
        // No exception means success
    }

    // ===================== insertTraceStats via mock ClickHouse =====================

    @Test
    fun `insertTraceStats sends correct SQL`() = runBlocking {
        val capturedSql = mutableListOf<String>()

        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            capturedSql.add(firstArg())
            val response = mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.OK
            response
        }

        val payload = DdStatsPayload(
            stats = listOf(
                DdStatsBucket(
                    start = 1700000000000000000L,
                    duration = 10000000000L,
                    stats = listOf(
                        DdStatsEntry(
                            name = "web.request",
                            service = "api-service",
                            resource = "GET /users",
                            type = "web",
                            httpStatusCode = 200,
                            synthetics = false,
                            hits = 100,
                            topLevelHits = 100,
                            errors = 2,
                            duration = 500000000,
                            okSummary = DdSketchSummary(count = 98, sum = 48000000000.0),
                            errorSummary = DdSketchSummary(count = 2, sum = 2000000000.0)
                        )
                    )
                )
            ),
            hostname = "web-01",
            env = "production",
            version = "1.0.0"
        )

        TraceIngestionService.insertTraceStats(
            organizationId = 1,
            payload = payload
        )

        assertTrue(capturedSql.isNotEmpty())
        assertTrue(capturedSql.first().contains("trace_stats"))
    }

    @Test
    fun `insertTraceStats skips when stats are empty`() = runBlocking {
        val payload = DdStatsPayload(stats = emptyList())
        TraceIngestionService.insertTraceStats(organizationId = 1, payload = payload)
    }

    @Test
    fun `insertTraceStats skips when bucket has no entries`() = runBlocking {
        val payload = DdStatsPayload(
            stats = listOf(DdStatsBucket(start = 0, duration = 0, stats = emptyList()))
        )
        TraceIngestionService.insertTraceStats(organizationId = 1, payload = payload)
    }

    // ===================== listTraces via mock ClickHouse =====================

    @Test
    fun `listTraces returns empty result for blank response`() = runBlocking {
        coEvery { ClickHouseClient.executeWithFormat(any(), match { it == "TabSeparated" }) } returns "0"
        coEvery { ClickHouseClient.executeWithFormat(any(), match { it == "" }) } returns ""

        val result = TraceIngestionService.listTraces(
            organizationId = 1,
            query = DdTraceListQuery(limit = 10, offset = 0)
        )

        assertEquals(0, result.totalCount)
        assertTrue(result.traces.isEmpty())
    }

    @Test
    fun `listTraces with service filter`() = runBlocking {
        coEvery { ClickHouseClient.executeWithFormat(any(), match { it == "TabSeparated" }) } returns "1"
        coEvery {
            ClickHouseClient.executeWithFormat(
                any(),
                match {
                    it == ""
                }
            )
        } returns """
            {"trace_id_canonical":"123","root_service":"api","root_resource":"GET /","root_name":"web.request","span_count":5,"duration_ns":500000000,"start_ns":1700000000000000000,"has_error":0,"source":"datadog"}
        """.trimIndent()

        val result = TraceIngestionService.listTraces(
            organizationId = 1,
            query = DdTraceListQuery(
                service = "api",
                env = "prod",
                source = "datadog",
                status = "ok",
                limit = 10,
                offset = 0,
            )
        )

        assertEquals(1, result.totalCount)
        assertEquals(1, result.traces.size)
        assertEquals("123", result.traces[0].traceId)
        assertEquals("api", result.traces[0].rootService)
        assertEquals("GET /", result.traces[0].rootResource)
        assertEquals(5, result.traces[0].spanCount)
        assertFalse(result.traces[0].hasError)
    }

    @Test
    fun `listTraces applies search to server side query`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        coEvery { ClickHouseClient.executeWithFormat(any(), any()) } coAnswers {
            capturedQueries.add(firstArg())
            if (secondArg<String>() == "TabSeparated") {
                "0"
            } else {
                ""
            }
        }

        TraceIngestionService.listTraces(
            organizationId = 1,
            query = DdTraceListQuery(
                limit = 10,
                offset = 0,
                search = "checkout's",
            )
        )

        assertTrue(capturedQueries.any { it.contains("positionCaseInsensitive(trace_id_canonical") })
        assertTrue(capturedQueries.any { it.contains("positionCaseInsensitive(root_resource") })
        assertTrue(capturedQueries.any { it.contains("checkout\\'s") })
    }

    @Test
    fun `APM overview reads finalized table for closed buckets and summaries for the live window`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        coEvery { ClickHouseClient.executeWithFormat(any(), any()) } coAnswers {
            capturedQueries.add(firstArg())
            if (secondArg<String>() == "TabSeparated") "0" else ""
        }

        TraceIngestionService.getApmOverview(
            organizationId = 1,
            query = DdTraceListQuery(limit = 10, offset = 0),
        )

        // Hybrid read: finalized one-row-per-trace records from apm_traces_final (read plainly, NOT with
        // the slow FINAL) UNION the still-filling recent buckets aggregated live from apm_trace_summaries.
        assertTrue(capturedQueries.isNotEmpty())
        assertTrue(capturedQueries.all { it.contains("apm_traces_final") })
        // Never the slow FINAL.
        assertFalse(capturedQueries.any { it.contains("apm_traces_final FINAL") })
        // Current-window reads are hybrid: finalized (no FINAL) UNION the live summaries window. The
        // previous-comparison window is entirely finalized, so it has neither UNION nor summaries.
        assertTrue(capturedQueries.any { it.contains("UNION ALL") && it.contains("apm_trace_summaries") })
        assertTrue(capturedQueries.any { it.contains("trace_bucket < toStartOfHour(now() - INTERVAL 2 HOUR)") })
        assertTrue(capturedQueries.any { it.contains("bucket_start >= toStartOfHour(now() - INTERVAL 2 HOUR)") })
    }

    // ===================== getTraceDetail =====================

    @Test
    fun `getTraceDetail returns null for invalid trace id`() = runBlocking {
        coEvery { ClickHouseClient.executeWithFormat(any(), any()) } returns ""

        val result = TraceIngestionService.getTraceDetail(1, "not-a-number")
        assertNull(result)
    }

    @Test
    fun `getTraceDetail returns null for empty result`() = runBlocking {
        coEvery { ClickHouseClient.executeWithFormat(any(), any()) } returns ""

        val result = TraceIngestionService.getTraceDetail(1, "12345")
        assertNull(result)
    }

    @Test
    fun `getTraceDetail returns spans`() = runBlocking {
        coEvery {
            ClickHouseClient.executeWithFormat(any(), any())
        } returns """
            {"span_id_out":"10","trace_id_out":"100","parent_id_out":"0","name":"root","service":"web","resource":"GET /","type":"web","start_ns":1700000000000000000,"duration":500000000,"error":0,"meta":{},"metrics":{},"host":"web-01","env":"prod","version":"1.0"}
            {"span_id_out":"20","trace_id_out":"100","parent_id_out":"10","name":"db.query","service":"pg","resource":"SELECT","type":"sql","start_ns":1700000000100000000,"duration":200000000,"error":0,"meta":{},"metrics":{},"host":"web-01","env":"prod","version":"1.0"}
        """.trimIndent()

        val result = TraceIngestionService.getTraceDetail(1, "100")
        assertNotNull(result)
        assertEquals("100", result.traceId)
        assertEquals(2, result.spans.size)
        assertEquals("root", result.spans[0].name)
        assertEquals("db.query", result.spans[1].name)
    }

    // ===================== getServiceMap =====================

    @Test
    fun `getServiceMap returns empty result for blank response`() = runBlocking {
        coEvery { ClickHouseClient.executeWithFormat(any(), any()) } returns ""

        val result = TraceIngestionService.getServiceMap(1)
        assertTrue(result.services.isEmpty())
    }

    @Test
    fun `getServiceMap returns services with relationships`() = runBlocking {
        var callCount = 0
        val capturedQueries = mutableListOf<String>()
        coEvery { ClickHouseClient.executeWithFormat(any(), any()) } coAnswers {
            callCount++
            capturedQueries.add(firstArg())
            if (callCount == 1) {
                """
                {"service":"web","span_count":100,"error_count":5,"avg_duration_ns":500000.0}
                {"service":"db","span_count":50,"error_count":1,"avg_duration_ns":200000.0}
                """.trimIndent()
            } else {
                """
                {"from_service":"web","to_service":"db"}
                """.trimIndent()
            }
        }

        val result = TraceIngestionService.getServiceMap(1)
        assertEquals(2, result.services.size)
        assertEquals("web", result.services[0].service)
        assertEquals(listOf("db"), result.services[0].callsTo)
        assertTrue(result.services[1].callsTo.isEmpty())
        assertTrue(capturedQueries.any { it.contains("apm_service_stats_hourly") })
        assertTrue(capturedQueries.any { it.contains("apm_service_edges_hourly") })
        assertTrue(capturedQueries.none { it.contains("INNER JOIN") })
        assertTrue(capturedQueries.none { it.contains("FROM `test_db`.apm_spans") })
    }

    @Test
    fun `getServiceMap passes explicit Sentry span to ClickHouse queries`() = runBlocking {
        val span = mockk<ISpan>(relaxed = true)
        coEvery { ClickHouseClient.executeWithFormat(any(), any(), span) } returns ""

        TraceIngestionService.getServiceMap(1, span)

        coVerify(exactly = 2) {
            ClickHouseClient.executeWithFormat(any(), any(), span)
        }
    }

    // ===================== getApmErrors =====================

    @Test
    fun `getApmErrors returns empty for blank response`() = runBlocking {
        coEvery { ClickHouseClient.executeWithFormat(any(), match { it == "TabSeparated" }) } returns "0"
        coEvery { ClickHouseClient.executeWithFormat(any(), match { it == "" }) } returns ""

        val result = TraceIngestionService.getApmErrors(
            organizationId = 1,
            services = emptyList(),
            limit = 10,
            offset = 0
        )

        assertEquals(0, result.totalCount)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `getApmErrors returns error groups`() = runBlocking {
        coEvery { ClickHouseClient.executeWithFormat(any(), match { it == "TabSeparated" }) } returns "2"
        coEvery {
            ClickHouseClient.executeWithFormat(
                match {
                    it.contains("GROUP BY service, resource, error_message, error_type") &&
                        it.contains("LIMIT")
                },
                match {
                    it == ""
                }
            )
        } returns """
            {"service":"api","resource":"POST /data","error_msg":"timeout","error_type":"TimeoutError","error_count":15,"last_seen":"2024-01-15 10:00:00","sample_trace_id":"trace-1"}
            {"service":"api","resource":"GET /users","error_msg":"not found","error_type":"NotFoundError","error_count":3,"last_seen":"2024-01-15 09:00:00","sample_trace_id":"trace-2"}
        """.trimIndent()
        coEvery {
            ClickHouseClient.executeWithFormat(
                match {
                    it.contains("GROUP BY service") &&
                        !it.contains("GROUP BY service, resource, error_message, error_type")
                },
                match {
                    it == ""
                }
            )
        } returns """
            {"service":"api","error_count":18}
            {"service":"worker","error_count":4}
        """.trimIndent()

        val result = TraceIngestionService.getApmErrors(
            organizationId = 1,
            services = listOf("api"),
            limit = 10,
            offset = 0
        )

        assertEquals(2, result.totalCount)
        assertEquals(2, result.errors.size)
        assertEquals("timeout", result.errors[0].errorMessage)
        assertEquals("TimeoutError", result.errors[0].errorType)
        assertEquals(15L, result.errors[0].count)
        assertEquals(2, result.serviceFacets.size)
        assertEquals("api", result.serviceFacets[0].service)
        assertEquals(18L, result.serviceFacets[0].count)
        assertEquals("worker", result.serviceFacets[1].service)
    }

    // ===================== listResourceStats =====================

    @Test
    fun `listResourceStats returns empty for blank response`() = runBlocking {
        coEvery { ClickHouseClient.executeWithFormat(any(), match { it == "TabSeparated" }) } returns "0"
        coEvery { ClickHouseClient.executeWithFormat(any(), match { it == "" }) } returns ""

        val result = TraceIngestionService.listResourceStats(
            organizationId = 1,
            service = null,
            limit = 10,
            offset = 0
        )

        assertEquals(0, result.totalCount)
        assertTrue(result.resources.isEmpty())
    }

    @Test
    fun `listResourceStats calculates error rate and avg duration`() = runBlocking {
        coEvery { ClickHouseClient.executeWithFormat(any(), match { it == "TabSeparated" }) } returns "1"
        coEvery {
            ClickHouseClient.executeWithFormat(
                any(),
                match {
                    it == ""
                }
            )
        } returns """
            {"service":"api","resource":"GET /users","name":"web.request","type":"web","total_hits":100,"total_errors":10,"ok_sum":90000000000.0,"ok_count":90}
        """.trimIndent()

        val result = TraceIngestionService.listResourceStats(
            organizationId = 1,
            service = "api",
            limit = 10,
            offset = 0
        )

        assertEquals(1, result.totalCount)
        assertEquals(1, result.resources.size)
        val res = result.resources[0]
        assertEquals("api", res.service)
        assertEquals("GET /users", res.resource)
        assertEquals(100L, res.totalHits)
        assertEquals(10L, res.totalErrors)
        assertEquals(0.1, res.errorRate)
        assertEquals(1000000000L, res.avgDurationNs)
    }

    @Test
    fun `listResourceStats filters erroring resources after grouping all matching traces`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        coEvery { ClickHouseClient.executeWithFormat(any(), any()) } coAnswers {
            val query = firstArg<String>()
            capturedQueries.add(query)
            if (secondArg<String>() == "TabSeparated") {
                "1"
            } else {
                "{" +
                    "\"service\":\"api\"," +
                    "\"resource\":\"POST /checkout\"," +
                    "\"name\":\"web.request\"," +
                    "\"type\":\"\"," +
                    "\"total_hits\":20," +
                    "\"total_errors\":5," +
                    "\"avg_duration_ns\":300000000," +
                    "\"error_rate\":0.25" +
                    "}"
            }
        }

        val result = TraceIngestionService.listResourceStats(
            organizationId = 1,
            query = DdResourceStatsQuery(
                service = "api",
                env = "production",
                source = "otlp",
                status = "error",
                search = "checkout",
                limit = 10,
                offset = 0,
            )
        )

        assertEquals(1, result.totalCount)
        assertEquals("POST /checkout", result.resources.first().resource)
        assertEquals(20L, result.resources.first().totalHits)
        assertEquals(5L, result.resources.first().totalErrors)
        assertEquals(0.25, result.resources.first().errorRate)
        assertTrue(capturedQueries.all { it.contains("apm_traces_final") })
        assertTrue(capturedQueries.any { it.contains("root_service = 'api'") })
        assertTrue(capturedQueries.any { it.contains("env = 'production'") })
        assertTrue(capturedQueries.any { it.contains("source = 'otlp'") })
        assertTrue(capturedQueries.any { it.contains("HAVING total_errors > 0") })
        assertFalse(capturedQueries.any { it.contains("has_error = 1") })
        assertTrue(capturedQueries.any { it.contains("positionCaseInsensitive(root_resource, 'checkout')") })
    }

    @Test
    fun `APM dashboard list queries use requested time range filters`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        val timeRange = DdApmQueryTimeRange(90, DdApmQueryTimeUnit.DAY)
        coEvery { ClickHouseClient.executeWithFormat(any(), any()) } coAnswers {
            capturedQueries.add(firstArg())
            if (secondArg<String>() == "TabSeparated") {
                "0"
            } else {
                ""
            }
        }

        TraceIngestionService.listTraces(
            organizationId = 1,
            query = DdTraceListQuery(
                limit = 10,
                offset = 0,
                timeRange = timeRange,
            )
        )
        TraceIngestionService.getApmErrors(
            organizationId = 1,
            services = emptyList(),
            limit = 10,
            offset = 0,
            timeRange = timeRange
        )
        TraceIngestionService.listResourceStats(
            organizationId = 1,
            service = null,
            limit = 10,
            offset = 0,
            timeRange = timeRange
        )

        val traceQueries = capturedQueries.filter { it.contains("apm_traces_final") }
        val errorQueries = capturedQueries.filter { it.contains("apm_error_groups_hourly") }
        val resourceQueries = capturedQueries.filter { it.contains("apm_resource_stats_hourly") }
        assertEquals(2, traceQueries.size)
        assertEquals(3, errorQueries.size)
        assertEquals(2, resourceQueries.size)
        // Finalized per-trace reads filter on trace_bucket; the hourly rollups still filter bucket_start.
        assertTrue(traceQueries.all { it.contains("trace_bucket >= toStartOfHour(now() - INTERVAL 90 DAY)") })
        assertTrue(
            (errorQueries + resourceQueries)
                .all { it.contains("bucket_start >= toStartOfHour(now() - INTERVAL 90 DAY)") }
        )
        assertTrue(capturedQueries.none { it.contains("FROM `test_db`.apm_spans") })
        assertTrue(capturedQueries.none { it.contains("FROM `test_db`.trace_stats") })

        val apmErrorsDataQuery = errorQueries.first { it.contains("sumMerge(error_count_state)") }
        assertTrue(apmErrorsDataQuery.contains("GROUP BY service, resource, error_message, error_type"))
        assertTrue(
            errorQueries.any {
                it.contains("GROUP BY service") &&
                    !it.contains("GROUP BY service, resource, error_message, error_type")
            }
        )
        assertFalse(capturedQueries.any { it.contains("concat(") })
    }

    // ===================== Helper: MessagePack trace packing =====================

    private data class TestSpanData(
        val traceId: Long = 1L,
        val spanId: Long = 1L,
        val parentId: Long = 0L,
        val name: String = "test",
        val service: String = "svc",
        val resource: String = "res",
        val type: String = "",
        val start: Long = 0L,
        val duration: Long = 0L,
        val error: Int = 0,
        val meta: Map<String, String> = emptyMap(),
        val metrics: Map<String, Double> = emptyMap(),
    )

    private fun packTraces(traces: List<List<TestSpanData>>): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(traces.size)
        for (trace in traces) {
            packer.packArrayHeader(trace.size)
            for (span in trace) {
                packer.packMapHeader(12)
                packer.packString("trace_id")
                packer.packBigInteger(java.math.BigInteger.valueOf(span.traceId))
                packer.packString("span_id")
                packer.packBigInteger(java.math.BigInteger.valueOf(span.spanId))
                packer.packString("parent_id")
                packer.packBigInteger(java.math.BigInteger.valueOf(span.parentId))
                packer.packString("name")
                packer.packString(span.name)
                packer.packString("service")
                packer.packString(span.service)
                packer.packString("resource")
                packer.packString(span.resource)
                packer.packString("type")
                packer.packString(span.type)
                packer.packString("start")
                packer.packLong(span.start)
                packer.packString("duration")
                packer.packLong(span.duration)
                packer.packString("error")
                packer.packInt(span.error)
                packer.packString("meta")
                packer.packMapHeader(span.meta.size)
                for ((k, v) in span.meta) {
                    packer.packString(k)
                    packer.packString(v)
                }
                packer.packString("metrics")
                packer.packMapHeader(span.metrics.size)
                for ((k, v) in span.metrics) {
                    packer.packString(k)
                    packer.packDouble(v)
                }
            }
        }
        packer.close()
        return packer.toByteArray()
    }
}
