// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.enterprise.oncall.organizationResourceId
import com.moneat.enterprise.oncall.overrideResourceId
import com.moneat.enterprise.oncall.requireValue
import com.moneat.enterprise.oncall.scheduleResourceId
import com.moneat.enterprise.oncall.userResourceId
import com.moneat.enterprise.oncall.userResourceIds
import com.moneat.enterprise.oncall.models.OnCallOverride
import com.moneat.enterprise.oncall.models.OnCallOverrides
import com.moneat.enterprise.oncall.models.OnCallParticipant
import com.moneat.enterprise.oncall.models.OnCallSchedule
import com.moneat.enterprise.oncall.models.OnCallScheduleLayer
import com.moneat.enterprise.oncall.models.OnCallScheduleUsergroups
import com.moneat.enterprise.oncall.models.OnCallResponderResolution
import com.moneat.shared.models.OnCallParticipants
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.OnCallScheduleLayers
import com.moneat.shared.models.OnCallScheduleLayerParticipants
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.time.Clock
import kotlin.time.Instant

private const val WEEKLY_ROTATION_DAYS = 7

data class ScheduleLayerDefinition(
    val name: String,
    val layerOrder: Int,
    val rotationType: String,
    val handoffTime: LocalTime,
    val timezone: String,
    val enabled: Boolean,
    val explicitGap: Boolean,
    val participantIds: List<Int>,
)

data class ScheduleLayerUpdate(
    val name: String? = null,
    val layerOrder: Int? = null,
    val rotationType: String? = null,
    val handoffTime: LocalTime? = null,
    val timezone: String? = null,
    val enabled: Boolean? = null,
    val explicitGap: Boolean? = null,
    val participantIds: List<Int>? = null,
)

private data class ScheduleLayerRecord(
    val model: OnCallScheduleLayer,
    val internalId: Int,
)

internal data class ResolvedResponder(
    val model: OnCallResponderResolution,
    val internalUserId: Int,
) {
    fun asParticipant(): OnCallParticipant =
        OnCallParticipant(
            id = model.userId,
            userResourceId = model.userId,
            userName = model.userName,
            userEmail = model.userEmail,
            position = -1,
            userId = internalUserId,
        )
}

class OnCallScheduleService {
    fun getOnCallUsedSeats(organizationId: Int): Int =
        transaction {
            val scheduleIds =
                OnCallSchedules
                    .selectAll()
                    .where { OnCallSchedules.organizationId eq organizationId }
                    .map { it[OnCallSchedules.id].value }

            if (scheduleIds.isEmpty()) return@transaction 0

            val participantUserIds =
                OnCallParticipants
                    .selectAll()
                    .where { OnCallParticipants.scheduleId inList scheduleIds }
                    .map { it[OnCallParticipants.userId] }
            val layerIds =
                OnCallScheduleLayers
                    .selectAll()
                    .where { OnCallScheduleLayers.scheduleId inList scheduleIds }
                    .map { it[OnCallScheduleLayers.id].value }
            val layerUserIds =
                if (layerIds.isEmpty()) {
                    emptyList()
                } else {
                    OnCallScheduleLayerParticipants
                        .selectAll()
                        .where { OnCallScheduleLayerParticipants.layerId inList layerIds }
                        .map { it[OnCallScheduleLayerParticipants.userId] }
                }

            (participantUserIds + layerUserIds)
                .distinct()
                .count()
        }

    fun getSchedule(scheduleId: Int): OnCallSchedule? =
        transaction {
            val scheduleRow =
                OnCallSchedules
                    .selectAll()
                    .where { OnCallSchedules.id eq scheduleId }
                    .singleOrNull() ?: return@transaction null

            scheduleResponse(scheduleRow)
        }

