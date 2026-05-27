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

import com.moneat.datadog.models.DdApmErrorsResponse
import com.moneat.datadog.models.DdApmOverviewFacets
import com.moneat.datadog.models.DdApmOverviewPreviousStats
import com.moneat.datadog.models.DdApmOverviewResponse
import com.moneat.datadog.models.DdApmOverviewStats
import com.moneat.datadog.models.DdProfileListResponse
import com.moneat.datadog.models.DdResourceStatsResponse
import com.moneat.datadog.models.DdServiceMapResponse
import com.moneat.datadog.models.DdTraceListResponse
import com.moneat.datadog.services.DatadogHostService
import com.moneat.datadog.services.DdResourceStatsQuery
import com.moneat.datadog.services.DdTraceListQuery
import com.moneat.datadog.services.ProfileIngestionService
import com.moneat.datadog.services.TraceIngestionService
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DatadogQueryRoutesTest {

    @BeforeTest
    fun setup() {
        startTestKoin()
        mockkObject(DatadogHostService)
        mockkObject(TraceIngestionService)
        mockkObject(ProfileIngestionService)

        every { DatadogHostService.listHosts(any<Int>()) } returns emptyList()
        every { DatadogHostService.getHost(any<Int>(), any<Int>()) } returns null
        every { DatadogHostService.deleteHost(any<Int>(), any<Int>()) } returns false
        coEvery { TraceIngestionService.listResourceStats(any(), any<DdResourceStatsQuery>(), any()) } returns
            DdResourceStatsResponse(emptyList(), 0L)
        coEvery {
            TraceIngestionService.listTraces(any(), any<DdTraceListQuery>(), any())
        } returns
            DdTraceListResponse(emptyList(), 0L)
        coEvery { TraceIngestionService.getApmOverview(any(), any<DdTraceListQuery>(), any()) } returns
            emptyApmOverview()
        coEvery { TraceIngestionService.getTraceDetail(any(), any(), any()) } returns null
        coEvery { TraceIngestionService.getServiceMap(any(), any()) } returns
            DdServiceMapResponse(emptyList())
        coEvery { TraceIngestionService.getApmErrors(any(), any(), any(), any(), any(), any()) } returns
            DdApmErrorsResponse(emptyList(), 0L)
        coEvery { ProfileIngestionService.listProfiles(any(), any(), any(), any(), any(), any()) } returns
            DdProfileListResponse(emptyList(), 0L)
        coEvery { ProfileIngestionService.getProfileMeta(any(), any()) } returns null
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ProfileIngestionService)
        unmockkObject(TraceIngestionService)
        unmockkObject(DatadogHostService)
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
        val resp = client.get("/v1/hosts/999") { withAuth(token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `hosts delete returns not found for unknown host`() = testApplication {
        installHostRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.delete("/v1/hosts/999") { withAuth(token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
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
    fun `traces resources forwards server side filters`() = testApplication {
        installTraceRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get(
            "/v1/traces/resources?service=api&env=prod&source=otlp&status=error&search=checkout&limit=500&offset=-10"
        ) {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        coVerify {
            TraceIngestionService.listResourceStats(
                organizationId = 10,
                query = match {
                    it.service == "api" &&
                        it.env == "prod" &&
                        it.source == "otlp" &&
                        it.status == "error" &&
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
    fun `traces list forwards server side paging search and filters`() = testApplication {
        installTraceRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get(
            "/v1/traces?service=api&env=prod&source=otlp&status=error&search=checkout&limit=500&offset=-10"
        ) {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        coVerify {
            TraceIngestionService.listTraces(
                organizationId = 10,
                query = match {
                    it.service == "api" &&
                        it.env == "prod" &&
                        it.source == "otlp" &&
                        it.status == "error" &&
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
    fun `profile download returns not found for unknown profile`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/profiles/unknown-id/download") { withAuth(token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `profile flamegraph returns not found for unknown profile`() = testApplication {
        installProfileRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/profiles/unknown-id/flamegraph") { withAuth(token) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
}
