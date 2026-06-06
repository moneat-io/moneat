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
import com.moneat.datadog.services.DdResourceStatsQuery
import com.moneat.datadog.services.DdTraceListQuery
import com.moneat.datadog.services.TraceIngestionService
import com.moneat.plugins.getSentryTransaction
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.util.Locale

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200
private const val DEFAULT_APM_TIME_RANGE = "24h"
private const val INVALID_TOKEN_ERROR = "Invalid token"
private const val INVALID_TIME_RANGE_ERROR = "Invalid timeRange"
private const val INVALID_STATUS_ERROR = "Invalid status"
private const val INVALID_SERVICES_ERROR = "Invalid services"
private const val INVALID_SERVICE_ERROR = "Invalid service"
private const val INVALID_SERVICE_MAP_FILTER_ERROR = "Invalid service map filter"
private const val STATUS_ERROR = "error"
private const val STATUS_OK = "ok"
private const val MAX_TRACE_SEARCH_LENGTH = 200
private const val MAX_SERVICE_FILTERS = 50
private const val MAX_SERVICE_FILTER_LENGTH = 200
private const val MAX_SERVICE_PARAM_LENGTH = 200
private val hexTraceIdPattern = Regex("^[0-9a-fA-F]+$")

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
                val orgId = call.organizationId()
                    ?: return@get call.respondUnauthorized()
                val query = call.apmRouteQuery()
                    ?: return@get call.respondBadRequest(call.apmRouteQueryError())

                val result = TraceIngestionService.listResourceStats(
                    organizationId = orgId,
                    query = query.toResourceStatsQuery(),
                    parentSpan = call.getSentryTransaction(),
                )
                call.respond(result)
            }

            // GET /v1/traces/overview - APM overview metrics for the main traces page
            get("/overview") {
                val orgId = call.organizationId()
                    ?: return@get call.respondUnauthorized()
                val query = call.apmRouteQuery()
                    ?: return@get call.respondBadRequest(call.apmRouteQueryError())

                val result = TraceIngestionService.getApmOverview(
                    organizationId = orgId,
                    query = query.toTraceListQuery(),
                    parentSpan = call.getSentryTransaction(),
                )
                call.respond(result)
            }

            // GET /v1/traces - list individual traces
            get {
                val orgId = call.organizationId()
                    ?: return@get call.respondUnauthorized()
                val query = call.apmRouteQuery()
                    ?: return@get call.respondBadRequest(call.apmRouteQueryError())

                val result = TraceIngestionService.listTraces(
                    organizationId = orgId,
                    query = query.toTraceListQuery(),
                    parentSpan = call.getSentryTransaction(),
                )
                call.respond(result)
            }

            // GET /v1/dd/traces/{traceId} - get trace detail
            get("/{traceId}") {
                val orgId = call.organizationId()
                    ?: return@get call.respondUnauthorized()
                val traceId = call.parameters["traceId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Missing traceId")
                    )
                if (!traceId.isSupportedTraceId()) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid traceId")
                    )
                }

                val result = TraceIngestionService.getTraceDetail(
                    orgId,
                    traceId,
                    call.getSentryTransaction(),
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
            val orgId = call.organizationId()
                ?: return@get call.respondUnauthorized()
            val timeRange = call.apmTimeRange()
                ?: return@get call.respondBadRequest(INVALID_TIME_RANGE_ERROR)
            val scope = call.serviceMapScope()
                ?: return@get call.respondBadRequest(INVALID_SERVICE_MAP_FILTER_ERROR)
            val result = TraceIngestionService.getServiceMap(
                orgId,
                timeRange,
                scope.env,
                scope.source,
                call.getSentryTransaction(),
            )
            call.respond(result)
        }

        // GET /v1/services/{service}/latency - focused-service latency percentiles
        get("/v1/services/{service}/latency") {
            val orgId = call.organizationId()
                ?: return@get call.respondUnauthorized()
            val service = call.requiredServiceMapParam("service")
                ?: return@get call.respondBadRequest(INVALID_SERVICE_ERROR)
            val timeRange = call.apmTimeRange()
                ?: return@get call.respondBadRequest(INVALID_TIME_RANGE_ERROR)
            val scope = call.serviceMapScope()
                ?: return@get call.respondBadRequest(INVALID_SERVICE_MAP_FILTER_ERROR)
            val result = TraceIngestionService.getServiceLatencyPercentiles(
                orgId,
                service,
                timeRange,
                scope.env,
                scope.source,
                call.getSentryTransaction(),
            )
            call.respond(result)
        }

        // GET /v1/apm-errors - list APM error groups
        get("/v1/apm-errors") {
            val orgId = call.organizationId()
                ?: return@get call.respondUnauthorized()
            // Accept a multi-select `services` list (comma-separated); fall back to
            // the legacy single `service` param for backward compatibility.
            val services = call.apmErrorServiceFilters()
                ?: return@get call.respondBadRequest(INVALID_SERVICES_ERROR)
            val limit = (
                call.parameters["limit"]
                    ?.toIntOrNull() ?: DEFAULT_LIMIT
                ).coerceAtMost(MAX_LIMIT)
            val offset = call.parameters["offset"]
                ?.toIntOrNull() ?: 0
            val timeRange = call.apmTimeRange()
                ?: return@get call.respondBadRequest(INVALID_TIME_RANGE_ERROR)

            val result = TraceIngestionService.getApmErrors(
                orgId,
                services,
                limit,
                offset,
                timeRange,
                call.getSentryTransaction(),
            )
            call.respond(result)
        }
    }
}

