// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.routes

import com.moneat.enterprise.alertroutes.commands.AlertRouteConditionGroupInput
import com.moneat.enterprise.alertroutes.commands.AlertRouteConditionInput
import com.moneat.enterprise.alertroutes.commands.AlertRouteGroupingInput
import com.moneat.enterprise.alertroutes.commands.AlertRouteIncidentInput
import com.moneat.enterprise.alertroutes.commands.AlertRoutePagingInput
import com.moneat.enterprise.alertroutes.commands.AlertRouteRecoveryInput
import com.moneat.enterprise.alertroutes.commands.AlertRouteOrderEntry
import com.moneat.enterprise.alertroutes.commands.AlertRouteSpecification
import com.moneat.enterprise.alertroutes.commands.AlertRouteTargetInput
import com.moneat.enterprise.alertroutes.context.AlertRouteSampleInput
import com.moneat.enterprise.alertroutes.evaluation.AlertRouteDecision
import com.moneat.enterprise.alertroutes.evaluation.AlertRouteEvaluationResult
import com.moneat.enterprise.alertroutes.models.AlertRouteConditionOperator
import com.moneat.enterprise.alertroutes.models.AlertRouteContextField
import com.moneat.enterprise.alertroutes.models.AlertRouteDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingBehavior
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingWindowKind
import com.moneat.enterprise.alertroutes.models.AlertRouteIncidentMode
import com.moneat.enterprise.alertroutes.models.AlertRouteOwnershipSource
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import com.moneat.enterprise.alertroutes.models.AlertRouteRevisionRecord
import com.moneat.enterprise.alertroutes.models.AlertRouteTargetKind
import com.moneat.shared.services.toUuidOrNull
import kotlin.uuid.Uuid

internal fun AlertRouteRequest.toSpecification(): AlertRouteSpecification =
    AlertRouteSpecification(
        key = key,
        name = name,
        description = description,
        enabled = enabled,
        conditionGroups =
            conditionGroups.map { group ->
                AlertRouteConditionGroupInput(group.conditions.map { it.toInput() })
            },
        paging = paging.toInput(),
        grouping = grouping.toInput(),
        incident = incident.toInput(),
        recovery = recovery.toInput(),
    )

private fun AlertRouteConditionPayload.toInput(): AlertRouteConditionInput =
    AlertRouteConditionInput(
        field = AlertRouteContextField.fromWire(field) ?: invalid("alert route field", field),
        operator = AlertRouteConditionOperator.fromWire(operator) ?: invalid("alert route operator", operator),
        values = values,
        metadataKey = metadataKey?.trim()?.takeIf { it.isNotEmpty() },
    )

private fun AlertRoutePagingPayload.toInput(): AlertRoutePagingInput =
    AlertRoutePagingInput(
        mode = AlertRoutePagingMode.fromWire(mode) ?: invalid("paging mode", mode),
        targets = targets.map { it.toInput() },
    )

private fun AlertRouteTargetPayload.toInput(): AlertRouteTargetInput =
    AlertRouteTargetInput(
        kind = AlertRouteTargetKind.fromWire(kind) ?: invalid("paging target kind", kind),
        escalationPolicyId = escalationPolicyId?.let { it.toUuidOrNull() ?: invalid("escalation policy id", it) },
        teamId = teamId?.let { it.toUuidOrNull() ?: invalid("team id", it) },
        ownershipSource =
            ownershipSource?.let { AlertRouteOwnershipSource.fromWire(it) ?: invalid("ownership source", it) },
    )

private fun AlertRouteGroupingPayload.toInput(): AlertRouteGroupingInput =
    AlertRouteGroupingInput(
        behavior = AlertRouteGroupingBehavior.fromWire(behavior) ?: invalid("grouping behavior", behavior),
        keys = keys,
        windowKind = AlertRouteGroupingWindowKind.fromWire(windowKind) ?: invalid("grouping window", windowKind),
        windowSeconds = windowSeconds,
    )

private fun AlertRouteIncidentPayload.toInput(): AlertRouteIncidentInput =
    AlertRouteIncidentInput(
        create = create,
        mode = AlertRouteIncidentMode.fromWire(mode) ?: invalid("incident mode", mode),
        titleTemplate = titleTemplate,
        summaryTemplate = summaryTemplate,
        severity = severity,
        incidentType = incidentType,
    )

private fun AlertRouteRecoveryPayload.toInput(): AlertRouteRecoveryInput =
    AlertRouteRecoveryInput(
        cancelEscalations = cancelEscalations,
        declineUnacceptedTriage = declineUnacceptedTriage,
        graceSeconds = graceSeconds,
    )

internal fun AlertRouteSamplePayload.toInput(): AlertRouteSampleInput =
    AlertRouteSampleInput(
        source = source,
        status = status,
        priority = priority,
        title = title,
        description = description,
        deduplicationKey = deduplicationKey,
        url = url,
        metadata = metadata,
    )

