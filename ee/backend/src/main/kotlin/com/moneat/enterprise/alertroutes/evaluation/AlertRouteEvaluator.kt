// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.evaluation

import com.moneat.alerts.models.AlertPriority
import com.moneat.enterprise.alertroutes.context.AlertRouteContext
import com.moneat.enterprise.alertroutes.models.AlertRouteConditionDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteConditionGroupDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteConditionOperator
import com.moneat.enterprise.alertroutes.models.AlertRouteDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingBehavior
import com.moneat.enterprise.alertroutes.models.AlertRouteOwnershipSource
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import com.moneat.enterprise.alertroutes.models.AlertRouteTargetDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteTargetKind

/** Resolves the default escalation policy of a team referenced by a route target. */
fun interface AlertRouteTeamEscalationResolver {
    fun escalationPolicy(organizationId: Int, teamId: Int): AlertRouteEscalationPolicyRef?

    companion object {
        val NONE = AlertRouteTeamEscalationResolver { _, _ -> null }
    }
}

/**
 * Deterministic, ordered first-match evaluation of alert routes.
 *
 * Routes are evaluated by ascending position. Within a route, condition groups are ORed and the
 * conditions inside a group are ANDed. The first matching enabled route wins; every other route is
 * still explained so preview can show why it was skipped.
 */
