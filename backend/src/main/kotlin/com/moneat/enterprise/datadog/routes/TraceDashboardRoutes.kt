// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.routes

import com.moneat.enterprise.datadog.services.TraceIngestionService
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
                val limit = (call.parameters["limit"]
                    ?.toIntOrNull() ?: DEFAULT_LIMIT)
                    .coerceAtMost(MAX_LIMIT)
                val offset = call.parameters["offset"]
                    ?.toIntOrNull() ?: 0

                val result = TraceIngestionService.listResourceStats(
                    orgId, service, limit, offset
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
                val limit = (call.parameters["limit"]
                    ?.toIntOrNull() ?: DEFAULT_LIMIT)
                    .coerceAtMost(MAX_LIMIT)
                val offset = call.parameters["offset"]
                    ?.toIntOrNull() ?: 0

                val result = TraceIngestionService.listTraces(
                    orgId, service, env, limit, offset
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
                    orgId, traceId
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
