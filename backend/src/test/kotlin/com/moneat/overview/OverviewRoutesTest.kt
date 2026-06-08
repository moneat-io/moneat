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

package com.moneat.overview

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.overview.models.OverviewActivityItem
import com.moneat.overview.models.OverviewCounts
import com.moneat.overview.models.OverviewDeployRow
import com.moneat.overview.models.OverviewInfraData
import com.moneat.overview.models.OverviewKpi
import com.moneat.overview.models.OverviewKpiDelta
import com.moneat.overview.models.OverviewResponse
import com.moneat.overview.models.OverviewStatusAi
import com.moneat.overview.models.OverviewSystemStatus
import com.moneat.overview.models.OverviewTelemetryData
import com.moneat.overview.models.OverviewTriageData
import com.moneat.overview.models.OverviewUptimeData
import com.moneat.overview.routes.overviewRoutes
import com.moneat.overview.services.OverviewService
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.TEST_JWT_SECRET
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverviewRoutesTest {

    private val service = mockk<OverviewService>()

    @Test
    fun `overview returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { overviewRoutes(service) }
            }

            val response = client.get("/v1/overview")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `overview uses JWT organization context`() =
        testApplication {
            coEvery { service.getOverview(42, null) } returns overviewResponse()

            application {
                installJwtAuth()
                routing { overviewRoutes(service) }
            }

            val token = RouteTestSupport.createToken(userId = 7, orgId = 42)
            val response = client.get("/v1/overview") { withAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Action needed"))
            coVerify(exactly = 1) { service.getOverview(42, null) }
        }

    @Test
    fun `overview passes demo epoch from demo token`() =
        testApplication {
            val demoEpochMs = 1_709_312_400_000L
            coEvery { service.getOverview(-1, demoEpochMs) } returns overviewResponse()

            application {
                installJwtAuth()
                routing { overviewRoutes(service) }
            }

            val response = client.get("/v1/overview") { withAuth(demoToken(demoEpochMs)) }

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify(exactly = 1) { service.getOverview(-1, demoEpochMs) }
        }

    @Test
    fun `overview returns 401 for authenticated requests without org context`() =
        testApplication {
            application {
                installJwtAuth()
                routing { overviewRoutes(service) }
            }

            val token = RouteTestSupport.createToken(userId = 7, orgId = null)
            val response = client.get("/v1/overview") { withAuth(token) }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    private fun overviewResponse() =
        OverviewResponse(
            systemStatus = OverviewSystemStatus(
                state = "Action needed",
                severity = "bad",
                counts = OverviewCounts(incidents = 1, alerts = 2, degraded = 3, hostsOffline = 4),
                ai = OverviewStatusAi(summary = "checkout-api is degraded", incidentId = "INC-204"),
            ),
            kpis = listOf(
                OverviewKpi(
                    id = "errors",
                    label = "Errors",
                    value = "12",
                    unit = null,
                    delta = OverviewKpiDelta(value = "4%", direction = "up", tone = "bad"),
                    status = "bad",
                    spark = listOf(1, 2, 3),
                )
            ),
            serviceHealth = emptyList(),
            telemetry = OverviewTelemetryData(
                errors = listOf(1),
                latency = listOf(2),
                throughput = listOf(3),
                logs = listOf(4),
                deployAtPct = 0,
                deployLabel = "latest deploy",
            ),
            triage = OverviewTriageData(
                incidents = emptyList(),
                alerts = emptyList(),
                issues = emptyList(),
                security = emptyList(),
            ),
            infra = OverviewInfraData(
                gauges = emptyList(),
                containers = 0,
                pods = 0,
                upLabel = "0/0 up",
                offlineNode = null,
            ),
            uptime = OverviewUptimeData(
                monitors = emptyList(),
                upLabel = "0/0 up",
                syntheticFailing = null,
                statusPages = "0 status pages",
            ),
            deploys = listOf(OverviewDeployRow("v1.0.0", "api", "neutral", "released", "now")),
            activity = listOf(OverviewActivityItem("deploy", "v1.0.0 released", "now")),
        )

    private fun demoToken(demoEpochMs: Long): String =
        JWT.create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", -1)
            .withClaim("orgId", -1)
            .withClaim("email", "demo@moneat.dev")
            .withClaim("isDemo", true)
            .withClaim("demoEpochMs", demoEpochMs)
            .sign(Algorithm.HMAC256(TEST_JWT_SECRET))
}
