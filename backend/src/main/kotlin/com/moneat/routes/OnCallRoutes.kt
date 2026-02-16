package com.moneat.routes

import com.moneat.models.OnCallScheduleUsergroups
import com.moneat.services.oncall.*
import io.ktor.http.*
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import com.moneat.utils.BooleanResponse
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalTime

@Serializable
data class ScheduleParticipant(
    val userId: Int,
    val position: Int
)

@Serializable
data class CreateScheduleRequest(
    val name: String,
    val rotationType: String,
    val handoffTime: String, // HH:MM:SS format
    val timezone: String,
    val participants: List<ScheduleParticipant>
)

@Serializable
data class UpdateScheduleRequest(
    val name: String? = null,
    val rotationType: String? = null,
    val handoffTime: String? = null,
    val timezone: String? = null,
    val participants: List<ScheduleParticipant>? = null
)

@Serializable
data class CreateOverrideRequest(
    val userId: Int,
    val startAt: String, // ISO 8601 timestamp
    val endAt: String // ISO 8601 timestamp
)

@Serializable
data class SetScheduleUsergroupRequest(
    val usergroupId: String,
    val usergroupHandle: String
)

fun Route.onCallRoutes(
    getSlackUserGroupSyncService: (() -> com.moneat.services.oncall.SlackUserGroupSyncService?)? = null,
    getPushNotificationService: (() -> com.moneat.services.oncall.PushNotificationService)? = null
) {
    val scheduleService = OnCallScheduleService()
    val slackUserGroupSyncService = getSlackUserGroupSyncService?.invoke()
    val pushNotificationService = getPushNotificationService?.invoke()
    
    route("/v1/on-call/schedules") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }
                
                val schedules = scheduleService.listSchedules(organizationId)
                call.respond(schedules)
            }
            
            post {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }
                
                val request = call.receive<CreateScheduleRequest>()
                
                try {
                    val handoffTime = LocalTime.parse(request.handoffTime)
                    val participantIds = request.participants
                        .sortedBy { it.position }
                        .map { it.userId }
                    val schedule = scheduleService.createSchedule(
                        organizationId = organizationId,
                        name = request.name,
                        rotationType = request.rotationType,
                        handoffTime = handoffTime,
                        timezone = request.timezone,
                        participantIds = participantIds
                    )
                    call.respond(HttpStatusCode.Created, schedule)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }
            
            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val scheduleId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }
                
                if (scheduleId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid schedule ID"))
                    return@get
                }
                
                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Schedule not found"))
                    return@get
                }
                
                val schedule = scheduleService.getSchedule(scheduleId)
                if (schedule != null) {
                    call.respond(schedule)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Schedule not found"))
                }
            }
            
            put("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val scheduleId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@put
                }
                
                if (scheduleId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid schedule ID"))
                    return@put
                }
                
                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Schedule not found"))
                    return@put
                }
                
                val request = call.receive<UpdateScheduleRequest>()
                
                try {
                    val handoffTime = request.handoffTime?.let { LocalTime.parse(it) }
                    val participantIds = request.participants
                        ?.sortedBy { it.position }
                        ?.map { it.userId }
                    val schedule = scheduleService.updateSchedule(
                        scheduleId = scheduleId,
                        name = request.name,
                        rotationType = request.rotationType,
                        handoffTime = handoffTime,
                        timezone = request.timezone,
                        participantIds = participantIds
                    )
                    
                    if (schedule != null) {
                        call.respond(schedule)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Schedule not found"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }
            
            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val scheduleId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@delete
                }
                
                if (scheduleId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid schedule ID"))
                    return@delete
                }
                
                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Schedule not found"))
                    return@delete
                }
                
                val deleted = scheduleService.deleteSchedule(scheduleId)
                if (deleted) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Schedule not found"))
                }
            }
            
            get("/{id}/current") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val scheduleId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }
                
                if (scheduleId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid schedule ID"))
                    return@get
                }
                
                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Schedule not found"))
                    return@get
                }
                
                val currentOnCall = scheduleService.getCurrentOnCall(scheduleId)
                if (currentOnCall != null) {
                    call.respond(currentOnCall)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("No on-call user found"))
                }
            }
            
            post("/{id}/overrides") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val scheduleId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }
                
                if (scheduleId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid schedule ID"))
                    return@post
                }
                
                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }
                
                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Schedule not found"))
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
                    
                    // Notify override user if they are on-call immediately
                    val now = kotlinx.datetime.Clock.System.now()
                    if (startAt <= now && endAt > now) {
                        val schedule = scheduleService.getSchedule(scheduleId)
                        if (schedule != null && pushNotificationService != null) {
                            CoroutineScope(Dispatchers.IO).launch {
                                pushNotificationService.sendOnCallAssignmentAlert(request.userId, schedule.name)
                            }
                        }
                    }

                    call.respond(HttpStatusCode.Created, override)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }
        }
    }
    
    route("/v1/on-call/overrides") {
        authenticate("auth-jwt") {
            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val overrideId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@delete
                }
                
                if (overrideId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid override ID"))
                    return@delete
                }
                
                if (!scheduleService.isOverrideInOrganization(overrideId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Override not found"))
                    return@delete
                }
                
                val deleted = scheduleService.deleteOverride(overrideId)
                if (deleted) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Override not found"))
                }
            }
        }
    }
    
    // Slack usergroup mapping endpoints
    route("/v1/on-call/schedules/{id}/slack-usergroup") {
        authenticate("auth-jwt") {
            put {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val scheduleId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null || userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@put
                }
                
                if (scheduleId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid schedule ID"))
                    return@put
                }
                
                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Schedule not found"))
                    return@put
                }
                
                val request = call.receive<SetScheduleUsergroupRequest>()
                
                try {
                    org.jetbrains.exposed.sql.transactions.transaction {
                        val now = kotlinx.datetime.Clock.System.now()
                        
                        // Check if mapping already exists
                        val existing = com.moneat.models.OnCallScheduleUsergroups
                            .selectAll()
                            .where { com.moneat.models.OnCallScheduleUsergroups.scheduleId eq scheduleId }
                            .singleOrNull()
                        
                        if (existing != null) {
                            // Update existing mapping
                            com.moneat.models.OnCallScheduleUsergroups.update({
                                com.moneat.models.OnCallScheduleUsergroups.scheduleId eq scheduleId
                            }) {
                                it[com.moneat.models.OnCallScheduleUsergroups.slackUsergroupId] = request.usergroupId
                                it[com.moneat.models.OnCallScheduleUsergroups.slackUsergroupHandle] = request.usergroupHandle
                                it[com.moneat.models.OnCallScheduleUsergroups.updatedAt] = now
                            }
                        } else {
                            // Insert new mapping
                            com.moneat.models.OnCallScheduleUsergroups.insert {
                                it[com.moneat.models.OnCallScheduleUsergroups.scheduleId] = scheduleId
                                it[com.moneat.models.OnCallScheduleUsergroups.slackUsergroupId] = request.usergroupId
                                it[com.moneat.models.OnCallScheduleUsergroups.slackUsergroupHandle] = request.usergroupHandle
                                it[com.moneat.models.OnCallScheduleUsergroups.createdAt] = now
                                it[com.moneat.models.OnCallScheduleUsergroups.updatedAt] = now
                            }
                        }
                    }
                    
                    // Trigger immediate sync if service is available
                    if (slackUserGroupSyncService != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            slackUserGroupSyncService.syncScheduleNow(scheduleId)
                        }
                    }
                    
                    call.respond(HttpStatusCode.OK, MessageResponse("Slack usergroup mapping updated"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }
            
            delete {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val scheduleId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@delete
                }
                
                if (scheduleId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid schedule ID"))
                    return@delete
                }
                
                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Schedule not found"))
                    return@delete
                }
                
                try {
                    val deleted = org.jetbrains.exposed.sql.transactions.transaction {
                        com.moneat.models.OnCallScheduleUsergroups.deleteWhere {
                            com.moneat.models.OnCallScheduleUsergroups.scheduleId eq scheduleId
                        }
                    }
                    
                    if (deleted > 0) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("No mapping found"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }
        }
    }
}
