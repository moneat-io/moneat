// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.timeline

import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.enterprise.incidents.models.IncidentTimelineProvenance
import com.moneat.enterprise.incidents.models.IncidentTimelineVisibility
import com.moneat.enterprise.incidents.models.NativeIncidentTimelineRevisions
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.Users
import com.moneat.shared.services.toUuidOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class IncidentTimelineEntry(
    val id: String,
    val eventKey: String,
    val eventType: String,
    val actorUserId: String? = null,
    val details: Map<String, JsonElement> = emptyMap(),
    val sourceType: String? = null,
    val sourceReference: String? = null,
    val sourceUrl: String? = null,
    val provenance: IncidentTimelineProvenance,
    val visibility: IncidentTimelineVisibility,
    val originalOccurredAt: String,
    val observedAt: String,
    val displayOrder: Long,
    val annotation: String? = null,
    val editedAt: String? = null,
    val deletedAt: String? = null,
    val createdAt: String,
)

@Serializable
data class IncidentTimelineRevision(
    val id: String,
    val revision: Int,
    val action: String,
    val previous: Map<String, JsonElement>,
    val next: Map<String, JsonElement>,
    val reason: String? = null,
    val editedByUserId: String,
    val createdAt: String,
)

@Serializable
data class IncidentTimelineExport(
    val incidentId: String,
    val exportedAt: String,
    val events: List<IncidentTimelineEntry>,
)

data class IncidentTimelineFilters(
    val eventTypes: List<String> = emptyList(),
    val provenance: List<IncidentTimelineProvenance> = emptyList(),
    val visibility: List<IncidentTimelineVisibility> = emptyList(),
    val includeDeleted: Boolean = false,
)

data class EditIncidentTimelineEvent(
    val eventType: String? = null,
    val details: Map<String, JsonElement>? = null,
    val originalOccurredAt: Instant? = null,
    val visibility: IncidentTimelineVisibility? = null,
    val reason: String? = null,
)

data class AnnotateIncidentTimelineEvent(
    val annotation: String?,
    val reason: String? = null,
)

class IncidentTimelineService {
    fun list(
        organizationId: Int,
        incidentId: Int,
        filters: IncidentTimelineFilters = IncidentTimelineFilters(),
    ): List<IncidentTimelineEntry> =
        transaction {
            requireIncident(organizationId, incidentId)
            val rows = filteredTimeline(organizationId, incidentId, filters).toList()
            val actors = publicUserIds(rows.mapNotNull { it[OnCallIncidentTimeline.actorUserId] })
            rows.map { row -> timelineEntry(row, actors) }
        }

    fun edit(
        organizationId: Int,
        incidentId: Int,
        eventResourceId: String,
        actorUserId: Int,
        request: EditIncidentTimelineEvent,
    ): IncidentTimelineEntry =
        transaction {
            val row = requireEvent(organizationId, incidentId, eventResourceId, lockForUpdate = true)
            require(row[OnCallIncidentTimeline.deletedAt] == null) { "Deleted timeline events cannot be edited" }
            val eventType = request.eventType?.trim()?.uppercase()
            eventType?.let {
                require(it.isNotEmpty()) { "Timeline event type is required" }
                require(it.length <= MAX_EVENT_TYPE_LENGTH) { "Timeline event type is too long" }
            }
            val previous = snapshot(row)
            val now = Clock.System.now()
            OnCallIncidentTimeline.update({ OnCallIncidentTimeline.id eq row[OnCallIncidentTimeline.id] }) {
                eventType?.let { value -> it[OnCallIncidentTimeline.eventType] = value }
                request.details?.let { value -> it[details] = value }
                request.originalOccurredAt?.let { value -> it[originalOccurredAt] = value }
                request.visibility?.let { value -> it[visibility] = value.wire }
                it[editedAt] = now
                it[editedBy] = actorUserId
            }
            val updated = requireEvent(organizationId, incidentId, eventResourceId)
            recordRevision(
                TimelineRevisionRecord(updated, "EDIT", previous, snapshot(updated), request.reason, actorUserId, now),
            )
            timelineEntry(updated, publicUserIds(listOfNotNull(updated[OnCallIncidentTimeline.actorUserId])))
        }

