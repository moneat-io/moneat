// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.followups

import com.moneat.enterprise.incidents.commands.AcceptIncidentFollowUpCommand
import com.moneat.enterprise.incidents.commands.AddIncidentFollowUpCommand
import com.moneat.enterprise.incidents.commands.CancelIncidentFollowUpCommand
import com.moneat.enterprise.incidents.commands.CompleteIncidentFollowUpCommand
import com.moneat.enterprise.incidents.commands.ExistingIncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandConflictException
import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.enterprise.incidents.commands.UpdateIncidentFollowUpCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.OrganizationTeams
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

internal class IncidentFollowUpCommandHandler(
    private val requireIncident: (ExistingIncidentCommand) -> ResultRow,
    private val loadMutation: (Int, Boolean) -> IncidentCommandService.IncidentMutation,
    private val appendVersionedEvent: (
        ExistingIncidentCommand,
        String,
        Map<String, JsonElement>,
    ) -> IncidentCommandService.IncidentMutation,
    private val requireUserMembership: (Int, Int) -> Unit,
    private val userResourceId: (Int) -> String,
) {
    fun apply(command: IncidentCommand): IncidentCommandService.IncidentMutation =
        when (command) {
            is AddIncidentFollowUpCommand -> add(command)
            is UpdateIncidentFollowUpCommand -> update(command)
            is AcceptIncidentFollowUpCommand -> transition(command, IncidentFollowUpStatus.ACCEPTED)
            is CompleteIncidentFollowUpCommand -> transition(command, IncidentFollowUpStatus.COMPLETED, command.note)
            is CancelIncidentFollowUpCommand -> transition(command, IncidentFollowUpStatus.CANCELLED, command.reason)
            else -> error("Unsupported follow-up command: ${command.type.wire}")
        }

    private fun add(command: AddIncidentFollowUpCommand): IncidentCommandService.IncidentMutation {
        val title = command.title.trim()
        require(title.isNotEmpty()) { "Incident follow-up title is required" }
        require(title.length <= MAX_TITLE_LENGTH) { "Incident follow-up title is too long" }
        val description = command.description.trim()
        require(description.isNotEmpty()) { "Incident follow-up description is required" }
        require(description.length <= MAX_DESCRIPTION_LENGTH) {
            "Incident follow-up description is too long"
        }
        requireOwner(command.actor.organizationId, command.ownerUserId, command.ownerTeamId)
        val labels = normalizeLabels(command.labels)
        validatePolicy(command.slaMinutes, command.reminderMinutes)
        require(command.slackChannelId == null || command.slackChannelId.length <= MAX_SLACK_CHANNEL_ID_LENGTH) {
            "Slack channel ID is too long"
        }
        require(command.slackMessageTs == null || command.slackMessageTs.length <= MAX_SLACK_MESSAGE_TS_LENGTH) {
            "Slack message reference is too long"
        }
        val now = Clock.System.now()
        val effectiveDueAt = command.dueAt ?: command.slaMinutes?.let { now.plus(it.minutes) }
        val resourceId = Uuid.random()
        NativeIncidentFollowUps.insert {
            it[NativeIncidentFollowUps.resourceId] = resourceId
            it[organizationId] = command.actor.organizationId
            it[incidentId] = command.incidentId
            it[NativeIncidentFollowUps.title] = title
            it[NativeIncidentFollowUps.description] = description
            it[ownerUserId] = command.ownerUserId
            it[ownerTeamId] = command.ownerTeamId
            it[priority] = command.priority.wire
            it[NativeIncidentFollowUps.labels] = labels
            it[NativeIncidentFollowUps.dueAt] = effectiveDueAt
            it[slaMinutes] = command.slaMinutes
            it[reminderMinutes] = command.reminderMinutes
            it[nextReminderAt] = command.reminderMinutes?.let { minutes -> now.plus(minutes.minutes) }
            it[escalationLevel] = 0
            it[status] = IncidentFollowUpStatus.OPEN.wire
            it[acceptedBy] = null
            it[acceptedAt] = null
            it[completedBy] = null
            it[completedAt] = null
            it[createdBy] = command.actor.userId
            it[sourceType] = command.source.wire
            it[slackChannelId] = command.slackChannelId
            it[slackMessageTs] = command.slackMessageTs
            it[createdAt] = now
            it[updatedAt] = now
        }
        val details = buildMap<String, JsonElement> {
            put("followUpId", JsonPrimitive(resourceId.toString()))
            put("title", JsonPrimitive(title))
            put("priority", JsonPrimitive(command.priority.wire))
            put("labels", JsonArray(labels.map(::JsonPrimitive)))
            put("status", JsonPrimitive(IncidentFollowUpStatus.OPEN.wire))
            command.ownerUserId?.let { put("ownerUserId", JsonPrimitive(userResourceId(it))) }
            command.ownerTeamId?.let { put("ownerTeamId", JsonPrimitive(teamResourceId(it))) }
            effectiveDueAt?.let { put("dueAt", JsonPrimitive(it.toString())) }
        }
        return appendVersionedEvent(command, "FOLLOW_UP_CREATED", details).copy(
            followUpResourceId = resourceId.toString(),
        )
    }

    private fun update(command: UpdateIncidentFollowUpCommand): IncidentCommandService.IncidentMutation {
        val currentIncident = requireIncident(command)
        val followUp = requireFollowUp(command)
        if (!hasChanges(command)) {
            return loadMutation(currentIncident[OnCallIncidents.id].value, false).copy(
                followUpResourceId = followUp[NativeIncidentFollowUps.resourceId].toString(),
            )
        }
        val now = Clock.System.now()
        val prepared = prepareUpdate(command, now)
        applyUpdate(followUp, command, prepared, now)
        val details = updateDetails(followUp, command, prepared)
        return appendVersionedEvent(command, "FOLLOW_UP_UPDATED", details).copy(
            followUpResourceId = followUp[NativeIncidentFollowUps.resourceId].toString(),
        )
    }

    private fun hasChanges(command: UpdateIncidentFollowUpCommand): Boolean =
        listOf(
            command.title,
            command.description,
            command.priority,
            command.labels,
            command.dueAt,
            command.slaMinutes,
            command.reminderMinutes,
        ).any { it != null } || command.clearDueAt || command.clearReminderAt || hasOwnerChange(command)

    private fun hasOwnerChange(command: UpdateIncidentFollowUpCommand): Boolean =
        command.ownerUserId != null || command.ownerTeamId != null

    private fun prepareUpdate(command: UpdateIncidentFollowUpCommand, now: kotlin.time.Instant): PreparedUpdate {
        val title = command.title?.trim()?.also { value ->
            require(value.isNotEmpty()) { "Incident follow-up title is required" }
            require(value.length <= MAX_TITLE_LENGTH) { "Incident follow-up title is too long" }
        }
        val description = command.description?.trim()?.also { value ->
            require(value.isNotEmpty()) { "Incident follow-up description is required" }
            require(value.length <= MAX_DESCRIPTION_LENGTH) {
                "Incident follow-up description is too long"
            }
        }
        val labels = command.labels?.let(::normalizeLabels)
        validatePolicy(command.slaMinutes, command.reminderMinutes)
        val ownerChange = hasOwnerChange(command)
        if (ownerChange) requireOwner(command.actor.organizationId, command.ownerUserId, command.ownerTeamId)
        val dueAtChanged = command.clearDueAt || command.dueAt != null || command.slaMinutes != null
        val dueAt = when {
            command.clearDueAt -> null
            command.dueAt != null -> command.dueAt
            command.slaMinutes != null -> now.plus(command.slaMinutes.minutes)
            else -> null
        }
        return PreparedUpdate(title, description, labels, ownerChange, dueAtChanged, dueAt)
    }

    private fun applyUpdate(
        followUp: ResultRow,
        command: UpdateIncidentFollowUpCommand,
        prepared: PreparedUpdate,
        now: kotlin.time.Instant,
    ) {
        NativeIncidentFollowUps.update({ NativeIncidentFollowUps.id eq followUp[NativeIncidentFollowUps.id] }) {
            prepared.title?.let { value -> it[NativeIncidentFollowUps.title] = value }
            prepared.description?.let { value -> it[NativeIncidentFollowUps.description] = value }
            if (prepared.ownerChange) {
                it[NativeIncidentFollowUps.ownerUserId] = command.ownerUserId
                it[NativeIncidentFollowUps.ownerTeamId] = command.ownerTeamId
            }
            command.priority?.let { value -> it[NativeIncidentFollowUps.priority] = value.wire }
            prepared.labels?.let { value -> it[NativeIncidentFollowUps.labels] = value }
            if (prepared.dueAtChanged) {
                it[NativeIncidentFollowUps.dueAt] = prepared.dueAt
            }
            command.slaMinutes?.let { value -> it[NativeIncidentFollowUps.slaMinutes] = value }
            if (command.clearReminderAt) {
                it[NativeIncidentFollowUps.reminderMinutes] = null
                it[NativeIncidentFollowUps.nextReminderAt] = null
            } else {
                command.reminderMinutes?.let { value ->
                    it[NativeIncidentFollowUps.reminderMinutes] = value
                    it[NativeIncidentFollowUps.nextReminderAt] = now.plus(value.minutes)
                }
            }
            it[NativeIncidentFollowUps.updatedAt] = now
        }
    }

    private fun updateDetails(
        followUp: ResultRow,
        command: UpdateIncidentFollowUpCommand,
        prepared: PreparedUpdate,
    ): Map<String, JsonElement> = buildMap {
        put("followUpId", JsonPrimitive(followUp[NativeIncidentFollowUps.resourceId].toString()))
        prepared.title?.let { put("title", JsonPrimitive(it)) }
        prepared.description?.let { put("description", JsonPrimitive(it)) }
        command.priority?.let { put("priority", JsonPrimitive(it.wire)) }
        prepared.labels?.let { put("labels", JsonArray(it.map(::JsonPrimitive))) }
        if (prepared.dueAtChanged) {
            prepared.dueAt?.let { put("dueAt", JsonPrimitive(it.toString())) } ?: put("dueAt", JsonNull)
        }
        if (command.clearReminderAt) put("reminderMinutes", JsonNull)
        command.reminderMinutes?.let { put("reminderMinutes", JsonPrimitive(it)) }
        if (prepared.ownerChange) {
            command.ownerUserId?.let { put("ownerUserId", JsonPrimitive(userResourceId(it))) }
            command.ownerTeamId?.let { put("ownerTeamId", JsonPrimitive(teamResourceId(it))) }
        }
    }

    private fun transition(
        command: ExistingIncidentCommand,
        target: IncidentFollowUpStatus,
        note: String? = null,
    ): IncidentCommandService.IncidentMutation {
        val currentIncident = requireIncident(command)
        val followUp = requireFollowUp(command)
        val currentStatus = IncidentFollowUpStatus.entries.firstOrNull {
            it.wire == followUp[NativeIncidentFollowUps.status]
        } ?: throw IncidentCommandConflictException("Unknown incident follow-up status")
        if (currentStatus == target) {
            return loadMutation(currentIncident[OnCallIncidents.id].value, false).copy(
                followUpResourceId = followUp[NativeIncidentFollowUps.resourceId].toString(),
            )
        }
        val allowed = when (currentStatus) {
            IncidentFollowUpStatus.OPEN -> setOf(IncidentFollowUpStatus.ACCEPTED, IncidentFollowUpStatus.CANCELLED)
            IncidentFollowUpStatus.ACCEPTED -> setOf(IncidentFollowUpStatus.COMPLETED, IncidentFollowUpStatus.CANCELLED)
            IncidentFollowUpStatus.COMPLETED,
            IncidentFollowUpStatus.CANCELLED,
            -> emptySet()
        }
        if (target !in allowed) {
            throw IncidentCommandConflictException(
                "Cannot transition follow-up from ${currentStatus.wire} to ${target.wire}",
            )
        }
        val now = Clock.System.now()
        NativeIncidentFollowUps.update({ NativeIncidentFollowUps.id eq followUp[NativeIncidentFollowUps.id] }) {
            it[NativeIncidentFollowUps.status] = target.wire
            if (target == IncidentFollowUpStatus.ACCEPTED) {
                it[NativeIncidentFollowUps.acceptedBy] = command.actor.userId
                it[NativeIncidentFollowUps.acceptedAt] = now
            }
            if (target == IncidentFollowUpStatus.COMPLETED) {
                it[NativeIncidentFollowUps.completedBy] = command.actor.userId
                it[NativeIncidentFollowUps.completedAt] = now
            }
            it[NativeIncidentFollowUps.updatedAt] = now
        }
        val details = buildMap<String, JsonElement> {
            put("followUpId", JsonPrimitive(followUp[NativeIncidentFollowUps.resourceId].toString()))
            put("previousStatus", JsonPrimitive(currentStatus.wire))
            put("status", JsonPrimitive(target.wire))
            note?.trim()?.takeIf(String::isNotEmpty)?.let { put("note", JsonPrimitive(it)) }
        }
        return appendVersionedEvent(command, "FOLLOW_UP_${target.wire}", details).copy(
            followUpResourceId = followUp[NativeIncidentFollowUps.resourceId].toString(),
        )
    }

    private fun requireFollowUp(command: ExistingIncidentCommand): ResultRow {
        val resourceId = followUpResourceId(command)
        return NativeIncidentFollowUps
            .selectAll()
            .where {
                (NativeIncidentFollowUps.organizationId eq command.actor.organizationId) and
                    (NativeIncidentFollowUps.incidentId eq command.incidentId) and
                    (NativeIncidentFollowUps.resourceId eq resourceId)
            }
            .forUpdate()
            .singleOrNull()
            ?: throw IncidentCommandNotFoundException(FOLLOW_UP_NOT_FOUND_MESSAGE)
    }

    private fun requireOwner(organizationId: Int, ownerUserId: Int?, ownerTeamId: Int?) {
        require((ownerUserId == null) != (ownerTeamId == null)) {
            "Incident follow-up requires exactly one user or team owner"
        }
        ownerUserId?.let { requireUserMembership(organizationId, it) }
        ownerTeamId?.let { teamId ->
            val exists = OrganizationTeams.selectAll().where {
                (OrganizationTeams.organizationId eq organizationId) and (OrganizationTeams.id eq teamId)
            }.limit(1).any()
            require(exists) { "Incident follow-up team owner is not in the organization" }
        }
    }

    private fun normalizeLabels(labels: List<String>): List<String> {
        require(labels.size <= MAX_LABELS) { "Too many incident follow-up labels" }
        return labels.map { label ->
            val normalized = label.trim()
            require(normalized.isNotEmpty()) { "Incident follow-up labels cannot be empty" }
            require(normalized.length <= MAX_LABEL_LENGTH) { "Incident follow-up label is too long" }
            normalized
        }.distinct()
    }

    private fun validatePolicy(slaMinutes: Int?, reminderMinutes: Int?) {
        require(slaMinutes == null || slaMinutes > 0) { "Follow-up SLA must be positive" }
        require(reminderMinutes == null || reminderMinutes > 0) { "Follow-up reminder must be positive" }
    }

    private fun teamResourceId(teamId: Int): String =
        OrganizationTeams
            .selectAll()
            .where { OrganizationTeams.id eq teamId }
            .single()[OrganizationTeams.resourceId]
            .toString()

    private fun followUpResourceId(command: ExistingIncidentCommand): Uuid {
        val value = when (command) {
            is UpdateIncidentFollowUpCommand -> command.followUpResourceId
            is AcceptIncidentFollowUpCommand -> command.followUpResourceId
            is CompleteIncidentFollowUpCommand -> command.followUpResourceId
            is CancelIncidentFollowUpCommand -> command.followUpResourceId
            else -> throw IncidentCommandNotFoundException(FOLLOW_UP_NOT_FOUND_MESSAGE)
        }
        return runCatching { Uuid.parse(value) }
            .getOrElse { throw IncidentCommandNotFoundException(FOLLOW_UP_NOT_FOUND_MESSAGE) }
    }

    private data class PreparedUpdate(
        val title: String?,
        val description: String?,
        val labels: List<String>?,
        val ownerChange: Boolean,
        val dueAtChanged: Boolean,
        val dueAt: kotlin.time.Instant?,
    )

    private companion object {
        private const val MAX_TITLE_LENGTH = 255
        private const val MAX_DESCRIPTION_LENGTH = 4_000
        private const val MAX_LABELS = 20
        private const val MAX_LABEL_LENGTH = 64
        private const val MAX_SLACK_CHANNEL_ID_LENGTH = 128
        private const val MAX_SLACK_MESSAGE_TS_LENGTH = 64
        private const val FOLLOW_UP_NOT_FOUND_MESSAGE = "Incident follow-up not found"
    }
}
