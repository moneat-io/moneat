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
import io.ktor.server.application.ApplicationCall
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
import com.moneat.utils.suspendRunCatching
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.GlobalContext
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

private suspend fun runUptimeRoute(
    call: ApplicationCall,
    logMessage: String,
    userMessage: String,
    block: suspend () -> Unit,
) {
    suspendRunCatching {
        block()
    }.onFailure { e ->
        logger.error(e) { "$logMessage: ${e.message}" }
        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(userMessage))
    }
}

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
            runUptimeRoute(call, "Push heartbeat error", "Internal error") {
                val token = call.parameters["token"]
                if (token == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing token"))
                    return@runUptimeRoute
                }

                val monitor = uptimeService.getMonitorByPushToken(token)
                if (monitor == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Invalid push token"))
                    return@runUptimeRoute
                }

                // Parse optional payload as raw JSON so numeric/string status formats both work.
                val payload =
                    try {
                        call.receiveText()
                            .takeIf { it.isNotBlank() }
                            ?.let { Json.parseToJsonElement(it).jsonObject }
                    } catch (_: SerializationException) {
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
            }
        }

        // All other routes require authentication
        authenticate("auth-jwt") {
            /**
             * List all monitors for organization.
             */
            get("/monitors") {
                runUptimeRoute(call, "List monitors error", "Failed to list monitors") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()

                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                        return@runUptimeRoute
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization membership"))
                        return@runUptimeRoute
                    }

                    val organizationId = orgIds.first()
                    val monitors = uptimeService.listMonitors(organizationId)

                    call.respond(HttpStatusCode.OK, monitors)
                }
            }

            /**
             * Create a new monitor.
             */
            post("/monitors") {
                suspendRunCatching {
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
                }.onFailure { e ->
                    logger.error(e) { "Create monitor error: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to create monitor"))
                }
            }

            /**
             * Get monitor details.
             */
            get("/monitors/{id}") {
                runUptimeRoute(call, "Get monitor error", "Failed to get monitor") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()

                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                        return@runUptimeRoute
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization membership"))
                        return@runUptimeRoute
                    }

                    val organizationId = orgIds.first()
                    val monitorId =
                        try {
                            UUID.fromString(call.parameters["id"])
                        } catch (_: IllegalArgumentException) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid monitor ID"))
                            return@runUptimeRoute
                        }

                    val monitor = uptimeService.getMonitor(monitorId, organizationId)
                    if (monitor == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Monitor not found"))
                        return@runUptimeRoute
                    }

                    call.respond(HttpStatusCode.OK, monitor)
                }
            }

            /**
             * Update a monitor.
             */
            put("/monitors/{id}") {
                runUptimeRoute(call, "Update monitor error", "Failed to update monitor") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()

                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                        return@runUptimeRoute
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization membership"))
                        return@runUptimeRoute
                    }

                    val organizationId = orgIds.first()
                    val monitorId =
                        try {
                            UUID.fromString(call.parameters["id"])
                        } catch (_: IllegalArgumentException) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid monitor ID"))
                            return@runUptimeRoute
                        }

                    val request = call.receive<UpdateUptimeMonitorRequest>()
                    val monitor = uptimeService.updateMonitor(monitorId, organizationId, request)

                    if (monitor == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Monitor not found"))
                        return@runUptimeRoute
                    }

                    call.respond(HttpStatusCode.OK, monitor)
                }
            }

            /**
             * Delete a monitor.
             */
            delete("/monitors/{id}") {
                runUptimeRoute(call, "Delete monitor error", "Failed to delete monitor") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()

                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                        return@runUptimeRoute
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization membership"))
                        return@runUptimeRoute
                    }

                    val organizationId = orgIds.first()
                    val monitorId =
                        try {
                            UUID.fromString(call.parameters["id"])
                        } catch (_: IllegalArgumentException) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid monitor ID"))
                            return@runUptimeRoute
                        }

                    val deleted = uptimeService.deleteMonitor(monitorId, organizationId)

                    if (!deleted) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Monitor not found"))
                        return@runUptimeRoute
                    }

                    call.respond(HttpStatusCode.OK, mapOf("ok" to true))
                }
            }

            /**
             * Pause a monitor.
             */
            post("/monitors/{id}/pause") {
                runUptimeRoute(call, "Pause monitor error", "Failed to pause monitor") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()

                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                        return@runUptimeRoute
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization membership"))
                        return@runUptimeRoute
                    }

                    val organizationId = orgIds.first()
                    val monitorId =
                        try {
                            UUID.fromString(call.parameters["id"])
                        } catch (_: IllegalArgumentException) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid monitor ID"))
                            return@runUptimeRoute
                        }

                    val paused = uptimeService.pauseMonitor(monitorId, organizationId)

                    if (!paused) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Monitor not found"))
                        return@runUptimeRoute
                    }

                    call.respond(HttpStatusCode.OK, mapOf("ok" to true))
                }
            }

            /**
             * Resume a monitor.
             */
            post("/monitors/{id}/resume") {
                runUptimeRoute(call, "Resume monitor error", "Failed to resume monitor") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()

                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                        return@runUptimeRoute
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization membership"))
                        return@runUptimeRoute
                    }

                    val organizationId = orgIds.first()
                    val monitorId =
                        try {
                            UUID.fromString(call.parameters["id"])
                        } catch (_: IllegalArgumentException) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid monitor ID"))
                            return@runUptimeRoute
                        }

                    val resumed = uptimeService.resumeMonitor(monitorId, organizationId)

                    if (!resumed) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Monitor not found"))
                        return@runUptimeRoute
                    }

                    call.respond(HttpStatusCode.OK, mapOf("ok" to true))
                }
            }

            /**
             * Get heartbeats for a monitor.
             */
            get("/monitors/{id}/heartbeats") {
                runUptimeRoute(call, "Get heartbeats error", "Failed to get heartbeats") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()

                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                        return@runUptimeRoute
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization membership"))
                        return@runUptimeRoute
                    }

                    val organizationId = orgIds.first()
                    val monitorId =
                        try {
                            UUID.fromString(call.parameters["id"])
                        } catch (_: IllegalArgumentException) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid monitor ID"))
                            return@runUptimeRoute
                        }

                    // Verify monitor belongs to org
                    val monitor = uptimeService.getMonitor(monitorId, organizationId)
                    if (monitor == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Monitor not found"))
                        return@runUptimeRoute
                    }

                    // Parse time range
                    val fromParam = call.request.queryParameters["from"]?.toLongOrNull()
                    val toParam = call.request.queryParameters["to"]?.toLongOrNull()

                    val now = Clock.System.now()
                    val from = fromParam?.let { Instant.fromEpochMilliseconds(it) } ?: now.minus(24.hours)
                    val to = toParam?.let { Instant.fromEpochMilliseconds(it) } ?: now

                    val heartbeats = uptimeService.getHeartbeats(monitorId, from, to)

                    call.respond(HttpStatusCode.OK, heartbeats)
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
