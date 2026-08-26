// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.commands

import com.moneat.alerts.models.IncidentSeverity
import com.moneat.alerts.models.AlertEpisodes
import com.moneat.enterprise.incidents.config.IncidentConfigurationService
import com.moneat.enterprise.incidents.config.ResolvedIncidentForm
import com.moneat.enterprise.incidents.events.IncidentOutboxWriter
import com.moneat.enterprise.incidents.events.PendingNativeIncidentDomainEvent
import com.moneat.enterprise.incidents.models.NativeIncidentCommands
import com.moneat.enterprise.incidents.models.NativeIncidentAlertEpisodeLinks
import com.moneat.enterprise.incidents.models.NativeIncidentFormSubmissions
import com.moneat.enterprise.incidents.models.NativeIncidentTypes
import com.moneat.enterprise.incidents.models.IncidentFormStage
import com.moneat.enterprise.incidents.models.IncidentParticipationType
import com.moneat.enterprise.incidents.models.IncidentRoleEndReason
import com.moneat.enterprise.incidents.models.NativeIncidentHandovers
import com.moneat.enterprise.incidents.models.NativeIncidentParticipants
import com.moneat.enterprise.incidents.models.NativeIncidentRoleAssignments
import com.moneat.enterprise.incidents.models.NativeIncidentRoleDefinitions
import com.moneat.enterprise.incidents.models.NativeIncidentSourceLinks
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.IncidentUpdateRequestStatus
import com.moneat.enterprise.incidents.models.NativeIncidentUpdateRequests
import com.moneat.enterprise.incidents.models.IncidentActionState
import com.moneat.enterprise.incidents.models.NativeIncidentActions
import com.moneat.enterprise.incidents.models.NativeIncidentActionEvents
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
import org.jetbrains.exposed.v1.core.inList
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
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class IncidentCommandService(
    private val policy: IncidentCommandPolicy = IncidentCommandPolicy(),
    private val outboxWriter: IncidentOutboxWriter = IncidentOutboxWriter(),
    private val timelineWriter: IncidentTimelineWriter = IncidentTimelineWriter(),
    private val configurationService: IncidentConfigurationService = IncidentConfigurationService(),
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
            policy.requireQuota(command)
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
            is AcceptIncidentCommand -> accept(command)
            is DeclineIncidentCommand -> transition(command, NativeIncidentStatus.DECLINED, command.reason)
            is MergeIncidentCommand -> merge(command)
            is UpdateIncidentCommand -> update(command)
            is RequestIncidentUpdateCommand -> requestUpdate(command)
            is PauseIncidentUpdateRemindersCommand -> pauseUpdateReminders(command)
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
            is AddIncidentActionCommand,
            is ClaimIncidentActionCommand,
            is ReassignIncidentActionCommand,
            is CompleteIncidentActionCommand,
            is CancelIncidentActionCommand,
            is ConvertIncidentActionToFollowUpCommand,
            -> applyActionCommand(command)
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

    private fun applyActionCommand(command: IncidentCommand): IncidentMutation =
        when (command) {
            is AddIncidentActionCommand -> addAction(command)
            is ClaimIncidentActionCommand -> claimAction(command)
            is ReassignIncidentActionCommand -> reassignAction(command)
            is CompleteIncidentActionCommand -> completeAction(command)
            is CancelIncidentActionCommand -> cancelAction(command)
            is ConvertIncidentActionToFollowUpCommand -> convertAction(command)
            else -> error("Unsupported incident action command: ${command.type.wire}")
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
        return loadMutation(incidentId, changed = false).copy(
            actionResourceId = existing[NativeIncidentCommands.actionResourceId]?.toString(),
        ).toResult(replayed = true)
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
        require(command.initialStatus in DECLARABLE_STATUSES) {
            "Incidents can only be declared in TRIAGE or ACTIVE status"
        }
        val severity = command.severity?.let(::requireSeverity)
        require(severity != null || command.initialStatus == NativeIncidentStatus.TRIAGE) {
            "Incident severity is required to declare an active incident"
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
                it[mergedAt] = null
                it[mergedIntoIncidentId] = null
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
        val prepared = prepareUpdate(command, current)
        val now = Clock.System.now()
        val nextVersion = current[OnCallIncidents.version] + 1
        val updated =
            OnCallIncidents.update({ versionPredicate(command, current) }) {
                applyUpdateFields(it, command, prepared, now)
                it[version] = nextVersion
                it[updatedAt] = now
            }
        requireVersionUpdate(updated, command.incidentId)
        fulfillUpdateRequests(command.actor.organizationId, command.incidentId, now)
        command.pauseUpdateReminders?.let { paused ->
            setUpdateRequestStatus(
                UpdateRequestStatusChange(
                    organizationId = command.actor.organizationId,
                    incidentId = command.incidentId,
                    from = if (paused) IncidentUpdateRequestStatus.OPEN else IncidentUpdateRequestStatus.PAUSED,
                    to = if (paused) IncidentUpdateRequestStatus.PAUSED else IncidentUpdateRequestStatus.OPEN,
                    now = now,
                    dueAt = command.nextUpdateAt,
                ),
            )
        }
        insertTimelineEvent(
            command,
            CommandTimelineEvent(
                command.incidentId,
                command.commandKey,
                "UPDATED",
                buildMap {
                    put("origin", JsonPrimitive(command.actor.origin))
                    prepared.targetStatus?.let { put("status", JsonPrimitive(it.wire)) }
                    prepared.message?.let { put("message", JsonPrimitive(it)) }
                    prepared.customerImpact?.let { put("customerImpact", JsonPrimitive(it)) }
                    prepared.nextUpdateAt?.let { put("nextUpdateAt", JsonPrimitive(it.toString())) }
                },
                now,
            ),
        )
        return loadMutation(command.incidentId)
    }

    private fun prepareUpdate(command: UpdateIncidentCommand, current: ResultRow): PreparedIncidentUpdate {
        val currentStatus = statusOf(current)
        val targetStatus = command.status
        if (targetStatus != null && targetStatus != currentStatus) {
            requireTransitionAllowed(currentStatus, targetStatus)
            require(currentStatus != NativeIncidentStatus.TRIAGE) {
                "Triage incidents must be accepted before their lifecycle status can be changed"
            }
        }
        command.title?.let {
            require(it.isNotBlank()) { "Incident title is required" }
            require(it.trim().length <= MAX_TITLE_LENGTH) { "Incident title is too long" }
        }
        val message = command.message?.trim()?.takeIf(String::isNotEmpty)
        val customerImpact = command.customerImpact?.trim()?.takeIf(String::isNotEmpty)
        require(message == null || message.length <= MAX_UPDATE_MESSAGE_LENGTH) {
            "Incident update message is too long"
        }
        require(customerImpact == null || customerImpact.length <= MAX_CUSTOMER_IMPACT_LENGTH) {
            "Customer impact is too long"
        }
        return PreparedIncidentUpdate(
            severity = command.severity?.let(::requireSeverity),
            message = message,
            customerImpact = customerImpact,
            nextUpdateAt = when {
                command.clearNextUpdateAt -> null
                command.nextUpdateAt != null -> command.nextUpdateAt
                else -> current[OnCallIncidents.nextUpdateAt]
            },
            targetStatus = targetStatus,
        )
    }

    private fun applyUpdateFields(
        statement: UpdateBuilder<*>,
        command: UpdateIncidentCommand,
        prepared: PreparedIncidentUpdate,
        now: kotlin.time.Instant,
    ) {
        command.title?.let { statement[OnCallIncidents.title] = it.trim() }
        command.description?.let { statement[OnCallIncidents.description] = it.trim().takeIf(String::isNotEmpty) }
        command.summary?.let { statement[OnCallIncidents.summary] = it.trim().takeIf(String::isNotEmpty) }
        prepared.message?.let { statement[OnCallIncidents.summary] = it }
        prepared.severity?.let { statement[OnCallIncidents.severity] = it }
        prepared.customerImpact?.let { statement[OnCallIncidents.customerImpact] = it }
        statement[OnCallIncidents.nextUpdateAt] = prepared.nextUpdateAt
        command.pauseUpdateReminders?.let { statement[OnCallIncidents.updateReminderPaused] = it }
        statement[OnCallIncidents.lastUpdateAt] = now
        prepared.targetStatus?.let {
            statement[OnCallIncidents.status] = it.wire
            applyStatusTimestamps(statement, it, command.actor.userId, now)
        }
        command.mode?.let { statement[OnCallIncidents.mode] = it.wire }
        command.visibility?.let { statement[OnCallIncidents.visibility] = it.wire }
        command.incidentType?.let { statement[OnCallIncidents.incidentType] = it.trim().takeIf(String::isNotEmpty) }
    }

    private fun requestUpdate(command: RequestIncidentUpdateCommand): IncidentMutation {
        requireIncident(command)
        val now = Clock.System.now()
        val dueAt = command.dueAt ?: now.plus(DEFAULT_UPDATE_REQUEST_DELAY)
        require(command.message?.length ?: 0 <= MAX_UPDATE_MESSAGE_LENGTH) {
            "Incident update request message is too long"
        }
        cancelActiveUpdateRequests(command.actor.organizationId, command.incidentId, now)
        NativeIncidentUpdateRequests.insert {
            it[resourceId] = Uuid.random()
            it[organizationId] = command.actor.organizationId
            it[incidentId] = command.incidentId
            it[requestedBy] = command.actor.userId
            it[message] = command.message?.trim()?.takeIf(String::isNotEmpty)
            it[NativeIncidentUpdateRequests.dueAt] = dueAt
            it[status] = IncidentUpdateRequestStatus.OPEN.wire
            it[escalationLevel] = 0
            it[lastRemindedAt] = null
            it[fulfilledAt] = null
            it[createdAt] = now
            it[updatedAt] = now
        }
        return appendVersionedEvent(
            command,
            "UPDATE_REQUESTED",
            buildMap {
                command.message?.trim()?.takeIf(String::isNotEmpty)?.let { put("message", JsonPrimitive(it)) }
                put("dueAt", JsonPrimitive(dueAt.toString()))
            },
        )
    }

    private fun pauseUpdateReminders(command: PauseIncidentUpdateRemindersCommand): IncidentMutation {
        val current = requireIncident(command)
        val now = Clock.System.now()
        val nextVersion = current[OnCallIncidents.version] + 1
        val target = if (command.paused) IncidentUpdateRequestStatus.PAUSED else IncidentUpdateRequestStatus.OPEN
        val updated = OnCallIncidents.update({ versionPredicate(command, current) }) {
            it[OnCallIncidents.updateReminderPaused] = command.paused
            it[version] = nextVersion
            it[updatedAt] = now
        }
        requireVersionUpdate(updated, command.incidentId)
        setUpdateRequestStatus(
            UpdateRequestStatusChange(
                organizationId = command.actor.organizationId,
                incidentId = command.incidentId,
                from = if (command.paused) IncidentUpdateRequestStatus.OPEN else IncidentUpdateRequestStatus.PAUSED,
                to = target,
                now = now,
                dueAt = command.rescheduleAt,
            ),
        )
        val details = buildMap {
            put("paused", JsonPrimitive(command.paused))
            command.rescheduleAt?.let { put("rescheduleAt", JsonPrimitive(it.toString())) }
        } + ("origin" to JsonPrimitive(command.actor.origin))
        insertTimelineEvent(
            command,
            CommandTimelineEvent(
                command.incidentId,
                command.commandKey,
                "UPDATE_REMINDERS_${if (command.paused) "PAUSED" else "RESUMED"}",
                details,
                now,
            ),
        )
        return loadMutation(command.incidentId)
    }

    private fun fulfillUpdateRequests(organizationId: Int, incidentId: Int, now: kotlin.time.Instant) {
        NativeIncidentUpdateRequests.update({
            (NativeIncidentUpdateRequests.organizationId eq organizationId) and
                (NativeIncidentUpdateRequests.incidentId eq incidentId) and
                (NativeIncidentUpdateRequests.status eq IncidentUpdateRequestStatus.OPEN.wire)
        }) {
            it[status] = IncidentUpdateRequestStatus.FULFILLED.wire
            it[fulfilledAt] = now
            it[updatedAt] = now
        }
    }

    private fun setUpdateRequestStatus(change: UpdateRequestStatusChange) {
        NativeIncidentUpdateRequests.update({
            (NativeIncidentUpdateRequests.organizationId eq change.organizationId) and
                (NativeIncidentUpdateRequests.incidentId eq change.incidentId) and
                (NativeIncidentUpdateRequests.status eq change.from.wire)
        }) {
            it[status] = change.to.wire
            change.dueAt?.let { value -> it[NativeIncidentUpdateRequests.dueAt] = value }
            it[updatedAt] = change.now
        }
    }

    private fun cancelActiveUpdateRequests(organizationId: Int, incidentId: Int, now: kotlin.time.Instant) {
        NativeIncidentUpdateRequests.update({
            (NativeIncidentUpdateRequests.organizationId eq organizationId) and
                (NativeIncidentUpdateRequests.incidentId eq incidentId) and
                (NativeIncidentUpdateRequests.status inList listOf(
                    IncidentUpdateRequestStatus.OPEN.wire,
                    IncidentUpdateRequestStatus.PAUSED.wire,
                ))
        }) {
            it[status] = IncidentUpdateRequestStatus.CANCELLED.wire
            it[updatedAt] = now
        }
    }

    private fun transition(
        command: ExistingIncidentCommand,
        target: NativeIncidentStatus,
        note: String?,
    ): IncidentMutation {
        val current = requireIncident(command)
        val currentStatus = statusOf(current)
        if (currentStatus == target) return loadMutation(command.incidentId, changed = false)
        requireTransitionAllowed(currentStatus, target)
        if (target in ACCEPTED_STATUSES) {
            if (currentStatus == NativeIncidentStatus.TRIAGE) {
                requireAcceptanceSatisfied(
                    organizationId = command.actor.organizationId,
                    incidentTypeResourceId = incidentTypeResourceId(command.actor.organizationId, current),
                    formValues = emptyMap(),
                    severity = current[OnCallIncidents.severity],
                )
            } else {
                require(current[OnCallIncidents.severity] != null) { SEVERITY_REQUIRED_MESSAGE }
            }
        }
        val now = Clock.System.now()
        val nextVersion = current[OnCallIncidents.version] + 1
        val updated =
            OnCallIncidents.update({ versionPredicate(command, current) }) {
                it[status] = target.wire
                it[version] = nextVersion
                it[updatedAt] = now
                applyStatusTimestamps(it, target, command.actor.userId, now)
            }
        requireVersionUpdate(updated, command.incidentId)
        if (target in setOf(
                NativeIncidentStatus.RESOLVED,
                NativeIncidentStatus.CANCELLED,
                NativeIncidentStatus.DECLINED,
                NativeIncidentStatus.MERGED,
                NativeIncidentStatus.CLOSED,
            )) {
            cancelActiveUpdateRequests(command.actor.organizationId, command.incidentId, now)
        }
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

    /**
     * Accepting classifies a triage incident and moves it into the active response in one
     * versioned mutation, so severity, incident type, and the acceptance form land together.
     */
    private fun accept(command: AcceptIncidentCommand): IncidentMutation {
        val current = requireIncident(command)
        val currentStatus = statusOf(current)
        if (currentStatus == NativeIncidentStatus.ACTIVE) return loadMutation(command.incidentId, changed = false)
        requireTransitionAllowed(currentStatus, NativeIncidentStatus.ACTIVE)
        val severity = command.severity?.let(::requireSeverity) ?: current[OnCallIncidents.severity]
        val organizationId = command.actor.organizationId
        val typeResourceId =
            command.incidentTypeResourceId.cleaned() ?: incidentTypeResourceId(organizationId, current)
        val acceptance =
            requireAcceptanceSatisfied(organizationId, typeResourceId, command.formValues, severity)
        val now = Clock.System.now()
        val nextVersion = current[OnCallIncidents.version] + 1
        val updated =
            OnCallIncidents.update({ versionPredicate(command, current) }) {
                it[status] = NativeIncidentStatus.ACTIVE.wire
                it[OnCallIncidents.severity] = severity
                it[incidentTypeDefinitionId] =
                    acceptance.incidentTypeDefinitionId ?: current[OnCallIncidents.incidentTypeDefinitionId]
                it[OnCallIncidents.incidentType] =
                    acceptance.incidentTypeName ?: current[OnCallIncidents.incidentType]
                it[acceptedAt] = now
                it[version] = nextVersion
                it[updatedAt] = now
                clearTerminalState(it)
            }
        requireVersionUpdate(updated, command.incidentId)
        recordAcceptanceSubmission(command, acceptance, now)
        insertTimelineEvent(
            command,
            CommandTimelineEvent(
                command.incidentId,
                command.commandKey,
                NativeIncidentStatus.ACTIVE.timelineEventType(),
                transitionDetails(command.actor.origin, null, currentStatus) +
                    ("severity" to JsonPrimitive(severity)),
                now,
            ),
        )
        return loadMutation(command.incidentId)
    }

    private fun recordAcceptanceSubmission(
        command: AcceptIncidentCommand,
        acceptance: ResolvedIncidentForm,
        now: kotlin.time.Instant,
    ) {
        if (acceptance.formDefinitionId == null && acceptance.values.isEmpty()) return
        NativeIncidentFormSubmissions.insert {
            it[resourceId] = Uuid.random()
            it[organizationId] = command.actor.organizationId
            it[incidentId] = command.incidentId
            it[formId] = acceptance.formDefinitionId
            it[stage] = IncidentFormStage.ACCEPTANCE.wire
            it[definitionSnapshot] = acceptance.definitionSnapshot
            it[valuesSnapshot] = acceptance.values
            it[submittedBy] = command.actor.userId
            it[submittedAt] = now
        }
    }

    /**
     * A triage incident only leaves triage once it carries a severity and satisfies the
     * organization's configured acceptance form.
     */
    private fun requireAcceptanceSatisfied(
        organizationId: Int,
        incidentTypeResourceId: String?,
        formValues: Map<String, JsonElement>,
        severity: String?,
    ): ResolvedIncidentForm {
        require(severity != null) { SEVERITY_REQUIRED_MESSAGE }
        return configurationService.resolveForm(
            organizationId = organizationId,
            incidentTypeResourceId = incidentTypeResourceId,
            stage = IncidentFormStage.ACCEPTANCE,
            submittedValues = formValues,
        )
    }

    private fun incidentTypeResourceId(organizationId: Int, current: ResultRow): String? {
        val definitionId = current[OnCallIncidents.incidentTypeDefinitionId] ?: return null
        return NativeIncidentTypes
            .selectAll()
            .where {
                (NativeIncidentTypes.id eq definitionId) and
                    (NativeIncidentTypes.organizationId eq organizationId)
            }.singleOrNull()
            ?.get(NativeIncidentTypes.resourceId)
            ?.toString()
    }

    private fun requireSeverity(value: String): String =
        requireNotNull(IncidentSeverity.fromString(value)) { "Invalid incident severity: $value" }.wire

    private fun requireTransitionAllowed(current: NativeIncidentStatus, target: NativeIncidentStatus) {
        if (target in allowedTransitions.getValue(current)) return
        throw IncidentCommandConflictException(
            "Cannot transition incident from ${current.wire} to ${target.wire}",
        )
    }

    private fun applyStatusTimestamps(
        statement: UpdateBuilder<*>,
        target: NativeIncidentStatus,
        actorUserId: Int,
        now: kotlin.time.Instant,
    ) {
        when (target) {
            NativeIncidentStatus.TRIAGE -> {
                statement[OnCallIncidents.triagedAt] = now
                clearTerminalState(statement)
            }
            NativeIncidentStatus.ACTIVE -> {
                statement[OnCallIncidents.acceptedAt] = now
                clearTerminalState(statement)
            }
            NativeIncidentStatus.RESOLVED -> {
                statement[OnCallIncidents.resolvedBy] = actorUserId
                statement[OnCallIncidents.resolvedAt] = now
            }
            NativeIncidentStatus.POST_INCIDENT -> statement[OnCallIncidents.postIncidentAt] = now
            NativeIncidentStatus.CLOSED -> statement[OnCallIncidents.closedAt] = now
            NativeIncidentStatus.CANCELLED -> statement[OnCallIncidents.cancelledAt] = now
            NativeIncidentStatus.DECLINED -> statement[OnCallIncidents.declinedAt] = now
            // MERGED is written only by the merge command, which also records the merge target.
            NativeIncidentStatus.MERGED -> error("MERGED is only reachable through a merge command")
        }
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
        val description = (command.description ?: command.title).trim()
        require(description.isNotEmpty()) { "Incident action description is required" }
        require(description.length <= MAX_ACTION_DESCRIPTION_LENGTH) { "Incident action description is too long" }
        require(command.slackChannelId == null || command.slackChannelId.length <= MAX_SLACK_CHANNEL_ID_LENGTH) {
            "Slack channel ID is too long"
        }
        require(command.slackMessageTs == null || command.slackMessageTs.length <= MAX_SLACK_MESSAGE_TS_LENGTH) {
            "Slack message reference is too long"
        }
        val now = Clock.System.now()
        val actionId = NativeIncidentActions.insertAndGetId {
            it[resourceId] = Uuid.random()
            it[organizationId] = command.actor.organizationId
            it[incidentId] = command.incidentId
            it[NativeIncidentActions.description] = description
            it[assigneeUserId] = command.assigneeUserId
            it[state] = if (command.assigneeUserId == null) {
                IncidentActionState.OPEN.wire
            } else {
                IncidentActionState.CLAIMED.wire
            }
            it[actionSource] = command.source.wire
            it[slackChannelId] = command.slackChannelId
            it[slackMessageTs] = command.slackMessageTs
            it[createdBy] = command.actor.userId
            it[claimedAt] = now.takeIf { command.assigneeUserId != null }
            it[completedAt] = null
            it[cancelledAt] = null
            it[convertedToFollowUpAt] = null
            it[createdAt] = now
            it[updatedAt] = now
        }
        recordActionEvent(
            ActionEventRecord(
                organizationId = command.actor.organizationId,
                incidentId = command.incidentId,
                actionId = actionId.value,
                actorUserId = command.actor.userId,
                eventType = "ACTION_CREATED",
                fromState = null,
                toState = if (command.assigneeUserId == null) IncidentActionState.OPEN else IncidentActionState.CLAIMED,
                details = mapOf(
                    "description" to JsonPrimitive(description),
                    "source" to JsonPrimitive(command.source.wire),
                    "origin" to JsonPrimitive(command.actor.origin),
                ),
                now = now,
            ),
        )
        val details = mutableMapOf<String, JsonElement>(
            "actionId" to JsonPrimitive(actionResourceId(command.actor.organizationId, actionId.value)),
            "description" to JsonPrimitive(description),
            "source" to JsonPrimitive(command.source.wire),
        )
        command.assigneeUserId?.let { details["assigneeUserId"] = JsonPrimitive(userResourceId(it)) }
        command.slackChannelId?.let { details["slackChannelId"] = JsonPrimitive(it) }
        command.slackMessageTs?.let { details["slackMessageTs"] = JsonPrimitive(it) }
        return appendVersionedEvent(command, "ACTION_ADDED", details).copy(
            actionResourceId = actionResourceId(
                command.actor.organizationId,
                actionId.value,
            ),
        )
    }

    private fun claimAction(command: ClaimIncidentActionCommand): IncidentMutation =
        transitionAction(command, IncidentActionState.CLAIMED, command.actor.userId, "ACTION_CLAIMED")

    private fun reassignAction(command: ReassignIncidentActionCommand): IncidentMutation {
        requireUserMembership(command.actor.organizationId, command.assigneeUserId)
        return transitionAction(command, IncidentActionState.CLAIMED, command.assigneeUserId, "ACTION_REASSIGNED")
    }

    private fun completeAction(command: CompleteIncidentActionCommand): IncidentMutation =
        transitionAction(command, IncidentActionState.COMPLETED, null, "ACTION_COMPLETED", command.note)

    private fun cancelAction(command: CancelIncidentActionCommand): IncidentMutation =
        transitionAction(command, IncidentActionState.CANCELLED, null, "ACTION_CANCELLED", command.reason)

    private fun convertAction(command: ConvertIncidentActionToFollowUpCommand): IncidentMutation =
        transitionAction(
            command,
            IncidentActionState.FOLLOW_UP,
            null,
            "ACTION_CONVERTED_TO_FOLLOW_UP",
            command.followUpDescription,
        )

    private fun transitionAction(
        command: ExistingIncidentCommand,
        target: IncidentActionState,
        assigneeUserId: Int?,
        eventType: String,
        note: String? = null,
    ): IncidentMutation {
        val action = requireAction(command.actor.organizationId, command.incidentId, actionResourceId(command))
        val currentState = action[NativeIncidentActions.state].toActionState()
        if (actionTransitionIsNoop(
                currentState,
                target,
                action[NativeIncidentActions.assigneeUserId],
                assigneeUserId,
            )
        ) {
            return loadMutation(command.incidentId, changed = false)
        }
        val isReassignment = command is ReassignIncidentActionCommand
        requireActionTransitionAllowed(currentState, target, allowReassignment = isReassignment)
        val now = Clock.System.now()
        NativeIncidentActions.update({ NativeIncidentActions.id eq action[NativeIncidentActions.id] }) {
            it[state] = target.wire
            if (target == IncidentActionState.CLAIMED) it[NativeIncidentActions.assigneeUserId] = assigneeUserId
            if (target == IncidentActionState.COMPLETED) it[completedAt] = now
            if (target == IncidentActionState.CANCELLED) it[cancelledAt] = now
            if (target == IncidentActionState.FOLLOW_UP) it[convertedToFollowUpAt] = now
            if (target == IncidentActionState.CLAIMED) it[claimedAt] = action[NativeIncidentActions.claimedAt] ?: now
            it[updatedAt] = now
        }
        recordActionEvent(
            ActionEventRecord(
                organizationId = command.actor.organizationId,
                incidentId = command.incidentId,
                actionId = action[NativeIncidentActions.id].value,
                actorUserId = command.actor.userId,
                eventType = eventType,
                fromState = currentState,
                toState = target,
                details = buildMap {
                    note?.trim()?.takeIf(String::isNotEmpty)?.let { put("note", JsonPrimitive(it)) }
                    assigneeUserId?.let { put("assigneeUserId", JsonPrimitive(userResourceId(it))) }
                    put("origin", JsonPrimitive(command.actor.origin))
                },
                now = now,
            ),
        )
        val details = buildMap<String, JsonElement> {
            put("actionId", JsonPrimitive(action[NativeIncidentActions.resourceId].toString()))
            put("previousState", JsonPrimitive(currentState.wire))
            put("state", JsonPrimitive(target.wire))
            note?.trim()?.takeIf(String::isNotEmpty)?.let { put("note", JsonPrimitive(it)) }
            assigneeUserId?.let { put("assigneeUserId", JsonPrimitive(userResourceId(it))) }
        }
        return appendVersionedEvent(command, eventType, details)
    }

    private fun actionResourceId(organizationId: Int, actionId: Int): String =
        NativeIncidentActions
            .selectAll()
            .where {
                (NativeIncidentActions.organizationId eq organizationId) and
                    (NativeIncidentActions.id eq actionId)
            }
            .single()[NativeIncidentActions.resourceId]
            .toString()

    private fun actionResourceId(command: ExistingIncidentCommand): Uuid {
        val value = when (command) {
            is ClaimIncidentActionCommand -> command.actionResourceId
            is ReassignIncidentActionCommand -> command.actionResourceId
            is CompleteIncidentActionCommand -> command.actionResourceId
            is CancelIncidentActionCommand -> command.actionResourceId
            is ConvertIncidentActionToFollowUpCommand -> command.actionResourceId
            else -> throw IncidentCommandNotFoundException("Incident action not found")
        }
        return runCatching { Uuid.parse(value) }
            .getOrElse { throw IncidentCommandNotFoundException("Incident action not found") }
    }

    private fun requireAction(organizationId: Int, incidentId: Int, resourceId: Uuid): ResultRow =
        NativeIncidentActions
            .selectAll()
            .where {
                (NativeIncidentActions.organizationId eq organizationId) and
                    (NativeIncidentActions.incidentId eq incidentId) and
                    (NativeIncidentActions.resourceId eq resourceId)
            }.singleOrNull() ?: throw IncidentCommandNotFoundException("Incident action not found")

    private fun actionTransitionIsNoop(
        currentState: IncidentActionState,
        target: IncidentActionState,
        currentAssigneeUserId: Int?,
        targetAssigneeUserId: Int?,
    ): Boolean = when {
        currentState != target -> false
        target != IncidentActionState.CLAIMED -> true
        else -> currentAssigneeUserId == targetAssigneeUserId
    }

    private fun requireActionTransitionAllowed(
        from: IncidentActionState,
        to: IncidentActionState,
        allowReassignment: Boolean = false,
    ) {
        if (allowReassignment && from == IncidentActionState.CLAIMED && to == IncidentActionState.CLAIMED) return
        val allowed = when (from) {
            IncidentActionState.OPEN -> setOf(
                IncidentActionState.CLAIMED,
                IncidentActionState.COMPLETED,
                IncidentActionState.CANCELLED,
                IncidentActionState.FOLLOW_UP,
            )
            IncidentActionState.CLAIMED -> setOf(
                IncidentActionState.COMPLETED,
                IncidentActionState.CANCELLED,
                IncidentActionState.FOLLOW_UP,
            )
            IncidentActionState.COMPLETED,
            IncidentActionState.CANCELLED,
            IncidentActionState.FOLLOW_UP,
            -> emptySet()
        }
        if (to !in allowed) {
            throw IncidentCommandConflictException(
                "Cannot transition action from ${from.wire} to ${to.wire}",
            )
        }
    }

    private fun String.toActionState(): IncidentActionState =
        IncidentActionState.entries.firstOrNull { it.wire == this }
            ?: throw IncidentCommandConflictException("Unknown incident action state: $this")

    private fun recordActionEvent(record: ActionEventRecord) {
        NativeIncidentActionEvents.insert {
            it[resourceId] = Uuid.random()
            it[NativeIncidentActionEvents.organizationId] = record.organizationId
            it[NativeIncidentActionEvents.actionId] = record.actionId
            it[NativeIncidentActionEvents.incidentId] = record.incidentId
            it[NativeIncidentActionEvents.actorUserId] = record.actorUserId
            it[NativeIncidentActionEvents.eventType] = record.eventType
            it[NativeIncidentActionEvents.fromState] = record.fromState?.wire
            it[NativeIncidentActionEvents.toState] = record.toState?.wire
            it[NativeIncidentActionEvents.details] = record.details
            it[createdAt] = record.now
        }
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

    /**
     * Merging retires the source incident into the target. The source keeps its own timeline and
     * command history; only its links move, and it lands in the terminal MERGED status that
     * records where it went.
     */
    private fun merge(command: MergeIncidentCommand): IncidentMutation {
        require(command.incidentId != command.sourceIncidentId) { "Cannot merge an incident into itself" }
        val target = requireIncident(command)
        val source = requireIncident(command.actor.organizationId, command.sourceIncidentId)
        alreadyMergedResult(command, source)?.let { return it }
        requireMergeable(statusOf(target), statusOf(source))
        transferOnCallAlertLinks(command)
        transferAlertEpisodeLinks(command)
        transferSourceLinks(command)
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
            it[status] = NativeIncidentStatus.MERGED.wire
            it[version] = sourceVersion
            it[mergedAt] = now
            it[mergedIntoIncidentId] = command.incidentId
            it[updatedAt] = now
        }.also { requireVersionUpdate(it, command.sourceIncidentId) }
        recordMergeEvents(command, source, target, sourceVersion, now)
        return loadMutation(command.incidentId)
    }

    /** A repeated merge of the same pair converges instead of failing. */
    private fun alreadyMergedResult(command: MergeIncidentCommand, source: ResultRow): IncidentMutation? {
        if (statusOf(source) != NativeIncidentStatus.MERGED) return null
        if (source[OnCallIncidents.mergedIntoIncidentId] == command.incidentId) {
            return loadMutation(command.incidentId, changed = false)
        }
        throw IncidentCommandConflictException("Incident is already merged into another incident")
    }

    private fun requireMergeable(targetStatus: NativeIncidentStatus, sourceStatus: NativeIncidentStatus) {
        if (targetStatus !in MERGE_TARGET_STATUSES) {
            throw IncidentCommandConflictException(
                "Incidents cannot be merged into an incident in ${targetStatus.wire} status",
            )
        }
        if (NativeIncidentStatus.MERGED !in allowedTransitions.getValue(sourceStatus)) {
            throw IncidentCommandConflictException("An incident in ${sourceStatus.wire} status cannot be merged")
        }
    }

    private fun transferOnCallAlertLinks(command: MergeIncidentCommand) {
        val sourceAlerts =
            OnCallIncidentAlerts
                .selectAll()
                .where { OnCallIncidentAlerts.incidentId eq command.sourceIncidentId }
                .map { it[OnCallIncidentAlerts.alertId] }
        if (sourceAlerts.isEmpty()) return
        OnCallIncidentAlerts.update({ OnCallIncidentAlerts.incidentId eq command.sourceIncidentId }) {
            it[incidentId] = command.incidentId
        }
        sourceAlerts.forEach { alertId ->
            OnCallAlerts.update({ OnCallAlerts.id eq alertId }) {
                it[declaredIncidentId] = command.incidentId
            }
        }
    }

    private fun transferAlertEpisodeLinks(command: MergeIncidentCommand) {
        val targetEpisodes =
            NativeIncidentAlertEpisodeLinks
                .selectAll()
                .where { NativeIncidentAlertEpisodeLinks.incidentId eq command.incidentId }
                .mapTo(mutableSetOf()) { it[NativeIncidentAlertEpisodeLinks.alertEpisodeId] }
        if (targetEpisodes.isNotEmpty()) {
            NativeIncidentAlertEpisodeLinks.deleteWhere {
                (incidentId eq command.sourceIncidentId) and (alertEpisodeId inList targetEpisodes)
            }
        }
        NativeIncidentAlertEpisodeLinks.update({
            NativeIncidentAlertEpisodeLinks.incidentId eq command.sourceIncidentId
        }) {
            it[incidentId] = command.incidentId
        }
    }

    private fun transferSourceLinks(command: MergeIncidentCommand) {
        val targetSources =
            NativeIncidentSourceLinks
                .selectAll()
                .where { NativeIncidentSourceLinks.incidentId eq command.incidentId }
                .mapTo(mutableSetOf()) {
                    it[NativeIncidentSourceLinks.sourceType] to it[NativeIncidentSourceLinks.sourceKey]
                }
        val duplicateIds =
            NativeIncidentSourceLinks
                .selectAll()
                .where { NativeIncidentSourceLinks.incidentId eq command.sourceIncidentId }
                .filter {
                    (it[NativeIncidentSourceLinks.sourceType] to it[NativeIncidentSourceLinks.sourceKey]) in
                        targetSources
                }.map { it[NativeIncidentSourceLinks.id].value }
        if (duplicateIds.isNotEmpty()) {
            NativeIncidentSourceLinks.deleteWhere { id inList duplicateIds }
        }
        NativeIncidentSourceLinks.update({
            NativeIncidentSourceLinks.incidentId eq command.sourceIncidentId
        }) {
            it[incidentId] = command.incidentId
        }
    }

    private fun recordMergeEvents(
        command: MergeIncidentCommand,
        source: ResultRow,
        target: ResultRow,
        sourceVersion: Int,
        now: kotlin.time.Instant,
    ) {
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
                NativeIncidentStatus.MERGED.timelineEventType(),
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
                payload = details +
                    mapOf(
                        "status" to JsonPrimitive(NativeIncidentStatus.MERGED.wire),
                        "mergedIntoIncidentId" to JsonPrimitive(target[OnCallIncidents.resourceId].toString()),
                        "mergedAt" to JsonPrimitive(now.toString()),
                    ),
            ),
        )
    }

    private fun requireIncident(command: ExistingIncidentCommand): ResultRow {
        val row = requireIncident(command.actor.organizationId, command.incidentId)
        policy.requireCapabilityAllowed(command, statusOf(row))
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
            it[actionResourceId] = mutation.actionResourceId?.let(Uuid::parse)
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
            NativeIncidentStatus.MERGED -> "MERGED_INTO_INCIDENT"
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
        statement[OnCallIncidents.mergedAt] = null
        statement[OnCallIncidents.mergedIntoIncidentId] = null
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
            actionResourceId = actionResourceId,
        )

    private data class IncidentMutation(
        val incidentId: Int,
        val incidentResourceId: String,
        val title: String,
        val severity: String?,
        val status: NativeIncidentStatus,
        val version: Int,
        val changed: Boolean,
        val actionResourceId: String? = null,
    )

    private data class PreparedIncidentUpdate(
        val severity: String?,
        val message: String?,
        val customerImpact: String?,
        val nextUpdateAt: kotlin.time.Instant?,
        val targetStatus: NativeIncidentStatus?,
    )

    private data class UpdateRequestStatusChange(
        val organizationId: Int,
        val incidentId: Int,
        val from: IncidentUpdateRequestStatus,
        val to: IncidentUpdateRequestStatus,
        val now: kotlin.time.Instant,
        val dueAt: kotlin.time.Instant?,
    )

    private data class CommandTimelineEvent(
        val incidentId: Int,
        val eventKey: String,
        val eventType: String,
        val details: Map<String, JsonElement>,
        val at: kotlin.time.Instant,
    )

    private data class ActionEventRecord(
        val organizationId: Int,
        val incidentId: Int,
        val actionId: Int,
        val actorUserId: Int,
        val eventType: String,
        val fromState: IncidentActionState?,
        val toState: IncidentActionState?,
        val details: Map<String, JsonElement>,
        val now: kotlin.time.Instant,
    )

    companion object {
        private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
        private const val COMMAND_KEY_CONSTRAINT = "uq_native_incident_commands_org_command_key"
        private const val INITIAL_VERSION = 1
        private const val MAX_TITLE_LENGTH = 255
        private const val MAX_UPDATE_MESSAGE_LENGTH = 2_000
        private const val MAX_CUSTOMER_IMPACT_LENGTH = 64
        private const val MAX_TIMELINE_EVENT_TYPE_LENGTH = 80
        private const val MAX_ACTION_DESCRIPTION_LENGTH = 2_000
        private const val MAX_SLACK_CHANNEL_ID_LENGTH = 128
        private const val MAX_SLACK_MESSAGE_TS_LENGTH = 64
        private const val MAX_SOURCE_KEY_LENGTH = 500
        private const val SEVERITY_REQUIRED_MESSAGE =
            "Incident severity is required before an incident is accepted"
        private val SAFE_EXTERNAL_SOURCE_SCHEMES = setOf("http", "https")
        private val DEFAULT_UPDATE_REQUEST_DELAY = 30.minutes

        private val DECLARABLE_STATUSES = setOf(NativeIncidentStatus.TRIAGE, NativeIncidentStatus.ACTIVE)

        /** Statuses that represent an accepted incident, so leaving triage for them needs acceptance. */
        private val ACCEPTED_STATUSES = setOf(
            NativeIncidentStatus.ACTIVE,
            NativeIncidentStatus.RESOLVED,
            NativeIncidentStatus.POST_INCIDENT,
            NativeIncidentStatus.CLOSED,
        )

        /** An incident can absorb a merge unless it has itself been closed out or merged away. */
        private val MERGE_TARGET_STATUSES = setOf(
            NativeIncidentStatus.TRIAGE,
            NativeIncidentStatus.ACTIVE,
            NativeIncidentStatus.RESOLVED,
            NativeIncidentStatus.POST_INCIDENT,
            NativeIncidentStatus.CLOSED,
        )

        private val allowedTransitions = mapOf(
            NativeIncidentStatus.TRIAGE to setOf(
                NativeIncidentStatus.ACTIVE,
                NativeIncidentStatus.DECLINED,
                NativeIncidentStatus.CANCELLED,
                NativeIncidentStatus.MERGED,
            ),
            NativeIncidentStatus.ACTIVE to setOf(
                NativeIncidentStatus.RESOLVED,
                NativeIncidentStatus.CANCELLED,
                NativeIncidentStatus.MERGED,
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
            // MERGED is terminal: a merged incident is never reopened or transitioned again.
            NativeIncidentStatus.MERGED to emptySet(),
        )
    }
}