    private fun scheduleResponse(
        scheduleRow: ResultRow,
        orgResourceId: String = organizationResourceId(scheduleRow[OnCallSchedules.organizationId]),
    ): OnCallSchedule {
        val scheduleId = scheduleRow[OnCallSchedules.id].value
        val scheduleResourceId = scheduleRow[OnCallSchedules.resourceId].toString()

        val participants =
            OnCallParticipants
                .innerJoin(Users)
                .selectAll()
                .where { OnCallParticipants.scheduleId eq scheduleId }
                .orderBy(OnCallParticipants.position to SortOrder.ASC)
                .toList()
        val participantResponses =
            participants.map { row ->
                OnCallParticipant(
                    id = row[OnCallParticipants.resourceId].toString(),
                    userResourceId = row[Users.resource_id].toString(),
                    userName = row[Users.name] ?: row[Users.email],
                    userEmail = row[Users.email],
                    position = row[OnCallParticipants.position],
                    internalId = row[OnCallParticipants.id].value,
                    userId = row[OnCallParticipants.userId],
                )
            }

        val overrideRows =
            OnCallOverrides
                .join(Users, JoinType.INNER, onColumn = OnCallOverrides.userId, otherColumn = Users.id)
                .selectAll()
                .where { OnCallOverrides.scheduleId eq scheduleId }
                .orderBy(OnCallOverrides.startAt to SortOrder.ASC)
                .toList()
        val createdByResourceIds = userResourceIds(overrideRows.map { row -> row[OnCallOverrides.createdBy] })
        val overrides =
            overrideRows
                .map { row ->
                    OnCallOverride(
                        id = row[OnCallOverrides.resourceId].toString(),
                        scheduleResourceId = scheduleResourceId,
                        userResourceId = row[Users.resource_id].toString(),
                        userName = row[Users.name] ?: row[Users.email],
                        startAt = row[OnCallOverrides.startAt].toString(),
                        endAt = row[OnCallOverrides.endAt].toString(),
                        createdByResourceId = createdByResourceIds.requireValue(
                            row[OnCallOverrides.createdBy],
                            "user",
                        ),
                        createdAt = row[OnCallOverrides.createdAt].toString(),
                        internalId = row[OnCallOverrides.id].value,
                        scheduleId = row[OnCallOverrides.scheduleId],
                        userId = row[OnCallOverrides.userId],
                        createdBy = row[OnCallOverrides.createdBy],
                    )
                }

        val currentOnCall =
            resolveScheduleResponders(
                organizationId = scheduleRow[OnCallSchedules.organizationId],
                schedule = scheduleRow,
                at = Clock.System.now(),
            ).firstOrNull()?.asParticipant()
        val layers = layersForSchedule(scheduleId, scheduleResourceId).map { it.model }

        // Fetch Slack usergroup mapping if it exists
        val usergroupMapping =
            OnCallScheduleUsergroups
                .selectAll()
                .where { OnCallScheduleUsergroups.scheduleId eq scheduleId }
                .singleOrNull()

        return OnCallSchedule(
            id = scheduleResourceId,
            organizationResourceId = orgResourceId,
            name = scheduleRow[OnCallSchedules.name],
            rotationType = scheduleRow[OnCallSchedules.rotationType],
            handoffTime = scheduleRow[OnCallSchedules.handoffTime],
            timezone = scheduleRow[OnCallSchedules.timezone],
            participants = participantResponses,
            overrides = overrides,
            layers = layers,
            currentOnCall = currentOnCall,
            slackUsergroupId = usergroupMapping?.get(OnCallScheduleUsergroups.slackUsergroupId),
            slackUsergroupHandle = usergroupMapping?.get(OnCallScheduleUsergroups.slackUsergroupHandle),
            createdAt = scheduleRow[OnCallSchedules.createdAt].toString(),
            updatedAt = scheduleRow[OnCallSchedules.updatedAt].toString(),
        )
    }

    fun getOnCallAt(scheduleId: Int, at: Instant): OnCallParticipant? =
        transaction {
            val schedule =
                OnCallSchedules
                    .selectAll()
                    .where { OnCallSchedules.id eq scheduleId }
                    .singleOrNull() ?: return@transaction null
            resolveScheduleResponders(schedule[OnCallSchedules.organizationId], schedule, at)
                .firstOrNull()
                ?.asParticipant()
        }

    fun getCurrentOnCall(scheduleId: Int): OnCallParticipant? =
        transaction {
            val now = Clock.System.now()
            val schedule =
                OnCallSchedules
                    .selectAll()
                    .where { OnCallSchedules.id eq scheduleId }
                    .singleOrNull() ?: return@transaction null
            resolveScheduleResponders(schedule[OnCallSchedules.organizationId], schedule, now)
                .firstOrNull()
                ?.asParticipant()
        }

