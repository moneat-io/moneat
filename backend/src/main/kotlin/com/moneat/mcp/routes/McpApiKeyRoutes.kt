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

import com.moneat.mcp.models.CreateMcpApiKeyRequest
import com.moneat.mcp.models.McpApiKeysResponse
import com.moneat.mcp.models.UpdateMcpApiKeyRequest
import com.moneat.mcp.protocol.McpResourceRegistry
import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.mcp.services.McpApiKeyService
import com.moneat.mcp.services.McpToolCatalogService
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.mcpApiKeyRoutes(
    toolRegistry: McpToolRegistry,
    resourceRegistry: McpResourceRegistry,
    mcpApiKeyService: McpApiKeyService = McpApiKeyService(),
) {
    authenticate("auth-jwt") {
        route("/v1/mcp") {
            get("/tool-catalog") {
                call.respondToolCatalog(toolRegistry, resourceRegistry)
            }

            route("/api-keys") {
                get {
                    call.respondMcpApiKeys(mcpApiKeyService)
                }

                post {
                    call.createMcpApiKey(mcpApiKeyService, toolRegistry, resourceRegistry)
                }

                put("/{id}") {
                    call.updateMcpApiKey(mcpApiKeyService, toolRegistry, resourceRegistry)
                }

                delete("/{id}") {
                    call.deleteMcpApiKey(mcpApiKeyService)
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondToolCatalog(
    toolRegistry: McpToolRegistry,
    resourceRegistry: McpResourceRegistry,
) {
    respond(
        HttpStatusCode.OK,
        McpToolCatalogService.buildCatalog(toolRegistry, resourceRegistry),
    )
}

private suspend fun ApplicationCall.respondMcpApiKeys(mcpApiKeyService: McpApiKeyService) {
    respond(
        HttpStatusCode.OK,
        McpApiKeysResponse(mcpApiKeyService.listKeys(mcpOrgId)),
    )
}

private suspend fun ApplicationCall.createMcpApiKey(
    mcpApiKeyService: McpApiKeyService,
    toolRegistry: McpToolRegistry,
    resourceRegistry: McpResourceRegistry,
) {
    val request = receive<CreateMcpApiKeyRequest>()
    val validationError = validateToolSelection(
        request.enabledTools,
        request.enabledResources,
        toolRegistry,
        resourceRegistry,
    )
    if (validationError != null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(validationError))
        return
    }

    try {
        val response = mcpApiKeyService.createKey(
            organizationId = mcpOrgId,
            userId = mcpUserId,
            name = request.name,
            enabledTools = request.enabledTools,
            enabledResources = request.enabledResources,
            expiresInDays = request.expiresInDays,
        )
        respond(HttpStatusCode.Created, response)
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
    }
}

private suspend fun ApplicationCall.updateMcpApiKey(
    mcpApiKeyService: McpApiKeyService,
    toolRegistry: McpToolRegistry,
    resourceRegistry: McpResourceRegistry,
) {
    val keyId = callKeyId() ?: return
    val request = receive<UpdateMcpApiKeyRequest>()
    val validationError = validateToolSelection(
        request.enabledTools,
        request.enabledResources,
        toolRegistry,
        resourceRegistry,
    )
    if (validationError != null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(validationError))
        return
    }

    try {
        val updated = mcpApiKeyService.updateKey(
            organizationId = mcpOrgId,
            keyId = keyId,
            name = request.name,
            enabledTools = request.enabledTools,
            enabledResources = request.enabledResources,
            expiresInDays = request.expiresInDays,
        )
        respondUpdateResult(updated)
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
    }
}

private suspend fun ApplicationCall.deleteMcpApiKey(mcpApiKeyService: McpApiKeyService) {
    val keyId = callKeyId() ?: return
    val deleted = mcpApiKeyService.revokeKey(mcpOrgId, keyId)
    if (deleted) {
        respond(HttpStatusCode.NoContent)
    } else {
        respond(HttpStatusCode.NotFound, ErrorResponse("Key not found"))
    }
}

private suspend fun ApplicationCall.callKeyId(): Int? {
    val keyId = parameters["id"]?.toIntOrNull()
    if (keyId == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid key ID"))
    }
    return keyId
}

private suspend fun ApplicationCall.respondUpdateResult(updated: Boolean) {
    if (updated) {
        respond(HttpStatusCode.OK)
    } else {
        respond(HttpStatusCode.NotFound, ErrorResponse("Key not found"))
    }
}

private val ApplicationCall.mcpOrgId: Int
    get() = principal<JWTPrincipal>()!!.payload.getClaim("orgId").asInt()

private val ApplicationCall.mcpUserId: Int
    get() = principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()

private fun validateToolSelection(
    tools: List<String>?,
    resources: List<String>?,
    toolRegistry: McpToolRegistry,
    resourceRegistry: McpResourceRegistry,
): String? {
    if (tools != null && tools.isEmpty()) {
        return "At least one MCP tool must be enabled"
    }

    val unknownTool = tools?.firstOrNull { !toolRegistry.hasTool(it) }
    if (unknownTool != null) {
        return "Unknown MCP tool: $unknownTool"
    }

    val unknownResource = resources?.firstOrNull { !resourceRegistry.hasResource(it) }
    if (unknownResource != null) {
        return "Unknown MCP resource: $unknownResource"
    }

    return null
}
