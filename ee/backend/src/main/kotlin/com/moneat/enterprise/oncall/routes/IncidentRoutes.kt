// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.routes

import com.moneat.enterprise.oncall.services.IncidentManagementService
import com.moneat.enterprise.oncall.services.OnCallIncidentService
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
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

@Serializable
data class DeclareIncidentRequest(
    val title: String,
    val description: String? = null,
    val priorityLevel: String? = null,
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

fun Route.incidentRoutes(incidentServiceProvider: () -> IncidentManagementService) {
    val onCallIncidentService = OnCallIncidentService()

    route("/v1/incidents") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                val status = call.request.queryParameters["status"]
                val priorityLevel = call.request.queryParameters["priority"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_INCIDENT_LIMIT
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val incidentService = incidentServiceProvider()
                val incidents =
                    incidentService.listIncidents(
                        organizationId = organizationId,
                        status = status,
                        priorityLevel = priorityLevel,
                        limit = limit,
                        offset = offset,
                        currentUserId = principal.payload.getClaim("userId").asInt(),
                    )
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

                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId, principal.payload.getClaim("userId").asInt())
                if (incident != null && incident.organizationId == organizationId) {
                    call.respond(incident)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
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

                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
                    return@get
                }

                val timeline = incidentService.getTimeline(incidentId)
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
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident ID"))
                    return@post
                }

                if (jwtUserId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
                    return@post
                }

                val acknowledged = incidentService.acknowledge(incidentId, jwtUserId)
                if (acknowledged) {
                    call.respond(HttpStatusCode.OK, MessageResponse("Incident acknowledged"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not acknowledge incident"))
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
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident ID"))
                    return@post
                }

                if (jwtUserId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
                    return@post
                }

                val resolved = incidentService.resolve(incidentId, jwtUserId)
                if (resolved) {
                    call.respond(HttpStatusCode.OK, MessageResponse("Incident resolved"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not resolve incident"))
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
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident ID"))
                    return@post
                }

                if (byUserId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
                    return@post
                }

                val request = call.receive<ReassignIncidentRequest>()

                val reassigned = incidentService.reassign(incidentId, request.toUserId, byUserId)
                if (reassigned) {
                    call.respond(HttpStatusCode.OK, MessageResponse("Incident reassigned"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not reassign incident"))
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
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident ID"))
                    return@post
                }

                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
                    return@post
                }

                val request = call.receive<AddNoteRequest>()

                val event = incidentService.addNote(incidentId, userId, request.note)
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
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident ID"))
                    return@post
                }

                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
                    return@post
                }

                incidentService.viewIncident(incidentId, userId)
                call.respond(HttpStatusCode.OK, MessageResponse("Incident viewed"))
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
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident ID"))
                    return@post
                }

                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
                    return@post
                }

                val result = incidentService.markUnavailable(incidentId, userId)
                if (result) {
                    call.respond(HttpStatusCode.OK, MessageResponse("Escalated to next on-call"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not escalate incident"))
                }
            }

            post("/{alertId}/declare") {
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

                val incidentService = incidentServiceProvider()
                val alert = incidentService.getIncident(alertId)
                if (alert == null || alert.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
                    return@post
                }

                val request = call.receive<DeclareIncidentRequest>()

                val priorityLevel = request.priorityLevel?.trim() ?: request.severity?.trim()

                if (priorityLevel.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing priority level"))
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
                            priorityLevel = priorityLevel,
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

    route("/v1/on-call-incidents") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                val status = call.request.queryParameters["status"]
                val priorityLevel = call.request.queryParameters["priorityLevel"]
                val incidents = onCallIncidentService.getIncidents(organizationId, status, priorityLevel)
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
                val incidentService = incidentServiceProvider()
                val alert = incidentService.getIncident(request.alertId)

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
