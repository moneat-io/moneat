// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.routes

import com.moneat.enterprise.models.OnCallScheduleUsergroups
import com.moneat.enterprise.services.oncall.*
import io.ktor.http.HttpStatusCode
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import com.moneat.utils.BooleanResponse
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*

import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.core.and
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
    getSlackUserGroupSyncService: (() -> com.moneat.enterprise.services.oncall.SlackUserGroupSyncService?)? = null,
    getPushNotificationService: (() -> com.moneat.enterprise.services.oncall.PushNotificationService)? = null
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
                    val now = Clock.System.now()
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
                    org.jetbrains.exposed.v1.jdbc.transactions.transaction {
                        val now = Clock.System.now()
                        
                        // Check if mapping already exists
                        val existing = OnCallScheduleUsergroups
                            .selectAll()
                            .where { OnCallScheduleUsergroups.scheduleId eq scheduleId }
                            .singleOrNull()
                        
                        if (existing != null) {
                            // Update existing mapping
                            OnCallScheduleUsergroups.update({
                                OnCallScheduleUsergroups.scheduleId eq scheduleId
                            }) {
                                it[OnCallScheduleUsergroups.slackUsergroupId] = request.usergroupId
                                it[OnCallScheduleUsergroups.slackUsergroupHandle] = request.usergroupHandle
                                it[OnCallScheduleUsergroups.updatedAt] = now
                            }
                        } else {
                            // Insert new mapping
                            OnCallScheduleUsergroups.insert {
                                it[OnCallScheduleUsergroups.scheduleId] = scheduleId
                                it[OnCallScheduleUsergroups.slackUsergroupId] = request.usergroupId
                                it[OnCallScheduleUsergroups.slackUsergroupHandle] = request.usergroupHandle
                                it[OnCallScheduleUsergroups.createdAt] = now
                                it[OnCallScheduleUsergroups.updatedAt] = now
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
                    val deleted = org.jetbrains.exposed.v1.jdbc.transactions.transaction {
                        OnCallScheduleUsergroups.deleteWhere {
                            OnCallScheduleUsergroups.scheduleId eq scheduleId
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

    // Returns the Twilio from-number so mobile apps can save it as a contact
    route("/v1/on-call/caller-number") {
        authenticate("auth-jwt") {
            get {
                val twilioService = com.moneat.enterprise.services.oncall.TwilioService.instance
                @kotlinx.serialization.Serializable
                data class CallerNumberResponse(val phoneNumber: String?)
                call.respond(CallerNumberResponse(if (twilioService.isEnabled()) twilioService.getFromNumber() else null))
            }
        }
    }
}
