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

package com.moneat.mcp.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.mcp.auth.McpScopes
import com.moneat.mcp.models.CreateMcpApiKeyResponse
import com.moneat.mcp.models.McpApiKeysResponse
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpResource
import com.moneat.mcp.protocol.McpResourceRegistry
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.mcp.protocol.ResourceContent
import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.mcp.protocol.ToolContent
import com.moneat.mcp.services.McpApiKeyService
import com.moneat.shared.models.McpApiKeys
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpApiKeyRoutesTest {
    companion object {
        private var db: Database? = null
        private val jsonParser = Json { ignoreUnknownKeys = true }
    }

    private lateinit var service: McpApiKeyService
    private lateinit var toolRegistry: McpToolRegistry
    private lateinit var resourceRegistry: McpResourceRegistry
    private lateinit var token: String
    private var organizationId = 0
    private var userId = 0

    @BeforeEach
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_mcp_api_key_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, McpApiKeys)
        transaction {
            organizationId = Organizations.insert {
                it[name] = "Route Org"
                it[slug] = "route-org"
            }[Organizations.id]
            userId = Users.insert {
                it[email] = "mcp-route@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            }[Users.id]
        }
        service = McpApiKeyService()
        toolRegistry = McpToolRegistry().also {
            it.register(RouteTool("search_logs"))
            it.register(RouteTool("get_issue"))
        }
        resourceRegistry = McpResourceRegistry().also {
            it.register(RouteResource("moneat://org"))
        }
        token = JWT.create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .withClaim("orgId", organizationId)
            .sign(Algorithm.HMAC256("test-secret"))
    }

    @Test
    fun `GET tool catalog returns registered tools and resources`() = testApplication {
        setupApp()

        val response = client.get("/v1/mcp/tool-catalog") {
            authHeader()
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("search_logs"), body)
        assertTrue(body.contains("moneat://org"), body)
    }

    @Test
    fun `GET api keys lists active keys for token organization`() = testApplication {
        setupApp()
        val created = service.createKey(organizationId, userId, "listed", listOf("search_logs"), emptyList())
        val otherOrganizationId = transaction {
            Organizations.insert {
                it[name] = "Other Route Org"
                it[slug] = "other-route-org"
            }[Organizations.id]
        }
        service.createKey(otherOrganizationId, userId, "other", listOf("get_issue"), emptyList())

        val response = client.get("/v1/mcp/api-keys") {
            authHeader()
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = jsonParser.decodeFromString<McpApiKeysResponse>(response.bodyAsText())
        assertEquals(listOf(created.id), body.keys.map { it.id })
        assertEquals("listed", body.keys.single().name)
    }

    @Test
    fun `POST api keys creates key when selection is valid`() = testApplication {
        setupApp()

        val response = client.post("/v1/mcp/api-keys") {
            jsonBody()
            setBody(
                """
                {
                  "name": "route key",
                  "enabledTools": ["search_logs", "get_issue"],
                  "enabledResources": ["moneat://org"],
                  "expiresInDays": 14
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = jsonParser.decodeFromString<CreateMcpApiKeyResponse>(response.bodyAsText())
        assertTrue(body.key.startsWith("mmcp_"))
        assertEquals(listOf("get_issue", "search_logs"), body.enabledTools)
        assertEquals(listOf("moneat://org"), body.enabledResources)
    }

    @Test
    fun `POST api keys rejects invalid selections and names`() = testApplication {
        setupApp()

        val emptyTools = postKey("""{"name":"empty","enabledTools":[]}""")
        assertEquals(HttpStatusCode.BadRequest, emptyTools.status)
        assertTrue(emptyTools.bodyAsText().contains("At least one MCP tool must be enabled"))

        val unknownTool = postKey("""{"name":"unknown","enabledTools":["missing_tool"]}""")
        assertEquals(HttpStatusCode.BadRequest, unknownTool.status)
        assertTrue(unknownTool.bodyAsText().contains("Unknown MCP tool: missing_tool"))

        val unknownResource = postKey(
            """{"name":"unknown","enabledTools":["search_logs"],"enabledResources":["moneat://missing"]}""",
        )
        assertEquals(HttpStatusCode.BadRequest, unknownResource.status)
        assertTrue(unknownResource.bodyAsText().contains("Unknown MCP resource: moneat://missing"))

        val blankName = postKey("""{"name":"   ","enabledTools":["search_logs"]}""")
        assertEquals(HttpStatusCode.BadRequest, blankName.status)
        assertTrue(blankName.bodyAsText().contains("Name is required"))
    }

    @Test
    fun `PUT api keys updates existing key and handles invalid ids`() = testApplication {
        setupApp()
        val created = service.createKey(organizationId, userId, "before", listOf("search_logs"), emptyList())

        val invalidId = client.put("/v1/mcp/api-keys/not-a-number") {
            jsonBody()
            setBody("""{"name":"ignored"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidId.status)
        assertTrue(invalidId.bodyAsText().contains("Invalid key ID"))

        val missing = client.put("/v1/mcp/api-keys/999999") {
            jsonBody()
            setBody("""{"name":"missing","enabledTools":["search_logs"]}""")
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertTrue(missing.bodyAsText().contains("Key not found"))

        val updated = client.put("/v1/mcp/api-keys/${created.id}") {
            jsonBody()
            setBody(
                """
                {
                  "name": "after",
                  "enabledTools": ["get_issue"],
                  "enabledResources": ["moneat://org"],
                  "expiresInDays": 30
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.OK, updated.status)
        val key = service.listKeys(organizationId).single()
        assertEquals("after", key.name)
        assertEquals(listOf("get_issue"), key.enabledTools)
        assertEquals(listOf("moneat://org"), key.enabledResources)
    }

    @Test
    fun `DELETE api keys revokes existing key and handles missing ids`() = testApplication {
        setupApp()
        val created = service.createKey(organizationId, userId, "delete me", listOf("search_logs"), emptyList())

        val invalidId = client.delete("/v1/mcp/api-keys/not-a-number") {
            authHeader()
        }
        assertEquals(HttpStatusCode.BadRequest, invalidId.status)
        assertTrue(invalidId.bodyAsText().contains("Invalid key ID"))

        val missing = client.delete("/v1/mcp/api-keys/999999") {
            authHeader()
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertTrue(missing.bodyAsText().contains("Key not found"))

        val deleted = client.delete("/v1/mcp/api-keys/${created.id}") {
            authHeader()
        }

        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertTrue(service.listKeys(organizationId).isEmpty())
        assertFalse(service.revokeKey(organizationId + 1, created.id))
    }

    private fun ApplicationTestBuilder.setupApp() {
        application {
            install(ContentNegotiation) {
                json()
            }
            install(Authentication) {
                jwt("auth-jwt") {
                    verifier(
                        JWT.require(Algorithm.HMAC256("test-secret"))
                            .withIssuer("moneat")
                            .withAudience("moneat-users")
                            .build(),
                    )
                    validate { JWTPrincipal(it.payload) }
                }
            }
            routing {
                mcpApiKeyRoutes(toolRegistry, resourceRegistry, service)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.postKey(body: String) =
        client.post("/v1/mcp/api-keys") {
            jsonBody()
            setBody(body)
        }

    private fun io.ktor.client.request.HttpRequestBuilder.authHeader() {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.jsonBody() {
        authHeader()
        contentType(ContentType.Application.Json)
    }
}

private class RouteTool(override val name: String) : McpTool {
    override val description: String = "$name description"
    override val inputSchema: InputSchema = InputSchema()
    override val requiredScopes: Set<String> = setOf(McpScopes.ORG_READ)

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult =
        ToolCallResult(content = listOf(ToolContent(text = name)))
}

private class RouteResource(override val uri: String) : McpResource {
    override val name: String = uri
    override val description: String = "$uri description"

    override suspend fun read(context: McpContext): ResourceContent =
        ResourceContent(uri = uri, text = "{}")
}
