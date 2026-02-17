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

package com.moneat.routes

import com.moneat.plugins.getDemoEpochMs
import com.moneat.plugins.isDemoUser
import com.moneat.services.DashboardService
import com.moneat.services.LlmDashboardService
import com.moneat.utils.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.llmRoutes() {
    val dashboardService = DashboardService()
    val llmService = LlmDashboardService()

    authenticate("auth-jwt") {
        rateLimit(RateLimitName("api")) {
            route("/v1/llm") {
                get("/overview") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()
                    val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()
                    if (projectId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("projectId is required"))
                        return@get
                    }
                    if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Access denied"))
                        return@get
                    }
                    val range = call.request.queryParameters["range"] ?: "24h"
                    val demoEpochMs = call.getDemoEpochMs()
                    call.respond(llmService.getOverview(projectId, range, demoEpochMs))
                }

                get("/generations") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()
                    val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()
                    if (projectId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("projectId is required"))
                        return@get
                    }
                    if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Access denied"))
                        return@get
                    }
                    val range = call.request.queryParameters["range"] ?: "24h"
                    val model = call.request.queryParameters["model"]
                    val provider = call.request.queryParameters["provider"]
                    val type = call.request.queryParameters["type"]
                    val status = call.request.queryParameters["status"]
                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 25
                    val demoEpochMs = call.getDemoEpochMs()
                    call.respond(llmService.getGenerations(projectId, range, model, provider, type, status, page, pageSize, demoEpochMs))
                }

                get("/generations/{id}") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()
                    val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()
                    if (projectId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("projectId is required"))
                        return@get
                    }
                    if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Access denied"))
                        return@get
                    }
                    val generationId = call.parameters["id"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("generation id is required"))
                        return@get
                    }
                    val detail = llmService.getGenerationDetail(projectId, generationId)
                    if (detail == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Generation not found"))
                        return@get
                    }
                    call.respond(detail)
                }

                get("/traces/{traceId}") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()
                    val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()
                    if (projectId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("projectId is required"))
                        return@get
                    }
                    if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Access denied"))
                        return@get
                    }
                    val traceId = call.parameters["traceId"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("traceId is required"))
                        return@get
                    }
                    val trace = llmService.getTrace(projectId, traceId)
                    if (trace == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Trace not found"))
                        return@get
                    }
                    call.respond(trace)
                }

                get("/models") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()
                    val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()
                    if (projectId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("projectId is required"))
                        return@get
                    }
                    if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Access denied"))
                        return@get
                    }
                    val range = call.request.queryParameters["range"] ?: "24h"
                    val demoEpochMs = call.getDemoEpochMs()
                    call.respond(llmService.getModels(projectId, range, demoEpochMs))
                }

                get("/costs") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()
                    val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()
                    if (projectId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("projectId is required"))
                        return@get
                    }
                    if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Access denied"))
                        return@get
                    }
                    val range = call.request.queryParameters["range"] ?: "24h"
                    val demoEpochMs = call.getDemoEpochMs()
                    call.respond(llmService.getCosts(projectId, range, demoEpochMs))
                }
            }
        }
    }
}
