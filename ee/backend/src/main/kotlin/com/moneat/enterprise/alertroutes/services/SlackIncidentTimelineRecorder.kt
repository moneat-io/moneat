// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.enterprise.incidents.models.IncidentSourceType
import com.moneat.enterprise.incidents.models.IncidentTimelineProvenance
import com.moneat.enterprise.incidents.timeline.IncidentTimelineWriter
import com.moneat.enterprise.incidents.timeline.PendingIncidentTimelineEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class SlackIncidentTimelineRecord(
    val organizationId: Int,
    val incidentId: Int,
    val actorUserId: Int,
    val alertEpisodeId: Int,
    val onCallAlertId: Int?,
    val eventType: String,
    val deliveryId: String?,
    val occurredAt: Instant,
)

class SlackIncidentTimelineRecorder(
    private val timelineWriter: IncidentTimelineWriter = IncidentTimelineWriter(),
) {
    fun record(event: SlackIncidentTimelineRecord) {
        val sourceReference = event.deliveryId?.trim()?.takeIf(String::isNotEmpty)
            ?: "${event.alertEpisodeId}:${event.eventType}:${event.actorUserId}:${Uuid.random()}"
        transaction {
            timelineWriter.record(
                PendingIncidentTimelineEvent(
                    organizationId = event.organizationId,
                    incidentId = event.incidentId,
                    eventKey = "slack:${event.eventType}:$sourceReference",
                    eventType = event.eventType,
                    actorUserId = event.actorUserId,
                    details = buildJsonObject {
                        put("origin", IncidentTimelineProvenance.SLACK.wire)
                        put("action", event.eventType)
                        put("alertEpisodeId", event.alertEpisodeId)
                        event.onCallAlertId?.let { put("onCallAlertId", it) }
                    },
                    provenance = IncidentTimelineProvenance.SLACK,
                    originalOccurredAt = event.occurredAt,
                    sourceType = IncidentSourceType.SLACK_MESSAGE.wire,
                    sourceReference = sourceReference,
                ),
            )
        }
    }
}
