// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.enterprise.alertroutes.models.AlertGroupDecisionType
import com.moneat.enterprise.alertroutes.models.AlertGroupEscalationState
import com.moneat.enterprise.alertroutes.models.AlertGroupMemberState
import com.moneat.enterprise.alertroutes.models.AlertGroupPagingState
import com.moneat.enterprise.alertroutes.models.AlertGroupState
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingBehavior
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingWindowKind
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class AlertGroupMemberRecord(
    val id: Uuid,
    val episodeId: Uuid,
    val state: AlertGroupMemberState,
    val version: Int,
    val pagingState: AlertGroupPagingState,
    val firstJoinedAt: Instant,
    val lastSeenAt: Instant,
    val resolvedAt: Instant?,
)

data class AlertGroupDecisionRecord(
    val id: Uuid,
    val type: AlertGroupDecisionType,
    val episodeId: Uuid?,
    val incidentId: Uuid?,
    val actorId: Uuid?,
    val commandKey: String,
    val details: Map<String, JsonElement>,
    val createdAt: Instant,
)

data class AlertGroupRecord(
    val id: Uuid,
    val routeId: Uuid,
    val routeRevision: Int,
    val identityHash: String,
    val groupingTuple: Map<String, JsonElement>,
    val singleton: Boolean,
    val behavior: AlertRouteGroupingBehavior,
    val windowKind: AlertRouteGroupingWindowKind,
    val windowSeconds: Int,
    val openedAt: Instant,
    val lastAlertAt: Instant,
    val closesAt: Instant,
    val state: AlertGroupState,
    val version: Int,
    val incidentId: Uuid?,
    val candidateIncidentId: Uuid?,
    val routeSnapshot: Map<String, JsonElement>,
    val incidentTemplateSnapshot: Map<String, JsonElement>,
    val pagingMode: AlertRoutePagingMode,
    val pagingState: AlertGroupPagingState,
    val members: List<AlertGroupMemberRecord>,
    val decisions: List<AlertGroupDecisionRecord>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AlertGroupCommandResult(val group: AlertGroupRecord, val replayed: Boolean)

data class AlertGroupPagingCompletion(
    val organizationId: Int,
    val groupId: Uuid,
    val episodeId: Uuid,
    val commandKey: String,
    val succeeded: Boolean,
)

data class AlertGroupPagingClaim(
    val organizationId: Int,
    val groupId: Uuid,
    val episodeId: Uuid,
    val commandKey: String,
    val now: Instant = kotlin.time.Clock.System.now(),
)

data class AlertGroupEscalationRecord(
    val organizationId: Int,
    val groupId: Uuid,
    val escalationPolicyId: Int,
    val escalationKey: String,
    val onCallAlertId: Uuid?,
    val state: AlertGroupEscalationState,
    val updatedAt: Instant? = null,
)
