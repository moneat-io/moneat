// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.enterprise.alertroutes.commands.AlertRouteActor
import com.moneat.enterprise.alertroutes.commands.AlertRouteCommand
import com.moneat.enterprise.alertroutes.commands.AlertRouteCommandResult
import com.moneat.enterprise.alertroutes.commands.AlertRouteFingerprint
import com.moneat.enterprise.alertroutes.commands.AlertRoutePolicy
import com.moneat.enterprise.alertroutes.commands.AlertRouteSpecification
import com.moneat.enterprise.alertroutes.commands.AlertRouteTargetInput
import com.moneat.enterprise.alertroutes.commands.CreateAlertRouteCommand
import com.moneat.enterprise.alertroutes.commands.DeleteAlertRouteCommand
import com.moneat.enterprise.alertroutes.commands.ReorderAlertRoutesCommand
import com.moneat.enterprise.alertroutes.commands.UpdateAlertRouteCommand
import com.moneat.enterprise.alertroutes.models.AlertRouteChangeType
import com.moneat.enterprise.alertroutes.models.AlertRouteDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteRevisionRecord
import com.moneat.enterprise.alertroutes.models.AlertRouteTargetKind
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteActions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteCommands
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteConditionGroups
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteConditions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteRevisions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteTargets
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRoutes
import com.moneat.enterprise.incidents.commands.IncidentCommandConflictException
import com.moneat.enterprise.incidents.commands.IncidentCommandDeniedException
import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.org.services.OrgRole
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationTeams
import com.moneat.shared.models.Organizations
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Organization-scoped CRUD, reorder, and revision history for enterprise Alert Routes.
 *
 * Mutations are rollout-gated, idempotent by command key, versioned by route revision, and audited
 * in `enterprise_alert_route_revisions`. Stale expected revisions are rejected as conflicts.
 */
