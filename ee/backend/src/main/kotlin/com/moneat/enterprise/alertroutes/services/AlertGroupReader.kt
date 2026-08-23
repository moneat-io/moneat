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
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object AlertGroupReader {
    fun list(organizationId: Int): List<AlertGroupRecord> = transaction {
        EnterpriseAlertGroups
            .selectAll()
            .where { EnterpriseAlertGroups.organizationId eq organizationId }
            .orderBy(EnterpriseAlertGroups.updatedAt to SortOrder.DESC)
            .map { load(organizationId, it) }
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
        val decisions =
            EnterpriseAlertGroupDecisions
                .selectAll()
                .where {
                    (EnterpriseAlertGroupDecisions.organizationId eq organizationId) and
                        (EnterpriseAlertGroupDecisions.groupId eq groupId)
                }.orderBy(
                    EnterpriseAlertGroupDecisions.createdAt to SortOrder.ASC,
                    EnterpriseAlertGroupDecisions.id to SortOrder.ASC,
                )
                .map { decisionRow(organizationId, it) }
        return AlertGroupRecord(
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
            incidentId = incidentResourceId(organizationId, row[EnterpriseAlertGroups.incidentId]),
            candidateIncidentId = incidentResourceId(organizationId, row[EnterpriseAlertGroups.candidateIncidentId]),
            routeSnapshot = row[EnterpriseAlertGroups.routeSnapshot],
            incidentTemplateSnapshot = row[EnterpriseAlertGroups.incidentTemplateSnapshot],
            pagingMode = AlertRoutePagingMode.valueOf(row[EnterpriseAlertGroups.pagingMode]),
            pagingState = AlertGroupPagingState.valueOf(row[EnterpriseAlertGroups.pagingState]),
            members = members,
            decisions = decisions,
            createdAt = row[EnterpriseAlertGroups.createdAt],
            updatedAt = row[EnterpriseAlertGroups.updatedAt],
        )
    }

    private fun decisionRow(organizationId: Int, row: ResultRow): AlertGroupDecisionRecord =
        AlertGroupDecisionRecord(
            id = row[EnterpriseAlertGroupDecisions.resourceId],
            type = AlertGroupDecisionType.valueOf(row[EnterpriseAlertGroupDecisions.decisionType]),
            episodeId = episodeResourceId(organizationId, row[EnterpriseAlertGroupDecisions.alertEpisodeId]),
            incidentId = incidentResourceId(organizationId, row[EnterpriseAlertGroupDecisions.incidentId]),
            actorId = userResourceId(row[EnterpriseAlertGroupDecisions.actorUserId]),
            commandKey = row[EnterpriseAlertGroupDecisions.commandKey],
            details = row[EnterpriseAlertGroupDecisions.details],
            createdAt = row[EnterpriseAlertGroupDecisions.createdAt],
        )

    private fun episodeResourceId(organizationId: Int, id: Int?): Uuid? =
        id?.let {
            AlertEpisodes
                .selectAll()
                .where { (AlertEpisodes.organizationId eq organizationId) and (AlertEpisodes.id eq it) }
                .singleOrNull()
                ?.get(AlertEpisodes.resourceId)
        }

    private fun incidentResourceId(organizationId: Int, id: Int?): Uuid? =
        id?.let {
            OnCallIncidents
                .selectAll()
                .where { (OnCallIncidents.organizationId eq organizationId) and (OnCallIncidents.id eq it) }
                .singleOrNull()
                ?.get(OnCallIncidents.resourceId)
        }

    private fun userResourceId(id: Int?): Uuid? =
        id?.let { Users.selectAll().where { Users.id eq it }.singleOrNull()?.get(Users.resource_id) }
}
