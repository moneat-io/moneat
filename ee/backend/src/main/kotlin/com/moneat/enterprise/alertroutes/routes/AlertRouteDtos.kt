// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.routes

import com.moneat.enterprise.alertroutes.commands.DEFAULT_GROUPING_WINDOW_SECONDS
import kotlinx.serialization.Serializable

@Serializable
data class AlertRouteConditionPayload(
    val field: String,
    val operator: String,
    val values: List<String> = emptyList(),
    val metadataKey: String? = null,
)

@Serializable
data class AlertRouteConditionGroupPayload(
    val conditions: List<AlertRouteConditionPayload> = emptyList(),
)

@Serializable
data class AlertRouteTargetPayload(
    val kind: String,
    val escalationPolicyId: String? = null,
    val teamId: String? = null,
    val ownershipSource: String? = null,
)

@Serializable
data class AlertRoutePagingPayload(
    val mode: String = "NONE",
    val targets: List<AlertRouteTargetPayload> = emptyList(),
)

@Serializable
data class AlertRouteGroupingPayload(
    val behavior: String = "SUGGESTED",
    val keys: List<String> = emptyList(),
    val windowKind: String = "ROLLING",
    val windowSeconds: Int = DEFAULT_GROUPING_WINDOW_SECONDS,
)

@Serializable
data class AlertRouteIncidentPayload(
    val create: Boolean = false,
    val mode: String = "TRIAGE",
    val titleTemplate: String? = null,
    val summaryTemplate: String? = null,
    val severity: String? = null,
    val incidentType: String? = null,
)

@Serializable
data class AlertRouteRecoveryPayload(
    val cancelEscalations: Boolean = false,
    val declineUnacceptedTriage: Boolean = false,
    val graceSeconds: Int = 0,
)

@Serializable
data class AlertRouteRequest(
    val key: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val expectedRevision: Int? = null,
    val conditionGroups: List<AlertRouteConditionGroupPayload> = emptyList(),
    val paging: AlertRoutePagingPayload = AlertRoutePagingPayload(),
    val grouping: AlertRouteGroupingPayload = AlertRouteGroupingPayload(),
    val incident: AlertRouteIncidentPayload = AlertRouteIncidentPayload(),
    val recovery: AlertRouteRecoveryPayload = AlertRouteRecoveryPayload(),
)

@Serializable
data class AlertRouteResponse(
    val id: String,
    val key: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean,
    val position: Int,
    val revision: Int,
    val conditionGroups: List<AlertRouteConditionGroupPayload>,
    val paging: AlertRoutePagingPayload,
    val grouping: AlertRouteGroupingPayload,
    val incident: AlertRouteIncidentPayload,
    val recovery: AlertRouteRecoveryPayload,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ReorderAlertRoutesRequest(
    val routes: List<AlertRouteOrderPayload>,
)

@Serializable
data class AlertRouteOrderPayload(
    val id: String,
    val expectedRevision: Int,
)

@Serializable
data class AlertRouteSamplePayload(
    val source: String,
    val status: String = "FIRING",
    val priority: String = "P3",
    val title: String = "",
    val description: String = "",
    val deduplicationKey: String = "",
    val url: String = "",
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class AlertRoutePreviewRequest(
    val episodeId: String? = null,
    val sample: AlertRouteSamplePayload? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class AlertRouteConditionExplanationPayload(
    val field: String,
    val operator: String,
    val values: List<String>,
    val metadataKey: String? = null,
    val actualValue: String? = null,
    val matched: Boolean,
)

@Serializable
data class AlertRouteGroupExplanationPayload(
    val position: Int,
    val matched: Boolean,
    val conditions: List<AlertRouteConditionExplanationPayload>,
)

@Serializable
data class AlertRouteEvaluationPayload(
    val routeId: String,
    val key: String,
    val name: String,
    val position: Int,
    val enabled: Boolean,
    val matched: Boolean,
    val selected: Boolean,
    val reason: String,
    val groups: List<AlertRouteGroupExplanationPayload>,
)

@Serializable
data class AlertRoutePagingResolutionPayload(
    val mode: String,
    val escalationPolicyIds: List<String>,
    val teamIds: List<String>,
    val unresolvedTargets: List<String>,
)

@Serializable
data class AlertRouteGroupingResolutionPayload(
    val behavior: String,
    val automatic: Boolean,
    val groupKey: String,
    val keys: List<String>,
    val tuple: Map<String, String>,
    val singleton: Boolean,
    val unresolvedKeys: List<String>,
    val windowKind: String,
    val windowSeconds: Int,
)

@Serializable
data class AlertRouteIncidentResolutionPayload(
    val create: Boolean,
    val mode: String,
    val title: String? = null,
    val summary: String? = null,
    val severity: String? = null,
    val incidentType: String? = null,
    val unresolvedReferences: List<String>,
)

@Serializable
data class AlertRouteDecisionPayload(
    val routeId: String,
    val key: String,
    val name: String,
    val position: Int,
    val revision: Int,
    val paging: AlertRoutePagingResolutionPayload,
    val grouping: AlertRouteGroupingResolutionPayload,
    val incident: AlertRouteIncidentResolutionPayload,
    val recovery: AlertRouteRecoveryPayload,
)

@Serializable
data class AlertRoutePreviewResponse(
    val matchedRouteId: String? = null,
    val decision: AlertRouteDecisionPayload? = null,
    val evaluations: List<AlertRouteEvaluationPayload>,
    val droppedMetadataKeys: List<String>,
)

@Serializable
data class AlertRouteRevisionPayload(
    val id: String,
    val routeId: String,
    val revision: Int,
    val changeType: String,
    val actorId: String? = null,
    val commandKey: String,
    val snapshot: Map<String, kotlinx.serialization.json.JsonElement>,
    val createdAt: String,
)
