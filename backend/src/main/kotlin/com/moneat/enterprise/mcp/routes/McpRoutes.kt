// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.mcp.routes

import com.moneat.enterprise.mcp.auth.McpAuthProvider
import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InitializeParams
import com.moneat.enterprise.mcp.protocol.InitializeResult
import com.moneat.enterprise.mcp.protocol.JsonRpcErrorCodes
import com.moneat.enterprise.mcp.protocol.JsonRpcRequest
import com.moneat.enterprise.mcp.protocol.JsonRpcResponse
import com.moneat.enterprise.mcp.protocol.McpResourceRegistry
import com.moneat.enterprise.mcp.protocol.McpSession
import com.moneat.enterprise.mcp.protocol.McpToolRegistry
import com.moneat.enterprise.mcp.protocol.McpTransport
import com.moneat.enterprise.mcp.protocol.ResourceReadParams
import com.moneat.enterprise.mcp.protocol.ResourcesListResult
import com.moneat.enterprise.mcp.protocol.ToolCallParams
import com.moneat.enterprise.mcp.protocol.ToolsListResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val SSE_HEARTBEAT_INTERVAL_MS = 15_000L

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    explicitNulls = false
}

private const val RESOURCE_READ_TIMEOUT_MS = 20_000L

@Suppress("LongMethod", "CyclomaticComplexity")
fun Route.mcpRoutes(
    toolRegistry: McpToolRegistry,
    resourceRegistry: McpResourceRegistry,
    requestScope: CoroutineScope
) {
    route("/v1/mcp") {

        // SSE endpoint - client connects here to establish MCP session
        get("/sse") {
            val token = call.request.queryParameters["token"]
                ?: call.request.headers["Authorization"]

            val auth = McpAuthProvider.validate(token)
            if (auth == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Invalid or missing token")
                )
                return@get
            }

            val session = McpTransport.createSession(
                auth.organizationId,
                auth.userId
            )

            call.respondTextWriter(
                contentType = ContentType.Text.EventStream
            ) {
                // Send endpoint event per MCP SSE spec
                write(
                    "event: endpoint\n" +
                        "data: /v1/mcp/message?sessionId=${session.id}\n\n"
                )
                flush()

                try {
                    // Single-writer loop: heartbeat and messages share one writer
                    while (true) {
                        val message = kotlinx.coroutines.withTimeoutOrNull(
                            SSE_HEARTBEAT_INTERVAL_MS
                        ) {
                            session.channel.receive()
                        }
                        if (message != null) {
                            write(message)
                            flush()
                            session.touch()
                        } else {
                            // No message within heartbeat interval — send keepalive
                            write(": heartbeat\n\n")
                            flush()
                            session.touch()
                        }
                    }
                } catch (@Suppress("SwallowedException") e: ClosedReceiveChannelException) {
                    logger.debug { "MCP session ${session.id} channel closed" }
                } catch (@Suppress("SwallowedException") e: kotlinx.coroutines.CancellationException) {
                    logger.debug { "MCP session ${session.id} SSE cancelled" }
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    // Write failure — client disconnected
                    logger.debug { "MCP session ${session.id} write failed: ${e.message}" }
                } finally {
                    McpTransport.removeSession(session.id)
                }
            }
        }

        // JSON-RPC message endpoint
        post("/message") {
            val token = call.request.queryParameters["token"]
                ?: call.request.headers["Authorization"]

            val auth = McpAuthProvider.validate(token)
            if (auth == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Invalid or missing token")
                )
                return@post
            }

            val sessionId = call.request.queryParameters["sessionId"]
            if (sessionId == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Missing sessionId")
                )
                return@post
            }

            val session = McpTransport.getSession(sessionId)
            if (session == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Session not found")
                )
                return@post
            }

            if (session.organizationId != auth.organizationId || session.userId != auth.userId) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "Session does not belong to this user")
                )
                return@post
            }

            session.touch()

            val request = try {
                call.receive<JsonRpcRequest>()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.debug(e) { "Failed to parse JsonRpcRequest" }
                call.respond(HttpStatusCode.BadRequest, parseErrorResponse())
                return@post
            }

            // Respond 202 immediately per MCP spec — tool response arrives via SSE.
            call.respond(HttpStatusCode.Accepted, "")

            if (request.id != null) {
                requestScope.launch {
                    val response = try {
                        handleRequest(
                            request, session, toolRegistry, resourceRegistry
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        logger.error(e) { "Unhandled error processing ${request.method}" }
                        JsonRpcResponse(
                            id = request.id,
                            error = com.moneat.enterprise.mcp.protocol.JsonRpcError(
                                code = JsonRpcErrorCodes.INTERNAL_ERROR,
                                message = "Internal error: ${e.message}"
                            )
                        )
                    }
                    if (response != null) {
                        session.sendMessage(response)
                    }
                }
            } else {
                // Notification (no id) — fire-and-forget, no response expected
                requestScope.launch {
                    try {
                        handleRequest(
                            request, session, toolRegistry, resourceRegistry
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        logger.error(e) { "Unhandled error processing ${request.method}" }
                    }
                }
            }
        }
    }
}

