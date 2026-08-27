// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.commands

import com.moneat.enterprise.incidents.models.NativeIncidentMode
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import com.moneat.enterprise.incidents.models.IncidentSourceType
import com.moneat.enterprise.incidents.models.IncidentActionSource
import com.moneat.enterprise.incidents.followups.IncidentFollowUpPriority
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant

data class IncidentCommandActor(
    val organizationId: Int,
    val userId: Int,
    val origin: String,
)

enum class IncidentCommandType(val wire: String) {
    DECLARE("DECLARE"),
    ACCEPT("ACCEPT"),
    DECLINE("DECLINE"),
    MERGE("MERGE"),
    UPDATE("UPDATE"),
    REQUEST_UPDATE("REQUEST_UPDATE"),
    PAUSE_UPDATE_REMINDERS("PAUSE_UPDATE_REMINDERS"),
    TRANSITION("TRANSITION"),
    ASSIGN_ROLE("ASSIGN_ROLE"),
    CLAIM_ROLE("CLAIM_ROLE"),
    UNASSIGN_ROLE("UNASSIGN_ROLE"),
    HANDOVER_ROLE("HANDOVER_ROLE"),
    JOIN("JOIN"),
    OBSERVE("OBSERVE"),
    LEAVE("LEAVE"),
    ADD_ACTION("ADD_ACTION"),
    CLAIM_ACTION("CLAIM_ACTION"),
    REASSIGN_ACTION("REASSIGN_ACTION"),
    COMPLETE_ACTION("COMPLETE_ACTION"),
    CANCEL_ACTION("CANCEL_ACTION"),
    CONVERT_ACTION("CONVERT_ACTION"),
    ADD_FOLLOW_UP("ADD_FOLLOW_UP"),
    UPDATE_FOLLOW_UP("UPDATE_FOLLOW_UP"),
    ACCEPT_FOLLOW_UP("ACCEPT_FOLLOW_UP"),
    COMPLETE_FOLLOW_UP("COMPLETE_FOLLOW_UP"),
    CANCEL_FOLLOW_UP("CANCEL_FOLLOW_UP"),
    ADD_TIMELINE_EVENT("ADD_TIMELINE_EVENT"),
    LINK_ON_CALL_ALERT("LINK_ON_CALL_ALERT"),
    LINK_SOURCE("LINK_SOURCE"),
    UNLINK_SOURCE("UNLINK_SOURCE"),
    RESOLVE("RESOLVE"),
    CANCEL("CANCEL"),
    REOPEN("REOPEN"),
}

sealed interface IncidentCommand {
    val commandKey: String
    val actor: IncidentCommandActor
    val expectedVersion: Int?
    val type: IncidentCommandType
}

data class DeclareIncidentCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    val title: String,
    val description: String?,
    val summary: String? = null,
    /** Triage declarations may stay unclassified; ACTIVE declarations must carry a severity. */
    val severity: String? = null,
    val mode: NativeIncidentMode = NativeIncidentMode.LIVE,
    val visibility: NativeIncidentVisibility = NativeIncidentVisibility.ORGANIZATION,
    val incidentType: String? = null,
    val incidentTypeDefinitionId: Int? = null,
    val formDefinitionId: Int? = null,
    val formDefinitionSnapshot: Map<String, JsonElement> = emptyMap(),
    val formValues: Map<String, JsonElement> = emptyMap(),
    val initialStatus: NativeIncidentStatus = NativeIncidentStatus.ACTIVE,
    val onCallAlertId: Int? = null,
) : IncidentCommand {
    override val expectedVersion: Int? = null
    override val type = IncidentCommandType.DECLARE
}

sealed interface ExistingIncidentCommand : IncidentCommand {
    val incidentId: Int
}

/**
 * Accepting a triage incident classifies it. Severity is required by the time the incident
 * becomes ACTIVE, and the organization's ACCEPTANCE form is validated against [formValues].
 */
data class AcceptIncidentCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    override val expectedVersion: Int? = null,
    val severity: String? = null,
    val incidentTypeResourceId: String? = null,
    val formValues: Map<String, JsonElement> = emptyMap(),
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.ACCEPT
}

data class DeclineIncidentCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val reason: String? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.DECLINE
}

data class MergeIncidentCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val sourceIncidentId: Int,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.MERGE
}

data class UpdateIncidentCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    override val expectedVersion: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val summary: String? = null,
    val severity: String? = null,
    val mode: NativeIncidentMode? = null,
    val visibility: NativeIncidentVisibility? = null,
    val incidentType: String? = null,
    val message: String? = null,
    val customerImpact: String? = null,
    val nextUpdateAt: Instant? = null,
    val clearNextUpdateAt: Boolean = false,
    val pauseUpdateReminders: Boolean? = null,
    val status: NativeIncidentStatus? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.UPDATE
}

data class RequestIncidentUpdateCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val message: String? = null,
    val dueAt: Instant? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.REQUEST_UPDATE
}

data class PauseIncidentUpdateRemindersCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val paused: Boolean,
    val rescheduleAt: Instant? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.PAUSE_UPDATE_REMINDERS
}

