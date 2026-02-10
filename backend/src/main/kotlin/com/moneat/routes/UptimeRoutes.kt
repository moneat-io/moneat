package com.moneat.routes

import com.moneat.models.*
import com.moneat.services.UptimeService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import mu.KotlinLogging
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

/**
 * Helper to get organization IDs for a user.
 */
private fun getOrganizationIdsForUser(userId: Int): List<Int> {
    return transaction {
        Memberships.selectAll().where { Memberships.user_id eq userId }
            .map { it[Memberships.organization_id] }
    }
}

/**
 * Uptime monitoring routes.
 */
fun Route.uptimeRoutes() {
    val uptimeService = UptimeService()
    
    route("/v1/uptime") {
        
        /**
         * Push monitor heartbeat endpoint (no auth required).
         */
        post("/push/{token}") {
            try {
                val token = call.parameters["token"]
                if (token == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing token"))
                    return@post
                }
                
                val monitor = uptimeService.getMonitorByPushToken(token)
                if (monitor == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Invalid push token"))
                    return@post
                }
                
                // Parse optional payload
                val body = try {
                    call.receive<Map<String, Any>>()
                } catch (_: Exception) {
                    emptyMap()
                }
                
                val status = (body["status"] as? String)?.toIntOrNull() ?: 1
                val message = body["msg"] as? String ?: "Push received"
                val ping = (body["ping"] as? Number)?.toFloat() ?: -1f
                
                // Record heartbeat
                val result = CheckResult(
                    status = status,
                    responseTimeMs = -1,
                    statusCode = 0,
                    message = message,
                    pingMs = ping
                )
                
                uptimeService.recordHeartbeat(monitor.id, result)
                uptimeService.updateMonitorStatus(monitor.id, result)
                
                call.respond(HttpStatusCode.OK, mapOf("ok" to true))
            } catch (e: Exception) {
                logger.error(e) { "Push heartbeat error: ${e.message}" }
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal error"))
            }
        }
        
        // All other routes require authentication
        authenticate("auth-jwt") {
            
            /**
             * List all monitors for organization.
             */
            get("/monitors") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                        return@get
                    }
                    
                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization membership"))
                        return@get
                    }
                    
                    val organizationId = orgIds.first()
                    val monitors = uptimeService.listMonitors(organizationId)
                    
                    call.respond(HttpStatusCode.OK, monitors)
                } catch (e: Exception) {
                    logger.error(e) { "List monitors error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to list monitors"))
                }
            }
            
            /**
             * Create a new monitor.
             */
            post("/monitors") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                        return@post
                    }
                    
                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization membership"))
                        return@post
                    }
                    
                    val organizationId = orgIds.first()
                    val request = call.receive<CreateUptimeMonitorRequest>()
                    
                    // Validate required fields based on type
                    val validationError = validateMonitorRequest(request)
                    if (validationError != null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
                        return@post
                    }
                    
                    val monitor = try {
                        uptimeService.createMonitor(organizationId, request)
                    } catch (e: IllegalStateException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                        return@post
                    }
                    
                    call.respond(HttpStatusCode.Created, monitor)
                } catch (e: Exception) {
                    logger.error(e) { "Create monitor error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create monitor"))
                }
            }
            
            /**
             * Get monitor details.
             */
            get("/monitors/{id}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                        return@get
                    }
                    
                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization membership"))
                        return@get
                    }
                    
                    val organizationId = orgIds.first()
                    val monitorId = try {
                        UUID.fromString(call.parameters["id"])
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid monitor ID"))
                        return@get
                    }
                    
                    val monitor = uptimeService.getMonitor(monitorId, organizationId)
                    if (monitor == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Monitor not found"))
                        return@get
                    }
                    
                    call.respond(HttpStatusCode.OK, monitor)
                } catch (e: Exception) {
                    logger.error(e) { "Get monitor error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get monitor"))
                }
            }
            
            /**
             * Update a monitor.
             */
            put("/monitors/{id}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                        return@put
                    }
                    
                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization membership"))
                        return@put
                    }
                    
                    val organizationId = orgIds.first()
                    val monitorId = try {
                        UUID.fromString(call.parameters["id"])
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid monitor ID"))
                        return@put
                    }
                    
                    val request = call.receive<UpdateUptimeMonitorRequest>()
                    val monitor = uptimeService.updateMonitor(monitorId, organizationId, request)
                    
                    if (monitor == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Monitor not found"))
                        return@put
                    }
                    
                    call.respond(HttpStatusCode.OK, monitor)
                } catch (e: Exception) {
                    logger.error(e) { "Update monitor error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to update monitor"))
                }
            }
            
            /**
             * Delete a monitor.
             */
            delete("/monitors/{id}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                        return@delete
                    }
                    
                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization membership"))
                        return@delete
                    }
                    
                    val organizationId = orgIds.first()
                    val monitorId = try {
                        UUID.fromString(call.parameters["id"])
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid monitor ID"))
                        return@delete
                    }
                    
                    val deleted = uptimeService.deleteMonitor(monitorId, organizationId)
                    
                    if (!deleted) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Monitor not found"))
                        return@delete
                    }
                    
                    call.respond(HttpStatusCode.OK, mapOf("ok" to true))
                } catch (e: Exception) {
                    logger.error(e) { "Delete monitor error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to delete monitor"))
                }
            }
            
            /**
             * Pause a monitor.
             */
            post("/monitors/{id}/pause") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                        return@post
                    }
                    
                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization membership"))
                        return@post
                    }
                    
                    val organizationId = orgIds.first()
                    val monitorId = try {
                        UUID.fromString(call.parameters["id"])
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid monitor ID"))
                        return@post
                    }
                    
                    val paused = uptimeService.pauseMonitor(monitorId, organizationId)
                    
                    if (!paused) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Monitor not found"))
                        return@post
                    }
                    
                    call.respond(HttpStatusCode.OK, mapOf("ok" to true))
                } catch (e: Exception) {
                    logger.error(e) { "Pause monitor error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to pause monitor"))
                }
            }
            
            /**
             * Resume a monitor.
             */
            post("/monitors/{id}/resume") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                        return@post
                    }
                    
                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization membership"))
                        return@post
                    }
                    
                    val organizationId = orgIds.first()
                    val monitorId = try {
                        UUID.fromString(call.parameters["id"])
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid monitor ID"))
                        return@post
                    }
                    
                    val resumed = uptimeService.resumeMonitor(monitorId, organizationId)
                    
                    if (!resumed) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Monitor not found"))
                        return@post
                    }
                    
                    call.respond(HttpStatusCode.OK, mapOf("ok" to true))
                } catch (e: Exception) {
                    logger.error(e) { "Resume monitor error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to resume monitor"))
                }
            }
            
            /**
             * Get heartbeats for a monitor.
             */
            get("/monitors/{id}/heartbeats") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                        return@get
                    }
                    
                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization membership"))
                        return@get
                    }
                    
                    val organizationId = orgIds.first()
                    val monitorId = try {
                        UUID.fromString(call.parameters["id"])
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid monitor ID"))
                        return@get
                    }
                    
                    // Verify monitor belongs to org
                    val monitor = uptimeService.getMonitor(monitorId, organizationId)
                    if (monitor == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Monitor not found"))
                        return@get
                    }
                    
                    // Parse time range
                    val fromParam = call.request.queryParameters["from"]?.toLongOrNull()
                    val toParam = call.request.queryParameters["to"]?.toLongOrNull()
                    
                    val now = Clock.System.now()
                    val from = fromParam?.let { Instant.fromEpochMilliseconds(it) } ?: now.minus(24.hours)
                    val to = toParam?.let { Instant.fromEpochMilliseconds(it) } ?: now
                    
                    val heartbeats = uptimeService.getHeartbeats(monitorId, from, to)
                    
                    call.respond(HttpStatusCode.OK, heartbeats)
                } catch (e: Exception) {
                    logger.error(e) { "Get heartbeats error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get heartbeats"))
                }
            }
        }
    }
}

