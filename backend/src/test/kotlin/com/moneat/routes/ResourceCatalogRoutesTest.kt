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

package com.moneat.routes

import com.moneat.monitor.models.CatalogResource
import com.moneat.monitor.models.CatalogResourceTelemetry
import com.moneat.monitor.models.CatalogVulnerabilityCounts
import com.moneat.monitor.routes.resourceCatalogRoutes
import com.moneat.monitor.services.ResourceCatalogService
import com.moneat.plugins.installErrorHandling
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceCatalogRoutesTest {
    private val catalogService = mockk<ResourceCatalogService>()

    private companion object {
        const val USER_ID = 41
        const val ORGANIZATION_ID = 73
        const val RESOURCE_ID = "host:73:1"
        const val RESOURCE_NAME = "web-01"
        const val RESOURCE_KIND = "host"
        const val FIRST_SEEN = "2026-06-01T00:00:00.000Z"
        const val LAST_CHANGE = "2026-06-07T12:00:00.000Z"
        const val REQUESTED_LIMIT = 500
        const val LIMIT_ABOVE_MAX = 9999
    }

    // ──── Authentication ────

    @Test
    fun `resources endpoint requires JWT`() = testApplication {
        application {
            installJwtAuth()
            installErrorHandling()
            routing { resourceCatalogRoutes(catalogService) }
        }

        val response = client.get("/v1/monitoring/resources")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `resources endpoint requires organization context`() = testApplication {
        application {
            installJwtAuth()
            installErrorHandling()
            routing { resourceCatalogRoutes(catalogService) }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID)
        val response = client.get("/v1/monitoring/resources") { withAuth(token) }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Organization context required"))
    }

    // ──── Catalog resources ────

    @Test
    fun `resources endpoint returns catalog resources for current organization`() = testApplication {
        coEvery { catalogService.listResources(listOf(ORGANIZATION_ID)) } returns listOf(
            CatalogResource(
                id = RESOURCE_ID,
                name = RESOURCE_NAME,
                kind = RESOURCE_KIND,
                health = "healthy",
                environment = "prod",
                region = "unknown",
                cloud = "on-prem",
                owner = null,
                tags = listOf("source:host-agent"),
                telemetry = CatalogResourceTelemetry(cpuPct = 10, memPct = 20),
                vulns = CatalogVulnerabilityCounts(),
                sbomComponents = 0,
                posture = emptyList(),
                monthlyUsd = 0.0,
                costTrendPct = 0.0,
                costBreakdown = emptyList(),
                relationships = emptyList(),
                changes = emptyList(),
                metadata = emptyList(),
                firstSeen = FIRST_SEEN,
                lastChange = LAST_CHANGE
            )
        )

        application {
            installJwtAuth()
            installErrorHandling()
            routing { resourceCatalogRoutes(catalogService) }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.get("/v1/monitoring/resources") { withAuth(token) }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains(""""id":"$RESOURCE_ID""""))
        assertTrue(body.contains(""""kind":"$RESOURCE_KIND""""))
        assertTrue(body.contains(""""telemetry":{"cpuPct":10,"memPct":20"""))
    }

    // ──── Limit parsing ────

    @Test
    fun `resources endpoint rejects malformed limit`() = testApplication {
        application {
            installJwtAuth()
            installErrorHandling()
            routing { resourceCatalogRoutes(catalogService) }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.get("/v1/monitoring/resources?limit=abc") { withAuth(token) }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("limit must be an integer"))
    }

    @Test
    fun `resources endpoint clamps excessive limit`() = testApplication {
        coEvery { catalogService.listResources(listOf(ORGANIZATION_ID), REQUESTED_LIMIT) } returns emptyList()

        application {
            installJwtAuth()
            installErrorHandling()
            routing { resourceCatalogRoutes(catalogService) }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.get("/v1/monitoring/resources?limit=$LIMIT_ABOVE_MAX") { withAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText())
    }
}
