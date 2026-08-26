// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.moneat.enterprise

import kotlinx.serialization.json.JsonElement

/**
 * Bridge interface for on-call escalation features.
 * Core code calls these methods optionally; the enterprise module
 * provides the real implementation via [FeatureRegistry].
 */
interface OnCallBridge {
    /** Resolve alert priority settings for the given organization. */
    fun resolvePriority(
        organizationId: Int,
        priority: String
    ): PriorityInfo?

    /** Check if escalation should proceed based on business hours. */
    fun shouldEscalate(
        organizationId: Int,
        priority: String
    ): Boolean

    /** Resolve a public escalation policy resource ID to its internal ID within the organization. */
    fun resolveEscalationPolicyId(
        organizationId: Int,
        escalationPolicyResourceId: String
    ): Int?

    /** Resolve a public on-call alert resource ID to its internal ID within the organization. */
    fun resolveAlertId(
        organizationId: Int,
        alertResourceId: String
    ): Int?

    /** Resolve the current on-call user for a schedule inside the organization. */
    fun getCurrentOnCall(
        organizationId: Int,
        scheduleId: Int
    ): OnCallUserInfo?

    /** Trigger the escalation engine and return the on-call alert resource ID (or null). */
    suspend fun triggerEscalation(
        organizationId: Int,
        escalationPolicyId: Int,
        title: String,
        description: String?,
        priority: String,
        alertSource: String,
        deduplicationKey: String?,
        metadata: String?
    ): String?

    /** Resolve open on-call alerts for the source/deduplication key. */
    suspend fun resolveEscalation(
        organizationId: Int,
        alertSource: String,
        deduplicationKey: String
    ): Boolean

    /** Declare an operational incident and return the declared incident resource ID. */
    suspend fun declareIncident(declaration: OnCallIncidentDeclaration): String?

    /** Create a response action on an operational incident and return its resource ID. */
    suspend fun createIncidentAction(request: OnCallIncidentActionRequest): String? = null

    /** Handle an authenticated Slack command, shortcut, or interaction. */
    suspend fun handleSlackInbound(
        requestType: String,
        payload: String,
        deliveryId: String?,
    ): String? = null

    /** Get a declared incident by ID for a user. */
    fun getIncident(
        incidentId: Int,
        userId: Int
    ): IncidentInfo?

    /** Acknowledge linked alerts for a declared incident. */
    fun acknowledgeIncident(
        incidentId: Int,
        userId: Int
    ): Boolean

    /** Get an on-call alert by ID for a user. */
    fun getAlert(
        alertId: Int,
        userId: Int
    ): IncidentInfo?

    /** Acknowledge an on-call alert. */
    fun acknowledgeAlert(
        alertId: Int,
        userId: Int
    ): Boolean
}

data class OnCallIncidentDeclaration(
    val organizationId: Int,
    val userId: Int,
    val alertId: Int?,
    val title: String,
    val description: String?,
    val severity: String,
    val commandKey: String,
    val origin: String = "WORKFLOW",
    val formDefinitionId: Int? = null,
    val formDefinitionSnapshot: Map<String, JsonElement> = emptyMap(),
    val formValues: Map<String, JsonElement> = emptyMap(),
)

data class OnCallIncidentActionRequest(
    val organizationId: Int,
    val incidentResourceId: String,
    val userId: Int,
    val description: String,
    val assigneeUserResourceId: String? = null,
    val source: String = "WORKFLOW",
    val slackChannelId: String? = null,
    val slackMessageTs: String? = null,
    val commandKey: String,
)

/** Lightweight data carrier for priority info, avoiding enterprise model dependency. */
data class PriorityInfo(val priority: String, val label: String?)

/** Lightweight data carrier for current on-call user info, avoiding enterprise model dependency. */
data class OnCallUserInfo(val userId: String, val userName: String)

/** Lightweight data carrier for incident info, avoiding enterprise model dependency. */
data class IncidentInfo(val id: Int, val organizationId: Int, val title: String, val status: String)
