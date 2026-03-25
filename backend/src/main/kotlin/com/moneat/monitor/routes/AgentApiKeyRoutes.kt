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

import com.moneat.monitor.models.CreateAgentApiKeyRequest
import com.moneat.monitor.services.AgentApiKeyService
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
import io.ktor.server.routing.route
import org.koin.core.context.GlobalContext

fun Route.agentApiKeyRoutes(
    agentApiKeyService: AgentApiKeyService = GlobalContext.get().get(),
) {
    route("/v1") {
        authenticate("auth-jwt") {
            get("/agent-api-keys") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt()
                val keys = agentApiKeyService.listKeys(orgId)
                call.respond(HttpStatusCode.OK, mapOf("keys" to keys))
            }

            post("/agent-api-keys") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = principal.payload.getClaim("orgId").asInt()

                val request = call.receive<CreateAgentApiKeyRequest>()
                val name = request.name.trim()
                if (name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Name is required"))
                    return@post
                }

                val response = agentApiKeyService.createKey(
                    organizationId = orgId,
                    name = name,
                    createdBy = userId
                )
                call.respond(HttpStatusCode.Created, response)
            }

            delete("/agent-api-keys/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt()

                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid key ID"))
                    return@delete
                }

                val deleted = agentApiKeyService.deleteKey(organizationId = orgId, keyId = id)
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Key not found"))
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
