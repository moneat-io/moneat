// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.routes

import com.moneat.auth.currentOrgIdOrNull
import com.moneat.enterprise.oncall.overrideResourceId
import com.moneat.enterprise.oncall.models.OnCallOverrides
import com.moneat.enterprise.oncall.models.OnCallScheduleUsergroups
import com.moneat.enterprise.oncall.services.OnCallScheduleService
import com.moneat.enterprise.oncall.services.ScheduleLayerDefinition
import com.moneat.enterprise.oncall.services.ScheduleLayerUpdate
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.OnCallParticipants
import com.moneat.shared.models.OnCallScheduleLayers
import com.moneat.shared.models.Users
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val WEEKLY_ROTATION_DAYS = 7L
private const val INVALID_TOKEN_MESSAGE = "Invalid token"
private const val SCHEDULE_NOT_FOUND_MESSAGE = "Schedule not found"
private const val OVERRIDE_NOT_FOUND_MESSAGE = "Override not found"
private const val LAYER_NOT_FOUND_MESSAGE = "Layer not found"

@Serializable
data class ScheduleTimelineEntry(
    val userId: String,
    val userName: String,
    val startAt: String,
    val endAt: String,
    val isOverride: Boolean,
    val overrideId: String? = null,
)

@Serializable
data class ScheduleInfo(
    val id: String,
    val name: String,
    val rotationType: String,
    val timezone: String,
)

@Serializable
data class ScheduleTimelineResponse(
    val schedule: ScheduleInfo,
    val entries: List<ScheduleTimelineEntry>,
)

@Serializable
data class ScheduleParticipant(
    val userId: String,
    val position: Int,
)

@Serializable
data class CreateScheduleRequest(
    val name: String,
    val rotationType: String,
    val handoffTime: String, // HH:MM:SS format
    val timezone: String,
    val participants: List<ScheduleParticipant>,
)

@Serializable
data class UpdateScheduleRequest(
    val name: String? = null,
    val rotationType: String? = null,
    val handoffTime: String? = null,
    val timezone: String? = null,
    val participants: List<ScheduleParticipant>? = null,
)

@Serializable
data class CreateOverrideRequest(
    val userId: String,
    val startAt: String, // ISO 8601 timestamp
    val endAt: String, // ISO 8601 timestamp
)

@Serializable
data class CreateScheduleLayerRequest(
    val name: String,
    val layerOrder: Int,
    val rotationType: String,
    val handoffTime: String,
    val timezone: String,
    val enabled: Boolean = true,
    val explicitGap: Boolean = false,
    val participants: List<ScheduleParticipant> = emptyList(),
)

@Serializable
data class UpdateScheduleLayerRequest(
    val name: String? = null,
    val layerOrder: Int? = null,
    val rotationType: String? = null,
    val handoffTime: String? = null,
    val timezone: String? = null,
    val enabled: Boolean? = null,
    val explicitGap: Boolean? = null,
    val participants: List<ScheduleParticipant>? = null,
)

private data class TimelineParticipant(
    val userId: Int,
    val userResourceId: String,
    val userName: String,
)

@Serializable
data class SetScheduleUsergroupRequest(
    val usergroupId: String,
    val usergroupHandle: String,
)