class AlertRouteEvaluator(
    private val teamEscalationResolver: AlertRouteTeamEscalationResolver = AlertRouteTeamEscalationResolver.NONE,
) {
    fun evaluate(
        routes: List<AlertRouteDefinition>,
        context: AlertRouteContext,
    ): AlertRouteEvaluationResult {
        val evaluations = mutableListOf<AlertRouteEvaluation>()
        var selected: AlertRouteDefinition? = null
        routes
            .sortedWith(compareBy({ it.position }, { it.id }))
            .forEach { route ->
                val evaluation = evaluateRoute(route, context, alreadySelected = selected != null)
                if (evaluation.selected) selected = route
                evaluations.add(evaluation)
            }
        return AlertRouteEvaluationResult(
            decision = selected?.let { buildDecision(it, context) },
            evaluations = evaluations,
            droppedMetadataKeys = context.droppedMetadataKeys,
        )
    }

    private fun evaluateRoute(
        route: AlertRouteDefinition,
        context: AlertRouteContext,
        alreadySelected: Boolean,
    ): AlertRouteEvaluation {
        val groups = route.conditionGroups.mapIndexed { index, group -> explainGroup(index, group, context) }
        val matched = groups.isEmpty() || groups.any { it.matched }
        val reason =
            when {
                !route.enabled -> AlertRouteEvaluationReason.ROUTE_DISABLED
                !matched -> AlertRouteEvaluationReason.NO_GROUP_MATCHED
                alreadySelected -> AlertRouteEvaluationReason.EARLIER_ROUTE_SELECTED
                else -> AlertRouteEvaluationReason.SELECTED
            }
        return AlertRouteEvaluation(
            routeId = route.resourceId,
            routeKey = route.key,
            routeName = route.name,
            position = route.position,
            enabled = route.enabled,
            matched = matched,
            selected = reason == AlertRouteEvaluationReason.SELECTED,
            reason = reason,
            groups = groups,
        )
    }

    private fun explainGroup(
        position: Int,
        group: AlertRouteConditionGroupDefinition,
        context: AlertRouteContext,
    ): AlertRouteGroupExplanation {
        val conditions = group.conditions.map { explainCondition(it, context) }
        return AlertRouteGroupExplanation(
            position = position,
            matched = conditions.isNotEmpty() && conditions.all { it.matched },
            conditions = conditions,
        )
    }

    private fun explainCondition(
        condition: AlertRouteConditionDefinition,
        context: AlertRouteContext,
    ): AlertRouteConditionExplanation {
        val actual = context.value(condition.field, condition.metadataKey)
        return AlertRouteConditionExplanation(
            field = condition.field,
            metadataKey = condition.metadataKey,
            operator = condition.operator,
            values = condition.values,
            actualValue = actual,
            matched = matches(condition.operator, actual, condition.values),
        )
    }

    private fun matches(
        operator: AlertRouteConditionOperator,
        actual: String?,
        values: List<String>,
    ): Boolean =
        when (operator) {
            AlertRouteConditionOperator.IS_SET -> actual != null
            AlertRouteConditionOperator.IS_NOT_SET -> actual == null
            AlertRouteConditionOperator.AT_LEAST_AS_SEVERE_AS -> matchesSeverity(actual, values)
            else -> matchesValue(operator, actual, values)
        }

    private fun matchesSeverity(actual: String?, values: List<String>): Boolean {
        val actualPriority = AlertPriority.fromString(actual) ?: return false
        val threshold = values.firstNotNullOfOrNull { AlertPriority.fromString(it) } ?: return false
        return actualPriority.ordinal <= threshold.ordinal
    }

    private fun matchesValue(
        operator: AlertRouteConditionOperator,
        actual: String?,
        values: List<String>,
    ): Boolean {
        if (actual == null) return operator in NEGATIVE_OPERATORS
        return when (operator) {
            AlertRouteConditionOperator.EQUALS,
            AlertRouteConditionOperator.ONE_OF,
            -> values.any { it.equals(actual, ignoreCase = true) }
            AlertRouteConditionOperator.NOT_EQUALS,
            AlertRouteConditionOperator.NOT_ONE_OF,
            -> values.none { it.equals(actual, ignoreCase = true) }
            AlertRouteConditionOperator.CONTAINS -> values.any { actual.contains(it, ignoreCase = true) }
            AlertRouteConditionOperator.NOT_CONTAINS -> values.none { actual.contains(it, ignoreCase = true) }
            AlertRouteConditionOperator.STARTS_WITH -> values.any { actual.startsWith(it, ignoreCase = true) }
            AlertRouteConditionOperator.ENDS_WITH -> values.any { actual.endsWith(it, ignoreCase = true) }
            else -> false
        }
    }

    private fun buildDecision(
        route: AlertRouteDefinition,
        context: AlertRouteContext,
    ): AlertRouteDecision =
        AlertRouteDecision(
            routeId = route.resourceId,
            routeKey = route.key,
            routeName = route.name,
            position = route.position,
            revision = route.revision,
            paging = resolvePaging(route, context),
            grouping = resolveGrouping(route, context),
            incident = resolveIncident(route, context),
            recovery =
                AlertRouteRecoveryResolution(
                    cancelEscalations = route.recovery.cancelEscalations,
                    declineUnacceptedTriage = route.recovery.declineUnacceptedTriage,
                    graceSeconds = route.recovery.graceSeconds,
                ),
        )

    private fun resolvePaging(
        route: AlertRouteDefinition,
        context: AlertRouteContext,
    ): AlertRoutePagingResolution {
        if (route.paging.mode == AlertRoutePagingMode.NONE) {
            return AlertRoutePagingResolution(AlertRoutePagingMode.NONE, emptyList(), emptyList(), emptyList())
        }
        val escalationPolicies = linkedSetOf<AlertRouteEscalationPolicyRef>()
        val teams = linkedSetOf<AlertRouteTeamRef>()
        val unresolved = mutableListOf<String>()
        route.paging.targets.forEach { target ->
            val policy = resolveTarget(route.organizationId, target, context, teams)
            if (policy == null) unresolved.add(target.describe()) else escalationPolicies.add(policy)
        }
        return AlertRoutePagingResolution(
            mode = route.paging.mode,
            escalationPolicies = escalationPolicies.toList(),
            teams = teams.toList(),
            unresolvedTargets = unresolved,
        )
    }

    private fun resolveTarget(
        organizationId: Int,
        target: AlertRouteTargetDefinition,
        context: AlertRouteContext,
        teams: MutableSet<AlertRouteTeamRef>,
    ): AlertRouteEscalationPolicyRef? =
        when (target.kind) {
            AlertRouteTargetKind.ESCALATION_POLICY ->
                target.escalationPolicyId?.let { AlertRouteEscalationPolicyRef(it, target.escalationPolicyResourceId) }
            AlertRouteTargetKind.TEAM ->
                target.teamId
                    ?.also { teams.add(AlertRouteTeamRef(it, target.teamResourceId)) }
                    ?.let { teamEscalationResolver.escalationPolicy(organizationId, it) }
            AlertRouteTargetKind.OWNERSHIP_DERIVED -> resolveOwnershipTarget(target, context, teams)
        }

    private fun resolveOwnershipTarget(
        target: AlertRouteTargetDefinition,
        context: AlertRouteContext,
        teams: MutableSet<AlertRouteTeamRef>,
    ): AlertRouteEscalationPolicyRef? {
        val ownership = context.ownership
        val dimensionPresent =
            when (target.ownershipSource) {
                AlertRouteOwnershipSource.SERVICE -> ownership.serviceName != null
                AlertRouteOwnershipSource.TEAM -> ownership.teamId != null || ownership.teamName != null
                AlertRouteOwnershipSource.CATALOG -> ownership.catalogResourceId != null
                null -> false
            }
        if (!dimensionPresent) return null
        ownership.teamId?.let { teams.add(AlertRouteTeamRef(it, ownership.teamResourceId)) }
        return ownership.escalationPolicyId?.let {
            AlertRouteEscalationPolicyRef(it, ownership.escalationPolicyResourceId)
        }
    }

    private fun resolveGrouping(
        route: AlertRouteDefinition,
        context: AlertRouteContext,
    ): AlertRouteGroupingResolution {
        val keys = route.grouping.keys.ifEmpty { DEFAULT_GROUPING_KEYS }
        val unresolved = mutableListOf<String>()
        val parts =
            keys.map { key ->
                context.reference(key) ?: run {
                    unresolved.add(key)
                    ""
                }
            }
        return AlertRouteGroupingResolution(
            behavior = route.grouping.behavior,
            automatic = route.grouping.behavior == AlertRouteGroupingBehavior.AUTOMATIC,
            groupKey = (listOf(route.key) + parts).joinToString(GROUP_KEY_SEPARATOR),
            keys = keys,
            unresolvedKeys = unresolved,
            windowKind = route.grouping.windowKind,
            windowSeconds = route.grouping.windowSeconds,
        )
    }

    private fun resolveIncident(
        route: AlertRouteDefinition,
        context: AlertRouteContext,
    ): AlertRouteIncidentResolution {
        val title = AlertRouteTemplateRenderer.render(route.incident.titleTemplate, context)
        val summary = AlertRouteTemplateRenderer.render(route.incident.summaryTemplate, context)
        return AlertRouteIncidentResolution(
            create = route.incident.create,
            mode = route.incident.mode,
            title = title.text ?: context.title,
            summary = summary.text,
            severity = route.incident.severity,
            incidentType = route.incident.incidentType,
            unresolvedReferences = (title.unresolvedReferences + summary.unresolvedReferences).distinct(),
        )
    }

    private fun AlertRouteTargetDefinition.describe(): String =
        when (kind) {
            AlertRouteTargetKind.ESCALATION_POLICY -> "ESCALATION_POLICY"
            AlertRouteTargetKind.TEAM -> "TEAM:${teamId ?: "unknown"}"
            AlertRouteTargetKind.OWNERSHIP_DERIVED -> "OWNERSHIP_DERIVED:${ownershipSource?.wire ?: "unknown"}"
        }

    private companion object {
        const val GROUP_KEY_SEPARATOR = "|"
        val DEFAULT_GROUPING_KEYS = listOf("alert.deduplication_key")
        val NEGATIVE_OPERATORS =
            setOf(
                AlertRouteConditionOperator.NOT_EQUALS,
                AlertRouteConditionOperator.NOT_ONE_OF,
                AlertRouteConditionOperator.NOT_CONTAINS,
            )
    }
}
