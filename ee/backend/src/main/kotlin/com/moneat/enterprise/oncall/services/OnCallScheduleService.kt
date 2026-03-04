// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.enterprise.oncall.models.OnCallOverride
import com.moneat.enterprise.oncall.models.OnCallOverrides
import com.moneat.enterprise.oncall.models.OnCallParticipant
import com.moneat.enterprise.oncall.models.OnCallSchedule
import com.moneat.enterprise.oncall.models.OnCallScheduleUsergroups
import com.moneat.shared.models.OnCallParticipants
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.JoinType
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

class OnCallScheduleService {
    fun getOnCallUsedSeats(organizationId: Int): Int =
        transaction {
            val scheduleIds =
                OnCallSchedules
                    .selectAll()
                    .where { OnCallSchedules.organizationId eq organizationId }
                    .map { it[OnCallSchedules.id].value }

            if (scheduleIds.isEmpty()) return@transaction 0

            OnCallParticipants
                .selectAll()
                .where { OnCallParticipants.scheduleId inList scheduleIds }
                .map { it[OnCallParticipants.userId] }
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

            val participants =
                OnCallParticipants
                    .innerJoin(Users)
                    .selectAll()
                    .where { OnCallParticipants.scheduleId eq scheduleId }
                    .orderBy(OnCallParticipants.position to SortOrder.ASC)
                    .map { row ->
                        OnCallParticipant(
                            id = row[OnCallParticipants.id].value,
                            userId = row[OnCallParticipants.userId],
                            userName = row[Users.name] ?: row[Users.email],
                            userEmail = row[Users.email],
                            position = row[OnCallParticipants.position],
                        )
                    }

            val overrides =
                OnCallOverrides
                    .join(Users, JoinType.INNER, onColumn = OnCallOverrides.userId, otherColumn = Users.id)
                    .selectAll()
                    .where { OnCallOverrides.scheduleId eq scheduleId }
                    .orderBy(OnCallOverrides.startAt to SortOrder.ASC)
                    .map { row ->
                        OnCallOverride(
                            id = row[OnCallOverrides.id].value,
                            scheduleId = row[OnCallOverrides.scheduleId],
                            userId = row[OnCallOverrides.userId],
                            userName = row[Users.name] ?: row[Users.email],
                            startAt = row[OnCallOverrides.startAt].toString(),
                            endAt = row[OnCallOverrides.endAt].toString(),
                            createdBy = row[OnCallOverrides.createdBy],
                            createdAt = row[OnCallOverrides.createdAt].toString(),
                        )
                    }

            val currentOnCall = getCurrentOnCall(scheduleId)

            // Fetch Slack usergroup mapping if it exists
            val usergroupMapping =
                OnCallScheduleUsergroups
                    .selectAll()
                    .where { OnCallScheduleUsergroups.scheduleId eq scheduleId }
                    .singleOrNull()

            OnCallSchedule(
                id = scheduleRow[OnCallSchedules.id].value,
                organizationId = scheduleRow[OnCallSchedules.organizationId],
                name = scheduleRow[OnCallSchedules.name],
                rotationType = scheduleRow[OnCallSchedules.rotationType],
                handoffTime = scheduleRow[OnCallSchedules.handoffTime],
                timezone = scheduleRow[OnCallSchedules.timezone],
                participants = participants,
                overrides = overrides,
                currentOnCall = currentOnCall,
                slackUsergroupId = usergroupMapping?.get(OnCallScheduleUsergroups.slackUsergroupId),
                slackUsergroupHandle = usergroupMapping?.get(OnCallScheduleUsergroups.slackUsergroupHandle),
                createdAt = scheduleRow[OnCallSchedules.createdAt].toString(),
                updatedAt = scheduleRow[OnCallSchedules.updatedAt].toString(),
            )
        }

    fun getOnCallAt(scheduleId: Int, at: Instant): OnCallParticipant? = transaction { computeOnCallAt(scheduleId, at) }

    fun getCurrentOnCall(scheduleId: Int): OnCallParticipant? =
        transaction {
            val now = Clock.System.now()
            computeOnCallAt(scheduleId, now)
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

                return OnCallParticipant(
                    id = participant?.get(OnCallParticipants.id)?.value ?: -1,
                    userId = override[OnCallOverrides.userId],
                    userName = override[Users.name] ?: override[Users.email],
                    userEmail = override[Users.email],
                    position = participant?.get(OnCallParticipants.position) ?: -1,
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

                    "WEEKLY" -> 7

                    "CUSTOM" -> 7

                    // default to weekly for custom
                    else -> 7
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
                id = currentParticipant[OnCallParticipants.id].value,
                userId = currentParticipant[OnCallParticipants.userId],
                userName = currentParticipant[Users.name] ?: currentParticipant[Users.email],
                userEmail = currentParticipant[Users.email],
                position = currentParticipant[OnCallParticipants.position],
            )
        }

    fun listSchedules(organizationId: Int): List<OnCallSchedule> =
        transaction {
            OnCallSchedules
                .selectAll()
                .where { OnCallSchedules.organizationId eq organizationId }
                .orderBy(OnCallSchedules.name to SortOrder.ASC)
                .mapNotNull { row ->
                    getSchedule(row[OnCallSchedules.id].value)
                }
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
                id = row[OnCallOverrides.id].value,
                scheduleId = row[OnCallOverrides.scheduleId],
                userId = row[OnCallOverrides.userId],
                userName = row[Users.name] ?: row[Users.email],
                startAt = row[OnCallOverrides.startAt].toString(),
                endAt = row[OnCallOverrides.endAt].toString(),
                createdBy = row[OnCallOverrides.createdBy],
                createdAt = row[OnCallOverrides.createdAt].toString(),
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

        val existingUsers =
            if (scheduleIds.isEmpty()) {
                emptySet()
            } else {
                OnCallParticipants
                    .selectAll()
                    .where { OnCallParticipants.scheduleId inList scheduleIds }
                    .map { it[OnCallParticipants.userId] }
                    .toSet()
            }

        val allUsers = existingUsers + newParticipantUserIds
        val neededSeats = allUsers.size

        if (neededSeats > seatsPurchased) {
            throw IllegalArgumentException(
                "On-call seat limit reached ($seatsPurchased seats). Purchase more seats in Settings > Billing.",
            )
        }
    }
}
