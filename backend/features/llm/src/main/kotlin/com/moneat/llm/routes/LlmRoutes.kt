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

import com.moneat.auth.currentOrgIdOrNull
import com.moneat.config.EnvConfig
import com.moneat.events.services.DashboardService
import com.moneat.llm.services.LlmDashboardService
import com.moneat.llm.services.LlmGenerationFilters
import com.moneat.llm.services.LlmGenerationsQuery
import com.moneat.llm.services.LlmQueryScope
import com.moneat.plugins.getDemoEpochMs
import com.moneat.plugins.isDemoUser
import com.moneat.shared.models.Memberships
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.koin.core.context.GlobalContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private const val DEFAULT_PAGE_SIZE = 25
private const val ERROR_NO_ORGANIZATION_ACCESS = "No organization access"
private const val ERROR_INVALID_SERVICE_IDS = "Invalid serviceIds"
private const val DEMO_PRIMARY_SERVICE_ID = -1L
private const val DEMO_SECONDARY_SERVICE_ID = -2L
private const val DEMO_TERTIARY_SERVICE_ID = -3L
private val DEMO_SERVICE_IDS =
    listOf(DEMO_PRIMARY_SERVICE_ID, DEMO_SECONDARY_SERVICE_ID, DEMO_TERTIARY_SERVICE_ID)

fun Route.llmRoutes(
    llmService: LlmDashboardService,
    dashboardService: DashboardService = GlobalContext.get().get(),
    projectIdResolver: ProjectIdResolver = ProjectIdResolver(),
) {
    authenticate("auth-jwt") {
        rateLimit(RateLimitName("api")) {
            route("/v1/llm") {
                get("/overview") { handleLlmOverview(dashboardService, llmService, projectIdResolver) }
                get("/generations") { handleLlmGenerations(dashboardService, llmService, projectIdResolver) }
                get("/generations/{id}") { handleLlmGenerationDetail(dashboardService, llmService, projectIdResolver) }
                get("/traces/{traceId}") { handleLlmTrace(dashboardService, llmService, projectIdResolver) }
                get("/models") { handleLlmModels(dashboardService, llmService, projectIdResolver) }
                get("/costs") { handleLlmCosts(dashboardService, llmService, projectIdResolver) }
            }
        }
    }
}

private data class LlmRouteContext(
    val scope: LlmQueryScope
)

private data class RequestedLlmServices(
    val serviceIds: List<Long>,
    val hasExplicitFilters: Boolean
) {
    fun applyOrganizationAccess(organizationServiceIds: List<Long>): List<Long> =
        if (hasExplicitFilters) {
            serviceIds.filter { serviceId -> serviceId in organizationServiceIds }
        } else {
            organizationServiceIds
        }
}

private suspend fun RoutingContext.requireLlmScope(
    dashboardService: DashboardService,
    projectIdResolver: ProjectIdResolver,
): LlmRouteContext? {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal?.payload?.getClaim("userId")?.asInt()
    if (userId == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
        return null
    }

    val organizationId = call.resolveLlmOrganizationId(principal, userId) ?: return null
    val requestedServices = call.requestedLlmServices(
        dashboardService = dashboardService,
        projectIdResolver = projectIdResolver,
        organizationId = organizationId
    ) ?: return null
    val scopedServiceIds = requestedServices.applyOrganizationAccess(
        call.availableLlmServiceIds(dashboardService, organizationId)
    )

    return LlmRouteContext(LlmQueryScope.services(scopedServiceIds))
}

private suspend fun ApplicationCall.resolveLlmOrganizationId(
    principal: JWTPrincipal,
    userId: Int
): Int? {
    if (isDemoUser()) return EnvConfig.Demo.ORG_ID.toInt()

    val organizationId = principal.currentOrgIdOrNull()
    if (organizationId != null && hasOrganizationAccess(userId, organizationId)) {
        return organizationId
    }

    respond(HttpStatusCode.NotFound, ErrorResponse(ERROR_NO_ORGANIZATION_ACCESS))
    return null
}

private suspend fun ApplicationCall.requestedLlmServices(
    dashboardService: DashboardService,
    projectIdResolver: ProjectIdResolver,
    organizationId: Int
): RequestedLlmServices? {
    val serviceIds = resolveServiceIdsQuery(projectIdResolver, organizationId)
    if (serviceIds == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(ERROR_INVALID_SERVICE_IDS))
        return null
    }

    val serviceNames = serviceNamesQuery()
    val resolvedServiceIds =
        serviceNames.mapNotNull { serviceName ->
            dashboardService.resolveServiceId(organizationId, serviceName)
        }

    return RequestedLlmServices(
        serviceIds = normalizeRequestedServiceIds(serviceIds + resolvedServiceIds),
        hasExplicitFilters = serviceIds.isNotEmpty() || serviceNames.isNotEmpty()
    )
}

private fun ApplicationCall.availableLlmServiceIds(
    dashboardService: DashboardService,
    organizationId: Int
): List<Long> =
    if (isDemoUser()) {
        DEMO_SERVICE_IDS
    } else {
        dashboardService.getServiceIdsForOrganization(organizationId)
    }

