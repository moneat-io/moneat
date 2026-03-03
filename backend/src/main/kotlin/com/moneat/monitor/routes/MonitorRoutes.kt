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

import com.moneat.billing.services.BillingQuotaService
import com.moneat.logs.models.AgentLogsRequest
import com.moneat.logs.models.LogQueryRequest
import com.moneat.logs.services.LogService
import com.moneat.monitor.models.AgentLogIngestResponse
import com.moneat.monitor.models.AllContainersResponse
import com.moneat.monitor.models.ContainerStatsResponse
import com.moneat.monitor.models.CreateAlertRequest
import com.moneat.monitor.models.CreateSilencePeriodRequest
import com.moneat.monitor.models.CreateHostRequest
import com.moneat.monitor.models.CreateHostResponse
import com.moneat.monitor.models.HostResponse
import com.moneat.monitor.models.IngestResponse
import com.moneat.monitor.models.LatestMetrics
import com.moneat.monitor.models.SystemMetricsPayload
import com.moneat.monitor.models.UpdateAlertRequest
import com.moneat.monitor.models.UpdateAlertScopeRequest
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Projects
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*

private val logger = KotlinLogging.logger {}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

/**
 * Helper function to get organization IDs for a user from their memberships.
 * Returns the list of organization IDs the user belongs to.
 */
private fun getOrganizationIdsForUser(userId: Int): List<Int> {
    return transaction {
        Memberships
            .selectAll()
            .where { Memberships.user_id eq userId }
            .map { it[Memberships.organization_id] }
            .distinct()
    }
}

private fun resolveProjectForOrganization(
    organizationId: Int,
    requestedProjectId: Long?
): Long? {
    return transaction {
        if (requestedProjectId != null) {
            val exists =
                Projects
                    .selectAll()
                    .where { (Projects.id eq requestedProjectId) and (Projects.organization_id eq organizationId) }
                    .count() > 0
            if (exists) {
                return@transaction requestedProjectId
            }
        }

        Projects
            .selectAll()
            .where { Projects.organization_id eq organizationId }
            .orderBy(Projects.id to SortOrder.ASC)
            .firstOrNull()
            ?.get(Projects.id)
    }
}

