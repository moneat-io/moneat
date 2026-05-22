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

import com.moneat.mcp.auth.McpAuthProvider
import com.moneat.mcp.auth.McpAuthResult
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpResourceRegistry
import com.moneat.mcp.protocol.McpToolRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult as SdkCallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult as SdkReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private const val MCP_SERVER_NAME = "moneat-mcp-server"
private const val MCP_SERVER_VERSION = "1.0.0"
private const val MCP_SESSION_ID_HEADER = "mcp-session-id"

private val sessions = ConcurrentHashMap<String, McpStreamableSession>()
private val sessionCleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

fun Route.mcpRoutes(
    toolRegistry: McpToolRegistry,
    resourceRegistry: McpResourceRegistry,
) {
    rateLimit(RateLimitName("mcp")) {
        route("/v1/mcp") {
            post {
                val session = resolveOrCreateSession(call, toolRegistry, resourceRegistry)
                    ?: return@post
                session.transport.handleRequest(null, call)
                if (session.transport.sessionId == null) {
                    session.close()
                }
            }

            get {
                call.respond(
                    HttpStatusCode.MethodNotAllowed,
                    mapOf("error" to "Server notifications are not exposed")
                )
            }

            delete {
                val session = resolveSession(call) ?: return@delete
                session.transport.handleRequest(null, call)
                session.close()
            }
        }
    }
}

private suspend fun resolveOrCreateSession(
    call: ApplicationCall,
    toolRegistry: McpToolRegistry,
    resourceRegistry: McpResourceRegistry,
): McpStreamableSession? {
    val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
    if (!sessionId.isNullOrBlank()) {
        return resolveSession(call)
    }

    val auth = authenticate(call) ?: return null
    val transport = StreamableHttpServerTransport(
        StreamableHttpServerTransport.Configuration(enableJsonResponse = true)
    )
    val session = McpStreamableSession(
        tokenId = auth.tokenId,
        transport = transport,
        context = McpContext(
            organizationId = auth.organizationId,
            userId = auth.userId,
            tokenId = auth.tokenId,
            scopes = auth.scopes,
            sessionId = "pending:${auth.tokenId}",
        ),
    )
    val server = createMcpServer({ session.context }, toolRegistry, resourceRegistry)
    session.server = server
    transport.setOnSessionInitialized { initializedSessionId ->
        session.context = session.context.copy(sessionId = initializedSessionId)
        sessions[initializedSessionId] = session
        logger.debug { "Initialized MCP Streamable HTTP session $initializedSessionId" }
    }
    transport.setOnSessionClosed { closedSessionId ->
        sessions.remove(closedSessionId)?.closeAsync()
        logger.debug { "Closed MCP Streamable HTTP session $closedSessionId" }
    }
    server.createSession(transport)
    return session
}

private suspend fun resolveSession(call: ApplicationCall): McpStreamableSession? {
    val auth = authenticate(call) ?: return null
    val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
    if (sessionId.isNullOrBlank()) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing MCP session id"))
        return null
    }

    val session = sessions[sessionId]
    if (session == null) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "MCP session not found"))
        return null
    }
    if (session.tokenId != auth.tokenId) {
        call.respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "MCP session does not belong to this token")
        )
        return null
    }
    return session
}

private suspend fun authenticate(call: ApplicationCall): McpAuthResult? {
    val auth = McpAuthProvider.validateAuthorization(call.request.header("Authorization"))
    if (auth == null) {
        call.respond(
            HttpStatusCode.Unauthorized,
            mapOf("error" to "Invalid or missing bearer token")
        )
    }
    return auth
}

private fun createMcpServer(
    contextProvider: () -> McpContext,
    toolRegistry: McpToolRegistry,
    resourceRegistry: McpResourceRegistry,
): Server {
    val server = Server(
        serverInfo = Implementation(
            name = MCP_SERVER_NAME,
            version = MCP_SERVER_VERSION,
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
                resources = ServerCapabilities.Resources(listChanged = false, subscribe = false),
            )
        ),
    )

    toolRegistry.listTools().forEach { tool ->
        server.addTool(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.inputSchema.toSdkToolSchema(),
            toolAnnotations = ToolAnnotations(
                readOnlyHint = tool.readOnly,
                destructiveHint = !tool.readOnly,
            ),
        ) { request ->
            val result = toolRegistry.callTool(
                name = request.name,
                args = request.arguments ?: JsonObject(emptyMap()),
                context = contextProvider(),
            )
            SdkCallToolResult(
                content = result.content.map { content ->
                    TextContent(text = content.text ?: "")
                },
                isError = result.isError,
            )
        }
    }

    resourceRegistry.listResources().forEach { resource ->
        server.addResource(
            uri = resource.uri,
            name = resource.name,
            description = resource.description.orEmpty(),
            mimeType = resource.mimeType ?: "application/json",
        ) { request ->
            val result = resourceRegistry.readResource(
                uri = request.uri,
                context = contextProvider(),
            )
            SdkReadResourceResult(
                contents = result.contents.map { content ->
                    TextResourceContents(
                        text = content.text ?: "",
                        uri = content.uri,
                        mimeType = content.mimeType,
                    )
                },
            )
        }
    }

    logger.debug { "Created MCP server" }
    return server
}

private class McpStreamableSession(
    val tokenId: Int,
    val transport: StreamableHttpServerTransport,
    @Volatile
    var context: McpContext,
) {
    lateinit var server: Server
    private val closed = AtomicBoolean(false)

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        transport.sessionId?.let { sessions.remove(it) }
        transport.close()
        if (::server.isInitialized) {
            server.close()
        }
    }

    fun closeAsync() {
        if (!closed.compareAndSet(false, true)) return
        transport.sessionId?.let { sessions.remove(it) }
        if (!::server.isInitialized) return
        sessionCleanupScope.launch {
            server.close()
        }
    }
}

private fun InputSchema.toSdkToolSchema(): ToolSchema =
    ToolSchema(
        properties = properties,
        required = required.takeIf { it.isNotEmpty() },
    )
