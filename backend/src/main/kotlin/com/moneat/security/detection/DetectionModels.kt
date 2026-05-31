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

package com.moneat.security.detection

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.jsonb
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * Detection rules — declarative, never code. The author supplies a `LogQueryParser` [filter]
 * expression plus structured [groupBy]/[windowSeconds]/[type]/[thresholdCount]; the
 * [RuleQueryCompiler] is the only path from rule → query and injects org scoping itself.
 *
 * [organizationId] scopes who *owns* the rule (read/edit), never the compiled query's tenant: the
 * engine injects `organization_id` from its own context, so there is deliberately no rule field a
 * malicious author could use to read another tenant's logs.
 */
object DetectionRules : IntIdTable("detection_rules") {
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val description = text("description").default("")
    val ruleSource = varchar("source", 32).default(DetectionSource.LOGS.wire)
    val filter = text("filter").default("")
    val groupBy = jsonb("group_by").default("[]")
    val windowSeconds = integer("window_seconds").default(DEFAULT_WINDOW_SECONDS)
    val type = varchar("type", 32).default(DetectionRuleType.THRESHOLD.wire)
    val thresholdCount = integer("threshold_count").nullable()
    val severity = varchar("severity", 16).default("medium")
    val signalTitle = text("signal_title").default("")
    val signalMessage = text("signal_message").default("")
    val suppressions = jsonb("suppressions").default("[]")
    val enabled = bool("enabled").default(false)
    val tags = jsonb("tags").default("[]")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

/**
 * Per-`(rule, group_key)` first-seen state for the new-value evaluator. Org-keyed Postgres state so
 * the seen-set is small, mutable, TTL'd by the evaluator's baseline window, and never crosses tenants.
 */
object DetectionBaselineValues : IntIdTable("detection_baseline_values") {
    val ruleId = integer("rule_id").references(DetectionRules.id, onDelete = ReferenceOption.CASCADE)
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val groupKey = text("group_key")
    val firstSeen = timestamp("first_seen")
    val lastSeen = timestamp("last_seen")
}

/** Default evaluation window when a rule does not specify one (5 minutes). */
const val DEFAULT_WINDOW_SECONDS: Int = 300

/** Telemetry source a rule reads. Phase 1 is logs-only; the compiler whitelist enforces this too. */
enum class DetectionSource(val wire: String) {
    LOGS("logs");

    companion object {
        fun fromWire(value: String?): DetectionSource? = entries.firstOrNull { it.wire == value }
    }
}

/** The two Phase 1 rule types. */
enum class DetectionRuleType(val wire: String) {
    THRESHOLD("threshold"),
    NEW_VALUE("new_value");

    companion object {
        fun fromWire(value: String?): DetectionRuleType? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
data class DetectionRuleResponse(
    val id: Int,
    val name: String,
    val description: String,
    val source: String,
    val filter: String,
    @SerialName("group_by") val groupBy: List<String>,
    @SerialName("window_seconds") val windowSeconds: Int,
    val type: String,
    @SerialName("threshold_count") val thresholdCount: Int? = null,
    val severity: String,
    @SerialName("signal_title") val signalTitle: String,
    @SerialName("signal_message") val signalMessage: String,
    val suppressions: List<String> = emptyList(),
    val enabled: Boolean,
    val tags: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class DetectionRuleListResponse(
    val rules: List<DetectionRuleResponse>,
    @SerialName("total_count") val totalCount: Long,
)

@Serializable
data class CreateDetectionRuleRequest(
    val name: String,
    val description: String = "",
    val source: String = "logs",
    val filter: String = "",
    @SerialName("group_by") val groupBy: List<String> = emptyList(),
    @SerialName("window_seconds") val windowSeconds: Int = DEFAULT_WINDOW_SECONDS,
    val type: String = "threshold",
    @SerialName("threshold_count") val thresholdCount: Int? = null,
    val severity: String = "medium",
    @SerialName("signal_title") val signalTitle: String = "",
    @SerialName("signal_message") val signalMessage: String = "",
    val suppressions: List<String> = emptyList(),
    val enabled: Boolean = false,
    val tags: List<String> = emptyList(),
)

@Serializable
data class UpdateDetectionRuleRequest(
    val name: String? = null,
    val description: String? = null,
    val source: String? = null,
    val filter: String? = null,
    @SerialName("group_by") val groupBy: List<String>? = null,
    @SerialName("window_seconds") val windowSeconds: Int? = null,
    val type: String? = null,
    @SerialName("threshold_count") val thresholdCount: Int? = null,
    val severity: String? = null,
    @SerialName("signal_title") val signalTitle: String? = null,
    @SerialName("signal_message") val signalMessage: String? = null,
    val suppressions: List<String>? = null,
    val enabled: Boolean? = null,
    val tags: List<String>? = null,
)

/** A would-be match sampled by preview / produced by an evaluator, before any signal is written. */
@Serializable
data class DetectionMatchSample(
    @SerialName("group_values") val groupValues: Map<String, String>,
    val count: Long,
)

@Serializable
data class DetectionPreviewResponse(
    @SerialName("match_count") val matchCount: Int,
    val samples: List<DetectionMatchSample>,
    @SerialName("window_seconds") val windowSeconds: Int,
)
