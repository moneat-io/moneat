package com.moneat.routes

import com.moneat.models.*
import com.moneat.services.MonitorService
import com.moneat.services.UsageTrackingService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger {}

fun Route.monitorRoutes() {
    val monitorService = MonitorService()
    val usageTracking = UsageTrackingService.instance
    
    route("/api/v1/monitor") {
        
        /**
         * Agent-facing ingestion endpoint.
         * Auth: Bearer token (agent key)
         */
        post("/ingest") {
            try {
                // Extract and validate agent key
                val authHeader = call.request.header("Authorization")
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing or invalid Authorization header"))
                    return@post
                }
                
                val agentKey = authHeader.removePrefix("Bearer ").trim()
                val (systemId, organizationId) = monitorService.validateAgentKey(agentKey) ?: run {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid agent key"))
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
                
                val payload = kotlinx.serialization.json.Json.decodeFromString<SystemMetricsPayload>(
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
         * Dashboard-facing endpoints (JWT auth required).
         */
        authenticate("jwt") {
            
            // List all systems for organization
            get("/systems") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                val systems = monitorService.listSystems(organizationId)
                val response = systems.map { system ->
                    SystemResponse(
                        id = system.id.toString(),
                        name = system.name,
                        host = system.host,
                        status = system.status,
                        last_seen_at = system.lastSeenAt?.toEpochMilliseconds(),
                        agent_version = system.agentVersion,
                        os = system.os,
                        arch = system.arch,
                        created_at = system.createdAt.toEpochMilliseconds(),
                        latest_metrics = null // Will be fetched separately by frontend for performance
                    )
                }
                
                call.respond(HttpStatusCode.OK, response)
            }
            
            // Create a new system
            post("/systems") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                // Check quota
                if (!monitorService.checkSystemQuota(organizationId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "System limit reached for your plan")
                    )
                    return@post
                }
                
                val request = call.receive<CreateSystemRequest>()
                val (system, agentKey) = monitorService.createSystem(organizationId, request.name)
                
                // Generate docker run command
                val apiUrl = System.getenv("API_URL") ?: "https://api.moneat.dev"
                val dockerCommand = """
                    docker run -d --name moneat-agent \
                      --restart unless-stopped \
                      -v /var/run/docker.sock:/var/run/docker.sock:ro \
                      -e MONEAT_KEY="$agentKey" \
                      -e MONEAT_URL="$apiUrl" \
                      ghcr.io/moneat/agent
                """.trimIndent()
                
                call.respond(
                    HttpStatusCode.Created,
                    CreateSystemResponse(
                        system = SystemResponse(
                            id = system.id.toString(),
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
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val systemIdStr = call.parameters["id"]
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                val systemId = try {
                    UUID.fromString(systemIdStr)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid system ID"))
                    return@get
                }
                
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "System not found"))
                    return@get
                }
                
                call.respond(
                    HttpStatusCode.OK,
                    SystemResponse(
                        id = system.id.toString(),
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
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val systemIdStr = call.parameters["id"]
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@delete
                }
                
                val systemId = try {
                    UUID.fromString(systemIdStr)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid system ID"))
                    return@delete
                }
                
                val deleted = monitorService.deleteSystem(systemId, organizationId)
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "System not found"))
                    return@delete
                }
                
                call.respond(HttpStatusCode.NoContent)
            }
            
            // Get historical metrics with downsampling
            get("/systems/{id}/metrics") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val systemIdStr = call.parameters["id"]
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                val systemId = try {
                    UUID.fromString(systemIdStr)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid system ID"))
                    return@get
                }
                
                // Verify ownership
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "System not found"))
                    return@get
                }
                
                val fromParam = call.request.queryParameters["from"]?.toLongOrNull()
                val toParam = call.request.queryParameters["to"]?.toLongOrNull()
                val intervalParam = call.request.queryParameters["interval"]?.toIntOrNull()
                
                if (fromParam == null || toParam == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing required parameters: from, to"))
                    return@get
                }
                
                val response = monitorService.getHistoricalMetrics(systemId, fromParam, toParam, intervalParam)
                call.respond(HttpStatusCode.OK, response)
            }
            
            // Get latest container stats
            get("/systems/{id}/containers") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val systemIdStr = call.parameters["id"]
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                val systemId = try {
                    UUID.fromString(systemIdStr)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid system ID"))
                    return@get
                }
                
                // Verify ownership
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "System not found"))
                    return@get
                }
                
                val containers = monitorService.getLatestContainers(systemId)
                call.respond(HttpStatusCode.OK, ContainerStatsResponse(containers = containers))
            }
            
            // Get container historical metrics
            get("/systems/{id}/containers/{name}/metrics") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val systemIdStr = call.parameters["id"]
                val containerName = call.parameters["name"]
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                if (containerName == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing container name"))
                    return@get
                }
                
                val systemId = try {
                    UUID.fromString(systemIdStr)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid system ID"))
                    return@get
                }
                
                // Verify ownership
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "System not found"))
                    return@get
                }
                
                val fromParam = call.request.queryParameters["from"]?.toLongOrNull()
                val toParam = call.request.queryParameters["to"]?.toLongOrNull()
                val intervalParam = call.request.queryParameters["interval"]?.toIntOrNull()
                
                if (fromParam == null || toParam == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing required parameters: from, to"))
                    return@get
                }
                
                val response = monitorService.getContainerHistoricalMetrics(
                    systemId, containerName, fromParam, toParam, intervalParam
                )
                call.respond(HttpStatusCode.OK, response)
            }
            
            // List alerts for a system
            get("/systems/{id}/alerts") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val systemIdStr = call.parameters["id"]
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                val systemId = try {
                    UUID.fromString(systemIdStr)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid system ID"))
                    return@get
                }
                
                // Verify ownership
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "System not found"))
                    return@get
                }
                
                val alerts = monitorService.listAlerts(systemId)
                call.respond(HttpStatusCode.OK, alerts)
            }
            
            // Create an alert
            post("/systems/{id}/alerts") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val systemIdStr = call.parameters["id"]
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                val systemId = try {
                    UUID.fromString(systemIdStr)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid system ID"))
                    return@post
                }
                
                // Verify ownership
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "System not found"))
                    return@post
                }
                
                val request = call.receive<CreateAlertRequest>()
                val alert = monitorService.createAlert(systemId, organizationId, request)
                call.respond(HttpStatusCode.Created, alert)
            }
            
            // Update an alert
            put("/systems/{systemId}/alerts/{alertId}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val systemIdStr = call.parameters["systemId"]
                val alertIdStr = call.parameters["alertId"]
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@put
                }
                
                val systemId = try {
                    UUID.fromString(systemIdStr)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid system ID"))
                    return@put
                }
                
                val alertId = alertIdStr?.toIntOrNull()
                if (alertId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid alert ID"))
                    return@put
                }
                
                // Verify ownership
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "System not found"))
                    return@put
                }
                
                val request = call.receive<UpdateAlertRequest>()
                val updated = monitorService.updateAlert(alertId, systemId, organizationId, request)
                if (!updated) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Alert not found"))
                    return@put
                }
                
                call.respond(HttpStatusCode.NoContent)
            }
            
            // Delete an alert
            delete("/systems/{systemId}/alerts/{alertId}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val systemIdStr = call.parameters["systemId"]
                val alertIdStr = call.parameters["alertId"]
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@delete
                }
                
                val systemId = try {
                    UUID.fromString(systemIdStr)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid system ID"))
                    return@delete
                }
                
                val alertId = alertIdStr?.toIntOrNull()
                if (alertId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid alert ID"))
                    return@delete
                }
                
                // Verify ownership
                val system = monitorService.getSystemById(systemId)
                if (system == null || system.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "System not found"))
                    return@delete
                }
                
                val deleted = monitorService.deleteAlert(alertId, systemId, organizationId)
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Alert not found"))
                    return@delete
                }
                
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
