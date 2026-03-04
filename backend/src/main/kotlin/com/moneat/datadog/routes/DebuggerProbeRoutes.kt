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

package com.moneat.datadog.routes

import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.models.CreateDebuggerProbeRequest
import com.moneat.datadog.models.UpdateDebuggerProbeRequest
import com.moneat.datadog.services.DebuggerProbeService
import com.moneat.shared.models.Memberships
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

fun Route.debuggerProbeRoutes() {
    route("/v1/infra/debugger/probes") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }

                val organizationIds = getOrgIdsForUser(userId)
                val probes = DebuggerProbeService.listProbes(organizationIds)
                call.respond(HttpStatusCode.OK, mapOf("probes" to probes))
            }

            post {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()

                if (userId == null || organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }

                val organizationIds = getOrgIdsForUser(userId)
                if (organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
                    return@post
                }

                try {
                    val request = call.receive<CreateDebuggerProbeRequest>()
                    val createdProbe = DebuggerProbeService.createProbe(organizationId, userId, request)
                    call.respond(HttpStatusCode.Created, createdProbe)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            put("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@put
                }

                val probeId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (probeId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid probe ID"))
                    return@put
                }

                val organizationIds = getOrgIdsForUser(userId)
                try {
                    val request = call.receive<UpdateDebuggerProbeRequest>()
                    val updatedProbe = DebuggerProbeService.updateProbe(probeId, organizationIds, request)
                    if (updatedProbe == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Probe not found"))
                        return@put
                    }
                    call.respond(HttpStatusCode.OK, updatedProbe)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@delete
                }

                val probeId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (probeId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid probe ID"))
                    return@delete
                }

                val organizationIds = getOrgIdsForUser(userId)
                val deleted = DebuggerProbeService.deleteProbe(probeId, organizationIds)
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Probe not found"))
                    return@delete
                }

                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    route("/dd/debugger/v1") {
        get("/probes") {
            val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return@get
            val service = call.request.queryParameters["service"]
            val environment = call.request.queryParameters["env"]
            val probes = DebuggerProbeService.listAgentProbes(organizationId, service, environment)
            call.respond(HttpStatusCode.OK, mapOf("probes" to probes))
        }
    }
}

private fun getOrgIdsForUser(userId: Int): List<Int> {
    return transaction {
        Memberships
            .selectAll()
            .where { Memberships.user_id eq userId }
            .map { it[Memberships.organization_id] }
    }
}
