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
import com.moneat.shared.models.Memberships
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
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.GlobalContext

private sealed class CloudSourceScopeResult {
    data class Resolved(val userId: Int, val organizationId: Int) : CloudSourceScopeResult()
    object NoAccess : CloudSourceScopeResult()
    object MissingContext : CloudSourceScopeResult()
}

fun Route.cloudSourceRoutes(
    cloudSourceService: CloudSourceService = GlobalContext.get().get(),
) {
    route("/v1/cloud-sources") {
        authenticate("auth-jwt") {
            get {
                val scope = call.resolveCloudSourceScope() ?: return@get
                val sources = cloudSourceService.listSources(scope.organizationId)
                call.respond(HttpStatusCode.OK, sources)
            }

            get("/setup-preview") {
                val scope = call.resolveCloudSourceScope() ?: return@get
                val provider = call.request.queryParameters["provider"]
                if (provider.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("provider is required"))
                    return@get
                }
                call.respondCloudSourceResult {
                    cloudSourceService.setupPreview(scope.organizationId, provider)
                }
            }

            post {
                val scope = call.resolveCloudSourceScope() ?: return@post
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
                val scope = call.resolveCloudSourceScope() ?: return@post
                val sourceId = call.parameters["id"]?.toIntOrNull()
                if (sourceId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid cloud source id"))
                    return@post
                }
                val response = call.cloudSourceResultOrNull {
                    cloudSourceService.syncSource(scope.organizationId, sourceId)
                } ?: return@post
                call.respond(HttpStatusCode.OK, response)
            }

            delete("/{id}") {
                val scope = call.resolveCloudSourceScope() ?: return@delete
                val sourceId = call.parameters["id"]?.toIntOrNull()
                if (sourceId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid cloud source id"))
                    return@delete
                }
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

private suspend fun ApplicationCall.resolveCloudSourceScope(): CloudSourceScopeResult.Resolved? {
    val principal = principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val organizationIdClaim = principal.payload.getClaim("orgId").asInt()
    return when (val scope = resolveCloudSourceOrganizationId(userId, organizationIdClaim)) {
        is CloudSourceScopeResult.Resolved -> scope
        CloudSourceScopeResult.NoAccess -> {
            respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
            null
        }
        CloudSourceScopeResult.MissingContext -> {
            respond(HttpStatusCode.BadRequest, ErrorResponse("Organization context required"))
            null
        }
    }
}

private fun resolveCloudSourceOrganizationId(
    userId: Int,
    organizationIdClaim: Int?,
): CloudSourceScopeResult {
    val organizationIds = transaction {
        Memberships
            .selectAll()
            .where { Memberships.user_id eq userId }
            .map { it[Memberships.organization_id] }
    }
    if (organizationIds.isEmpty()) return CloudSourceScopeResult.NoAccess
    if (organizationIdClaim != null) {
        return if (organizationIdClaim in organizationIds) {
            CloudSourceScopeResult.Resolved(userId, organizationIdClaim)
        } else {
            CloudSourceScopeResult.NoAccess
        }
    }
    if (organizationIds.size == 1) return CloudSourceScopeResult.Resolved(userId, organizationIds.first())
    return CloudSourceScopeResult.MissingContext
}

private suspend fun ApplicationCall.respondCloudSourceResult(block: () -> Any) {
    val response = cloudSourceResultOrNull(block) ?: return
    respond(HttpStatusCode.OK, response)
}

private suspend fun <T> ApplicationCall.cloudSourceResultOrNull(block: suspend () -> T): T? =
    try {
        block()
    } catch (error: InvalidCloudSourceException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid cloud source"))
        null
    } catch (error: CloudSourceConnectorUnavailableException) {
        respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(error.message ?: "Cloud connector unavailable"))
        null
    }
