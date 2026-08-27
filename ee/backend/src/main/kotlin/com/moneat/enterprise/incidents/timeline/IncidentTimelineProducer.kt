// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.timeline

import com.moneat.enterprise.incidents.models.IncidentTimelineProvenance
import com.moneat.enterprise.incidents.models.IncidentTimelineVisibility
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Instant

/**
 * Integration-owned event categories that can contribute evidence to a native incident.
 * The timeline table intentionally keeps source types as strings so integrations can evolve
 * without changing the canonical storage contract.
 */
enum class IncidentTimelineProducerSource(val wire: String) {
    STATUS_PAGE("STATUS_PAGE"),
    CALL("CALL"),
    DEPLOY("DEPLOY"),
    SLACK("SLACK_MESSAGE"),
}

data class IncidentTimelineProducerEvent(
    val organizationId: Int,
    val incidentId: Int,
    val eventKey: String,
    val eventType: String,
    val originalOccurredAt: Instant,
    val details: Map<String, JsonElement> = emptyMap(),
    val actorUserId: Int? = null,
    val sourceReference: String,
    val sourceUrl: String? = null,
    val observedAt: Instant = originalOccurredAt,
    val visibility: IncidentTimelineVisibility = IncidentTimelineVisibility.ORGANIZATION,
)

/**
 * Records integration activity using the same writer as first-party incident events.
 * Callers provide a stable event key derived from the upstream event identity; the writer
 * enforces per-incident idempotency and preserves the upstream occurrence time for late events.
 */
class IncidentTimelineProducer(
    private val writer: IncidentTimelineWriter = IncidentTimelineWriter(),
) {
    fun recordStatusPageChange(event: IncidentTimelineProducerEvent): Int =
        record(event, IncidentTimelineProducerSource.STATUS_PAGE)

    fun recordCallActivity(event: IncidentTimelineProducerEvent): Int =
        record(event, IncidentTimelineProducerSource.CALL)

    fun recordDeployActivity(event: IncidentTimelineProducerEvent): Int =
        record(event, IncidentTimelineProducerSource.DEPLOY)

    fun recordSlackActivity(event: IncidentTimelineProducerEvent): Int =
        record(event, IncidentTimelineProducerSource.SLACK)

    private fun record(
        event: IncidentTimelineProducerEvent,
        source: IncidentTimelineProducerSource,
    ): Int {
        require(event.sourceReference.isNotBlank()) { "Incident timeline source reference is required" }
        val pending = PendingIncidentTimelineEvent(
            organizationId = event.organizationId,
            incidentId = event.incidentId,
            eventKey = event.eventKey,
            eventType = event.eventType,
            actorUserId = event.actorUserId,
            details = event.details,
            provenance = IncidentTimelineProvenance.INTEGRATION,
            visibility = event.visibility,
            originalOccurredAt = event.originalOccurredAt,
            observedAt = event.observedAt,
            sourceType = source.wire,
            sourceReference = event.sourceReference,
            sourceUrl = event.sourceUrl,
        )
        return if (TransactionManager.currentOrNull() == null) {
            transaction { writer.record(pending) }
        } else {
            writer.record(pending)
        }
    }
}
