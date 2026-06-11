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

package com.moneat.monitor.routes

import com.moneat.auth.currentOrgContextOrNull
import com.moneat.logs.models.LogQueryRequest
import com.moneat.logs.services.LogService
import com.moneat.monitor.models.AllContainersResponse
import com.moneat.monitor.models.ContainerStatsResponse
import com.moneat.monitor.models.CreateAlertRequest
import com.moneat.monitor.models.CreateSilencePeriodRequest
import com.moneat.monitor.models.HostData
import com.moneat.monitor.models.HostResponse
import com.moneat.monitor.models.LatestMetrics
import com.moneat.monitor.models.UpdateAlertRequest
import com.moneat.monitor.models.UpdateAlertScopeRequest
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

private const val DEFAULT_LIMIT = 100
private const val NO_ORGANIZATION_ACCESS_MESSAGE = "No organization access"
private const val ALERT_NOT_FOUND_MESSAGE = "Alert not found"

/**
 * Helper function to get organization IDs for a user from their memberships.
 * Returns the list of organization IDs the user belongs to.
 */
private fun getOrganizationIdsForUser(userId: Int, principal: JWTPrincipal?): List<Int> =
    principal
        ?.currentOrgContextOrNull()
        ?.takeIf { it.userId == userId }
        ?.let { listOf(it.orgId) }
        .orEmpty()

private suspend fun ensureHostAccessible(
    call: ApplicationCall,
    host: HostData?,
    organizationIds: List<Int>
): HostData? {
    if (host == null || host.organizationId !in organizationIds) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
        return null
    }
    return host
}

private suspend fun resolveHostFromPath(
    call: ApplicationCall,
    monitorService: MonitorService,
    principal: JWTPrincipal?,
    parameterName: String = "id"
): HostData? {
    val userId = principal!!.payload.getClaim("userId").asInt()
    val organizationIds = getOrganizationIdsForUser(userId, principal)
    if (organizationIds.isEmpty()) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse(NO_ORGANIZATION_ACCESS_MESSAGE))
        return null
    }

    val hostResourceId = call.parameters[parameterName]?.let(::parseMonitorResourceId)
    if (hostResourceId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
        return null
    }

    return ensureHostAccessible(
        call,
        monitorService.getHostByResourceId(hostResourceId, organizationIds),
        organizationIds
    )
}

private suspend fun resolveAlertIdFromPath(
    call: ApplicationCall,
    monitorService: MonitorService,
    host: HostData,
    scope: String
): Int? {
    val alertResourceId = call.parameters["alertId"]?.let(::parseMonitorResourceId)
    if (alertResourceId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
        return null
    }

    val alertId = monitorService.resolveAlertId(alertResourceId, host.id, host.organizationId, scope)
    if (alertId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ALERT_NOT_FOUND_MESSAGE))
        return null
    }
    return alertId
}

private fun hostResponse(host: HostData, latestMetrics: LatestMetrics?): HostResponse =
    HostResponse(
        id = host.resourceId.toString(),
        name = host.displayName ?: host.hostname,
        hostname = host.hostname,
        status = host.status,
        lastSeenAt = host.lastSeenAt?.toEpochMilliseconds(),
        firstSeenAt = host.firstSeenAt.toEpochMilliseconds(),
        agentVersion = host.agentVersion,
        os = host.os,
        arch = host.arch,
        platform = host.platform,
        processor = host.processor,
        cpuCores = host.cpuCores,
        memoryTotalKb = host.memoryTotalKb,
        createdAt = host.createdAt.toEpochMilliseconds(),
        latestMetrics = latestMetrics
    )

private fun parseMonitorResourceId(value: String): Uuid? =
    value.toUuidOrNull()

