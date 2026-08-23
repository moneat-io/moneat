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

/** Stable billing keys shared by native incident response and later alert-route tasks. */
enum class NativeIncidentQuotaKey(val wire: String, val window: NativeIncidentQuotaWindow) {
    NATIVE_INCIDENTS("native_incidents", NativeIncidentQuotaWindow.MONTHLY),
    RESPONDERS("responders", NativeIncidentQuotaWindow.CAPACITY),
    INTEGRATIONS("integrations", NativeIncidentQuotaWindow.CAPACITY),
    AI_RUNS("ai_runs", NativeIncidentQuotaWindow.MONTHLY),
    TRANSCRIPTION_MINUTES("transcription_minutes", NativeIncidentQuotaWindow.MONTHLY),
    RETAINED_EVIDENCE_BYTES("retained_evidence_bytes", NativeIncidentQuotaWindow.CAPACITY),
    ENABLED_ALERT_ROUTES("enabled_alert_routes", NativeIncidentQuotaWindow.CAPACITY),
    ACTIVE_ALERT_GROUPS("active_alert_groups", NativeIncidentQuotaWindow.CAPACITY),
    ROUTE_CREATED_INCIDENTS("route_created_incidents", NativeIncidentQuotaWindow.MONTHLY),
    AUTOMATIC_CORRELATIONS("automatic_correlations", NativeIncidentQuotaWindow.MONTHLY),
    AI_ASSISTED_CORRELATIONS("ai_assisted_correlations", NativeIncidentQuotaWindow.MONTHLY),
}

enum class NativeIncidentQuotaWindow {
    CAPACITY,
    MONTHLY,
}

data class NativeIncidentQuotaStatus(
    val key: NativeIncidentQuotaKey,
    val limit: Long,
    val used: Long,
) {
    val remaining: Long = (limit - used).coerceAtLeast(0)
    val exhausted: Boolean = used >= limit
}

data class NativeIncidentEntitlementStatus(
    val enabled: Boolean,
    val plan: String,
    val reason: String? = null,
    val quotas: Map<NativeIncidentQuotaKey, NativeIncidentQuotaStatus> = emptyMap(),
)

data class NativeIncidentQuotaDecision(
    val allowed: Boolean,
    val status: NativeIncidentQuotaStatus,
    val message: String? = null,
    val replayed: Boolean = false,
)

/**
 * Runtime bridge implemented by billing. Calls may occur inside an existing Exposed transaction;
 * implementations must preserve that transaction so admission and the protected mutation are atomic.
 */
interface NativeIncidentEntitlementBridge {
    fun status(organizationId: Int): NativeIncidentEntitlementStatus

    fun isEnabled(organizationId: Int): Boolean = status(organizationId).enabled

    fun consume(
        organizationId: Int,
        quotaKey: NativeIncidentQuotaKey,
        quantity: Long,
        idempotencyKey: String,
    ): NativeIncidentQuotaDecision

    fun reconcile(
        organizationId: Int,
        quotaKey: NativeIncidentQuotaKey,
        authoritativeUsage: Long,
        idempotencyKey: String,
    ): NativeIncidentQuotaDecision
}
