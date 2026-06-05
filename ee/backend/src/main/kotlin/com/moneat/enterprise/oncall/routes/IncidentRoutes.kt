// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.routes

import com.moneat.alerts.models.IncidentSeverity
import com.moneat.enterprise.oncall.services.OnCallAlertService
import com.moneat.enterprise.oncall.services.OnCallIncidentService
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

private const val DEFAULT_INCIDENT_LIMIT = 50
private const val MIN_INCIDENT_LIMIT = 1
private const val MAX_INCIDENT_LIMIT = 200

private fun parseStatusFilters(rawStatuses: List<String>?): List<String> =
    rawStatuses
        .orEmpty()
        .flatMap { it.split(",") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

@Serializable
data class DeclareIncidentRequest(
    val title: String,
    val description: String? = null,
    val severity: String? = null,
)

@Serializable
data class AddAlertToIncidentRequest(
    val alertId: Int,
)

@Serializable
data class ReassignIncidentRequest(
    val toUserId: Int,
)

@Serializable
data class AddNoteRequest(
    val note: String,
)

fun Route.incidentRoutes(alertServiceProvider: () -> OnCallAlertService) {
    val onCallIncidentService = OnCallIncidentService()

    route("/v1/on-call/alerts") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                val statuses = parseStatusFilters(call.request.queryParameters.getAll("status"))
                val priority = call.request.queryParameters["priority"]
                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull()
                val limit =
                    when {
                        rawLimit == null -> DEFAULT_INCIDENT_LIMIT
                        rawLimit < MIN_INCIDENT_LIMIT ->
                            throw BadRequestException("limit must be >= $MIN_INCIDENT_LIMIT")
                        else -> rawLimit.coerceAtMost(MAX_INCIDENT_LIMIT)
                    }
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val alertService = alertServiceProvider()
                val alerts =
                    alertService.listAlerts(
                        organizationId = organizationId,
                        statuses = statuses.ifEmpty { null },
                        priority = priority,
                        limit = limit,
                        offset = offset,
                        currentUserId = principal.payload.getClaim("userId").asInt(),
                    )
                call.respond(alerts)
            }

            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
                    return@get
                }

                val alertService = alertServiceProvider()
                val incident = alertService.getAlert(incidentId, principal.payload.getClaim("userId").asInt())
                if (incident != null && incident.organizationId == organizationId) {
                    call.respond(incident)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
                }
            }

            get("/{id}/timeline") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
                    return@get
                }

                val alertService = alertServiceProvider()
                val incident = alertService.getAlert(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
                    return@get
                }

                val timeline = alertService.getTimeline(incidentId)
                call.respond(timeline)
            }

            post("/{id}/acknowledge") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val jwtUserId = principal?.payload?.getClaim("userId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
                    return@post
                }

                if (jwtUserId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                val alertService = alertServiceProvider()
                val incident = alertService.getAlert(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
                    return@post
                }

                val acknowledged = alertService.acknowledge(incidentId, jwtUserId)
                if (acknowledged) {
                    call.respond(HttpStatusCode.OK, MessageResponse("Alert acknowledged"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not acknowledge alert"))
                }
            }

            post("/{id}/resolve") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val jwtUserId = principal?.payload?.getClaim("userId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
                    return@post
                }

                if (jwtUserId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                val alertService = alertServiceProvider()
                val incident = alertService.getAlert(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
                    return@post
                }

                val resolved = alertService.resolve(incidentId, jwtUserId)
                if (resolved) {
                    call.respond(HttpStatusCode.OK, MessageResponse("Alert resolved"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not resolve alert"))
                }
            }

            post("/{id}/reassign") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val byUserId = principal?.payload?.getClaim("userId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
                    return@post
                }

                if (byUserId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                val alertService = alertServiceProvider()
                val incident = alertService.getAlert(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
                    return@post
                }

                val request = call.receive<ReassignIncidentRequest>()

                val reassigned = alertService.reassign(incidentId, request.toUserId, byUserId)
                if (reassigned) {
                    call.respond(HttpStatusCode.OK, MessageResponse("Alert reassigned"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not reassign alert"))
                }
            }

            post("/{id}/notes") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
                    return@post
                }

                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                val alertService = alertServiceProvider()
                val incident = alertService.getAlert(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
                    return@post
                }

                val request = call.receive<AddNoteRequest>()

                val event = alertService.addNote(incidentId, userId, request.note)
                call.respond(HttpStatusCode.Created, event)
            }

            post("/{id}/view") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null || userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
                    return@post
                }

                val alertService = alertServiceProvider()
                val incident = alertService.getAlert(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
                    return@post
                }

                alertService.viewAlert(incidentId, userId)
                call.respond(HttpStatusCode.OK, MessageResponse("Alert viewed"))
            }

            post("/{id}/unavailable") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null || userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
                    return@post
                }

                val alertService = alertServiceProvider()
                val incident = alertService.getAlert(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
                    return@post
                }

                val result = alertService.markUnavailable(incidentId, userId)
                if (result) {
                    call.respond(HttpStatusCode.OK, MessageResponse("Escalated to next on-call"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not escalate alert"))
                }
            }

            post("/{alertId}/declare-incident") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val alertId = call.parameters["alertId"]?.toIntOrNull()

                if (organizationId == null || userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                if (alertId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
                    return@post
                }

                val alertService = alertServiceProvider()
                val alert = alertService.getAlert(alertId)
                if (alert == null || alert.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
                    return@post
                }

                val request = call.receive<DeclareIncidentRequest>()

                val severity = request.severity?.trim()

                if (severity.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing incident severity"))
                    return@post
                }

                val incidentSeverity = IncidentSeverity.wireValue(severity)
                if (incidentSeverity == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident severity"))
                    return@post
                }

                try {
                    val incident =
                        onCallIncidentService.declareIncident(
                            organizationId = organizationId,
                            userId = userId,
                            alertId = alertId,
                            title = request.title,
                            description = request.description,
                            severity = incidentSeverity,
                        )
                    call.respond(HttpStatusCode.Created, incident)
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }
        }
    }

    route("/v1/on-call/incidents") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                val status = call.request.queryParameters["status"]
                val severity = call.request.queryParameters["severity"]
                val incidents = onCallIncidentService.getIncidents(organizationId, status, severity)
                call.respond(incidents)
            }

            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident ID"))
                    return@get
                }

                if (!onCallIncidentService.isIncidentInOrganization(incidentId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
                    return@get
                }

                val incident = onCallIncidentService.getIncident(incidentId)
                if (incident != null) {
                    call.respond(incident)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
                }
            }

            post("/{id}/resolve") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null || userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident ID"))
                    return@post
                }

                if (!onCallIncidentService.isIncidentInOrganization(incidentId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
                    return@post
                }

                val incident = onCallIncidentService.resolveIncident(incidentId, userId)
                if (incident != null) {
                    call.respond(incident)
                } else {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to resolve incident"))
                }
            }

            post("/{id}/add-alert") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident ID"))
                    return@post
                }

                if (!onCallIncidentService.isIncidentInOrganization(incidentId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
                    return@post
                }

                val request = call.receive<AddAlertToIncidentRequest>()
                val alertService = alertServiceProvider()
                val alert = alertService.getAlert(request.alertId)

                if (alert == null || alert.organizationId != organizationId) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Alert not found or not in organization"))
                    return@post
                }

                try {
                    onCallIncidentService.addAlertToIncident(incidentId, request.alertId)
                    call.respond(HttpStatusCode.OK, MessageResponse("Alert added to incident"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }

            get("/{id}/timeline") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident ID"))
                    return@get
                }

                if (!onCallIncidentService.isIncidentInOrganization(incidentId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
                    return@get
                }

                val timeline = onCallIncidentService.getIncidentTimeline(incidentId)
                call.respond(timeline)
            }

            post("/{id}/notes") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null || userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident ID"))
                    return@post
                }

                if (!onCallIncidentService.isIncidentInOrganization(incidentId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
                    return@post
                }

                val request = call.receive<AddNoteRequest>()
                onCallIncidentService.addNote(incidentId, userId, request.note)
                call.respond(HttpStatusCode.OK, MessageResponse("Note added"))
            }
        }
    }
}
