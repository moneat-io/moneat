// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.followups

import com.moneat.enterprise.incidents.events.IncidentOutboxWriter
import com.moneat.enterprise.incidents.events.PendingNativeIncidentDomainEvent
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.oncall.models.OnCallIncidents
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Advances overdue follow-ups and emits durable reminder events for the normal outbox path. */
class IncidentFollowUpReminderService(
    private val outboxWriter: IncidentOutboxWriter = IncidentOutboxWriter(),
    private val clock: Clock = Clock.System,
) {
    fun processDue(now: Instant = clock.now()): Int =
        transaction {
            NativeIncidentFollowUps
                .selectAll()
                .where {
                    NativeIncidentFollowUps.status inList listOf(
                        IncidentFollowUpStatus.OPEN.wire,
                        IncidentFollowUpStatus.ACCEPTED.wire,
                    ) and (
                        (NativeIncidentFollowUps.nextReminderAt lessEq now) or
                            (
                                (NativeIncidentFollowUps.dueAt lessEq now) and
                                    NativeIncidentFollowUps.slaFiredAt.isNull()
                                )
                        )
                }
                .map { followUp -> processFollowUp(followUp[NativeIncidentFollowUps.id].value, now) }
                .count { it }
        }

    private fun processFollowUp(followUpId: Int, now: Instant): Boolean {
        val followUp = NativeIncidentFollowUps
            .selectAll()
            .where { NativeIncidentFollowUps.id eq followUpId }
            .forUpdate()
            .singleOrNull() ?: return false
        if (followUp[NativeIncidentFollowUps.status] !in setOf(
                IncidentFollowUpStatus.OPEN.wire,
                IncidentFollowUpStatus.ACCEPTED.wire,
            )) {
            return false
        }
        val incident = OnCallIncidents
            .selectAll()
            .where { OnCallIncidents.id eq followUp[NativeIncidentFollowUps.incidentId] }
            .forUpdate()
            .singleOrNull() ?: return false
        val incidentStatus = NativeIncidentStatus.fromWire(incident[OnCallIncidents.status]) ?: return false
        if (incidentStatus in TERMINAL_STATUSES) {
            NativeIncidentFollowUps.update({ NativeIncidentFollowUps.id eq followUpId }) {
                it[status] = IncidentFollowUpStatus.CANCELLED.wire
                it[nextReminderAt] = null
                it[updatedAt] = now
            }
            return false
        }

        val dueState = dueState(followUp, now)
        if (!dueState.isDue) return false

        val nextLevel = followUp[NativeIncidentFollowUps.escalationLevel] + 1
        val nextVersion = incident[OnCallIncidents.version] + 1
        val updated = OnCallIncidents.update({
            (OnCallIncidents.id eq incident[OnCallIncidents.id]) and
                (OnCallIncidents.version eq incident[OnCallIncidents.version])
        }) {
            it[version] = nextVersion
            it[updatedAt] = now
        }
        if (updated != 1) return false

        NativeIncidentFollowUps.update({ NativeIncidentFollowUps.id eq followUpId }) {
            it[escalationLevel] = nextLevel
            if (dueState.slaDue) it[slaFiredAt] = now
            it[nextReminderAt] = followUp[NativeIncidentFollowUps.reminderMinutes]?.let { minutes ->
                now.plus(minutes.minutes)
            }
            it[updatedAt] = now
        }
        outboxWriter.record(
            PendingNativeIncidentDomainEvent(
                organizationId = followUp[NativeIncidentFollowUps.organizationId],
                incidentId = followUp[NativeIncidentFollowUps.incidentId],
                eventType = "INCIDENT_FOLLOW_UP_REMINDER",
                aggregateVersion = nextVersion,
                idempotencyKey = buildReminderIdempotencyKey(followUpId, dueState.slaDue, nextLevel),
                payload = buildMap {
                    put("incidentId", JsonPrimitive(incident[OnCallIncidents.resourceId].toString()))
                    put("followUpId", JsonPrimitive(followUp[NativeIncidentFollowUps.resourceId].toString()))
                    put("escalationLevel", JsonPrimitive(nextLevel))
                    put("slaDue", JsonPrimitive(dueState.slaDue))
                    followUp[NativeIncidentFollowUps.dueAt]?.let { dueAt ->
                        put("dueAt", JsonPrimitive(dueAt.toString()))
                    }
                },
            ),
        )
        return true
    }

    private fun dueState(followUp: org.jetbrains.exposed.v1.core.ResultRow, now: Instant): DueState = DueState(
        reminderDue = followUp[NativeIncidentFollowUps.nextReminderAt]?.let { it <= now } == true,
        slaDue = followUp[NativeIncidentFollowUps.dueAt]?.let { it <= now } == true &&
            followUp[NativeIncidentFollowUps.slaFiredAt] == null,
    )

    private fun buildReminderIdempotencyKey(followUpId: Int, slaDue: Boolean, level: Int): String =
        "incident-follow-up:$followUpId:${if (slaDue) "sla" else "reminder"}:$level"

    private data class DueState(
        val reminderDue: Boolean,
        val slaDue: Boolean,
    ) {
        val isDue: Boolean get() = reminderDue || slaDue
    }

    companion object {
        private val TERMINAL_STATUSES = setOf(
            NativeIncidentStatus.CLOSED,
            NativeIncidentStatus.CANCELLED,
            NativeIncidentStatus.DECLINED,
            NativeIncidentStatus.MERGED,
        )
    }
}