@Suppress("LongMethod")
private suspend fun handleRequest(
    request: JsonRpcRequest,
    session: McpSession,
    toolRegistry: McpToolRegistry,
    resourceRegistry: McpResourceRegistry
): JsonRpcResponse? {
    val context = McpContext(
        organizationId = session.organizationId,
        userId = session.userId,
        sessionId = session.id
    )

    return when (request.method) {
        "initialize" -> handleInitialize(request)
        "initialized" -> null
        "ping" -> JsonRpcResponse(
            id = request.id,
            result = JsonObject(emptyMap())
        )
        "tools/list" -> handleToolsList(request, toolRegistry)
        "tools/call" -> handleToolCall(
            request, toolRegistry, context
        )
        "resources/list" -> handleResourcesList(
            request, resourceRegistry
        )
        "resources/read" -> handleResourceRead(
            request, resourceRegistry, context
        )
        "prompts/list" -> JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(
                mapOf("prompts" to emptyList<String>())
            )
        )
        else -> methodNotFoundResponse(request)
    }
}

private fun handleInitialize(
    request: JsonRpcRequest
): JsonRpcResponse {
    val result = InitializeResult()
    return JsonRpcResponse(
        id = request.id,
        result = json.encodeToJsonElement(result)
    )
}

private fun handleToolsList(
    request: JsonRpcRequest,
    registry: McpToolRegistry
): JsonRpcResponse {
    val tools = registry.listTools()
    val result = ToolsListResult(tools = tools)
    return JsonRpcResponse(
        id = request.id,
        result = json.encodeToJsonElement(result)
    )
}

private suspend fun handleToolCall(
    request: JsonRpcRequest,
    registry: McpToolRegistry,
    context: McpContext
): JsonRpcResponse {
    val params = request.params ?: return invalidParamsResponse(request)
    val callParams = try {
        json.decodeFromJsonElement<ToolCallParams>(params)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        logger.error(e) { "Failed to decode ToolCallParams: $params" }
        return invalidParamsResponse(request)
    }

    val result = registry.callTool(
        callParams.name,
        callParams.arguments ?: JsonObject(emptyMap()),
        context
    )

    return JsonRpcResponse(
        id = request.id,
        result = json.encodeToJsonElement(result)
    )
}

private fun handleResourcesList(
    request: JsonRpcRequest,
    registry: McpResourceRegistry
): JsonRpcResponse {
    val resources = registry.listResources()
    val result = ResourcesListResult(resources = resources)
    return JsonRpcResponse(
        id = request.id,
        result = json.encodeToJsonElement(result)
    )
}

private suspend fun handleResourceRead(
    request: JsonRpcRequest,
    registry: McpResourceRegistry,
    context: McpContext
): JsonRpcResponse {
    val params = request.params ?: return invalidParamsResponse(request)
    val readParams = try {
        json.decodeFromJsonElement<ResourceReadParams>(params)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        logger.debug(e) { "Failed to decode ResourceReadParams: $params" }
        return invalidParamsResponse(request)
    }

    return try {
        val result = kotlinx.coroutines.withTimeout(RESOURCE_READ_TIMEOUT_MS) {
            registry.readResource(readParams.uri, context)
        }
        JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(result)
        )
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        logger.warn { "Resource read for ${readParams.uri} timed out after ${RESOURCE_READ_TIMEOUT_MS}ms" }
        JsonRpcResponse(
            id = request.id,
            error = com.moneat.enterprise.mcp.protocol.JsonRpcError(
                code = JsonRpcErrorCodes.INTERNAL_ERROR,
                message = "Resource read timed out"
            )
        )
    }
}

private fun parseErrorResponse() = JsonRpcResponse(
    error = com.moneat.enterprise.mcp.protocol.JsonRpcError(
        code = JsonRpcErrorCodes.PARSE_ERROR,
        message = "Parse error"
    )
)

private fun methodNotFoundResponse(
    request: JsonRpcRequest
) = JsonRpcResponse(
    id = request.id,
    error = com.moneat.enterprise.mcp.protocol.JsonRpcError(
        code = JsonRpcErrorCodes.METHOD_NOT_FOUND,
        message = "Method not found: ${request.method}"
    )
)

private fun invalidParamsResponse(
    request: JsonRpcRequest
) = JsonRpcResponse(
    id = request.id,
    error = com.moneat.enterprise.mcp.protocol.JsonRpcError(
        code = JsonRpcErrorCodes.INVALID_PARAMS,
        message = "Invalid params"
    )
)
