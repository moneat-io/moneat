// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.enterprise.alertroutes.models.AlertRouteChangeType
import com.moneat.enterprise.alertroutes.models.AlertRouteConditionDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteConditionGroupDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteConditionOperator
import com.moneat.enterprise.alertroutes.models.AlertRouteContextField
import com.moneat.enterprise.alertroutes.models.AlertRouteDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingBehavior
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingWindowKind
import com.moneat.enterprise.alertroutes.models.AlertRouteIncidentDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteIncidentMode
import com.moneat.enterprise.alertroutes.models.AlertRouteOwnershipSource
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingDefinition
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import com.moneat.enterprise.alertroutes.models.AlertRouteRecoveryDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteRevisionRecord
import com.moneat.enterprise.alertroutes.models.AlertRouteTargetDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteTargetKind
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteActions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteConditionGroups
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteConditions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteRevisions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteTargets
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRoutes
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.OrganizationTeams
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Loads alert routes and their audit history.
 *
 * Every function must run inside an existing Exposed transaction; callers own the boundary.
 */
internal object AlertRouteReader {
    fun load(organizationId: Int, routeResourceId: Uuid? = null): List<AlertRouteDefinition> {
        val routeRows =
            EnterpriseAlertRoutes
                .selectAll()
                .where {
                    if (routeResourceId == null) {
                        EnterpriseAlertRoutes.organizationId eq organizationId
                    } else {
                        (EnterpriseAlertRoutes.organizationId eq organizationId) and
                            (EnterpriseAlertRoutes.resourceId eq routeResourceId)
                    }
                }.orderBy(
                    EnterpriseAlertRoutes.position to SortOrder.ASC,
                    EnterpriseAlertRoutes.id to SortOrder.ASC,
                ).toList()
        if (routeRows.isEmpty()) return emptyList()
        val routeIds = routeRows.map { it[EnterpriseAlertRoutes.id].value }
        val groupsByRoute = loadConditionGroups(routeIds)
        val actionsByRoute = loadActions(routeIds)
        val targetsByRoute = loadTargets(routeIds)
        return routeRows.map { row ->
            val routeId = row[EnterpriseAlertRoutes.id].value
            row.toDefinition(
                conditionGroups = groupsByRoute[routeId].orEmpty(),
                actions = actionsByRoute[routeId],
                targets = targetsByRoute[routeId].orEmpty(),
            )
        }
    }

    fun loadOne(organizationId: Int, routeResourceId: Uuid): AlertRouteDefinition? =
        load(organizationId, routeResourceId).firstOrNull()

    fun revisions(organizationId: Int, routeResourceId: Uuid): List<AlertRouteRevisionRecord> {
        val rows =
            EnterpriseAlertRouteRevisions
                .selectAll()
                .where {
                    (EnterpriseAlertRouteRevisions.organizationId eq organizationId) and
                        (EnterpriseAlertRouteRevisions.routeResourceId eq routeResourceId)
                }.orderBy(EnterpriseAlertRouteRevisions.revision to SortOrder.DESC)
                .toList()
        val actorResourceIds = actorResourceIds(rows.mapNotNull { it[EnterpriseAlertRouteRevisions.actorUserId] })
        return rows.map { row ->
            AlertRouteRevisionRecord(
                id = row[EnterpriseAlertRouteRevisions.resourceId],
                routeId = row[EnterpriseAlertRouteRevisions.routeResourceId],
                revision = row[EnterpriseAlertRouteRevisions.revision],
                changeType = AlertRouteChangeType.valueOf(row[EnterpriseAlertRouteRevisions.changeType]),
                actorId = row[EnterpriseAlertRouteRevisions.actorUserId]?.let(actorResourceIds::get),
                commandKey = row[EnterpriseAlertRouteRevisions.commandKey],
                snapshot = row[EnterpriseAlertRouteRevisions.snapshot],
                createdAt = row[EnterpriseAlertRouteRevisions.createdAt],
            )
        }
    }

    private fun actorResourceIds(userIds: List<Int>): Map<Int, Uuid> {
        if (userIds.isEmpty()) return emptyMap()
        return Users
            .selectAll()
            .where { Users.id inList userIds.distinct() }
            .associate { it[Users.id] to it[Users.resource_id] }
    }

