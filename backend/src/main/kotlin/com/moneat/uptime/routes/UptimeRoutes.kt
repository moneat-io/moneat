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

import com.moneat.auth.currentOrgContextOrNull
import com.moneat.alerts.models.AlertPriority
import com.moneat.shared.services.parseJavaUuidOrNull
import com.moneat.uptime.models.CheckResult
import com.moneat.uptime.models.CreateUptimeMonitorRequest
import com.moneat.uptime.models.UpdateUptimeMonitorRequest
import com.moneat.uptime.services.UptimeService
import com.moneat.utils.ErrorResponse
import com.moneat.utils.UrlValidator
import com.moneat.utils.suspendRunCatching
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.koin.core.context.GlobalContext
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

private const val INVALID_TOKEN_MESSAGE = "Invalid token"
private const val INVALID_MONITOR_ID_MESSAGE = "Invalid monitor ID"
private const val MONITOR_NOT_FOUND_MESSAGE = "Monitor not found"
private const val MIN_INTERVAL_SECONDS = 10

private data class UptimeRouteContext(
    val organizationId: Int,
)

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

private suspend fun ApplicationCall.requireUptimeRouteContext(): UptimeRouteContext? {
    val principal = principal<JWTPrincipal>()
    val context = principal?.currentOrgContextOrNull()
    if (context == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
        return null
    }

    return UptimeRouteContext(
        organizationId = context.orgId,
    )
}

private suspend fun ApplicationCall.requireMonitorId(): UUID? {
    val monitorId = parseJavaUuidOrNull(parameters["id"])
    if (monitorId == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_MONITOR_ID_MESSAGE))
        return null
    }
    return monitorId
}

/**
 * Uptime monitoring routes.
 */
fun Route.uptimeRoutes(
    uptimeService: UptimeService = GlobalContext.get().get(),
) {
    route("/v1/uptime") {
        registerPushHeartbeatRoute(uptimeService)
        authenticate("auth-jwt") {
            registerMonitorRoutes(uptimeService)
        }
    }
}

private fun Route.registerPushHeartbeatRoute(uptimeService: UptimeService) {
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

            val payload =
                try {
                    call.receiveText()
                        .takeIf { it.isNotBlank() }
                        ?.let { Json.parseToJsonElement(it).jsonObject }
                } catch (_: SerializationException) {
                    null
                }

            val result =
                CheckResult(
                    status = payload?.get("status")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1,
                    responseTimeMs = -1,
                    statusCode = 0,
                    message = payload?.get("msg")?.jsonPrimitive?.contentOrNull ?: "Push received",
                    pingMs = payload?.get("ping")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: -1f,
                )

            uptimeService.recordHeartbeat(monitor.id, result)
            uptimeService.updateMonitorStatus(monitor.id, result)
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }
    }
}

private fun Route.registerMonitorRoutes(uptimeService: UptimeService) {
    registerListMonitorsRoute(uptimeService)
    registerCreateMonitorRoute(uptimeService)
    registerGetMonitorRoute(uptimeService)
    registerUpdateMonitorRoute(uptimeService)
    registerDeleteMonitorRoute(uptimeService)
    registerPauseMonitorRoute(uptimeService)
    registerResumeMonitorRoute(uptimeService)
    registerMonitorHeartbeatsRoute(uptimeService)
}

private fun Route.registerListMonitorsRoute(uptimeService: UptimeService) {
    get("/monitors") {
        runUptimeRoute(call, "List monitors error", "Failed to list monitors") {
            val context = call.requireUptimeRouteContext() ?: return@runUptimeRoute
            val monitors = uptimeService.listMonitors(context.organizationId)
            call.respond(HttpStatusCode.OK, monitors)
        }
    }
}

