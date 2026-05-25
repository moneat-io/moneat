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

import com.moneat.datadog.services.DdApmQueryTimeRange
import com.moneat.datadog.services.DdApmQueryTimeUnit
import com.moneat.datadog.services.TraceIngestionService
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
private const val DEFAULT_APM_TIME_RANGE = "24h"

private val apmTimeRanges = mapOf(
    "1h" to DdApmQueryTimeRange(1, DdApmQueryTimeUnit.HOUR),
    "6h" to DdApmQueryTimeRange(6, DdApmQueryTimeUnit.HOUR),
    "24h" to DdApmQueryTimeRange(24, DdApmQueryTimeUnit.HOUR),
    "7d" to DdApmQueryTimeRange(7, DdApmQueryTimeUnit.DAY),
    "30d" to DdApmQueryTimeRange(30, DdApmQueryTimeUnit.DAY),
    "90d" to DdApmQueryTimeRange(90, DdApmQueryTimeUnit.DAY),
)

fun Route.traceDashboardRoutes() {
    authenticate("auth-jwt") {
        route("/v1/traces") {
            // GET /v1/traces/resources - aggregated resource stats (main APM view)
            get("/resources") {
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
                    )
                    .coerceAtMost(MAX_LIMIT)
                val offset = call.parameters["offset"]
                    ?.toIntOrNull() ?: 0
                val timeRange = call.apmTimeRange()
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid timeRange")
                    )

                val result = TraceIngestionService.listResourceStats(
                    orgId,
                    service,
                    limit,
                    offset,
                    timeRange
                )
                call.respond(result)
            }

            // GET /v1/traces - list individual traces
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
                    )
                    .coerceAtMost(MAX_LIMIT)
                val offset = call.parameters["offset"]
                    ?.toIntOrNull() ?: 0
                val timeRange = call.apmTimeRange()
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid timeRange")
                    )

                val result = TraceIngestionService.listTraces(
                    orgId,
                    service,
                    env,
                    limit,
                    offset,
                    timeRange
                )
                call.respond(result)
            }

            // GET /v1/dd/traces/{traceId} - get trace detail
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
            val principal = call.principal<JWTPrincipal>()
            val orgId = principal?.payload
                ?.getClaim("orgId")?.asInt()
                ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Invalid token")
                )
            val result = TraceIngestionService.getServiceMap(orgId)
            call.respond(result)
        }

        // GET /v1/apm-errors - list APM error groups
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
            val timeRange = call.apmTimeRange()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid timeRange")
                )

            val result = TraceIngestionService.getApmErrors(
                orgId,
                service,
                limit,
                offset,
                timeRange
            )
            call.respond(result)
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.apmTimeRange(): DdApmQueryTimeRange? {
    val rawValue = parameters["timeRange"] ?: parameters["range"] ?: DEFAULT_APM_TIME_RANGE
    return apmTimeRanges[rawValue]
}
