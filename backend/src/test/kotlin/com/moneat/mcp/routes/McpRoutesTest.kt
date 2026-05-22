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

import com.moneat.auth.services.AuthTokenService
import com.moneat.mcp.auth.McpScopes
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpResourceRegistry
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.mcp.protocol.ToolContent
import com.moneat.shared.models.AuthTokens
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
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
                    resourceRegistry = McpResourceRegistry(),
                )
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.mcpHeaders() {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        header(HttpHeaders.Authorization, "Bearer $token")
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

    private fun seedToken(scopes: List<String>): String {
        val userId = transaction {
            val orgId = Organizations.insert {
                it[name] = "Test Org"
                it[slug] = "test-org"
            }[Organizations.id]
            val insertedUserId = Users.insert {
                it[email] = "mcp@test.com"
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
