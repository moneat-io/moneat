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

import com.moneat.monitor.services.ApmQueryService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

fun Route.apmRoutes() {
    authenticate("auth-jwt") {
        route("/v1/traces") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal?.payload
                    ?.getClaim("orgId")?.asInt()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Invalid token")
                    )
                val service = call.parameters["service"]
                val env = call.parameters["env"]
                val limit = (
                    call.parameters["limit"]
                        ?.toIntOrNull() ?: DEFAULT_LIMIT
                    ).coerceAtMost(MAX_LIMIT)
                val offset = call.parameters["offset"]
                    ?.toIntOrNull() ?: 0

                val result = ApmQueryService.listTraces(
                    orgId,
                    service,
                    env,
                    limit,
                    offset,
                )
                call.respond(result)
            }

            get("/{traceId}") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal?.payload
                    ?.getClaim("orgId")?.asInt()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Invalid token")
                    )
                val traceId = call.parameters["traceId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Missing traceId")
                    )

                val result = ApmQueryService.getTraceDetail(
                    orgId,
                    traceId,
                )
                if (result == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Trace not found")
                    )
                } else {
                    call.respond(result)
                }
            }
        }

        get("/v1/services/map") {
            val principal = call.principal<JWTPrincipal>()
            val orgId = principal?.payload
                ?.getClaim("orgId")?.asInt()
                ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Invalid token")
                )
            val result = ApmQueryService.getServiceMap(orgId)
            call.respond(result)
        }

        get("/v1/apm-errors") {
            val principal = call.principal<JWTPrincipal>()
            val orgId = principal?.payload
                ?.getClaim("orgId")?.asInt()
                ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Invalid token")
                )
            val service = call.parameters["service"]
            val limit = (
                call.parameters["limit"]
                    ?.toIntOrNull() ?: DEFAULT_LIMIT
                ).coerceAtMost(MAX_LIMIT)
            val offset = call.parameters["offset"]
                ?.toIntOrNull() ?: 0

            val result = ApmQueryService.getApmErrors(
                orgId,
                service,
                limit,
                offset,
            )
            call.respond(result)
        }
    }
}
