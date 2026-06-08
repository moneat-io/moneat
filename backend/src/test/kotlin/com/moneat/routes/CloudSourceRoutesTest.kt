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

import com.moneat.monitor.models.CloudSourceCreateRequest
import com.moneat.monitor.models.CloudSourceProviderConfig
import com.moneat.monitor.models.CloudSourceResponse
import com.moneat.monitor.models.CloudSourceSetupPreview
import com.moneat.monitor.routes.cloudSourceRoutes
import com.moneat.monitor.services.CloudSourceService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudSourceRoutesTest {
    private val cloudSourceService = mockk<CloudSourceService>()

    @BeforeTest
    fun setup() {
        val db = Database.connect(
            url = "jdbc:h2:mem:moneat_cloud_source_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships)
    }

    @Test
    fun `cloud sources require JWT`() = testApplication {
        application {
            installJwtAuth()
            routing { cloudSourceRoutes(cloudSourceService) }
        }

        val response = client.get("/v1/cloud-sources")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `setup preview returns provider snippet for current organization`() = testApplication {
        val orgId = seedOrg()
        val userId = seedUser()
        seedMembership(userId, orgId)
        every { cloudSourceService.setupPreview(orgId, "aws") } returns CloudSourceSetupPreview(
            provider = "aws",
            externalId = "mnt-ext-test",
            principal = "arn:aws:iam::499432741914:root",
            snippetLabel = "Trust policy",
            snippetLanguage = "json",
            snippet = "{}"
        )

        application {
            installJwtAuth()
            routing { cloudSourceRoutes(cloudSourceService) }
        }

        val token = RouteTestSupport.createToken(userId = userId, orgId = orgId)
        val response = client.get("/v1/cloud-sources/setup-preview?provider=aws") { withAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("mnt-ext-test"))
    }

    @Test
    fun `create source returns saved source`() = testApplication {
        val orgId = seedOrg()
        val userId = seedUser()
        seedMembership(userId, orgId)
        val request = CloudSourceCreateRequest(
            provider = "aws",
            displayName = "Production AWS",
            config = CloudSourceProviderConfig(accountId = "123456789012", roleName = "MoneatIntegrationRole"),
            collectMetrics = true,
            collectInventory = true,
            collectCost = true,
            collectLogs = false
        )
        coEvery { cloudSourceService.createSource(orgId, userId, request) } returns CloudSourceResponse(
            id = 1,
            provider = "aws",
            displayName = "Production AWS",
            status = "healthy",
            config = request.config,
            collectMetrics = true,
            collectInventory = true,
            collectCost = true,
            collectLogs = false,
            externalId = "mnt-ext-test",
            lastSyncAt = "2026-06-07T12:00:00Z",
            lastError = null,
            createdAt = "2026-06-07T12:00:00Z",
            updatedAt = "2026-06-07T12:00:00Z"
        )

        application {
            installJwtAuth()
            routing { cloudSourceRoutes(cloudSourceService) }
        }

        val token = RouteTestSupport.createToken(userId = userId, orgId = orgId)
        val response = client.post("/v1/cloud-sources") {
            withAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "provider": "aws",
                  "displayName": "Production AWS",
                  "config": {"accountId": "123456789012", "roleName": "MoneatIntegrationRole"},
                  "collectMetrics": true,
                  "collectInventory": true,
                  "collectCost": true,
                  "collectLogs": false
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.bodyAsText().contains("Production AWS"))
    }

    private fun seedUser(): Int =
        transaction {
            Users.insert {
                it[email] = "cloud-route-${System.nanoTime()}@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            } get Users.id
        }

    private fun seedOrg(): Int =
        transaction {
            Organizations.insert {
                it[name] = "Cloud Source Route Org"
                it[slug] = "cloud-route-${System.nanoTime()}"
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
