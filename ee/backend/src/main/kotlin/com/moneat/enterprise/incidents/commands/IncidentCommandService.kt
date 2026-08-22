// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.commands

import com.moneat.alerts.models.IncidentSeverity
import com.moneat.enterprise.incidents.events.IncidentOutboxWriter
import com.moneat.enterprise.incidents.events.PendingNativeIncidentDomainEvent
import com.moneat.enterprise.incidents.models.NativeIncidentCommands
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.Memberships
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.postgresql.util.PSQLException
import kotlin.time.Clock
import kotlin.uuid.Uuid

class IncidentCommandService(
    private val policy: IncidentCommandPolicy = IncidentCommandPolicy(),
    private val outboxWriter: IncidentOutboxWriter = IncidentOutboxWriter(),
) {
    fun execute(command: IncidentCommand): IncidentCommandResult {
        policy.requireAllowed(command)
        return try {
            executeTransaction(command)
        } catch (e: ExposedSQLException) {
            if (e.sqlState != UNIQUE_VIOLATION_SQL_STATE) throw e
            if (e.violatesConstraint(COMMAND_KEY_CONSTRAINT)) {
                transaction {
                    requireActorMembership(command.actor)
                    replayResult(command)
                        ?: throw IncidentCommandConflictException("Incident command key is already in use")
                }
            } else {
                throw IncidentCommandConflictException("Incident mutation conflicts with existing state")
            }
        }
    }

    private fun executeTransaction(command: IncidentCommand): IncidentCommandResult =
        transaction {
            requireActorMembership(command.actor)
            replayResult(command)?.let { return@transaction it }
            val mutation = applyCommand(command)
            recordCommand(command, mutation)
            if (mutation.changed) {
                outboxWriter.record(
                    PendingNativeIncidentDomainEvent(
                        organizationId = command.actor.organizationId,
                        incidentId = mutation.incidentId,
                        eventType = "INCIDENT_${command.type.wire}",
                        aggregateVersion = mutation.version,
                        idempotencyKey = command.commandKey,
                        payload = mutation.eventPayload(command),
                    ),
                )
            }
            mutation.toResult(replayed = false)
        }

    private fun applyCommand(command: IncidentCommand): IncidentMutation =
        when (command) {
            is DeclareIncidentCommand -> declare(command)
            is AcceptIncidentCommand -> transition(command, NativeIncidentStatus.ACTIVE, null)
            is MergeIncidentCommand -> merge(command)
            is UpdateIncidentCommand -> update(command)
            is TransitionIncidentCommand -> transition(command, command.targetStatus, command.note)
            else -> applySupportingCommand(command)
        }

    private fun applySupportingCommand(command: IncidentCommand): IncidentMutation =
        when (command) {
            is AssignIncidentRoleCommand -> assignRole(command)
            is AddIncidentActionCommand -> addAction(command)
            is AddIncidentTimelineEventCommand -> addTimelineEvent(command)
            is LinkOnCallAlertCommand -> linkAlert(command)
            is ResolveIncidentCommand -> transition(command, NativeIncidentStatus.RESOLVED, command.note)
            is CancelIncidentCommand -> transition(command, NativeIncidentStatus.CANCELLED, command.reason)
            is ReopenIncidentCommand -> transition(command, NativeIncidentStatus.ACTIVE, command.reason)
            else -> error("Unsupported incident command: ${command.type.wire}")
        }

    private fun replayResult(command: IncidentCommand): IncidentCommandResult? {
        val existing =
            NativeIncidentCommands
                .selectAll()
                .where {
                    (NativeIncidentCommands.organizationId eq command.actor.organizationId) and
                        (NativeIncidentCommands.commandKey eq command.commandKey)
                }.singleOrNull() ?: return null
        if (existing[NativeIncidentCommands.commandType] != command.type.wire) {
            throw IncidentCommandConflictException("Incident command key was already used for another command")
        }
        if (existing[NativeIncidentCommands.requestFingerprint] != IncidentCommandFingerprint.of(command)) {
            throw IncidentCommandConflictException("Incident command key was already used for another request")
        }
        val incidentId = existing[NativeIncidentCommands.incidentId]
            ?: throw IncidentCommandConflictException("Incident command completed without an incident result")
        return loadMutation(incidentId, changed = false).toResult(replayed = true)
    }

    private fun requireActorMembership(actor: IncidentCommandActor) {
        val member =
            Memberships
                .selectAll()
                .where {
                    (Memberships.organization_id eq actor.organizationId) and
                        (Memberships.user_id eq actor.userId)
                }.limit(1)
                .singleOrNull()
        if (member == null) {
            throw IncidentCommandDeniedException("Actor is not a member of the incident organization")
        }
    }

    private fun declare(command: DeclareIncidentCommand): IncidentMutation {
        val title = command.title.trim()
        require(title.isNotEmpty()) { "Incident title is required" }
        require(title.length <= MAX_TITLE_LENGTH) { "Incident title is too long" }
        val severity =
            requireNotNull(IncidentSeverity.fromString(command.severity)) {
                "Invalid incident severity: ${command.severity}"
            }.wire
        require(command.initialStatus in DECLARABLE_STATUSES) {
            "Incidents can only be declared in TRIAGE or ACTIVE status"
        }
        command.onCallAlertId?.let { requireAlert(command.actor.organizationId, it) }
        val now = Clock.System.now()
        val incidentId =
            OnCallIncidents.insertAndGetId {
                it[resourceId] = Uuid.random()
                it[organizationId] = command.actor.organizationId
                it[OnCallIncidents.title] = title
                it[description] = command.description?.trim()?.takeIf(String::isNotEmpty)
                it[OnCallIncidents.severity] = severity
                it[status] = command.initialStatus.wire
                it[mode] = command.mode.wire
                it[visibility] = command.visibility.wire
                it[incidentType] = command.incidentType?.trim()?.takeIf(String::isNotEmpty)
                it[summary] = command.summary?.trim()?.takeIf(String::isNotEmpty)
                it[version] = INITIAL_VERSION
                it[declaredBy] = command.actor.userId
                it[declaredAt] = now
                it[triagedAt] = now.takeIf { command.initialStatus == NativeIncidentStatus.TRIAGE }
                it[acceptedAt] = now.takeIf { command.initialStatus == NativeIncidentStatus.ACTIVE }
                it[resolvedBy] = null
                it[resolvedAt] = null
                it[postIncidentAt] = null
                it[closedAt] = null
                it[cancelledAt] = null
                it[declinedAt] = null
                it[createdAt] = now
                it[updatedAt] = now
            }.value
        command.onCallAlertId?.let { alertId -> linkAlertRecord(incidentId, alertId) }
        insertTimelineEvent(
            incidentId = incidentId,
            actorUserId = command.actor.userId,
            eventType = "DECLARED",
            details = mapOf(
                "origin" to JsonPrimitive(command.actor.origin),
                "mode" to JsonPrimitive(command.mode.wire),
                "visibility" to JsonPrimitive(command.visibility.wire),
            ),
            at = now,
        )
        return loadMutation(incidentId)
    }

    private fun update(command: UpdateIncidentCommand): IncidentMutation {
        val current = requireIncident(command)
        val severity = command.severity?.let {
            requireNotNull(IncidentSeverity.fromString(it)) { "Invalid incident severity: $it" }.wire
        }
        command.title?.let {
            require(it.isNotBlank()) { "Incident title is required" }
            require(it.trim().length <= MAX_TITLE_LENGTH) { "Incident title is too long" }
        }
        val now = Clock.System.now()
        val nextVersion = current[OnCallIncidents.version] + 1
        val updated =
            OnCallIncidents.update({ versionPredicate(command, current) }) {
                command.title?.let { value -> it[title] = value.trim() }
                command.description?.let { value -> it[description] = value.trim().takeIf(String::isNotEmpty) }
                command.summary?.let { value -> it[summary] = value.trim().takeIf(String::isNotEmpty) }
                severity?.let { value -> it[OnCallIncidents.severity] = value }
                command.mode?.let { value -> it[mode] = value.wire }
                command.visibility?.let { value -> it[visibility] = value.wire }
                command.incidentType?.let { value -> it[incidentType] = value.trim().takeIf(String::isNotEmpty) }
                it[version] = nextVersion
                it[updatedAt] = now
            }
        requireVersionUpdate(updated, command.incidentId)
        insertTimelineEvent(
            command.incidentId,
            command.actor.userId,
            "UPDATED",
            mapOf("origin" to JsonPrimitive(command.actor.origin)),
            now,
        )
        return loadMutation(command.incidentId)
    }

    private fun transition(
        command: ExistingIncidentCommand,
        target: NativeIncidentStatus,
        note: String?,
    ): IncidentMutation {
        val current = requireIncident(command)
        val currentStatus = statusOf(current)
        if (currentStatus == target) return loadMutation(command.incidentId, changed = false)
        if (target !in allowedTransitions.getValue(currentStatus)) {
            throw IncidentCommandConflictException(
                "Cannot transition incident from ${currentStatus.wire} to ${target.wire}",
            )
        }
        val now = Clock.System.now()
        val nextVersion = current[OnCallIncidents.version] + 1
        val updated =
            OnCallIncidents.update({ versionPredicate(command, current) }) {
                it[status] = target.wire
                it[version] = nextVersion
                it[updatedAt] = now
                when (target) {
                    NativeIncidentStatus.TRIAGE -> {
                        it[triagedAt] = now
                        clearTerminalState(it)
                    }
                    NativeIncidentStatus.ACTIVE -> {
                        it[acceptedAt] = now
                        clearTerminalState(it)
                    }
                    NativeIncidentStatus.RESOLVED -> {
                        it[resolvedBy] = command.actor.userId
                        it[resolvedAt] = now
                    }
                    NativeIncidentStatus.POST_INCIDENT -> it[postIncidentAt] = now
                    NativeIncidentStatus.CLOSED -> it[closedAt] = now
                    NativeIncidentStatus.CANCELLED -> it[cancelledAt] = now
                    NativeIncidentStatus.DECLINED -> it[declinedAt] = now
                }
            }
        requireVersionUpdate(updated, command.incidentId)
        insertTimelineEvent(
            command.incidentId,
            command.actor.userId,
            target.timelineEventType(),
            transitionDetails(command.actor.origin, note, currentStatus),
            now,
        )
        return loadMutation(command.incidentId)
    }

    private fun assignRole(command: AssignIncidentRoleCommand): IncidentMutation {
        require(command.role.isNotBlank()) { "Incident role is required" }
        requireUserMembership(command.actor.organizationId, command.assigneeUserId)
        return appendVersionedEvent(
            command,
            "ROLE_ASSIGNED",
            mapOf(
                "role" to JsonPrimitive(command.role.trim()),
                "assigneeUserId" to JsonPrimitive(command.assigneeUserId),
            ),
        )
    }

    private fun addAction(command: AddIncidentActionCommand): IncidentMutation {
        require(command.title.isNotBlank()) { "Incident action title is required" }
        command.assigneeUserId?.let { requireUserMembership(command.actor.organizationId, it) }
        val details = mutableMapOf<String, JsonElement>("title" to JsonPrimitive(command.title.trim()))
        command.assigneeUserId?.let { details["assigneeUserId"] = JsonPrimitive(it) }
        return appendVersionedEvent(command, "ACTION_ADDED", details)
    }

    private fun addTimelineEvent(command: AddIncidentTimelineEventCommand): IncidentMutation {
        require(command.eventType.isNotBlank()) { "Incident timeline event type is required" }
        require(command.eventType.length <= MAX_TIMELINE_EVENT_TYPE_LENGTH) {
            "Incident timeline event type is too long"
        }
        return appendVersionedEvent(command, command.eventType.uppercase(), command.details)
    }

    private fun appendVersionedEvent(
        command: ExistingIncidentCommand,
        eventType: String,
        details: Map<String, JsonElement>,
    ): IncidentMutation {
        val current = requireIncident(command)
        val now = Clock.System.now()
        val nextVersion = current[OnCallIncidents.version] + 1
        val updated =
            OnCallIncidents.update({ versionPredicate(command, current) }) {
                it[version] = nextVersion
                it[updatedAt] = now
            }
        requireVersionUpdate(updated, command.incidentId)
        insertTimelineEvent(
            command.incidentId,
            command.actor.userId,
            eventType,
            details + ("origin" to JsonPrimitive(command.actor.origin)),
            now,
        )
        return loadMutation(command.incidentId)
    }

    private fun linkAlert(command: LinkOnCallAlertCommand): IncidentMutation {
        val current = requireIncident(command)
        requireAlert(command.actor.organizationId, command.alertId)
        val existing =
            OnCallIncidentAlerts
                .selectAll()
                .where { OnCallIncidentAlerts.alertId eq command.alertId }
                .singleOrNull()
        if (existing != null) {
            if (existing[OnCallIncidentAlerts.incidentId] != command.incidentId) {
                throw IncidentCommandConflictException("On-call alert is already linked to another native incident")
            }
            return loadMutation(command.incidentId, changed = false)
        }
        linkAlertRecord(command.incidentId, command.alertId)
        val now = Clock.System.now()
        val nextVersion = current[OnCallIncidents.version] + 1
        val updated =
            OnCallIncidents.update({ versionPredicate(command, current) }) {
                it[version] = nextVersion
                it[updatedAt] = now
            }
        requireVersionUpdate(updated, command.incidentId)
        val alert = requireAlert(command.actor.organizationId, command.alertId)
        insertTimelineEvent(
            command.incidentId,
            command.actor.userId,
            "ALERT_LINKED",
            mapOf(
                "origin" to JsonPrimitive(command.actor.origin),
                "alertId" to JsonPrimitive(alert[OnCallAlerts.resourceId].toString()),
                "alertTitle" to JsonPrimitive(alert[OnCallAlerts.title]),
            ),
            now,
        )
        return loadMutation(command.incidentId)
    }

    private fun merge(command: MergeIncidentCommand): IncidentMutation {
        require(command.incidentId != command.sourceIncidentId) { "Cannot merge an incident into itself" }
        val target = requireIncident(command)
        val source = requireIncident(command.actor.organizationId, command.sourceIncidentId)
        val sourceStatus = statusOf(source)
        if (NativeIncidentStatus.CANCELLED !in allowedTransitions.getValue(sourceStatus)) {
            throw IncidentCommandConflictException("An incident in ${sourceStatus.wire} status cannot be merged")
        }
        val sourceAlerts =
            OnCallIncidentAlerts
                .selectAll()
                .where { OnCallIncidentAlerts.incidentId eq command.sourceIncidentId }
                .map { it[OnCallIncidentAlerts.alertId] }
        OnCallIncidentAlerts.update({ OnCallIncidentAlerts.incidentId eq command.sourceIncidentId }) {
            it[incidentId] = command.incidentId
        }
        sourceAlerts.forEach { alertId ->
            OnCallAlerts.update({ OnCallAlerts.id eq alertId }) {
                it[declaredIncidentId] = command.incidentId
            }
        }
        val now = Clock.System.now()
        val targetVersion = target[OnCallIncidents.version] + 1
        requireVersionUpdate(
            OnCallIncidents.update({ versionPredicate(command, target) }) {
                it[version] = targetVersion
                it[updatedAt] = now
            },
            command.incidentId,
        )
        val sourceVersion = source[OnCallIncidents.version] + 1
        OnCallIncidents.update({
            (OnCallIncidents.id eq command.sourceIncidentId) and
                (OnCallIncidents.organizationId eq command.actor.organizationId) and
                (OnCallIncidents.version eq source[OnCallIncidents.version])
        }) {
            it[status] = NativeIncidentStatus.CANCELLED.wire
            it[version] = sourceVersion
            it[cancelledAt] = now
            it[updatedAt] = now
        }.also { requireVersionUpdate(it, command.sourceIncidentId) }
        val details = mapOf(
            "origin" to JsonPrimitive(command.actor.origin),
            "sourceIncidentId" to JsonPrimitive(source[OnCallIncidents.resourceId].toString()),
            "targetIncidentId" to JsonPrimitive(target[OnCallIncidents.resourceId].toString()),
        )
        insertTimelineEvent(command.incidentId, command.actor.userId, "INCIDENT_MERGED", details, now)
        insertTimelineEvent(command.sourceIncidentId, command.actor.userId, "MERGED_INTO_INCIDENT", details, now)
        outboxWriter.record(
            PendingNativeIncidentDomainEvent(
                organizationId = command.actor.organizationId,
                incidentId = command.sourceIncidentId,
                eventType = "INCIDENT_MERGED_SOURCE",
                aggregateVersion = sourceVersion,
                idempotencyKey = "${command.commandKey}:source",
                payload = details + ("status" to JsonPrimitive(NativeIncidentStatus.CANCELLED.wire)),
            ),
        )
        return loadMutation(command.incidentId)
    }

    private fun requireIncident(command: ExistingIncidentCommand): ResultRow {
        val row = requireIncident(command.actor.organizationId, command.incidentId)
        command.expectedVersion?.let { expected ->
            if (row[OnCallIncidents.version] != expected) {
                throw IncidentCommandConflictException(
                    "Stale incident version: expected $expected but was ${row[OnCallIncidents.version]}",
                )
            }
        }
        return row
    }

    private fun requireIncident(organizationId: Int, incidentId: Int): ResultRow =
        OnCallIncidents
            .selectAll()
            .where {
                (OnCallIncidents.id eq incidentId) and (OnCallIncidents.organizationId eq organizationId)
            }.singleOrNull()
            ?: throw IncidentCommandNotFoundException("Native incident not found")

    private fun requireAlert(organizationId: Int, alertId: Int): ResultRow =
        OnCallAlerts
            .selectAll()
            .where { (OnCallAlerts.id eq alertId) and (OnCallAlerts.organizationId eq organizationId) }
            .singleOrNull()
            ?: throw IncidentCommandNotFoundException("On-call alert not found")

    private fun requireUserMembership(organizationId: Int, userId: Int) {
        val found =
            Memberships
                .selectAll()
                .where {
                    (Memberships.organization_id eq organizationId) and (Memberships.user_id eq userId)
                }.limit(1)
                .singleOrNull() != null
        if (!found) throw IncidentCommandNotFoundException("Incident participant not found")
    }

    private fun versionPredicate(command: ExistingIncidentCommand, current: ResultRow) =
        (OnCallIncidents.id eq command.incidentId) and
            (OnCallIncidents.organizationId eq command.actor.organizationId) and
            (OnCallIncidents.version eq current[OnCallIncidents.version])

    private fun requireVersionUpdate(updated: Int, incidentId: Int) {
        if (updated != 1) {
            throw IncidentCommandConflictException("Concurrent incident update detected for incident $incidentId")
        }
    }

    private fun linkAlertRecord(incidentId: Int, alertId: Int) {
        OnCallIncidentAlerts.insert {
            it[OnCallIncidentAlerts.incidentId] = incidentId
            it[OnCallIncidentAlerts.alertId] = alertId
            it[statusOwner] = "INCIDENT"
            it[severityOwner] = "INCIDENT"
            it[resolutionOwner] = "INCIDENT"
        }
        OnCallAlerts.update({ OnCallAlerts.id eq alertId }) {
            it[declaredIncidentId] = incidentId
        }
    }

    private fun insertTimelineEvent(
        incidentId: Int,
        actorUserId: Int,
        eventType: String,
        details: Map<String, JsonElement>,
        at: kotlin.time.Instant,
    ) {
        OnCallIncidentTimeline.insert {
            it[OnCallIncidentTimeline.resourceId] = Uuid.random()
            it[OnCallIncidentTimeline.incidentId] = incidentId
            it[OnCallIncidentTimeline.eventType] = eventType
            it[OnCallIncidentTimeline.actorUserId] = actorUserId
            it[OnCallIncidentTimeline.details] = details
            it[OnCallIncidentTimeline.createdAt] = at
        }
    }

    private fun recordCommand(command: IncidentCommand, mutation: IncidentMutation) {
        val incidentId = mutation.incidentId
        NativeIncidentCommands.insert {
            it[resourceId] = Uuid.random()
            it[organizationId] = command.actor.organizationId
            it[NativeIncidentCommands.incidentId] = incidentId
            it[actorUserId] = command.actor.userId
            it[commandKey] = command.commandKey
            it[commandType] = command.type.wire
            it[requestFingerprint] = IncidentCommandFingerprint.of(command)
            it[expectedVersion] = command.expectedVersion
            it[resultVersion] = mutation.version
            it[createdAt] = Clock.System.now()
        }
    }

    private fun loadMutation(incidentId: Int, changed: Boolean = true): IncidentMutation {
        val row = OnCallIncidents.selectAll().where { OnCallIncidents.id eq incidentId }.single()
        return IncidentMutation(
            incidentId = incidentId,
            incidentResourceId = row[OnCallIncidents.resourceId].toString(),
            title = row[OnCallIncidents.title],
            severity = row[OnCallIncidents.severity],
            status = statusOf(row),
            version = row[OnCallIncidents.version],
            changed = changed,
        )
    }

    private fun statusOf(row: ResultRow): NativeIncidentStatus =
        checkNotNull(NativeIncidentStatus.fromWire(row[OnCallIncidents.status])) {
            "Unknown native incident status: ${row[OnCallIncidents.status]}"
        }

    private fun NativeIncidentStatus.timelineEventType(): String =
        when (this) {
            NativeIncidentStatus.TRIAGE -> "TRIAGED"
            NativeIncidentStatus.ACTIVE -> "ACCEPTED"
            NativeIncidentStatus.RESOLVED -> "RESOLVED"
            NativeIncidentStatus.POST_INCIDENT -> "POST_INCIDENT_STARTED"
            NativeIncidentStatus.CLOSED -> "CLOSED"
            NativeIncidentStatus.CANCELLED -> "CANCELLED"
            NativeIncidentStatus.DECLINED -> "DECLINED"
        }

    private fun transitionDetails(
        origin: String,
        note: String?,
        previousStatus: NativeIncidentStatus,
    ): Map<String, JsonElement> =
        buildMap {
            put("origin", JsonPrimitive(origin))
            put("previousStatus", JsonPrimitive(previousStatus.wire))
            note?.trim()?.takeIf(String::isNotEmpty)?.let { put("note", JsonPrimitive(it)) }
        }

    private fun IncidentMutation.eventPayload(command: IncidentCommand): Map<String, JsonElement> =
        mapOf(
            "incidentId" to JsonPrimitive(incidentResourceId),
            "title" to JsonPrimitive(title),
            "severity" to JsonPrimitive(severity),
            "status" to JsonPrimitive(status.wire),
            "version" to JsonPrimitive(version),
            "actorUserId" to JsonPrimitive(command.actor.userId),
            "origin" to JsonPrimitive(command.actor.origin),
            "commandType" to JsonPrimitive(command.type.wire),
        )

    private fun clearTerminalState(statement: UpdateBuilder<*>) {
        statement[OnCallIncidents.resolvedBy] = null
        statement[OnCallIncidents.resolvedAt] = null
        statement[OnCallIncidents.postIncidentAt] = null
        statement[OnCallIncidents.closedAt] = null
        statement[OnCallIncidents.cancelledAt] = null
        statement[OnCallIncidents.declinedAt] = null
    }

    private fun ExposedSQLException.violatesConstraint(constraintName: String): Boolean =
        generateSequence<Throwable>(this) { it.cause }
            .any { cause ->
                val postgresConstraint = (cause as? PSQLException)?.serverErrorMessage?.constraint
                postgresConstraint == constraintName ||
                    cause.message?.contains(constraintName, ignoreCase = true) == true
            }

    private fun IncidentMutation.toResult(replayed: Boolean) =
        IncidentCommandResult(
            incidentId = incidentId,
            incidentResourceId = incidentResourceId,
            status = status,
            version = version,
            replayed = replayed,
        )

    private data class IncidentMutation(
        val incidentId: Int,
        val incidentResourceId: String,
        val title: String,
        val severity: String,
        val status: NativeIncidentStatus,
        val version: Int,
        val changed: Boolean,
    )

    companion object {
        private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
        private const val COMMAND_KEY_CONSTRAINT = "uq_native_incident_commands_org_command_key"
        private const val INITIAL_VERSION = 1
        private const val MAX_TITLE_LENGTH = 255
        private const val MAX_TIMELINE_EVENT_TYPE_LENGTH = 30

        private val DECLARABLE_STATUSES = setOf(NativeIncidentStatus.TRIAGE, NativeIncidentStatus.ACTIVE)

        private val allowedTransitions = mapOf(
            NativeIncidentStatus.TRIAGE to setOf(
                NativeIncidentStatus.ACTIVE,
                NativeIncidentStatus.DECLINED,
                NativeIncidentStatus.CANCELLED,
            ),
            NativeIncidentStatus.ACTIVE to setOf(
                NativeIncidentStatus.RESOLVED,
                NativeIncidentStatus.CANCELLED,
            ),
            NativeIncidentStatus.RESOLVED to setOf(
                NativeIncidentStatus.POST_INCIDENT,
                NativeIncidentStatus.CLOSED,
                NativeIncidentStatus.ACTIVE,
            ),
            NativeIncidentStatus.POST_INCIDENT to setOf(
                NativeIncidentStatus.CLOSED,
                NativeIncidentStatus.ACTIVE,
            ),
            NativeIncidentStatus.CLOSED to setOf(NativeIncidentStatus.ACTIVE),
            NativeIncidentStatus.CANCELLED to setOf(NativeIncidentStatus.ACTIVE),
            NativeIncidentStatus.DECLINED to setOf(NativeIncidentStatus.ACTIVE),
        )
    }
}
