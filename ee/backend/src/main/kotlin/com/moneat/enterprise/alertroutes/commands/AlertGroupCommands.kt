// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.commands

import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import kotlin.uuid.Uuid

data class AlertGroupActor(val organizationId: Int, val userId: Int, val origin: String)

enum class AlertGroupCommandType(val wire: String) {
    MARK_UNRELATED("MARK_UNRELATED"),
    REMOVE_EPISODE("REMOVE_EPISODE"),
    ATTACH_INCIDENT("ATTACH_INCIDENT"),
    CREATE_TRIAGE("CREATE_TRIAGE"),
}

sealed interface AlertGroupCommand {
    val commandKey: String
    val actor: AlertGroupActor
    val groupId: Uuid
    val expectedVersion: Int
    val type: AlertGroupCommandType
}

data class MarkAlertGroupEpisodeUnrelatedCommand(
    override val commandKey: String,
    override val actor: AlertGroupActor,
    override val groupId: Uuid,
    override val expectedVersion: Int,
    val episodeId: Uuid,
) : AlertGroupCommand {
    override val type = AlertGroupCommandType.MARK_UNRELATED
}

data class RemoveAlertGroupEpisodeCommand(
    override val commandKey: String,
    override val actor: AlertGroupActor,
    override val groupId: Uuid,
    override val expectedVersion: Int,
    val episodeId: Uuid,
) : AlertGroupCommand {
    override val type = AlertGroupCommandType.REMOVE_EPISODE
}

data class AttachAlertGroupIncidentCommand(
    override val commandKey: String,
    override val actor: AlertGroupActor,
    override val groupId: Uuid,
    override val expectedVersion: Int,
    val incidentId: Uuid,
    val automatic: Boolean = false,
) : AlertGroupCommand {
    override val type = AlertGroupCommandType.ATTACH_INCIDENT
}

data class CreateAlertGroupTriageCommand(
    override val commandKey: String,
    override val actor: AlertGroupActor,
    override val groupId: Uuid,
    override val expectedVersion: Int,
    val title: String? = null,
    val summary: String? = null,
    val initialStatus: NativeIncidentStatus = NativeIncidentStatus.TRIAGE,
    val severity: String? = null,
    val incidentType: String? = null,
) : AlertGroupCommand {
    override val type = AlertGroupCommandType.CREATE_TRIAGE
}
