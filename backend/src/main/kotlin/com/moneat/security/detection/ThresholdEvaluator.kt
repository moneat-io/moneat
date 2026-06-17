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

import com.moneat.security.signals.SignalOutcome
import com.moneat.security.signals.SignalSource
import com.moneat.security.signals.SignalSpec
import com.moneat.security.signals.SignalWriter
import mu.KotlinLogging
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Threshold rule type: `count() >= N` per group over the window. The compiler emits the GROUP BY +
 * `HAVING count() >= threshold`, so every row the query returns is already a match; this evaluator
 * folds each into a signal via [SignalWriter] (dedup by rule + group values).
 */
class ThresholdEvaluator(
    private val compiler: RuleQueryCompiler,
    private val runner: DetectionQueryRunner = DetectionQueryRunner(),
    private val upsert: (Int, SignalSpec) -> SignalOutcome = SignalWriter::upsert,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    /** Evaluate [rule], upserting one signal per group over threshold. Returns the matches found. */
    suspend fun evaluate(rule: DetectionRuleRecord): List<DetectionGroupRow> {
        // The compiler injects org scoping from the rule's owning org; the author cannot influence it.
        val compiled = compiler.compile(rule.organizationId, rule.compilerInput())
        val rows = runner.run(compiled)
        rows.forEach { row -> writeSignal(rule, compiled, row) }
        if (rows.isNotEmpty()) {
            logger.info { "Threshold rule ${rule.id} produced ${rows.size} match(es)" }
        }
        return rows
    }

    private fun writeSignal(rule: DetectionRuleRecord, compiled: CompiledRuleQuery, row: DetectionGroupRow) {
        val spec = SignalSpec(
            source = SignalSource.DETECTION,
            ruleId = rule.ruleId(),
            ruleName = rule.name,
            severity = rule.severity,
            dedupKey = dedupKeyFor(rule, row.groupValues),
            entities = row.groupValues,
            evidenceType = "clickhouse_query",
            evidenceReference = "${compiled.evidenceDescriptor} match=count>=${rule.thresholdCount}",
            occurredAtMs = now(),
            tags = rule.tags,
        )
        upsert(rule.organizationId, spec)
    }
}
