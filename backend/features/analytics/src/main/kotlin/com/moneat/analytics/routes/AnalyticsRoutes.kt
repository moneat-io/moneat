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

package com.moneat.analytics.routes

import com.moneat.analytics.models.AnalyticsFilter
import com.moneat.analytics.services.AnalyticsQueryScope
import com.moneat.analytics.services.AnalyticsService
import com.moneat.auth.currentOrgIdOrNull
import com.moneat.config.EnvConfig
import com.moneat.events.services.DashboardService
import com.moneat.plugins.isDemoUser
import com.moneat.shared.models.Memberships
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import mu.KotlinLogging
import org.koin.core.context.GlobalContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.format.DateTimeParseException

private val logger = KotlinLogging.logger {}

private const val PERIOD_7D_OFFSET_DAYS = 6L
private const val PERIOD_30D_OFFSET_DAYS = 29L
private const val PERIOD_6MO_MONTHS = 6L
private const val PERIOD_12MO_MONTHS = 12L
private const val FILTER_PARTS_COUNT = 3
private const val MAX_RETENTION_PERIOD_DAYS = 365
private const val INVALID_DATE_RANGE = "Invalid date range"
private val DEFAULT_RETENTION_PERIODS = listOf(1, 7, 14, 30)

/**
 * Authenticated dashboard API routes for product analytics.
 * Project-scoped endpoints are under /v1/analytics/{projectId}/...
 * Organization-scoped endpoints are under /v1/analytics/...
 */
fun Route.analyticsRoutes(
    analyticsService: AnalyticsService = GlobalContext.get().get(),
    dashboardService: DashboardService = GlobalContext.get().get(),
    projectIdResolver: ProjectIdResolver = ProjectIdResolver(),
) {
    route("/v1/analytics") {
        analyticsReadRoutes(analyticsService) { call ->
            extractOrganizationContext(call, dashboardService, projectIdResolver)
        }
    }

    route("/v1/analytics/{projectId}") {
        analyticsReadRoutes(analyticsService) { call ->
            extractProjectContext(call, dashboardService, projectIdResolver)
        }
    }
}

private typealias AnalyticsContextProvider = suspend (ApplicationCall) -> AnalyticsRouteContext?

private enum class PageBreakdown {
    ALL,
    ENTRY,
    EXIT,
}

private fun Route.analyticsReadRoutes(
    analyticsService: AnalyticsService,
    contextProvider: AnalyticsContextProvider,
) {
    authenticate("auth-jwt") {
        get("/overview") {
            call.respondOverview(analyticsService, contextProvider)
        }
        get("/timeseries") {
            call.respondTimeseries(analyticsService, contextProvider)
        }
        get("/pages") {
            call.respondPages(analyticsService, contextProvider, PageBreakdown.ALL)
        }
        get("/entry-pages") {
            call.respondPages(analyticsService, contextProvider, PageBreakdown.ENTRY)
        }
        get("/exit-pages") {
            call.respondPages(analyticsService, contextProvider, PageBreakdown.EXIT)
        }
        get("/sources") {
            call.respondBreakdown(analyticsService, contextProvider, "referrer_source")
        }
        get("/utm/{param}") {
            val param = call.parameters["param"] ?: "source"
            call.respondBreakdown(analyticsService, contextProvider, "utm_$param")
        }
        get("/locations") {
            call.respondBreakdown(analyticsService, contextProvider, "country_code")
        }
        get("/devices") {
            call.respondDevices(analyticsService, contextProvider)
        }
        get("/events") {
            call.respondEvents(analyticsService, contextProvider)
        }
        get("/realtime") {
            call.respondRealtime(analyticsService, contextProvider)
        }
        get("/funnel") {
            call.respondFunnel(analyticsService, contextProvider)
        }
        get("/retention") {
            call.respondRetention(analyticsService, contextProvider)
        }
    }
}

private suspend fun ApplicationCall.respondOverview(
    analyticsService: AnalyticsService,
    contextProvider: AnalyticsContextProvider,
) {
    val context = contextProvider(this) ?: return
    val (dateFrom, dateTo) = parseRequiredDateRange() ?: return
    val filters = parseFilters(this)
    val (compFrom, compTo) = parseComparison(this, dateFrom, dateTo)
    respond(analyticsService.getOverview(context, dateFrom, dateTo, filters, compFrom, compTo))
}

private suspend fun ApplicationCall.respondTimeseries(
    analyticsService: AnalyticsService,
    contextProvider: AnalyticsContextProvider,
) {
    val context = contextProvider(this) ?: return
    val (dateFrom, dateTo) = parseRequiredDateRange() ?: return
    val filters = parseFilters(this)
    respond(analyticsService.getTimeseries(context, dateFrom, dateTo, filters))
}

