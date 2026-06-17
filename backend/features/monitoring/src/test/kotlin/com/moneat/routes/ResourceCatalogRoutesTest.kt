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

import com.moneat.billing.services.EntitlementService
import com.moneat.monitor.models.CatalogOwner
import com.moneat.monitor.models.CatalogResource
import com.moneat.monitor.models.CatalogResourceTelemetry
import com.moneat.monitor.models.CatalogVulnerabilityCounts
import com.moneat.monitor.models.ResourceTelemetryResponse
import com.moneat.monitor.routes.resourceCatalogRoutes
import com.moneat.monitor.services.ResourceCatalogService
import com.moneat.monitor.services.ResourceTelemetryRequest
import com.moneat.org.services.OrgMembershipService
import com.moneat.org.services.OrgRole
import com.moneat.plugins.installErrorHandling
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceCatalogRoutesTest {
    private val catalogService = mockk<ResourceCatalogService>()
    private val entitlementService = mockk<EntitlementService>()
    private val membershipService = mockk<OrgMembershipService>(relaxed = true)

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

    private fun Route.installResourceCatalogRoutesForTest() {
        resourceCatalogRoutes(catalogService, entitlementService, membershipService)
    }

    // ──── Authentication ────

    @Test
    fun `resources endpoint requires JWT`() = testApplication {
        application {
            installJwtAuth()
            installErrorHandling()
            routing { installResourceCatalogRoutesForTest() }
        }

        val response = client.get("/v1/monitoring/resources")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `resources endpoint requires organization context`() = testApplication {
        application {
            installJwtAuth()
            installErrorHandling()
            routing { installResourceCatalogRoutesForTest() }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = null)
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
            routing { installResourceCatalogRoutesForTest() }
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
            routing { installResourceCatalogRoutesForTest() }
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
            routing { installResourceCatalogRoutesForTest() }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.get("/v1/monitoring/resources?limit=$LIMIT_ABOVE_MAX") { withAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText())
    }

    // ──── Telemetry ────

    @Test
    fun `telemetry endpoint requires a kind`() = testApplication {
        application {
            installJwtAuth()
            installErrorHandling()
            routing { installResourceCatalogRoutesForTest() }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.get("/v1/monitoring/resources/telemetry") { withAuth(token) }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("kind is required"))
    }

    @Test
    fun `telemetry endpoint rejects malformed range`() = testApplication {
        application {
            installJwtAuth()
            installErrorHandling()
            routing { installResourceCatalogRoutesForTest() }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.get("/v1/monitoring/resources/telemetry?kind=service&rangeSeconds=abc") {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("rangeSeconds must be an integer"))
    }

    @Test
    fun `telemetry endpoint rejects malformed host id`() = testApplication {
        application {
            installJwtAuth()
            installErrorHandling()
            routing { installResourceCatalogRoutesForTest() }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.get("/v1/monitoring/resources/telemetry?kind=host&hostId=abc") {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("hostId must be an integer"))
    }

    @Test
    fun `telemetry endpoint forwards resource selector and range`() = testApplication {
        val requestSlot = slot<ResourceTelemetryRequest>()
        coEvery { catalogService.getResourceTelemetry(capture(requestSlot)) } returns ResourceTelemetryResponse(
            kind = "container",
            rangeSeconds = 600,
            intervalSeconds = 60,
            metrics = emptyList(),
        )

        application {
            installJwtAuth()
            installErrorHandling()
            routing { installResourceCatalogRoutesForTest() }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.get(
            "/v1/monitoring/resources/telemetry" +
                "?kind=container&hostId=42&service=checkout-api&host=web-01&container=checkout&rangeSeconds=600"
        ) {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(""""kind":"container""""))
        val request = requestSlot.captured
        assertEquals(listOf(ORGANIZATION_ID), request.organizationIds)
        assertEquals("container", request.kind)
        assertEquals(600L, request.rangeSeconds)
        assertEquals(42, request.selector.hostId)
        assertEquals("checkout-api", request.selector.service)
        assertEquals("web-01", request.selector.containerHost)
        assertEquals("checkout", request.selector.containerName)
    }

    // ──── Ownership claims ────

    @Test
    fun `ownership endpoint rejects a claim on the free plan`() = testApplication {
        every { entitlementService.isTeamsEnabled(ORGANIZATION_ID) } returns false
        every { entitlementService.unavailableTeamsMessage(ORGANIZATION_ID) } returns
            "Teams is not available on your current plan"

        application {
            installJwtAuth()
            installErrorHandling()
            routing { installResourceCatalogRoutesForTest() }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.put("/v1/monitoring/resources/ownership") { withAuth(token) }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("Teams is not available"))
    }

    @Test
    fun `ownership endpoint persists a claim on a paid plan`() = testApplication {
        every { entitlementService.isTeamsEnabled(ORGANIZATION_ID) } returns true
        coEvery { catalogService.claimOwnership(eq(ORGANIZATION_ID), any(), any()) } returns
            CatalogOwner(
                teamId = "11111111-1111-1111-1111-111111111111",
                teamName = "Payments",
                slack = "#pay",
                repo = "moneat/pay",
            )

        application {
            installJwtAuth()
            installErrorHandling()
            routing { installResourceCatalogRoutesForTest() }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.put("/v1/monitoring/resources/ownership") {
            withAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "resourceId": "$RESOURCE_ID",
                  "teamId": "11111111-1111-1111-1111-111111111111"
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(""""teamName":"Payments""""))
    }

    @Test
    fun `ownership endpoint requires an organization admin`() = testApplication {
        every { entitlementService.isTeamsEnabled(ORGANIZATION_ID) } returns true
        every { membershipService.requireRole(ORGANIZATION_ID, USER_ID, OrgRole.ADMIN) } throws
            IllegalStateException("Insufficient permissions")

        application {
            installJwtAuth()
            installErrorHandling()
            routing { installResourceCatalogRoutesForTest() }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.put("/v1/monitoring/resources/ownership") {
            withAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"resourceId":"$RESOURCE_ID","teamId":"11111111-1111-1111-1111-111111111111"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("Insufficient permissions"))
    }

    @Test
    fun `ownership endpoint rejects blank resource and team`() = testApplication {
        every { entitlementService.isTeamsEnabled(ORGANIZATION_ID) } returns true

        application {
            installJwtAuth()
            installErrorHandling()
            routing { installResourceCatalogRoutesForTest() }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.put("/v1/monitoring/resources/ownership") {
            withAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"resourceId":"","teamId":""}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("resourceId and teamId are required"))
    }

    @Test
    fun `ownership endpoint records unknown actor when the JWT email is blank`() = testApplication {
        val actorSlot = slot<String>()
        every { entitlementService.isTeamsEnabled(ORGANIZATION_ID) } returns true
        coEvery { catalogService.claimOwnership(eq(ORGANIZATION_ID), any(), capture(actorSlot)) } returns
            CatalogOwner(teamId = "11111111-1111-1111-1111-111111111111", teamName = "Payments")

        application {
            installJwtAuth()
            installErrorHandling()
            routing { installResourceCatalogRoutesForTest() }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID, email = "")
        val response = client.put("/v1/monitoring/resources/ownership") {
            withAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"resourceId":"$RESOURCE_ID","teamId":"11111111-1111-1111-1111-111111111111"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("unknown", actorSlot.captured)
    }

    @Test
    fun `ownership endpoint returns not found for resources outside the scoped catalog`() = testApplication {
        every { entitlementService.isTeamsEnabled(ORGANIZATION_ID) } returns true
        coEvery { catalogService.claimOwnership(eq(ORGANIZATION_ID), any(), any()) } returns null

        application {
            installJwtAuth()
            installErrorHandling()
            routing { installResourceCatalogRoutesForTest() }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.put("/v1/monitoring/resources/ownership") {
            withAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"resourceId":"service:8:checkout-api","teamId":"11111111-1111-1111-1111-111111111111"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("Resource not found"))
    }

    @Test
    fun `ownership endpoint returns not found for an unknown team`() = testApplication {
        every { entitlementService.isTeamsEnabled(ORGANIZATION_ID) } returns true
        coEvery { catalogService.claimOwnership(eq(ORGANIZATION_ID), any(), any()) } throws
            NotFoundException("Team not found")

        application {
            installJwtAuth()
            installErrorHandling()
            routing { installResourceCatalogRoutesForTest() }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.put("/v1/monitoring/resources/ownership") {
            withAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"resourceId":"$RESOURCE_ID","teamId":"11111111-1111-1111-1111-111111111111"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("Team not found"))
    }

    @Test
    fun `delete ownership endpoint removes a claim on a paid plan`() = testApplication {
        every { entitlementService.isTeamsEnabled(ORGANIZATION_ID) } returns true
        coEvery { catalogService.deleteOwnership(ORGANIZATION_ID, RESOURCE_ID) } returns true

        application {
            installJwtAuth()
            installErrorHandling()
            routing { installResourceCatalogRoutesForTest() }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.delete("/v1/monitoring/resources/ownership/$RESOURCE_ID") {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `delete ownership endpoint returns not found for missing claim`() = testApplication {
        every { entitlementService.isTeamsEnabled(ORGANIZATION_ID) } returns true
        coEvery { catalogService.deleteOwnership(ORGANIZATION_ID, RESOURCE_ID) } returns false

        application {
            installJwtAuth()
            installErrorHandling()
            routing { installResourceCatalogRoutesForTest() }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.delete("/v1/monitoring/resources/ownership/$RESOURCE_ID") {
            withAuth(token)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("Resource ownership not found"))
    }
}
