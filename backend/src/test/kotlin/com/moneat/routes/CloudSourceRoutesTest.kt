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
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudSourceRoutesTest {
    private val cloudSourceService = mockk<CloudSourceService>()

    private companion object {
        const val USER_ID = 41
        const val ORGANIZATION_ID = 73
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
    fun `cloud sources require organization context`() = testApplication {
        application {
            installJwtAuth()
            routing { cloudSourceRoutes(cloudSourceService) }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID)
        val response = client.get("/v1/cloud-sources") { withAuth(token) }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Organization context required"))
    }

    @Test
    fun `setup preview returns provider snippet for current organization`() = testApplication {
        every { cloudSourceService.setupPreview(ORGANIZATION_ID, "aws") } returns CloudSourceSetupPreview(
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

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.get("/v1/cloud-sources/setup-preview?provider=aws") { withAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("mnt-ext-test"))
    }

    @Test
    fun `create source returns saved source`() = testApplication {
        val request = CloudSourceCreateRequest(
            provider = "aws",
            displayName = "Production AWS",
            config = CloudSourceProviderConfig(accountId = "123456789012", roleName = "MoneatIntegrationRole"),
            collectMetrics = true,
            collectInventory = true,
            collectCost = true,
            collectLogs = false
        )
        coEvery { cloudSourceService.createSource(ORGANIZATION_ID, USER_ID, request) } returns CloudSourceResponse(
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

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
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
}
