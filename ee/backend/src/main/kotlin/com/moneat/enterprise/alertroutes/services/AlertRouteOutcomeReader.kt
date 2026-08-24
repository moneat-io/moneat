// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.enterprise.alertroutes.models.AlertGroupEscalationState
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupEscalations
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroups
import com.moneat.enterprise.oncall.models.AlertRouteActionSummary
import com.moneat.enterprise.oncall.models.AlertRouteOutcomeSummary
import com.moneat.enterprise.oncall.models.OnCallIncidents
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Read-only route execution evidence attached to a concrete on-call alert. */
object AlertRouteOutcomeReader {
    fun findForOnCallAlert(organizationId: Int, onCallAlertId: Int): AlertRouteOutcomeSummary? = transaction {
        val escalation = EnterpriseAlertGroupEscalations
            .selectAll()
            .where {
                (EnterpriseAlertGroupEscalations.organizationId eq organizationId) and
                    (EnterpriseAlertGroupEscalations.onCallAlertId eq onCallAlertId)
            }
            .orderBy(EnterpriseAlertGroupEscalations.updatedAt to SortOrder.DESC)
            .firstOrNull() ?: return@transaction null
        val group = EnterpriseAlertGroups.selectAll().where {
            (EnterpriseAlertGroups.organizationId eq organizationId) and
                (EnterpriseAlertGroups.id eq escalation[EnterpriseAlertGroupEscalations.groupId])
        }.singleOrNull() ?: return@transaction null
        val incidentId = group[EnterpriseAlertGroups.incidentId]?.let { internalId ->
            OnCallIncidents.selectAll().where {
                (OnCallIncidents.organizationId eq organizationId) and (OnCallIncidents.id eq internalId)
            }.singleOrNull()?.get(OnCallIncidents.resourceId)?.toString()
        }
        val pagingMode = AlertRoutePagingMode.valueOf(group[EnterpriseAlertGroups.pagingMode])
        val escalations = EnterpriseAlertGroupEscalations.selectAll().where {
            (EnterpriseAlertGroupEscalations.organizationId eq organizationId) and
                (EnterpriseAlertGroupEscalations.groupId eq group[EnterpriseAlertGroups.id].value)
        }.toList()
        val paging = when {
            pagingMode == AlertRoutePagingMode.NONE ->
                AlertRouteActionSummary("SKIPPED", "Paging is disabled for this route")
            escalations.any { it[EnterpriseAlertGroupEscalations.state] == AlertGroupEscalationState.FAILED.wire } ->
                AlertRouteActionSummary("FAILED", "One or more paging targets failed")
            escalations.any { it[EnterpriseAlertGroupEscalations.state] == AlertGroupEscalationState.TRIGGERED.wire } ->
                AlertRouteActionSummary("SUCCEEDED", "Paging target delivered this alert")
            else ->
                AlertRouteActionSummary("SKIPPED", "Paging has not been delivered for this alert")
        }
        val createIncident = group[EnterpriseAlertGroups.incidentTemplateSnapshot]["create"]
            ?.jsonPrimitive?.booleanOrNull == true
        val storedIncident = group[EnterpriseAlertGroups.incidentTemplateSnapshot]["execution"]
            ?.jsonObject
            ?.get("incident")
            ?.jsonObject
        val incident = if (incidentId != null) {
            AlertRouteActionSummary("SUCCEEDED", "Alert linked to the incident")
        } else if (storedIncident != null) {
            AlertRouteActionSummary(
                state = storedIncident["state"]?.jsonPrimitive?.contentOrNull ?: "FAILED",
                reason = storedIncident["reason"]?.jsonPrimitive?.contentOrNull ?: "Incident action failed",
            )
        } else if (createIncident) {
            AlertRouteActionSummary("SKIPPED", "Incident was not created for this alert")
        } else {
            AlertRouteActionSummary("SKIPPED", "Incident creation is disabled for this route")
        }
        AlertRouteOutcomeSummary(
            matchedRouteId = group[EnterpriseAlertGroups.routeResourceId].toString(),
            matchedRouteRevision = group[EnterpriseAlertGroups.routeRevision],
            groupId = group[EnterpriseAlertGroups.resourceId].toString(),
            incidentId = incidentId,
            grouping = AlertRouteActionSummary("SUCCEEDED", "Alert grouped by the selected route"),
            paging = paging,
            incident = incident,
        )
    }
}