    private fun loadConditionGroups(routeIds: List<Int>): Map<Int, List<AlertRouteConditionGroupDefinition>> {
        val groupRows =
            EnterpriseAlertRouteConditionGroups
                .selectAll()
                .where { EnterpriseAlertRouteConditionGroups.routeId inList routeIds }
                .orderBy(EnterpriseAlertRouteConditionGroups.position to SortOrder.ASC)
                .toList()
        if (groupRows.isEmpty()) return emptyMap()
        val groupIds = groupRows.map { it[EnterpriseAlertRouteConditionGroups.id].value }
        val conditionsByGroup =
            EnterpriseAlertRouteConditions
                .selectAll()
                .where { EnterpriseAlertRouteConditions.groupId inList groupIds }
                .orderBy(EnterpriseAlertRouteConditions.position to SortOrder.ASC)
                .groupBy({ it[EnterpriseAlertRouteConditions.groupId] }) { row ->
                    AlertRouteConditionDefinition(
                        field = AlertRouteContextField.valueOf(row[EnterpriseAlertRouteConditions.contextField]),
                        operator =
                            requireNotNull(
                                AlertRouteConditionOperator.fromWire(row[EnterpriseAlertRouteConditions.operator]),
                            ) { "Unknown stored alert route condition operator" },
                        values = row[EnterpriseAlertRouteConditions.comparisonValues],
                        metadataKey = row[EnterpriseAlertRouteConditions.metadataKey],
                    )
                }
        return groupRows.groupBy({ it[EnterpriseAlertRouteConditionGroups.routeId] }) { row ->
            AlertRouteConditionGroupDefinition(
                conditions = conditionsByGroup[row[EnterpriseAlertRouteConditionGroups.id].value].orEmpty(),
            )
        }
    }

    private fun loadActions(routeIds: List<Int>): Map<Int, ResultRow> =
        EnterpriseAlertRouteActions
            .selectAll()
            .where { EnterpriseAlertRouteActions.routeId inList routeIds }
            .associateBy { it[EnterpriseAlertRouteActions.routeId] }

    private fun loadTargets(routeIds: List<Int>): Map<Int, List<AlertRouteTargetDefinition>> {
        val rows =
            EnterpriseAlertRouteTargets
                .selectAll()
                .where { EnterpriseAlertRouteTargets.routeId inList routeIds }
                .orderBy(EnterpriseAlertRouteTargets.position to SortOrder.ASC)
                .toList()
        if (rows.isEmpty()) return emptyMap()
        val policyResourceIds =
            escalationPolicyResourceIds(rows.mapNotNull { it[EnterpriseAlertRouteTargets.escalationPolicyId] })
        val teamResourceIds = teamResourceIds(rows.mapNotNull { it[EnterpriseAlertRouteTargets.teamId] })
        return rows.groupBy({ it[EnterpriseAlertRouteTargets.routeId] }) { row ->
            val policyId = row[EnterpriseAlertRouteTargets.escalationPolicyId]
            val teamId = row[EnterpriseAlertRouteTargets.teamId]
            AlertRouteTargetDefinition(
                kind = AlertRouteTargetKind.valueOf(row[EnterpriseAlertRouteTargets.targetKind]),
                escalationPolicyId = policyId,
                escalationPolicyResourceId = policyId?.let(policyResourceIds::get),
                teamId = teamId,
                teamResourceId = teamId?.let(teamResourceIds::get),
                ownershipSource =
                    row[EnterpriseAlertRouteTargets.ownershipSource]?.let(AlertRouteOwnershipSource::valueOf),
            )
        }
    }

    private fun escalationPolicyResourceIds(policyIds: List<Int>): Map<Int, Uuid> {
        if (policyIds.isEmpty()) return emptyMap()
        return EscalationPolicies
            .selectAll()
            .where { EscalationPolicies.id inList policyIds.distinct() }
            .associate { it[EscalationPolicies.id].value to it[EscalationPolicies.resourceId] }
    }