private fun Route.registerCreateMonitorRoute(uptimeService: UptimeService) {
    post("/monitors") {
        suspendRunCatching {
            val context = call.requireUptimeRouteContext() ?: return@post
            val request = call.receive<CreateUptimeMonitorRequest>()

            val validationError = validateMonitorRequest(request)
            if (validationError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(validationError))
                return@post
            }

            val monitor =
                try {
                    uptimeService.createMonitor(context.organizationId, request)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                    return@post
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
}

private fun Route.registerGetMonitorRoute(uptimeService: UptimeService) {
    get("/monitors/{id}") {
        runUptimeRoute(call, "Get monitor error", "Failed to get monitor") {
            val context = call.requireUptimeRouteContext() ?: return@runUptimeRoute
            val monitorId = call.requireMonitorId() ?: return@runUptimeRoute
            val monitor = uptimeService.getMonitor(monitorId, context.organizationId)
            if (monitor == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(MONITOR_NOT_FOUND_MESSAGE))
                return@runUptimeRoute
            }

            call.respond(HttpStatusCode.OK, monitor)
        }
    }
}

private fun Route.registerUpdateMonitorRoute(uptimeService: UptimeService) {
    put("/monitors/{id}") {
        runUptimeRoute(call, "Update monitor error", "Failed to update monitor") {
            val context = call.requireUptimeRouteContext() ?: return@runUptimeRoute
            val monitorId = call.requireMonitorId() ?: return@runUptimeRoute
            val request = call.receive<UpdateUptimeMonitorRequest>()
            val validationError = validateMonitorRequest(request)
            if (validationError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(validationError))
                return@runUptimeRoute
            }

            val monitor =
                try {
                    uptimeService.updateMonitor(monitorId, context.organizationId, request)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                    return@runUptimeRoute
                }
            if (monitor == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(MONITOR_NOT_FOUND_MESSAGE))
                return@runUptimeRoute
            }

            call.respond(HttpStatusCode.OK, monitor)
        }
    }
}

private fun Route.registerDeleteMonitorRoute(uptimeService: UptimeService) {
    delete("/monitors/{id}") {
        runUptimeRoute(call, "Delete monitor error", "Failed to delete monitor") {
            val context = call.requireUptimeRouteContext() ?: return@runUptimeRoute
            val monitorId = call.requireMonitorId() ?: return@runUptimeRoute
            val deleted = uptimeService.deleteMonitor(monitorId, context.organizationId)
            if (!deleted) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(MONITOR_NOT_FOUND_MESSAGE))
                return@runUptimeRoute
            }

            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }
    }
}

private fun Route.registerPauseMonitorRoute(uptimeService: UptimeService) {
    post("/monitors/{id}/pause") {
        runUptimeRoute(call, "Pause monitor error", "Failed to pause monitor") {
            val context = call.requireUptimeRouteContext() ?: return@runUptimeRoute
            val monitorId = call.requireMonitorId() ?: return@runUptimeRoute
            val paused = uptimeService.pauseMonitor(monitorId, context.organizationId)
            if (!paused) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(MONITOR_NOT_FOUND_MESSAGE))
                return@runUptimeRoute
            }

            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }
    }
}

private fun Route.registerResumeMonitorRoute(uptimeService: UptimeService) {
    post("/monitors/{id}/resume") {
        runUptimeRoute(call, "Resume monitor error", "Failed to resume monitor") {
            val context = call.requireUptimeRouteContext() ?: return@runUptimeRoute
            val monitorId = call.requireMonitorId() ?: return@runUptimeRoute
            val resumed = uptimeService.resumeMonitor(monitorId, context.organizationId)
            if (!resumed) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(MONITOR_NOT_FOUND_MESSAGE))
                return@runUptimeRoute
            }

            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }
    }
}

