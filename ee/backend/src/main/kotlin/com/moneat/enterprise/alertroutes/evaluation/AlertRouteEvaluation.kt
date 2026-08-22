// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.evaluation

import com.moneat.enterprise.alertroutes.models.AlertRouteConditionOperator
import com.moneat.enterprise.alertroutes.models.AlertRouteContextField
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingBehavior
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingWindowKind
import com.moneat.enterprise.alertroutes.models.AlertRouteIncidentMode
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Why one condition matched or did not match. */
data class AlertRouteConditionExplanation(
    val field: AlertRouteContextField,
    val metadataKey: String?,
    val operator: AlertRouteConditionOperator,
    val values: List<String>,
    val actualValue: String?,
    val matched: Boolean,
)

/** Explanation for one ORed condition group. */
data class AlertRouteGroupExplanation(
    val position: Int,
    val matched: Boolean,
    val conditions: List<AlertRouteConditionExplanation>,
)

/** Per-route outcome of an evaluation, including routes skipped after a earlier route matched. */
data class AlertRouteEvaluation(
    val routeId: Uuid,
    val routeKey: String,
    val routeName: String,
    val position: Int,
    val enabled: Boolean,
    val matched: Boolean,
    val selected: Boolean,
    val reason: AlertRouteEvaluationReason,
    val groups: List<AlertRouteGroupExplanation>,
)

enum class AlertRouteEvaluationReason(val wire: String) {
    SELECTED("SELECTED"),
    NO_GROUP_MATCHED("NO_GROUP_MATCHED"),
    ROUTE_DISABLED("ROUTE_DISABLED"),
    EARLIER_ROUTE_SELECTED("EARLIER_ROUTE_SELECTED"),
}

/** An escalation policy resolved from a route target, with its public resource id when known. */
data class AlertRouteEscalationPolicyRef(
    val id: Int,
    val resourceId: Uuid?,
)

/** A team resolved from a route target, with its public resource id when known. */
data class AlertRouteTeamRef(
    val id: Int,
    val resourceId: Uuid?,
)

/** Paging targets resolved for the selected route. */
data class AlertRoutePagingResolution(
    val mode: AlertRoutePagingMode,
    val escalationPolicies: List<AlertRouteEscalationPolicyRef>,
    val teams: List<AlertRouteTeamRef>,
    val unresolvedTargets: List<String>,
)

/** Grouping plan resolved for the selected route. */
data class AlertRouteGroupingResolution(
    val behavior: AlertRouteGroupingBehavior,
    val automatic: Boolean,
    val groupKey: String,
    val keys: List<String>,
    val unresolvedKeys: List<String>,
    val windowKind: AlertRouteGroupingWindowKind,
    val windowSeconds: Int,
) {
    /** When the grouping window closes for a group that started at [groupStartedAt]. */
    fun windowClosesAt(groupStartedAt: Instant, lastAlertAt: Instant): Instant =
        when (windowKind) {
            AlertRouteGroupingWindowKind.FIXED -> groupStartedAt + windowSeconds.seconds
            AlertRouteGroupingWindowKind.ROLLING -> lastAlertAt + windowSeconds.seconds
        }
}

/** Incident-creation plan resolved for the selected route, with templates already rendered. */
data class AlertRouteIncidentResolution(
    val create: Boolean,
    val mode: AlertRouteIncidentMode,
    val title: String?,
    val summary: String?,
    val severity: String?,
    val incidentType: String?,
    val unresolvedReferences: List<String>,
)

/** Recovery plan resolved for the selected route. */
data class AlertRouteRecoveryResolution(
    val cancelEscalations: Boolean,
    val declineUnacceptedTriage: Boolean,
    val graceSeconds: Int,
)

/** Everything the selected route decides for one alert. */
data class AlertRouteDecision(
    val routeId: Uuid,
    val routeKey: String,
    val routeName: String,
    val position: Int,
    val revision: Int,
    val paging: AlertRoutePagingResolution,
    val grouping: AlertRouteGroupingResolution,
    val incident: AlertRouteIncidentResolution,
    val recovery: AlertRouteRecoveryResolution,
)

/** Result of evaluating every route in an organization against one alert context. */
data class AlertRouteEvaluationResult(
    val decision: AlertRouteDecision?,
    val evaluations: List<AlertRouteEvaluation>,
    val droppedMetadataKeys: List<String>,
)