fun Route.monitorRoutes(
    monitorService: MonitorService = GlobalContext.get().get(),
    logService: LogService = GlobalContext.get().get(),
    monitorAlertService: MonitorAlertService = GlobalContext.get().get(),
) {
    route("/v1/monitor") {
        /**
         * Dashboard-facing endpoints (JWT auth required).
         */
        authenticate("auth-jwt") {
            // List all hosts for organization
            get("/hosts") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val organizationIds = getOrganizationIdsForUser(userId, principal)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(NO_ORGANIZATION_ACCESS_MESSAGE))
                    return@get
                }

                // Get hosts from all user's organizations
                val allHosts =
                    organizationIds.flatMap { orgId ->
                        monitorService.listHosts(orgId)
                    }

                // Batch-fetch latest metrics per org to avoid N+1 ClickHouse queries
                val latestMetricsByHost: Map<Int, LatestMetrics?> =
                    organizationIds.flatMap { orgId ->
                        val orgHosts = allHosts.filter { it.organizationId == orgId }
                        if (orgHosts.isEmpty()) {
                            emptyList()
                        } else {
                            val metrics = monitorService.getLatestMetricsForHosts(orgHosts.map { it.id }, orgId)
                            metrics.entries.map { it.toPair() }
                        }
                    }.toMap()

                val response =
                    allHosts.map { host ->
                        hostResponse(host, latestMetricsByHost[host.id])
                    }

                call.respond(HttpStatusCode.OK, response)
            }

            // List all containers across all systems
            get("/containers") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val organizationIds = getOrganizationIdsForUser(userId, principal)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(NO_ORGANIZATION_ACCESS_MESSAGE))
                    return@get
                }

                val containers = monitorService.getLatestContainersForOrganizations(organizationIds)
                call.respond(HttpStatusCode.OK, AllContainersResponse(containers = containers))
            }

            // Get host details
            get("/hosts/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val host = resolveHostFromPath(call, monitorService, principal) ?: return@get

                call.respond(
                    HttpStatusCode.OK,
                    hostResponse(host, monitorService.getLatestMetrics(host.id))
                )
            }

            // Delete host
            delete("/hosts/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val host = resolveHostFromPath(call, monitorService, principal) ?: return@delete

                val deleted = monitorService.deleteHost(host.id, host.organizationId)
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
                    return@delete
                }

                call.respond(HttpStatusCode.NoContent)
            }

            // Get historical metrics with downsampling
            get("/hosts/{id}/metrics") {
                val principal = call.principal<JWTPrincipal>()
                val host = resolveHostFromPath(call, monitorService, principal) ?: return@get

                val fromParam = call.request.queryParameters["from"]?.toLongOrNull()
                val toParam = call.request.queryParameters["to"]?.toLongOrNull()
                val intervalParam = call.request.queryParameters["interval"]?.toIntOrNull()

                if (fromParam == null || toParam == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing required parameters: from, to"))
                    return@get
                }

                val response = monitorService.getHistoricalMetrics(host.id, fromParam, toParam, intervalParam)
                call.respond(HttpStatusCode.OK, response)
            }

            // Get latest container stats
            get("/hosts/{id}/containers") {
                val principal = call.principal<JWTPrincipal>()
                val host = resolveHostFromPath(call, monitorService, principal) ?: return@get

                val containers = monitorService.getLatestContainers(host.id)
                call.respond(HttpStatusCode.OK, ContainerStatsResponse(containers = containers))
            }

            // Get container historical metrics
            get("/hosts/{id}/containers/{name}/metrics") {
                val principal = call.principal<JWTPrincipal>()
                val host = resolveHostFromPath(call, monitorService, principal) ?: return@get
                val containerName = call.parameters["name"]

                if (containerName == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing container name"))
                    return@get
                }

                val fromParam = call.request.queryParameters["from"]?.toLongOrNull()
                val toParam = call.request.queryParameters["to"]?.toLongOrNull()
                val intervalParam = call.request.queryParameters["interval"]?.toIntOrNull()

                if (fromParam == null || toParam == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing required parameters: from, to"))
                    return@get
                }

                val response =
                    monitorService.getContainerHistoricalMetrics(
                        host.id,
                        containerName,
                        fromParam,
                        toParam,
                        intervalParam
                    )
                call.respond(HttpStatusCode.OK, response)
            }

            // Get logs for a host (container logs)
            get("/hosts/{id}/logs") {
                val principal = call.principal<JWTPrincipal>()
                val host = resolveHostFromPath(call, monitorService, principal) ?: return@get

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
                val cursor = call.request.queryParameters["cursor"]
                val query = call.request.queryParameters["query"]
                val levels = call.request.queryParameters.getAll("levels") ?: emptyList()
                val service = call.request.queryParameters["service"]
                val environment = call.request.queryParameters["environment"]
                val containerName = call.request.queryParameters["container_name"]
                val from = call.request.queryParameters["from"]
                val to = call.request.queryParameters["to"]

                val logRequest =
                    LogQueryRequest(
                        limit = limit,
                        cursor = cursor,
                        query = query,
                        levels = levels,
                        service = service,
                        environment = environment,
                        from = from,
                        to = to,
                        hostId = host.id,
                        containerName = containerName
                    )

                val response = logService.queryLogs(host.organizationId.toLong(), logRequest)
                call.respond(HttpStatusCode.OK, response)
            }

            // List alerts for a host
            get("/hosts/{id}/alerts") {
                val principal = call.principal<JWTPrincipal>()
                val host = resolveHostFromPath(call, monitorService, principal) ?: return@get

                val alerts = monitorService.listAlerts(host.id, host.organizationId)
                call.respond(HttpStatusCode.OK, alerts)
            }

            // List scoped alert config for a host
            get("/hosts/{id}/alerts/config") {
                val principal = call.principal<JWTPrincipal>()
                val host = resolveHostFromPath(call, monitorService, principal) ?: return@get

                val config = monitorService.getAlertConfig(host.id, host.organizationId)
                call.respond(HttpStatusCode.OK, config)
            }

            // Update active alert scope for a host (global vs host)
            put("/hosts/{id}/alerts/scope") {
                val principal = call.principal<JWTPrincipal>()
                val host = resolveHostFromPath(call, monitorService, principal) ?: return@put

                val request = call.receive<UpdateAlertScopeRequest>()
                val scope = request.scope.lowercase()
                if (scope != MonitorService.ALERT_SCOPE_GLOBAL && scope != MonitorService.ALERT_SCOPE_HOST) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert scope"))
                    return@put
                }

                monitorService.updateAlertScope(host.id, host.organizationId, scope)
                call.respond(HttpStatusCode.NoContent)
            }

            // Create an alert
            post("/hosts/{id}/alerts") {
                val principal = call.principal<JWTPrincipal>()
                val host = resolveHostFromPath(call, monitorService, principal) ?: return@post

                val scope = (call.request.queryParameters["scope"] ?: MonitorService.ALERT_SCOPE_HOST).lowercase()
                if (scope != MonitorService.ALERT_SCOPE_GLOBAL && scope != MonitorService.ALERT_SCOPE_HOST) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert scope"))
                    return@post
                }

                val request = call.receive<CreateAlertRequest>()
                val alert = monitorService.createAlert(host.id, host.organizationId, request, scope)
                call.respond(HttpStatusCode.Created, alert)
            }

            // Update an alert
            put("/hosts/{hostId}/alerts/{alertId}") {
                val principal = call.principal<JWTPrincipal>()
                val host = resolveHostFromPath(call, monitorService, principal, "hostId") ?: return@put

                val scope = (call.request.queryParameters["scope"] ?: MonitorService.ALERT_SCOPE_HOST).lowercase()
                if (scope != MonitorService.ALERT_SCOPE_GLOBAL && scope != MonitorService.ALERT_SCOPE_HOST) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert scope"))
                    return@put
                }

                val alertId = resolveAlertIdFromPath(call, monitorService, host, scope) ?: return@put
                val request = call.receive<UpdateAlertRequest>()
                val updated = monitorService.updateAlert(alertId, host.id, host.organizationId, request, scope)
                if (!updated) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ALERT_NOT_FOUND_MESSAGE))
                    return@put
                }

                call.respond(HttpStatusCode.NoContent)
            }

            // Delete an alert
            delete("/hosts/{hostId}/alerts/{alertId}") {
                val principal = call.principal<JWTPrincipal>()
                val host = resolveHostFromPath(call, monitorService, principal, "hostId") ?: return@delete

                val scope = (call.request.queryParameters["scope"] ?: MonitorService.ALERT_SCOPE_HOST).lowercase()
                if (scope != MonitorService.ALERT_SCOPE_GLOBAL && scope != MonitorService.ALERT_SCOPE_HOST) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert scope"))
                    return@delete
                }

                val alertId = resolveAlertIdFromPath(call, monitorService, host, scope) ?: return@delete
                val deleted = monitorService.deleteAlert(alertId, host.id, host.organizationId, scope)
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ALERT_NOT_FOUND_MESSAGE))
                    return@delete
                }

                call.respond(HttpStatusCode.NoContent)
            }

            // --- Silence Period Routes ---

            get("/silence-periods") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val organizationIds = getOrganizationIdsForUser(userId, principal)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(NO_ORGANIZATION_ACCESS_MESSAGE))
                    return@get
                }

                val periods = monitorAlertService.listSilencePeriods(organizationIds.first())
                call.respond(HttpStatusCode.OK, periods)
            }

            post("/silence-periods") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val organizationIds = getOrganizationIdsForUser(userId, principal)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(NO_ORGANIZATION_ACCESS_MESSAGE))
                    return@post
                }

                val request = call.receive<CreateSilencePeriodRequest>()
                if (request.endsAt <= request.startsAt) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("End time must be after start time"))
                    return@post
                }

                val period = monitorAlertService.createSilencePeriod(organizationIds.first(), userId, request)
                call.respond(HttpStatusCode.Created, period)
            }

            delete("/silence-periods/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val periodId = call.parameters["id"]?.let(::parseMonitorResourceId)

                if (periodId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid silence period ID"))
                    return@delete
                }

                val organizationIds = getOrganizationIdsForUser(userId, principal)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(NO_ORGANIZATION_ACCESS_MESSAGE))
                    return@delete
                }

                val deleted = monitorAlertService.deleteSilencePeriodByResourceId(periodId, organizationIds.first())
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Silence period not found"))
                    return@delete
                }

                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
