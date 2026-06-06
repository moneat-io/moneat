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

package com.moneat.apm.services

import com.moneat.config.ClickHouseClient
import com.moneat.datadog.services.defaultApmQueryTimeRange
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApmServiceCatalogServiceTest {

    @Test
    fun `listServices maps span aggregates into service catalog rows`() = runBlocking {
        mockkObject(ClickHouseClient)
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            every { ClickHouseClient.getDatabase() } returns "moneat"
            coEvery { ClickHouseClient.executeWithFormat(any(), any(), any<Map<String, String>>()) } answers {
                val query = firstArg<String>()
                queries += query
                when {
                    "GROUP BY service" in query ->
                        "{" +
                            "\"name\":\"checkout-api\",\"span_count\":7200,\"error_count\":180," +
                            "\"p50_ns\":82000000,\"p95_ns\":248000000,\"p99_ns\":612000000," +
                            "\"envs\":[\"production\"],\"sources\":[\"otlp\"],\"version\":\"v3.1.2\"," +
                            "\"last_seen\":\"2026-06-06T03:20:00.000Z\",\"type\":\"web\"}"
                    else -> ""
                }
            }

            val response = ApmServiceCatalogService.listServices(
                organizationId = 12,
                query = ApmServiceQuery(timeRange = defaultApmQueryTimeRange),
            )

            val service = response.services.single()
            assertEquals("checkout-api", service.name)
            assertEquals("alerting", service.status)
            assertEquals(248, service.p95Ms)
            assertEquals(612, service.p99Ms)
            assertEquals("2.5%", service.errorRateLabel)
            assertEquals("OTLP", service.sources.single())
            assertEquals(1, response.summary.total)
            assertTrue(queries.any { "organization_id" in it && "apm_spans" in it })
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `listServices uses telemetry-derived sparkline apdex ownership and language`() = runBlocking {
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "moneat"
            coEvery { ClickHouseClient.executeWithFormat(any(), any(), any<Map<String, String>>()) } answers {
                val query = firstArg<String>()
                when {
                    "GROUP BY service, bucket_start" in query ->
                        """
                        {"service":"checkout-api","bucket_start":"2026-06-06T02:00:00.000Z","span_count":10}
                        {"service":"checkout-api","bucket_start":"2026-06-06T02:05:00.000Z","span_count":20}
                        {"service":"checkout-api","bucket_start":"2026-06-06T02:10:00.000Z","span_count":5}
                        """.trimIndent()
                    "GROUP BY service" in query ->
                        "{" +
                            "\"name\":\"checkout-api\",\"span_count\":40,\"error_count\":10," +
                            "\"p50_ns\":70000000,\"p95_ns\":248000000,\"p99_ns\":612000000," +
                            "\"apdex_satisfied\":20,\"apdex_tolerating\":10," +
                            "\"envs\":[\"production\"],\"sources\":[\"otlp\"],\"version\":\"v3.1.2\"," +
                            "\"last_seen\":\"2026-06-06T03:20:00.000Z\",\"type\":\"web\"," +
                            "\"team\":\"payments\",\"language\":\"go\"}"
                    else -> ""
                }
            }

            val response = ApmServiceCatalogService.listServices(
                organizationId = 12,
                query = ApmServiceQuery(timeRange = defaultApmQueryTimeRange),
            )

            val service = response.services.single()
            assertEquals(listOf(10, 20, 5), service.spark)
            assertEquals("0.63", service.apdex)
            assertEquals("payments", service.team)
            assertEquals("go", service.language)
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `getServiceDetail returns real deployments deltas infra and enriched errors`() = runBlocking {
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "moneat"
            coEvery { ClickHouseClient.executeWithFormat(any(), any(), any<Map<String, String>>()) } answers {
                val query = firstArg<String>()
                when {
                    "FROM `moneat`.containers_latest_by_host" in query ->
                        "{" +
                            "\"pod\":\"checkout-api-7f8d9c\",\"node\":\"prod-web-01\"," +
                            "\"cpu\":42,\"mem_usage\":2147483648,\"mem_limit\":4294967296," +
                            "\"restarts\":2,\"state\":\"running\"}"
                    "GROUP BY version" in query ->
                        listOf(
                            "{" +
                                "\"version\":\"v3.1.2\",\"first_seen\":\"2026-06-06T03:00:00.000Z\"," +
                                "\"last_seen\":\"2026-06-06T03:20:00.000Z\",\"deploy_t\":\"03:00\"," +
                                "\"span_count\":60,\"error_count\":3,\"p95_ns\":240000000}",
                            "{" +
                                "\"version\":\"v3.1.1\",\"first_seen\":\"2026-06-06T02:00:00.000Z\"," +
                                "\"last_seen\":\"2026-06-06T02:59:00.000Z\",\"deploy_t\":\"02:00\"," +
                                "\"span_count\":30,\"error_count\":0,\"p95_ns\":180000000}",
                        ).joinToString("\n")
                    "previous_span_count" in query ->
                        "{" +
                            "\"previous_span_count\":30,\"previous_error_count\":0," +
                            "\"previous_p95_ns\":180000000,\"previous_p99_ns\":400000000," +
                            "\"previous_apdex_satisfied\":20,\"previous_apdex_tolerating\":5}"
                    "FROM `moneat`.apm_spans" in query && "error_type" in query && "user_count" in query ->
                        "{" +
                            "\"resource\":\"POST /checkout\",\"error_type\":\"TimeoutError\"," +
                            "\"error_message\":\"payment timeout\",\"error_count\":7,\"user_count\":3," +
                            "\"status_code\":504,\"unhandled\":1,\"version\":\"v3.1.2\"," +
                            "\"first_seen\":\"2026-06-06T02:10:00.000Z\",\"last_seen\":\"2026-06-06T03:00:00.000Z\"}"
                    "GROUP BY trace_id_out" in query ->
                        "{" +
                            "\"trace_id_out\":\"trace-1\",\"resource\":\"POST /checkout\"," +
                            "\"name\":\"http.request\",\"http_status\":0,\"duration_ms\":620," +
                            "\"span_count\":4,\"time\":\"03:01:00\"}"
                    "GROUP BY service" in query ->
                        "{" +
                            "\"name\":\"checkout-api\",\"span_count\":60,\"error_count\":3," +
                            "\"p50_ns\":70000000,\"p95_ns\":240000000,\"p99_ns\":612000000," +
                            "\"apdex_satisfied\":40,\"apdex_tolerating\":10," +
                            "\"envs\":[\"production\"],\"sources\":[\"otlp\"],\"version\":\"v3.1.2\"," +
                            "\"last_seen\":\"2026-06-06T03:20:00.000Z\",\"type\":\"web\"," +
                            "\"team\":\"payments\",\"language\":\"go\"}"
                    "GROUP BY bucket_start" in query ->
                        "{\"t\":\"03:00\",\"p50\":70,\"p90\":180,\"p95\":240,\"p99\":612," +
                            "\"rps\":14.5,\"errors\":0.2}"
                    "GROUP BY resource" in query ->
                        "{" +
                            "\"resource\":\"POST /checkout\",\"name\":\"http.request\",\"type\":\"web\"," +
                            "\"span_count\":40,\"error_count\":4,\"p50_ns\":70000000," +
                            "\"p95_ns\":240000000,\"p99_ns\":612000000}"
                    else -> ""
                }
            }

            val detail = ApmServiceCatalogService.getServiceDetail(
                organizationId = 12,
                serviceName = "checkout-api",
                query = ApmServiceQuery(timeRange = defaultApmQueryTimeRange),
            )

            requireNotNull(detail)
            assertEquals("03:00", detail.deployAt)
            assertEquals(listOf("v3.1.2", "v3.1.1"), detail.deployments.map { deploy -> deploy.version })
            assertTrue(detail.kpis.any { kpi -> kpi.delta != null })
            assertEquals("checkout-api-7f8d9c", detail.pods.single().pod)
            assertEquals("2.0GB", detail.pods.single().mem)
            assertEquals(2, detail.pods.single().restarts)
            assertEquals("bad", detail.errorBars.single().level)
            assertEquals("fatal", detail.errors.single().severity)
            assertEquals("3", detail.errors.single().users)
            assertTrue(detail.errors.single().unhandled)
            assertTrue(detail.errors.single().chips.contains("v3.1.2"))
            assertEquals(0, detail.traces.single().httpStatus)
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `getServiceDetail returns resource rows and latency series for a service`() = runBlocking {
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "moneat"
            coEvery { ClickHouseClient.executeWithFormat(any(), any(), any<Map<String, String>>()) } answers {
                val query = firstArg<String>()
                when {
                    "GROUP BY service" in query ->
                        "{" +
                            "\"name\":\"checkout-api\",\"span_count\":7200,\"error_count\":36," +
                            "\"p50_ns\":70000000,\"p95_ns\":248000000,\"p99_ns\":612000000," +
                            "\"apdex_satisfied\":6900,\"apdex_tolerating\":200," +
                            "\"envs\":[\"production\"],\"sources\":[\"otlp\"],\"version\":\"v3.1.2\"," +
                            "\"last_seen\":\"2026-06-06T03:20:00.000Z\",\"type\":\"web\"}"
                    "GROUP BY bucket_start" in query ->
                        "{\"t\":\"03:00\",\"p50\":70,\"p90\":180,\"p95\":248,\"p99\":612," +
                            "\"rps\":14.5,\"errors\":0.2}"
                    "GROUP BY resource" in query ->
                        "{" +
                            "\"resource\":\"POST /checkout/pay\",\"name\":\"rack.request\",\"type\":\"web\"," +
                            "\"span_count\":2400,\"error_count\":24,\"p50_ns\":180000000," +
                            "\"p95_ns\":612000000,\"p99_ns\":1210000000}"
                    else -> ""
                }
            }

            val detail = ApmServiceCatalogService.getServiceDetail(
                organizationId = 12,
                serviceName = "checkout-api",
                query = ApmServiceQuery(timeRange = defaultApmQueryTimeRange),
            )

            requireNotNull(detail)
            assertEquals("checkout-api", detail.name)
            assertEquals("v3.1.2", detail.version)
            assertEquals("POST", detail.resources.single().method)
            assertEquals("/checkout/pay", detail.resources.single().name)
            assertEquals("post-checkout-pay", detail.resources.single().slug)
            assertEquals(248, detail.latency.single().p95)
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `getServiceDetail resource aggregate query does not group by aggregate aliases`() = runBlocking {
        mockkObject(ClickHouseClient)
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            every { ClickHouseClient.getDatabase() } returns "moneat"
            coEvery { ClickHouseClient.executeWithFormat(any(), any(), any<Map<String, String>>()) } answers {
                val query = firstArg<String>()
                queries += query
                when {
                    "GROUP BY service" in query ->
                        "{" +
                            "\"name\":\"api-gateway\",\"span_count\":7200,\"error_count\":36," +
                            "\"p50_ns\":70000000,\"p95_ns\":248000000,\"p99_ns\":612000000," +
                            "\"envs\":[\"production\"],\"sources\":[\"otlp\"],\"version\":\"v3.1.2\"," +
                            "\"last_seen\":\"2026-06-06T03:20:00.000Z\",\"type\":\"web\"}"
                    "GROUP BY resource" in query ->
                        "{" +
                            "\"resource\":\"GET /api/products\",\"name\":\"http.request\",\"type\":\"web\"," +
                            "\"span_count\":2400,\"error_count\":24,\"p50_ns\":180000000," +
                            "\"p95_ns\":612000000,\"p99_ns\":1210000000}"
                    "GROUP BY bucket_start" in query ->
                        "{\"t\":\"03:00\",\"p50\":70,\"p90\":180,\"p95\":248,\"p99\":612," +
                            "\"rps\":14.5,\"errors\":0.2}"
                    else -> ""
                }
            }

            val detail = ApmServiceCatalogService.getServiceDetail(
                organizationId = 12,
                serviceName = "api-gateway",
                query = ApmServiceQuery(timeRange = defaultApmQueryTimeRange),
            )

            requireNotNull(detail)
            val resourceAggregateQuery = queries.single { "argMax(name, start)" in it }
            assertTrue("GROUP BY resource, name, type" !in resourceAggregateQuery)
            assertTrue("GROUP BY resource" in resourceAggregateQuery)
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `getResourceDetail assembles dependency waterfall distribution and errors`() = runBlocking {
        mockkObject(ClickHouseClient)
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            every { ClickHouseClient.getDatabase() } returns "moneat"
            coEvery { ClickHouseClient.executeWithFormat(any(), any(), any<Map<String, String>>()) } answers {
                val query = firstArg<String>()
                queries += query
                when {
                    "span_id_out" in query ->
                        listOf(
                            "{" +
                                "\"span_id_out\":\"root\",\"parent_id_out\":\"0\",\"name\":\"POST\"," +
                                "\"service\":\"checkout-api\",\"resource\":\"POST /checkout/pay\",\"type\":\"web\"," +
                                "\"start_ns\":1000000000,\"duration_ms\":1400,\"error\":1,\"status_code\":500}",
                            "{" +
                                "\"span_id_out\":\"db\",\"parent_id_out\":\"root\",\"name\":\"SELECT\"," +
                                "\"service\":\"payments-db\",\"resource\":\"SELECT payments\",\"type\":\"db\"," +
                                "\"start_ns\":1200000000,\"duration_ms\":700,\"error\":0,\"status_code\":200}",
                        ).joinToString("\n")
                    "error_type" in query && "user_count" in query ->
                        "{" +
                            "\"resource\":\"POST /checkout/pay\",\"error_type\":\"PaymentTimeout\"," +
                            "\"error_message\":\"processor timed out\",\"error_count\":9,\"user_count\":2," +
                            "\"status_code\":504,\"unhandled\":1,\"version\":\"v3.1.2\"," +
                            "\"first_seen\":\"2026-06-06T03:00:00.000Z\"," +
                            "\"last_seen\":\"2026-06-06T03:21:00.000Z\"}"
                    "least(intDiv" in query ->
                        """
                        {"bucket_idx":0,"span_count":1}
                        {"bucket_idx":8,"span_count":5}
                        {"bucket_idx":19,"span_count":10}
                        """.trimIndent()
                    "FROM `moneat`.apm_service_edges_hourly" in query ->
                        "{" +
                            "\"peer_service\":\"payments-db\",\"call_count\":40,\"error_count\":4," +
                            "\"avg_latency_ms\":180,\"p95_latency_ms\":700}"
                    "GROUP BY trace_id_out" in query ->
                        "{" +
                            "\"trace_id_out\":\"trace-resource-1\",\"resource\":\"POST /checkout/pay\"," +
                            "\"name\":\"POST\",\"http_status\":500,\"duration_ms\":1400," +
                            "\"span_count\":8,\"time\":\"03:01:00\"}"
                    "previous_span_count" in query ->
                        "{" +
                            "\"previous_span_count\":20,\"previous_error_count\":1," +
                            "\"previous_p95_ns\":300000000,\"previous_p99_ns\":900000000," +
                            "\"previous_apdex_satisfied\":18,\"previous_apdex_tolerating\":1}"
                    "GROUP BY version" in query ->
                        "{" +
                            "\"version\":\"v3.1.2\",\"first_seen\":\"2026-06-06T03:00:00.000Z\"," +
                            "\"last_seen\":\"2026-06-06T03:20:00.000Z\",\"deploy_t\":\"03:00\"," +
                            "\"span_count\":40,\"error_count\":4,\"p95_ns\":700000000}"
                    "GROUP BY bucket_start" in query && "count() /" in query ->
                        "{\"t\":\"03:00\",\"rps\":11.5,\"errors\":0.7}"
                    "GROUP BY bucket_start" in query ->
                        "{\"t\":\"03:00\",\"p50\":110,\"p90\":620,\"p95\":700,\"p99\":1200}"
                    "argMax(name, start)" in query ->
                        "{" +
                            "\"resource\":\"POST /checkout/pay\",\"name\":\"POST /checkout/pay\",\"type\":\"web\"," +
                            "\"span_count\":40,\"error_count\":4,\"p50_ns\":110000000," +
                            "\"p95_ns\":700000000,\"p99_ns\":1200000000}"
                    "GROUP BY service" in query ->
                        "{" +
                            "\"name\":\"checkout-api\",\"span_count\":80,\"error_count\":8," +
                            "\"p50_ns\":90000000,\"p95_ns\":700000000,\"p99_ns\":1200000000," +
                            "\"apdex_satisfied\":64,\"apdex_tolerating\":8," +
                            "\"envs\":[\"production\"],\"sources\":[\"otlp\"],\"version\":\"v3.1.2\"," +
                            "\"last_seen\":\"2026-06-06T03:20:00.000Z\",\"type\":\"web\"}"
                    else -> ""
                }
            }

            val detail = ApmServiceCatalogService.getResourceDetail(
                organizationId = 12,
                serviceName = "checkout-api",
                resourceSlug = "post-checkout-pay",
                query = ApmServiceQuery(timeRange = defaultApmQueryTimeRange),
            )

            requireNotNull(detail)
            assertEquals("checkout-api", detail.serviceName)
            assertEquals("POST", detail.method)
            assertEquals("/checkout/pay", detail.path)
            assertEquals("payments-db", detail.topDependency)
            assertEquals("03:00", detail.deployAt)
            assertEquals(700, detail.latency.single().p95)
            assertEquals(11.5, detail.throughput.single().rps)
            assertEquals("payments-db contributes to the slow path for POST /checkout/pay.", detail.whereInsight)
            assertEquals("PaymentTimeout: processor timed out", detail.errors.single().title)
            assertEquals("9", detail.errors.single().events)
            assertEquals("2", detail.errors.single().users)
            assertTrue(detail.errors.single().unhandled)
            assertTrue(queries.any { query -> "uniqExactIf(user_key" in query })
            assertEquals("trace-resource-1", detail.exemplar.traceId)
            assertEquals(500, detail.exemplar.httpStatus)
            assertTrue(detail.exemplar.rows.any { row -> row.tone == "db" && row.indent != null })
            assertTrue(detail.exemplar.rows.first().selected)
            assertTrue(detail.whereTimeSpent.any { row -> row.label == "SELECT payments" })
            assertTrue(detail.distribution.any { bar -> bar.band == "bad" && bar.h == 100 })
            assertTrue(!detail.slowTraces.single().bucket.isNullOrBlank())
            assertTrue(detail.kpis.any { kpi -> kpi.delta != null })
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `getServiceDetail uses parameterized exact service lookup without catalog paging`() = runBlocking {
        mockkObject(ClickHouseClient)
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Map<String, String>>>())
        val serviceName = "worker-low-volume';DROP TABLE apm_spans;--"
        try {
            every { ClickHouseClient.getDatabase() } returns "moneat"
            coEvery { ClickHouseClient.executeWithFormat(any(), any(), any<Map<String, String>>()) } answers {
                val query = firstArg<String>()
                calls += query to thirdArg()
                when {
                    "GROUP BY service" in query ->
                        "{" +
                            "\"name\":\"worker-low-volume\",\"span_count\":12,\"error_count\":0," +
                            "\"p50_ns\":12000000,\"p95_ns\":24000000,\"p99_ns\":48000000," +
                            "\"apdex_satisfied\":12,\"apdex_tolerating\":0," +
                            "\"envs\":[\"production\"],\"sources\":[\"otlp\"],\"version\":\"v1\"," +
                            "\"last_seen\":\"2026-06-06T03:20:00.000Z\",\"type\":\"worker\"}"
                    else -> ""
                }
            }

            val detail = ApmServiceCatalogService.getServiceDetail(
                organizationId = 12,
                serviceName = serviceName,
                query = ApmServiceQuery(timeRange = defaultApmQueryTimeRange),
            )

            requireNotNull(detail)
            val serviceAggregateCall = calls.single { (query, _) -> "GROUP BY service" in query }
            val serviceAggregateQuery = serviceAggregateCall.first
            assertTrue("service = {p0:String}" in serviceAggregateQuery)
            assertTrue(serviceName !in serviceAggregateQuery)
            assertTrue(serviceAggregateCall.second.values.contains(serviceName))
            assertTrue("LIMIT 1" in serviceAggregateQuery)
            assertTrue("OFFSET" !in serviceAggregateQuery)
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `getServiceDetail runs independent detail queries concurrently`() = runBlocking {
        mockkObject(ClickHouseClient)
        val activeQueries = AtomicInteger(0)
        val maxActiveQueries = AtomicInteger(0)
        try {
            every { ClickHouseClient.getDatabase() } returns "moneat"
            coEvery { ClickHouseClient.executeWithFormat(any(), any(), any<Map<String, String>>()) } coAnswers {
                val query = firstArg<String>()
                if ("GROUP BY service" in query) {
                    return@coAnswers "{" +
                        "\"name\":\"checkout-api\",\"span_count\":7200,\"error_count\":36," +
                        "\"p50_ns\":70000000,\"p95_ns\":248000000,\"p99_ns\":612000000," +
                        "\"apdex_satisfied\":6900,\"apdex_tolerating\":200," +
                        "\"envs\":[\"production\"],\"sources\":[\"otlp\"],\"version\":\"v3.1.2\"," +
                        "\"last_seen\":\"2026-06-06T03:20:00.000Z\",\"type\":\"web\"}"
                }

                val active = activeQueries.incrementAndGet()
                maxActiveQueries.updateAndGet { current -> maxOf(current, active) }
                Thread.sleep(50)
                activeQueries.decrementAndGet()

                when {
                    "GROUP BY bucket_start" in query ->
                        "{\"t\":\"03:00\",\"p50\":70,\"p90\":180,\"p95\":248,\"p99\":612," +
                            "\"rps\":14.5,\"errors\":0.2}"
                    "GROUP BY resource" in query ->
                        "{" +
                            "\"resource\":\"POST /checkout/pay\",\"name\":\"rack.request\",\"type\":\"web\"," +
                            "\"span_count\":2400,\"error_count\":24,\"p50_ns\":180000000," +
                            "\"p95_ns\":612000000,\"p99_ns\":1210000000}"
                    else -> ""
                }
            }

            val detail = ApmServiceCatalogService.getServiceDetail(
                organizationId = 12,
                serviceName = "checkout-api",
                query = ApmServiceQuery(timeRange = defaultApmQueryTimeRange),
            )

            requireNotNull(detail)
            assertTrue(maxActiveQueries.get() > 1, "detail queries should overlap instead of running serially")
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }
}