fun Route.onCallRoutes(
    getSlackUserGroupSyncService: (() -> com.moneat.enterprise.oncall.services.SlackUserGroupSyncService?)? = null,
    getPushNotificationService: (() -> com.moneat.enterprise.oncall.services.PushNotificationService)? = null,
) {
    val scheduleService = OnCallScheduleService()
    val slackUserGroupSyncService = getSlackUserGroupSyncService?.invoke()
    val pushNotificationService = getPushNotificationService?.invoke()

    route("/v1/on-call/schedules") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@get
                }

                val schedules = scheduleService.listSchedules(organizationId)
                call.respond(schedules)
            }

            get("/responders") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@get
                }
                val rawScheduleIds =
                    call.request.queryParameters.getAll("scheduleId")
                        .orEmpty()
                        .flatMap { it.split(',') }
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                if (rawScheduleIds.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("At least one scheduleId is required"))
                    return@get
                }
                val scheduleResourceIds = rawScheduleIds.mapNotNull { it.toUuidOrNull() }
                if (scheduleResourceIds.size != rawScheduleIds.size) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid schedule ID"))
                    return@get
                }
                val scheduleIds =
                    transaction {
                        OnCallSchedules
                            .selectAll()
                            .where {
                                (OnCallSchedules.organizationId eq organizationId) and
                                    (OnCallSchedules.resourceId inList scheduleResourceIds)
                            }
                            .map { it[OnCallSchedules.id].value }
                    }
                val all = call.request.queryParameters["all"]?.toBooleanStrictOrNull() ?: true
                val responders = scheduleService.resolveCurrentResponders(organizationId, scheduleIds, all = all)
                if (responders.isEmpty()) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("No on-call responders found"))
                } else {
                    call.respond(responders)
                }
            }

            post {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@post
                }

                val request = call.receive<CreateScheduleRequest>()

                try {
                    val handoffTime = LocalTime.parse(request.handoffTime)
                    val participantIds =
                        request.participants
                            .sortedBy { it.position }
                            .map { resolveOnCallUserId(organizationId, it.userId) }
                    val schedule =
                        scheduleService.createSchedule(
                            organizationId = organizationId,
                            name = request.name,
                            rotationType = request.rotationType,
                            handoffTime = handoffTime,
                            timezone = request.timezone,
                            participantIds = participantIds,
                        )
                    call.respond(HttpStatusCode.Created, schedule)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }

            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val scheduleId = call.resolveScheduleId(organizationId)

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@get
                }

                if (scheduleId == null) {
                    return@get
                }

                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(SCHEDULE_NOT_FOUND_MESSAGE))
                    return@get
                }

                val schedule = scheduleService.getSchedule(scheduleId)
                if (schedule != null) {
                    call.respond(schedule)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(SCHEDULE_NOT_FOUND_MESSAGE))
                }
            }

            put("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val scheduleId = call.resolveScheduleId(organizationId)

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@put
                }

                if (scheduleId == null) {
                    return@put
                }

                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(SCHEDULE_NOT_FOUND_MESSAGE))
                    return@put
                }

                val request = call.receive<UpdateScheduleRequest>()

                try {
                    val handoffTime = request.handoffTime?.let { LocalTime.parse(it) }
                    val participantIds =
                        request.participants
                            ?.sortedBy { it.position }
                            ?.map { resolveOnCallUserId(organizationId, it.userId) }
                    val schedule =
                        scheduleService.updateSchedule(
                            scheduleId = scheduleId,
                            name = request.name,
                            rotationType = request.rotationType,
                            handoffTime = handoffTime,
                            timezone = request.timezone,
                            participantIds = participantIds,
                        )

                    if (schedule != null) {
                        call.respond(schedule)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(SCHEDULE_NOT_FOUND_MESSAGE))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }

            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val scheduleId = call.resolveScheduleId(organizationId)

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@delete
                }

                if (scheduleId == null) {
                    return@delete
                }

                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(SCHEDULE_NOT_FOUND_MESSAGE))
                    return@delete
                }

                val deleted = scheduleService.deleteSchedule(scheduleId)
                if (deleted) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(SCHEDULE_NOT_FOUND_MESSAGE))
                }
            }

            get("/{id}/current") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val scheduleId = call.resolveScheduleId(organizationId)

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@get
                }

                if (scheduleId == null) {
                    return@get
                }

                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(SCHEDULE_NOT_FOUND_MESSAGE))
                    return@get
                }

                val currentOnCall = scheduleService.getCurrentOnCall(scheduleId)
                if (currentOnCall != null) {
                    call.respond(currentOnCall)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("No on-call user found"))
                }
            }

            get("/{id}/responders") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val scheduleId = call.resolveScheduleId(organizationId)

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@get
                }
                if (scheduleId == null) return@get
                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(SCHEDULE_NOT_FOUND_MESSAGE))
                    return@get
                }

                val all = call.request.queryParameters["all"]?.toBooleanStrictOrNull() ?: true
                val responders = scheduleService.resolveCurrentResponders(
                    organizationId = organizationId,
                    scheduleIds = listOf(scheduleId),
                    all = all,
                )
                if (responders.isEmpty()) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("No on-call responders found"))
                } else {
                    call.respond(responders)
                }
            }

            get("/{id}/layers") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val scheduleId = call.resolveScheduleId(organizationId)
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@get
                }
                if (scheduleId == null) return@get
                call.respond(scheduleService.listLayers(organizationId, scheduleId))
            }

            post("/{id}/layers") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val scheduleId = call.resolveScheduleId(organizationId)
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@post
                }
                if (scheduleId == null) return@post
                val request = call.receive<CreateScheduleLayerRequest>()
                try {
                    val layer =
                        scheduleService.createLayer(
                            organizationId = organizationId,
                            scheduleId = scheduleId,
                            definition =
                                ScheduleLayerDefinition(
                                    name = request.name,
                                    layerOrder = request.layerOrder,
                                    rotationType = request.rotationType,
                                    handoffTime = LocalTime.parse(request.handoffTime),
                                    timezone = request.timezone,
                                    enabled = request.enabled,
                                    explicitGap = request.explicitGap,
                                    participantIds =
                                        request.participants
                                            .sortedBy { it.position }
                                            .map { resolveOnCallUserId(organizationId, it.userId) },
                                ),
                        )
                    call.respond(HttpStatusCode.Created, layer)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }

            put("/{id}/layers/{layerId}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val scheduleId = call.resolveScheduleId(organizationId)
                val layerId = call.resolveLayerId(organizationId, scheduleId)
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@put
                }
                if (scheduleId == null || layerId == null) return@put
                val request = call.receive<UpdateScheduleLayerRequest>()
                try {
                    val layer =
                        scheduleService.updateLayer(
                            organizationId = organizationId,
                            scheduleId = scheduleId,
                            layerId = layerId,
                            update =
                                ScheduleLayerUpdate(
                                    name = request.name,
                                    layerOrder = request.layerOrder,
                                    rotationType = request.rotationType,
                                    handoffTime = request.handoffTime?.let(LocalTime::parse),
                                    timezone = request.timezone,
                                    enabled = request.enabled,
                                    explicitGap = request.explicitGap,
                                    participantIds =
                                        request.participants
                                            ?.sortedBy { it.position }
                                            ?.map { resolveOnCallUserId(organizationId, it.userId) },
                                ),
                        )
                    if (layer == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(LAYER_NOT_FOUND_MESSAGE))
                    } else {
                        call.respond(layer)
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }

            delete("/{id}/layers/{layerId}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val scheduleId = call.resolveScheduleId(organizationId)
                val layerId = call.resolveLayerId(organizationId, scheduleId)
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@delete
                }
                if (scheduleId == null || layerId == null) return@delete
                if (scheduleService.deleteLayer(organizationId, scheduleId, layerId)) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(LAYER_NOT_FOUND_MESSAGE))
                }
            }

            get("/{id}/timeline") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val scheduleId = call.resolveScheduleId(organizationId)
                val startParam = call.request.queryParameters["start"]
                val endParam = call.request.queryParameters["end"]

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@get
                }
                if (scheduleId == null) {
                    return@get
                }
                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(SCHEDULE_NOT_FOUND_MESSAGE))
                    return@get
                }

                val rangeStart = try {
                    startParam?.let { Instant.parse(it) } ?: Clock.System.now()
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid 'start' timestamp"))
                    return@get
                }
                val rangeEnd = try {
                    endParam?.let { Instant.parse(it) } ?: rangeStart.plus(30.days)
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid 'end' timestamp"))
                    return@get
                }

                val timeline = buildScheduleTimeline(scheduleId, rangeStart, rangeEnd)
                if (timeline == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(SCHEDULE_NOT_FOUND_MESSAGE))
                } else {
                    call.respond(timeline)
                }
            }

            post("/{id}/overrides") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val scheduleId = call.resolveScheduleId(organizationId)

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@post
                }

                if (scheduleId == null) {
                    return@post
                }

                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@post
                }

                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(SCHEDULE_NOT_FOUND_MESSAGE))
                    return@post
                }

                val request = call.receive<CreateOverrideRequest>()

                try {
                    val startAt = Instant.parse(request.startAt)
                    val endAt = Instant.parse(request.endAt)
                    val overrideUserId = resolveOnCallUserId(organizationId, request.userId)

                    val override =
                        scheduleService.createOverride(
                            scheduleId = scheduleId,
                            userId = overrideUserId,
                            startAt = startAt,
                            endAt = endAt,
                            createdBy = userId,
                        )

                    // Notify override user if they are on-call immediately
                    val now = Clock.System.now()
                    if (startAt <= now && endAt > now) {
                        val schedule = scheduleService.getSchedule(scheduleId)
                        if (schedule != null && pushNotificationService != null) {
                            CoroutineScope(Dispatchers.IO).launch {
                                pushNotificationService.sendOnCallAssignmentAlert(overrideUserId, schedule.name)
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
                val organizationId = principal?.currentOrgIdOrNull()
                val overrideId = call.resolveOverrideId(organizationId)

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@delete
                }

                if (overrideId == null) {
                    return@delete
                }

                if (!scheduleService.isOverrideInOrganization(overrideId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(OVERRIDE_NOT_FOUND_MESSAGE))
                    return@delete
                }

                val deleted = scheduleService.deleteOverride(overrideId)
                if (deleted) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(OVERRIDE_NOT_FOUND_MESSAGE))
                }
            }
        }
    }

    // Slack usergroup mapping endpoints
    route("/v1/on-call/schedules/{id}/slack-usergroup") {
        authenticate("auth-jwt") {
            put {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val scheduleId = call.resolveScheduleId(organizationId)

                if (organizationId == null || userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@put
                }

                if (scheduleId == null) {
                    return@put
                }

                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(SCHEDULE_NOT_FOUND_MESSAGE))
                    return@put
                }

                val request = call.receive<SetScheduleUsergroupRequest>()

                try {
                    org.jetbrains.exposed.v1.jdbc.transactions.transaction {
                        val now = Clock.System.now()

                        // Check if mapping already exists
                        val existing =
                            OnCallScheduleUsergroups
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
                val organizationId = principal?.currentOrgIdOrNull()
                val scheduleId = call.resolveScheduleId(organizationId)

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                    return@delete
                }

                if (scheduleId == null) {
                    return@delete
                }

                if (!scheduleService.isScheduleInOrganization(scheduleId, organizationId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(SCHEDULE_NOT_FOUND_MESSAGE))
                    return@delete
                }

                try {
                    val deleted =
                        org.jetbrains.exposed.v1.jdbc.transactions.transaction {
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
                val twilioService = com.moneat.enterprise.oncall.services.TwilioService.instance

                @kotlinx.serialization.Serializable
                data class CallerNumberResponse(
                    val phoneNumber: String?,
                )
                val number = if (twilioService.isEnabled()) twilioService.getFromNumber() else null
                call.respond(CallerNumberResponse(number))
            }
        }
    }
}

