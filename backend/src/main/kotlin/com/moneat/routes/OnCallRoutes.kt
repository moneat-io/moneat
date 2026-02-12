package com.moneat.routes

import com.moneat.services.oncall.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.time.LocalTime

@Serializable
data class CreateScheduleRequest(
    val name: String,
    val rotationType: String,
    val handoffTime: String, // HH:MM:SS format
    val timezone: String,
    val participantIds: List<Int>
)

@Serializable
data class UpdateScheduleRequest(
    val name: String? = null,
    val rotationType: String? = null,
    val handoffTime: String? = null,
    val timezone: String? = null,
    val participantIds: List<Int>? = null
)

@Serializable
data class CreateOverrideRequest(
    val userId: Int,
    val startAt: String, // ISO 8601 timestamp
    val endAt: String // ISO 8601 timestamp
)

fun Route.onCallRoutes() {
    val scheduleService = OnCallScheduleService()
    
    route("/v1/on-call/schedules") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                val schedules = scheduleService.listSchedules(organizationId)
                call.respond(schedules)
            }
            
            post {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                val request = call.receive<CreateScheduleRequest>()
                
                try {
                    val handoffTime = LocalTime.parse(request.handoffTime)
                    val schedule = scheduleService.createSchedule(
                        organizationId = organizationId,
                        name = request.name,
                        rotationType = request.rotationType,
                        handoffTime = handoffTime,
                        timezone = request.timezone,
                        participantIds = request.participantIds
                    )
                    call.respond(HttpStatusCode.Created, schedule)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
            
            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val scheduleId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                if (scheduleId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid schedule ID"))
                    return@get
                }
                
                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Schedule not found"))
                    return@get
                }
                
                val schedule = scheduleService.getSchedule(scheduleId)
                if (schedule != null) {
                    call.respond(schedule)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Schedule not found"))
                }
            }
            
            put("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val scheduleId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@put
                }
                
                if (scheduleId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid schedule ID"))
                    return@put
                }
                
                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Schedule not found"))
                    return@put
                }
                
                val request = call.receive<UpdateScheduleRequest>()
                
                try {
                    val handoffTime = request.handoffTime?.let { LocalTime.parse(it) }
                    val schedule = scheduleService.updateSchedule(
                        scheduleId = scheduleId,
                        name = request.name,
                        rotationType = request.rotationType,
                        handoffTime = handoffTime,
                        timezone = request.timezone,
                        participantIds = request.participantIds
                    )
                    
                    if (schedule != null) {
                        call.respond(schedule)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Schedule not found"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
            
            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val scheduleId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@delete
                }
                
                if (scheduleId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid schedule ID"))
                    return@delete
                }
                
                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Schedule not found"))
                    return@delete
                }
                
                val deleted = scheduleService.deleteSchedule(scheduleId)
                if (deleted) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Schedule not found"))
                }
            }
            
            get("/{id}/current") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val scheduleId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                if (scheduleId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid schedule ID"))
                    return@get
                }
                
                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Schedule not found"))
                    return@get
                }
                
                val currentOnCall = scheduleService.getCurrentOnCall(scheduleId)
                if (currentOnCall != null) {
                    call.respond(currentOnCall)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "No on-call user found"))
                }
            }
            
            post("/{id}/overrides") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val scheduleId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                if (scheduleId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid schedule ID"))
                    return@post
                }
                
                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Schedule not found"))
                    return@post
                }
                
                val request = call.receive<CreateOverrideRequest>()
                
                try {
                    val startAt = Instant.parse(request.startAt)
                    val endAt = Instant.parse(request.endAt)
                    
                    val override = scheduleService.createOverride(
                        scheduleId = scheduleId,
                        userId = request.userId,
                        startAt = startAt,
                        endAt = endAt,
                        createdBy = userId
                    )
                    call.respond(HttpStatusCode.Created, override)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
        }
    }
    
    route("/on-call/overrides") {
        authenticate("auth-jwt") {
            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val overrideId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@delete
                }
                
                if (overrideId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid override ID"))
                    return@delete
                }
                
                if (!scheduleService.isOverrideInOrganization(overrideId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Override not found"))
                    return@delete
                }
                
                val deleted = scheduleService.deleteOverride(overrideId)
                if (deleted) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Override not found"))
                }
            }
        }
    }
}