    private fun teamResourceIds(teamIds: List<Int>): Map<Int, Uuid> {
        if (teamIds.isEmpty()) return emptyMap()
        return OrganizationTeams
            .selectAll()
            .where { OrganizationTeams.id inList teamIds.distinct() }
            .associate { it[OrganizationTeams.id].value to it[OrganizationTeams.resourceId] }
    }

    private fun ResultRow.toDefinition(
        conditionGroups: List<AlertRouteConditionGroupDefinition>,
        actions: ResultRow?,
        targets: List<AlertRouteTargetDefinition>,
    ): AlertRouteDefinition =
        AlertRouteDefinition(
            id = this[EnterpriseAlertRoutes.id].value,
            resourceId = this[EnterpriseAlertRoutes.resourceId],
            organizationId = this[EnterpriseAlertRoutes.organizationId],
            key = this[EnterpriseAlertRoutes.routeKey],
            name = this[EnterpriseAlertRoutes.name],
            description = this[EnterpriseAlertRoutes.description],
            enabled = this[EnterpriseAlertRoutes.enabled],
            position = this[EnterpriseAlertRoutes.position],
            revision = this[EnterpriseAlertRoutes.revision],
            conditionGroups = conditionGroups,
            paging =
                AlertRoutePagingDefinition(
                    mode =
                        actions
                            ?.get(EnterpriseAlertRouteActions.pagingMode)
                            ?.let(AlertRoutePagingMode::valueOf)
                            ?: AlertRoutePagingMode.NONE,
                    targets = targets,
                ),
            grouping = actions.toGroupingDefinition(),
            incident = actions.toIncidentDefinition(),
            recovery = actions.toRecoveryDefinition(),
            createdAt = this[EnterpriseAlertRoutes.createdAt],
            updatedAt = this[EnterpriseAlertRoutes.updatedAt],
        )

    private fun ResultRow?.toGroupingDefinition(): AlertRouteGroupingDefinition =
        AlertRouteGroupingDefinition(
            behavior =
                this
                    ?.get(EnterpriseAlertRouteActions.groupingBehavior)
                    ?.let(AlertRouteGroupingBehavior::valueOf)
                    ?: AlertRouteGroupingBehavior.DEFAULT,
            keys = this?.get(EnterpriseAlertRouteActions.groupingKeys).orEmpty(),
            windowKind =
                this
                    ?.get(EnterpriseAlertRouteActions.groupingWindowKind)
                    ?.let(AlertRouteGroupingWindowKind::valueOf)
                    ?: AlertRouteGroupingWindowKind.ROLLING,
            windowSeconds =
                this?.get(EnterpriseAlertRouteActions.groupingWindowSeconds) ?: DEFAULT_GROUPING_WINDOW_SECONDS,
        )

    private fun ResultRow?.toIncidentDefinition(): AlertRouteIncidentDefinition =
        AlertRouteIncidentDefinition(
            create = this?.get(EnterpriseAlertRouteActions.incidentCreationEnabled) == true,
            mode =
                this
                    ?.get(EnterpriseAlertRouteActions.incidentCreationMode)
                    ?.let(AlertRouteIncidentMode::valueOf)
                    ?: AlertRouteIncidentMode.TRIAGE,
            titleTemplate = this?.get(EnterpriseAlertRouteActions.incidentTitleTemplate),
            summaryTemplate = this?.get(EnterpriseAlertRouteActions.incidentSummaryTemplate),
            severity = this?.get(EnterpriseAlertRouteActions.incidentSeverity),
            incidentType = this?.get(EnterpriseAlertRouteActions.incidentType),
        )

    private fun ResultRow?.toRecoveryDefinition(): AlertRouteRecoveryDefinition =
        AlertRouteRecoveryDefinition(
            cancelEscalations = this?.get(EnterpriseAlertRouteActions.recoveryCancelEscalations) == true,
            declineUnacceptedTriage =
                this?.get(EnterpriseAlertRouteActions.recoveryDeclineUnacceptedTriage) == true,
            graceSeconds = this?.get(EnterpriseAlertRouteActions.recoveryGraceSeconds) ?: 0,
        )

    private const val DEFAULT_GROUPING_WINDOW_SECONDS = 900
}
