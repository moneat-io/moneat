// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.commands

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.HexFormat

internal object IncidentCommandFingerprint {
    private val json = Json

    fun of(command: IncidentCommand): String {
        val encoded = json.encodeToString(parts(command))
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    private fun parts(command: IncidentCommand): List<String?> =
        commonParts(command) + commandParts(command)

    private fun commandParts(command: IncidentCommand): List<String?> =
        when (command) {
            is DeclareIncidentCommand,
            is AcceptIncidentCommand,
            is DeclineIncidentCommand,
            is MergeIncidentCommand,
            is UpdateIncidentCommand,
            is RequestIncidentUpdateCommand,
            is PauseIncidentUpdateRemindersCommand,
            is TransitionIncidentCommand,
            -> primaryParts(command)
            is AssignIncidentRoleCommand,
            is ClaimIncidentRoleCommand,
            is UnassignIncidentRoleCommand,
            is HandoverIncidentRoleCommand,
            is SetIncidentParticipationCommand,
            is LeaveIncidentCommand,
            -> responderParts(command)
            else -> supportingParts(command)
        }

    private fun primaryParts(command: IncidentCommand): List<String?> =
        when (command) {
            is DeclareIncidentCommand ->
                listOf(
                    command.title,
                    command.description,
                    command.summary,
                    command.severity,
                    command.mode.wire,
                    command.visibility.wire,
                    command.incidentType,
                    command.incidentTypeDefinitionId?.toString(),
                    command.formDefinitionId?.toString(),
                    canonicalDetails(command.formDefinitionSnapshot),
                    canonicalDetails(command.formValues),
                    command.initialStatus.wire,
                    command.onCallAlertId?.toString(),
                )
            is AcceptIncidentCommand ->
                listOf(
                    command.incidentId.toString(),
                    command.severity,
                    command.incidentTypeResourceId,
                    canonicalDetails(command.formValues),
                )
            is DeclineIncidentCommand -> listOf(command.incidentId.toString(), command.reason)
            is MergeIncidentCommand -> listOf(command.incidentId.toString(), command.sourceIncidentId.toString())
            is UpdateIncidentCommand ->
                listOf(
                    command.incidentId.toString(),
                    command.title,
                    command.description,
                    command.summary,
                    command.severity,
                    command.mode?.wire,
                    command.visibility?.wire,
                    command.incidentType,
                    command.message,
                    command.customerImpact,
                    command.nextUpdateAt?.toString(),
                    command.clearNextUpdateAt.toString(),
                    command.pauseUpdateReminders?.toString(),
                    command.status?.wire,
                )
            is RequestIncidentUpdateCommand ->
                listOf(command.incidentId.toString(), command.message, command.dueAt?.toString())
            is PauseIncidentUpdateRemindersCommand ->
                listOf(command.incidentId.toString(), command.paused.toString(), command.rescheduleAt?.toString())
            is TransitionIncidentCommand ->
                listOf(command.incidentId.toString(), command.targetStatus.wire, command.note)
            else -> error("Unsupported primary incident command")
        }

    private fun responderParts(command: IncidentCommand): List<String?> =
        when (command) {
            is AssignIncidentRoleCommand ->
                listOf(
                    command.incidentId.toString(),
                    command.roleDefinitionId.toString(),
                    command.assigneeUserId.toString(),
                )
            is ClaimIncidentRoleCommand ->
                listOf(command.incidentId.toString(), command.roleDefinitionId.toString())
            is UnassignIncidentRoleCommand ->
                listOf(command.incidentId.toString(), command.roleDefinitionId.toString())
            is HandoverIncidentRoleCommand ->
                listOf(
                    command.incidentId.toString(),
                    command.roleDefinitionId.toString(),
                    command.toUserId.toString(),
                    command.note,
                )
            is SetIncidentParticipationCommand ->
                listOf(command.incidentId.toString(), command.userId.toString(), command.participationType.wire)
            is LeaveIncidentCommand -> listOf(command.incidentId.toString(), command.userId.toString())
            else -> error("Unsupported incident responder command")
        }

    private fun supportingParts(command: IncidentCommand): List<String?> =
        when (command) {
            is AddIncidentActionCommand,
            is ClaimIncidentActionCommand,
            is ReassignIncidentActionCommand,
            is CompleteIncidentActionCommand,
            is CancelIncidentActionCommand,
            is ConvertIncidentActionToFollowUpCommand,
            is AddIncidentFollowUpCommand,
            is UpdateIncidentFollowUpCommand,
            is AcceptIncidentFollowUpCommand,
            is CompleteIncidentFollowUpCommand,
            is CancelIncidentFollowUpCommand,
            -> actionParts(command)
            is AddIncidentTimelineEventCommand ->
                listOf(command.incidentId.toString(), command.eventType, canonicalDetails(command.details))
            is LinkOnCallAlertCommand -> listOf(command.incidentId.toString(), command.alertId.toString())
            is LinkIncidentSourceCommand ->
                listOf(
                    command.incidentId.toString(),
                    command.source.sourceType.wire,
                    command.source.sourceKey,
                    command.source.onCallAlertId?.toString(),
                    command.source.alertEpisodeId?.toString(),
                    command.source.label,
                    command.source.sourceUrl,
                    canonicalDetails(command.source.metadata),
                )
            is UnlinkIncidentSourceCommand ->
                listOf(command.incidentId.toString(), command.sourceType.wire, command.sourceKey)
            is ResolveIncidentCommand -> listOf(command.incidentId.toString(), command.note)
            is CancelIncidentCommand -> listOf(command.incidentId.toString(), command.reason)
            is ReopenIncidentCommand -> listOf(command.incidentId.toString(), command.reason)
            else -> error("Unsupported supporting incident command")
        }

    private fun actionParts(command: IncidentCommand): List<String?> =
        when (command) {
            is AddIncidentActionCommand ->
                listOf(
                    command.incidentId.toString(),
                    command.title,
                    command.assigneeUserId?.toString(),
                    command.description,
                    command.source.wire,
                    command.slackChannelId,
                    command.slackMessageTs,
                )
            is ClaimIncidentActionCommand -> listOf(command.incidentId.toString(), command.actionResourceId)
            is ReassignIncidentActionCommand ->
                listOf(
                    command.incidentId.toString(),
                    command.actionResourceId,
                    command.assigneeUserId.toString(),
                )
            is CompleteIncidentActionCommand ->
                listOf(command.incidentId.toString(), command.actionResourceId, command.note)
            is CancelIncidentActionCommand ->
                listOf(command.incidentId.toString(), command.actionResourceId, command.reason)
            is ConvertIncidentActionToFollowUpCommand ->
                listOf(command.incidentId.toString(), command.actionResourceId, command.followUpDescription)
            is AddIncidentFollowUpCommand ->
                listOf(
                    command.incidentId.toString(),
                    command.title,
                    command.description,
                    command.ownerUserId?.toString(),
                    command.ownerTeamId?.toString(),
                    command.priority.wire,
                    command.labels.joinToString("\u001f"),
                    command.dueAt?.toString(),
                    command.slaMinutes?.toString(),
                    command.reminderMinutes?.toString(),
                    command.source.wire,
                    command.slackChannelId,
                    command.slackMessageTs,
                )
            is UpdateIncidentFollowUpCommand ->
                listOf(
                    command.incidentId.toString(),
                    command.followUpResourceId,
                    command.title,
                    command.description,
                    command.ownerUserId?.toString(),
                    command.ownerTeamId?.toString(),
                    command.priority?.wire,
                    command.labels?.joinToString("\u001f"),
                    command.dueAt?.toString(),
                    command.clearDueAt.toString(),
                    command.slaMinutes?.toString(),
                    command.reminderMinutes?.toString(),
                    command.clearReminderAt.toString(),
                )
            is AcceptIncidentFollowUpCommand ->
                listOf(command.incidentId.toString(), command.followUpResourceId)
            is CompleteIncidentFollowUpCommand ->
                listOf(command.incidentId.toString(), command.followUpResourceId, command.note)
            is CancelIncidentFollowUpCommand ->
                listOf(command.incidentId.toString(), command.followUpResourceId, command.reason)
            else -> error("Unsupported incident action command")
        }

    private fun commonParts(command: IncidentCommand): List<String?> =
        listOf(command.type.wire, command.expectedVersion?.toString())

    private fun canonicalDetails(details: Map<String, JsonElement>): String =
        json.encodeToString<JsonElement>(
            JsonObject(details.toSortedMap().mapValues { (_, value) -> value.canonical() }),
        )

    private fun JsonElement.canonical(): JsonElement =
        when (this) {
            is JsonObject -> JsonObject(toSortedMap().mapValues { (_, value) -> value.canonical() })
            is JsonArray -> JsonArray(map { element -> element.canonical() })
            else -> this
        }
}
