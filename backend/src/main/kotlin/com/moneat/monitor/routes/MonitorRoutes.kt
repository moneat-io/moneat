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

import com.moneat.auth.requireCurrentOrg
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
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.koin.core.context.GlobalContext

private const val DEFAULT_PROJECT_ID = 0L
private const val DEFAULT_LIMIT = 100

private suspend fun ensureHostAccessible(
    call: ApplicationCall,
    host: HostData?,
    organizationId: Int
): HostData? {
    if (host == null || host.organizationId != organizationId) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
        return null
    }
    return host
}

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
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get

                val allHosts = monitorService.listHosts(orgId)

                val latestMetricsByHost: Map<Int, LatestMetrics?> =
                    if (allHosts.isEmpty()) {
                        emptyMap()
                    } else {
                        monitorService.getLatestMetricsForHosts(allHosts.map { it.id }, orgId)
                    }

                val response =
                    allHosts.map { host ->
                        HostResponse(
                            id = host.id,
                            projectId = DEFAULT_PROJECT_ID,
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
                            latestMetrics = latestMetricsByHost[host.id]
                        )
                    }

                call.respond(HttpStatusCode.OK, response)
            }

            // List all containers across all systems
            get("/containers") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get

                val containers = monitorService.getLatestContainersForOrganizations(listOf(orgId))
                call.respond(HttpStatusCode.OK, AllContainersResponse(containers = containers))
            }

            // Get host details
            get("/hosts/{id}") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get
                val hostIdStr = call.parameters["id"]

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@get
                    }

                val host =
                    ensureHostAccessible(call, monitorService.getHostById(hostId), orgId)
                        ?: return@get

                call.respond(
                    HttpStatusCode.OK,
                    HostResponse(
                        id = host.id,
                        projectId = DEFAULT_PROJECT_ID,
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
                        latestMetrics = monitorService.getLatestMetrics(host.id)
                    )
                )
            }

            // Delete host
            delete("/hosts/{id}") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@delete
                val hostIdStr = call.parameters["id"]

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@delete
                    }

                val host =
                    ensureHostAccessible(call, monitorService.getHostById(hostId), orgId)
                        ?: return@delete

                val deleted = monitorService.deleteHost(hostId, host.organizationId)
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
                    return@delete
                }

                call.respond(HttpStatusCode.NoContent)
            }

            // Get historical metrics with downsampling
            get("/hosts/{id}/metrics") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get
                val hostIdStr = call.parameters["id"]

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@get
                    }

                val host =
                    ensureHostAccessible(call, monitorService.getHostById(hostId), orgId)
                        ?: return@get

                val fromParam = call.request.queryParameters["from"]?.toLongOrNull()
                val toParam = call.request.queryParameters["to"]?.toLongOrNull()
                val intervalParam = call.request.queryParameters["interval"]?.toIntOrNull()

                if (fromParam == null || toParam == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing required parameters: from, to"))
                    return@get
                }

                val response = monitorService.getHistoricalMetrics(hostId, fromParam, toParam, intervalParam)
                call.respond(HttpStatusCode.OK, response)
            }

            // Get latest container stats
            get("/hosts/{id}/containers") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get
                val hostIdStr = call.parameters["id"]

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@get
                    }

                val host =
                    ensureHostAccessible(call, monitorService.getHostById(hostId), orgId)
                        ?: return@get

                val containers = monitorService.getLatestContainers(hostId)
                call.respond(HttpStatusCode.OK, ContainerStatsResponse(containers = containers))
            }

            // Get container historical metrics
            get("/hosts/{id}/containers/{name}/metrics") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get
                val hostIdStr = call.parameters["id"]
                val containerName = call.parameters["name"]

                if (containerName == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing container name"))
                    return@get
                }

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@get
                    }

                val host =
                    ensureHostAccessible(call, monitorService.getHostById(hostId), orgId)
                        ?: return@get

                val fromParam = call.request.queryParameters["from"]?.toLongOrNull()
                val toParam = call.request.queryParameters["to"]?.toLongOrNull()
                val intervalParam = call.request.queryParameters["interval"]?.toIntOrNull()

                if (fromParam == null || toParam == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing required parameters: from, to"))
                    return@get
                }

                val response =
                    monitorService.getContainerHistoricalMetrics(
                        hostId,
                        containerName,
                        fromParam,
                        toParam,
                        intervalParam
                    )
                call.respond(HttpStatusCode.OK, response)
            }

            // Get logs for a host (container logs)
            get("/hosts/{id}/logs") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get
                val hostIdStr = call.parameters["id"]

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@get
                    }

                val host =
                    ensureHostAccessible(call, monitorService.getHostById(hostId), orgId)
                        ?: return@get

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
                        hostId = hostId,
                        containerName = containerName
                    )

                val response = logService.queryLogs(host.organizationId.toLong(), logRequest)
                call.respond(HttpStatusCode.OK, response)
            }

            // List alerts for a host
            get("/hosts/{id}/alerts") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get
                val hostIdStr = call.parameters["id"]

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@get
                    }

                val host =
                    ensureHostAccessible(call, monitorService.getHostById(hostId), orgId)
                        ?: return@get

                val alerts = monitorService.listAlerts(hostId, host.organizationId)
                call.respond(HttpStatusCode.OK, alerts)
            }

            // List scoped alert config for a host
            get("/hosts/{id}/alerts/config") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get
                val hostIdStr = call.parameters["id"]

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@get
                    }

                val host =
                    ensureHostAccessible(call, monitorService.getHostById(hostId), orgId)
                        ?: return@get

                val config = monitorService.getAlertConfig(hostId, host.organizationId)
                call.respond(HttpStatusCode.OK, config)
            }

            // Update active alert scope for a host (global vs host)
            put("/hosts/{id}/alerts/scope") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@put
                val hostIdStr = call.parameters["id"]

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@put
                    }

                val host =
                    ensureHostAccessible(call, monitorService.getHostById(hostId), orgId)
                        ?: return@put

                val request = call.receive<UpdateAlertScopeRequest>()
                val scope = request.scope.lowercase()
                if (scope != MonitorService.ALERT_SCOPE_GLOBAL && scope != MonitorService.ALERT_SCOPE_HOST) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert scope"))
                    return@put
                }

                monitorService.updateAlertScope(hostId, host.organizationId, scope)
                call.respond(HttpStatusCode.NoContent)
            }

            // Create an alert
            post("/hosts/{id}/alerts") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@post
                val hostIdStr = call.parameters["id"]

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@post
                    }

                val host =
                    ensureHostAccessible(call, monitorService.getHostById(hostId), orgId)
                        ?: return@post

                val scope = (call.request.queryParameters["scope"] ?: MonitorService.ALERT_SCOPE_HOST).lowercase()
                if (scope != MonitorService.ALERT_SCOPE_GLOBAL && scope != MonitorService.ALERT_SCOPE_HOST) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert scope"))
                    return@post
                }

                val request = call.receive<CreateAlertRequest>()
                val alert = monitorService.createAlert(hostId, host.organizationId, request, scope)
                call.respond(HttpStatusCode.Created, alert)
            }

            // Update an alert
            put("/hosts/{hostId}/alerts/{alertId}") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@put
                val hostIdStr = call.parameters["hostId"]
                val alertIdStr = call.parameters["alertId"]

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@put
                    }

                val alertId = alertIdStr?.toIntOrNull()
                if (alertId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
                    return@put
                }

                val host =
                    ensureHostAccessible(call, monitorService.getHostById(hostId), orgId)
                        ?: return@put

                val scope = (call.request.queryParameters["scope"] ?: MonitorService.ALERT_SCOPE_HOST).lowercase()
                if (scope != MonitorService.ALERT_SCOPE_GLOBAL && scope != MonitorService.ALERT_SCOPE_HOST) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert scope"))
                    return@put
                }

                val request = call.receive<UpdateAlertRequest>()
                val updated = monitorService.updateAlert(alertId, hostId, host.organizationId, request, scope)
                if (!updated) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
                    return@put
                }

                call.respond(HttpStatusCode.NoContent)
            }

            // Delete an alert
            delete("/hosts/{hostId}/alerts/{alertId}") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@delete
                val hostIdStr = call.parameters["hostId"]
                val alertIdStr = call.parameters["alertId"]

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@delete
                    }

                val alertId = alertIdStr?.toIntOrNull()
                if (alertId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
                    return@delete
                }

                val host =
                    ensureHostAccessible(call, monitorService.getHostById(hostId), orgId)
                        ?: return@delete

                val scope = (call.request.queryParameters["scope"] ?: MonitorService.ALERT_SCOPE_HOST).lowercase()
                if (scope != MonitorService.ALERT_SCOPE_GLOBAL && scope != MonitorService.ALERT_SCOPE_HOST) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert scope"))
                    return@delete
                }

                val deleted = monitorService.deleteAlert(alertId, hostId, host.organizationId, scope)
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
                    return@delete
                }

                call.respond(HttpStatusCode.NoContent)
            }

            // --- Silence Period Routes ---

            get("/silence-periods") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get

                val periods = monitorAlertService.listSilencePeriods(orgId)
                call.respond(HttpStatusCode.OK, periods)
            }

            post("/silence-periods") {
                val context = call.requireCurrentOrg() ?: return@post
                val userId = context.userId
                val orgId = context.orgId

                val request = call.receive<CreateSilencePeriodRequest>()
                if (request.endsAt <= request.startsAt) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("End time must be after start time"))
                    return@post
                }

                val period = monitorAlertService.createSilencePeriod(orgId, userId, request)
                call.respond(HttpStatusCode.Created, period)
            }

            delete("/silence-periods/{id}") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@delete
                val periodId = call.parameters["id"]?.toIntOrNull()

                if (periodId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid silence period ID"))
                    return@delete
                }

                val deleted = monitorAlertService.deleteSilencePeriod(periodId, orgId)
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Silence period not found"))
                    return@delete
                }

                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
