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
import com.moneat.auth.services.AuthTokenService
import com.moneat.mcp.McpModule
import com.moneat.mcp.auth.McpScopes
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpResource
import com.moneat.mcp.protocol.McpResourceRegistry
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.mcp.protocol.ResourceContent
import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.mcp.protocol.ToolContent
import com.moneat.shared.models.AuthTokens
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class McpRoutesTest {
    companion object {
        private var db: Database? = null
    }

    private lateinit var token: String

    @BeforeEach
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_mcp_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, AuthTokens)
        token = seedToken(scopes = listOf("project:read", "org:read", "event:read"))
    }

    @Test
    fun `POST v1 mcp initializes`() = testApplication {
        setupApp()

        val response = client.post("/v1/mcp") {
            mcpHeaders()
            setBody(initializeRequest())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("moneat-mcp-server"), body)
        assertTrue(response.headers["mcp-session-id"]?.isNotBlank() == true)
    }

    @Test
    fun `POST v1 mcp lists tools`() = testApplication {
        setupApp()
        val sessionId = initializeSession()

        val response = client.post("/v1/mcp") {
            mcpHeaders()
            header("mcp-session-id", sessionId)
            setBody("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("authorized_read"), body)
    }

    @Test
    fun `POST v1 mcp calls authorized read tool`() = testApplication {
        setupApp()
        val sessionId = initializeSession()

        val response = client.post("/v1/mcp") {
            mcpHeaders()
            header("mcp-session-id", sessionId)
            setBody(
                """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"authorized_read","arguments":{}}}
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("authorized"), body)
    }

    @Test
    fun `legacy mcp routes are not registered`() = testApplication {
        setupApp()

        assertEquals(HttpStatusCode.NotFound, client.post("/v1/mcp/sse").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/mcp/message").status)
    }

    @Test
    fun `GET v1 mcp returns method not allowed`() = testApplication {
        setupApp()

        val response = client.get("/v1/mcp")

        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        assertTrue(response.bodyAsText().contains("Server notifications are not exposed"))
    }

    @Test
    fun `POST v1 mcp rejects missing bearer token`() = testApplication {
        setupApp()

        val response = client.post("/v1/mcp") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody(initializeRequest())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("Invalid or missing bearer token"))
    }

    @Test
    fun `DELETE v1 mcp requires session id`() = testApplication {
        setupApp()

        val response = client.delete("/v1/mcp") {
            mcpHeaders()
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Missing MCP session id"))
    }

    @Test
    fun `DELETE v1 mcp rejects unknown session id`() = testApplication {
        setupApp()

        val response = client.delete("/v1/mcp") {
            mcpHeaders()
            header("mcp-session-id", "missing-session")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("MCP session not found"))
    }

    @Test
    fun `DELETE v1 mcp rejects session from another token`() = testApplication {
        setupApp()
        val sessionId = initializeSession()
        val otherToken = seedToken(
            scopes = listOf("project:read", "org:read", "event:read"),
            label = "other",
        )

        val response = client.delete("/v1/mcp") {
            mcpHeaders(otherToken)
            header("mcp-session-id", sessionId)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("MCP session does not belong to this token"))
    }

    @Test
    fun `POST v1 mcp reads registered resource`() = testApplication {
        setupApp()
        val sessionId = initializeSession()

        val response = client.post("/v1/mcp") {
            mcpHeaders()
            header("mcp-session-id", sessionId)
            setBody(
                """
                {
                  "jsonrpc": "2.0",
                  "id": 4,
                  "method": "resources/read",
                  "params": {"uri": "moneat://test-resource"}
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("resource ok"))
    }

    @Test
    fun `core module registers MCP routes`() = testApplication {
        setupRealModuleApp()

        val response = client.get("/v1/mcp")

        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.setupApp() {
        application {
            install(ContentNegotiation) {
                json()
            }
            install(RateLimit) {
                register(RateLimitName("mcp")) {
                    requestKey { "test" }
                    rateLimiter(limit = 100, refillPeriod = 1.seconds)
                }
            }
            routing {
                mcpRoutes(
                    toolRegistry = McpToolRegistry().also { it.register(AuthorizedReadTool()) },
                    resourceRegistry = McpResourceRegistry().also { it.register(TestResource()) },
                )
            }
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.setupRealModuleApp() {
        application {
            install(ContentNegotiation) {
                json()
            }
            install(RateLimit) {
                register(RateLimitName("mcp")) {
                    requestKey { "test" }
                    rateLimiter(limit = 100, refillPeriod = 1.seconds)
                }
            }
            install(Authentication) {
                jwt("auth-jwt") {
                    verifier(
                        JWT.require(Algorithm.HMAC256("test-secret"))
                            .withIssuer("moneat")
                            .withAudience("moneat-users")
                            .build()
                    )
                    validate { JWTPrincipal(it.payload) }
                }
            }
            routing {
                McpModule.registerRoutes(this)
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.mcpHeaders(tokenValue: String = token) {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        header(HttpHeaders.Authorization, "Bearer $tokenValue")
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.initializeSession(): String {
        val response = client.post("/v1/mcp") {
            mcpHeaders()
            setBody(initializeRequest())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.headers["mcp-session-id"] ?: error("MCP session id missing")
    }

    private fun initializeRequest(): String =
        """
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "initialize",
          "params": {
            "protocolVersion": "2025-11-25",
            "capabilities": {},
            "clientInfo": {"name": "moneat-test", "version": "1.0.0"}
          }
        }
        """.trimIndent()

    private fun seedToken(scopes: List<String>, label: String = "test"): String {
        val userId = transaction {
            val orgId = Organizations.insert {
                it[name] = "$label Org"
                it[slug] = "$label-org"
            }[Organizations.id]
            val insertedUserId = Users.insert {
                it[email] = "mcp-$label@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            }[Users.id]
            Memberships.insert {
                it[user_id] = insertedUserId
                it[organization_id] = orgId
                it[role] = "owner"
            }
            insertedUserId
        }
        return AuthTokenService()
            .generateToken(
                userId = userId,
                name = "MCP test token",
                scopes = scopes,
            )
            .token ?: error("Generated token was missing")
    }
}

private class AuthorizedReadTool : McpTool {
    override val name: String = "authorized_read"
    override val description: String = "Authorized read"
    override val inputSchema: InputSchema = InputSchema()
    override val requiredScopes = setOf(McpScopes.PROJECT_READ)

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult =
        ToolCallResult(content = listOf(ToolContent(text = "authorized")))
}

private class TestResource : McpResource {
    override val uri: String = "moneat://test-resource"
    override val name: String = "Test resource"
    override val description: String = "Test resource"
    override val requiredScopes: Set<String> = setOf(McpScopes.ORG_READ)

    override suspend fun read(context: McpContext): ResourceContent =
        ResourceContent(uri = uri, text = """{"message":"resource ok"}""")
}