    fun listLayers(
        organizationId: Int,
        scheduleId: Int,
    ): List<OnCallScheduleLayer> =
        transaction {
            val schedule =
                OnCallSchedules
                    .selectAll()
                    .where {
                        (OnCallSchedules.id eq scheduleId) and
                            (OnCallSchedules.organizationId eq organizationId)
                    }
                    .singleOrNull() ?: return@transaction emptyList()
            layersForSchedule(scheduleId, schedule[OnCallSchedules.resourceId].toString()).map { it.model }
        }

    fun createLayer(
        organizationId: Int,
        scheduleId: Int,
        definition: ScheduleLayerDefinition,
    ): OnCallScheduleLayer =
        transaction {
            val schedule =
                OnCallSchedules
                    .selectAll()
                    .where {
                        (OnCallSchedules.id eq scheduleId) and
                            (OnCallSchedules.organizationId eq organizationId)
                    }
                    .singleOrNull() ?: throw IllegalArgumentException("Schedule not found")
            ZoneId.of(definition.timezone)
            require(definition.layerOrder >= 0) { "Layer order must be non-negative" }
            checkSeatLimit(organizationId, definition.participantIds)
            val now = Clock.System.now()
            val layerId =
                OnCallScheduleLayers
                    .insertAndGetId {
                        it[OnCallScheduleLayers.organizationId] = organizationId
                        it[OnCallScheduleLayers.scheduleId] = scheduleId
                        it[OnCallScheduleLayers.name] = definition.name
                        it[OnCallScheduleLayers.layerOrder] = definition.layerOrder
                        it[OnCallScheduleLayers.rotationType] = definition.rotationType
                        it[OnCallScheduleLayers.handoffTime] = definition.handoffTime
                        it[OnCallScheduleLayers.timezone] = definition.timezone
                        it[OnCallScheduleLayers.enabled] = definition.enabled
                        it[OnCallScheduleLayers.explicitGap] = definition.explicitGap
                        it[OnCallScheduleLayers.createdAt] = now
                        it[OnCallScheduleLayers.updatedAt] = now
                    }.value
            insertLayerParticipants(layerId, organizationId, definition.participantIds, now)
            layersForSchedule(scheduleId, schedule[OnCallSchedules.resourceId].toString())
                .single { it.internalId == layerId }
                .model
        }

    fun updateLayer(
        organizationId: Int,
        scheduleId: Int,
        layerId: Int,
        update: ScheduleLayerUpdate,
    ): OnCallScheduleLayer? =
        transaction {
            val schedule =
                OnCallSchedules
                    .selectAll()
                    .where {
                        (OnCallSchedules.id eq scheduleId) and
                            (OnCallSchedules.organizationId eq organizationId)
                    }
                    .singleOrNull() ?: return@transaction null
            val existingLayerId =
                OnCallScheduleLayers
                    .selectAll()
                    .where {
                        (OnCallScheduleLayers.id eq layerId) and
                            (OnCallScheduleLayers.scheduleId eq scheduleId) and
                            (OnCallScheduleLayers.organizationId eq organizationId)
                    }
                    .singleOrNull()
                    ?.get(OnCallScheduleLayers.id)
            if (existingLayerId == null) {
                return@transaction null
            }
            if (update.layerOrder != null) require(update.layerOrder >= 0) { "Layer order must be non-negative" }
            update.timezone?.let { ZoneId.of(it) }
            val now = Clock.System.now()
            OnCallScheduleLayers.update({ OnCallScheduleLayers.id eq layerId }) {
                update.name?.let { value -> it[OnCallScheduleLayers.name] = value }
                update.layerOrder?.let { value -> it[OnCallScheduleLayers.layerOrder] = value }
                update.rotationType?.let { value -> it[OnCallScheduleLayers.rotationType] = value }
                update.handoffTime?.let { value -> it[OnCallScheduleLayers.handoffTime] = value }
                update.timezone?.let { value -> it[OnCallScheduleLayers.timezone] = value }
                update.enabled?.let { value -> it[OnCallScheduleLayers.enabled] = value }
                update.explicitGap?.let { value -> it[OnCallScheduleLayers.explicitGap] = value }
                it[OnCallScheduleLayers.updatedAt] = now
            }
            if (update.participantIds != null) {
                checkSeatLimit(
                    organizationId = organizationId,
                    newParticipantUserIds = update.participantIds,
                    layerIdToExclude = layerId,
                )
                OnCallScheduleLayerParticipants.deleteWhere {
                    OnCallScheduleLayerParticipants.layerId eq layerId
                }
                insertLayerParticipants(layerId, organizationId, update.participantIds, now)
            }
            layersForSchedule(scheduleId, schedule[OnCallSchedules.resourceId].toString())
                .single { it.internalId == layerId }
                .model
        }

