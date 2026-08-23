// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.routes

import com.moneat.enterprise.alertroutes.services.AlertGroupDecisionRecord
import com.moneat.enterprise.alertroutes.services.AlertGroupMemberRecord
import com.moneat.enterprise.alertroutes.services.AlertGroupRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AlertGroupMemberResponse(
    val id: String,
    val episodeId: String,
    val state: String,
    val version: Int,
    val pagingState: String,
    val firstJoinedAt: String,
    val lastSeenAt: String,
    val resolvedAt: String?,
)

@Serializable
data class AlertGroupDecisionResponse(
    val id: String,
    val type: String,
    val episodeId: String?,
    val incidentId: String?,
    val actorId: String?,
    val commandKey: String,
    val details: Map<String, JsonElement>,
    val createdAt: String,
)

@Serializable
data class AlertGroupResponse(
    val id: String,
    val routeId: String,
    val routeRevision: Int,
    val identityHash: String,
    val groupingTuple: Map<String, JsonElement>,
    val singleton: Boolean,
    val behavior: String,
    val windowKind: String,
    val windowSeconds: Int,
    val openedAt: String,
    val lastAlertAt: String,
    val closesAt: String,
    val state: String,
    val version: Int,
    val incidentId: String?,
    val candidateIncidentId: String?,
    val routeSnapshot: Map<String, JsonElement>,
    val incidentTemplateSnapshot: Map<String, JsonElement>,
    val pagingMode: String,
    val pagingState: String,
    val members: List<AlertGroupMemberResponse>,
    val decisions: List<AlertGroupDecisionResponse>,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class AlertGroupExpectedVersionRequest(val expectedVersion: Int)

@Serializable
data class AttachAlertGroupRequest(val expectedVersion: Int, val incidentId: String)

@Serializable
data class CreateAlertGroupTriageRequest(
    val expectedVersion: Int,
    val title: String? = null,
    val summary: String? = null,
)

internal fun AlertGroupRecord.toResponse(): AlertGroupResponse =
    AlertGroupResponse(
        id = id.toString(),
        routeId = routeId.toString(),
        routeRevision = routeRevision,
        identityHash = identityHash,
        groupingTuple = groupingTuple,
        singleton = singleton,
        behavior = behavior.wire,
        windowKind = windowKind.wire,
        windowSeconds = windowSeconds,
        openedAt = openedAt.toString(),
        lastAlertAt = lastAlertAt.toString(),
        closesAt = closesAt.toString(),
        state = state.wire,
        version = version,
        incidentId = incidentId?.toString(),
        candidateIncidentId = candidateIncidentId?.toString(),
        routeSnapshot = routeSnapshot,
        incidentTemplateSnapshot = incidentTemplateSnapshot,
        pagingMode = pagingMode.wire,
        pagingState = pagingState.wire,
        members = members.map(AlertGroupMemberRecord::toResponse),
        decisions = decisions.map(AlertGroupDecisionRecord::toResponse),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun AlertGroupMemberRecord.toResponse(): AlertGroupMemberResponse =
    AlertGroupMemberResponse(
        id = id.toString(),
        episodeId = episodeId.toString(),
        state = state.wire,
        version = version,
        pagingState = pagingState.wire,
        firstJoinedAt = firstJoinedAt.toString(),
        lastSeenAt = lastSeenAt.toString(),
        resolvedAt = resolvedAt?.toString(),
    )

private fun AlertGroupDecisionRecord.toResponse(): AlertGroupDecisionResponse =
    AlertGroupDecisionResponse(
        id = id.toString(),
        type = type.wire,
        episodeId = episodeId?.toString(),
        incidentId = incidentId?.toString(),
        actorId = actorId?.toString(),
        commandKey = commandKey,
        details = details,
        createdAt = createdAt.toString(),
    )
