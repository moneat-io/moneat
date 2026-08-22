// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.commands

import com.moneat.enterprise.incidents.models.NativeIncidentMode
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import kotlinx.serialization.json.JsonElement

data class IncidentCommandActor(
    val organizationId: Int,
    val userId: Int,
    val origin: String,
)

enum class IncidentCommandType(val wire: String) {
    DECLARE("DECLARE"),
    ACCEPT("ACCEPT"),
    MERGE("MERGE"),
    UPDATE("UPDATE"),
    TRANSITION("TRANSITION"),
    ASSIGN_ROLE("ASSIGN_ROLE"),
    ADD_ACTION("ADD_ACTION"),
    ADD_TIMELINE_EVENT("ADD_TIMELINE_EVENT"),
    LINK_ON_CALL_ALERT("LINK_ON_CALL_ALERT"),
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
    val severity: String,
    val mode: NativeIncidentMode = NativeIncidentMode.LIVE,
    val visibility: NativeIncidentVisibility = NativeIncidentVisibility.ORGANIZATION,
    val incidentType: String? = null,
    val initialStatus: NativeIncidentStatus = NativeIncidentStatus.ACTIVE,
    val onCallAlertId: Int? = null,
) : IncidentCommand {
    override val expectedVersion: Int? = null
    override val type = IncidentCommandType.DECLARE
}

sealed interface ExistingIncidentCommand : IncidentCommand {
    val incidentId: Int
}

data class AcceptIncidentCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.ACCEPT
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
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.UPDATE
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
    val role: String,
    val assigneeUserId: Int,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.ASSIGN_ROLE
}

data class AddIncidentActionCommand(
    override val commandKey: String,
    override val actor: IncidentCommandActor,
    override val incidentId: Int,
    val title: String,
    val assigneeUserId: Int? = null,
    override val expectedVersion: Int? = null,
) : ExistingIncidentCommand {
    override val type = IncidentCommandType.ADD_ACTION
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
)

open class IncidentCommandException(message: String) : IllegalStateException(message)

class IncidentCommandDeniedException(message: String) : IncidentCommandException(message)

class IncidentCommandConflictException(message: String) : IncidentCommandException(message)

class IncidentCommandNotFoundException(message: String) : IncidentCommandException(message)