    fun deleteLayer(
        organizationId: Int,
        scheduleId: Int,
        layerId: Int,
    ): Boolean =
        transaction {
            OnCallScheduleLayers.deleteWhere {
                (OnCallScheduleLayers.id eq layerId) and
                    (OnCallScheduleLayers.scheduleId eq scheduleId) and
                    (OnCallScheduleLayers.organizationId eq organizationId)
            } > 0
        }

    private fun insertLayerParticipants(
        layerId: Int,
        organizationId: Int,
        participantIds: List<Int>,
        now: Instant,
    ) {
        participantIds.forEachIndexed { index, userId ->
            OnCallScheduleLayerParticipants.insert {
                it[OnCallScheduleLayerParticipants.organizationId] = organizationId
                it[OnCallScheduleLayerParticipants.layerId] = layerId
                it[OnCallScheduleLayerParticipants.userId] = userId
                it[OnCallScheduleLayerParticipants.position] = index
                it[OnCallScheduleLayerParticipants.createdAt] = now
            }
        }
    }

    private fun layersForSchedule(
        scheduleId: Int,
        scheduleResourceId: String,
    ): List<ScheduleLayerRecord> =
        OnCallScheduleLayers
            .selectAll()
            .where { OnCallScheduleLayers.scheduleId eq scheduleId }
            .orderBy(OnCallScheduleLayers.layerOrder to SortOrder.ASC)
            .map { layer ->
                val participants =
                    OnCallScheduleLayerParticipants
                        .innerJoin(Users)
                        .selectAll()
                        .where { OnCallScheduleLayerParticipants.layerId eq layer[OnCallScheduleLayers.id].value }
                        .orderBy(OnCallScheduleLayerParticipants.position to SortOrder.ASC)
                        .map { row ->
                            OnCallParticipant(
                                id = row[OnCallScheduleLayerParticipants.resourceId].toString(),
                                userResourceId = row[Users.resource_id].toString(),
                                userName = row[Users.name] ?: row[Users.email],
                                userEmail = row[Users.email],
                                position = row[OnCallScheduleLayerParticipants.position],
                                internalId = row[OnCallScheduleLayerParticipants.id].value,
                                userId = row[OnCallScheduleLayerParticipants.userId],
                            )
                        }
                ScheduleLayerRecord(
                    model =
                        OnCallScheduleLayer(
                            id = layer[OnCallScheduleLayers.resourceId].toString(),
                            scheduleResourceId = scheduleResourceId,
                            name = layer[OnCallScheduleLayers.name],
                            layerOrder = layer[OnCallScheduleLayers.layerOrder],
                            rotationType = layer[OnCallScheduleLayers.rotationType],
                            handoffTime = layer[OnCallScheduleLayers.handoffTime],
                            timezone = layer[OnCallScheduleLayers.timezone],
                            enabled = layer[OnCallScheduleLayers.enabled],
                            explicitGap = layer[OnCallScheduleLayers.explicitGap],
                            participants = participants,
                            createdAt = layer[OnCallScheduleLayers.createdAt].toString(),
                            updatedAt = layer[OnCallScheduleLayers.updatedAt].toString(),
                        ),
                    internalId = layer[OnCallScheduleLayers.id].value,
                )
            }

