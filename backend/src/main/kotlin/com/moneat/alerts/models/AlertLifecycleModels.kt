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

package com.moneat.alerts.models

import kotlinx.serialization.json.JsonElement

enum class AlertSeverity {
    CRITICAL, HIGH, MEDIUM, LOW;

    companion object {
        fun fromString(value: String?): AlertSeverity? =
            value?.let {
                try {
                    valueOf(it.uppercase())
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
    }
}

enum class AlertStatus {
    FIRING, RESOLVED
}

enum class AlertSource {
    HOST_ALERT,
    HOST_DOWN,
    UPTIME_MONITOR,
    ERROR_ALERT,
    DASHBOARD_ALERT
}

data class AlertLifecycleEvent(
    val title: String,
    val description: String,
    val severity: AlertSeverity,
    val status: AlertStatus,
    val source: AlertSource,
    val deduplicationKey: String,
    val organizationId: Int,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val moneatUrl: String
)
