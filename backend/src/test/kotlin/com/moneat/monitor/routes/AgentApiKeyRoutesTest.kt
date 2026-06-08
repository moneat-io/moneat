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

package com.moneat.monitor.routes

import com.moneat.monitor.models.AgentApiKeyResponse
import com.moneat.monitor.models.CreateAgentApiKeyResponse
import com.moneat.monitor.services.AgentApiKeyService
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentApiKeyRoutesTest {

    private val service = mockk<AgentApiKeyService>()

    @Test
    fun `GET agent-api-keys uses JWT org`() = testApplication {
        every { service.listKeys(42) } returns listOf(
            AgentApiKeyResponse(
                id = 1,
                name = "collector",
                keyPrefix = "magt_prefix",
                createdAt = "2026-01-01T00:00:00Z"
            )
        )

        installRoutes()
        val token = RouteTestSupport.createToken(userId = 7, orgId = 42)
        val response = client.get("/v1/agent-api-keys") { withAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("collector"))
        verify { service.listKeys(42) }
    }

    @Test
    fun `POST agent-api-keys uses JWT org and user`() = testApplication {
        every { service.createKey(organizationId = 42, name = "collector", createdBy = 7) } returns
            CreateAgentApiKeyResponse(
                id = 1,
                name = "collector",
                keyPrefix = "magt_prefix",
                key = "magt_secret",
                createdAt = "2026-01-01T00:00:00Z"
            )

        installRoutes()
        val token = RouteTestSupport.createToken(userId = 7, orgId = 42)
        val response =
            client.post("/v1/agent-api-keys") {
                withAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":" collector "}""")
            }

        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.bodyAsText().contains("magt_secret"))
        verify { service.createKey(organizationId = 42, name = "collector", createdBy = 7) }
    }

    @Test
    fun `DELETE agent-api-keys uses JWT org`() = testApplication {
        every { service.deleteKey(organizationId = 42, keyId = 9) } returns true

        installRoutes()
        val token = RouteTestSupport.createToken(userId = 7, orgId = 42)
        val response = client.delete("/v1/agent-api-keys/9") { withAuth(token) }

        assertEquals(HttpStatusCode.NoContent, response.status)
        verify { service.deleteKey(organizationId = 42, keyId = 9) }
    }

    @Test
    fun `agent-api-key routes return 401 when jwt has no current org claim`() = testApplication {
        installRoutes()
        val token = RouteTestSupport.createToken(userId = 7, orgId = null)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/agent-api-keys") { withAuth(token) }.status)

        val createResponse =
            client.post("/v1/agent-api-keys") {
                withAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"collector"}""")
            }
        assertEquals(HttpStatusCode.Unauthorized, createResponse.status)

        assertEquals(HttpStatusCode.Unauthorized, client.delete("/v1/agent-api-keys/9") { withAuth(token) }.status)
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installRoutes() {
        application {
            installJwtAuth()
            routing { agentApiKeyRoutes(service) }
        }
    }
}