private fun ApplicationCall.organizationId(): Int? =
    principal<JWTPrincipal>()?.payload
        ?.getClaim("orgId")
        ?.asInt()

private suspend fun ApplicationCall.respondUnauthorized() {
    respond(
        HttpStatusCode.Unauthorized,
        mapOf("error" to INVALID_TOKEN_ERROR)
    )
}

private suspend fun ApplicationCall.respondBadRequest(error: String) {
    respond(
        HttpStatusCode.BadRequest,
        mapOf("error" to error)
    )
}

private data class ApmRouteQuery(
    val service: String?,
    val services: List<String>,
    val env: String?,
    val source: String?,
    val search: String?,
    val status: String?,
    val operation: String?,
    val limit: Int,
    val offset: Int,
    val timeRange: DdApmQueryTimeRange,
) {
    fun toTraceListQuery(): DdTraceListQuery =
        DdTraceListQuery(
            service = service,
            services = services,
            env = env,
            source = source,
            status = status,
            operation = operation,
            search = search,
            limit = limit,
            offset = offset,
            timeRange = timeRange,
        )

    fun toResourceStatsQuery(): DdResourceStatsQuery =
        DdResourceStatsQuery(
            service = service,
            services = services,
            env = env,
            source = source,
            status = status,
            operation = operation,
            search = search,
            limit = limit,
            offset = offset,
            timeRange = timeRange,
        )
}

private fun ApplicationCall.apmRouteQuery(): ApmRouteQuery? =
    ApmRouteQuery(
        service = serviceFilter(),
        services = serviceFilters() ?: return null,
        env = parameters["env"],
        source = parameters["source"],
        search = traceSearch(),
        status = apmStatus() ?: return null,
        operation = parameters["operation"]?.takeIf { it.isNotBlank() },
        limit = traceQueryLimit(),
        offset = traceQueryOffset(),
        timeRange = apmTimeRange() ?: return null,
    )

private fun ApplicationCall.apmRouteQueryError(): String =
    when {
        serviceFilters() == null -> INVALID_SERVICES_ERROR
        apmStatus() == null -> INVALID_STATUS_ERROR
        else -> INVALID_TIME_RANGE_ERROR
    }

private fun ApplicationCall.apmTimeRange(): DdApmQueryTimeRange? {
    val rawValue = parameters["timeRange"] ?: parameters["range"] ?: DEFAULT_APM_TIME_RANGE
    return apmTimeRanges[rawValue]
}

private data class ServiceMapScope(
    val env: String?,
    val source: String?,
)

private data class ServiceMapParam(
    val value: String?,
)

private fun ApplicationCall.serviceMapScope(): ServiceMapScope? {
    val env = optionalServiceMapParam("env") ?: return null
    val source = optionalServiceMapParam("source") ?: return null
    return ServiceMapScope(env.value, source.value)
}

private fun ApplicationCall.optionalServiceMapParam(name: String): ServiceMapParam? {
    val rawValue = parameters[name] ?: return ServiceMapParam(null)
    val value = rawValue.trim()
    if (value.length > MAX_SERVICE_PARAM_LENGTH) return null
    return ServiceMapParam(value.takeIf { it.isNotEmpty() })
}

private fun ApplicationCall.requiredServiceMapParam(name: String): String? =
    parameters[name]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.takeIf { it.length <= MAX_SERVICE_PARAM_LENGTH }

private fun ApplicationCall.apmStatus(): String? {
    val rawValue = parameters["status"] ?: return ""
    val normalized = rawValue.lowercase(Locale.ROOT)
    return when (normalized) {
        "", STATUS_ERROR, STATUS_OK -> normalized
        else -> null
    }
}

private fun ApplicationCall.traceSearch(): String? =
    parameters["search"]
        ?.trim()
        ?.take(MAX_TRACE_SEARCH_LENGTH)
        ?.takeIf { it.isNotEmpty() }

private fun ApplicationCall.traceQueryLimit(): Int =
    (parameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

private fun ApplicationCall.traceQueryOffset(): Int =
    parameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

private fun ApplicationCall.serviceFilter(): String? =
    parameters["service"]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun ApplicationCall.serviceFilters(): List<String>? {
    val parsedServices = parameters["services"]
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
    val rawServices = (parsedServices ?: emptyList()) + listOfNotNull(serviceFilter())
    if (rawServices.size > MAX_SERVICE_FILTERS) return null

    val services = rawServices.distinct()
    if (services.size > MAX_SERVICE_FILTERS) return null
    return services.takeIf {
        it.all { service -> service.length <= MAX_SERVICE_FILTER_LENGTH }
    }
}

private fun ApplicationCall.apmErrorServiceFilters(): List<String>? {
    val parsedServices = parameters["services"]
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
    val rawServices = parsedServices
        ?.takeIf { it.isNotEmpty() }
        ?: listOfNotNull(serviceFilter())
    if (rawServices.size > MAX_SERVICE_FILTERS) return null

    val services = rawServices.distinct()
    if (services.size > MAX_SERVICE_FILTERS) return null
    return services.takeIf {
        it.all { service -> service.length <= MAX_SERVICE_FILTER_LENGTH }
    }
}

private fun String.isSupportedTraceId(): Boolean =
    isNotBlank() && (toULongOrNull() != null || hexTraceIdPattern.matches(this))
