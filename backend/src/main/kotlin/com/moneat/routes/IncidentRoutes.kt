package com.moneat.routes

import com.moneat.services.oncall.IncidentManagementService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class ReassignIncidentRequest(
    val toUserId: Int
)

@Serializable
data class AddNoteRequest(
    val note: String
)

fun Route.incidentRoutes(incidentServiceProvider: () -> IncidentManagementService) {
    
    route("/incidents") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
                
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
                    offset = offset
                )
                call.respond(incidents)
            }
            
            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
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
                if (incident != null && incident.organizationId == organizationId) {
                    call.respond(incident)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Incident not found"))
                }
            }
            
            get("/{id}/timeline") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
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
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
                val jwtUserId = principal?.payload?.getClaim("user_id")?.asInt()
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
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
                val jwtUserId = principal?.payload?.getClaim("user_id")?.asInt()
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
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
                val byUserId = principal?.payload?.getClaim("user_id")?.asInt()
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
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
                val userId = principal?.payload?.getClaim("user_id")?.asInt()
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
        }
    }
}
