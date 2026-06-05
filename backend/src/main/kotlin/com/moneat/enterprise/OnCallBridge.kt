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

    /** Trigger the escalation engine and return the on-call alert ID (or null). */
    suspend fun triggerEscalation(
        organizationId: Int,
        escalationPolicyId: Int,
        title: String,
        description: String?,
        priority: String,
        alertSource: String,
        deduplicationKey: String?,
        metadata: String?
    ): Int?

    /** Declare an operational incident and return the declared incident ID. */
    suspend fun declareIncident(
        organizationId: Int,
        userId: Int,
        alertId: Int?,
        title: String,
        description: String?,
        severity: String
    ): Int?

    /** Get an on-call alert by ID for a user. */
    fun getIncident(
        incidentId: Int,
        userId: Int
    ): IncidentInfo?

    /** Acknowledge an on-call alert. */
    fun acknowledgeIncident(
        incidentId: Int,
        userId: Int
    ): Boolean
}

/** Lightweight data carrier for priority info, avoiding enterprise model dependency. */
data class PriorityInfo(val priority: String, val label: String?)

/** Lightweight data carrier for incident info, avoiding enterprise model dependency. */
data class IncidentInfo(val id: Int, val organizationId: Int, val title: String, val status: String)