private fun Route.registerMonitorHeartbeatsRoute(uptimeService: UptimeService) {
    get("/monitors/{id}/heartbeats") {
        runUptimeRoute(call, "Get heartbeats error", "Failed to get heartbeats") {
            val context = call.requireUptimeRouteContext() ?: return@runUptimeRoute
            val monitorId = call.requireMonitorId() ?: return@runUptimeRoute
            val monitor = uptimeService.getMonitor(monitorId, context.organizationId)
            if (monitor == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(MONITOR_NOT_FOUND_MESSAGE))
                return@runUptimeRoute
            }

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

/**
 * Validate monitor creation request.
 */
private fun validateMonitorRequest(request: CreateUptimeMonitorRequest): String? {
    if (request.name.isBlank()) return "Monitor name is required"

    val typeError = validateMonitorType(request)
    if (typeError != null) return typeError

    val targetError = validateMonitorTargets(request)
    if (targetError != null) return targetError

    if (request.intervalSeconds < MIN_INTERVAL_SECONDS) return "Check interval must be at least 10 seconds"
    if (request.timeoutSeconds < 1) return "Timeout must be at least 1 second"
    return validateAlertPriority(request.alertPriority ?: request.legacyIncidentSeverity)
}

private fun validateMonitorRequest(request: UpdateUptimeMonitorRequest): String? {
    val targetError = validateMonitorTargets(request)
    if (targetError != null) return targetError
    return validateAlertPriority(request.alertPriority ?: request.legacyIncidentSeverity)
}

private fun validateAlertPriority(priority: String?): String? {
    if (priority == null) return null
    return if (AlertPriority.fromString(priority) == null) {
        "Alert priority must be one of P0, P1, P2, P3, P4, or P5"
    } else {
        null
    }
}

private fun validateMonitorType(request: CreateUptimeMonitorRequest): String? =
    when (request.type.lowercase()) {
        "http", "keyword", "json_query" ->
            if (request.url.isNullOrBlank()) "URL is required for ${request.type} monitors" else null
        "tcp" -> validateTcpMonitor(request)
        "ping" ->
            if (request.hostname.isNullOrBlank()) "Hostname is required for ping monitors" else null
        "dns" ->
            if (request.hostname.isNullOrBlank()) "Hostname is required for DNS monitors" else null
        "websocket" ->
            if (request.url.isNullOrBlank()) "URL is required for WebSocket monitors" else null
        "ssl" ->
            if (request.hostname.isNullOrBlank()) "Hostname is required for SSL monitors" else null
        "database" ->
            if (request.dbConnectionString.isNullOrBlank()) "Database connection string is required" else null
        "docker" ->
            if (request.dockerContainerName.isNullOrBlank()) "Container name is required for Docker monitors" else null
        "push" -> null // No additional validation for push monitors
        else -> "Unknown monitor type: ${request.type}"
    }

private fun validateTcpMonitor(request: CreateUptimeMonitorRequest): String? {
    if (request.hostname.isNullOrBlank()) return "Hostname is required for TCP monitors"
    if (request.port == null) return "Port is required for TCP monitors"
    return null
}

private fun validateMonitorTargets(request: CreateUptimeMonitorRequest): String? =
    when (request.type.lowercase()) {
        "http", "keyword", "json_query" -> validateExternalUrlTarget(request.url)
        "tcp", "ping", "dns", "ssl" ->
            validateExternalHostTarget(request.hostname) ?: validateExternalHostTarget(request.dnsServer)
        "websocket" -> validateWebSocketTarget(request.url)
        "database" -> validateJdbcTarget(request.dbConnectionString)
        "docker" -> validateDockerTarget(request.dockerHost)
        else -> null
    }

private fun validateMonitorTargets(request: UpdateUptimeMonitorRequest): String? =
    validateWebSocketTarget(request.url)
        ?: validateExternalHostTarget(request.hostname)
        ?: validateExternalHostTarget(request.dnsServer)
        ?: validateJdbcTarget(request.dbConnectionString)
        ?: validateDockerTarget(request.dockerHost)

private fun validateExternalUrlTarget(url: String?): String? =
    validateTarget(url) { UrlValidator.validateExternalUrl(it) }

private fun validateExternalHostTarget(hostname: String?): String? =
    validateTarget(hostname) { UrlValidator.validateExternalHost(it) }

private fun validateJdbcTarget(connectionString: String?): String? =
    validateTarget(connectionString) { UrlValidator.validateExternalJdbcUrl(it) }

private fun validateDockerTarget(dockerHost: String?): String? {
    if (dockerHost == null || !dockerHost.startsWith("http", ignoreCase = true)) return null
    return validateExternalUrlTarget(dockerHost)
}

private fun validateWebSocketTarget(url: String?): String? {
    val target = url?.takeIf { it.isNotBlank() } ?: return null
    val normalized = try {
        webSocketHttpUrl(target)
    } catch (_: java.net.URISyntaxException) {
        return "Invalid URL: $target"
    } catch (_: IllegalArgumentException) {
        return "Invalid URL: $target"
    }
    return validateExternalUrlTarget(normalized)
}

private fun validateTarget(
    value: String?,
    validator: (String) -> Unit,
): String? {
    val target = value?.takeIf { it.isNotBlank() } ?: return null
    return try {
        validator(target)
        null
    } catch (e: UrlValidator.SsrfException) {
        "Blocked: ${e.message}"
    }
}

private fun webSocketHttpUrl(url: String): String {
    val uri = java.net.URI(url)
    val scheme = when (uri.scheme?.lowercase()) {
        "ws" -> "http"
        "wss" -> "https"
        else -> uri.scheme
    }
    return java.net.URI(scheme, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
}
