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
import com.moneat.analytics.services.AnalyticsService
import com.moneat.events.services.DashboardService
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import mu.KotlinLogging
import org.koin.core.context.GlobalContext
import java.time.LocalDate
import java.time.format.DateTimeParseException

private val logger = KotlinLogging.logger {}

private const val PERIOD_7D_OFFSET_DAYS = 6L
private const val PERIOD_30D_OFFSET_DAYS = 29L
private const val PERIOD_6MO_MONTHS = 6L
private const val PERIOD_12MO_MONTHS = 12L
private const val FILTER_PARTS_COUNT = 3

/**
 * Authenticated dashboard API routes for product analytics.
 * All endpoints are under /v1/analytics/{projectId}/...
 */
fun Route.analyticsRoutes(
    analyticsService: AnalyticsService = GlobalContext.get().get(),
    dashboardService: DashboardService = GlobalContext.get().get(),
) {
    route("/v1/analytics/{projectId}") {
        authenticate("auth-jwt") {
            get("/overview") {
                val (projectId, _) = extractContext(call, dashboardService) ?: return@get
                val (dateFrom, dateTo) = parseDateRange(call) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid date range"))
                    return@get
                }
                val filters = parseFilters(call)
                val (compFrom, compTo) = parseComparison(call, dateFrom, dateTo)

                val result = analyticsService.getOverview(projectId, dateFrom, dateTo, filters, compFrom, compTo)
                call.respond(result)
            }

            get("/timeseries") {
                val (projectId, _) = extractContext(call, dashboardService) ?: return@get
                val (dateFrom, dateTo) = parseDateRange(call) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid date range"))
                    return@get
                }
                val filters = parseFilters(call)

                val result = analyticsService.getTimeseries(projectId, dateFrom, dateTo, filters)
                call.respond(result)
            }

            get("/pages") {
                val (projectId, _) = extractContext(call, dashboardService) ?: return@get
                val (dateFrom, dateTo) = parseDateRange(call) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid date range"))
                    return@get
                }
                val filters = parseFilters(call)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT

                val result = analyticsService.getPages(projectId, dateFrom, dateTo, filters, limit)
                call.respond(result)
            }

            get("/entry-pages") {
                val (projectId, _) = extractContext(call, dashboardService) ?: return@get
                val (dateFrom, dateTo) = parseDateRange(call) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid date range"))
                    return@get
                }
                val filters = parseFilters(call)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT

                val result = analyticsService.getEntryPages(projectId, dateFrom, dateTo, filters, limit)
                call.respond(result)
            }

            get("/exit-pages") {
                val (projectId, _) = extractContext(call, dashboardService) ?: return@get
                val (dateFrom, dateTo) = parseDateRange(call) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid date range"))
                    return@get
                }
                val filters = parseFilters(call)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT

                val result = analyticsService.getExitPages(projectId, dateFrom, dateTo, filters, limit)
                call.respond(result)
            }

            get("/sources") {
                val (projectId, _) = extractContext(call, dashboardService) ?: return@get
                val (dateFrom, dateTo) = parseDateRange(call) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid date range"))
                    return@get
                }
                val filters = parseFilters(call)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT

                val result = analyticsService.getBreakdown(
                    projectId,
                    dateFrom,
                    dateTo,
                    filters,
                    "referrer_source",
                    limit,
                )
                call.respond(result)
            }

            get("/utm/{param}") {
                val (projectId, _) = extractContext(call, dashboardService) ?: return@get
                val (dateFrom, dateTo) = parseDateRange(call) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid date range"))
                    return@get
                }
                val filters = parseFilters(call)
                val param = call.parameters["param"] ?: "source"
                val dimension = "utm_$param"
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT

                val result = analyticsService.getBreakdown(projectId, dateFrom, dateTo, filters, dimension, limit)
                call.respond(result)
            }

            get("/locations") {
                val (projectId, _) = extractContext(call, dashboardService) ?: return@get
                val (dateFrom, dateTo) = parseDateRange(call) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid date range"))
                    return@get
                }
                val filters = parseFilters(call)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT

                val result = analyticsService.getBreakdown(
                    projectId,
                    dateFrom,
                    dateTo,
                    filters,
                    "country_code",
                    limit,
                )
                call.respond(result)
            }

            get("/devices") {
                val (projectId, _) = extractContext(call, dashboardService) ?: return@get
                val (dateFrom, dateTo) = parseDateRange(call) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid date range"))
                    return@get
                }
                val filters = parseFilters(call)
                val type = call.request.queryParameters["type"] ?: "device_type"
                val dimension = when (type) {
                    "browser" -> "browser"
                    "os" -> "os"
                    else -> "device_type"
                }
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT

                val result = analyticsService.getBreakdown(projectId, dateFrom, dateTo, filters, dimension, limit)
                call.respond(result)
            }

            get("/events") {
                val (projectId, _) = extractContext(call, dashboardService) ?: return@get
                val (dateFrom, dateTo) = parseDateRange(call) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid date range"))
                    return@get
                }
                val filters = parseFilters(call)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT

                val result = analyticsService.getEvents(projectId, dateFrom, dateTo, filters, limit)
                call.respond(result)
            }

            get("/realtime") {
                val (projectId, _) = extractContext(call, dashboardService) ?: return@get
                val result = analyticsService.getRealtime(projectId)
                call.respond(result)
            }

            get("/funnel") {
                val (projectId, _) = extractContext(call, dashboardService) ?: return@get
                val (dateFrom, dateTo) = parseDateRange(call) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid date range"))
                    return@get
                }
                val steps = call.request.queryParameters.getAll("steps") ?: emptyList()
                if (steps.size < 2) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("At least 2 funnel steps required"))
                    return@get
                }

                val result = analyticsService.getFunnel(projectId, dateFrom, dateTo, steps)
                call.respond(result)
            }
        }
    }
}

// --- Helpers ---

private const val DEFAULT_LIMIT = 100

private suspend fun extractContext(
    call: io.ktor.server.application.ApplicationCall,
    dashboardService: DashboardService,
): Pair<Long, Int>? {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal?.payload?.getClaim("userId")?.asInt()
    if (userId == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
        return null
    }
    val projectId = call.parameters["projectId"]?.toLongOrNull()
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

private fun parseDateRange(call: io.ktor.server.application.ApplicationCall): Pair<LocalDate, LocalDate>? {
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
    call: io.ktor.server.application.ApplicationCall,
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

private fun parseFilters(call: io.ktor.server.application.ApplicationCall): List<AnalyticsFilter> {
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
