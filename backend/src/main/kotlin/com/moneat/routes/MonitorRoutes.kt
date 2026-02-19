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

package com.moneat.routes

import com.moneat.models.*
import com.moneat.services.BillingQuotaService
import com.moneat.services.LogService
import com.moneat.services.MonitorAlertService
import com.moneat.services.MonitorService
import com.moneat.services.UsageTrackingService
import io.ktor.http.HttpStatusCode
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import com.moneat.utils.BooleanResponse
import io.ktor.server.application.call
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.request.header
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.core.*
import java.util.*

private val logger = KotlinLogging.logger {}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val json = Json { 
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
        Memberships.selectAll().where { Memberships.user_id eq userId }
            .map { it[Memberships.organization_id] }
    }
}

private fun resolveProjectForOrganization(organizationId: Int, requestedProjectId: Long?): Long? {
    return transaction {
        if (requestedProjectId != null) {
            val exists = Projects
                .selectAll()
                .where { (Projects.id eq requestedProjectId) and (Projects.organization_id eq organizationId) }
                .count() > 0
            if (exists) {
                return@transaction requestedProjectId
            }
        }

        Projects.selectAll()
            .where { Projects.organization_id eq organizationId }
            .orderBy(Projects.id to SortOrder.ASC)
            .firstOrNull()
            ?.get(Projects.id)
    }
}

fun Route.monitorRoutes() {
    val monitorService = MonitorService()
    val usageTracking = UsageTrackingService.instance
    val quotaService = BillingQuotaService()
    val logService = LogService()
    
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
                val (systemId, organizationId) = monitorService.validateAgentKey(agentKey) ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid agent key"))
                    return@post
                }
                
                // Parse payload
                val contentEncoding = call.request.header("Content-Encoding")
                val bodyBytes = call.receive<ByteArray>()
                
                val decompressedBytes = if (contentEncoding == "gzip") {
                    java.util.zip.GZIPInputStream(bodyBytes.inputStream()).readBytes()
                } else {
                    bodyBytes
                }
                
                val payload = json.decodeFromString<SystemMetricsPayload>(
                    decompressedBytes.decodeToString()
                )
                
                logger.debug { "Received metrics from system $systemId (org $organizationId)" }
                
                // Track bandwidth usage
                usageTracking.recordUsage(
                    projectId = systemId.mostSignificantBits, // Use system UUID as pseudo-project for usage tracking
                    eventType = "system_metric",
                    byteSize = bodyBytes.size
                )
                
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
                val (systemId, organizationId) = monitorService.validateAgentKey(agentKey) ?: run {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        AgentLogIngestResponse(error = "Invalid agent key")
                    )
                    return@post
                }

                val contentEncoding = call.request.header("Content-Encoding")
                val bodyBytes = call.receive<ByteArray>()

                val decompressedBytes = if (contentEncoding == "gzip") {
                    java.util.zip.GZIPInputStream(bodyBytes.inputStream()).readBytes()
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

                // Agent logs are now scoped to system, not project
                // We still need a projectId for billing/quota tracking, so use the first project in the org
                val projectId = resolveProjectForOrganization(organizationId, payload.projectId) ?: 0L

                if (quotaService.isEnforcementEnabled()) {
                    val billableBytes = logService.estimateBillableBytes(payload.logs, systemId.toString())
                    val reservation = quotaService.reserveUnits(
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

                val queueKey = call.application.environment.config.propertyOrNull("logs.queueKey")?.getString()
                    ?: "moneat:logs:queue"
                val accepted = logService.enqueueAgentLogs(projectId, systemId.toString(), payload.logs, queueKey)
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
                val allSystems = organizationIds.flatMap { orgId ->
                    monitorService.listSystems(orgId)
                }
                
                val response = allSystems.map { system ->
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
                val dockerCommand = """
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
                        system = SystemResponse(
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
                
                val systemId = try {
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
                
                val systemId = try {
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
                
                val systemId = try {
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
                
                val systemId = try {
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
                
                val systemId = try {
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
                
                val response = monitorService.getContainerHistoricalMetrics(
                    systemId, containerName, fromParam, toParam, intervalParam
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
                
                val systemId = try {
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
                
                val logRequest = LogQueryRequest(
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
                
                val systemId = try {
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

                val systemId = try {
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

                val systemId = try {
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
                
                val systemId = try {
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
                
                val systemId = try {
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
                
                val systemId = try {
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
}