class AlertRouteCommandService(
    private val policy: AlertRoutePolicy = AlertRoutePolicy(),
) {
    fun list(organizationId: Int): List<AlertRouteDefinition> {
        policy.requireReadAllowed(organizationId)
        return transaction { AlertRouteReader.load(organizationId) }
    }

    fun get(organizationId: Int, routeId: Uuid): AlertRouteDefinition {
        policy.requireReadAllowed(organizationId)
        return transaction { AlertRouteReader.loadOne(organizationId, routeId) }
            ?: throw IncidentCommandNotFoundException(ROUTE_NOT_FOUND)
    }

    fun revisions(organizationId: Int, routeId: Uuid): List<AlertRouteRevisionRecord> {
        policy.requireReadAllowed(organizationId)
        return transaction {
            AlertRouteReader
                .revisions(organizationId, routeId)
                .ifEmpty { throw IncidentCommandNotFoundException(ROUTE_NOT_FOUND) }
        }
    }

    fun execute(command: AlertRouteCommand): AlertRouteCommandResult {
        policy.requireAllowed(command)
        AlertRouteValidator.validate(command)
        return try {
            executeTransaction(command)
        } catch (error: ExposedSQLException) {
            if (error.sqlState != UNIQUE_VIOLATION_SQL_STATE) throw error
            transaction { replayResult(command) }
                ?: throw IncidentCommandConflictException("Alert route mutation conflicts with existing state")
        }
    }

    private fun executeTransaction(command: AlertRouteCommand): AlertRouteCommandResult =
        transaction {
            requireAdministrator(command.actor)
            lockOrganization(command.actor.organizationId)
            replayResult(command)?.let { return@transaction it }
            val result = when (command) {
                is CreateAlertRouteCommand -> create(command)
                is UpdateAlertRouteCommand -> update(command)
                is DeleteAlertRouteCommand -> delete(command)
                is ReorderAlertRoutesCommand -> reorder(command)
            }
            recordCommand(command, result.routes.map { it.resourceId })
            result
        }

    private fun create(command: CreateAlertRouteCommand): AlertRouteCommandResult {
        val organizationId = command.actor.organizationId
        val specification = command.specification
        policy.requireQuota(command, enabledRouteCount(organizationId) + if (specification.enabled) 1 else 0)
        requireAvailableKey(organizationId, specification.key.trim(), currentRouteId = null)
        val now = Clock.System.now()
        val routeResourceId = Uuid.random()
        val routeId =
            EnterpriseAlertRoutes.insertAndGetId {
                it[resourceId] = routeResourceId
                it[EnterpriseAlertRoutes.organizationId] = organizationId
                it[routeKey] = specification.key.trim()
                it[name] = specification.name.trim()
                it[description] = specification.description?.trim()?.takeIf(String::isNotEmpty)
                it[enabled] = specification.enabled
                it[position] = nextPosition(organizationId)
                it[revision] = INITIAL_REVISION
                it[createdBy] = command.actor.userId
                it[updatedBy] = command.actor.userId
                it[createdAt] = now
                it[updatedAt] = now
            }.value
        writeSpecification(organizationId, routeId, specification, now)
        val created = requireLoaded(organizationId, routeResourceId)
        recordRevision(
            command,
            AlertRouteRevisionEntry(routeId, routeResourceId, INITIAL_REVISION, AlertRouteChangeType.CREATED, now),
            created,
        )
        return AlertRouteCommandResult(listOf(created), replayed = false)
    }

    private fun update(command: UpdateAlertRouteCommand): AlertRouteCommandResult {
        val organizationId = command.actor.organizationId
        val existing =
            AlertRouteReader.loadOne(organizationId, command.routeId)
                ?: throw IncidentCommandNotFoundException(ROUTE_NOT_FOUND)
        requireCurrentRevision(command.expectedRevision, existing.revision)
        val desiredEnabledCount =
            enabledRouteCount(organizationId) +
                when {
                    existing.enabled == command.specification.enabled -> 0
                    command.specification.enabled -> 1
                    else -> -1
                }
        policy.requireQuota(command, desiredEnabledCount)
        requireAvailableKey(organizationId, command.specification.key.trim(), currentRouteId = existing.id)
        val now = Clock.System.now()
        val nextRevision = existing.revision + 1
        val changed =
            EnterpriseAlertRoutes.update({
                (EnterpriseAlertRoutes.id eq existing.id) and (EnterpriseAlertRoutes.revision eq existing.revision)
            }) {
                it[routeKey] = command.specification.key.trim()
                it[name] = command.specification.name.trim()
                it[description] = command.specification.description?.trim()?.takeIf(String::isNotEmpty)
                it[enabled] = command.specification.enabled
                it[revision] = nextRevision
                it[updatedBy] = command.actor.userId
                it[updatedAt] = now
            }
        if (changed == 0) throw IncidentCommandConflictException(STALE_REVISION)
        clearSpecification(existing.id)
        writeSpecification(organizationId, existing.id, command.specification, now)
        val updated = requireLoaded(organizationId, existing.resourceId)
        recordRevision(
            command,
            AlertRouteRevisionEntry(
                existing.id,
                existing.resourceId,
                nextRevision,
                AlertRouteChangeType.UPDATED,
                now,
            ),
            updated,
        )
        return AlertRouteCommandResult(listOf(updated), replayed = false)
    }

    private fun delete(command: DeleteAlertRouteCommand): AlertRouteCommandResult {
        val organizationId = command.actor.organizationId
        val existing =
            AlertRouteReader.loadOne(organizationId, command.routeId)
                ?: throw IncidentCommandNotFoundException(ROUTE_NOT_FOUND)
        requireCurrentRevision(command.expectedRevision, existing.revision)
        policy.requireQuota(command, enabledRouteCount(organizationId) - if (existing.enabled) 1 else 0)
        val now = Clock.System.now()
        recordRevision(
            command,
            AlertRouteRevisionEntry(
                existing.id,
                existing.resourceId,
                existing.revision + 1,
                AlertRouteChangeType.DELETED,
                now,
            ),
            existing.copy(revision = existing.revision + 1),
        )
        val deleted = EnterpriseAlertRoutes.deleteWhere {
            (EnterpriseAlertRoutes.id eq existing.id) and (EnterpriseAlertRoutes.revision eq existing.revision)
        }
        if (deleted == 0) throw IncidentCommandConflictException(STALE_REVISION)
        return AlertRouteCommandResult(emptyList(), replayed = false)
    }

    private fun reorder(command: ReorderAlertRoutesCommand): AlertRouteCommandResult {
        val organizationId = command.actor.organizationId
        val current = AlertRouteReader.load(organizationId)
        val orderedRouteIds = command.orderedRoutes.map { it.routeId }
        require(orderedRouteIds.toSet() == current.map { it.resourceId }.toSet()) {
            "Reorder must list every alert route in the organization exactly once"
        }
        command.orderedRoutes.forEach { entry ->
            val route = current.first { it.resourceId == entry.routeId }
            requireCurrentRevision(entry.expectedRevision, route.revision)
        }
        policy.requireQuota(command, enabledRouteCount(organizationId))
        if (current.map { it.resourceId } == orderedRouteIds) {
            return AlertRouteCommandResult(current, replayed = false)
        }
        val now = Clock.System.now()
        val temporaryStart = (current.maxOfOrNull { it.position } ?: -1) + 1
        orderedRouteIds.forEachIndexed { index, resourceId ->
            val route = current.first { it.resourceId == resourceId }
            val changed =
                EnterpriseAlertRoutes.update({
                    (EnterpriseAlertRoutes.id eq route.id) and (EnterpriseAlertRoutes.revision eq route.revision)
                }) {
                    it[position] = temporaryStart + index
                }
            if (changed == 0) throw IncidentCommandConflictException(STALE_REVISION)
        }
        orderedRouteIds.forEachIndexed { index, resourceId ->
            val route = current.first { it.resourceId == resourceId }
            val nextRevision = route.revision + 1
            val changed =
                EnterpriseAlertRoutes.update({
                    (EnterpriseAlertRoutes.id eq route.id) and (EnterpriseAlertRoutes.revision eq route.revision)
                }) {
                    it[position] = index
                    it[revision] = nextRevision
                    it[updatedBy] = command.actor.userId
                    it[updatedAt] = now
                }
            if (changed == 0) throw IncidentCommandConflictException(STALE_REVISION)
            val reordered = requireLoaded(organizationId, resourceId)
            recordRevision(
                command,
                AlertRouteRevisionEntry(route.id, resourceId, nextRevision, AlertRouteChangeType.REORDERED, now),
                reordered,
            )
        }
        return AlertRouteCommandResult(AlertRouteReader.load(organizationId), replayed = false)
    }

    private fun replayResult(command: AlertRouteCommand): AlertRouteCommandResult? {
        val organizationId = command.actor.organizationId
        val row =
            EnterpriseAlertRouteCommands
                .selectAll()
                .where {
                    (EnterpriseAlertRouteCommands.organizationId eq organizationId) and
                        (EnterpriseAlertRouteCommands.commandKey eq command.commandKey)
                }.limit(1)
                .firstOrNull()
                ?: return null
        val fingerprint = AlertRouteFingerprint.of(command)
        if (row[EnterpriseAlertRouteCommands.requestFingerprint] != fingerprint) {
            throw IncidentCommandConflictException("Alert route command key was already used for another request")
        }
        val routes =
            row[EnterpriseAlertRouteCommands.resultRouteIds]
                .map(Uuid::parse)
                .mapNotNull { AlertRouteReader.loadOne(organizationId, it) }
        return AlertRouteCommandResult(routes, replayed = true)
    }

    private fun recordCommand(command: AlertRouteCommand, resultRouteIds: List<Uuid>) {
        EnterpriseAlertRouteCommands.insert {
            it[EnterpriseAlertRouteCommands.organizationId] = command.actor.organizationId
            it[EnterpriseAlertRouteCommands.commandKey] = command.commandKey
            it[EnterpriseAlertRouteCommands.commandType] = command.type.wire
            it[EnterpriseAlertRouteCommands.requestFingerprint] = AlertRouteFingerprint.of(command)
            it[EnterpriseAlertRouteCommands.resultRouteIds] = resultRouteIds.map(Uuid::toString)
            it[EnterpriseAlertRouteCommands.createdAt] = Clock.System.now()
        }
    }

    private fun lockOrganization(organizationId: Int) {
        Organizations
            .selectAll()
            .where { Organizations.id eq organizationId }
            .forUpdate()
            .single()
    }

    private fun requireAdministrator(actor: AlertRouteActor) {
        val role =
            Memberships
                .selectAll()
                .where {
                    (Memberships.organization_id eq actor.organizationId) and
                        (Memberships.user_id eq actor.userId)
                }.limit(1)
                .firstOrNull()
                ?.get(Memberships.role)
                ?: throw IncidentCommandDeniedException("Actor is not a member of the alert route organization")
        if (OrgRole.fromString(role).level < OrgRole.ADMIN.level) {
            throw IncidentCommandDeniedException("Alert routes can only be managed by organization administrators")
        }
    }

    private fun requireCurrentRevision(expectedRevision: Int, currentRevision: Int) {
        if (expectedRevision != currentRevision) {
            throw IncidentCommandConflictException(STALE_REVISION)
        }
    }

    private fun requireAvailableKey(organizationId: Int, key: String, currentRouteId: Int?) {
        val conflict =
            EnterpriseAlertRoutes
                .selectAll()
                .where {
                    (EnterpriseAlertRoutes.organizationId eq organizationId) and
                        (EnterpriseAlertRoutes.routeKey eq key)
                }.limit(1)
                .firstOrNull()
                ?.get(EnterpriseAlertRoutes.id)
                ?.value
        if (conflict != null && conflict != currentRouteId) {
            throw IncidentCommandConflictException("Alert route key $key is already in use")
        }
    }

    private fun requireLoaded(organizationId: Int, routeResourceId: Uuid): AlertRouteDefinition =
        AlertRouteReader.loadOne(organizationId, routeResourceId)
            ?: throw IncidentCommandNotFoundException(ROUTE_NOT_FOUND)

    private fun nextPosition(organizationId: Int): Int =
        EnterpriseAlertRoutes
            .selectAll()
            .where { EnterpriseAlertRoutes.organizationId eq organizationId }
            .orderBy(EnterpriseAlertRoutes.position to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(EnterpriseAlertRoutes.position)
            ?.plus(1)
            ?: 0

    private fun enabledRouteCount(organizationId: Int): Long =
        EnterpriseAlertRoutes
            .selectAll()
            .where {
                (EnterpriseAlertRoutes.organizationId eq organizationId) and
                    (EnterpriseAlertRoutes.enabled eq true)
            }.count()

    private fun clearSpecification(routeId: Int) {
        val groupIds =
            EnterpriseAlertRouteConditionGroups
                .selectAll()
                .where { EnterpriseAlertRouteConditionGroups.routeId eq routeId }
                .map { it[EnterpriseAlertRouteConditionGroups.id].value }
        if (groupIds.isNotEmpty()) {
            EnterpriseAlertRouteConditions.deleteWhere { groupId inList groupIds }
            EnterpriseAlertRouteConditionGroups.deleteWhere {
                EnterpriseAlertRouteConditionGroups.routeId eq routeId
            }
        }
        EnterpriseAlertRouteTargets.deleteWhere { EnterpriseAlertRouteTargets.routeId eq routeId }
        EnterpriseAlertRouteActions.deleteWhere { EnterpriseAlertRouteActions.routeId eq routeId }
    }

    private fun writeSpecification(
        organizationId: Int,
        routeId: Int,
        specification: AlertRouteSpecification,
        now: Instant,
    ) {
        EnterpriseAlertRouteActions.insert {
            it[resourceId] = Uuid.random()
            it[EnterpriseAlertRouteActions.organizationId] = organizationId
            it[EnterpriseAlertRouteActions.routeId] = routeId
            it[pagingMode] = specification.paging.mode.wire
            it[groupingBehavior] = specification.grouping.behavior.wire
            it[groupingWindowKind] = specification.grouping.windowKind.wire
            it[groupingWindowSeconds] = specification.grouping.windowSeconds
            it[groupingKeys] = specification.grouping.keys
            it[incidentCreationEnabled] = specification.incident.create
            it[incidentCreationMode] = specification.incident.mode.wire
            it[incidentTitleTemplate] = specification.incident.titleTemplate?.trim()?.takeIf(String::isNotEmpty)
            it[incidentSummaryTemplate] = specification.incident.summaryTemplate?.trim()?.takeIf(String::isNotEmpty)
            it[incidentSeverity] = specification.incident.severity
            it[incidentType] = specification.incident.incidentType?.trim()?.takeIf(String::isNotEmpty)
            it[recoveryCancelEscalations] = specification.recovery.cancelEscalations
            it[recoveryDeclineUnacceptedTriage] = specification.recovery.declineUnacceptedTriage
            it[recoveryGraceSeconds] = specification.recovery.graceSeconds
            it[createdAt] = now
            it[updatedAt] = now
        }
        writeTargets(organizationId, routeId, specification, now)
        writeConditions(organizationId, routeId, specification, now)
    }

    private fun writeTargets(
        organizationId: Int,
        routeId: Int,
        specification: AlertRouteSpecification,
        now: Instant,
    ) {
        specification.paging.targets.forEachIndexed { index, target ->
            EnterpriseAlertRouteTargets.insert {
                it[resourceId] = Uuid.random()
                it[EnterpriseAlertRouteTargets.organizationId] = organizationId
                it[EnterpriseAlertRouteTargets.routeId] = routeId
                it[position] = index
                it[targetKind] = target.kind.wire
                it[escalationPolicyId] = resolveEscalationPolicyId(organizationId, target)
                it[teamId] = resolveTeamId(organizationId, target)
                it[ownershipSource] = target.ownershipSource?.wire
                it[createdAt] = now
            }
        }
    }

    private fun writeConditions(
        organizationId: Int,
        routeId: Int,
        specification: AlertRouteSpecification,
        now: Instant,
    ) {
        specification.conditionGroups.forEachIndexed { groupIndex, group ->
            val groupId =
                EnterpriseAlertRouteConditionGroups.insertAndGetId {
                    it[resourceId] = Uuid.random()
                    it[EnterpriseAlertRouteConditionGroups.organizationId] = organizationId
                    it[EnterpriseAlertRouteConditionGroups.routeId] = routeId
                    it[position] = groupIndex
                    it[createdAt] = now
                }.value
            group.conditions.forEachIndexed { conditionIndex, condition ->
                EnterpriseAlertRouteConditions.insert {
                    it[resourceId] = Uuid.random()
                    it[EnterpriseAlertRouteConditions.organizationId] = organizationId
                    it[EnterpriseAlertRouteConditions.groupId] = groupId
                    it[position] = conditionIndex
                    it[contextField] = condition.field.name
                    it[metadataKey] = condition.metadataKey?.trim()
                    it[operator] = condition.operator.wire
                    it[comparisonValues] = condition.values
                    it[createdAt] = now
                }
            }
        }
    }

    private fun resolveEscalationPolicyId(organizationId: Int, target: AlertRouteTargetInput): Int? {
        if (target.kind != AlertRouteTargetKind.ESCALATION_POLICY) return null
        val resourceId = requireNotNull(target.escalationPolicyId) { "Escalation policy target is missing an id" }
        return EscalationPolicies
            .selectAll()
            .where {
                (EscalationPolicies.organizationId eq organizationId) and
                    (EscalationPolicies.resourceId eq resourceId)
            }.limit(1)
            .firstOrNull()
            ?.get(EscalationPolicies.id)
            ?.value
            ?: throw IllegalArgumentException("Unknown escalation policy: $resourceId")
    }

    private fun resolveTeamId(organizationId: Int, target: AlertRouteTargetInput): Int? {
        if (target.kind != AlertRouteTargetKind.TEAM) return null
        val resourceId = requireNotNull(target.teamId) { "Team target is missing an id" }
        return OrganizationTeams
            .selectAll()
            .where {
                (OrganizationTeams.organizationId eq organizationId) and
                    (OrganizationTeams.resourceId eq resourceId)
            }.limit(1)
            .firstOrNull()
            ?.get(OrganizationTeams.id)
            ?.value
            ?: throw IllegalArgumentException("Unknown team: $resourceId")
    }

    private fun recordRevision(
        command: AlertRouteCommand,
        entry: AlertRouteRevisionEntry,
        route: AlertRouteDefinition,
    ) {
        EnterpriseAlertRouteRevisions.insert {
            it[resourceId] = Uuid.random()
            it[EnterpriseAlertRouteRevisions.organizationId] = command.actor.organizationId
            it[routeId] = entry.routeId
            it[routeResourceId] = entry.routeResourceId
            it[revision] = entry.revision
            it[changeType] = entry.changeType.wire
            it[actorUserId] = command.actor.userId
            it[commandKey] = command.commandKey
            it[requestFingerprint] = AlertRouteFingerprint.of(command)
            it[snapshot] = AlertRouteSnapshot.of(command, route, entry.changeType)
            it[createdAt] = entry.at
        }
    }

    private data class AlertRouteRevisionEntry(
        val routeId: Int,
        val routeResourceId: Uuid,
        val revision: Int,
        val changeType: AlertRouteChangeType,
        val at: Instant,
    )

    private companion object {
        const val INITIAL_REVISION = 1
        const val UNIQUE_VIOLATION_SQL_STATE = "23505"
        const val ROUTE_NOT_FOUND = "Alert route not found"
        const val STALE_REVISION = "Alert route revision is stale"
    }
}
