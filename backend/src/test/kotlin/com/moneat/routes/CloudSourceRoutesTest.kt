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
import com.moneat.plugins.installErrorHandling
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
        const val SOURCE_ID = 1
        const val PROVIDER_AWS = "aws"
        const val DISPLAY_NAME = "Production AWS"
        const val AWS_ACCOUNT_ID = "123456789012"
        const val AWS_ROLE_NAME = "MoneatIntegrationRole"
        const val EXTERNAL_ID = "mnt-ext-test"
        const val AWS_PRINCIPAL_ARN = "arn:aws:iam::499432741914:root"
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

        val token = RouteTestSupport.createToken(userId = USER_ID)
        val response = client.get("/v1/cloud-sources") { withAuth(token) }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Organization context required"))
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
        coEvery { cloudSourceService.createSource(ORGANIZATION_ID, USER_ID, request) } returns CloudSourceResponse(
            id = SOURCE_ID,
            provider = PROVIDER_AWS,
            displayName = DISPLAY_NAME,
            status = STATUS_HEALTHY,
            config = request.config,
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
}
