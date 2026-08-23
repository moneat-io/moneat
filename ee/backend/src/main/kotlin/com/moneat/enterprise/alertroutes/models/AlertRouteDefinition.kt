// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.models

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonElement

/** One condition inside a group. Conditions in the same group are ANDed. */
data class AlertRouteConditionDefinition(
    val field: AlertRouteContextField,
    val operator: AlertRouteConditionOperator,
    val values: List<String> = emptyList(),
    val metadataKey: String? = null,
)

/** Groups are ORed with each other. An empty group list matches every alert. */
data class AlertRouteConditionGroupDefinition(
    val conditions: List<AlertRouteConditionDefinition>,
)

/** A static or ownership-derived escalation target. */
data class AlertRouteTargetDefinition(
    val kind: AlertRouteTargetKind,
    val escalationPolicyId: Int? = null,
    val escalationPolicyResourceId: Uuid? = null,
    val teamId: Int? = null,
    val teamResourceId: Uuid? = null,
    val ownershipSource: AlertRouteOwnershipSource? = null,
)

data class AlertRoutePagingDefinition(
    val mode: AlertRoutePagingMode,
    val targets: List<AlertRouteTargetDefinition>,
)

data class AlertRouteGroupingDefinition(
    val behavior: AlertRouteGroupingBehavior,
    val keys: List<String>,
    val windowKind: AlertRouteGroupingWindowKind,
    val windowSeconds: Int,
)

data class AlertRouteIncidentDefinition(
    val create: Boolean,
    val mode: AlertRouteIncidentMode,
    val titleTemplate: String?,
    val summaryTemplate: String?,
    val severity: String?,
    val incidentType: String?,
)

data class AlertRouteRecoveryDefinition(
    val cancelEscalations: Boolean,
    val declineUnacceptedTriage: Boolean,
    val graceSeconds: Int,
)

/** A complete, persisted alert route as evaluated at runtime. */
data class AlertRouteDefinition(
    val id: Int,
    val resourceId: Uuid,
    val organizationId: Int,
    val key: String,
    val name: String,
    val description: String?,
    val enabled: Boolean,
    val position: Int,
    val revision: Int,
    val conditionGroups: List<AlertRouteConditionGroupDefinition>,
    val paging: AlertRoutePagingDefinition,
    val grouping: AlertRouteGroupingDefinition,
    val incident: AlertRouteIncidentDefinition,
    val recovery: AlertRouteRecoveryDefinition,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** One audited revision of a route. */
data class AlertRouteRevisionRecord(
    val id: Uuid,
    val routeId: Uuid,
    val revision: Int,
    val changeType: AlertRouteChangeType,
    val actorId: Uuid?,
    val commandKey: String,
    val snapshot: Map<String, JsonElement>,
    val createdAt: Instant,
)
