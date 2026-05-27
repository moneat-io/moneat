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
                call.respond(
                    HttpStatusCode.OK,
                    McpToolCatalogService.buildCatalog(toolRegistry, resourceRegistry),
                )
            }

            route("/api-keys") {
                get {
                    val principal = call.principal<JWTPrincipal>()!!
                    val orgId = principal.payload.getClaim("orgId").asInt()
                    call.respond(
                        HttpStatusCode.OK,
                        McpApiKeysResponse(mcpApiKeyService.listKeys(orgId)),
                    )
                }

                post {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = principal.payload.getClaim("userId").asInt()
                    val orgId = principal.payload.getClaim("orgId").asInt()
                    val request = call.receive<CreateMcpApiKeyRequest>()
                    val validationError = validateToolSelection(
                        request.enabledTools,
                        request.enabledResources,
                        toolRegistry,
                        resourceRegistry,
                    )
                    if (validationError != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(validationError))
                        return@post
                    }

                    try {
                        val response = mcpApiKeyService.createKey(
                            organizationId = orgId,
                            userId = userId,
                            name = request.name,
                            enabledTools = request.enabledTools,
                            enabledResources = request.enabledResources,
                            expiresInDays = request.expiresInDays,
                        )
                        call.respond(HttpStatusCode.Created, response)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                    }
                }

                put("/{id}") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val orgId = principal.payload.getClaim("orgId").asInt()
                    val keyId = call.parameters["id"]?.toIntOrNull()
                    if (keyId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid key ID"))
                        return@put
                    }

                    val request = call.receive<UpdateMcpApiKeyRequest>()
                    val validationError = validateToolSelection(
                        request.enabledTools,
                        request.enabledResources,
                        toolRegistry,
                        resourceRegistry,
                    )
                    if (validationError != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(validationError))
                        return@put
                    }

                    try {
                        val updated = mcpApiKeyService.updateKey(
                            organizationId = orgId,
                            keyId = keyId,
                            name = request.name,
                            enabledTools = request.enabledTools,
                            enabledResources = request.enabledResources,
                            expiresInDays = request.expiresInDays,
                        )
                        if (updated) {
                            call.respond(HttpStatusCode.OK)
                        } else {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Key not found"))
                        }
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                    }
                }

                delete("/{id}") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val orgId = principal.payload.getClaim("orgId").asInt()
                    val keyId = call.parameters["id"]?.toIntOrNull()
                    if (keyId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid key ID"))
                        return@delete
                    }

                    val deleted = mcpApiKeyService.revokeKey(orgId, keyId)
                    if (deleted) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Key not found"))
                    }
                }
            }
        }
    }
}

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
