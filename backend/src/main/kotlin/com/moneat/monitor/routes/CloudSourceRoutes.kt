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

import com.moneat.monitor.models.CloudSourceCreateRequest
import com.moneat.monitor.services.CloudSourceConnectorUnavailableException
import com.moneat.monitor.services.CloudSourceService
import com.moneat.monitor.services.InvalidCloudSourceException
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.koin.core.context.GlobalContext

private data class CloudSourceScope(val userId: Int, val organizationId: Int)

fun Route.cloudSourceRoutes(
    cloudSourceService: CloudSourceService = GlobalContext.get().get(),
) {
    route("/v1/cloud-sources") {
        authenticate("auth-jwt") {
            get {
                val scope = call.resolveCloudSourceScope()
                val sources = cloudSourceService.listSources(scope.organizationId)
                call.respond(HttpStatusCode.OK, sources)
            }

            get("/setup-preview") {
                val scope = call.resolveCloudSourceScope()
                val provider = call.request.queryParameters["provider"]
                if (provider.isNullOrBlank()) {
                    throw BadRequestException("provider is required")
                }
                call.respondCloudSourceResult {
                    cloudSourceService.setupPreview(scope.organizationId, provider)
                }
            }

            post {
                val scope = call.resolveCloudSourceScope()
                val request = call.receive<CloudSourceCreateRequest>()
                val response = call.cloudSourceResultOrNull {
                    cloudSourceService.createSource(
                        organizationId = scope.organizationId,
                        userId = scope.userId,
                        request = request
                    )
                } ?: return@post
                call.respond(HttpStatusCode.Created, response)
            }

            post("/{id}/sync") {
                val scope = call.resolveCloudSourceScope()
                val sourceId = call.parameters["id"]?.toIntOrNull()
                    ?: throw BadRequestException("Invalid cloud source id")
                val response = call.cloudSourceResultOrNull {
                    cloudSourceService.syncSource(scope.organizationId, sourceId)
                } ?: return@post
                call.respond(HttpStatusCode.OK, response)
            }

            delete("/{id}") {
                val scope = call.resolveCloudSourceScope()
                val sourceId = call.parameters["id"]?.toIntOrNull()
                    ?: throw BadRequestException("Invalid cloud source id")
                val deleted = cloudSourceService.deleteSource(scope.organizationId, sourceId)
                if (deleted) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Cloud source not found"))
                }
            }
        }
    }
}

private fun ApplicationCall.resolveCloudSourceScope(): CloudSourceScope {
    val payload = principal<JWTPrincipal>()?.payload
        ?: throw BadRequestException("Organization context required")
    val userId = payload.getClaim("userId")?.asInt()
        ?: throw BadRequestException("Organization context required")
    val organizationId = payload.getClaim("orgId")?.asInt()
        ?: throw BadRequestException("Organization context required")
    return CloudSourceScope(userId, organizationId)
}

private suspend fun ApplicationCall.respondCloudSourceResult(block: suspend () -> Any) {
    val response = cloudSourceResultOrNull(block) ?: return
    respond(HttpStatusCode.OK, response)
}

private suspend fun <T> ApplicationCall.cloudSourceResultOrNull(block: suspend () -> T): T? =
    try {
        block()
    } catch (error: InvalidCloudSourceException) {
        throw BadRequestException(error.message ?: "Invalid cloud source")
    } catch (error: CloudSourceConnectorUnavailableException) {
        respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(error.message ?: "Cloud connector unavailable"))
        null
    }
