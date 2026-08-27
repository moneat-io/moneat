// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.enterprise.alertroutes.commands.AlertGroupActor
import com.moneat.enterprise.alertroutes.commands.AlertGroupCommand
import com.moneat.enterprise.alertroutes.commands.AlertGroupPolicy
import com.moneat.enterprise.alertroutes.commands.AttachAlertGroupIncidentCommand
import com.moneat.enterprise.alertroutes.commands.CreateAlertGroupTriageCommand
import com.moneat.enterprise.alertroutes.commands.MarkAlertGroupEpisodeUnrelatedCommand
import com.moneat.enterprise.alertroutes.commands.RemoveAlertGroupEpisodeCommand
import com.moneat.enterprise.alertroutes.models.AlertGroupDecisionType
import com.moneat.enterprise.alertroutes.models.AlertGroupMemberState
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingBehavior
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupCommands
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupDecisions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupMembers
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroups
import com.moneat.enterprise.incidents.commands.DeclareIncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandConflictException
import com.moneat.enterprise.incidents.commands.IncidentCommandDeniedException
import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.commands.IncidentSourceReference
import com.moneat.enterprise.incidents.commands.LinkIncidentSourceCommand
import com.moneat.enterprise.incidents.models.IncidentParticipationType
import com.moneat.enterprise.incidents.models.NativeIncidentParticipants
import com.moneat.enterprise.incidents.models.NativeIncidentRoleAssignments
import com.moneat.enterprise.incidents.models.IncidentSourceType
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.org.services.OrgRole
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Idempotent responder decisions over a durable alert group. */
class AlertGroupCommandService(
    private val policy: AlertGroupPolicy = AlertGroupPolicy(),
    private val incidentCommands: IncidentCommandService = IncidentCommandService(),
) {
    fun execute(command: AlertGroupCommand): AlertGroupCommandResult {
        policy.requireEnabled(command.actor.organizationId)
        validate(command)
        return transaction {
            lockOrganization(command.actor.organizationId)
            requireMembership(command)
            replay(command)?.let { return@transaction it }
            val group = AlertGroupReader.requireGroup(command.actor.organizationId, command.groupId, lock = true)
            requireVersion(command, group)
            when (command) {
                is MarkAlertGroupEpisodeUnrelatedCommand ->
                    changeMembership(
                        command,
                        group,
                        AlertGroupMemberState.UNRELATED,
                        AlertGroupDecisionType.MARKED_UNRELATED,
                    )
                is RemoveAlertGroupEpisodeCommand ->
                    changeMembership(command, group, AlertGroupMemberState.REMOVED, AlertGroupDecisionType.REMOVED)
                is AttachAlertGroupIncidentCommand -> attach(command, group)
                is CreateAlertGroupTriageCommand -> createTriage(command, group)
            }
            val updated = AlertGroupReader.requireGroup(command.actor.organizationId, command.groupId)
            recordCommand(command, updated)
            AlertGroupCommandResult(AlertGroupReader.load(command.actor.organizationId, updated), replayed = false)
        }
    }

    private fun changeMembership(
        command: AlertGroupCommand,
        group: ResultRow,
        nextState: AlertGroupMemberState,
        decision: AlertGroupDecisionType,
    ) {
        val episodeId = when (command) {
            is MarkAlertGroupEpisodeUnrelatedCommand -> command.episodeId
            is RemoveAlertGroupEpisodeCommand -> command.episodeId
            else -> error("Membership command expected")
        }
        val member =
            requireMember(command.actor.organizationId, group[EnterpriseAlertGroups.id].value, episodeId)
        val now = Clock.System.now()
        EnterpriseAlertGroupMembers.update({
            EnterpriseAlertGroupMembers.id eq member[EnterpriseAlertGroupMembers.id]
        }) {
            it[state] = nextState.wire
            it[version] = member[EnterpriseAlertGroupMembers.version] + 1
            it[updatedAt] = now
        }
        incrementGroup(group, incidentId = group[EnterpriseAlertGroups.incidentId], now = now)
        insertDecision(
            CommandDecision(
                command = command,
                groupId = group[EnterpriseAlertGroups.id].value,
                type = decision,
                episodeId = member[EnterpriseAlertGroupMembers.alertEpisodeId],
                incidentId = group[EnterpriseAlertGroups.incidentId],
                now = now,
            ),
        )
    }

    private fun attach(command: AttachAlertGroupIncidentCommand, group: ResultRow) {
        val incident = requireEligibleIncident(command)
        val internalIncidentId = incident[OnCallIncidents.id].value
        val existing = group[EnterpriseAlertGroups.incidentId]
        if (existing != null && existing != internalIncidentId) {
            throw IncidentCommandConflictException("Alert group is already attached to another incident")
        }
        if (command.automatic) {
            if (group[EnterpriseAlertGroups.groupingBehavior] != AlertRouteGroupingBehavior.AUTOMATIC.wire) {
                throw IncidentCommandConflictException("Automatic attachment is not enabled for this alert group")
            }
            policy.requireAutomaticCorrelation(
                command.actor.organizationId,
                "automatic-correlation:${command.commandKey}",
            )
        }
        linkActiveEpisodes(command, group, internalIncidentId)
        val now = Clock.System.now()
        incrementGroup(group, incidentId = internalIncidentId, now = now)
        insertDecision(
            CommandDecision(
                command = command,
                groupId = group[EnterpriseAlertGroups.id].value,
                type = if (command.automatic) {
                    AlertGroupDecisionType.AUTOMATICALLY_ATTACHED
                } else {
                    AlertGroupDecisionType.ATTACHED
                },
                incidentId = internalIncidentId,
                now = now,
            ),
        )
    }

    private fun createTriage(command: CreateAlertGroupTriageCommand, group: ResultRow) {
        if (group[EnterpriseAlertGroups.incidentId] != null) {
            throw IncidentCommandConflictException("Alert group is already attached to an incident")
        }
        val snapshot = group[EnterpriseAlertGroups.incidentTemplateSnapshot]
        val title = command.title?.trim()?.takeIf(String::isNotEmpty) ?: snapshot.text("title") ?: "Alert group"
        val actor = IncidentCommandActor(command.actor.organizationId, command.actor.userId, command.actor.origin)
        val result = incidentCommands.execute(
            DeclareIncidentCommand(
                commandKey = nestedCommandKey("group-triage", command.commandKey),
                actor = actor,
                title = title,
                description = null,
                summary = command.summary?.trim()?.takeIf(String::isNotEmpty) ?: snapshot.text("summary"),
                severity = command.severity?.trim()?.takeIf(String::isNotEmpty) ?: snapshot.text("severity"),
                incidentType =
                    command.incidentType?.trim()?.takeIf(String::isNotEmpty) ?: snapshot.text("incidentType"),
                initialStatus = command.initialStatus,
            ),
        )
        val attach = AttachAlertGroupIncidentCommand(
            commandKey = command.commandKey,
            actor = command.actor,
            groupId = command.groupId,
            expectedVersion = command.expectedVersion,
            incidentId = Uuid.parse(result.incidentResourceId),
        )
        linkActiveEpisodes(attach, group, result.incidentId)
        val now = Clock.System.now()
        incrementGroup(group, incidentId = result.incidentId, now = now)
        insertDecision(
            CommandDecision(
                command = command,
                groupId = group[EnterpriseAlertGroups.id].value,
                type = AlertGroupDecisionType.TRIAGE_CREATED,
                incidentId = result.incidentId,
                details = mapOf("incidentId" to JsonPrimitive(result.incidentResourceId)),
                now = now,
            ),
        )
    }

    private fun linkActiveEpisodes(command: AttachAlertGroupIncidentCommand, group: ResultRow, incidentId: Int) {
        val actor = IncidentCommandActor(command.actor.organizationId, command.actor.userId, command.actor.origin)
        val members =
            (EnterpriseAlertGroupMembers innerJoin AlertEpisodes)
                .selectAll()
                .where {
                    (EnterpriseAlertGroupMembers.organizationId eq command.actor.organizationId) and
                        (EnterpriseAlertGroupMembers.groupId eq group[EnterpriseAlertGroups.id].value) and
                        (EnterpriseAlertGroupMembers.state eq AlertGroupMemberState.ACTIVE.wire)
                }.toList()
        members.forEach { member ->
            val episodeResourceId = member[AlertEpisodes.resourceId]
            incidentCommands.execute(
                LinkIncidentSourceCommand(
                    commandKey = nestedCommandKey("group-source", "${command.commandKey}:$episodeResourceId"),
                    actor = actor,
                    incidentId = incidentId,
                    source = IncidentSourceReference(
                        sourceType = IncidentSourceType.ALERT_EPISODE,
                        sourceKey = episodeResourceId.toString(),
                        alertEpisodeId = member[AlertEpisodes.id].value,
                        label = member[AlertEpisodes.title],
                    ),
                ),
            )
        }
    }

    private fun incrementGroup(group: ResultRow, incidentId: Int?, now: kotlin.time.Instant) {
        EnterpriseAlertGroups.update({
            (EnterpriseAlertGroups.id eq group[EnterpriseAlertGroups.id]) and
                (EnterpriseAlertGroups.version eq group[EnterpriseAlertGroups.version])
        }) {
            it[version] = group[EnterpriseAlertGroups.version] + 1
            it[EnterpriseAlertGroups.incidentId] = incidentId
            if (incidentId != null) it[candidateIncidentId] = null
            it[updatedAt] = now
        }.takeIf { it == 1 } ?: throw IncidentCommandConflictException("Alert group changed concurrently")
    }

    private fun requireMember(organizationId: Int, groupId: Int, episodeId: Uuid): ResultRow =
        (EnterpriseAlertGroupMembers innerJoin AlertEpisodes)
            .selectAll()
            .where {
                (EnterpriseAlertGroupMembers.organizationId eq organizationId) and
                    (EnterpriseAlertGroupMembers.groupId eq groupId) and
                    (AlertEpisodes.resourceId eq episodeId)
            }.singleOrNull() ?: throw IncidentCommandNotFoundException("Alert group episode not found")

    private fun requireEligibleIncident(command: AttachAlertGroupIncidentCommand): ResultRow {
        val incident = OnCallIncidents.selectAll().where {
            (OnCallIncidents.organizationId eq command.actor.organizationId) and
                (OnCallIncidents.resourceId eq command.incidentId)
        }.singleOrNull() ?: throw IncidentCommandNotFoundException("Incident not found")
        val status = NativeIncidentStatus.fromWire(incident[OnCallIncidents.status])
        if (status !in ELIGIBLE_INCIDENT_STATUSES) {
            throw IncidentCommandConflictException("Only triage or active incidents can receive an alert group")
        }
        if (!command.automatic && incident[OnCallIncidents.visibility] == NativeIncidentVisibility.PRIVATE.wire) {
            requirePrivateIncidentAccess(command.actor, incident[OnCallIncidents.id].value)
        }
        return incident
    }

    private fun requirePrivateIncidentAccess(actor: AlertGroupActor, incidentId: Int) {
        val membership = Memberships.selectAll().where {
            (Memberships.organization_id eq actor.organizationId) and
                (Memberships.user_id eq actor.userId)
        }.singleOrNull()
        val role = membership?.get(Memberships.role)?.let { value ->
            runCatching { OrgRole.fromString(value) }.getOrNull()
        }
        if (role?.level ?: -1 >= OrgRole.ADMIN.level) return

        val hasIncidentRole = NativeIncidentRoleAssignments.selectAll().where {
            (NativeIncidentRoleAssignments.organizationId eq actor.organizationId) and
                (NativeIncidentRoleAssignments.incidentId eq incidentId) and
                (NativeIncidentRoleAssignments.assigneeUserId eq actor.userId) and
                NativeIncidentRoleAssignments.endedAt.isNull()
        }.limit(1).firstOrNull() != null
        val isParticipant = NativeIncidentParticipants.selectAll().where {
            (NativeIncidentParticipants.organizationId eq actor.organizationId) and
                (NativeIncidentParticipants.incidentId eq incidentId) and
                (NativeIncidentParticipants.userId eq actor.userId) and
                (NativeIncidentParticipants.participationType eq IncidentParticipationType.PARTICIPANT.wire) and
                NativeIncidentParticipants.leftAt.isNull()
        }.limit(1).firstOrNull() != null
        if (!hasIncidentRole && !isParticipant) {
            throw IncidentCommandDeniedException(
                "Actor is not authorized to attach an alert group to this private incident",
            )
        }
    }

    private fun replay(command: AlertGroupCommand): AlertGroupCommandResult? {
        val existing = EnterpriseAlertGroupCommands.selectAll().where {
            (EnterpriseAlertGroupCommands.organizationId eq command.actor.organizationId) and
                (EnterpriseAlertGroupCommands.commandKey eq command.commandKey)
        }.singleOrNull() ?: return null
        if (existing[EnterpriseAlertGroupCommands.commandType] != command.type.wire ||
            existing[EnterpriseAlertGroupCommands.requestFingerprint] != fingerprint(command)
        ) {
            throw IncidentCommandConflictException("Alert group command key was already used for another request")
        }
        return AlertGroupCommandResult(AlertGroupReader.get(command.actor.organizationId, command.groupId), true)
    }

    private fun recordCommand(command: AlertGroupCommand, group: ResultRow) {
        EnterpriseAlertGroupCommands.insert {
            it[organizationId] = command.actor.organizationId
            it[groupId] = group[EnterpriseAlertGroups.id].value
            it[commandKey] = command.commandKey
            it[commandType] = command.type.wire
            it[requestFingerprint] = fingerprint(command)
            it[resultVersion] = group[EnterpriseAlertGroups.version]
            it[createdAt] = Clock.System.now()
        }
    }

    private fun insertDecision(decision: CommandDecision) {
        val command = decision.command
        EnterpriseAlertGroupDecisions.insert {
            it[resourceId] = Uuid.random()
            it[organizationId] = command.actor.organizationId
            it[EnterpriseAlertGroupDecisions.groupId] = decision.groupId
            it[decisionType] = decision.type.wire
            it[alertEpisodeId] = decision.episodeId
            it[EnterpriseAlertGroupDecisions.incidentId] = decision.incidentId
            it[actorUserId] = command.actor.userId
            it[commandKey] = command.commandKey
            it[EnterpriseAlertGroupDecisions.details] = decision.details
            it[createdAt] = decision.now
        }
    }

    private data class CommandDecision(
        val command: AlertGroupCommand,
        val groupId: Int,
        val type: AlertGroupDecisionType,
        val episodeId: Int? = null,
        val incidentId: Int? = null,
        val details: Map<String, JsonElement> = emptyMap(),
        val now: kotlin.time.Instant,
    )

    private fun validate(command: AlertGroupCommand) {
        require(command.commandKey.isNotBlank()) { "Alert group command key is required" }
        require(command.commandKey.length <= MAX_COMMAND_KEY_LENGTH) { "Alert group command key is too long" }
        require(command.expectedVersion > 0) { "Expected alert group version must be positive" }
        require(command.actor.organizationId > 0 && command.actor.userId > 0) { "Alert group actor is invalid" }
        require(command.actor.origin.isNotBlank()) { "Alert group command origin is required" }
    }

    private fun requireVersion(command: AlertGroupCommand, group: ResultRow) {
        if (group[EnterpriseAlertGroups.version] != command.expectedVersion) {
            throw IncidentCommandConflictException("Alert group version does not match expected version")
        }
    }

    private fun requireMembership(command: AlertGroupCommand) {
        val member = Memberships.selectAll().where {
            (Memberships.organization_id eq command.actor.organizationId) and
                (Memberships.user_id eq command.actor.userId)
        }.singleOrNull()
        if (member == null) {
            throw IncidentCommandDeniedException("Actor is not a member of the alert group organization")
        }
    }

    private fun lockOrganization(organizationId: Int) {
        Organizations.selectAll().where { Organizations.id eq organizationId }.forUpdate().singleOrNull()
            ?: throw IncidentCommandNotFoundException("Organization not found")
    }

    private fun fingerprint(command: AlertGroupCommand): String {
        val parts = mutableListOf(command.type.wire, command.groupId.toString(), command.expectedVersion.toString())
        when (command) {
            is MarkAlertGroupEpisodeUnrelatedCommand -> parts.add(command.episodeId.toString())
            is RemoveAlertGroupEpisodeCommand -> parts.add(command.episodeId.toString())
            is AttachAlertGroupIncidentCommand -> {
                parts.add(command.incidentId.toString())
                parts.add(command.automatic.toString())
            }
            is CreateAlertGroupTriageCommand -> {
                parts.add(command.title.orEmpty())
                parts.add(command.summary.orEmpty())
                parts.add(command.initialStatus.wire)
                parts.add(command.severity.orEmpty())
                parts.add(command.incidentType.orEmpty())
            }
        }
        return sha256(parts.joinToString("\u001F"))
    }

    private fun nestedCommandKey(prefix: String, value: String): String = "$prefix:${sha256(value)}"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun Map<String, JsonElement>.text(key: String): String? = (get(key) as? JsonPrimitive)?.content

    private companion object {
        const val MAX_COMMAND_KEY_LENGTH = 160
        val ELIGIBLE_INCIDENT_STATUSES = setOf(NativeIncidentStatus.TRIAGE, NativeIncidentStatus.ACTIVE)
    }
}
