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
import com.moneat.shared.models.Organizations
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Scheduler-level behavior: a single sweep evaluates enabled rules end-to-end (service → compiler →
 * evaluator → SignalWriter) and the per-org fairness cap bounds how many of a tenant's rules run per
 * tick. The query runner is faked so no ClickHouse is needed.
 */
class DetectionSchedulerTest {
    companion object {
        private var db: Database? = null
    }

    private var orgId: Int = 0

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_detection_sched;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        DetectionSchemaTestSupport.reset()
        orgId = seedOrg("acme")
    }

    @Test
    fun `a sweep evaluates an enabled threshold rule and writes a signal`() = runBlocking {
        seedEnabledThresholdRule()
        val compiler = RuleQueryCompiler({ "moneat" })
        val runner = DetectionQueryRunner(execute = { _ -> """{"g0":"web-01","match_count":42}""" })
        val scheduler = DetectionScheduler(
            ruleService = DetectionRuleService(compiler = compiler, runner = runner),
            compiler = compiler,
            runner = runner,
            thresholdEvaluator = ThresholdEvaluator(compiler, runner),
            newValueEvaluator = NewValueEvaluator(compiler, runner),
        )

        scheduler.evaluateEnabledRules()

        transaction {
            val signals = SecuritySignals.selectAll()
                .where { SecuritySignals.organizationId eq orgId }
                .toList()
            assertEquals(1, signals.size)
            assertEquals("detection-1", signals.single()[SecuritySignals.ruleId])
        }
    }

    @Test
    fun `per-org cap defers rules beyond the limit to a later tick`() {
        val rules = (1..5).map { recordForOrg(id = it, org = orgId) } +
            (6..7).map { recordForOrg(id = it, org = orgId + 1) }
        val capped = capPerOrg(rules, maxPerOrg = 3)
        // org A is capped at 3; org B (only 2 rules) keeps both.
        assertEquals(3, capped.count { it.organizationId == orgId })
        assertEquals(2, capped.count { it.organizationId == orgId + 1 })
    }

    private fun recordForOrg(id: Int, org: Int) = DetectionRuleRecord(
        id = id,
        organizationId = org,
        name = "r$id",
        source = "logs",
        filter = "",
        groupBy = listOf("host"),
        windowSeconds = 300,
        type = DetectionRuleType.THRESHOLD,
        thresholdCount = 5,
        severity = com.moneat.security.signals.SignalSeverity.LOW,
        signalTitle = "",
        signalMessage = "",
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    private fun seedEnabledThresholdRule() {
        transaction {
            DetectionRules.insert {
                it[id] = 1
                it[organizationId] = orgId
                it[name] = "Burst"
                it[ruleSource] = "logs"
                it[filter] = "status:error"
                it[groupBy] = """["host"]"""
                it[windowSeconds] = 300
                it[type] = DetectionRuleType.THRESHOLD.wire
                it[thresholdCount] = 5
                it[severity] = "high"
                it[enabled] = true
                it[createdAt] = Instant.fromEpochMilliseconds(1_700_000_000_000)
                it[updatedAt] = Instant.fromEpochMilliseconds(1_700_000_000_000)
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
