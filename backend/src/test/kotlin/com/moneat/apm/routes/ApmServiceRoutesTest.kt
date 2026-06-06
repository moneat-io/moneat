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
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ApmServiceCatalogService)
    }

    @Test
    fun `GET v1 services returns catalog response`() = testApplication {
        coEvery {
            ApmServiceCatalogService.listServices(any(), any(), any())
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
        val response = client.get("/v1/services?timeRange=6h&env=production") {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"checkout-api\""))
        coVerify(exactly = 1) {
            ApmServiceCatalogService.listServices(42, any(), any())
        }
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
}
