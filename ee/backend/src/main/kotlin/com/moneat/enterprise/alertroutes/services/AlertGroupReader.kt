// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.enterprise.alertroutes.models.AlertGroupDecisionType
import com.moneat.enterprise.alertroutes.models.AlertGroupMemberState
import com.moneat.enterprise.alertroutes.models.AlertGroupPagingState
import com.moneat.enterprise.alertroutes.models.AlertGroupState
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingBehavior
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingWindowKind
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupDecisions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupMembers
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroups
import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object AlertGroupReader {
    fun list(organizationId: Int, limit: Int, offset: Long): List<AlertGroupRecord> = transaction {
        val rows = EnterpriseAlertGroups
            .selectAll()
            .where { EnterpriseAlertGroups.organizationId eq organizationId }
            .orderBy(EnterpriseAlertGroups.updatedAt to SortOrder.DESC)
            .limit(limit)
            .offset(offset)
            .toList()
        val incidentIds = rows.flatMap { row ->
            listOfNotNull(row[EnterpriseAlertGroups.incidentId], row[EnterpriseAlertGroups.candidateIncidentId])
        }.toSet()
        val incidents = incidentResourceIds(organizationId, incidentIds)
        rows.map { row -> groupRecord(row, incidents, emptyList(), emptyList()) }
    }

    fun get(organizationId: Int, groupId: Uuid): AlertGroupRecord = transaction {
        val row = requireGroup(organizationId, groupId)
        load(organizationId, row)
    }

    internal fun requireGroup(organizationId: Int, groupId: Uuid, lock: Boolean = false): ResultRow {
        val query = EnterpriseAlertGroups.selectAll().where {
            (EnterpriseAlertGroups.organizationId eq organizationId) and
                (EnterpriseAlertGroups.resourceId eq groupId)
        }
        if (lock) query.forUpdate()
        return query.singleOrNull() ?: throw IncidentCommandNotFoundException("Alert group not found")
    }

    internal fun load(organizationId: Int, row: ResultRow): AlertGroupRecord {
        val groupId = row[EnterpriseAlertGroups.id].value
        val members =
            (EnterpriseAlertGroupMembers innerJoin AlertEpisodes)
                .selectAll()
                .where {
                    (EnterpriseAlertGroupMembers.organizationId eq organizationId) and
                        (EnterpriseAlertGroupMembers.groupId eq groupId)
                }.orderBy(EnterpriseAlertGroupMembers.firstJoinedAt to SortOrder.ASC)
                .map {
                    AlertGroupMemberRecord(
                        id = it[EnterpriseAlertGroupMembers.resourceId],
                        episodeId = it[AlertEpisodes.resourceId],
                        state = AlertGroupMemberState.valueOf(it[EnterpriseAlertGroupMembers.state]),
                        version = it[EnterpriseAlertGroupMembers.version],
                        pagingState = AlertGroupPagingState.valueOf(it[EnterpriseAlertGroupMembers.pagingState]),
                        firstJoinedAt = it[EnterpriseAlertGroupMembers.firstJoinedAt],
                        lastSeenAt = it[EnterpriseAlertGroupMembers.lastSeenAt],
                        resolvedAt = it[EnterpriseAlertGroupMembers.resolvedAt],
                    )
                }
        val decisionRows =
            EnterpriseAlertGroupDecisions
                .selectAll()
                .where {
                    (EnterpriseAlertGroupDecisions.organizationId eq organizationId) and
                        (EnterpriseAlertGroupDecisions.groupId eq groupId)
                }.orderBy(
                    EnterpriseAlertGroupDecisions.createdAt to SortOrder.ASC,
                    EnterpriseAlertGroupDecisions.id to SortOrder.ASC,
                )
                .toList()
        val decisions = decisionRows.map(
            decisionMapper(
                episodeResourceIds(organizationId, decisionRows.mapNotNull {
                    it[EnterpriseAlertGroupDecisions.alertEpisodeId]
                }.toSet()),
                incidentResourceIds(organizationId, decisionRows.mapNotNull {
                    it[EnterpriseAlertGroupDecisions.incidentId]
                }.toSet()),
                userResourceIds(decisionRows.mapNotNull { it[EnterpriseAlertGroupDecisions.actorUserId] }.toSet()),
            ),
        )
        val incidentIds = setOfNotNull(
            row[EnterpriseAlertGroups.incidentId],
            row[EnterpriseAlertGroups.candidateIncidentId],
        )
        return groupRecord(row, incidentResourceIds(organizationId, incidentIds), members, decisions)
    }

    private fun groupRecord(
        row: ResultRow,
        incidentIds: Map<Int, Uuid>,
        members: List<AlertGroupMemberRecord>,
        decisions: List<AlertGroupDecisionRecord>,
    ): AlertGroupRecord = AlertGroupRecord(
            id = row[EnterpriseAlertGroups.resourceId],
            routeId = row[EnterpriseAlertGroups.routeResourceId],
            routeRevision = row[EnterpriseAlertGroups.routeRevision],
            identityHash = row[EnterpriseAlertGroups.identityHash],
            groupingTuple = row[EnterpriseAlertGroups.groupingTuple],
            singleton = row[EnterpriseAlertGroups.singletonEpisodeId] != null,
            behavior = AlertRouteGroupingBehavior.valueOf(row[EnterpriseAlertGroups.groupingBehavior]),
            windowKind = AlertRouteGroupingWindowKind.valueOf(row[EnterpriseAlertGroups.windowKind]),
            windowSeconds = row[EnterpriseAlertGroups.windowSeconds],
            openedAt = row[EnterpriseAlertGroups.openedAt],
            lastAlertAt = row[EnterpriseAlertGroups.lastAlertAt],
            closesAt = row[EnterpriseAlertGroups.closesAt],
            state = AlertGroupState.valueOf(row[EnterpriseAlertGroups.state]),
            version = row[EnterpriseAlertGroups.version],
            incidentId = row[EnterpriseAlertGroups.incidentId]?.let(incidentIds::get),
            candidateIncidentId = row[EnterpriseAlertGroups.candidateIncidentId]?.let(incidentIds::get),
            routeSnapshot = row[EnterpriseAlertGroups.routeSnapshot],
            incidentTemplateSnapshot = row[EnterpriseAlertGroups.incidentTemplateSnapshot],
            pagingMode = AlertRoutePagingMode.valueOf(row[EnterpriseAlertGroups.pagingMode]),
            pagingState = AlertGroupPagingState.valueOf(row[EnterpriseAlertGroups.pagingState]),
            members = members,
            decisions = decisions,
            createdAt = row[EnterpriseAlertGroups.createdAt],
            updatedAt = row[EnterpriseAlertGroups.updatedAt],
        )

    private fun decisionMapper(
        episodes: Map<Int, Uuid>,
        incidents: Map<Int, Uuid>,
        users: Map<Int, Uuid>,
    ): (ResultRow) -> AlertGroupDecisionRecord = { row ->
        AlertGroupDecisionRecord(
            id = row[EnterpriseAlertGroupDecisions.resourceId],
            type = AlertGroupDecisionType.valueOf(row[EnterpriseAlertGroupDecisions.decisionType]),
            episodeId = row[EnterpriseAlertGroupDecisions.alertEpisodeId]?.let(episodes::get),
            incidentId = row[EnterpriseAlertGroupDecisions.incidentId]?.let(incidents::get),
            actorId = row[EnterpriseAlertGroupDecisions.actorUserId]?.let(users::get),
            commandKey = row[EnterpriseAlertGroupDecisions.commandKey],
            details = row[EnterpriseAlertGroupDecisions.details],
            createdAt = row[EnterpriseAlertGroupDecisions.createdAt],
        )
    }

    private fun episodeResourceIds(organizationId: Int, ids: Set<Int>): Map<Int, Uuid> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            AlertEpisodes
                .selectAll()
                .where { (AlertEpisodes.organizationId eq organizationId) and (AlertEpisodes.id inList ids) }
                .associate { it[AlertEpisodes.id].value to it[AlertEpisodes.resourceId] }
        }

    private fun incidentResourceIds(organizationId: Int, ids: Set<Int>): Map<Int, Uuid> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            OnCallIncidents
                .selectAll()
                .where { (OnCallIncidents.organizationId eq organizationId) and (OnCallIncidents.id inList ids) }
                .associate { it[OnCallIncidents.id].value to it[OnCallIncidents.resourceId] }
        }

    private fun userResourceIds(ids: Set<Int>): Map<Int, Uuid> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            Users
                .selectAll()
                .where { Users.id inList ids }
                .associate { it[Users.id] to it[Users.resource_id] }
        }
}