    fun resolveCurrentResponders(
        organizationId: Int,
        scheduleIds: Collection<Int>,
        at: Instant = Clock.System.now(),
        all: Boolean = true,
    ): List<OnCallResponderResolution> =
        resolveCurrentResponderRecords(organizationId, scheduleIds, at, all).map { it.model }

    internal fun resolveCurrentResponderRecords(
        organizationId: Int,
        scheduleIds: Collection<Int>,
        at: Instant = Clock.System.now(),
        all: Boolean = true,
    ): List<ResolvedResponder> = transaction {
        if (scheduleIds.isEmpty()) return@transaction emptyList()
        val schedules =
            OnCallSchedules
                .selectAll()
                .where {
                    (OnCallSchedules.organizationId eq organizationId) and
                        (OnCallSchedules.id inList scheduleIds.distinct())
                }
                .orderBy(OnCallSchedules.id to SortOrder.ASC)
                .toList()
        val resolved = linkedMapOf<Int, ResolvedResponder>()
        schedules.forEach { schedule ->
            resolveScheduleResponders(organizationId, schedule, at).forEach { response ->
                resolved.putIfAbsent(response.internalUserId, response)
            }
            if (!all && resolved.isNotEmpty()) return@transaction resolved.values.take(1)
        }
        resolved.values.toList()
    }

