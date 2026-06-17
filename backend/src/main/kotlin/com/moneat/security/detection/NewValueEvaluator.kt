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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * New-value rule type: a group-by value not previously seen is a match. The baseline is a per-`(rule,
 * group_key)` seen-set in [DetectionBaselineStore]; a configurable **warm-up** suppresses signals while
 * the baseline is learned, so the rule does not alert on every value on day one. Values appearing after
 * warm-up that are absent from the baseline produce a signal, then join the baseline. Baseline entries
 * older than the retention window are evicted to bound state growth.
 *
 * Warm-up is anchored to the rule's **first real evaluation** (activation), not its creation time: a
 * rule created disabled and enabled long after the window has elapsed would otherwise be `warm` on its
 * very first run and alert on every unseen group at once. On the first evaluation we record a per-rule
 * warm-up-start marker (a sentinel row in [DetectionBaselineStore]) and treat the rule as still warming;
 * `warm` becomes true only once `now >= warmupStart + window`. [DetectionRuleService.preview] mirrors
 * this so a freshly created rule's preview matches what the scheduler would emit.
 *
 * The grouping query is compiled by [RuleQueryCompiler] with the rule's owning org injected, so the
 * baseline only ever reflects this tenant's logs.
 */
class NewValueEvaluator(
    private val compiler: RuleQueryCompiler,
    private val runner: DetectionQueryRunner = DetectionQueryRunner(),
    private val baseline: DetectionBaselineStore = PostgresDetectionBaselineStore(),
    private val upsert: (Int, SignalSpec) -> SignalOutcome = SignalWriter::upsert,
    private val warmupSeconds: Long = DEFAULT_WARMUP_SECONDS,
    private val baselineRetentionSeconds: Long = DEFAULT_BASELINE_RETENTION_SECONDS,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    companion object {
        const val DEFAULT_WARMUP_SECONDS: Long = 24 * 60 * 60
        const val DEFAULT_BASELINE_RETENTION_SECONDS: Long = 30L * 24 * 60 * 60
    }

    /** Evaluate [rule]: learn the baseline, then emit signals for genuinely new values post warm-up. */
    suspend fun evaluate(rule: DetectionRuleRecord): List<DetectionGroupRow> {
        val compiled = compiler.compile(rule.organizationId, rule.compilerInput())
        val rows = runner.run(compiled)
        val known = baseline.knownKeys(rule.id)
        val now = clock()
        // Anchor warm-up to first activation, not rule creation: stamp the marker on the first run and
        // treat the rule as still warming on that run, so a long-dormant rule cannot alert on enable.
        val warmupStart = warmupStart(rule, now)
        val warm = isWarm(now, warmupStart)

        val matches = mutableListOf<DetectionGroupRow>()
        rows.forEach { row ->
            val key = baselineKey(rule, row)
            val isNew = key !in known
            baseline.record(rule.id, rule.organizationId, key, now)
            if (isNew && warm) {
                writeSignal(rule, compiled, row)
                matches += row
            }
        }
        baseline.evictOlderThan(rule.id, now - baselineRetentionSeconds.seconds)
        if (matches.isNotEmpty()) {
            logger.info { "New-value rule ${rule.id} org ${rule.organizationId}: ${matches.size} new value(s)" }
        }
        return matches
    }

    /** Warm-up start for [rule]: the stored first-evaluation marker, stamped to [now] on the first run. */
    private fun warmupStart(rule: DetectionRuleRecord, now: Instant): Instant {
        baseline.warmupStartedAt(rule.id)?.let { return it }
        baseline.markWarmupStarted(rule.id, rule.organizationId, now)
        return now
    }

    /** Warm-up has elapsed once [now] reaches [warmupStart] + the configured window. */
    private fun isWarm(now: Instant, warmupStart: Instant): Boolean =
        now >= (warmupStart + warmupSeconds.seconds)

    private fun baselineKey(rule: DetectionRuleRecord, row: DetectionGroupRow): String =
        encodeGroupKey(rule.groupBy, row.groupValues)

    private fun writeSignal(rule: DetectionRuleRecord, compiled: CompiledRuleQuery, row: DetectionGroupRow) {
        val spec = SignalSpec(
            source = SignalSource.DETECTION,
            ruleId = rule.ruleId(),
            ruleName = rule.name,
            severity = rule.severity,
            dedupKey = dedupKeyFor(rule, row.groupValues),
            entities = row.groupValues,
            evidenceType = "clickhouse_query",
            evidenceReference = "${compiled.evidenceDescriptor} match=new_value",
            occurredAtMs = clock().toEpochMilliseconds(),
            tags = rule.tags,
        )
        upsert(rule.organizationId, spec)
    }
}