/**
 * Validate monitor creation request.
 */
private fun validateMonitorRequest(request: CreateUptimeMonitorRequest): String? {
    if (request.name.isBlank()) {
        return "Monitor name is required"
    }
    
    when (request.type.lowercase()) {
        "http", "keyword", "json_query" -> {
            if (request.url.isNullOrBlank()) {
                return "URL is required for ${request.type} monitors"
            }
        }
        "tcp" -> {
            if (request.hostname.isNullOrBlank()) {
                return "Hostname is required for TCP monitors"
            }
            if (request.port == null) {
                return "Port is required for TCP monitors"
            }
        }
        "ping" -> {
            if (request.hostname.isNullOrBlank()) {
                return "Hostname is required for ping monitors"
            }
        }
        "dns" -> {
            if (request.hostname.isNullOrBlank()) {
                return "Hostname is required for DNS monitors"
            }
        }
        "websocket" -> {
            if (request.url.isNullOrBlank()) {
                return "URL is required for WebSocket monitors"
            }
        }
        "ssl" -> {
            if (request.hostname.isNullOrBlank()) {
                return "Hostname is required for SSL monitors"
            }
        }
        "database" -> {
            if (request.dbConnectionString.isNullOrBlank()) {
                return "Database connection string is required"
            }
        }
        "docker" -> {
            if (request.dockerContainerName.isNullOrBlank()) {
                return "Container name is required for Docker monitors"
            }
        }
        "push" -> {
            // No additional validation for push monitors
        }
        else -> {
            return "Unknown monitor type: ${request.type}"
        }
    }
    
    if (request.intervalSeconds < 10) {
        return "Check interval must be at least 10 seconds"
    }
    
    if (request.timeoutSeconds < 1) {
        return "Timeout must be at least 1 second"
    }
    
    return null
}