private suspend fun ApplicationCall.respondPages(
    analyticsService: AnalyticsService,
    contextProvider: AnalyticsContextProvider,
    pageBreakdown: PageBreakdown,
) {
    val context = contextProvider(this) ?: return
    val (dateFrom, dateTo) = parseRequiredDateRange() ?: return
    val filters = parseFilters(this)
    val limit = limitQuery()
    val result = when (pageBreakdown) {
        PageBreakdown.ALL -> analyticsService.getPages(context, dateFrom, dateTo, filters, limit)
        PageBreakdown.ENTRY -> analyticsService.getEntryPages(context, dateFrom, dateTo, filters, limit)
        PageBreakdown.EXIT -> analyticsService.getExitPages(context, dateFrom, dateTo, filters, limit)
    }
    respond(result)
}

private suspend fun ApplicationCall.respondBreakdown(
    analyticsService: AnalyticsService,
    contextProvider: AnalyticsContextProvider,
    dimension: String,
) {
    val context = contextProvider(this) ?: return
    val (dateFrom, dateTo) = parseRequiredDateRange() ?: return
    val filters = parseFilters(this)
    respond(analyticsService.getBreakdown(context, dateFrom, dateTo, filters, dimension, limitQuery()))
}

private suspend fun ApplicationCall.respondDevices(
    analyticsService: AnalyticsService,
    contextProvider: AnalyticsContextProvider,
) {
    val dimension = when (request.queryParameters["type"]) {
        "browser" -> "browser"
        "os" -> "os"
        else -> "device_type"
    }
    respondBreakdown(analyticsService, contextProvider, dimension)
}

private suspend fun ApplicationCall.respondEvents(
    analyticsService: AnalyticsService,
    contextProvider: AnalyticsContextProvider,
) {
    val context = contextProvider(this) ?: return
    val (dateFrom, dateTo) = parseRequiredDateRange() ?: return
    val filters = parseFilters(this)
    val groupBy = request.queryParameters["group_by"] ?: "session_id"
    val source = request.queryParameters["source"]
    respond(analyticsService.getEvents(context, dateFrom, dateTo, filters, limitQuery(), groupBy, source))
}

private suspend fun ApplicationCall.respondRealtime(
    analyticsService: AnalyticsService,
    contextProvider: AnalyticsContextProvider,
) {
    val context = contextProvider(this) ?: return
    respond(analyticsService.getRealtime(context))
}

private suspend fun ApplicationCall.respondFunnel(
    analyticsService: AnalyticsService,
    contextProvider: AnalyticsContextProvider,
) {
    val context = contextProvider(this) ?: return
    val (dateFrom, dateTo) = parseRequiredDateRange() ?: return
    val steps = requiredFunnelSteps() ?: return
    val groupBy = request.queryParameters["group_by"] ?: "session_id"
    val source = request.queryParameters["source"]
    respond(analyticsService.getFunnel(context, dateFrom, dateTo, steps, groupBy, source))
}

private suspend fun ApplicationCall.respondRetention(
    analyticsService: AnalyticsService,
    contextProvider: AnalyticsContextProvider,
) {
    val context = contextProvider(this) ?: return
    val (dateFrom, dateTo) = parseRequiredDateRange() ?: return
    val (startEvent, returnEvent) = requiredRetentionEvents() ?: return
    respond(
        analyticsService.getRetention(
            context,
            dateFrom,
            dateTo,
            startEvent,
            returnEvent,
            parseRetentionPeriods(this),
        ),
    )
}

// --- Helpers ---

private const val DEFAULT_LIMIT = 100
private const val ERROR_NO_ORGANIZATION_ACCESS = "No organization access"
private const val ERROR_INVALID_SERVICE_IDS = "Invalid serviceIds"
private val DEMO_SERVICE_IDS = listOf(-1L, -2L, -3L)

private data class AnalyticsRouteContext(
    val scope: AnalyticsQueryScope,
    val projectId: Long? = null,
)

private suspend fun AnalyticsService.getOverview(
    context: AnalyticsRouteContext,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    filters: List<AnalyticsFilter>,
    compFrom: LocalDate?,
    compTo: LocalDate?,
) = context.projectId?.let { projectId ->
    getOverview(projectId, dateFrom, dateTo, filters, compFrom, compTo)
} ?: getOverview(context.scope, dateFrom, dateTo, filters, compFrom, compTo)

private suspend fun AnalyticsService.getTimeseries(
    context: AnalyticsRouteContext,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    filters: List<AnalyticsFilter>,
) = context.projectId?.let { projectId ->
    getTimeseries(projectId, dateFrom, dateTo, filters)
} ?: getTimeseries(context.scope, dateFrom, dateTo, filters)

