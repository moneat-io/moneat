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
import com.moneat.monitor.models.AgentApiKeyResponse
import com.moneat.monitor.models.AllContainersResponse
import com.moneat.monitor.models.ContainerStatsResponse
import com.moneat.monitor.models.CreateAgentApiKeyRequest
import com.moneat.monitor.models.CreateAgentApiKeyResponse
import com.moneat.monitor.models.CreateAlertRequest
import com.moneat.monitor.models.CreateSilencePeriodRequest
import com.moneat.monitor.models.CreateSystemRequest
import com.moneat.monitor.models.CreateSystemResponse
import com.moneat.monitor.models.IngestResponse
import com.moneat.monitor.models.SystemMetricsPayload
import com.moneat.monitor.models.SystemResponse
import com.moneat.monitor.models.UpdateAlertRequest
import com.moneat.monitor.models.UpdateAlertScopeRequest
import com.moneat.monitor.services.AgentApiKeyService
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
            try {
                // Extract and validate agent key
                val authHeader = call.request.header("Authorization")
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid Authorization header"))
                    return@post
                }

                val agentKey = authHeader.removePrefix("Bearer ").trim()
                val (systemId, organizationId) =
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

                logger.debug { "Received metrics from system $systemId (org $organizationId)" }

                val metricCount = monitorService.countMetricsInPayload(payload)
                if (quotaService.isEnforcementEnabled() && metricCount > 0) {
                    val reservation =
                        quotaService.reserveUnits(
                            organizationId = organizationId,
                            requestedUnits = metricCount,
                            eventType = "custom_metric"
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
                }

                if (metricCount > 0) {
                    usageTracking.recordOrgUsage(
                        organizationId = organizationId,
                        eventType = "custom_metric",
                        count = metricCount
                    )
                }

                // Ingest metrics and get poll interval
                val intervalSeconds = monitorService.ingestMetrics(systemId, organizationId, payload)

                call.respond(
                    HttpStatusCode.OK,
                    IngestResponse(
                        success = true,
                        interval_seconds = intervalSeconds
                    )
                )
            } catch (e: Exception) {
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
                val (systemId, organizationId) =
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
                    val billableBytes = logService.estimateBillableBytes(payload.logs, systemId.toString())
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
                    systemId.toString(),
                    payload.logs,
                    queueKey
                )
                call.respond(
                    HttpStatusCode.Accepted,
                    AgentLogIngestResponse(accepted = accepted, systemId = systemId.toString())
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
            // List all systems for organization
            get("/systems") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                // Get systems from all user's organizations
                val allSystems =
                    organizationIds.flatMap { orgId ->
                        monitorService.listSystems(orgId)
                    }

                val response =
                    allSystems.map { system ->
                        SystemResponse(
                            id = system.id.toString(),
                            projectId = 0L, // Not used - logs are now scoped by system_id
                            name = system.name,
                            host = system.host,
                            status = system.status,
                            last_seen_at = system.lastSeenAt?.toEpochMilliseconds(),
                            agent_version = system.agentVersion,
                            os = system.os,
                            arch = system.arch,
                            created_at = system.createdAt.toEpochMilliseconds(),
                            latest_metrics = monitorService.getLatestMetrics(system.id)
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

            // Create a new system
            post("/systems") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@post
                }

                // Use the first organization for creating the system
                val organizationId = organizationIds.first()

                // Check quota
                if (!monitorService.checkSystemQuota(organizationId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("System limit reached for your plan")
                    )
                    return@post
                }

                val request = call.receive<CreateSystemRequest>()
                val (system, agentKey) = monitorService.createSystem(organizationId, request.name)

                // Generate docker run command
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
                    CreateSystemResponse(
                        system =
                        SystemResponse(
                            id = system.id.toString(),
                            projectId = 0L, // Not used - logs are now scoped by system_id
                            name = system.name,
                            host = system.host,
                            status = system.status,
                            last_seen_at = system.lastSeenAt?.toEpochMilliseconds(),
                            agent_version = system.agentVersion,
                            os = system.os,
                            arch = system.arch,
                            created_at = system.createdAt.toEpochMilliseconds(),
                            latest_metrics = null
                        ),
                        agent_key = agentKey,
                        docker_command = dockerCommand
                    )
                )
            }

            // Get system details
            get("/systems/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val systemIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                val systemId =
                    try {
                        UUID.fromString(systemIdStr)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid system ID"))
                        return@get
                    }

                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("System not found"))
                    return@get
                }

                call.respond(
                    HttpStatusCode.OK,
                    SystemResponse(
                        id = system.id.toString(),
                        projectId = 0L, // Not used - logs are now scoped by system_id
                        name = system.name,
                        host = system.host,
                        status = system.status,
                        last_seen_at = system.lastSeenAt?.toEpochMilliseconds(),
                        agent_version = system.agentVersion,
                        os = system.os,
                        arch = system.arch,
                        created_at = system.createdAt.toEpochMilliseconds(),
                        latest_metrics = monitorService.getLatestMetrics(system.id)
                    )
                )
            }

            // Delete system
            delete("/systems/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val systemIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@delete
                }

                val systemId =
                    try {
                        UUID.fromString(systemIdStr)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid system ID"))
                        return@delete
                    }

                // Check if system belongs to any of user's organizations
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("System not found"))
                    return@delete
                }

                val deleted = monitorService.deleteSystem(systemId, system.organizationId)
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("System not found"))
                    return@delete
                }

                call.respond(HttpStatusCode.NoContent)
            }

            // Get historical metrics with downsampling
            get("/systems/{id}/metrics") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val systemIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                val systemId =
                    try {
                        UUID.fromString(systemIdStr)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid system ID"))
                        return@get
                    }

                // Verify ownership
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("System not found"))
                    return@get
                }

                val fromParam = call.request.queryParameters["from"]?.toLongOrNull()
                val toParam = call.request.queryParameters["to"]?.toLongOrNull()
                val intervalParam = call.request.queryParameters["interval"]?.toIntOrNull()

                if (fromParam == null || toParam == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing required parameters: from, to"))
                    return@get
                }

                val response = monitorService.getHistoricalMetrics(systemId, fromParam, toParam, intervalParam)
                call.respond(HttpStatusCode.OK, response)
            }

            // Get latest container stats
            get("/systems/{id}/containers") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val systemIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                val systemId =
                    try {
                        UUID.fromString(systemIdStr)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid system ID"))
                        return@get
                    }

                // Verify ownership
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("System not found"))
                    return@get
                }

                val containers = monitorService.getLatestContainers(systemId)
                call.respond(HttpStatusCode.OK, ContainerStatsResponse(containers = containers))
            }

            // Get container historical metrics
            get("/systems/{id}/containers/{name}/metrics") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val systemIdStr = call.parameters["id"]
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

                val systemId =
                    try {
                        UUID.fromString(systemIdStr)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid system ID"))
                        return@get
                    }

                // Verify ownership
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("System not found"))
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
                        systemId,
                        containerName,
                        fromParam,
                        toParam,
                        intervalParam
                    )
                call.respond(HttpStatusCode.OK, response)
            }

            // Get logs for a system (container logs)
            get("/systems/{id}/logs") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val systemIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                val systemId =
                    try {
                        UUID.fromString(systemIdStr)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid system ID"))
                        return@get
                    }

                // Verify ownership
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("System not found"))
                    return@get
                }

                // Parse query parameters
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
                        systemId = systemIdStr,
                        containerName = containerName
                    )

                // Use a dummy projectId (0) since we're querying by systemId
                val response = logService.queryLogs(0L, logRequest)
                call.respond(HttpStatusCode.OK, response)
            }

            // List alerts for a system
            get("/systems/{id}/alerts") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val systemIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                val systemId =
                    try {
                        UUID.fromString(systemIdStr)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid system ID"))
                        return@get
                    }

                // Verify ownership
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("System not found"))
                    return@get
                }

                val alerts = monitorService.listAlerts(systemId)
                call.respond(HttpStatusCode.OK, alerts)
            }

            // List scoped alert config for a system
            get("/systems/{id}/alerts/config") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val systemIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

                val systemId =
                    try {
                        UUID.fromString(systemIdStr)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid system ID"))
                        return@get
                    }

                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("System not found"))
                    return@get
                }

                val config = monitorService.getAlertConfig(systemId, system.organizationId)
                call.respond(HttpStatusCode.OK, config)
            }

            // Update active alert scope for a system (global vs system)
            put("/systems/{id}/alerts/scope") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val systemIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@put
                }

                val systemId =
                    try {
                        UUID.fromString(systemIdStr)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid system ID"))
                        return@put
                    }

                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("System not found"))
                    return@put
                }

                val request = call.receive<UpdateAlertScopeRequest>()
                val scope = request.scope.lowercase()
                if (scope != MonitorService.ALERT_SCOPE_GLOBAL && scope != MonitorService.ALERT_SCOPE_SYSTEM) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert scope"))
                    return@put
                }

                monitorService.updateAlertScope(systemId, system.organizationId, scope)
                call.respond(HttpStatusCode.NoContent)
            }

            // Create an alert
            post("/systems/{id}/alerts") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val systemIdStr = call.parameters["id"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@post
                }

                val systemId =
                    try {
                        UUID.fromString(systemIdStr)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid system ID"))
                        return@post
                    }

                // Verify ownership
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("System not found"))
                    return@post
                }

                val scope = (call.request.queryParameters["scope"] ?: MonitorService.ALERT_SCOPE_SYSTEM).lowercase()
                if (scope != MonitorService.ALERT_SCOPE_GLOBAL && scope != MonitorService.ALERT_SCOPE_SYSTEM) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert scope"))
                    return@post
                }

                val request = call.receive<CreateAlertRequest>()
                val alert = monitorService.createAlert(systemId, system.organizationId, request, scope)
                call.respond(HttpStatusCode.Created, alert)
            }

            // Update an alert
            put("/systems/{systemId}/alerts/{alertId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val systemIdStr = call.parameters["systemId"]
                val alertIdStr = call.parameters["alertId"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@put
                }

                val systemId =
                    try {
                        UUID.fromString(systemIdStr)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid system ID"))
                        return@put
                    }

                val alertId = alertIdStr?.toIntOrNull()
                if (alertId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
                    return@put
                }

                // Verify ownership
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("System not found"))
                    return@put
                }

                val scope = (call.request.queryParameters["scope"] ?: MonitorService.ALERT_SCOPE_SYSTEM).lowercase()
                if (scope != MonitorService.ALERT_SCOPE_GLOBAL && scope != MonitorService.ALERT_SCOPE_SYSTEM) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert scope"))
                    return@put
                }

                val request = call.receive<UpdateAlertRequest>()
                val updated = monitorService.updateAlert(alertId, systemId, system.organizationId, request, scope)
                if (!updated) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
                    return@put
                }

                call.respond(HttpStatusCode.NoContent)
            }

            // Delete an alert
            delete("/systems/{systemId}/alerts/{alertId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val systemIdStr = call.parameters["systemId"]
                val alertIdStr = call.parameters["alertId"]

                val organizationIds = getOrganizationIdsForUser(userId)
                if (organizationIds.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@delete
                }

                val systemId =
                    try {
                        UUID.fromString(systemIdStr)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid system ID"))
                        return@delete
                    }

                val alertId = alertIdStr?.toIntOrNull()
                if (alertId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
                    return@delete
                }

                // Verify ownership
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId !in organizationIds) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("System not found"))
                    return@delete
                }

                val scope = (call.request.queryParameters["scope"] ?: MonitorService.ALERT_SCOPE_SYSTEM).lowercase()
                if (scope != MonitorService.ALERT_SCOPE_GLOBAL && scope != MonitorService.ALERT_SCOPE_SYSTEM) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert scope"))
                    return@delete
                }

                val deleted = monitorService.deleteAlert(alertId, systemId, system.organizationId, scope)
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

    route("/v1") {
        authenticate("auth-jwt") {
            val agentApiKeyService = AgentApiKeyService()

            get("/agent-api-keys") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt()
                val keys = agentApiKeyService.listKeys(orgId)
                call.respond(HttpStatusCode.OK, mapOf("keys" to keys))
            }

            post("/agent-api-keys") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = principal.payload.getClaim("orgId").asInt()

                val request = call.receive<CreateAgentApiKeyRequest>()
                val name = request.name.trim()
                if (name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Name is required"))
                    return@post
                }

                val response = agentApiKeyService.createKey(
                    organizationId = orgId,
                    name = name,
                    createdBy = userId
                )
                call.respond(HttpStatusCode.Created, response)
            }

            delete("/agent-api-keys/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal!!.payload.getClaim("orgId").asInt()

                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid key ID"))
                    return@delete
                }

                val deleted = agentApiKeyService.deleteKey(organizationId = orgId, keyId = id)
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Key not found"))
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
