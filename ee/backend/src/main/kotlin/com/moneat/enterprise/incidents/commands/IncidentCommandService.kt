// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.commands

import com.moneat.alerts.models.IncidentSeverity
import com.moneat.alerts.models.AlertEpisodes
import com.moneat.enterprise.incidents.events.IncidentOutboxWriter
import com.moneat.enterprise.incidents.events.PendingNativeIncidentDomainEvent
import com.moneat.enterprise.incidents.models.NativeIncidentCommands
import com.moneat.enterprise.incidents.models.NativeIncidentAlertEpisodeLinks
import com.moneat.enterprise.incidents.models.NativeIncidentFormSubmissions
import com.moneat.enterprise.incidents.models.IncidentParticipationType
import com.moneat.enterprise.incidents.models.IncidentRoleEndReason
import com.moneat.enterprise.incidents.models.NativeIncidentHandovers
import com.moneat.enterprise.incidents.models.NativeIncidentParticipants
import com.moneat.enterprise.incidents.models.NativeIncidentRoleAssignments
import com.moneat.enterprise.incidents.models.NativeIncidentRoleDefinitions
import com.moneat.enterprise.incidents.models.NativeIncidentSourceLinks
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.timeline.IncidentTimelineWriter
import com.moneat.enterprise.incidents.timeline.PendingIncidentTimelineEvent
import com.moneat.enterprise.incidents.timeline.toIncidentTimelineProvenance
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Users
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.postgresql.util.PSQLException
import java.net.URI
import kotlin.time.Clock
import kotlin.uuid.Uuid