fun Route.monitorRoutes(
    monitorService: MonitorService = MonitorService(),
    logService: LogService = LogService(),
    quotaService: BillingQuotaService = BillingQuotaService(),
) {
    val usageTracking = UsageTrackingService.instance

    route("/v1/monitor") {
        /**
         * Agent-facing ingestion endpoint.
         * Auth: Bearer token (agent key)
         */
        post("/ingest") {
            var refundOrgId: Int? = null
            var refundMetricCount = 0
            var refundBytes: Long = 0
            try {
                // Extract and validate agent key
                val authHeader = call.request.header("Authorization")
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid Authorization header"))
                    return@post
                }

                val agentKey = authHeader.removePrefix("Bearer ").trim()
                val (hostId, organizationId) =
                    monitorService.validateAgentKey(agentKey) ?: run {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid agent key"))
                        return@post
                    }

                // Parse payload
                val contentEncoding = call.request.header("Content-Encoding")
                val bodyBytes = call.receive<ByteArray>()

                val decompressedBytes =
                    if (contentEncoding == "gzip") {
                        java.util.zip
                            .GZIPInputStream(bodyBytes.inputStream())
                            .readBytes()
                    } else {
                        bodyBytes
                    }

                val payload =
                    json.decodeFromString<SystemMetricsPayload>(
                        decompressedBytes.decodeToString()
                    )

                logger.debug { "Received metrics from host $hostId (org $organizationId)" }

                val metricCount = monitorService.countMetricsInPayload(payload)
                val billableBytes = decompressedBytes.size.toLong()
                if (quotaService.isEnforcementEnabled() && (metricCount > 0 || billableBytes > 0)) {
                    val reservation =
                        quotaService.reserveUnits(
                            organizationId = organizationId,
                            requestedUnits = metricCount,
                            eventType = "custom_metric",
                            requestedBytes = billableBytes
                        )
                    if (!reservation.allowed) {
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            IngestResponse(
                                success = false,
                                interval_seconds = 60,
                                message = "Custom metrics quota exceeded. Please upgrade your plan."
                            )
                        )
                        return@post
                    }
                    refundOrgId = organizationId
                    refundMetricCount = metricCount
                    refundBytes = billableBytes
                }

                // Ingest metrics and get poll interval
                val intervalSeconds = monitorService.ingestMetrics(hostId, organizationId, payload)

                if (metricCount > 0) {
                    usageTracking.recordOrgUsage(
                        organizationId = organizationId,
                        eventType = "custom_metric",
                        count = metricCount
                    )
                }

                call.respond(
                    HttpStatusCode.OK,
                    IngestResponse(
                        success = true,
                        interval_seconds = intervalSeconds
                    )
                )
            } catch (e: Exception) {
                if (quotaService.isEnforcementEnabled() &&
                    (refundMetricCount > 0 || refundBytes > 0) && refundOrgId != null
                ) {
                    quotaService.refundUnits(
                        organizationId = refundOrgId,
                        units = refundMetricCount,
                        eventType = "custom_metric",
                        requestedBytes = refundBytes
                    )
                }
                logger.error(e) { "Failed to ingest metrics: ${e.message}" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    IngestResponse(
                        success = false,
                        interval_seconds = 60,
                        message = e.message
                    )
                )
            }
        }

        /**
         * Agent-facing log ingestion endpoint.
         * Auth: Bearer token (agent key)
         */
        post("/logs") {
            try {
                val authHeader = call.request.header("Authorization")
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        AgentLogIngestResponse(error = "Missing or invalid Authorization header")
                    )
                    return@post
                }

                val agentKey = authHeader.removePrefix("Bearer ").trim()
                val (hostId, organizationId) =
                    monitorService.validateAgentKey(agentKey) ?: run {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            AgentLogIngestResponse(error = "Invalid agent key")
                        )
                        return@post
                    }

                val contentEncoding = call.request.header("Content-Encoding")
                val bodyBytes = call.receive<ByteArray>()

                val decompressedBytes =
                    if (contentEncoding == "gzip") {
                        java.util.zip
                            .GZIPInputStream(bodyBytes.inputStream())
                            .readBytes()
                    } else {
                        bodyBytes
                    }

                val jsonString = decompressedBytes.decodeToString()
                logger.debug { "Received log payload: ${jsonString.take(500)}" }
                val payload = json.decodeFromString<AgentLogsRequest>(jsonString)
                if (payload.logs.isEmpty()) {
                    call.respond(HttpStatusCode.Accepted, AgentLogIngestResponse(accepted = 0))
                    return@post
                }

                if (quotaService.isEnforcementEnabled()) {
                    val billableBytes =
                        payload.logs.sumOf { ((it.message?.length ?: 0) + (it.body?.length ?: 0)).toLong() }
                    val reservation =
                        quotaService.reserveUnits(
                            organizationId = organizationId,
                            requestedUnits = payload.logs.size,
                            eventType = "log",
                            requestedBytes = billableBytes
                        )
                    if (!reservation.allowed) {
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            AgentLogIngestResponse(
                                error = "Quota exceeded",
                                reason = reservation.reason,
                                usage = reservation.usage
                            )
                        )
                        return@post
                    }
                }

                val queueKey =
                    call.application.environment.config
                        .propertyOrNull("logs.queueKey")
                        ?.getString()
                        ?: "moneat:logs:queue"
                val accepted = logService.enqueueAgentLogs(
                    organizationId.toLong(),
                    hostId,
                    payload.logs,
                    queueKey
                )
                call.respond(
                    HttpStatusCode.Accepted,
                    AgentLogIngestResponse(accepted = accepted, hostId = hostId.toString())
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to ingest agent logs: ${e.message}" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    AgentLogIngestResponse(
                        error = "Invalid log payload",
                        message = e.message ?: "Unknown error"
                    )
                )
            }
        }

        /**
         * Dashboard-facing endpoints (JWT auth required).
         */
        authenticate("auth-jwt") {
            // List all hosts for organization
            get("/hosts") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
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
                        HostResponse(
                            id = host.id,
                            projectId = 0L,
                            name = host.displayName ?: host.hostname,
                            hostname = host.hostname,
                            status = host.status,
                            last_seen_at = host.lastSeenAt?.toEpochMilliseconds(),
                            firstSeenAt = host.firstSeenAt.toEpochMilliseconds(),
                            agent_version = host.agentVersion,
                            os = host.os,
                            arch = host.arch,
                            platform = host.platform,
                            processor = host.processor,
                            cpuCores = host.cpuCores,
                            memoryTotalKb = host.memoryTotalKb,
                            created_at = host.createdAt.toEpochMilliseconds(),
                            latest_metrics = latestMetricsByHost[host.id]
                        )
                    }

                call.respond(HttpStatusCode.OK, response)
            }

            // List all containers across all systems
            get("/containers") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                val containers = monitorService.getLatestContainersForOrganizations(organizationIds)
                call.respond(HttpStatusCode.OK, AllContainersResponse(containers = containers))
            }

            // Create a new host (Moneat Agent)
            post("/hosts") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@post
                }

                val organizationId = organizationIds.first()

                if (!monitorService.checkHostQuota(organizationId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("Host limit reached for your plan")
                    )
                    return@post
                }

                val request = call.receive<CreateHostRequest>()
                val (host, agentKey) = monitorService.createHost(organizationId, request.name)

                val dockerCommand =
                    """
                    docker run -d --name moneat-agent \
                      --restart always \
                      --network host \
                      -v /var/run/docker.sock:/var/run/docker.sock:ro \
                      -e MONEAT_KEY="$agentKey" \
                      adrianelder/moneat-agent:latest
                    """.trimIndent()

                call.respond(
                    HttpStatusCode.Created,
                    CreateHostResponse(
                        host =
                        HostResponse(
                            id = host.id,
                            projectId = 0L,
                            name = host.displayName ?: host.hostname,
                            hostname = host.hostname,
                            status = host.status,
                            last_seen_at = host.lastSeenAt?.toEpochMilliseconds(),
                            firstSeenAt = host.firstSeenAt.toEpochMilliseconds(),
                            agent_version = host.agentVersion,
                            os = host.os,
                            arch = host.arch,
                            platform = host.platform,
                            processor = host.processor,
                            cpuCores = host.cpuCores,
                            memoryTotalKb = host.memoryTotalKb,
                            created_at = host.createdAt.toEpochMilliseconds(),
                            latest_metrics = null
                        ),
                        agent_key = agentKey,
                        docker_command = dockerCommand
                    )
                )
            }

            // Get host details
            get("/hosts/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val hostIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@get
                    }

                val host = monitorService.getHostById(hostId)
                if (host == null || host.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
                    return@get
                }

                call.respond(
                    HttpStatusCode.OK,
                    HostResponse(
                        id = host.id,
                        projectId = 0L,
                        name = host.displayName ?: host.hostname,
                        hostname = host.hostname,
                        status = host.status,
                        last_seen_at = host.lastSeenAt?.toEpochMilliseconds(),
                        firstSeenAt = host.firstSeenAt.toEpochMilliseconds(),
                        agent_version = host.agentVersion,
                        os = host.os,
                        arch = host.arch,
                        platform = host.platform,
                        processor = host.processor,
                        cpuCores = host.cpuCores,
                        memoryTotalKb = host.memoryTotalKb,
                        created_at = host.createdAt.toEpochMilliseconds(),
                        latest_metrics = monitorService.getLatestMetrics(host.id)
                    )
                )
            }

            // Delete host
            delete("/hosts/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val hostIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@delete
                }

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@delete
                    }

                val host = monitorService.getHostById(hostId)
                if (host == null || host.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
                    return@delete
                }

                val deleted = monitorService.deleteHost(hostId, host.organizationId)
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
                    return@delete
                }

                call.respond(HttpStatusCode.NoContent)
            }

            // Get historical metrics with downsampling
            get("/hosts/{id}/metrics") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val hostIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@get
                    }

                val host = monitorService.getHostById(hostId)
                if (host == null || host.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
                    return@get
                }

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
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val hostIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@get
                    }

                val host = monitorService.getHostById(hostId)
                if (host == null || host.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
                    return@get
                }

                val containers = monitorService.getLatestContainers(hostId)
                call.respond(HttpStatusCode.OK, ContainerStatsResponse(containers = containers))
            }

            // Get container historical metrics
            get("/hosts/{id}/containers/{name}/metrics") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val hostIdStr = call.parameters["id"]
                val containerName = call.parameters["name"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                if (containerName == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing container name"))
                    return@get
                }

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@get
                    }

                val host = monitorService.getHostById(hostId)
                if (host == null || host.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
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
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val hostIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@get
                    }

                val host = monitorService.getHostById(hostId)
                if (host == null || host.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
                    return@get
                }

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
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

                val response = logService.queryLogs(0L, logRequest)
                call.respond(HttpStatusCode.OK, response)
            }

            // List alerts for a host
            get("/hosts/{id}/alerts") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val hostIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@get
                    }

                val host = monitorService.getHostById(hostId)
                if (host == null || host.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
                    return@get
                }

                val alerts = monitorService.listAlerts(hostId, host.organizationId)
                call.respond(HttpStatusCode.OK, alerts)
            }

            // List scoped alert config for a host
            get("/hosts/{id}/alerts/config") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val hostIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@get
                    }

                val host = monitorService.getHostById(hostId)
                if (host == null || host.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
                    return@get
                }

                val config = monitorService.getAlertConfig(hostId, host.organizationId)
                call.respond(HttpStatusCode.OK, config)
            }

            // Update active alert scope for a host (global vs host)
            put("/hosts/{id}/alerts/scope") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val hostIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@put
                }

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@put
                    }

                val host = monitorService.getHostById(hostId)
                if (host == null || host.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
                    return@put
                }

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
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val hostIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@post
                }

                val hostId = hostIdStr?.toIntOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid host ID"))
                        return@post
                    }

                val host = monitorService.getHostById(hostId)
                if (host == null || host.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
                    return@post
                }

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
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val hostIdStr = call.parameters["hostId"]
                val alertIdStr = call.parameters["alertId"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@put
                }

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

                val host = monitorService.getHostById(hostId)
                if (host == null || host.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
                    return@put
                }

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
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val hostIdStr = call.parameters["hostId"]
                val alertIdStr = call.parameters["alertId"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@delete
                }

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

                val host = monitorService.getHostById(hostId)
                if (host == null || host.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Host not found"))
                    return@delete
                }

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
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                val alertService = MonitorAlertService()
                val periods = alertService.listSilencePeriods(organizationIds.first())
                call.respond(HttpStatusCode.OK, periods)
            }

            post("/silence-periods") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@post
                }

                val request = call.receive<CreateSilencePeriodRequest>()
                if (request.endsAt <= request.startsAt) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("End time must be after start time"))
                    return@post
                }

                val alertService = MonitorAlertService()
                val period = alertService.createSilencePeriod(organizationIds.first(), userId, request)
                call.respond(HttpStatusCode.Created, period)
            }

            delete("/silence-periods/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val periodId = call.parameters["id"]?.toIntOrNull()

                if (periodId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid silence period ID"))
                    return@delete
                }

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@delete
                }

                val alertService = MonitorAlertService()
                val deleted = alertService.deleteSilencePeriod(periodId, organizationIds.first())
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Silence period not found"))
                    return@delete
                }

                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
