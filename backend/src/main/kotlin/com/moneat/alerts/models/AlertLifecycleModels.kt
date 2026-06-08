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

enum class AlertPriority(
    val wire: String,
    private val aliases: Set<String>
) {
    P0("P0", setOf("CRITICAL")),
    P1("P1", setOf("HIGH")),
    P2("P2", setOf("MEDIUM")),
    P3("P3", setOf("LOW")),
    P4("P4", emptySet()),
    P5("P5", emptySet());

    companion object {
        fun fromString(value: String?): AlertPriority? {
            val normalized = normalize(value) ?: return null
            return entries.firstOrNull { priority ->
                normalized == priority.wire ||
                    normalized == priority.name ||
                    normalized in priority.aliases
            }
        }

        fun wireValue(value: String?): String? = fromString(value)?.wire

        private fun normalize(value: String?): String? =
            value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.uppercase()
                ?.replace('_', '-')
    }

    override fun toString(): String = wire
}

enum class IncidentSeverity(
    val wire: String,
    private val aliases: Set<String>
) {
    SEV0("SEV-0", setOf("SEV0")),
    SEV1("SEV-1", setOf("SEV1")),
    SEV2("SEV-2", setOf("SEV2")),
    SEV3("SEV-3", setOf("SEV3")),
    SEV4("SEV-4", setOf("SEV4"));

    companion object {
        private val compactSevPattern = Regex("^SEV([0-4])$")

        fun fromString(value: String?): IncidentSeverity? {
            val normalized = normalize(value) ?: return null
            return entries.firstOrNull { severity ->
                normalized == severity.wire ||
                    normalized == severity.name ||
                    normalized in severity.aliases
            }
        }

        fun wireValue(value: String?): String? = fromString(value)?.wire

        private fun normalize(value: String?): String? {
            val normalized =
                value
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.uppercase()
                    ?.replace('_', '-')
                    ?: return null
            return compactSevPattern.replace(normalized) { matchResult ->
                "SEV-${matchResult.groupValues[1]}"
            }
        }
    }

    override fun toString(): String = wire
}

enum class AlertStatus {
    FIRING, RESOLVED
}

enum class AlertSource {
    HOST_ALERT,
    HOST_DOWN,
    UPTIME_MONITOR,
    SYNTHETIC_TEST,
    ERROR_ALERT,
    DASHBOARD_ALERT
}

data class AlertLifecycleEvent(
    val title: String,
    val description: String,
    val priority: AlertPriority,
    val status: AlertStatus,
    val source: AlertSource,
    val deduplicationKey: String,
    val organizationId: Int,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val moneatUrl: String
)