class IncidentCommandService(
    private val policy: IncidentCommandPolicy = IncidentCommandPolicy(),
    private val outboxWriter: IncidentOutboxWriter = IncidentOutboxWriter(),
    private val timelineWriter: IncidentTimelineWriter = IncidentTimelineWriter(),
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
            is AssignIncidentRoleCommand,
            is ClaimIncidentRoleCommand,
            is UnassignIncidentRoleCommand,
            is HandoverIncidentRoleCommand,
            is SetIncidentParticipationCommand,
            is LeaveIncidentCommand,
            -> applyResponderCommand(command)
            is AddIncidentActionCommand -> addAction(command)
            is AddIncidentTimelineEventCommand -> addTimelineEvent(command)
            is LinkOnCallAlertCommand -> linkAlert(command)
            is LinkIncidentSourceCommand -> linkSource(command)
            is UnlinkIncidentSourceCommand -> unlinkSource(command)
            is ResolveIncidentCommand -> transition(command, NativeIncidentStatus.RESOLVED, command.note)
            is CancelIncidentCommand -> transition(command, NativeIncidentStatus.CANCELLED, command.reason)
            is ReopenIncidentCommand -> transition(command, NativeIncidentStatus.ACTIVE, command.reason)
            else -> error("Unsupported incident command: ${command.type.wire}")
        }

    private fun applyResponderCommand(command: IncidentCommand): IncidentMutation =
        when (command) {
            is AssignIncidentRoleCommand -> assignRole(command)
            is ClaimIncidentRoleCommand -> claimRole(command)
            is UnassignIncidentRoleCommand -> unassignRole(command)
            is HandoverIncidentRoleCommand -> handoverRole(command)
            is SetIncidentParticipationCommand -> setParticipation(command)
            is LeaveIncidentCommand -> leaveIncident(command)
            else -> error("Unsupported incident responder command: ${command.type.wire}")
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
                it[incidentTypeDefinitionId] = command.incidentTypeDefinitionId
                it[declarationSnapshot] =
                    command.formDefinitionSnapshot + ("values" to JsonObject(command.formValues))
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
        if (command.formDefinitionId != null || command.formDefinitionSnapshot.isNotEmpty()) {
            NativeIncidentFormSubmissions.insert {
                it[NativeIncidentFormSubmissions.resourceId] = Uuid.random()
                it[NativeIncidentFormSubmissions.organizationId] = command.actor.organizationId
                it[NativeIncidentFormSubmissions.incidentId] = incidentId
                it[NativeIncidentFormSubmissions.formId] = command.formDefinitionId
                it[NativeIncidentFormSubmissions.stage] = "DECLARATION"
                it[NativeIncidentFormSubmissions.definitionSnapshot] = command.formDefinitionSnapshot
                it[NativeIncidentFormSubmissions.valuesSnapshot] = command.formValues
                it[NativeIncidentFormSubmissions.submittedBy] = command.actor.userId
                it[NativeIncidentFormSubmissions.submittedAt] = now
            }
        }
        command.onCallAlertId?.let { alertId ->
            linkAlertRecord(incidentId, alertId)
            val alert = requireAlert(command.actor.organizationId, alertId)
            insertSourceLink(
                command.actor,
                incidentId,
                IncidentSourceReference(
                    sourceType = com.moneat.enterprise.incidents.models.IncidentSourceType.ON_CALL_ALERT,
                    sourceKey = alert[OnCallAlerts.resourceId].toString(),
                    onCallAlertId = alertId,
                    label = alert[OnCallAlerts.title],
                ),
                now,
            )
        }
        insertTimelineEvent(
            command,
            CommandTimelineEvent(
                incidentId = incidentId,
                eventKey = command.commandKey,
                eventType = "DECLARED",
                details = mapOf(
                    "origin" to JsonPrimitive(command.actor.origin),
                    "mode" to JsonPrimitive(command.mode.wire),
                    "visibility" to JsonPrimitive(command.visibility.wire),
                ),
                at = now,
            ),
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
            command,
            CommandTimelineEvent(
                command.incidentId,
                command.commandKey,
                "UPDATED",
                mapOf("origin" to JsonPrimitive(command.actor.origin)),
                now,
            ),
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
            command,
            CommandTimelineEvent(
                command.incidentId,
                command.commandKey,
                target.timelineEventType(),
                transitionDetails(command.actor.origin, note, currentStatus),
                now,
            ),
        )
        return loadMutation(command.incidentId)
    }

    private fun assignRole(command: AssignIncidentRoleCommand): IncidentMutation {
        requireIncident(command)
        val role = requireRoleDefinition(command.actor.organizationId, command.roleDefinitionId)
        requireUserMembership(command.actor.organizationId, command.assigneeUserId)
        val active = activeRoleAssignment(command.incidentId, command.roleDefinitionId)
        if (active?.get(NativeIncidentRoleAssignments.assigneeUserId) == command.assigneeUserId) {
            return loadMutation(command.incidentId, changed = false)
        }
        val now = Clock.System.now()
        active?.let {
            endRoleAssignment(it, command.actor.userId, IncidentRoleEndReason.REASSIGNED, now)
        }
        NativeIncidentRoleAssignments.insert {
            it[resourceId] = Uuid.random()
            it[organizationId] = command.actor.organizationId
            it[incidentId] = command.incidentId
            it[roleDefinitionId] = command.roleDefinitionId
            it[assigneeUserId] = command.assigneeUserId
            it[assignedBy] = command.actor.userId
            it[assignedAt] = now
            it[endedBy] = null
            it[endedAt] = null
            it[endReason] = null
        }
        ensureParticipant(command, command.assigneeUserId, now)
        val mutation = appendVersionedEvent(
            command,
            "ROLE_ASSIGNED",
            mapOf(
                "roleId" to JsonPrimitive(role[NativeIncidentRoleDefinitions.resourceId].toString()),
                "role" to JsonPrimitive(role[NativeIncidentRoleDefinitions.name]),
                "assigneeUserId" to JsonPrimitive(userResourceId(command.assigneeUserId)),
            ),
        )
        recordPrivateRoleInstructions(command, role, command.assigneeUserId, mutation.version)
        return mutation
    }

    private fun claimRole(command: ClaimIncidentRoleCommand): IncidentMutation =
        assignRole(
            AssignIncidentRoleCommand(
                commandKey = command.commandKey,
                actor = command.actor,
                incidentId = command.incidentId,
                roleDefinitionId = command.roleDefinitionId,
                assigneeUserId = command.actor.userId,
                expectedVersion = command.expectedVersion,
            ),
        )

    private fun unassignRole(command: UnassignIncidentRoleCommand): IncidentMutation {
        requireIncident(command)
        val role = requireRoleDefinition(command.actor.organizationId, command.roleDefinitionId)
        val active = activeRoleAssignment(command.incidentId, command.roleDefinitionId)
            ?: return loadMutation(command.incidentId, changed = false)
        val now = Clock.System.now()
        val assigneeUserId = active[NativeIncidentRoleAssignments.assigneeUserId]
        endRoleAssignment(active, command.actor.userId, IncidentRoleEndReason.UNASSIGNED, now)
        return appendVersionedEvent(
            command,
            "ROLE_UNASSIGNED",
            mapOf(
                "roleId" to JsonPrimitive(role[NativeIncidentRoleDefinitions.resourceId].toString()),
                "role" to JsonPrimitive(role[NativeIncidentRoleDefinitions.name]),
                "assigneeUserId" to JsonPrimitive(userResourceId(assigneeUserId)),
            ),
        )
    }

    private fun handoverRole(command: HandoverIncidentRoleCommand): IncidentMutation {
        requireIncident(command)
        val role = requireRoleDefinition(command.actor.organizationId, command.roleDefinitionId)
        requireUserMembership(command.actor.organizationId, command.toUserId)
        val active = activeRoleAssignment(command.incidentId, command.roleDefinitionId)
            ?: throw IncidentCommandConflictException("Incident role is not assigned")
        val fromUserId = active[NativeIncidentRoleAssignments.assigneeUserId]
        if (fromUserId == command.toUserId) return loadMutation(command.incidentId, changed = false)
        val now = Clock.System.now()
        endRoleAssignment(active, command.actor.userId, IncidentRoleEndReason.HANDOVER, now)
        val nextAssignmentId =
            NativeIncidentRoleAssignments.insertAndGetId {
                it[resourceId] = Uuid.random()
                it[organizationId] = command.actor.organizationId
                it[incidentId] = command.incidentId
                it[roleDefinitionId] = command.roleDefinitionId
                it[assigneeUserId] = command.toUserId
                it[assignedBy] = command.actor.userId
                it[assignedAt] = now
                it[endedBy] = null
                it[endedAt] = null
                it[endReason] = null
            }.value
        NativeIncidentHandovers.insert {
            it[resourceId] = Uuid.random()
            it[organizationId] = command.actor.organizationId
            it[incidentId] = command.incidentId
            it[roleDefinitionId] = command.roleDefinitionId
            it[fromAssignmentId] = active[NativeIncidentRoleAssignments.id].value
            it[toAssignmentId] = nextAssignmentId
            it[handedOverBy] = command.actor.userId
            it[note] = command.note.cleaned()
            it[createdAt] = now
        }
        ensureParticipant(command, command.toUserId, now)
        val details = buildMap<String, JsonElement> {
            put("roleId", JsonPrimitive(role[NativeIncidentRoleDefinitions.resourceId].toString()))
            put("role", JsonPrimitive(role[NativeIncidentRoleDefinitions.name]))
            put("fromUserId", JsonPrimitive(userResourceId(fromUserId)))
            put("toUserId", JsonPrimitive(userResourceId(command.toUserId)))
            command.note.cleaned()?.let { put("note", JsonPrimitive(it)) }
        }
        val mutation = appendVersionedEvent(command, "ROLE_HANDED_OVER", details)
        recordPrivateRoleInstructions(command, role, command.toUserId, mutation.version)
        return mutation
    }

    private fun setParticipation(command: SetIncidentParticipationCommand): IncidentMutation {
        requireIncident(command)
        requireUserMembership(command.actor.organizationId, command.userId)
        val now = Clock.System.now()
        val changed = setActiveParticipation(command, command.userId, command.participationType, now)
        if (!changed) return loadMutation(command.incidentId, changed = false)
        val eventType = when (command.participationType) {
            IncidentParticipationType.PARTICIPANT -> "PARTICIPANT_JOINED"
            IncidentParticipationType.OBSERVER -> "OBSERVER_JOINED"
        }
        return appendVersionedEvent(
            command,
            eventType,
            mapOf(
                "userId" to JsonPrimitive(userResourceId(command.userId)),
                "participationType" to JsonPrimitive(command.participationType.wire),
            ),
        )
    }

    private fun leaveIncident(command: LeaveIncidentCommand): IncidentMutation {
        requireIncident(command)
        val active = activeParticipant(command.incidentId, command.userId)
            ?: return loadMutation(command.incidentId, changed = false)
        val now = Clock.System.now()
        NativeIncidentParticipants.update({ NativeIncidentParticipants.id eq active[NativeIncidentParticipants.id] }) {
            it[leftBy] = command.actor.userId
            it[leftAt] = now
        }
        return appendVersionedEvent(
            command,
            "PARTICIPANT_LEFT",
            mapOf(
                "userId" to JsonPrimitive(userResourceId(command.userId)),
                "participationType" to JsonPrimitive(active[NativeIncidentParticipants.participationType]),
            ),
        )
    }

    private fun requireRoleDefinition(organizationId: Int, roleDefinitionId: Int): ResultRow =
        NativeIncidentRoleDefinitions
            .selectAll()
            .where {
                (NativeIncidentRoleDefinitions.id eq roleDefinitionId) and
                    (NativeIncidentRoleDefinitions.organizationId eq organizationId)
            }.singleOrNull()
            ?: throw IncidentCommandNotFoundException("Incident role not found")

    private fun activeRoleAssignment(incidentId: Int, roleDefinitionId: Int): ResultRow? =
        NativeIncidentRoleAssignments
            .selectAll()
            .where {
                (NativeIncidentRoleAssignments.incidentId eq incidentId) and
                    (NativeIncidentRoleAssignments.roleDefinitionId eq roleDefinitionId) and
                    NativeIncidentRoleAssignments.endedAt.isNull()
            }.singleOrNull()

    private fun endRoleAssignment(
        assignment: ResultRow,
        actorUserId: Int,
        reason: IncidentRoleEndReason,
        now: kotlin.time.Instant,
    ) {
        NativeIncidentRoleAssignments.update({
            NativeIncidentRoleAssignments.id eq assignment[NativeIncidentRoleAssignments.id]
        }) {
            it[endedBy] = actorUserId
            it[endedAt] = now
            it[endReason] = reason.wire
        }
    }

    private fun ensureParticipant(
        command: ExistingIncidentCommand,
        userId: Int,
        now: kotlin.time.Instant,
    ) {
        setActiveParticipation(command, userId, IncidentParticipationType.PARTICIPANT, now)
    }

    private fun setActiveParticipation(
        command: ExistingIncidentCommand,
        userId: Int,
        participationType: IncidentParticipationType,
        now: kotlin.time.Instant,
    ): Boolean {
        val active = activeParticipant(command.incidentId, userId)
        if (active?.get(NativeIncidentParticipants.participationType) == participationType.wire) return false
        active?.let { row ->
            NativeIncidentParticipants.update({ NativeIncidentParticipants.id eq row[NativeIncidentParticipants.id] }) {
                it[leftBy] = command.actor.userId
                it[leftAt] = now
            }
        }
        NativeIncidentParticipants.insert {
            it[resourceId] = Uuid.random()
            it[organizationId] = command.actor.organizationId
            it[incidentId] = command.incidentId
            it[NativeIncidentParticipants.userId] = userId
            it[NativeIncidentParticipants.participationType] = participationType.wire
            it[joinedBy] = command.actor.userId
            it[joinedAt] = now
            it[leftBy] = null
            it[leftAt] = null
        }
        return true
    }

    private fun activeParticipant(incidentId: Int, userId: Int): ResultRow? =
        NativeIncidentParticipants
            .selectAll()
            .where {
                (NativeIncidentParticipants.incidentId eq incidentId) and
                    (NativeIncidentParticipants.userId eq userId) and
                    NativeIncidentParticipants.leftAt.isNull()
            }.singleOrNull()

    private fun recordPrivateRoleInstructions(
        command: ExistingIncidentCommand,
        role: ResultRow,
        assigneeUserId: Int,
        version: Int,
    ) {
        val instructions = role[NativeIncidentRoleDefinitions.privateInstructions].cleaned() ?: return
        outboxWriter.record(
            PendingNativeIncidentDomainEvent(
                organizationId = command.actor.organizationId,
                incidentId = command.incidentId,
                eventType = "INCIDENT_ROLE_INSTRUCTIONS",
                aggregateVersion = version,
                idempotencyKey = "${command.commandKey}:private-instructions",
                payload = mapOf(
                    "incidentId" to JsonPrimitive(loadMutation(command.incidentId).incidentResourceId),
                    "roleId" to JsonPrimitive(role[NativeIncidentRoleDefinitions.resourceId].toString()),
                    "role" to JsonPrimitive(role[NativeIncidentRoleDefinitions.name]),
                    "assigneeUserId" to JsonPrimitive(userResourceId(assigneeUserId)),
                    "instructions" to JsonPrimitive(instructions),
                    "visibility" to JsonPrimitive("PRIVATE"),
                ),
            ),
        )
    }

    private fun addAction(command: AddIncidentActionCommand): IncidentMutation {
        require(command.title.isNotBlank()) { "Incident action title is required" }
        command.assigneeUserId?.let { requireUserMembership(command.actor.organizationId, it) }
        val details = mutableMapOf<String, JsonElement>("title" to JsonPrimitive(command.title.trim()))
        command.assigneeUserId?.let { details["assigneeUserId"] = JsonPrimitive(userResourceId(it)) }
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
            command,
            CommandTimelineEvent(
                command.incidentId,
                command.commandKey,
                eventType,
                details + ("origin" to JsonPrimitive(command.actor.origin)),
                now,
            ),
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
        insertSourceLink(
            command.actor,
            command.incidentId,
            IncidentSourceReference(
                sourceType = com.moneat.enterprise.incidents.models.IncidentSourceType.ON_CALL_ALERT,
                sourceKey = alert[OnCallAlerts.resourceId].toString(),
                onCallAlertId = command.alertId,
                label = alert[OnCallAlerts.title],
            ),
            now,
        )
        insertTimelineEvent(
            command,
            CommandTimelineEvent(
                incidentId = command.incidentId,
                eventKey = command.commandKey,
                eventType = "ALERT_LINKED",
                details = mapOf(
                    "origin" to JsonPrimitive(command.actor.origin),
                    "alertId" to JsonPrimitive(alert[OnCallAlerts.resourceId].toString()),
                    "alertTitle" to JsonPrimitive(alert[OnCallAlerts.title]),
                ),
                at = now,
            ),
        )
        return loadMutation(command.incidentId)
    }

    private fun linkSource(command: LinkIncidentSourceCommand): IncidentMutation {
        requireIncident(command)
        val source = validateSource(command.actor.organizationId, command.source)
        val existing =
            NativeIncidentSourceLinks
                .selectAll()
                .where {
                    (NativeIncidentSourceLinks.incidentId eq command.incidentId) and
                        (NativeIncidentSourceLinks.sourceType eq source.sourceType.wire) and
                        (NativeIncidentSourceLinks.sourceKey eq source.sourceKey)
                }.singleOrNull()
        if (existing != null) return loadMutation(command.incidentId, changed = false)

        val now = Clock.System.now()
        when (source.sourceType) {
            com.moneat.enterprise.incidents.models.IncidentSourceType.ON_CALL_ALERT ->
                linkAlertRecord(command.incidentId, checkNotNull(source.onCallAlertId))
            com.moneat.enterprise.incidents.models.IncidentSourceType.ALERT_EPISODE ->
                linkAlertEpisodeRecord(command, checkNotNull(source.alertEpisodeId), now)
            else -> Unit
        }
        insertSourceLink(command.actor, command.incidentId, source, now)
        return appendVersionedEvent(
            command,
            "SOURCE_LINKED",
            sourceTimelineDetails(command.actor.origin, source),
        )
    }

    private fun unlinkSource(command: UnlinkIncidentSourceCommand): IncidentMutation {
        requireIncident(command)
        val sourceKey = command.sourceKey.trim()
        require(sourceKey.isNotEmpty()) { "Incident source key is required" }
        val row =
            NativeIncidentSourceLinks
                .selectAll()
                .where {
                    (NativeIncidentSourceLinks.organizationId eq command.actor.organizationId) and
                        (NativeIncidentSourceLinks.incidentId eq command.incidentId) and
                        (NativeIncidentSourceLinks.sourceType eq command.sourceType.wire) and
                        (NativeIncidentSourceLinks.sourceKey eq sourceKey)
                }.singleOrNull() ?: return loadMutation(command.incidentId, changed = false)
        row[NativeIncidentSourceLinks.onCallAlertId]?.let { alertId ->
            OnCallIncidentAlerts.deleteWhere {
                (incidentId eq command.incidentId) and (OnCallIncidentAlerts.alertId eq alertId)
            }
            OnCallAlerts.update({
                (OnCallAlerts.id eq alertId) and (OnCallAlerts.declaredIncidentId eq command.incidentId)
            }) {
                it[declaredIncidentId] = null
            }
        }
        row[NativeIncidentSourceLinks.alertEpisodeId]?.let { episodeId ->
            NativeIncidentAlertEpisodeLinks.deleteWhere {
                (incidentId eq command.incidentId) and (alertEpisodeId eq episodeId)
            }
        }
        NativeIncidentSourceLinks.deleteWhere { id eq row[NativeIncidentSourceLinks.id] }
        return appendVersionedEvent(
            command,
            "SOURCE_UNLINKED",
            mapOf(
                "origin" to JsonPrimitive(command.actor.origin),
                "sourceType" to JsonPrimitive(command.sourceType.wire),
                "sourceKey" to JsonPrimitive(sourceKey),
            ),
        )
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
        insertTimelineEvent(
            command,
            CommandTimelineEvent(
                command.incidentId,
                "${command.commandKey}:target",
                "INCIDENT_MERGED",
                details,
                now,
            ),
        )
        insertTimelineEvent(
            command,
            CommandTimelineEvent(
                command.sourceIncidentId,
                "${command.commandKey}:source",
                "MERGED_INTO_INCIDENT",
                details,
                now,
            ),
        )
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

    private fun userResourceId(userId: Int): String =
        Users
            .selectAll()
            .where { Users.id eq userId }
            .single()[Users.resource_id]
            .toString()

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
        val existing =
            OnCallIncidentAlerts
                .selectAll()
                .where { OnCallIncidentAlerts.alertId eq alertId }
                .singleOrNull()
        if (existing != null) {
            if (existing[OnCallIncidentAlerts.incidentId] != incidentId) {
                throw IncidentCommandConflictException("On-call alert is already linked to another native incident")
            }
            return
        }
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

    private fun validateSource(
        organizationId: Int,
        source: IncidentSourceReference,
    ): IncidentSourceReference {
        val sourceKey = source.sourceKey.trim()
        require(sourceKey.isNotEmpty()) { "Incident source key is required" }
        require(sourceKey.length <= MAX_SOURCE_KEY_LENGTH) { "Incident source key is too long" }
        return when (source.sourceType) {
            com.moneat.enterprise.incidents.models.IncidentSourceType.ON_CALL_ALERT -> {
                val alertId = requireNotNull(source.onCallAlertId) { "On-call alert source is missing its alert" }
                require(source.alertEpisodeId == null) { "On-call alert source has an unexpected episode" }
                val alert = requireAlert(organizationId, alertId)
                source.copy(
                    sourceKey = alert[OnCallAlerts.resourceId].toString(),
                    label = source.label.cleaned() ?: alert[OnCallAlerts.title],
                )
            }
            com.moneat.enterprise.incidents.models.IncidentSourceType.ALERT_EPISODE -> {
                val episodeId = requireNotNull(source.alertEpisodeId) { "Alert episode source is missing its episode" }
                require(source.onCallAlertId == null) { "Alert episode source has an unexpected on-call alert" }
                val episode =
                    AlertEpisodes
                        .selectAll()
                        .where {
                            (AlertEpisodes.id eq episodeId) and
                                (AlertEpisodes.organizationId eq organizationId)
                        }.singleOrNull()
                        ?: throw IncidentCommandNotFoundException("Alert episode not found")
                source.copy(
                    sourceKey = episode[AlertEpisodes.resourceId].toString(),
                    label = source.label.cleaned() ?: episode[AlertEpisodes.title],
                )
            }
            else -> {
                require(source.onCallAlertId == null && source.alertEpisodeId == null) {
                    "External incident source has an unexpected internal pointer"
                }
                val sourceUrl = source.sourceUrl.cleaned()
                if (source.sourceType == com.moneat.enterprise.incidents.models.IncidentSourceType.URL) {
                    require(sourceUrl != null) { "URL incident source requires a URL" }
                }
                sourceUrl?.let(::requireSafeExternalSourceUrl)
                source.copy(
                    sourceKey = sourceKey,
                    label = source.label.cleaned(),
                    sourceUrl = sourceUrl,
                )
            }
        }
    }

    private fun requireSafeExternalSourceUrl(value: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        require(
            uri != null &&
                uri.isAbsolute &&
                uri.rawAuthority?.isNotBlank() == true &&
                uri.scheme.lowercase() in SAFE_EXTERNAL_SOURCE_SCHEMES,
        ) { "Incident source URL must use HTTP or HTTPS" }
    }

    private fun linkAlertEpisodeRecord(
        command: LinkIncidentSourceCommand,
        alertEpisodeId: Int,
        now: kotlin.time.Instant,
    ) {
        NativeIncidentAlertEpisodeLinks.insert {
            it[resourceId] = Uuid.random()
            it[organizationId] = command.actor.organizationId
            it[incidentId] = command.incidentId
            it[NativeIncidentAlertEpisodeLinks.alertEpisodeId] = alertEpisodeId
            it[statusOwner] = "INCIDENT"
            it[severityOwner] = "INCIDENT"
            it[resolutionOwner] = "INCIDENT"
            it[createdAt] = now
        }
    }

    private fun insertSourceLink(
        actor: IncidentCommandActor,
        incidentId: Int,
        source: IncidentSourceReference,
        now: kotlin.time.Instant,
    ) {
        NativeIncidentSourceLinks.insert {
            it[resourceId] = Uuid.random()
            it[organizationId] = actor.organizationId
            it[NativeIncidentSourceLinks.incidentId] = incidentId
            it[sourceType] = source.sourceType.wire
            it[sourceKey] = source.sourceKey
            it[onCallAlertId] = source.onCallAlertId
            it[alertEpisodeId] = source.alertEpisodeId
            it[label] = source.label
            it[sourceUrl] = source.sourceUrl
            it[metadata] = source.metadata
            it[linkedBy] = actor.userId
            it[createdAt] = now
        }
    }

    private fun sourceTimelineDetails(
        origin: String,
        source: IncidentSourceReference,
    ): Map<String, JsonElement> =
        buildMap {
            put("origin", JsonPrimitive(origin))
            put("sourceType", JsonPrimitive(source.sourceType.wire))
            put("sourceKey", JsonPrimitive(source.sourceKey))
            source.label?.let { put("label", JsonPrimitive(it)) }
            source.sourceUrl?.let { put("sourceUrl", JsonPrimitive(it)) }
        }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun insertTimelineEvent(
        command: IncidentCommand,
        event: CommandTimelineEvent,
    ) {
        timelineWriter.record(
            PendingIncidentTimelineEvent(
                organizationId = command.actor.organizationId,
                incidentId = event.incidentId,
                eventKey = event.eventKey,
                eventType = event.eventType,
                actorUserId = command.actor.userId,
                details = event.details,
                provenance = command.actor.origin.toIncidentTimelineProvenance(),
                originalOccurredAt = event.at,
            ),
        )
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

    private data class CommandTimelineEvent(
        val incidentId: Int,
        val eventKey: String,
        val eventType: String,
        val details: Map<String, JsonElement>,
        val at: kotlin.time.Instant,
    )

    companion object {
        private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
        private const val COMMAND_KEY_CONSTRAINT = "uq_native_incident_commands_org_command_key"
        private const val INITIAL_VERSION = 1
        private const val MAX_TITLE_LENGTH = 255
        private const val MAX_TIMELINE_EVENT_TYPE_LENGTH = 80
        private const val MAX_SOURCE_KEY_LENGTH = 500
        private val SAFE_EXTERNAL_SOURCE_SCHEMES = setOf("http", "https")

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
