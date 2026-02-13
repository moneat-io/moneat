package com.moneat.routes

import com.moneat.services.oncall.IncidentManagementService
import com.moneat.services.oncall.OnCallIncidentService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class DeclareIncidentRequest(
    val title: String,
    val description: String? = null,
    val severity: String
)

@Serializable
data class AddAlertToIncidentRequest(
    val alertId: Int
)

@Serializable
data class ReassignIncidentRequest(
    val toUserId: Int
)

@Serializable
data class AddNoteRequest(
    val note: String
)

fun Route.incidentRoutes(incidentServiceProvider: () -> IncidentManagementService) {
    
    val onCallIncidentService = OnCallIncidentService()
    
    route("/v1/incidents") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                val status = call.request.queryParameters["status"]
                val priorityLevel = call.request.queryParameters["priority"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                
                val incidentService = incidentServiceProvider()
                val incidents = incidentService.listIncidents(
                    organizationId = organizationId,
                    status = status,
                    priorityLevel = priorityLevel,
                    limit = limit,
                    offset = offset,
                    currentUserId = principal.payload.getClaim("userId").asInt()
                )
                call.respond(incidents)
            }
            
            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid incident ID"))
                    return@get
                }
                
                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId, principal.payload.getClaim("userId").asInt())
                if (incident != null && incident.organizationId == organizationId) {
                    call.respond(incident)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Incident not found"))
                }
            }
            
            get("/{id}/timeline") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid incident ID"))
                    return@get
                }
                
                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Incident not found"))
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
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid incident ID"))
                    return@post
                }
                
                if (jwtUserId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Incident not found"))
                    return@post
                }

                val acknowledged = incidentService.acknowledge(incidentId, jwtUserId)
                if (acknowledged) {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Incident acknowledged"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Could not acknowledge incident"))
                }
            }
            
            post("/{id}/resolve") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val jwtUserId = principal?.payload?.getClaim("userId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid incident ID"))
                    return@post
                }
                
                if (jwtUserId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Incident not found"))
                    return@post
                }

                val resolved = incidentService.resolve(incidentId, jwtUserId)
                if (resolved) {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Incident resolved"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Could not resolve incident"))
                }
            }
            
            post("/{id}/reassign") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val byUserId = principal?.payload?.getClaim("userId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid incident ID"))
                    return@post
                }
                
                if (byUserId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Incident not found"))
                    return@post
                }
                
                val request = call.receive<ReassignIncidentRequest>()
                
                val reassigned = incidentService.reassign(incidentId, request.toUserId, byUserId)
                if (reassigned) {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Incident reassigned"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Could not reassign incident"))
                }
            }
            
            post("/{id}/notes") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid incident ID"))
                    return@post
                }
                
                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Incident not found"))
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
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid incident ID"))
                    return@post
                }
                
                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Incident not found"))
                    return@post
                }
                
                incidentService.viewIncident(incidentId, userId)
                call.respond(HttpStatusCode.OK, mapOf("message" to "Incident viewed"))
            }
            
            post("/{id}/unavailable") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null || userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid incident ID"))
                    return@post
                }
                
                val incidentService = incidentServiceProvider()
                val incident = incidentService.getIncident(incidentId)
                if (incident == null || incident.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Incident not found"))
                    return@post
                }
                
                val result = incidentService.markUnavailable(incidentId, userId)
                if (result) {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Escalated to next on-call"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Could not escalate incident"))
                }
            }
            
            post("/{alertId}/declare") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val alertId = call.parameters["alertId"]?.toIntOrNull()
                
                if (organizationId == null || userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                if (alertId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid alert ID"))
                    return@post
                }
                
                val incidentService = incidentServiceProvider()
                val alert = incidentService.getIncident(alertId)
                if (alert == null || alert.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Alert not found"))
                    return@post
                }
                
                val request = call.receive<DeclareIncidentRequest>()
                
                try {
                    val incident = onCallIncidentService.declareIncident(
                        organizationId = organizationId,
                        userId = userId,
                        alertId = alertId,
                        title = request.title,
                        description = request.description,
                        severity = request.severity
                    )
                    call.respond(HttpStatusCode.Created, incident)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
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
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                val status = call.request.queryParameters["status"]
                val incidents = onCallIncidentService.getIncidents(organizationId, status)
                call.respond(incidents)
            }
            
            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid incident ID"))
                    return@get
                }
                
                if (!onCallIncidentService.isIncidentInOrganization(incidentId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Incident not found"))
                    return@get
                }
                
                val incident = onCallIncidentService.getIncident(incidentId)
                if (incident != null) {
                    call.respond(incident)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Incident not found"))
                }
            }
            
            post("/{id}/resolve") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null || userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid incident ID"))
                    return@post
                }
                
                if (!onCallIncidentService.isIncidentInOrganization(incidentId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Incident not found"))
                    return@post
                }
                
                val incident = onCallIncidentService.resolveIncident(incidentId, userId)
                if (incident != null) {
                    call.respond(incident)
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to resolve incident"))
                }
            }
            
            post("/{id}/add-alert") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val incidentId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                if (incidentId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid incident ID"))
                    return@post
                }
                
                if (!onCallIncidentService.isIncidentInOrganization(incidentId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Incident not found"))
                    return@post
                }
                
                val request = call.receive<AddAlertToIncidentRequest>()
                val incidentService = incidentServiceProvider()
                val alert = incidentService.getIncident(request.alertId)
                
                if (alert == null || alert.organizationId != organizationId) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Alert not found or not in organization"))
                    return@post
                }
                
                try {
                    onCallIncidentService.addAlertToIncident(incidentId, request.alertId)
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Alert added to incident"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
        }
    }
}