private suspend fun AnalyticsService.getPages(
    context: AnalyticsRouteContext,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    filters: List<AnalyticsFilter>,
    limit: Int,
) = context.projectId?.let { projectId ->
    getPages(projectId, dateFrom, dateTo, filters, limit)
} ?: getPages(context.scope, dateFrom, dateTo, filters, limit)

private suspend fun AnalyticsService.getEntryPages(
    context: AnalyticsRouteContext,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    filters: List<AnalyticsFilter>,
    limit: Int,
) = context.projectId?.let { projectId ->
    getEntryPages(projectId, dateFrom, dateTo, filters, limit)
} ?: getEntryPages(context.scope, dateFrom, dateTo, filters, limit)

private suspend fun AnalyticsService.getExitPages(
    context: AnalyticsRouteContext,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    filters: List<AnalyticsFilter>,
    limit: Int,
) = context.projectId?.let { projectId ->
    getExitPages(projectId, dateFrom, dateTo, filters, limit)
} ?: getExitPages(context.scope, dateFrom, dateTo, filters, limit)

private suspend fun AnalyticsService.getBreakdown(
    context: AnalyticsRouteContext,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    filters: List<AnalyticsFilter>,
    dimension: String,
    limit: Int,
) = context.projectId?.let { projectId ->
    getBreakdown(projectId, dateFrom, dateTo, filters, dimension, limit)
} ?: getBreakdown(context.scope, dateFrom, dateTo, filters, dimension, limit)

private fun AnalyticsService.getRealtime(context: AnalyticsRouteContext) =
    context.projectId?.let { projectId ->
        getRealtime(projectId)
    } ?: getRealtime(context.scope)

private suspend fun AnalyticsService.getFunnel(
    context: AnalyticsRouteContext,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    steps: List<String>,
    groupBy: String,
    source: String?,
) = context.projectId?.let { projectId ->
    getFunnel(projectId, dateFrom, dateTo, steps, groupBy, source)
} ?: getFunnel(context.scope, dateFrom, dateTo, steps, groupBy, source)

private suspend fun AnalyticsService.getRetention(
    context: AnalyticsRouteContext,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    startEvent: String,
    returnEvent: String,
    periods: List<Int>,
) = context.projectId?.let { projectId ->
    getRetention(projectId, dateFrom, dateTo, startEvent, returnEvent, periods)
} ?: getRetention(context.scope, dateFrom, dateTo, startEvent, returnEvent, periods)

private suspend fun AnalyticsService.getEvents(
    context: AnalyticsRouteContext,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    filters: List<AnalyticsFilter>,
    limit: Int,
    groupBy: String,
    source: String?,
) = context.projectId?.let { projectId ->
    getEvents(projectId, dateFrom, dateTo, filters, limit, groupBy, source)
} ?: getEvents(context.scope, dateFrom, dateTo, filters, limit, groupBy, source)

private suspend fun extractOrganizationContext(
    call: ApplicationCall,
    dashboardService: DashboardService,
    projectIdResolver: ProjectIdResolver,
): AnalyticsRouteContext? {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal?.payload?.getClaim("userId")?.asInt()
    if (userId == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
        return null
    }

    val organizationId = if (call.isDemoUser()) {
        EnvConfig.Demo.ORG_ID.toInt()
    } else {
        val orgId = principal.currentOrgIdOrNull()
        if (orgId == null || !hasOrganizationAccess(userId, orgId)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(ERROR_NO_ORGANIZATION_ACCESS))
            return null
        }
        orgId
    }

    val serviceIds = call.resolveServiceIdsQuery(projectIdResolver, organizationId)
    if (serviceIds == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERROR_INVALID_SERVICE_IDS))
        return null
    }

    val serviceNames = call.serviceNamesQuery()
    val resolvedNameIds = serviceNames.mapNotNull { serviceName ->
        dashboardService.resolveServiceId(organizationId, serviceName)
    }
    val requestedServiceIds = normalizeRequestedServiceIds(serviceIds + resolvedNameIds)
    val organizationServiceIds = if (call.isDemoUser()) {
        DEMO_SERVICE_IDS
    } else {
        dashboardService.getServiceIdsForOrganization(organizationId)
    }

    val scopedServiceIds = if (serviceIds.isNotEmpty() || serviceNames.isNotEmpty()) {
        requestedServiceIds.filter { serviceId -> serviceId in organizationServiceIds }
    } else {
        organizationServiceIds
    }

    return AnalyticsRouteContext(AnalyticsQueryScope.services(scopedServiceIds))
}

