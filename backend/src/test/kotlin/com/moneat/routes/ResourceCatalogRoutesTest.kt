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
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceCatalogRoutesTest {
    private val catalogService = mockk<ResourceCatalogService>()

    @BeforeTest
    fun setup() {
        val db = Database.connect(
            url = "jdbc:h2:mem:moneat_resource_catalog_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships)
    }

    @Test
    fun `resources endpoint requires JWT`() = testApplication {
        application {
            installJwtAuth()
            routing { resourceCatalogRoutes(catalogService) }
        }

        val response = client.get("/v1/monitoring/resources")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `resources endpoint returns catalog resources for user organizations`() = testApplication {
        val orgId = seedOrg()
        val userId = seedUser()
        seedMembership(userId, orgId)
        coEvery { catalogService.listResources(listOf(orgId)) } returns listOf(
            CatalogResource(
                id = "host:1",
                name = "web-01",
                kind = "host",
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
                firstSeen = "2026-06-01T00:00:00.000Z",
                lastChange = "2026-06-07T12:00:00.000Z"
            )
        )

        application {
            installJwtAuth()
            routing { resourceCatalogRoutes(catalogService) }
        }

        val token = RouteTestSupport.createToken(userId = userId, orgId = orgId)
        val response = client.get("/v1/monitoring/resources") { withAuth(token) }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains(""""id":"host:1""""))
        assertTrue(body.contains(""""kind":"host""""))
        assertTrue(body.contains(""""telemetry":{"cpuPct":10,"memPct":20"""))
    }

    @Test
    fun `resources endpoint returns empty list when user has no memberships`() = testApplication {
        val userId = seedUser()

        application {
            installJwtAuth()
            routing { resourceCatalogRoutes(catalogService) }
        }

        val token = RouteTestSupport.createToken(userId = userId)
        val response = client.get("/v1/monitoring/resources") { withAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText())
    }

    private fun seedUser(): Int =
        transaction {
            Users.insert {
                it[email] = "resource-catalog-${System.nanoTime()}@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            } get Users.id
        }

    private fun seedOrg(): Int =
        transaction {
            Organizations.insert {
                it[name] = "Resource Catalog Org"
                it[slug] = "resource-catalog-${System.nanoTime()}"
            } get Organizations.id
        }

    private fun seedMembership(userId: Int, orgId: Int) {
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
        }
    }
}