internal fun AlertRouteDefinition.toResponse(): AlertRouteResponse =
    AlertRouteResponse(
        id = resourceId.toString(),
        key = key,
        name = name,
        description = description,
        enabled = enabled,
        position = position,
        revision = revision,
        conditionGroups =
            conditionGroups.map { group ->
                AlertRouteConditionGroupPayload(
                    conditions =
                        group.conditions.map { condition ->
                            AlertRouteConditionPayload(
                                field = condition.field.wire,
                                operator = condition.operator.wire,
                                values = condition.values,
                                metadataKey = condition.metadataKey,
                            )
                        },
                )
            },
        paging =
            AlertRoutePagingPayload(
                mode = paging.mode.wire,
                targets =
                    paging.targets.map { target ->
                        AlertRouteTargetPayload(
                            kind = target.kind.wire,
                            escalationPolicyId = target.escalationPolicyResourceId?.toString(),
                            teamId = target.teamResourceId?.toString(),
                            ownershipSource = target.ownershipSource?.wire,
                        )
                    },
            ),
        grouping =
            AlertRouteGroupingPayload(
                behavior = grouping.behavior.wire,
                keys = grouping.keys,
                windowKind = grouping.windowKind.wire,
                windowSeconds = grouping.windowSeconds,
            ),
        incident =
            AlertRouteIncidentPayload(
                create = incident.create,
                mode = incident.mode.wire,
                titleTemplate = incident.titleTemplate,
                summaryTemplate = incident.summaryTemplate,
                severity = incident.severity,
                incidentType = incident.incidentType,
            ),
        recovery =
            AlertRouteRecoveryPayload(
                cancelEscalations = recovery.cancelEscalations,
                declineUnacceptedTriage = recovery.declineUnacceptedTriage,
                graceSeconds = recovery.graceSeconds,
            ),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

internal fun AlertRouteRevisionRecord.toPayload(): AlertRouteRevisionPayload =
    AlertRouteRevisionPayload(
        id = id.toString(),
        routeId = routeId.toString(),
        revision = revision,
        changeType = changeType.wire,
        actorId = actorId?.toString(),
        commandKey = commandKey,
        snapshot = snapshot,
        createdAt = createdAt.toString(),
    )

internal fun AlertRouteEvaluationResult.toPreviewResponse(): AlertRoutePreviewResponse =
    AlertRoutePreviewResponse(
        matchedRouteId = decision?.routeId?.toString(),
        decision = decision?.toPayload(),
        evaluations =
            evaluations.map { evaluation ->
                AlertRouteEvaluationPayload(
                    routeId = evaluation.routeId.toString(),
                    key = evaluation.routeKey,
                    name = evaluation.routeName,
                    position = evaluation.position,
                    enabled = evaluation.enabled,
                    matched = evaluation.matched,
                    selected = evaluation.selected,
                    reason = evaluation.reason.wire,
                    groups =
                        evaluation.groups.map { group ->
                            AlertRouteGroupExplanationPayload(
                                position = group.position,
                                matched = group.matched,
                                conditions =
                                    group.conditions.map { condition ->
                                        AlertRouteConditionExplanationPayload(
                                            field = condition.field.wire,
                                            operator = condition.operator.wire,
                                            values = condition.values,
                                            metadataKey = condition.metadataKey,
                                            actualValue = condition.actualValue,
                                            matched = condition.matched,
                                        )
                                    },
                            )
                        },
                )
            },
        droppedMetadataKeys = droppedMetadataKeys,
    )

private fun AlertRouteDecision.toPayload(): AlertRouteDecisionPayload =
    AlertRouteDecisionPayload(
        routeId = routeId.toString(),
        key = routeKey,
        name = routeName,
        position = position,
        revision = revision,
        paging =
            AlertRoutePagingResolutionPayload(
                mode = paging.mode.wire,
                escalationPolicyIds = paging.escalationPolicies.mapNotNull { it.resourceId?.toString() },
                teamIds = paging.teams.mapNotNull { it.resourceId?.toString() },
                unresolvedTargets = paging.unresolvedTargets,
            ),
        grouping =
            AlertRouteGroupingResolutionPayload(
                behavior = grouping.behavior.wire,
                automatic = grouping.automatic,
                groupKey = grouping.groupKey,
                keys = grouping.keys,
                unresolvedKeys = grouping.unresolvedKeys,
                windowKind = grouping.windowKind.wire,
                windowSeconds = grouping.windowSeconds,
            ),
        incident =
            AlertRouteIncidentResolutionPayload(
                create = incident.create,
                mode = incident.mode.wire,
                title = incident.title,
                summary = incident.summary,
                severity = incident.severity,
                incidentType = incident.incidentType,
                unresolvedReferences = incident.unresolvedReferences,
            ),
        recovery =
            AlertRouteRecoveryPayload(
                cancelEscalations = recovery.cancelEscalations,
                declineUnacceptedTriage = recovery.declineUnacceptedTriage,
                graceSeconds = recovery.graceSeconds,
            ),
    )

internal fun AlertRouteOrderPayload.toInput(): AlertRouteOrderEntry =
    AlertRouteOrderEntry(
        routeId = parseRouteId(id),
        expectedRevision = expectedRevision,
    )

internal fun parseRouteId(raw: String?): Uuid =
    raw?.toUuidOrNull() ?: invalid("alert route id", raw.orEmpty())

private fun invalid(label: String, value: String): Nothing =
    throw IllegalArgumentException("Invalid $label: $value")