private fun buildScheduleTimeline(
    scheduleId: Int,
    rangeStart: Instant,
    rangeEnd: Instant,
): ScheduleTimelineResponse? = transaction {
    val scheduleRow =
        OnCallSchedules
            .selectAll()
            .where { OnCallSchedules.id eq scheduleId }
            .singleOrNull() ?: return@transaction null

    val participants =
        OnCallParticipants
            .innerJoin(Users)
            .selectAll()
            .where { OnCallParticipants.scheduleId eq scheduleId }
            .orderBy(OnCallParticipants.position to SortOrder.ASC)
            .map { row ->
                val userId = row[OnCallParticipants.userId]
                TimelineParticipant(
                    userId = userId,
                    userResourceId = row[Users.resource_id].toString(),
                    userName = row[Users.name] ?: row[Users.email],
                )
            }

    if (participants.isEmpty()) {
        return@transaction ScheduleTimelineResponse(
            schedule = ScheduleInfo(
                id = scheduleRow[OnCallSchedules.resourceId].toString(),
                name = scheduleRow[OnCallSchedules.name],
                rotationType = scheduleRow[OnCallSchedules.rotationType],
                timezone = scheduleRow[OnCallSchedules.timezone],
            ),
            entries = emptyList(),
        )
    }

    val rotationType = scheduleRow[OnCallSchedules.rotationType]
    val rotationDays = when (rotationType) { "DAILY" -> 1L; else -> WEEKLY_ROTATION_DAYS }
    val zoneId = ZoneId.of(scheduleRow[OnCallSchedules.timezone])
    val handoffLocalTime = scheduleRow[OnCallSchedules.handoffTime]

    // Build rotation entries spanning the requested range, period by period
    val entries = mutableListOf<ScheduleTimelineEntry>()

    // Find rotation period containing rangeStart
    val startZoned =
        java.time.Instant
            .ofEpochMilli(rangeStart.toEpochMilliseconds())
            .atZone(zoneId)
    val startRotationDate =
        if (startZoned.toLocalTime().isBefore(handoffLocalTime)) {
            startZoned.toLocalDate().minusDays(1)
        } else {
            startZoned.toLocalDate()
        }
    val startDaysSinceEpoch = ChronoUnit.DAYS.between(LocalDate.EPOCH, startRotationDate)
    var periodStartDays = (startDaysSinceEpoch / rotationDays) * rotationDays

    val endEpochMs = rangeEnd.toEpochMilliseconds()

    while (true) {
        val periodStart =
            LocalDate.EPOCH
                .plusDays(periodStartDays)
                .atTime(handoffLocalTime)
                .atZone(zoneId)
                .toInstant()
        if (periodStart.toEpochMilli() >= endEpochMs) break

        val periodEnd =
            LocalDate.EPOCH
                .plusDays(periodStartDays + rotationDays)
                .atTime(handoffLocalTime)
                .atZone(zoneId)
                .toInstant()
        val rotationCycle = ((periodStartDays / rotationDays) % participants.size).toInt()
        val participant = participants[rotationCycle]

        val entryStart = maxOf(periodStart, java.time.Instant.ofEpochMilli(rangeStart.toEpochMilliseconds()))
        val entryEnd = minOf(periodEnd, java.time.Instant.ofEpochMilli(endEpochMs))

        entries.add(
            ScheduleTimelineEntry(
                userId = participant.userResourceId,
                userName = participant.userName,
                startAt = entryStart.toString(),
                endAt = entryEnd.toString(),
                isOverride = false,
            ),
        )
        periodStartDays += rotationDays
    }

    // Overlay overrides: remove rotation entries that overlap, insert override entries
    val overrides =
        OnCallOverrides
            .join(Users, JoinType.INNER, onColumn = OnCallOverrides.userId, otherColumn = Users.id)
            .selectAll()
            .where {
                (OnCallOverrides.scheduleId eq scheduleId) and
                    (OnCallOverrides.startAt less rangeEnd) and
                    (OnCallOverrides.endAt greater rangeStart)
            }
            .orderBy(OnCallOverrides.startAt to SortOrder.ASC)
            .map { row ->
                ScheduleTimelineEntry(
                    userId = row[Users.resource_id].toString(),
                    userName = row[Users.name] ?: row[Users.email],
                    startAt = row[OnCallOverrides.startAt].toString(),
                    endAt = row[OnCallOverrides.endAt].toString(),
                    isOverride = true,
                    overrideId = overrideResourceId(row[OnCallOverrides.id].value),
                )
            }

    // Merge: split/trim rotation entries around overrides and add override entries
    val merged = mutableListOf<ScheduleTimelineEntry>()
    for (rotEntry in entries) {
        var current: ScheduleTimelineEntry? = rotEntry
        for (ov in overrides) {
            val c = current ?: break
            val ovStart = java.time.Instant.parse(ov.startAt)
            val ovEnd = java.time.Instant.parse(ov.endAt)
            val cStart = java.time.Instant.parse(c.startAt)
            val cEnd = java.time.Instant.parse(c.endAt)
            // Trim or split the rotation entry around this override
            if (ovEnd <= cStart || ovStart >= cEnd) continue // no overlap
            if (ovStart > cStart) {
                merged.add(c.copy(endAt = ovStart.toString()))
            }
            current = if (ovEnd < cEnd) c.copy(startAt = ovEnd.toString()) else null
        }
        current?.let { merged.add(it) }
    }
    merged.addAll(overrides)
    merged.sortBy { it.startAt }

    ScheduleTimelineResponse(
        schedule = ScheduleInfo(
            id = scheduleRow[OnCallSchedules.resourceId].toString(),
            name = scheduleRow[OnCallSchedules.name],
            rotationType = scheduleRow[OnCallSchedules.rotationType],
            timezone = scheduleRow[OnCallSchedules.timezone],
        ),
        entries = merged,
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.resolveScheduleId(organizationId: Int?): Int? {
    if (organizationId == null) return null
    val resourceId = parseOnCallResourceId(parameters["id"])
    if (resourceId == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid schedule ID"))
        return null
    }
    val scheduleId =
        transaction {
            OnCallSchedules
                .selectAll()
                .where {
                    (OnCallSchedules.organizationId eq organizationId) and
                        (OnCallSchedules.resourceId eq resourceId)
                }
                .firstOrNull()
                ?.get(OnCallSchedules.id)
                ?.value
        }
    if (scheduleId == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse(SCHEDULE_NOT_FOUND_MESSAGE))
    }
    return scheduleId
}

private suspend fun io.ktor.server.application.ApplicationCall.resolveLayerId(
    organizationId: Int?,
    scheduleId: Int?,
): Int? {
    if (organizationId == null || scheduleId == null) return null
    val resourceId = parameters["layerId"]?.toUuidOrNull()
    if (resourceId == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid layer ID"))
        return null
    }
    val layerId =
        transaction {
            OnCallScheduleLayers
                .selectAll()
                .where {
                    (OnCallScheduleLayers.organizationId eq organizationId) and
                        (OnCallScheduleLayers.scheduleId eq scheduleId) and
                        (OnCallScheduleLayers.resourceId eq resourceId)
                }
                .firstOrNull()
                ?.get(OnCallScheduleLayers.id)
                ?.value
        }
    if (layerId == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse(LAYER_NOT_FOUND_MESSAGE))
    }
    return layerId
}

