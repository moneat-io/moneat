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

import com.moneat.security.signals.SignalSeverity
import kotlin.time.Instant

/**
 * An immutable snapshot of a detection rule row, decoupled from Exposed so evaluators are easy to
 * construct and unit-test. [organizationId] is the owning org — also the org the compiler injects into
 * the query, so a rule can only ever read its own tenant's logs.
 */
data class DetectionRuleRecord(
    val id: Int,
    val organizationId: Int,
    val name: String,
    val source: String,
    val filter: String,
    val groupBy: List<String>,
    val windowSeconds: Int,
    val type: DetectionRuleType,
    val thresholdCount: Int?,
    val severity: SignalSeverity,
    val signalTitle: String,
    val signalMessage: String,
    val createdAt: Instant,
    val tags: List<String> = emptyList(),
) {
    /** Stable rule identifier used as the signal `rule_id`. */
    fun ruleId(): String = "detection-$id"

    fun compilerInput(): RuleQueryCompiler.RuleInput = RuleQueryCompiler.RuleInput(
        source = source,
        filter = filter,
        groupBy = groupBy,
        windowSeconds = windowSeconds,
        type = type,
        thresholdCount = thresholdCount,
    )
}

/**
 * Renders a rule's signal title/message template against a match's group values. Supports `{column}`
 * and `{{column}}` placeholders; unknown placeholders are left intact. Templates are rule-author text
 * but are never SQL — they only ever reach the Postgres signal row (escaped by Exposed) and the UI.
 */
object DetectionTemplate {
    private val PLACEHOLDER = Regex("""\{\{?([a-zA-Z0-9_.\[\]'-]+)\}?\}""")

    fun render(template: String, groupValues: Map<String, String>): String {
        if (template.isBlank()) return template
        return PLACEHOLDER.replace(template) { match ->
            val key = match.groupValues[1]
            groupValues[key] ?: match.value
        }
    }
}

/**
 * Encodes a rule's group values into one stable, unambiguous string in the rule's declared column
 * order. Group values can legally contain the field/record separators (`|`, `=`), and a missing value
 * must stay distinct from an empty one, so naive `col=value` joins can collapse distinct matches and
 * suppress signals. Each segment escapes `\`, NUL, `|` and `=`; a missing value is encoded as a control-char
 * [MISSING_VALUE_SENTINEL] that escaping can never produce, so no two distinct inputs share a key
 * (an empty value encodes as `col=`, a missing one as `col=<sentinel>`). Same inputs → same key.
 */
internal fun encodeGroupKey(groupBy: List<String>, groupValues: Map<String, String>): String =
    groupBy.joinToString("|") { col ->
        val raw = groupValues[col]
        val value = if (raw == null) MISSING_VALUE_SENTINEL else escapeKeySegment(raw)
        "${escapeKeySegment(col)}=$value"
    }

/** Sentinel for an absent group value: a NUL control char that [escapeKeySegment] never emits, so it
 *  can never collide with an escaped real value (the empty string encodes as ""). */
private const val MISSING_VALUE_SENTINEL = "\u0000"

private fun escapeKeySegment(segment: String): String =
    segment
        .replace("\\", "\\\\")
        .replace(MISSING_VALUE_SENTINEL, "\\0")
        .replace("|", "\\|")
        .replace("=", "\\=")

/** Builds the dedup key from a match's group values in the rule's declared column order. */
internal fun dedupKeyFor(rule: DetectionRuleRecord, groupValues: Map<String, String>): String =
    "${rule.ruleId()}|${encodeGroupKey(rule.groupBy, groupValues)}"
