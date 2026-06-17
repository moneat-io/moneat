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

package com.moneat.datadog.routes

import com.moneat.config.ClickHouseClient
import com.moneat.datadog.models.DdApmErrorsResponse
import com.moneat.datadog.models.DdApmOverviewFacets
import com.moneat.datadog.models.DdApmOverviewPreviousStats
import com.moneat.datadog.models.DdApmOverviewResponse
import com.moneat.datadog.models.DdApmOverviewStats
import com.moneat.datadog.models.DdProfileListResponse
import com.moneat.datadog.models.DdProfileResponse
import com.moneat.datadog.models.DdProfileServicesResponse
import com.moneat.datadog.models.DdProfileTimeseriesResponse
import com.moneat.datadog.models.DdResourceStatsResponse
import com.moneat.datadog.models.DdServiceMapResponse
import com.moneat.datadog.models.DdTraceListResponse
import com.moneat.datadog.services.DatadogHostService
import com.moneat.datadog.services.DatadogJfrFlamegraphService
import com.moneat.datadog.services.DatadogPprofFlamegraphService
import com.moneat.datadog.services.DdResourceStatsQuery
import com.moneat.datadog.services.DdHostInfo
import com.moneat.datadog.services.DdTraceListQuery
import com.moneat.datadog.services.ProfileIngestionService
import com.moneat.datadog.services.ProfileMergeService
import com.moneat.datadog.services.ProfileStorageService
import com.moneat.datadog.services.TraceIngestionService
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.statement.HttpResponse
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DatadogQueryRoutesTest {

    @BeforeTest
    fun setup() {
        startTestKoin()
        mockkObject(ClickHouseClient)
        mockkObject(DatadogHostService)
        mockkObject(TraceIngestionService)
        mockkObject(ProfileIngestionService)
        mockkObject(ProfileMergeService)
        mockkObject(ProfileStorageService)
        mockkStatic(HttpResponse::bodyAsText)

        every { ClickHouseClient.getDatabase() } returns "testdb"
        every { DatadogHostService.listHosts(any<Int>()) } returns emptyList()
        every { DatadogHostService.getHost(any<Int>(), any<Uuid>()) } returns null
        every { DatadogHostService.deleteHost(any<Int>(), any<Uuid>()) } returns false
        coEvery { TraceIngestionService.listResourceStats(any(), any<DdResourceStatsQuery>(), any()) } returns
            DdResourceStatsResponse(emptyList(), 0L)
        coEvery {
            TraceIngestionService.listTraces(any(), any<DdTraceListQuery>(), any())
        } returns
            DdTraceListResponse(emptyList(), 0L)
        coEvery { TraceIngestionService.getApmOverview(any(), any<DdTraceListQuery>(), any()) } returns
            emptyApmOverview()
        coEvery { TraceIngestionService.getTraceDetail(any(), any(), any()) } returns null
        coEvery { TraceIngestionService.getServiceMap(any(), any(), any(), any(), any()) } returns
            DdServiceMapResponse(emptyList())
        coEvery { TraceIngestionService.getApmErrors(any(), any(), any(), any(), any(), any()) } returns
            DdApmErrorsResponse(emptyList(), 0L)
        coEvery { ProfileIngestionService.listProfiles(any(), any()) } returns
            DdProfileListResponse(emptyList(), 0L)
        coEvery { ProfileIngestionService.getProfileMeta(any(), any()) } returns null
        every { ProfileStorageService.read(any()) } returns null
        coEvery { ProfileIngestionService.getProfile(any(), any()) } returns null
        coEvery { ProfileIngestionService.listServices(any(), any(), any()) } returns
            DdProfileServicesResponse(emptyList(), 0L, 0L, 0, 0L, 0)
        coEvery { ProfileIngestionService.timeseries(any()) } returns DdProfileTimeseriesResponse(emptyList(), 60L)
        coEvery { ProfileMergeService.mergeFlamegraph(any()) } returns buildJsonObject {
            put("frames", buildJsonArray {})
        }
    }

    @AfterTest
    fun teardown() {
        unmockkStatic(HttpResponse::bodyAsText)
        unmockkObject(ProfileMergeService)
        unmockkObject(ProfileStorageService)
        unmockkObject(ProfileIngestionService)
        unmockkObject(TraceIngestionService)
        unmockkObject(DatadogHostService)
        unmockkObject(ClickHouseClient)
        stopTestKoin()
    }

    private fun installInfraQueryRoutes(): io.ktor.server.testing.ApplicationTestBuilder.() -> Unit = {
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing {
                datadogInfraQueryRoutes()
                datadogEventQueryRoutes()
            }
        }
    }

    private fun installHostRoutes(): io.ktor.server.testing.ApplicationTestBuilder.() -> Unit = {
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { datadogHostQueryRoutes() }
        }
    }

    private fun installTraceRoutes(): io.ktor.server.testing.ApplicationTestBuilder.() -> Unit = {
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { traceDashboardRoutes() }
        }
    }

    private fun installProfileRoutes(): io.ktor.server.testing.ApplicationTestBuilder.() -> Unit = {
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { profileDashboardRoutes() }
        }
    }

    // ──── Infra Query Routes — 401 without JWT ────

    @Test
    fun `processes returns 401 without jwt`() = testApplication {
        installInfraQueryRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/infra/processes").status)
    }

    @Test
    fun `containers returns 401 without jwt`() = testApplication {
        installInfraQueryRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/infra/containers").status)
    }

    @Test
    fun `connections returns 401 without jwt`() = testApplication {
        installInfraQueryRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/infra/connections").status)
    }

    @Test
    fun `events returns 401 without jwt`() = testApplication {
        installInfraQueryRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/infra/events").status)
    }

    @Test
    fun `service-checks returns 401 without jwt`() = testApplication {
        installInfraQueryRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/infra/service-checks").status)
    }

    // ──── Infra Query Routes — Missing org context with JWT ────

    @Test
    fun `processes returns unauthorized when jwt lacks orgId`() = testApplication {
        installInfraQueryRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = null)
        val resp = client.get("/v1/infra/processes") { withAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `containers returns unauthorized when jwt lacks orgId`() = testApplication {
        installInfraQueryRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = null)
        val resp = client.get("/v1/infra/containers") { withAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `connections returns unauthorized when jwt lacks orgId`() = testApplication {
        installInfraQueryRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = null)
        val resp = client.get("/v1/infra/connections") { withAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `events returns unauthorized when jwt lacks orgId`() = testApplication {
        installInfraQueryRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = null)
        val resp = client.get("/v1/infra/events") { withAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `service-checks returns unauthorized when jwt lacks orgId`() = testApplication {
        installInfraQueryRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = null)
        val resp = client.get("/v1/infra/service-checks") { withAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // ──── Host Query Routes — 401 without JWT ────

    @Test
    fun `hosts list returns 401 without jwt`() = testApplication {
        installHostRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/hosts").status)
    }

    @Test
    fun `hosts detail returns 401 without jwt`() = testApplication {
        installHostRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/hosts/1").status)
    }

    @Test
    fun `hosts delete returns 401 without jwt`() = testApplication {
        installHostRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/v1/hosts/1").status)
    }

    @Test
    fun `hosts metrics returns 401 without jwt`() = testApplication {
        installHostRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/hosts/1/metrics").status)
    }

    @Test
    fun `hosts containers returns 401 without jwt`() = testApplication {
        installHostRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/hosts/1/containers").status)
    }

    // ──── Host Query Routes — Missing org context ────

    @Test
    fun `hosts list returns unauthorized when jwt lacks orgId`() = testApplication {
        installHostRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = null)
        val resp = client.get("/v1/hosts") { withAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `hosts detail returns unauthorized when jwt lacks orgId`() = testApplication {
        installHostRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = null)
        val resp = client.get("/v1/hosts/1") { withAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `hosts detail returns bad request for non-numeric id`() = testApplication {
        installHostRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/hosts/abc") { withAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `hosts detail returns not found for unknown host`() = testApplication {
        installHostRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/hosts/11111111-1111-4111-8111-111111111111") { withAuth(token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `hosts delete returns not found for unknown host`() = testApplication {
        installHostRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.delete("/v1/hosts/11111111-1111-4111-8111-111111111111") { withAuth(token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `hosts metrics uses normalized rollups and computes dashboard values`() = testApplication {
        val hostId = Uuid.parse(TEST_HOST_RESOURCE_ID)
        val queries = mutableListOf<String>()
        val clickHouseResponse = mockk<HttpResponse>()
        every { DatadogHostService.getHost(10, hostId) } returns sampleHost()
        every { clickHouseResponse.status } returns HttpStatusCode.OK
        coEvery { clickHouseResponse.bodyAsText(any()) } returns normalizedHostMetricRows()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            queries.add(firstArg())
            clickHouseResponse
        }
        installHostRoutes()()

        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get(
            "/v1/hosts/$TEST_HOST_RESOURCE_ID/metrics" +
                "?from=2026-06-15T00%3A00%3A00Z&to=2026-06-15T01%3A00%3A00Z"
        ) {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("\"cpu_percent\":100.0"), body)
        assertTrue(body.contains("\"mem_percent\":80.0"), body)
        assertTrue(body.contains("\"mem_percent\":30.0"), body)
        assertTrue(body.contains("\"disk_percent\":71.5"), body)
        assertTrue(body.contains("\"net_recv_bytes\":1024.0"), body)
        assertTrue(body.contains("\"net_sent_bytes\":2048.0"), body)
        assertTrue(body.contains("\"load_1\":1.25"), body)

        val query = queries.single()
        assertTrue(query.contains("metrics_rollup_1m"))
        assertTrue(query.contains("host_id = 42"))
        assertTrue(query.contains("bucket_start >= toDateTime('2026-06-15T00:00:00Z')"))
        assertTrue(query.contains("bucket_start <= toDateTime('2026-06-15T01:00:00Z')"))
        assertTrue(query.contains("'system.mem.available'"))
        assertTrue(query.contains("'system.disk.percent'"))
        assertTrue(query.contains("'system.net.recv_bytes'"))
        assertFalse(query.contains("system.mem.pct_usable"))
        assertFalse(query.contains("system.disk.in_use"))
        assertFalse(query.contains("system.net.bytes_rcvd"))
    }

    @Test
    fun `hosts metrics returns empty data points when rollup query fails`() = testApplication {
        val hostId = Uuid.parse(TEST_HOST_RESOURCE_ID)
        val clickHouseResponse = mockk<HttpResponse>()
        every { DatadogHostService.getHost(10, hostId) } returns sampleHost()
        every { clickHouseResponse.status } returns HttpStatusCode.InternalServerError
        coEvery { ClickHouseClient.execute(any()) } returns clickHouseResponse
        installHostRoutes()()

        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/hosts/$TEST_HOST_RESOURCE_ID/metrics") {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"data_points\":[]"))
    }

    @Test
    fun `hosts containers returns latest container rows`() = testApplication {
        val hostId = Uuid.parse(TEST_HOST_RESOURCE_ID)
        val queries = mutableListOf<String>()
        val clickHouseResponse = mockk<HttpResponse>()
        every { DatadogHostService.getHost(10, hostId) } returns sampleHost()
        every { clickHouseResponse.status } returns HttpStatusCode.OK
        coEvery { clickHouseResponse.bodyAsText(any()) } returns normalizedContainerRows()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            queries.add(firstArg())
            clickHouseResponse
        }
        installHostRoutes()()

        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/hosts/$TEST_HOST_RESOURCE_ID/containers") {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("\"totalCount\":2"), body)
        assertTrue(body.contains("\"name\":\"api\""), body)
        assertTrue(body.contains("\"image\":\"ghcr.io/moneat/api:1\""), body)
        assertTrue(body.contains("\"name\":\"worker\""), body)
        assertTrue(body.contains("\"cpuPercent\":12.5"), body)
        assertTrue(body.contains("\"netTxBytes\":456.0"), body)

        val query = queries.single()
        assertTrue(query.contains("containers_latest_by_host"))
        assertTrue(query.contains("host_id = 42"))
        assertTrue(query.contains("INTERVAL 10 MINUTE"))
    }

    // ──── Trace Dashboard Routes — 401 without JWT ────

    @Test
    fun `traces resources returns 401 without jwt`() = testApplication {
        installTraceRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/traces/resources").status)
    }

    @Test
    fun `traces list returns 401 without jwt`() = testApplication {
        installTraceRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/traces").status)
    }

    @Test
    fun `traces overview returns 401 without jwt`() = testApplication {
        installTraceRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/traces/overview").status)
    }

    @Test
    fun `trace detail returns 401 without jwt`() = testApplication {
        installTraceRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/traces/12345").status)
    }

    @Test
    fun `service map returns 401 without jwt`() = testApplication {
        installTraceRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/services/map").status)
    }

    @Test
    fun `apm errors returns 401 without jwt`() = testApplication {
        installTraceRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/apm-errors").status)
    }

    // ──── Trace Dashboard Routes — Missing org context ────

    @Test
    fun `traces resources returns unauthorized when jwt lacks orgId`() = testApplication {
        installTraceRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = null)
        val resp = client.get("/v1/traces/resources") { withAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `traces list returns unauthorized when jwt lacks orgId`() = testApplication {
        installTraceRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = null)
        val resp = client.get("/v1/traces") { withAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `traces overview rejects invalid status`() = testApplication {
        installTraceRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/traces/overview?status=slow") { withAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `traces overview rejects invalid services`() = testApplication {
        installTraceRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val services = (1..51).joinToString(",") { "service-$it" }
        val resp = client.get("/v1/traces/overview?services=$services") { withAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `traces resources forwards server side filters`() = testApplication {
        installTraceRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get(
            "/v1/traces/resources?services=api,worker&env=prod&source=otlp&status=error" +
                "&operation=POST%20%2Fcheckout&search=checkout&limit=500&offset=-10"
        ) {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        coVerify {
            TraceIngestionService.listResourceStats(
                organizationId = 10,
                query = match {
                    it.services == listOf("api", "worker") &&
                        it.env == "prod" &&
                        it.source == "otlp" &&
                        it.status == "error" &&
                        it.operation == "POST /checkout" &&
                        it.search == "checkout" &&
                        it.limit == 200 &&
                        it.offset == 0
                },
            )
        }
    }

    @Test
    fun `traces overview rejects invalid time range`() = testApplication {
        installTraceRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/traces/overview?timeRange=2y") { withAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `traces overview forwards server side filters`() = testApplication {
        installTraceRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get(
            "/v1/traces/overview?services=api,worker&env=prod&source=otlp&status=ok" +
                "&operation=GET%20%2Forders&search=orders&timeRange=7d"
        ) {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        coVerify {
            TraceIngestionService.getApmOverview(
                organizationId = 10,
                query = match {
                    it.services == listOf("api", "worker") &&
                        it.env == "prod" &&
                        it.source == "otlp" &&
                        it.status == "ok" &&
                        it.operation == "GET /orders" &&
                        it.search == "orders"
                },
                parentSpan = any(),
            )
        }
    }

    @Test
    fun `traces list forwards server side paging search and filters`() = testApplication {
        installTraceRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get(
            "/v1/traces?services=api,worker&env=prod&source=otlp&status=error" +
                "&operation=POST%20%2Fcheckout&search=checkout&limit=500&offset=-10"
        ) {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        coVerify {
            TraceIngestionService.listTraces(
                organizationId = 10,
                query = match {
                    it.services == listOf("api", "worker") &&
                        it.env == "prod" &&
                        it.source == "otlp" &&
                        it.status == "error" &&
                        it.operation == "POST /checkout" &&
                        it.limit == 200 &&
                        it.offset == 0 &&
                        it.search == "checkout"
                },
                parentSpan = any(),
            )
        }
    }

    @Test
    fun `trace detail allows hex trace id`() = testApplication {
        installTraceRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/traces/4f8a2c9b8e7f4a1a8c1d2e3f4b5c6d7e") { withAuth(token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `trace detail returns bad request for invalid traceId`() = testApplication {
        installTraceRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/traces/not-a-number") { withAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `trace detail returns not found for unknown trace`() = testApplication {
        installTraceRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/traces/12345") { withAuth(token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    private fun emptyApmOverview(): DdApmOverviewResponse =
        DdApmOverviewResponse(
            stats = DdApmOverviewStats(
                totalTraces = 0,
                errorTraces = 0,
                errorRate = 0.0,
                serviceCount = 0,
                sourceCount = 0,
                p50DurationNs = 0,
                p95DurationNs = 0,
                p99DurationNs = 0,
                avgSpansPerTrace = 0.0,
                previous = DdApmOverviewPreviousStats(
                    totalTraces = 0,
                    errorRate = 0.0,
                    p50DurationNs = 0,
                    p95DurationNs = 0,
                    p99DurationNs = 0,
                    avgSpansPerTrace = 0.0,
                ),
            ),
            latencySeries = emptyList(),
            serviceHealth = emptyList(),
            resourceHotspots = emptyList(),
            errors = emptyList(),
            facets = DdApmOverviewFacets(
                services = emptyList(),
                sources = emptyList(),
                environments = emptyList(),
                operations = emptyList(),
            ),
        )

    // ──── Profile Dashboard Routes — 401 without JWT ────

    @Test
    fun `profiles list returns 401 without jwt`() = testApplication {
        installProfileRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/profiles").status)
    }

    @Test
    fun `profile download returns 401 without jwt`() = testApplication {
        installProfileRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/profiles/abc/download").status)
    }

    @Test
    fun `profile flamegraph returns 401 without jwt`() = testApplication {
        installProfileRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/profiles/abc/flamegraph").status)
    }

    // ──── Profile Dashboard Routes — Missing org context ────

    @Test
    fun `profiles list returns unauthorized when jwt lacks orgId`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = null)
        val resp = client.get("/v1/profiles") { withAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `profiles list returns ok with jwt and clamps limit`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get(
            "/v1/profiles?service=api&services=api,worker&type=cpu&source=datadog&env=prod&host=h1&version=v1" +
                "&from=100&to=200&limit=500&offset=3",
        ) {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        coVerify {
            ProfileIngestionService.listProfiles(
                10,
                match {
                    it.service == "api" &&
                        it.services == listOf("api", "worker") &&
                        it.profileType == "cpu" &&
                        it.source == "datadog" &&
                        it.env == "prod" &&
                        it.host == "h1" &&
                        it.version == "v1" &&
                        it.fromMs == 100L &&
                        it.toMs == 200L &&
                        it.limit == 200 &&
                        it.offset == 3
                },
            )
        }
    }

    @Test
    fun `profiles list normalizes invalid paging values`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/profiles?limit=0&offset=-10") { withAuth(token) }

        assertEquals(HttpStatusCode.OK, resp.status)
        coVerify {
            ProfileIngestionService.listProfiles(
                10,
                match {
                    it.limit == 1 && it.offset == 0
                },
            )
        }
    }

    @Test
    fun `profile download returns not found for unknown profile`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/profiles/unknown-id/download") { withAuth(token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `profile download returns raw profile bytes when found`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        coEvery {
            ProfileIngestionService.getProfileMeta(10, "profile-download")
        } returns ProfileIngestionService.ProfileMeta("profile-key", "cpu", "datadog")
        every { ProfileStorageService.read("profile-key") } returns "raw-profile".toByteArray()

        val resp = client.get("/v1/profiles/profile-download/download") { withAuth(token) }

        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("raw-profile", resp.bodyAsText())
    }

    @Test
    fun `profile download returns generic error when metadata lookup fails`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        coEvery {
            ProfileIngestionService.getProfileMeta(10, "profile-error")
        } throws IllegalStateException("metadata failed")

        val resp = client.get("/v1/profiles/profile-error/download") { withAuth(token) }

        assertEquals(HttpStatusCode.InternalServerError, resp.status)
    }

    @Test
    fun `profile flamegraph returns not found for unknown profile`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/profiles/unknown-id/flamegraph") { withAuth(token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `profile flamegraph forwards datadog pprof selectors`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val payload = "pprof-payload".toByteArray()
        coEvery {
            ProfileIngestionService.getProfileMeta(10, "profile-pprof")
        } returns ProfileIngestionService.ProfileMeta("profile-key", "cpu", "datadog")
        every { ProfileStorageService.read("profile-key") } returns payload

        mockkObject(DatadogPprofFlamegraphService)
        try {
            every { DatadogPprofFlamegraphService.isLikelyJfrPayload(payload) } returns false
            every {
                DatadogPprofFlamegraphService.parseToFrames(payload, "cpu", "worker-1")
            } returns selectorFlamegraph("cpu", "worker-1")

            val resp = client.get("/v1/profiles/profile-pprof/flamegraph?sampleType=cpu&thread=worker-1") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue(body.contains("\"selectedSampleType\":\"cpu\""))
            assertTrue(body.contains("\"selectedThread\":\"worker-1\""))
            verify(exactly = 1) {
                DatadogPprofFlamegraphService.parseToFrames(payload, "cpu", "worker-1")
            }
        } finally {
            unmockkObject(DatadogPprofFlamegraphService)
        }
    }

    @Test
    fun `profile flamegraph forwards datadog jfr selectors`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val payload = "jfr-payload".toByteArray()
        coEvery {
            ProfileIngestionService.getProfileMeta(10, "profile-jfr")
        } returns ProfileIngestionService.ProfileMeta("jfr-key", "jfr", "datadog")
        every { ProfileStorageService.read("jfr-key") } returns payload

        mockkObject(DatadogJfrFlamegraphService)
        try {
            every {
                DatadogJfrFlamegraphService.parseToFrames(payload, "cpu", "all")
            } returns selectorFlamegraph("cpu", "all")

            val resp = client.get("/v1/profiles/profile-jfr/flamegraph?sampleType=cpu&thread=all") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue(body.contains("\"selectedSampleType\":\"cpu\""))
            assertTrue(body.contains("\"selectedThread\":\"all\""))
            verify(exactly = 1) {
                DatadogJfrFlamegraphService.parseToFrames(payload, "cpu", "all")
            }
        } finally {
            unmockkObject(DatadogJfrFlamegraphService)
        }
    }

    private fun selectorFlamegraph(
        sampleType: String,
        thread: String,
    ) = buildJsonObject {
        put("frames", buildJsonArray {})
        put("selectedSampleType", sampleType)
        put("selectedThread", thread)
    }

    // ──── Profile aggregation routes ────

    @Test
    fun `profile services returns 401 without jwt`() = testApplication {
        installProfileRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/profiles/services").status)
    }

    @Test
    fun `profile services returns ok with jwt`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/profiles/services") { withAuth(token) }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `profile timeseries returns 401 without jwt`() = testApplication {
        installProfileRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/profiles/timeseries").status)
    }

    @Test
    fun `profile timeseries rejects inverted range`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/profiles/timeseries?from=200&to=100") { withAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `profile timeseries returns ok with jwt and forwards filters`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get(
            "/v1/profiles/timeseries?service=api&services=api,worker&type=cpu&env=prod" +
                "&host=h1&from=1000&to=61000&buckets=10",
        ) {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        coVerify {
            ProfileIngestionService.timeseries(
                match {
                    it.organizationId == 10 &&
                        it.filters.service == "api" &&
                        it.filters.services == listOf("api", "worker") &&
                        it.filters.profileType == "cpu" &&
                        it.filters.env == "prod" &&
                        it.filters.host == "h1" &&
                        it.window.fromMs == 1000L &&
                        it.window.toMs == 61000L &&
                        it.buckets == 10
                },
            )
        }
    }

    @Test
    fun `merged flamegraph returns 401 without jwt`() = testApplication {
        installProfileRoutes()()
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/profiles/merged-flamegraph").status,
        )
    }

    @Test
    fun `merged flamegraph returns ok with jwt`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/profiles/merged-flamegraph?service=api") { withAuth(token) }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `merged flamegraph rejects inverted range`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/profiles/merged-flamegraph?from=200&to=100") { withAuth(token) }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        coVerify(exactly = 0) {
            ProfileMergeService.mergeFlamegraph(any())
        }
    }

    @Test
    fun `profile metadata returns 401 without jwt`() = testApplication {
        installProfileRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/profiles/some-id").status)
    }

    @Test
    fun `profile metadata returns not found for unknown profile`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/profiles/unknown-id") { withAuth(token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `profile metadata returns profile when found`() = testApplication {
        coEvery { ProfileIngestionService.getProfile(any(), any()) } returns sampleProfile()
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/profiles/known-id") { withAuth(token) }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    private fun sampleHost(): DdHostInfo =
        DdHostInfo(
            id = TEST_HOST_RESOURCE_ID,
            internalId = 42,
            organizationId = 10,
            hostname = "prod-host-01",
            os = "linux",
            platform = "ubuntu",
            processor = "x86_64",
            cpuCores = 8,
            memoryTotalKb = 16_000_000L,
            agentVersion = "7.52.0",
            tags = mapOf("env" to "prod"),
            firstSeenAt = "2026-06-15T00:00:00Z",
            lastSeenAt = "2026-06-15T01:00:00Z",
            isOnline = true,
        )

    private fun normalizedHostMetricRows(): String =
        listOf(
            """{"ts":1700000000,"metric_name":"system.cpu.user","value":60.0}""",
            """{"ts":1700000000,"metric_name":"system.cpu.system","value":55.0}""",
            """{"ts":1700000000,"metric_name":"system.mem.available","value":200.0}""",
            """{"ts":1700000000,"metric_name":"system.mem.total","value":1000.0}""",
            """{"ts":1700000000,"metric_name":"system.disk.percent","value":71.5}""",
            """{"ts":1700000000,"metric_name":"system.net.recv_bytes","value":1024.0}""",
            """{"ts":1700000000,"metric_name":"system.net.sent_bytes","value":2048.0}""",
            """{"ts":1700000000,"metric_name":"system.load.1","value":1.25}""",
            """{"ts":1700000000,"metric_name":"system.load.5","value":1.5}""",
            """{"ts":1700000000,"metric_name":"system.load.15","value":1.75}""",
            """{"ts":1700000300,"metric_name":"system.mem.used","value":300.0}""",
            """{"ts":1700000300,"metric_name":"system.mem.total","value":1000.0}""",
        ).joinToString("\n")

    private fun normalizedContainerRows(): String =
        listOf(
            """
            {
              "host":"prod-host-01",
              "container_id":"container-a",
              "name":"api",
              "image":"ghcr.io/moneat/api:1",
              "state":"running",
              "cpu_percent":12.5,
              "mem_usage":256.0,
              "mem_limit":512.0,
              "net_rx_bytes":123.0,
              "net_tx_bytes":456.0,
              "tags":{"env":"prod"},
              "ts":"2026-06-15T00:00:00.000Z"
            }
            """.trimIndent().replace("\n", ""),
            """
            {
              "host":"prod-host-01",
              "container_id":"container-b",
              "name":"",
              "image":"",
              "state":"running",
              "tags":{
                "docker_image":"ghcr.io/moneat/worker:2",
                "docker_container_name":"worker"
              },
              "ts":"2026-06-15T00:01:00.000Z"
            }
            """.trimIndent().replace("\n", ""),
        ).joinToString("\n")

    private fun sampleProfile(): DdProfileResponse = DdProfileResponse(
        profileId = "known-id",
        host = "h1",
        service = "api",
        env = "prod",
        version = "1.0.0",
        runtime = "jvm",
        language = "jvm",
        profileType = "jfr",
        startTime = "2026-05-28 12:00:00.000",
        endTime = "2026-05-28 12:01:00.000",
        durationNs = 60_000_000_000L,
        sizeBytes = 2_000_000L,
        tags = emptyMap(),
        source = "datadog",
    )
}

private const val TEST_HOST_RESOURCE_ID = "11111111-1111-4111-8111-111111111111"
