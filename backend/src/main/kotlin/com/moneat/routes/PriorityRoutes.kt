package com.moneat.routes

import com.moneat.models.BusinessHoursWindow
import com.moneat.services.oncall.PriorityService
import com.moneat.services.oncall.BusinessHoursService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.LocalTime

@Serializable
data class UpdatePriorityRequest(
    val severity: String,
    val priorityLevel: String,
    val isPageable: Boolean,
    val label: String,
    val description: String? = null
)

@Serializable
data class UpdateBusinessHoursRequest(
    val timezone: String,
    val enabled: Boolean,
    val windows: List<BusinessHoursWindowRequest>
)

@Serializable
data class BusinessHoursWindowRequest(
    val dayOfWeek: Int,
    val startTime: String, // HH:MM:SS format
    val endTime: String
)

fun Route.priorityRoutes() {
    val priorityService = PriorityService()
    val businessHoursService = BusinessHoursService()
    
    route("/priorities") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                val priorities = priorityService.getAllPriorities(organizationId)
                call.respond(priorities)
            }
            
            put {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@put
                }
                
                val request = call.receive<UpdatePriorityRequest>()
                
                try {
                    val priority = priorityService.updatePriority(
                        organizationId = organizationId,
                        severity = request.severity,
                        priorityLevel = request.priorityLevel,
                        isPageable = request.isPageable,
                        label = request.label,
                        description = request.description
                    )
                    
                    if (priority != null) {
                        call.respond(priority)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Priority not found"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
        }
    }
    
    route("/business-hours") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                val config = businessHoursService.getBusinessHours(organizationId)
                if (config != null) {
                    call.respond(config)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Business hours not configured"))
                }
            }
            
            put {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@put
                }
                
                val request = call.receive<UpdateBusinessHoursRequest>()
                
                try {
                    val windows = request.windows.map { w ->
                        BusinessHoursWindow(
                            dayOfWeek = w.dayOfWeek,
                            startTime = LocalTime.parse(w.startTime),
                            endTime = LocalTime.parse(w.endTime)
                        )
                    }
                    
                    val config = businessHoursService.updateBusinessHours(
                        organizationId = organizationId,
                        timezone = request.timezone,
                        enabled = request.enabled,
                        windows = windows
                    )
                    call.respond(config)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
        }
    }
}
