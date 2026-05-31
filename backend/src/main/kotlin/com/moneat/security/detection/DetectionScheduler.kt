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

import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.shared.services.TaskLock
import com.moneat.utils.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Background job that evaluates enabled detection rules on a cadence under a [TaskLock] (ShedLock), so
 * only one node runs the sweep per tick. Each rule is dispatched to the [ThresholdEvaluator] or
 * [NewValueEvaluator]; both compile through [RuleQueryCompiler] (org injection + caps) before touching
 * ClickHouse. A **per-org cap** on rules evaluated per tick prevents one tenant from saturating the
 * shared cluster; remaining rules are picked up on subsequent ticks. Modeled on
 * [com.moneat.monitor.services.MonitorAlertService].
 */
class DetectionScheduler(
    private val ruleService: DetectionRuleService = DetectionRuleService(),
    compiler: RuleQueryCompiler = RuleQueryCompiler({ ClickHouseClient.getDatabase() }),
    runner: DetectionQueryRunner = DetectionQueryRunner(),
    private val thresholdEvaluator: ThresholdEvaluator = ThresholdEvaluator(compiler, runner),
    private val newValueEvaluator: NewValueEvaluator = NewValueEvaluator(compiler, runner),
) {
    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 60
        private const val ENV_INTERVAL_SECONDS = "DETECTION_SCHEDULER_INTERVAL_SECONDS"
        private const val ENV_PER_ORG_MAX_RULES = "DETECTION_PER_ORG_MAX_RULES_PER_TICK"
        private const val DEFAULT_PER_ORG_MAX_RULES = 25
        private const val LOCK_NAME = "detection-rule-evaluation"
        private const val LOCK_SLACK_SECONDS = 5
    }

    private var evaluationJob: Job? = null

    private val intervalSeconds: Int
        get() = EnvConfig.get(ENV_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS.toString())
            .toIntOrNull()?.coerceAtLeast(RuleQueryCompiler.MIN_WINDOW_SECONDS) ?: DEFAULT_INTERVAL_SECONDS

    private val perOrgMaxRules: Int
        get() = EnvConfig.get(ENV_PER_ORG_MAX_RULES, DEFAULT_PER_ORG_MAX_RULES.toString())
            .toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_PER_ORG_MAX_RULES

    fun start(scope: CoroutineScope) {
        logger.info { "Starting DetectionScheduler (interval=${intervalSeconds}s)" }
        evaluationJob = scope.launch {
            while (isActive) {
                TaskLock.tryWithLock(
                    LOCK_NAME,
                    lockAtMostFor = 5.minutes,
                    lockAtLeastFor = (intervalSeconds - LOCK_SLACK_SECONDS).coerceAtLeast(0).seconds,
                ) {
                    evaluateEnabledRules()
                }
                delay(intervalSeconds.seconds)
            }
        }
    }

    fun stop() {
        logger.info { "Stopping DetectionScheduler" }
        evaluationJob?.cancel()
        evaluationJob = null
    }

    /** One sweep: load enabled rules, fairly cap per org, and evaluate each. Public for tests. */
    suspend fun evaluateEnabledRules() {
        val rules = suspendRunCatching { ruleService.loadEnabledRules() }
            .getOrElse { e ->
                logger.error(e) { "Failed to load enabled detection rules" }
                emptyList()
            }
        val capped = capPerOrg(rules, perOrgMaxRules)
        logger.debug { "Evaluating ${capped.size}/${rules.size} enabled detection rules this tick" }
        for (rule in capped) {
            suspendRunCatching { evaluateRule(rule) }
                .getOrElse { e -> logger.error(e) { "Error evaluating detection rule ${rule.id}" } }
        }
    }

    private suspend fun evaluateRule(rule: DetectionRuleRecord) {
        when (rule.type) {
            DetectionRuleType.THRESHOLD -> thresholdEvaluator.evaluate(rule)
            DetectionRuleType.NEW_VALUE -> newValueEvaluator.evaluate(rule)
        }
    }
}

/**
 * Caps the rules evaluated per organization to [maxPerOrg] so a tenant with many rules cannot crowd
 * out others or saturate ClickHouse in a single tick. Order within an org is preserved (the query
 * returns them deterministically), so the remainder is simply deferred to the next tick.
 */
internal fun capPerOrg(rules: List<DetectionRuleRecord>, maxPerOrg: Int): List<DetectionRuleRecord> {
    val perOrgCount = HashMap<Int, Int>()
    return rules.filter { rule ->
        val count = perOrgCount.getOrDefault(rule.organizationId, 0)
        if (count >= maxPerOrg) {
            false
        } else {
            perOrgCount[rule.organizationId] = count + 1
            true
        }
    }
}
