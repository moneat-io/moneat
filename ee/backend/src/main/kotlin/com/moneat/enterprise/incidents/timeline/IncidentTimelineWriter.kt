// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.timeline

import com.moneat.enterprise.incidents.models.IncidentTimelineProvenance
import com.moneat.enterprise.incidents.models.IncidentTimelineVisibility
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class PendingIncidentTimelineEvent(
    val organizationId: Int,
    val incidentId: Int,
    val eventKey: String,
    val eventType: String,
    val actorUserId: Int?,
    val details: Map<String, JsonElement>,
    val provenance: IncidentTimelineProvenance,
    val visibility: IncidentTimelineVisibility = IncidentTimelineVisibility.ORGANIZATION,
    val originalOccurredAt: Instant,
    val observedAt: Instant = originalOccurredAt,
    val sourceType: String? = null,
    val sourceReference: String? = null,
    val sourceUrl: String? = null,
)

class IncidentTimelineWriter {
    fun record(event: PendingIncidentTimelineEvent): Int {
        require(event.eventKey.isNotBlank()) { "Incident timeline event key is required" }
        require(event.eventKey.length <= MAX_EVENT_KEY_LENGTH) { "Incident timeline event key is too long" }
        require(event.eventType.isNotBlank()) { "Incident timeline event type is required" }
        require(event.eventType.length <= MAX_EVENT_TYPE_LENGTH) { "Incident timeline event type is too long" }
        val existing =
            OnCallIncidentTimeline
                .selectAll()
                .where {
                    (OnCallIncidentTimeline.incidentId eq event.incidentId) and
                        (OnCallIncidentTimeline.eventKey eq event.eventKey)
                }.singleOrNull()
        if (existing != null) return existing[OnCallIncidentTimeline.id].value

        return OnCallIncidentTimeline.insertAndGetId {
            it[resourceId] = Uuid.random()
            it[organizationId] = event.organizationId
            it[incidentId] = event.incidentId
            it[eventKey] = event.eventKey
            it[eventType] = event.eventType.uppercase()
            it[actorUserId] = event.actorUserId
            it[details] = event.details
            it[sourceType] = event.sourceType
            it[sourceReference] = event.sourceReference
            it[sourceUrl] = event.sourceUrl
            it[provenance] = event.provenance.wire
            it[visibility] = event.visibility.wire
            it[originalOccurredAt] = event.originalOccurredAt
            it[observedAt] = event.observedAt
            it[displayOrder] = displayOrder(event.originalOccurredAt)
            it[annotation] = null
            it[editedAt] = null
            it[editedBy] = null
            it[deletedAt] = null
            it[deletedBy] = null
            it[createdAt] = event.observedAt
        }.value
    }

    private fun displayOrder(instant: Instant): Long =
        Math.addExact(
            Math.multiplyExact(instant.epochSeconds, MICROS_PER_SECOND),
            instant.nanosecondsOfSecond.toLong() / NANOS_PER_MICRO,
        )

    companion object {
        private const val MAX_EVENT_KEY_LENGTH = 200
        private const val MAX_EVENT_TYPE_LENGTH = 80
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val NANOS_PER_MICRO = 1_000L
    }
}

fun String.toIncidentTimelineProvenance(): IncidentTimelineProvenance =
    IncidentTimelineProvenance.entries.firstOrNull { it.wire == uppercase() }
        ?: IncidentTimelineProvenance.INTERNAL
