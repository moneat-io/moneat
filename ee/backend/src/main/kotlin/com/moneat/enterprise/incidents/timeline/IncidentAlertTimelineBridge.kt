// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.timeline

import com.moneat.enterprise.incidents.models.IncidentSourceType
import com.moneat.enterprise.incidents.models.IncidentTimelineProvenance
import com.moneat.enterprise.oncall.models.OnCallAlertTimeline
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentAlerts
import com.moneat.enterprise.oncall.models.EscalationExecutionEvents
import com.moneat.enterprise.oncall.models.EscalationExecutionStates
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock

/** Copies legacy escalation events into the canonical incident timeline. */
class IncidentAlertTimelineBridge(
    private val timelineWriter: IncidentTimelineWriter = IncidentTimelineWriter(),
    private val clock: Clock = Clock.System,
) {
    /** Records one alert event when the alert is already linked to a native incident. */
    fun recordForAlertTimeline(alertTimelineId: Int): Boolean = inTransaction {
        val timeline = OnCallAlertTimeline.selectAll()
            .where { OnCallAlertTimeline.id eq alertTimelineId }
            .singleOrNull() ?: return@inTransaction false
        val alertId = timeline[OnCallAlertTimeline.alertId]
        val alert = OnCallAlerts.selectAll()
            .where { OnCallAlerts.id eq alertId }
            .singleOrNull() ?: return@inTransaction false
        val link = OnCallIncidentAlerts.selectAll()
            .where { OnCallIncidentAlerts.alertId eq alertId }
            .singleOrNull() ?: return@inTransaction false
        record(link[OnCallIncidentAlerts.incidentId], alert, timeline)
        true
    }

    /** Replays all prior alert events after an alert is linked to an incident. */
    fun backfill(incidentId: Int, alertId: Int): Int = inTransaction {
        val linked = OnCallIncidentAlerts.selectAll()
            .where {
                (OnCallIncidentAlerts.incidentId eq incidentId) and
                    (OnCallIncidentAlerts.alertId eq alertId)
            }
            .singleOrNull() ?: return@inTransaction 0
        val alert = OnCallAlerts.selectAll()
            .where { OnCallAlerts.id eq alertId }
            .singleOrNull() ?: return@inTransaction 0
        val events = OnCallAlertTimeline.selectAll()
            .where { OnCallAlertTimeline.alertId eq alertId }
            .toList()
        events.forEach { record(linked[OnCallIncidentAlerts.incidentId], alert, it) }
        val executionIds = EscalationExecutionStates.selectAll()
            .where { EscalationExecutionStates.alertId eq alertId }
            .map { it[EscalationExecutionStates.id].value }
        val escalationEvents = EscalationExecutionEvents.selectAll()
            .where { EscalationExecutionEvents.executionId inList executionIds }
            .toList()
        escalationEvents.forEach { record(linked[OnCallIncidentAlerts.incidentId], alert, it) }
        events.size + escalationEvents.size
    }

    /** Records one escalation-path event when its alert is linked to a native incident. */
    fun recordForEscalationEvent(eventId: Int): Boolean = inTransaction {
        val event = EscalationExecutionEvents.selectAll()
            .where { EscalationExecutionEvents.id eq eventId }
            .singleOrNull() ?: return@inTransaction false
        val execution = EscalationExecutionStates.selectAll()
            .where { EscalationExecutionStates.id eq event[EscalationExecutionEvents.executionId] }
            .singleOrNull() ?: return@inTransaction false
        val alert = OnCallAlerts.selectAll()
            .where { OnCallAlerts.id eq execution[EscalationExecutionStates.alertId] }
            .singleOrNull() ?: return@inTransaction false
        val link = OnCallIncidentAlerts.selectAll()
            .where { OnCallIncidentAlerts.alertId eq execution[EscalationExecutionStates.alertId] }
            .singleOrNull() ?: return@inTransaction false
        record(link[OnCallIncidentAlerts.incidentId], alert, event, execution)
        true
    }

    private fun <T> inTransaction(block: () -> T): T =
        if (TransactionManager.currentOrNull() == null) transaction { block() } else block()

    private fun record(
        incidentId: Int,
        alert: org.jetbrains.exposed.v1.core.ResultRow,
        event: org.jetbrains.exposed.v1.core.ResultRow,
    ) {
        val occurredAt = event[OnCallAlertTimeline.createdAt]
        timelineWriter.record(
            PendingIncidentTimelineEvent(
                organizationId = alert[OnCallAlerts.organizationId],
                incidentId = incidentId,
                eventKey = "on-call-alert:${alert[OnCallAlerts.resourceId]}:${event[OnCallAlertTimeline.resourceId]}",
                eventType = "ALERT_${event[OnCallAlertTimeline.eventType]}",
                actorUserId = event[OnCallAlertTimeline.actorUserId],
                details = (event[OnCallAlertTimeline.details] ?: emptyMap()) + mapOf(
                    "alertId" to JsonPrimitive(alert[OnCallAlerts.resourceId].toString()),
                    "alertTimelineEventId" to JsonPrimitive(event[OnCallAlertTimeline.resourceId].toString()),
                ),
                provenance = IncidentTimelineProvenance.INTEGRATION,
                originalOccurredAt = occurredAt,
                observedAt = clock.now(),
                sourceType = IncidentSourceType.ON_CALL_ALERT.wire,
                sourceReference = alert[OnCallAlerts.resourceId].toString(),
            ),
        )
    }

    private fun record(
        incidentId: Int,
        alert: org.jetbrains.exposed.v1.core.ResultRow,
        event: org.jetbrains.exposed.v1.core.ResultRow,
        execution: org.jetbrains.exposed.v1.core.ResultRow,
    ) {
        val occurredAt = event[EscalationExecutionEvents.createdAt]
        timelineWriter.record(
            PendingIncidentTimelineEvent(
                organizationId = alert[OnCallAlerts.organizationId],
                incidentId = incidentId,
                eventKey = "on-call-escalation:${alert[OnCallAlerts.resourceId]}:" +
                    event[EscalationExecutionEvents.resourceId],
                eventType = "ESCALATION_${event[EscalationExecutionEvents.eventType]}",
                actorUserId = event[EscalationExecutionEvents.actorUserId],
                details = event[EscalationExecutionEvents.details] + mapOf(
                    "alertId" to JsonPrimitive(alert[OnCallAlerts.resourceId].toString()),
                    "executionId" to JsonPrimitive(execution[EscalationExecutionStates.resourceId].toString()),
                    "executionEventId" to JsonPrimitive(event[EscalationExecutionEvents.resourceId].toString()),
                ),
                provenance = IncidentTimelineProvenance.INTEGRATION,
                originalOccurredAt = occurredAt,
                observedAt = clock.now(),
                sourceType = IncidentSourceType.ON_CALL_ALERT.wire,
                sourceReference = alert[OnCallAlerts.resourceId].toString(),
            ),
        )
    }
}
