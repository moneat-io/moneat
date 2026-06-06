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

package com.moneat.apm.routes

import com.moneat.apm.services.ApmServiceCatalogService
import com.moneat.apm.services.ApmServiceQuery
import com.moneat.datadog.services.DdApmQueryTimeRange
import com.moneat.datadog.services.DdApmQueryTimeUnit
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

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200
private const val DEFAULT_APM_TIME_RANGE = "24h"
private const val INVALID_TOKEN_ERROR = "Invalid token"
private const val INVALID_TIME_RANGE_ERROR = "Invalid timeRange"
private const val INVALID_SERVICE_ERROR = "Invalid service"
private const val INVALID_RESOURCE_ERROR = "Invalid resource"
private const val INVALID_SERVICE_MAP_FILTER_ERROR = "Invalid service map filter"
private const val MAX_TRACE_SEARCH_LENGTH = 200
private const val MAX_SERVICE_PARAM_LENGTH = 200

private val apmTimeRanges = mapOf(
    "1h" to DdApmQueryTimeRange(1, DdApmQueryTimeUnit.HOUR),
    "6h" to DdApmQueryTimeRange(6, DdApmQueryTimeUnit.HOUR),
    "24h" to DdApmQueryTimeRange(24, DdApmQueryTimeUnit.HOUR),
    "7d" to DdApmQueryTimeRange(7, DdApmQueryTimeUnit.DAY),
    "30d" to DdApmQueryTimeRange(30, DdApmQueryTimeUnit.DAY),
    "90d" to DdApmQueryTimeRange(90, DdApmQueryTimeUnit.DAY),
)

fun Route.apmServiceDashboardRoutes() {
    authenticate("auth-jwt") {
        apmServiceDashboardRouteHandlers()
    }
}

internal fun Route.apmServiceDashboardRouteHandlers() {
    // GET /v1/services - service catalog for the APM services view
    get("/v1/services") {
        val orgId = call.organizationId()
            ?: return@get call.respondUnauthorized()
        val query = call.apmServiceQuery()
            ?: return@get call.respondBadRequest(call.apmServiceQueryError())
        val result = ApmServiceCatalogService.listServices(
            organizationId = orgId,
            query = query,
            parentSpan = call.getSentryTransaction(),
        )
        call.respond(result)
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

    // GET /v1/services/{service} - service detail for the APM service page
    get("/v1/services/{service}") {
        val orgId = call.organizationId()
            ?: return@get call.respondUnauthorized()
        val service = call.requiredServiceMapParam("service")
            ?: return@get call.respondBadRequest(INVALID_SERVICE_ERROR)
        val query = call.apmServiceQuery()
            ?: return@get call.respondBadRequest(call.apmServiceQueryError())
        val result = ApmServiceCatalogService.getServiceDetail(
            organizationId = orgId,
            serviceName = service,
            query = query,
            parentSpan = call.getSentryTransaction(),
        )
        if (result == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Service not found"))
        } else {
            call.respond(result)
        }
    }

    // GET /v1/services/{service}/resources/{resource} - resource detail for a service endpoint/span
    get("/v1/services/{service}/resources/{resource}") {
        val orgId = call.organizationId()
            ?: return@get call.respondUnauthorized()
        val service = call.requiredServiceMapParam("service")
            ?: return@get call.respondBadRequest(INVALID_SERVICE_ERROR)
        val resource = call.requiredServiceMapParam("resource")
            ?: return@get call.respondBadRequest(INVALID_RESOURCE_ERROR)
        val query = call.apmServiceQuery()
            ?: return@get call.respondBadRequest(call.apmServiceQueryError())
        val result = ApmServiceCatalogService.getResourceDetail(
            organizationId = orgId,
            serviceName = service,
            resourceSlug = resource,
            query = query,
            parentSpan = call.getSentryTransaction(),
        )
        if (result == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Resource not found"))
        } else {
            call.respond(result)
        }
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

private fun ApplicationCall.apmServiceQuery(): ApmServiceQuery? {
    val search = traceSearch()
    val env = optionalServiceMapParam("env") ?: return null
    val source = optionalServiceMapParam("source") ?: return null
    return ApmServiceQuery(
        env = env.value,
        source = source.value,
        search = search,
        limit = traceQueryLimit(),
        offset = traceQueryOffset(),
        timeRange = apmTimeRange() ?: return null,
    )
}

private fun ApplicationCall.apmServiceQueryError(): String =
    if (apmTimeRange() == null) INVALID_TIME_RANGE_ERROR else INVALID_SERVICE_MAP_FILTER_ERROR

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

private fun ApplicationCall.traceSearch(): String? =
    parameters["search"]
        ?.trim()
        ?.take(MAX_TRACE_SEARCH_LENGTH)
        ?.takeIf { it.isNotEmpty() }

private fun ApplicationCall.traceQueryLimit(): Int =
    (parameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

private fun ApplicationCall.traceQueryOffset(): Int =
    parameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
