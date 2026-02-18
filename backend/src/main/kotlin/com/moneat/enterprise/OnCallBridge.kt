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
    /** Resolve priority for a severity level in the given organization. */
    fun resolvePriority(organizationId: Int, severity: String): PriorityInfo?

    /** Check if escalation should proceed based on business hours. */
    fun shouldEscalate(organizationId: Int, priorityLevel: String): Boolean

    /** Trigger the escalation engine and return the incident ID (or null). */
    suspend fun triggerEscalation(
        organizationId: Int,
        escalationPolicyId: Int,
        title: String,
        description: String?,
        priorityLevel: String,
        alertSource: String,
        deduplicationKey: String?,
        metadata: String?
    ): Int?

    /** Get an incident by ID for a user. Returns a map of incident fields or null. */
    fun getIncident(incidentId: Int, userId: Int): IncidentInfo?

    /** Acknowledge an incident. */
    fun acknowledgeIncident(incidentId: Int, userId: Int): Boolean
}

/** Lightweight data carrier for priority info, avoiding enterprise model dependency. */
data class PriorityInfo(val priorityLevel: String, val label: String?)

/** Lightweight data carrier for incident info, avoiding enterprise model dependency. */
data class IncidentInfo(val id: Int, val organizationId: Int, val title: String, val status: String)
