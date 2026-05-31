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

import com.moneat.security.signals.SecuritySignals
import com.moneat.security.signals.SignalSeverity
import com.moneat.security.signals.SignalSource
import com.moneat.shared.models.Organizations
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Evaluators against real [com.moneat.security.signals.SignalWriter] + H2, with a stubbed query runner
 * supplying the ClickHouse group rows. Proves: threshold matches become signals (dedup-folding on
 * repeat), new-value warm-up suppresses first-sight values then fires on genuinely new ones, and the
 * compiler injects each rule's owning org.
 */
class DetectionEvaluatorTest {
    companion object {
        private var db: Database? = null
    }

    private var orgId: Int = 0
    private val compiler = RuleQueryCompiler({ "moneat" })

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_detection_eval;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        DetectionSchemaTestSupport.reset()
        orgId = seedOrg("acme")
    }

    private fun fakeRunner(rows: List<DetectionGroupRow>): DetectionQueryRunner {
        // Encode rows as the JSONEachRow the real runner parses, using aliases g0..gN. We know the test
        // rules group by ["host"] so alias g0 ↔ host; encode accordingly per row's single group value.
        return DetectionQueryRunner(execute = { _ ->
            rows.joinToString("\n") { row ->
                val gv = row.groupValues.entries.firstOrNull()?.value ?: ""
                """{"g0":"$gv","match_count":${row.count}}"""
            }
        })
    }

    private fun thresholdRule(count: Int = 5) = DetectionRuleRecord(
        id = 1,
        organizationId = orgId,
        name = "Failed auth burst",
        source = "logs",
        filter = "status:error",
        groupBy = listOf("host"),
        windowSeconds = 300,
        type = DetectionRuleType.THRESHOLD,
        thresholdCount = count,
        severity = SignalSeverity.HIGH,
        signalTitle = "Burst on {host}",
        signalMessage = "{host} exceeded",
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    @Test
    fun `threshold matches become detection signals`() = runBlocking {
        seedRule(thresholdRule())
        val runner = fakeRunner(
            listOf(
                DetectionGroupRow(mapOf("host" to "web-01"), 12),
                DetectionGroupRow(mapOf("host" to "web-02"), 7),
            ),
        )
        val evaluator = ThresholdEvaluator(compiler, runner)

        val matches = evaluator.evaluate(thresholdRule())

        assertEquals(2, matches.size)
        transaction {
            val signals = SecuritySignals.selectAll()
                .where { SecuritySignals.organizationId eq orgId }
                .toList()
            assertEquals(2, signals.size)
            assertTrue(signals.all { it[SecuritySignals.signalSource] == SignalSource.DETECTION.wire })
            assertTrue(signals.all { it[SecuritySignals.ruleId] == "detection-1" })
            assertTrue(signals.any { it[SecuritySignals.dedupKey] == "detection-1|host=web-01" })
        }
    }

    @Test
    fun `a repeat threshold match folds into the open signal instead of duplicating`() = runBlocking {
        seedRule(thresholdRule())
        val runner = fakeRunner(listOf(DetectionGroupRow(mapOf("host" to "web-01"), 12)))
        val evaluator = ThresholdEvaluator(compiler, runner)

        evaluator.evaluate(thresholdRule())
        evaluator.evaluate(thresholdRule())

        transaction {
            val signals = SecuritySignals.selectAll()
                .where { SecuritySignals.organizationId eq orgId }
                .toList()
            assertEquals(1, signals.size, "repeat should fold, not duplicate")
            assertEquals(2, signals.single()[SecuritySignals.sampleCount])
        }
    }

    private fun newValueRule(createdAt: Instant) = DetectionRuleRecord(
        id = 2,
        organizationId = orgId,
        name = "New login country",
        source = "logs",
        filter = "service:auth",
        groupBy = listOf("host"),
        windowSeconds = 300,
        type = DetectionRuleType.NEW_VALUE,
        thresholdCount = null,
        severity = SignalSeverity.MEDIUM,
        signalTitle = "New value {host}",
        signalMessage = "first seen {host}",
        createdAt = createdAt,
    )

    @Test
    fun `new-value warm-up suppresses first-sight values then fires on genuinely new ones`() = runBlocking {
        val justCreated = Clock.System.now()
        seedRule(newValueRule(justCreated))

        // During warm-up: the first sweep learns the baseline and fires nothing.
        val firstSweep = NewValueEvaluator(
            compiler = compiler,
            runner = fakeRunner(listOf(DetectionGroupRow(mapOf("host" to "web-01"), 3))),
            warmupSeconds = 1.days.inWholeSeconds,
            clock = { justCreated },
        ).evaluate(newValueRule(justCreated))
        assertEquals(0, firstSweep.size, "warm-up must suppress first-sight values")

        // After warm-up: a brand-new value fires; the already-seen value does not.
        val afterWarmup = justCreated + 2.days
        val secondSweep = NewValueEvaluator(
            compiler = compiler,
            runner = fakeRunner(
                listOf(
                    DetectionGroupRow(mapOf("host" to "web-01"), 3), // already in baseline
                    DetectionGroupRow(mapOf("host" to "web-99"), 1), // genuinely new
                ),
            ),
            warmupSeconds = 1.days.inWholeSeconds,
            clock = { afterWarmup },
        ).evaluate(newValueRule(justCreated))

        assertEquals(1, secondSweep.size, "only the genuinely-new value should fire")
        assertEquals("web-99", secondSweep.single().groupValues["host"])
        transaction {
            val signal = SecuritySignals.selectAll()
                .where {
                    (SecuritySignals.organizationId eq orgId) and
                        (SecuritySignals.ruleId eq "detection-2")
                }
                .single()
            assertEquals("detection-2|host=web-99", signal[SecuritySignals.dedupKey])
        }
    }

    @Test
    fun `warm-up is anchored to first evaluation not rule creation`() = runBlocking {
        // Rule created long before the warm-up window elapsed, but enabled and first evaluated only now.
        // Warm-up must restart at this first run, so the first sweep suppresses every unseen group
        // instead of bursting alerts just because createdAt is old.
        val createdLongAgo = Clock.System.now() - 30.days
        seedRule(newValueRule(createdLongAgo))
        val firstRun = Clock.System.now()

        val store = PostgresDetectionBaselineStore()
        val firstSweep = NewValueEvaluator(
            compiler = compiler,
            runner = fakeRunner(listOf(DetectionGroupRow(mapOf("host" to "web-01"), 3))),
            baseline = store,
            warmupSeconds = 1.days.inWholeSeconds,
            clock = { firstRun },
        ).evaluate(newValueRule(createdLongAgo))
        assertEquals(0, firstSweep.size, "first evaluation must warm up even when createdAt is old")
        assertEquals(firstRun, store.warmupStartedAt(2), "warm-up marker is stamped on first run")

        // After the window has elapsed *from first evaluation*, a genuinely-new value fires.
        val afterWarmup = firstRun + 2.days
        val secondSweep = NewValueEvaluator(
            compiler = compiler,
            runner = fakeRunner(listOf(DetectionGroupRow(mapOf("host" to "web-02"), 1))),
            baseline = store,
            warmupSeconds = 1.days.inWholeSeconds,
            clock = { afterWarmup },
        ).evaluate(newValueRule(createdLongAgo))
        assertEquals(1, secondSweep.size, "post-warm-up new values fire")
        assertEquals("web-02", secondSweep.single().groupValues["host"])
    }

    private fun seedRule(rule: DetectionRuleRecord) {
        transaction {
            DetectionRules.insert {
                it[id] = rule.id
                it[organizationId] = rule.organizationId
                it[name] = rule.name
                it[ruleSource] = rule.source
                it[filter] = rule.filter
                it[groupBy] = """["host"]"""
                it[windowSeconds] = rule.windowSeconds
                it[type] = rule.type.wire
                it[thresholdCount] = rule.thresholdCount
                it[severity] = rule.severity.wire
                it[signalTitle] = rule.signalTitle
                it[signalMessage] = rule.signalMessage
                it[enabled] = true
                it[createdAt] = rule.createdAt
                it[updatedAt] = rule.createdAt
            }
        }
    }

    private fun seedOrg(name: String): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name
            } get Organizations.id
        }
}