private suspend fun io.ktor.server.application.ApplicationCall.resolveOverrideId(organizationId: Int?): Int? {
    if (organizationId == null) return null
    val resourceId = parseOnCallResourceId(parameters["id"])
    if (resourceId == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid override ID"))
        return null
    }
    val overrideId =
        transaction {
            OnCallOverrides
                .innerJoin(OnCallSchedules)
                .selectAll()
                .where {
                    (OnCallOverrides.resourceId eq resourceId) and
                        (OnCallSchedules.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.get(OnCallOverrides.id)
                ?.value
        }
    if (overrideId == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse(OVERRIDE_NOT_FOUND_MESSAGE))
    }
    return overrideId
}

private fun parseOnCallResourceId(raw: String?): Uuid? =
    raw?.toUuidOrNull()

private fun resolveOnCallUserId(organizationId: Int, raw: String): Int {
    val resourceId =
        raw.toUuidOrNull() ?: throw IllegalArgumentException("Invalid user ID")
    return transaction {
        Users
            .innerJoin(Memberships)
            .selectAll()
            .where {
                (Users.resource_id eq resourceId) and
                    (Memberships.organization_id eq organizationId)
            }
            .firstOrNull()
            ?.get(Users.id)
    } ?: throw IllegalArgumentException("User not found")
}
