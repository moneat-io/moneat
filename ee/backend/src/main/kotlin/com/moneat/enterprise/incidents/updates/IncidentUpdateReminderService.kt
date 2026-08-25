// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.updates

import com.moneat.enterprise.incidents.events.IncidentOutboxWriter
import com.moneat.enterprise.incidents.events.PendingNativeIncidentDomainEvent
import com.moneat.enterprise.incidents.models.IncidentUpdateRequestStatus
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.NativeIncidentUpdateRequests
import com.moneat.enterprise.oncall.models.OnCallIncidents
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Advances overdue update requests and emits durable reminder events for the normal outbox path. */
class IncidentUpdateReminderService(
    private val outboxWriter: IncidentOutboxWriter = IncidentOutboxWriter(),
    private val clock: Clock = Clock.System,
) {
    fun processDue(now: Instant = clock.now()): Int =
        transaction {
            NativeIncidentUpdateRequests
                .selectAll()
                .where { NativeIncidentUpdateRequests.status eq IncidentUpdateRequestStatus.OPEN.wire }
                .mapNotNull { request ->
                    if (request[NativeIncidentUpdateRequests.dueAt] > now) return@mapNotNull null
                    processRequest(request[NativeIncidentUpdateRequests.id].value, now)
                }
                .count { it }
        }

    private fun processRequest(requestId: Int, now: Instant): Boolean {
        val request = NativeIncidentUpdateRequests.selectAll()
            .where { NativeIncidentUpdateRequests.id eq requestId }
            .singleOrNull() ?: return false
        if (request[NativeIncidentUpdateRequests.status] != IncidentUpdateRequestStatus.OPEN.wire) return false
        val incident = OnCallIncidents.selectAll()
            .where { OnCallIncidents.id eq request[NativeIncidentUpdateRequests.incidentId] }
            .singleOrNull() ?: return false
        val status = NativeIncidentStatus.fromWire(incident[OnCallIncidents.status]) ?: return false
        if (incident[OnCallIncidents.updateReminderPaused]) {
            NativeIncidentUpdateRequests.update({ NativeIncidentUpdateRequests.id eq requestId }) {
                it[NativeIncidentUpdateRequests.status] = IncidentUpdateRequestStatus.PAUSED.wire
                it[updatedAt] = now
            }
            return false
        }
        if (status.terminal || status in setOf(
                NativeIncidentStatus.RESOLVED,
                NativeIncidentStatus.CANCELLED,
                NativeIncidentStatus.DECLINED,
                NativeIncidentStatus.CLOSED,
            )) {
            NativeIncidentUpdateRequests.update({ NativeIncidentUpdateRequests.id eq requestId }) {
                it[NativeIncidentUpdateRequests.status] = IncidentUpdateRequestStatus.CANCELLED.wire
                it[updatedAt] = now
            }
            return false
        }
        val nextLevel = request[NativeIncidentUpdateRequests.escalationLevel] + 1
        val nextVersion = incident[OnCallIncidents.version] + 1
        val updated = OnCallIncidents.update({
            OnCallIncidents.id eq incident[OnCallIncidents.id] and
                (OnCallIncidents.version eq incident[OnCallIncidents.version])
        }) {
            it[version] = nextVersion
            it[updatedAt] = now
        }
        if (updated != 1) return false
        NativeIncidentUpdateRequests.update({ NativeIncidentUpdateRequests.id eq requestId }) {
            it[escalationLevel] = nextLevel
            it[lastRemindedAt] = now
            it[dueAt] = now.plus(REMINDER_INTERVAL)
            it[updatedAt] = now
        }
        outboxWriter.record(
            PendingNativeIncidentDomainEvent(
                organizationId = request[NativeIncidentUpdateRequests.organizationId],
                incidentId = request[NativeIncidentUpdateRequests.incidentId],
                eventType = "INCIDENT_UPDATE_REMINDER",
                aggregateVersion = nextVersion,
                idempotencyKey = "incident-update-request:$requestId:reminder:$nextLevel",
                payload = mapOf(
                    "incidentId" to JsonPrimitive(incident[OnCallIncidents.resourceId].toString()),
                    "requestId" to JsonPrimitive(request[NativeIncidentUpdateRequests.resourceId].toString()),
                    "escalationLevel" to JsonPrimitive(nextLevel),
                    "dueAt" to JsonPrimitive(request[NativeIncidentUpdateRequests.dueAt].toString()),
                ),
            ),
        )
        return true
    }

    companion object {
        private val REMINDER_INTERVAL = 15.minutes
    }
}