data class TransitionIncidentCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val targetStatus: NativeIncidentStatus,
    val note: String? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.TRANSITION
}

data class AssignIncidentRoleCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val roleDefinitionId: Int,
    val assigneeUserId: Int,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.ASSIGN_ROLE
}

data class ClaimIncidentRoleCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val roleDefinitionId: Int,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.CLAIM_ROLE
}

data class UnassignIncidentRoleCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val roleDefinitionId: Int,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.UNASSIGN_ROLE
}

data class HandoverIncidentRoleCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val roleDefinitionId: Int,
    val toUserId: Int,
    val note: String? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.HANDOVER_ROLE
}

data class SetIncidentParticipationCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val userId: Int,
    val participationType: com.moneat.enterprise.incidents.models.IncidentParticipationType,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type =
        when (participationType) {
            com.moneat.enterprise.incidents.models.IncidentParticipationType.PARTICIPANT -> IncidentCommandType.JOIN
            com.moneat.enterprise.incidents.models.IncidentParticipationType.OBSERVER -> IncidentCommandType.OBSERVE
        }
}

data class LeaveIncidentCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val userId: Int,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.LEAVE
}

data class AddIncidentActionCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val title: String,
    val assigneeUserId: Int? = null,
    val description: String? = null,
    val source: IncidentActionSource = IncidentActionSource.COMMAND,
    val slackChannelId: String? = null,
    val slackMessageTs: String? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.ADD_ACTION
}

data class ClaimIncidentActionCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val actionResourceId: String,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.CLAIM_ACTION
}

data class ReassignIncidentActionCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val actionResourceId: String,
    val assigneeUserId: Int,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.REASSIGN_ACTION
}

data class CompleteIncidentActionCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val actionResourceId: String,
    val note: String? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.COMPLETE_ACTION
}

data class CancelIncidentActionCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val actionResourceId: String,
    val reason: String? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.CANCEL_ACTION
}

data class ConvertIncidentActionToFollowUpCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val actionResourceId: String,
    val followUpDescription: String? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.CONVERT_ACTION
}

data class AddIncidentFollowUpCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val title: String,
    val description: String,
    val ownerUserId: Int? = null,
    val ownerTeamId: Int? = null,
    val priority: IncidentFollowUpPriority = IncidentFollowUpPriority.P2,
    val labels: List<String> = emptyList(),
    val dueAt: Instant? = null,
    val slaMinutes: Int? = null,
    val reminderMinutes: Int? = null,
    val source: IncidentActionSource = IncidentActionSource.COMMAND,
    val slackChannelId: String? = null,
    val slackMessageTs: String? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.ADD_FOLLOW_UP
}

data class UpdateIncidentFollowUpCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val followUpResourceId: String,
    val title: String? = null,
    val description: String? = null,
    val ownerUserId: Int? = null,
    val ownerTeamId: Int? = null,
    val priority: IncidentFollowUpPriority? = null,
    val labels: List<String>? = null,
    val dueAt: Instant? = null,
    val clearDueAt: Boolean = false,
    val slaMinutes: Int? = null,
    val reminderMinutes: Int? = null,
    val clearReminderAt: Boolean = false,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.UPDATE_FOLLOW_UP
}

data class AcceptIncidentFollowUpCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val followUpResourceId: String,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.ACCEPT_FOLLOW_UP
}

data class CompleteIncidentFollowUpCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val followUpResourceId: String,
    val note: String? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.COMPLETE_FOLLOW_UP
}

data class CancelIncidentFollowUpCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val followUpResourceId: String,
    val reason: String? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.CANCEL_FOLLOW_UP
}

data class AddIncidentTimelineEventCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val eventType: String,
    val details: Map<String, JsonElement>,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.ADD_TIMELINE_EVENT
}

data class LinkOnCallAlertCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val alertId: Int,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.LINK_ON_CALL_ALERT
}

data class IncidentSourceReference(
    val sourceType: IncidentSourceType,
    val sourceKey: String,
    val onCallAlertId: Int? = null,
    val alertEpisodeId: Int? = null,
    val label: String? = null,
    val sourceUrl: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
)

data class LinkIncidentSourceCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val source: IncidentSourceReference,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.LINK_SOURCE
}

data class UnlinkIncidentSourceCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val sourceType: IncidentSourceType,
    val sourceKey: String,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.UNLINK_SOURCE
}

data class ResolveIncidentCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val note: String? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.RESOLVE
}

data class CancelIncidentCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val reason: String? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.CANCEL
}

data class ReopenIncidentCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val reason: String? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.REOPEN
}

data class IncidentCommandResult(
    val incidentId: Int,
    val incidentResourceId: String,
    val status: NativeIncidentStatus,
    val version: Int,
    val replayed: Boolean,
    val actionResourceId: String? = null,
    val followUpResourceId: String? = null,
)

open class IncidentCommandException(message: String) : IllegalStateException(message)

class IncidentCommandDeniedException(message: String) : IncidentCommandException(message)

class IncidentCommandConflictException(message: String) : IncidentCommandException(message)

class IncidentCommandNotFoundException(message: String) : IncidentCommandException(message)

class IncidentCommandQuotaExceededException(message: String) : IncidentCommandException(message)
