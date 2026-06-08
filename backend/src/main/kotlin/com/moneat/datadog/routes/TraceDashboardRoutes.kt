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
import com.moneat.datadog.services.TraceIngestionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

fun Route.traceDashboardRoutes() {
    authenticate("auth-jwt") {
        route("/v1/traces") {
            // GET /v1/traces/resources - aggregated resource stats (main APM view)
            get("/resources") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get
                val service = call.parameters["service"]
                val limit = (
                    call.parameters["limit"]
                        ?.toIntOrNull() ?: DEFAULT_LIMIT
                    )
                    .coerceAtMost(MAX_LIMIT)
                val offset = call.parameters["offset"]
                    ?.toIntOrNull() ?: 0

                val result = TraceIngestionService.listResourceStats(
                    orgId,
                    service,
                    limit,
                    offset
                )
                call.respond(result)
            }

            // GET /v1/traces - list individual traces
            get {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get
                val service = call.parameters["service"]
                val env = call.parameters["env"]
                val limit = (
                    call.parameters["limit"]
                        ?.toIntOrNull() ?: DEFAULT_LIMIT
                    )
                    .coerceAtMost(MAX_LIMIT)
                val offset = call.parameters["offset"]
                    ?.toIntOrNull() ?: 0

                val result = TraceIngestionService.listTraces(
                    orgId,
                    service,
                    env,
                    limit,
                    offset
                )
                call.respond(result)
            }

            // GET /v1/dd/traces/{traceId} - get trace detail
            get("/{traceId}") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get
                val traceId = call.parameters["traceId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Missing traceId")
                    )
                if (traceId.toULongOrNull() == null) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid traceId")
                    )
                }

                val result = TraceIngestionService.getTraceDetail(
                    orgId,
                    traceId
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

        // GET /v1/services/map - service dependency map
        get("/v1/services/map") {
            val orgId = call.requireCurrentOrg()?.orgId ?: return@get
            val result = TraceIngestionService.getServiceMap(orgId)
            call.respond(result)
        }

        // GET /v1/apm-errors - list APM error groups
        get("/v1/apm-errors") {
            val orgId = call.requireCurrentOrg()?.orgId ?: return@get
            val service = call.parameters["service"]
            val limit = (
                call.parameters["limit"]
                    ?.toIntOrNull() ?: DEFAULT_LIMIT
                ).coerceAtMost(MAX_LIMIT)
            val offset = call.parameters["offset"]
                ?.toIntOrNull() ?: 0

            val result = TraceIngestionService.getApmErrors(
                orgId,
                service,
                limit,
                offset,
            )
            call.respond(result)
        }
    }
}