    fun annotate(
        organizationId: Int,
        incidentId: Int,
        eventResourceId: String,
        actorUserId: Int,
        request: AnnotateIncidentTimelineEvent,
    ): IncidentTimelineEntry =
        transaction {
            val row = requireEvent(organizationId, incidentId, eventResourceId, lockForUpdate = true)
            require(row[OnCallIncidentTimeline.deletedAt] == null) { "Deleted timeline events cannot be annotated" }
            val previous = snapshot(row)
            val now = Clock.System.now()
            OnCallIncidentTimeline.update({ OnCallIncidentTimeline.id eq row[OnCallIncidentTimeline.id] }) {
                it[OnCallIncidentTimeline.annotation] = request.annotation.cleaned()
                it[editedAt] = now
                it[editedBy] = actorUserId
            }
            val updated = requireEvent(organizationId, incidentId, eventResourceId)
            recordRevision(
                TimelineRevisionRecord(
                    updated,
                    "ANNOTATE",
                    previous,
                    snapshot(updated),
                    request.reason,
                    actorUserId,
                    now,
                ),
            )
            timelineEntry(updated, publicUserIds(listOfNotNull(updated[OnCallIncidentTimeline.actorUserId])))
        }

    fun reorder(
        organizationId: Int,
        incidentId: Int,
        actorUserId: Int,
        orderedEventIds: List<String>,
        reason: String?,
    ): List<IncidentTimelineEntry> =
        transaction {
            require(orderedEventIds.distinct().size == orderedEventIds.size) {
                "Timeline reorder event IDs must be unique"
            }
            val rows =
                filteredTimeline(organizationId, incidentId, IncidentTimelineFilters())
                    .forUpdate()
                    .toList()
            val rowsById = rows.associateBy { it[OnCallIncidentTimeline.resourceId].toString() }
            require(rowsById.keys == orderedEventIds.toSet()) {
                "Timeline reorder must include every active event exactly once"
            }
            val now = Clock.System.now()
            orderedEventIds.forEachIndexed { index, resourceId ->
                val row = checkNotNull(rowsById[resourceId])
                val nextOrder = index.toLong() * DISPLAY_ORDER_STEP
                if (row[OnCallIncidentTimeline.displayOrder] != nextOrder) {
                    val previous = snapshot(row)
                    OnCallIncidentTimeline.update({ OnCallIncidentTimeline.id eq row[OnCallIncidentTimeline.id] }) {
                        it[displayOrder] = nextOrder
                        it[editedAt] = now
                        it[editedBy] = actorUserId
                    }
                    val updated = requireEvent(organizationId, incidentId, resourceId)
                    recordRevision(
                        TimelineRevisionRecord(
                            updated,
                            "REORDER",
                            previous,
                            snapshot(updated),
                            reason,
                            actorUserId,
                            now,
                        ),
                    )
                }
            }
            val updatedRows = filteredTimeline(organizationId, incidentId, IncidentTimelineFilters()).toList()
            val actors = publicUserIds(updatedRows.mapNotNull { it[OnCallIncidentTimeline.actorUserId] })
            updatedRows.map { timelineEntry(it, actors) }
        }

    fun delete(
        organizationId: Int,
        incidentId: Int,
        eventResourceId: String,
        actorUserId: Int,
        reason: String?,
    ) {
        mutateDeletedState(
            DeletedStateMutation(organizationId, incidentId, eventResourceId, actorUserId, deleted = true, reason),
        )
    }

    fun restore(
        organizationId: Int,
        incidentId: Int,
        eventResourceId: String,
        actorUserId: Int,
        reason: String?,
    ) {
        mutateDeletedState(
            DeletedStateMutation(organizationId, incidentId, eventResourceId, actorUserId, deleted = false, reason),
        )
    }

    fun revisions(
        organizationId: Int,
        incidentId: Int,
        eventResourceId: String,
    ): List<IncidentTimelineRevision> =
        transaction {
            val event = requireEvent(organizationId, incidentId, eventResourceId)
            val rows =
                NativeIncidentTimelineRevisions
                    .selectAll()
                    .where {
                        (NativeIncidentTimelineRevisions.organizationId eq organizationId) and
                            (NativeIncidentTimelineRevisions.timelineEventId eq
                                event[OnCallIncidentTimeline.id].value)
                    }.orderBy(NativeIncidentTimelineRevisions.revisionNumber to SortOrder.ASC)
                    .toList()
            val editors = publicUserIds(rows.map { it[NativeIncidentTimelineRevisions.editedBy] })
            rows.map { row ->
                IncidentTimelineRevision(
                    id = row[NativeIncidentTimelineRevisions.resourceId].toString(),
                    revision = row[NativeIncidentTimelineRevisions.revisionNumber],
                    action = row[NativeIncidentTimelineRevisions.action],
                    previous = row[NativeIncidentTimelineRevisions.previousSnapshot],
                    next = row[NativeIncidentTimelineRevisions.nextSnapshot],
                    reason = row[NativeIncidentTimelineRevisions.reason],
                    editedByUserId = checkNotNull(editors[row[NativeIncidentTimelineRevisions.editedBy]]),
                    createdAt = row[NativeIncidentTimelineRevisions.createdAt].toString(),
                )
            }
        }