    private fun resolveScheduleResponders(
        organizationId: Int,
        schedule: ResultRow,
        at: Instant,
    ): List<ResolvedResponder> {
        val scheduleId = schedule[OnCallSchedules.id].value
        val scheduleResourceId = schedule[OnCallSchedules.resourceId].toString()
        val override =
            OnCallOverrides
                .join(Users, JoinType.INNER, onColumn = OnCallOverrides.userId, otherColumn = Users.id)
                .selectAll()
                .where {
                    (OnCallOverrides.scheduleId eq scheduleId) and
                        (OnCallOverrides.startAt lessEq at) and
                        (OnCallOverrides.endAt greater at)
                }
                .orderBy(OnCallOverrides.startAt to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        if (override != null) {
            return listOf(
                ResolvedResponder(
                    model =
                        OnCallResponderResolution(
                            userId = override[Users.resource_id].toString(),
                            userName = override[Users.name] ?: override[Users.email],
                            userEmail = override[Users.email],
                            scheduleResourceId = scheduleResourceId,
                            source = "OVERRIDE",
                            activeUntil = override[OnCallOverrides.endAt].toString(),
                        ),
                    internalUserId = override[OnCallOverrides.userId],
                ),
            )
        }

        val layers =
            OnCallScheduleLayers
                .selectAll()
                .where {
                    (OnCallScheduleLayers.organizationId eq organizationId) and
                        (OnCallScheduleLayers.scheduleId eq scheduleId) and
                        (OnCallScheduleLayers.enabled eq true)
                }
                .orderBy(OnCallScheduleLayers.layerOrder to SortOrder.ASC)
                .toList()
        if (layers.isNotEmpty()) {
            return layers.flatMap { layer ->
                resolveLayerParticipant(layer, scheduleResourceId, at)
            }
        }

        return computeOnCallAt(scheduleId, at)?.let { participant ->
            listOf(
                ResolvedResponder(
                    model =
                        OnCallResponderResolution(
                            userId = participant.userResourceId,
                            userName = participant.userName,
                            userEmail = participant.userEmail,
                            scheduleResourceId = scheduleResourceId,
                            source = "ROTATION",
                        ),
                    internalUserId = participant.userId,
                ),
            )
        } ?: emptyList()
    }

    private fun resolveLayerParticipant(
        layer: ResultRow,
        scheduleResourceId: String,
        at: Instant,
    ): List<ResolvedResponder> {
        val participants =
            OnCallScheduleLayerParticipants
                .innerJoin(Users)
                .selectAll()
                .where { OnCallScheduleLayerParticipants.layerId eq layer[OnCallScheduleLayers.id].value }
                .orderBy(OnCallScheduleLayerParticipants.position to SortOrder.ASC)
                .toList()
        val index =
            ScheduleRotationResolver.participantIndex(
                RotationDefinition(
                    rotationType = layer[OnCallScheduleLayers.rotationType],
                    handoffTime = layer[OnCallScheduleLayers.handoffTime],
                    timezone = layer[OnCallScheduleLayers.timezone],
                    explicitGap = layer[OnCallScheduleLayers.explicitGap],
                ),
                participants.size,
                at,
            ) ?: return emptyList()
        val participant = participants.getOrNull(index) ?: return emptyList()
        return listOf(
            ResolvedResponder(
                model =
                    OnCallResponderResolution(
                        userId = participant[Users.resource_id].toString(),
                        userName = participant[Users.name] ?: participant[Users.email],
                        userEmail = participant[Users.email],
                        scheduleResourceId = scheduleResourceId,
                        layerId = layer[OnCallScheduleLayers.resourceId].toString(),
                        source = "LAYER",
                    ),
                internalUserId = participant[OnCallScheduleLayerParticipants.userId],
            ),
        )
    }

    // Extracted so both getCurrentOnCall and getOnCallAt share logic within an open transaction
    private fun computeOnCallAt(scheduleId: Int, now: Instant): OnCallParticipant? {
            // Check for active override first
            val override =
                OnCallOverrides
                    .join(Users, JoinType.INNER, onColumn = OnCallOverrides.userId, otherColumn = Users.id)
                    .selectAll()
                    .where {
                        (OnCallOverrides.scheduleId eq scheduleId) and
                            (OnCallOverrides.startAt lessEq now) and
                            (OnCallOverrides.endAt greater now)
                    }.orderBy(OnCallOverrides.startAt to SortOrder.DESC)
                    .limit(1)
                    .singleOrNull()

            if (override != null) {
                val participant =
                    OnCallParticipants
                        .selectAll()
                        .where {
                            (OnCallParticipants.scheduleId eq scheduleId) and
                                (OnCallParticipants.userId eq override[OnCallOverrides.userId])
                        }.singleOrNull()

                val userId = override[OnCallOverrides.userId]
                val participantId = participant?.get(OnCallParticipants.id)?.value
                return OnCallParticipant(
                    id = participant?.get(OnCallParticipants.resourceId)?.toString()
                        ?: override[Users.resource_id].toString(),
                    userResourceId = override[Users.resource_id].toString(),
                    userName = override[Users.name] ?: override[Users.email],
                    userEmail = override[Users.email],
                    position = participant?.get(OnCallParticipants.position) ?: -1,
                    internalId = participantId ?: -1,
                    userId = userId,
                )
            }

            // No override, calculate based on rotation
            val schedule =
                OnCallSchedules
                    .selectAll()
                    .where { OnCallSchedules.id eq scheduleId }
                    .singleOrNull() ?: return null

            val participants =
                OnCallParticipants
                    .innerJoin(Users)
                    .selectAll()
                    .where { OnCallParticipants.scheduleId eq scheduleId }
                    .orderBy(OnCallParticipants.position to SortOrder.ASC)
                    .toList()

            if (participants.isEmpty()) return null

            val rotationType = schedule[OnCallSchedules.rotationType]

            val rotationDays =
                when (rotationType) {
                    "DAILY" -> 1

                    "WEEKLY" -> WEEKLY_ROTATION_DAYS

                    "CUSTOM" -> WEEKLY_ROTATION_DAYS

                    // default to weekly for custom
                    else -> WEEKLY_ROTATION_DAYS
                }

            // Calculate days since epoch using schedule timezone and handoffTime
            val zoneId = ZoneId.of(schedule[OnCallSchedules.timezone])
            val handoffLocalTime = schedule[OnCallSchedules.handoffTime]
            val zonedNow =
                java.time.Instant
                    .ofEpochMilli(now.toEpochMilliseconds())
                    .atZone(zoneId)
            val rotationDate =
                if (zonedNow.toLocalTime().isBefore(handoffLocalTime)) {
                    zonedNow.toLocalDate().minusDays(1)
                } else {
                    zonedNow.toLocalDate()
                }
            val daysSinceEpoch = ChronoUnit.DAYS.between(LocalDate.EPOCH, rotationDate).toInt()

            // Calculate which participant should be on call
            val rotationCycle = (daysSinceEpoch / rotationDays) % participants.size
            val currentParticipant = participants[rotationCycle]

            return OnCallParticipant(
                id = currentParticipant[OnCallParticipants.resourceId].toString(),
                userResourceId = currentParticipant[Users.resource_id].toString(),
                userName = currentParticipant[Users.name] ?: currentParticipant[Users.email],
                userEmail = currentParticipant[Users.email],
                position = currentParticipant[OnCallParticipants.position],
                internalId = currentParticipant[OnCallParticipants.id].value,
                userId = currentParticipant[OnCallParticipants.userId],
            )
        }

    fun listSchedules(organizationId: Int): List<OnCallSchedule> =
        transaction {
            val orgResourceId = organizationResourceId(organizationId)
            OnCallSchedules
                .selectAll()
                .where { OnCallSchedules.organizationId eq organizationId }
                .orderBy(OnCallSchedules.name to SortOrder.ASC)
                .map { row -> scheduleResponse(row, orgResourceId) }
        }

    fun createSchedule(
        organizationId: Int,
        name: String,
        rotationType: String,
        handoffTime: LocalTime,
        timezone: String,
        participantIds: List<Int>,
    ): OnCallSchedule =
        transaction {
            // Enforce seat limits
            checkSeatLimit(organizationId, participantIds)

            val now = Clock.System.now()

            val scheduleId =
                OnCallSchedules
                    .insertAndGetId {
                        it[OnCallSchedules.organizationId] = organizationId
                        it[OnCallSchedules.name] = name
                        it[OnCallSchedules.rotationType] = rotationType
                        it[OnCallSchedules.handoffTime] = handoffTime
                        it[OnCallSchedules.timezone] = timezone
                        it[OnCallSchedules.createdAt] = now
                        it[OnCallSchedules.updatedAt] = now
                    }.value

            participantIds.forEachIndexed { index, userId ->
                OnCallParticipants.insert {
                    it[OnCallParticipants.scheduleId] = scheduleId
                    it[OnCallParticipants.userId] = userId
                    it[position] = index
                    it[createdAt] = now
                }
            }

            getSchedule(scheduleId)!!
        }

    fun updateSchedule(
        scheduleId: Int,
        name: String? = null,
        rotationType: String? = null,
        handoffTime: LocalTime? = null,
        timezone: String? = null,
        participantIds: List<Int>? = null,
    ): OnCallSchedule? =
        transaction {
            if (participantIds != null) {
                val schedule = OnCallSchedules.selectAll().where { OnCallSchedules.id eq scheduleId }.singleOrNull()
                if (schedule != null) {
                    checkSeatLimit(schedule[OnCallSchedules.organizationId], participantIds, scheduleId)
                }
            }

            val now = Clock.System.now()

            OnCallSchedules.update({ OnCallSchedules.id eq scheduleId }) {
                if (name != null) it[OnCallSchedules.name] = name
                if (rotationType != null) it[OnCallSchedules.rotationType] = rotationType
                if (handoffTime != null) it[OnCallSchedules.handoffTime] = handoffTime
                if (timezone != null) it[OnCallSchedules.timezone] = timezone
                it[updatedAt] = now
            }

            if (participantIds != null) {
                OnCallParticipants.deleteWhere { OnCallParticipants.scheduleId eq scheduleId }
                participantIds.forEachIndexed { index, userId ->
                    OnCallParticipants.insert {
                        it[OnCallParticipants.scheduleId] = scheduleId
                        it[OnCallParticipants.userId] = userId
                        it[position] = index
                        it[createdAt] = now
                    }
                }
            }

            getSchedule(scheduleId)
        }

    fun deleteSchedule(scheduleId: Int): Boolean =
        transaction {
            OnCallSchedules.deleteWhere { id eq scheduleId } > 0
        }

    fun createOverride(
        scheduleId: Int,
        userId: Int,
        startAt: Instant,
        endAt: Instant,
        createdBy: Int,
    ): OnCallOverride =
        transaction {
            val now = Clock.System.now()

            val overrideId =
                OnCallOverrides
                    .insertAndGetId {
                        it[OnCallOverrides.scheduleId] = scheduleId
                        it[OnCallOverrides.userId] = userId
                        it[OnCallOverrides.startAt] = startAt
                        it[OnCallOverrides.endAt] = endAt
                        it[OnCallOverrides.createdBy] = createdBy
                        it[createdAt] = now
                    }.value

            val row =
                OnCallOverrides
                    .join(Users, JoinType.INNER, onColumn = OnCallOverrides.userId, otherColumn = Users.id)
                    .selectAll()
                    .where { OnCallOverrides.id eq overrideId }
                    .single()

            OnCallOverride(
                id = overrideResourceId(row[OnCallOverrides.id].value),
                scheduleResourceId = scheduleResourceId(row[OnCallOverrides.scheduleId]),
                userResourceId = row[Users.resource_id].toString(),
                userName = row[Users.name] ?: row[Users.email],
                startAt = row[OnCallOverrides.startAt].toString(),
                endAt = row[OnCallOverrides.endAt].toString(),
                createdByResourceId = userResourceId(row[OnCallOverrides.createdBy]),
                createdAt = row[OnCallOverrides.createdAt].toString(),
                internalId = row[OnCallOverrides.id].value,
                scheduleId = row[OnCallOverrides.scheduleId],
                userId = row[OnCallOverrides.userId],
                createdBy = row[OnCallOverrides.createdBy],
            )
        }

    fun isScheduleInOrganization(
        scheduleId: Int,
        organizationId: Int,
    ): Boolean =
        transaction {
            OnCallSchedules
                .selectAll()
                .where { (OnCallSchedules.id eq scheduleId) and (OnCallSchedules.organizationId eq organizationId) }
                .limit(1)
                .singleOrNull() != null
        }

    fun isOverrideInOrganization(
        overrideId: Int,
        organizationId: Int,
    ): Boolean =
        transaction {
            OnCallOverrides
                .innerJoin(OnCallSchedules)
                .selectAll()
                .where {
                    (OnCallOverrides.id eq overrideId) and
                        (OnCallSchedules.organizationId eq organizationId)
                }.limit(1)
                .singleOrNull() != null
        }

    fun deleteOverride(overrideId: Int): Boolean =
        transaction {
            OnCallOverrides.deleteWhere { id eq overrideId } > 0
        }

    private fun checkSeatLimit(
        organizationId: Int,
        newParticipantUserIds: List<Int>,
        scheduleIdToExclude: Int? = null,
        layerIdToExclude: Int? = null,
    ) {
        val sub =
            Subscriptions
                .selectAll()
                .where {
                    (Subscriptions.organization_id eq organizationId) and
                        (Subscriptions.status inList listOf("active", "trialing", "past_due"))
                }.orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()

        val seatsPurchased = sub?.get(Subscriptions.oncall_seats) ?: 0

        val scheduleIds =
            OnCallSchedules
                .selectAll()
                .where { OnCallSchedules.organizationId eq organizationId }
                .map { it[OnCallSchedules.id].value }
                .filter { it != scheduleIdToExclude }

        val existingScheduleUsers =
            if (scheduleIds.isEmpty()) {
                emptySet()
            } else {
                OnCallParticipants
                    .selectAll()
                    .where { OnCallParticipants.scheduleId inList scheduleIds }
                    .map { it[OnCallParticipants.userId] }
                    .toSet()
            }

        val layerIds =
            if (scheduleIds.isEmpty()) {
                emptyList()
            } else {
                OnCallScheduleLayers
                    .selectAll()
                    .where { OnCallScheduleLayers.scheduleId inList scheduleIds }
                    .map { it[OnCallScheduleLayers.id].value }
                    .filter { it != layerIdToExclude }
            }
        val existingLayerUsers =
            if (layerIds.isEmpty()) {
                emptySet()
            } else {
                OnCallScheduleLayerParticipants
                    .selectAll()
                    .where { OnCallScheduleLayerParticipants.layerId inList layerIds }
                    .map { it[OnCallScheduleLayerParticipants.userId] }
                    .toSet()
            }

        val allUsers = existingScheduleUsers + existingLayerUsers + newParticipantUserIds
        val neededSeats = allUsers.size

        if (neededSeats > seatsPurchased) {
            throw IllegalArgumentException(
                "On-call seat limit reached ($seatsPurchased seats). Purchase more seats in Settings > Billing.",
            )
        }
    }
}
