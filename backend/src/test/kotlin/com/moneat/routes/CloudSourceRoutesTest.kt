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
import com.moneat.monitor.services.CloudSourceConnectorUnavailableException
import com.moneat.monitor.services.CloudSourceService
import com.moneat.plugins.installErrorHandling
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import io.ktor.client.request.delete
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
        const val SOURCE_ID = 1
        const val PROVIDER_AWS = "aws"
        const val DISPLAY_NAME = "Production AWS"
        const val AWS_ACCOUNT_ID = "123456789012"
        const val AWS_ROLE_NAME = "MoneatIntegrationRole"
        const val EXTERNAL_ID = "mnt-ext-test"
        const val AWS_PRINCIPAL_ARN = "arn:aws:iam::499432741914:role/MoneatCloudSource"
        const val TRUST_POLICY_LABEL = "Trust policy"
        const val SNIPPET_LANGUAGE_JSON = "json"
        const val STATUS_HEALTHY = "healthy"
        const val TIMESTAMP = "2026-06-07T12:00:00Z"
    }

    // ──── Authentication ────

    @Test
    fun `cloud sources require JWT`() = testApplication {
        application {
            installJwtAuth()
            installErrorHandling()
            routing { cloudSourceRoutes(cloudSourceService) }
        }

        val response = client.get("/v1/cloud-sources")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `cloud sources require organization context`() = testApplication {
        application {
            installJwtAuth()
            installErrorHandling()
            routing { cloudSourceRoutes(cloudSourceService) }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = null)
        val response = client.get("/v1/cloud-sources") { withAuth(token) }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Organization context required"))
    }

    // ──── List sources ────

    @Test
    fun `list sources uses current organization context`() = testApplication {
        every { cloudSourceService.listSources(ORGANIZATION_ID) } returns listOf(sourceResponse())

        application {
            installJwtAuth()
            installErrorHandling()
            routing { cloudSourceRoutes(cloudSourceService) }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.get("/v1/cloud-sources") { withAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(DISPLAY_NAME))
    }

    // ──── Setup preview ────

    @Test
    fun `setup preview returns provider snippet for current organization`() = testApplication {
        every { cloudSourceService.setupPreview(ORGANIZATION_ID, PROVIDER_AWS) } returns CloudSourceSetupPreview(
            provider = PROVIDER_AWS,
            externalId = EXTERNAL_ID,
            principal = AWS_PRINCIPAL_ARN,
            snippetLabel = TRUST_POLICY_LABEL,
            snippetLanguage = SNIPPET_LANGUAGE_JSON,
            snippet = "{}"
        )

        application {
            installJwtAuth()
            installErrorHandling()
            routing { cloudSourceRoutes(cloudSourceService) }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.get("/v1/cloud-sources/setup-preview?provider=$PROVIDER_AWS") { withAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(EXTERNAL_ID))
    }

    @Test
    fun `setup preview requires provider`() = testApplication {
        application {
            installJwtAuth()
            installErrorHandling()
            routing { cloudSourceRoutes(cloudSourceService) }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.get("/v1/cloud-sources/setup-preview") { withAuth(token) }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("provider is required"))
    }

    // ──── Create source ────

    @Test
    fun `create source returns saved source`() = testApplication {
        val request = CloudSourceCreateRequest(
            provider = PROVIDER_AWS,
            displayName = DISPLAY_NAME,
            config = CloudSourceProviderConfig(accountId = AWS_ACCOUNT_ID, roleName = AWS_ROLE_NAME),
            collectMetrics = true,
            collectInventory = true,
            collectCost = true,
            collectLogs = false
        )
        coEvery {
            cloudSourceService.createSource(ORGANIZATION_ID, USER_ID, request)
        } returns sourceResponse(request.config)

        application {
            installJwtAuth()
            installErrorHandling()
            routing { cloudSourceRoutes(cloudSourceService) }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.post("/v1/cloud-sources") {
            withAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "provider": "$PROVIDER_AWS",
                  "displayName": "$DISPLAY_NAME",
                  "config": {"accountId": "$AWS_ACCOUNT_ID", "roleName": "$AWS_ROLE_NAME"},
                  "collectMetrics": true,
                  "collectInventory": true,
                  "collectCost": true,
                  "collectLogs": false
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.bodyAsText().contains(DISPLAY_NAME))
    }

    @Test
    fun `create source maps connector errors to service unavailable`() = testApplication {
        coEvery {
            cloudSourceService.createSource(any(), any(), any())
        } throws CloudSourceConnectorUnavailableException("Cloud connector is missing CLOUD_AWS_PRINCIPAL_ARN")

        application {
            installJwtAuth()
            installErrorHandling()
            routing { cloudSourceRoutes(cloudSourceService) }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.post("/v1/cloud-sources") {
            withAuth(token)
            contentType(ContentType.Application.Json)
            setBody(sourceBody())
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("CLOUD_AWS_PRINCIPAL_ARN"))
    }

    // ──── Sync and delete ────

    @Test
    fun `sync source validates id and returns refreshed source`() = testApplication {
        coEvery { cloudSourceService.syncSource(ORGANIZATION_ID, SOURCE_ID) } returns sourceResponse()

        application {
            installJwtAuth()
            installErrorHandling()
            routing { cloudSourceRoutes(cloudSourceService) }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val response = client.post("/v1/cloud-sources/$SOURCE_ID/sync") { withAuth(token) }
        val invalid = client.post("/v1/cloud-sources/not-a-number/sync") { withAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(EXTERNAL_ID))
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
    }

    @Test
    fun `delete source returns no content or not found`() = testApplication {
        coEvery { cloudSourceService.deleteSource(ORGANIZATION_ID, SOURCE_ID) } returns true
        coEvery { cloudSourceService.deleteSource(ORGANIZATION_ID, SOURCE_ID + 1) } returns false

        application {
            installJwtAuth()
            installErrorHandling()
            routing { cloudSourceRoutes(cloudSourceService) }
        }

        val token = RouteTestSupport.createToken(userId = USER_ID, orgId = ORGANIZATION_ID)
        val deleted = client.delete("/v1/cloud-sources/$SOURCE_ID") { withAuth(token) }
        val missing = client.delete("/v1/cloud-sources/${SOURCE_ID + 1}") { withAuth(token) }

        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertTrue(missing.bodyAsText().contains("Cloud source not found"))
    }

    private fun sourceResponse(
        config: CloudSourceProviderConfig = CloudSourceProviderConfig(
            accountId = AWS_ACCOUNT_ID,
            roleName = AWS_ROLE_NAME,
        ),
    ): CloudSourceResponse =
        CloudSourceResponse(
            id = SOURCE_ID,
            provider = PROVIDER_AWS,
            displayName = DISPLAY_NAME,
            status = STATUS_HEALTHY,
            config = config,
            collectMetrics = true,
            collectInventory = true,
            collectCost = true,
            collectLogs = false,
            externalId = EXTERNAL_ID,
            lastSyncAt = TIMESTAMP,
            lastError = null,
            createdAt = TIMESTAMP,
            updatedAt = TIMESTAMP
        )

    private fun sourceBody(): String =
        """
        {
          "provider": "$PROVIDER_AWS",
          "displayName": "$DISPLAY_NAME",
          "config": {"accountId": "$AWS_ACCOUNT_ID", "roleName": "$AWS_ROLE_NAME"},
          "collectMetrics": true,
          "collectInventory": true,
          "collectCost": true,
          "collectLogs": false
        }
        """.trimIndent()
}