    fun export(organizationId: Int, incidentId: Int): IncidentTimelineExport =
        transaction {
            val incident = requireIncident(organizationId, incidentId)
            IncidentTimelineExport(
                incidentId = incident[OnCallIncidents.resourceId].toString(),
                exportedAt = Clock.System.now().toString(),
                events = list(organizationId, incidentId, IncidentTimelineFilters(includeDeleted = true)),
            )
        }

    private fun mutateDeletedState(mutation: DeletedStateMutation) {
        transaction {
            val row =
                requireEvent(
                    mutation.organizationId,
                    mutation.incidentId,
                    mutation.eventResourceId,
                    lockForUpdate = true,
                )
            val currentlyDeleted = row[OnCallIncidentTimeline.deletedAt] != null
            if (currentlyDeleted == mutation.deleted) return@transaction
            val previous = snapshot(row)
            val now = Clock.System.now()
            OnCallIncidentTimeline.update({ OnCallIncidentTimeline.id eq row[OnCallIncidentTimeline.id] }) {
                it[deletedAt] = now.takeIf { mutation.deleted }
                it[deletedBy] = mutation.actorUserId.takeIf { mutation.deleted }
                it[editedAt] = now
                it[editedBy] = mutation.actorUserId
            }
            val updated = requireEvent(mutation.organizationId, mutation.incidentId, mutation.eventResourceId)
            val action = if (mutation.deleted) "DELETE" else "RESTORE"
            recordRevision(
                TimelineRevisionRecord(
                    updated,
                    action,
                    previous,
                    snapshot(updated),
                    mutation.reason,
                    mutation.actorUserId,
                    now,
                ),
            )
        }
    }

    private fun filteredTimeline(
        organizationId: Int,
        incidentId: Int,
        filters: IncidentTimelineFilters,
    ): Query {
        requireIncident(organizationId, incidentId)
        var query =
            OnCallIncidentTimeline
                .selectAll()
                .where {
                    (OnCallIncidentTimeline.organizationId eq organizationId) and
                        (OnCallIncidentTimeline.incidentId eq incidentId)
                }
        if (!filters.includeDeleted) query = query.andWhere { OnCallIncidentTimeline.deletedAt.isNull() }
        if (filters.eventTypes.isNotEmpty()) {
            query = query.andWhere { OnCallIncidentTimeline.eventType inList filters.eventTypes.map(String::uppercase) }
        }
        if (filters.provenance.isNotEmpty()) {
            query = query.andWhere { OnCallIncidentTimeline.provenance inList filters.provenance.map { it.wire } }
        }
        if (filters.visibility.isNotEmpty()) {
            query = query.andWhere { OnCallIncidentTimeline.visibility inList filters.visibility.map { it.wire } }
        }
        return query.orderBy(
            OnCallIncidentTimeline.displayOrder to SortOrder.ASC,
            OnCallIncidentTimeline.id to SortOrder.ASC,
        )
    }

    private fun requireIncident(organizationId: Int, incidentId: Int): ResultRow =
        OnCallIncidents
            .selectAll()
            .where {
                (OnCallIncidents.organizationId eq organizationId) and
                    (OnCallIncidents.id eq incidentId)
            }.singleOrNull()
            ?: throw IncidentCommandNotFoundException("Native incident not found")

    private fun requireEvent(
        organizationId: Int,
        incidentId: Int,
        resourceId: String,
        lockForUpdate: Boolean = false,
    ): ResultRow {
        val uuid = resourceId.toUuidOrNull() ?: throw IllegalArgumentException("Invalid timeline event ID")
        val query = OnCallIncidentTimeline
            .selectAll()
            .where {
                (OnCallIncidentTimeline.organizationId eq organizationId) and
                    (OnCallIncidentTimeline.incidentId eq incidentId) and
                    (OnCallIncidentTimeline.resourceId eq uuid)
            }
        if (lockForUpdate) query.forUpdate()
        return query.singleOrNull()
            ?: throw IncidentCommandNotFoundException("Incident timeline event not found")
    }