private suspend fun extractProjectContext(
    call: ApplicationCall,
    dashboardService: DashboardService,
    projectIdResolver: ProjectIdResolver,
): AnalyticsRouteContext? {
    val (projectId, _) = extractContext(call, dashboardService, projectIdResolver) ?: return null
    return AnalyticsRouteContext(AnalyticsQueryScope.service(projectId), projectId)
}

private suspend fun extractContext(
    call: ApplicationCall,
    dashboardService: DashboardService,
    projectIdResolver: ProjectIdResolver,
): Pair<Long, Int>? {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal?.payload?.getClaim("userId")?.asInt()
    if (userId == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
        return null
    }
    val projectId = call.parameters["projectId"]?.let(projectIdResolver::resolve)
    if (projectId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid project ID"))
        return null
    }
    if (!dashboardService.hasProjectAccess(userId, projectId)) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Forbidden"))
        return null
    }
    return projectId to userId
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
    val rawServiceIds = queryCsvValues("serviceIds") + queryCsvValues("serviceId")
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

private suspend fun ApplicationCall.parseRequiredDateRange(): Pair<LocalDate, LocalDate>? =
    parseDateRange(this) ?: run {
        respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_DATE_RANGE))
        null
    }

private fun ApplicationCall.limitQuery(): Int =
    request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT

private suspend fun ApplicationCall.requiredFunnelSteps(): List<String>? {
    val steps = request.queryParameters.getAll("steps")
        ?: request.queryParameters.getAll("steps[]")
        ?: emptyList()

    if (steps.size < 2) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("At least 2 funnel steps required"))
        return null
    }

    return steps
}

private suspend fun ApplicationCall.requiredRetentionEvents(): Pair<String, String>? {
    val startEvent = request.queryParameters["start_event"]
    val returnEvent = request.queryParameters["return_event"]
    if (startEvent.isNullOrBlank() || returnEvent.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("start_event and return_event are required"))
        return null
    }

    return startEvent to returnEvent
}

private fun parseDateRange(call: ApplicationCall): Pair<LocalDate, LocalDate>? {
    val period = call.request.queryParameters["period"] ?: "30d"
    val now = LocalDate.now()

    return when (period) {
        "today" -> now to now
        "7d" -> now.minusDays(PERIOD_7D_OFFSET_DAYS) to now
        "30d" -> now.minusDays(PERIOD_30D_OFFSET_DAYS) to now
        "month" -> now.withDayOfMonth(1) to now
        "6mo" -> now.minusMonths(PERIOD_6MO_MONTHS) to now
        "12mo" -> now.minusMonths(PERIOD_12MO_MONTHS) to now
        "custom" -> {
            val from = call.request.queryParameters["from"] ?: call.request.queryParameters["date_from"]
            val to = call.request.queryParameters["to"] ?: call.request.queryParameters["date_to"]
            if (from == null || to == null) return null
            try {
                LocalDate.parse(from) to LocalDate.parse(to)
            } catch (_: DateTimeParseException) {
                null
            }
        }
        else -> now.minusDays(PERIOD_30D_OFFSET_DAYS) to now
    }
}

private fun parseComparison(
    call: ApplicationCall,
    dateFrom: LocalDate,
    dateTo: LocalDate,
): Pair<LocalDate?, LocalDate?> {
    val comparison = call.request.queryParameters["comparison"]
    if (comparison == null) return null to null

    val days = dateTo.toEpochDay() - dateFrom.toEpochDay() + 1
    return when (comparison) {
        "previous_period" -> {
            val compTo = dateFrom.minusDays(1)
            val compFrom = compTo.minusDays(days - 1)
            compFrom to compTo
        }
        "year_over_year" -> {
            dateFrom.minusYears(1) to dateTo.minusYears(1)
        }
        else -> null to null
    }
}

private fun parseFilters(call: ApplicationCall): List<AnalyticsFilter> {
    val rawFilters = call.request.queryParameters.getAll("filters")
        ?: call.request.queryParameters.getAll("filters[]")
        ?: emptyList()
    return rawFilters.mapNotNull { filterStr ->
        val parts = filterStr.split(":", limit = 3)
        if (parts.size == FILTER_PARTS_COUNT) {
            AnalyticsFilter(parts[0], parts[1], parts[2])
        } else {
            null
        }
    }
}

private fun parseRetentionPeriods(call: ApplicationCall): List<Int> {
    val values = call.request.queryParameters.getAll("periods")
        ?: call.request.queryParameters.getAll("periods[]")
        ?: return DEFAULT_RETENTION_PERIODS

    return values
        .mapNotNull(String::toIntOrNull)
        .filter { it in 1..MAX_RETENTION_PERIOD_DAYS }
        .distinct()
        .sorted()
        .ifEmpty { DEFAULT_RETENTION_PERIODS }
}