private suspend fun RoutingContext.handleLlmOverview(
    dashboardService: DashboardService,
    llmService: LlmDashboardService,
    projectIdResolver: ProjectIdResolver,
) {
    val context = requireLlmScope(dashboardService, projectIdResolver) ?: return
    val range = call.request.queryParameters["range"] ?: "24h"
    val demoEpochMs = call.getDemoEpochMs()
    call.respond(llmService.getOverview(context.scope, range, demoEpochMs))
}

private suspend fun RoutingContext.handleLlmGenerations(
    dashboardService: DashboardService,
    llmService: LlmDashboardService,
    projectIdResolver: ProjectIdResolver,
) {
    val context = requireLlmScope(dashboardService, projectIdResolver) ?: return
    val range = call.request.queryParameters["range"] ?: "24h"
    val model = call.request.queryParameters["model"]
    val provider = call.request.queryParameters["provider"]
    val type = call.request.queryParameters["type"]
    val status = call.request.queryParameters["status"]
    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
    val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: DEFAULT_PAGE_SIZE
    val query = LlmGenerationsQuery(
        range = range,
        filters = LlmGenerationFilters(
            model = model,
            provider = provider,
            type = type,
            status = status
        ),
        page = page,
        pageSize = pageSize,
        demoEpochMs = call.getDemoEpochMs()
    )
    call.respond(
        llmService.getGenerations(context.scope, query)
    )
}

private suspend fun RoutingContext.handleLlmGenerationDetail(
    dashboardService: DashboardService,
    llmService: LlmDashboardService,
    projectIdResolver: ProjectIdResolver,
) {
    val context = requireLlmScope(dashboardService, projectIdResolver) ?: return
    val generationId =
        call.parameters["id"] ?: run {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("generation id is required"))
            return
        }
    val detail = llmService.getGenerationDetail(context.scope, generationId)
    if (detail == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Generation not found"))
        return
    }
    call.respond(detail)
}

private suspend fun RoutingContext.handleLlmTrace(
    dashboardService: DashboardService,
    llmService: LlmDashboardService,
    projectIdResolver: ProjectIdResolver,
) {
    val context = requireLlmScope(dashboardService, projectIdResolver) ?: return
    val traceId =
        call.parameters["traceId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("traceId is required"))
            return
        }
    val trace = llmService.getTrace(context.scope, traceId)
    if (trace == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Trace not found"))
        return
    }
    call.respond(trace)
}

private suspend fun RoutingContext.handleLlmModels(
    dashboardService: DashboardService,
    llmService: LlmDashboardService,
    projectIdResolver: ProjectIdResolver,
) {
    val context = requireLlmScope(dashboardService, projectIdResolver) ?: return
    val range = call.request.queryParameters["range"] ?: "24h"
    val demoEpochMs = call.getDemoEpochMs()
    call.respond(llmService.getModels(context.scope, range, demoEpochMs))
}

private suspend fun RoutingContext.handleLlmCosts(
    dashboardService: DashboardService,
    llmService: LlmDashboardService,
    projectIdResolver: ProjectIdResolver,
) {
    val context = requireLlmScope(dashboardService, projectIdResolver) ?: return
    val range = call.request.queryParameters["range"] ?: "24h"
    val demoEpochMs = call.getDemoEpochMs()
    call.respond(llmService.getCosts(context.scope, range, demoEpochMs))
}

private fun normalizeRequestedServiceIds(serviceIds: List<Long>): List<Long> =
    serviceIds
        .flatMap { serviceId ->
            if (serviceId == EnvConfig.Demo.PROJECT_ID) {
                DEMO_SERVICE_IDS
            } else {
                listOf(serviceId)
            }
        }
        .distinct()

private fun ApplicationCall.serviceNamesQuery(): List<String> =
    queryCsvValues("services") + queryCsvValues("service")

private fun ApplicationCall.resolveServiceIdsQuery(
    projectIdResolver: ProjectIdResolver,
    organizationId: Int,
): List<Long>? {
    val rawServiceIds = queryCsvValues("projectId") + queryCsvValues("serviceIds") + queryCsvValues("serviceId")
    if (rawServiceIds.isEmpty()) return emptyList()
    return rawServiceIds.map { rawServiceId ->
        projectIdResolver.resolve(rawServiceId, organizationId) ?: return null
    }.distinct()
}

private fun ApplicationCall.queryCsvValues(name: String): List<String> =
    request.queryParameters.getAll(name)
        ?.flatMap { value -> value.split(",") }
        ?.map { value -> value.trim() }
        ?.filter { value -> value.isNotBlank() }
        ?: emptyList()

private fun hasOrganizationAccess(userId: Int, orgId: Int): Boolean =
    transaction {
        Memberships
            .selectAll()
            .where {
                (Memberships.user_id eq userId) and
                    (Memberships.organization_id eq orgId)
            }
            .firstOrNull() != null
    }
