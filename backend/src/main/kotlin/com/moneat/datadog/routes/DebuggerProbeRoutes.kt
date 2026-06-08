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

import com.moneat.auth.requireCurrentOrg
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.models.CreateDebuggerProbeRequest
import com.moneat.datadog.models.UpdateDebuggerProbeRequest
import com.moneat.datadog.services.DebuggerProbeService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

fun Route.debuggerProbeRoutes() {
    route("/v1/infra/debugger/probes") {
        authenticate("auth-jwt") {
            get {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get
                val probes = DebuggerProbeService.listProbes(listOf(orgId))
                call.respond(HttpStatusCode.OK, mapOf("probes" to probes))
            }

            post {
                val context = call.requireCurrentOrg() ?: return@post

                try {
                    val request = call.receive<CreateDebuggerProbeRequest>()
                    val createdProbe = DebuggerProbeService.createProbe(context.orgId, context.userId, request)
                    call.respond(HttpStatusCode.Created, createdProbe)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            put("/{id}") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@put

                val probeId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (probeId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid probe ID"))
                    return@put
                }

                try {
                    val request = call.receive<UpdateDebuggerProbeRequest>()
                    val updatedProbe = DebuggerProbeService.updateProbe(probeId, listOf(orgId), request)
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
                val orgId = call.requireCurrentOrg()?.orgId ?: return@delete

                val probeId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (probeId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid probe ID"))
                    return@delete
                }

                val deleted = DebuggerProbeService.deleteProbe(probeId, listOf(orgId))
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
