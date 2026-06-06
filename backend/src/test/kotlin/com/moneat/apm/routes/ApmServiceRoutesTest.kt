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

package com.moneat.apm.routes

import com.moneat.apm.models.ApmCatalogSummary
import com.moneat.apm.models.ApmEnvPill
import com.moneat.apm.models.ApmServiceCatalogResponse
import com.moneat.apm.models.ApmServiceCatalogRow
import com.moneat.apm.services.ApmServiceCatalogService
import com.moneat.apm.services.ApmServiceQuery
import com.moneat.datadog.models.DdServiceLatencyResponse
import com.moneat.datadog.models.DdServiceMapResponse
import com.moneat.datadog.services.TraceIngestionService
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.withAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApmServiceRoutesTest {

    @BeforeTest
    fun setup() {
        mockkObject(ApmServiceCatalogService)
        mockkObject(TraceIngestionService)
    }

    @AfterTest
    fun teardown() {
        unmockkObject(TraceIngestionService)
        unmockkObject(ApmServiceCatalogService)
    }

    @Test
    fun `GET v1 services returns catalog response`() = testApplication {
        val querySlot = slot<ApmServiceQuery>()
        coEvery {
            ApmServiceCatalogService.listServices(any(), capture(querySlot), any())
        } returns ApmServiceCatalogResponse(
            services = listOf(
                ApmServiceCatalogRow(
                    name = "checkout-api",
                    type = "web",
                    status = "healthy",
                    env = listOf(ApmEnvPill(label = "production", chart = 1)),
                    rps = 12.5,
                    spark = listOf(14, 12, 11),
                    p95Ms = 248,
                    p99Ms = 612,
                    errorRateLabel = "0.2%",
                    errorBarPct = 8,
                    errorLevel = "good",
                    apdex = "0.98",
                    apdexTone = "success",
                    lastDeploy = "v3.1.2",
                    team = "unassigned",
                    language = "web",
                    sources = listOf("OTLP"),
                )
            ),
            summary = ApmCatalogSummary(total = 1, alerting = 0, degraded = 0),
        )
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { apmServiceDashboardRoutes() }
        }

        val token = RouteTestSupport.createToken(userId = 1, orgId = 42)
        val response = client.get(
            "/v1/services?timeRange=6h&env=%20production%20&source=otel&search=%20checkout%20" +
                "&limit=999&offset=-3"
        ) {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"checkout-api\""))
        assertEquals("production", querySlot.captured.env)
        assertEquals("otel", querySlot.captured.source)
        assertEquals("checkout", querySlot.captured.search)
        assertEquals(200, querySlot.captured.limit)
        assertEquals(0, querySlot.captured.offset)
        coVerify(exactly = 1) {
            ApmServiceCatalogService.listServices(42, any(), any())
        }
    }

    @Test
    fun `GET v1 services rejects token without organization`() = testApplication {
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { apmServiceDashboardRoutes() }
        }

        val token = RouteTestSupport.createToken(userId = 1)
        val response = client.get("/v1/services") {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("Unauthorized"))
    }

    @Test
    fun `GET v1 services rejects invalid query parameters`() = testApplication {
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { apmServiceDashboardRoutes() }
        }

        val token = RouteTestSupport.createToken(userId = 1, orgId = 42)
        val badRange = client.get("/v1/services?timeRange=bad") {
            withAuth(token)
        }
        val badFilter = client.get("/v1/services?env=${"e".repeat(201)}") {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.BadRequest, badRange.status)
        assertTrue(badRange.bodyAsText().contains("Invalid timeRange"))
        assertEquals(HttpStatusCode.BadRequest, badFilter.status)
        assertTrue(badFilter.bodyAsText().contains("Invalid service map filter"))
    }

    @Test
    fun `GET v1 services rejects oversized service parameter`() = testApplication {
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { apmServiceDashboardRoutes() }
        }

        val token = RouteTestSupport.createToken(userId = 1, orgId = 42)
        val response = client.get("/v1/services/${"s".repeat(201)}") {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET v1 service detail returns not found when service is missing`() = testApplication {
        coEvery {
            ApmServiceCatalogService.getServiceDetail(any(), any(), any(), any())
        } returns null
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { apmServiceDashboardRoutes() }
        }

        val token = RouteTestSupport.createToken(userId = 1, orgId = 42)
        val response = client.get("/v1/services/checkout-api?range=24h") {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("Service not found"))
        coVerify(exactly = 1) {
            ApmServiceCatalogService.getServiceDetail(42, "checkout-api", any(), any())
        }
    }

    @Test
    fun `GET v1 resource detail validates resource and returns not found`() = testApplication {
        coEvery {
            ApmServiceCatalogService.getResourceDetail(any(), any(), any(), any(), any())
        } returns null
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { apmServiceDashboardRoutes() }
        }

        val token = RouteTestSupport.createToken(userId = 1, orgId = 42)
        val blankResource = client.get("/v1/services/checkout-api/resources/%20") {
            withAuth(token)
        }
        val missingResource = client.get("/v1/services/checkout-api/resources/GET%20%2Fcheckout") {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.BadRequest, blankResource.status)
        assertTrue(blankResource.bodyAsText().contains("Invalid resource"))
        assertEquals(HttpStatusCode.NotFound, missingResource.status)
        assertTrue(missingResource.bodyAsText().contains("Resource not found"))
        coVerify(exactly = 1) {
            ApmServiceCatalogService.getResourceDetail(42, "checkout-api", "GET /checkout", any(), any())
        }
    }

    @Test
    fun `GET v1 services map validates filters and forwards source-neutral scope`() = testApplication {
        coEvery {
            TraceIngestionService.getServiceMap(any(), any(), any(), any(), any())
        } returns DdServiceMapResponse(emptyList())
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { apmServiceDashboardRoutes() }
        }

        val token = RouteTestSupport.createToken(userId = 1, orgId = 42)
        val ok = client.get("/v1/services/map?range=1h&env=prod&source=otel") {
            withAuth(token)
        }
        val badRange = client.get("/v1/services/map?range=bad") {
            withAuth(token)
        }
        val badFilter = client.get("/v1/services/map?source=${"s".repeat(201)}") {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(HttpStatusCode.BadRequest, badRange.status)
        assertTrue(badRange.bodyAsText().contains("Invalid timeRange"))
        assertEquals(HttpStatusCode.BadRequest, badFilter.status)
        assertTrue(badFilter.bodyAsText().contains("Invalid service map filter"))
        coVerify(exactly = 1) {
            TraceIngestionService.getServiceMap(42, any(), "prod", "otel", any())
        }
    }

    @Test
    fun `GET v1 service latency validates service and forwards filters`() = testApplication {
        coEvery {
            TraceIngestionService.getServiceLatencyPercentiles(any(), any(), any(), any(), any(), any())
        } returns DdServiceLatencyResponse(
            service = "checkout-api",
            p50DurationNs = 100,
            p90DurationNs = 200,
            p99DurationNs = 300,
            sampleCount = 4,
        )
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { apmServiceDashboardRoutes() }
        }

        val token = RouteTestSupport.createToken(userId = 1, orgId = 42)
        val ok = client.get("/v1/services/checkout-api/latency?timeRange=90d&env=prod&source=otel") {
            withAuth(token)
        }
        val badService = client.get("/v1/services/%20/latency") {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("checkout-api"))
        assertEquals(HttpStatusCode.BadRequest, badService.status)
        assertTrue(badService.bodyAsText().contains("Invalid service"))
        coVerify(exactly = 1) {
            TraceIngestionService.getServiceLatencyPercentiles(42, "checkout-api", any(), "prod", "otel", any())
        }
    }
}