    private fun recordRevision(record: TimelineRevisionRecord) {
        val eventId = record.event[OnCallIncidentTimeline.id].value
        val revision =
            NativeIncidentTimelineRevisions
                .selectAll()
                .where { NativeIncidentTimelineRevisions.timelineEventId eq eventId }
                .orderBy(NativeIncidentTimelineRevisions.revisionNumber to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(NativeIncidentTimelineRevisions.revisionNumber)
                ?.plus(1)
                ?: 1
        NativeIncidentTimelineRevisions.insert {
            it[resourceId] = Uuid.random()
            it[organizationId] = record.event[OnCallIncidentTimeline.organizationId]
            it[timelineEventId] = eventId
            it[revisionNumber] = revision
            it[NativeIncidentTimelineRevisions.action] = record.action
            it[previousSnapshot] = record.previous
            it[nextSnapshot] = record.next
            it[NativeIncidentTimelineRevisions.reason] = record.reason.cleaned()
            it[editedBy] = record.actorUserId
            it[createdAt] = record.now
        }
    }

    private fun snapshot(row: ResultRow): Map<String, JsonElement> =
        mapOf(
            "eventType" to JsonPrimitive(row[OnCallIncidentTimeline.eventType]),
            "details" to JsonObject(row[OnCallIncidentTimeline.details]),
            "visibility" to JsonPrimitive(row[OnCallIncidentTimeline.visibility]),
            "originalOccurredAt" to JsonPrimitive(row[OnCallIncidentTimeline.originalOccurredAt].toString()),
            "displayOrder" to JsonPrimitive(row[OnCallIncidentTimeline.displayOrder]),
            "annotation" to row[OnCallIncidentTimeline.annotation].toJsonElement(),
            "deletedAt" to row[OnCallIncidentTimeline.deletedAt]?.toString().toJsonElement(),
        )

    private fun timelineEntry(row: ResultRow, users: Map<Int, String>): IncidentTimelineEntry =
        IncidentTimelineEntry(
            id = row[OnCallIncidentTimeline.resourceId].toString(),
            eventKey = row[OnCallIncidentTimeline.eventKey],
            eventType = row[OnCallIncidentTimeline.eventType],
            actorUserId = row[OnCallIncidentTimeline.actorUserId]?.let(users::get),
            details = row[OnCallIncidentTimeline.details],
            sourceType = row[OnCallIncidentTimeline.sourceType],
            sourceReference = row[OnCallIncidentTimeline.sourceReference],
            sourceUrl = row[OnCallIncidentTimeline.sourceUrl],
            provenance = IncidentTimelineProvenance.valueOf(row[OnCallIncidentTimeline.provenance]),
            visibility = IncidentTimelineVisibility.valueOf(row[OnCallIncidentTimeline.visibility]),
            originalOccurredAt = row[OnCallIncidentTimeline.originalOccurredAt].toString(),
            observedAt = row[OnCallIncidentTimeline.observedAt].toString(),
            displayOrder = row[OnCallIncidentTimeline.displayOrder],
            annotation = row[OnCallIncidentTimeline.annotation],
            editedAt = row[OnCallIncidentTimeline.editedAt]?.toString(),
            deletedAt = row[OnCallIncidentTimeline.deletedAt]?.toString(),
            createdAt = row[OnCallIncidentTimeline.createdAt].toString(),
        )

    private fun publicUserIds(userIds: List<Int>): Map<Int, String> {
        if (userIds.isEmpty()) return emptyMap()
        return Users
            .selectAll()
            .where { Users.id inList userIds.distinct() }
            .associate { row -> row[Users.id] to row[Users.resource_id].toString() }
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf(String::isNotEmpty)
    private fun String?.toJsonElement(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

    private data class DeletedStateMutation(
        val organizationId: Int,
        val incidentId: Int,
        val eventResourceId: String,
        val actorUserId: Int,
        val deleted: Boolean,
        val reason: String?,
    )

    private data class TimelineRevisionRecord(
        val event: ResultRow,
        val action: String,
        val previous: Map<String, JsonElement>,
        val next: Map<String, JsonElement>,
        val reason: String?,
        val actorUserId: Int,
        val now: Instant,
    )

    companion object {
        private const val MAX_EVENT_TYPE_LENGTH = 80
        private const val DISPLAY_ORDER_STEP = 1_000_000L
    }
}
