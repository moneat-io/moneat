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

package com.moneat.llm.routes

import com.moneat.events.services.DashboardService
import com.moneat.llm.services.LlmDashboardService
import com.moneat.plugins.getDemoEpochMs
import com.moneat.plugins.isDemoUser
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.koin.core.context.GlobalContext

fun Route.llmRoutes(
    dashboardService: DashboardService = GlobalContext.get().get(),
    llmService: LlmDashboardService = GlobalContext.get().get(),
) {
    authenticate("auth-jwt") {
        rateLimit(RateLimitName("api")) {
            route("/v1/llm") {
                get("/overview") { handleLlmOverview(dashboardService, llmService) }
                get("/generations") { handleLlmGenerations(dashboardService, llmService) }
                get("/generations/{id}") { handleLlmGenerationDetail(dashboardService, llmService) }
                get("/traces/{traceId}") { handleLlmTrace(dashboardService, llmService) }
                get("/models") { handleLlmModels(dashboardService, llmService) }
                get("/costs") { handleLlmCosts(dashboardService, llmService) }
            }
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.requireLlmProjectAccess(
    dashboardService: DashboardService,
): Long? {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val isDemo = call.isDemoUser()
    val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()
    if (projectId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("projectId is required"))
        return null
    }
    if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Access denied"))
        return null
    }
    return projectId
}

private suspend fun io.ktor.server.routing.RoutingContext.handleLlmOverview(
    dashboardService: DashboardService,
    llmService: LlmDashboardService,
) {
    val projectId = requireLlmProjectAccess(dashboardService) ?: return
    val range = call.request.queryParameters["range"] ?: "24h"
    val demoEpochMs = call.getDemoEpochMs()
    call.respond(llmService.getOverview(projectId, range, demoEpochMs))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleLlmGenerations(
    dashboardService: DashboardService,
    llmService: LlmDashboardService,
) {
    val projectId = requireLlmProjectAccess(dashboardService) ?: return
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

private suspend fun io.ktor.server.routing.RoutingContext.handleLlmGenerationDetail(
    dashboardService: DashboardService,
    llmService: LlmDashboardService,
) {
    val projectId = requireLlmProjectAccess(dashboardService) ?: return
    val generationId =
        call.parameters["id"] ?: run {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("generation id is required"))
            return
        }
    val detail = llmService.getGenerationDetail(projectId, generationId)
    if (detail == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Generation not found"))
        return
    }
    call.respond(detail)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleLlmTrace(
    dashboardService: DashboardService,
    llmService: LlmDashboardService,
) {
    val projectId = requireLlmProjectAccess(dashboardService) ?: return
    val traceId =
        call.parameters["traceId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("traceId is required"))
            return
        }
    val trace = llmService.getTrace(projectId, traceId)
    if (trace == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Trace not found"))
        return
    }
    call.respond(trace)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleLlmModels(
    dashboardService: DashboardService,
    llmService: LlmDashboardService,
) {
    val projectId = requireLlmProjectAccess(dashboardService) ?: return
    val range = call.request.queryParameters["range"] ?: "24h"
    val demoEpochMs = call.getDemoEpochMs()
    call.respond(llmService.getModels(projectId, range, demoEpochMs))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleLlmCosts(
    dashboardService: DashboardService,
    llmService: LlmDashboardService,
) {
    val projectId = requireLlmProjectAccess(dashboardService) ?: return
    val range = call.request.queryParameters["range"] ?: "24h"
    val demoEpochMs = call.getDemoEpochMs()
    call.respond(llmService.getCosts(projectId, range, demoEpochMs))
}
