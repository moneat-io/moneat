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

package com.moneat.uptime.routes

import com.moneat.shared.models.Memberships
import com.moneat.uptime.models.CheckResult
import com.moneat.uptime.models.CreateUptimeMonitorRequest
import com.moneat.uptime.models.UpdateUptimeMonitorRequest
import com.moneat.uptime.services.UptimeService
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.GlobalContext
import java.util.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Helper to get organization IDs for a user.
 */
private fun getOrganizationIdsForUser(userId: Int): List<Int> {
    return transaction {
        Memberships
            .selectAll()
            .where { Memberships.user_id eq userId }
            .map { it[Memberships.organization_id] }
    }
}

/**
 * Uptime monitoring routes.
 */
fun Route.uptimeRoutes(
    uptimeService: UptimeService = GlobalContext.get().get(),
) {
    route("/v1/uptime") {
        /**
         * Push monitor heartbeat endpoint (no auth required).
         */
        post("/push/{token}") {
            try {
                val token = call.parameters["token"]
                if (token == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing token"))
                    return@post
                }

                val monitor = uptimeService.getMonitorByPushToken(token)
                if (monitor == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Invalid push token"))
                    return@post
                }

                // Parse optional payload as raw JSON so numeric/string status formats both work.
                val payload =
                    try {
                        call.receiveText()
                            .takeIf { it.isNotBlank() }
                            ?.let { Json.parseToJsonElement(it).jsonObject }
                    } catch (_: Exception) {
                        null
                    }

                val status = payload?.get("status")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                val message = payload?.get("msg")?.jsonPrimitive?.contentOrNull ?: "Push received"
                val ping = payload?.get("ping")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: -1f

                // Record heartbeat
                val result =
                    CheckResult(
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
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal error"))
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
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                        return@get
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization membership"))
                        return@get
                    }

                    val organizationId = orgIds.first()
                    val monitors = uptimeService.listMonitors(organizationId)

                    call.respond(HttpStatusCode.OK, monitors)
                } catch (e: Exception) {
                    logger.error(e) { "List monitors error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to list monitors"))
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
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                        return@post
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization membership"))
                        return@post
                    }

                    val organizationId = orgIds.first()
                    val request = call.receive<CreateUptimeMonitorRequest>()

                    // Validate required fields based on type
                    val validationError = validateMonitorRequest(request)
                    if (validationError != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(validationError))
                        return@post
                    }

                    val monitor =
                        try {
                            uptimeService.createMonitor(organizationId, request)
                        } catch (e: IllegalStateException) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                            return@post
                        }

                    call.respond(HttpStatusCode.Created, monitor)
                } catch (e: Exception) {
                    logger.error(e) { "Create monitor error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to create monitor"))
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
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                        return@get
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization membership"))
                        return@get
                    }

                    val organizationId = orgIds.first()
                    val monitorId =
                        try {
                            UUID.fromString(call.parameters["id"])
                        } catch (_: Exception) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid monitor ID"))
                            return@get
                        }

                    val monitor = uptimeService.getMonitor(monitorId, organizationId)
                    if (monitor == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Monitor not found"))
                        return@get
                    }

                    call.respond(HttpStatusCode.OK, monitor)
                } catch (e: Exception) {
                    logger.error(e) { "Get monitor error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to get monitor"))
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
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                        return@put
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization membership"))
                        return@put
                    }

                    val organizationId = orgIds.first()
                    val monitorId =
                        try {
                            UUID.fromString(call.parameters["id"])
                        } catch (_: Exception) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid monitor ID"))
                            return@put
                        }

                    val request = call.receive<UpdateUptimeMonitorRequest>()
                    val monitor = uptimeService.updateMonitor(monitorId, organizationId, request)

                    if (monitor == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Monitor not found"))
                        return@put
                    }

                    call.respond(HttpStatusCode.OK, monitor)
                } catch (e: Exception) {
                    logger.error(e) { "Update monitor error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to update monitor"))
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
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                        return@delete
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization membership"))
                        return@delete
                    }

                    val organizationId = orgIds.first()
                    val monitorId =
                        try {
                            UUID.fromString(call.parameters["id"])
                        } catch (_: Exception) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid monitor ID"))
                            return@delete
                        }

                    val deleted = uptimeService.deleteMonitor(monitorId, organizationId)

                    if (!deleted) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Monitor not found"))
                        return@delete
                    }

                    call.respond(HttpStatusCode.OK, mapOf("ok" to true))
                } catch (e: Exception) {
                    logger.error(e) { "Delete monitor error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to delete monitor"))
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
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                        return@post
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization membership"))
                        return@post
                    }

                    val organizationId = orgIds.first()
                    val monitorId =
                        try {
                            UUID.fromString(call.parameters["id"])
                        } catch (_: Exception) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid monitor ID"))
                            return@post
                        }

                    val paused = uptimeService.pauseMonitor(monitorId, organizationId)

                    if (!paused) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Monitor not found"))
                        return@post
                    }

                    call.respond(HttpStatusCode.OK, mapOf("ok" to true))
                } catch (e: Exception) {
                    logger.error(e) { "Pause monitor error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to pause monitor"))
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
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                        return@post
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization membership"))
                        return@post
                    }

                    val organizationId = orgIds.first()
                    val monitorId =
                        try {
                            UUID.fromString(call.parameters["id"])
                        } catch (_: Exception) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid monitor ID"))
                            return@post
                        }

                    val resumed = uptimeService.resumeMonitor(monitorId, organizationId)

                    if (!resumed) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Monitor not found"))
                        return@post
                    }

                    call.respond(HttpStatusCode.OK, mapOf("ok" to true))
                } catch (e: Exception) {
                    logger.error(e) { "Resume monitor error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to resume monitor"))
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
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                        return@get
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization membership"))
                        return@get
                    }

                    val organizationId = orgIds.first()
                    val monitorId =
                        try {
                            UUID.fromString(call.parameters["id"])
                        } catch (_: Exception) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid monitor ID"))
                            return@get
                        }

                    // Verify monitor belongs to org
                    val monitor = uptimeService.getMonitor(monitorId, organizationId)
                    if (monitor == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Monitor not found"))
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
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to get heartbeats"))
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
