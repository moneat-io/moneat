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

package com.moneat.security.signals

import com.moneat.shared.models.Organizations
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class SignalWriterTest {
    companion object {
        private var db: Database? = null
    }

    private var orgId: Int = 0
    private var otherOrgId: Int = 0

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_signal_writer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        SignalSchemaTestSupport.reset()
        orgId = seedOrg("acme")
        otherOrgId = seedOrg("globex")
    }

    @Test
    fun `first occurrence creates an open signal with one evidence row`() {
        val outcome = SignalWriter.upsert(orgId, spec(severity = SignalSeverity.MEDIUM))

        assertIs<SignalOutcome.Created>(outcome)
        transaction {
            val row = SecuritySignals.selectAll().where { SecuritySignals.id eq outcome.signalId }.single()
            assertEquals(SignalStatus.OPEN.wire, row[SecuritySignals.status])
            assertEquals(1, row[SecuritySignals.sampleCount])
            assertEquals("medium", row[SecuritySignals.severity])
            assertEquals(
                1,
                SecuritySignalEvidence.selectAll()
                    .where { SecuritySignalEvidence.signalId eq outcome.signalId }.count()
            )
        }
    }

    @Test
    fun `repeat within an open signal folds in and increments sample_count`() {
        val first = SignalWriter.upsert(orgId, spec(severity = SignalSeverity.MEDIUM))
        val second = SignalWriter.upsert(orgId, spec(severity = SignalSeverity.MEDIUM))

        assertIs<SignalOutcome.Updated>(second)
        assertEquals(first.signalId, second.signalId)
        transaction {
            val row = SecuritySignals.selectAll().where { SecuritySignals.id eq first.signalId }.single()
            assertEquals(2, row[SecuritySignals.sampleCount])
            assertEquals(
                2,
                SecuritySignalEvidence.selectAll()
                    .where { SecuritySignalEvidence.signalId eq first.signalId }.count()
            )
        }
        // No new signal was created.
        transaction { assertEquals(1, SecuritySignals.selectAll().count()) }
    }

    @Test
    fun `a higher-severity repeat escalates and raises severity to the max`() {
        val first = SignalWriter.upsert(orgId, spec(severity = SignalSeverity.LOW))
        val escalated = SignalWriter.upsert(orgId, spec(severity = SignalSeverity.CRITICAL))

        assertIs<SignalOutcome.Escalated>(escalated)
        assertEquals(first.signalId, escalated.signalId)
        assertEquals(SignalSeverity.CRITICAL, escalated.severity)
        transaction {
            assertEquals(
                "critical",
                SecuritySignals.selectAll().where { SecuritySignals.id eq first.signalId }
                    .single()[SecuritySignals.severity]
            )
        }
    }

    @Test
    fun `a lower-severity repeat does not lower severity and is only an update`() {
        SignalWriter.upsert(orgId, spec(severity = SignalSeverity.HIGH))
        val outcome = SignalWriter.upsert(orgId, spec(severity = SignalSeverity.LOW))

        assertIs<SignalOutcome.Updated>(outcome)
        assertEquals(SignalSeverity.HIGH, outcome.severity)
    }

    @Test
    fun `once archived a new occurrence forms a fresh signal`() {
        val first = SignalWriter.upsert(orgId, spec(severity = SignalSeverity.MEDIUM))
        transaction {
            SecuritySignals.update({ SecuritySignals.id eq first.signalId }) {
                it[status] = SignalStatus.ARCHIVED.wire
                it[archiveReason] = ArchiveReason.FALSE_POSITIVE.wire
            }
        }

        val second = SignalWriter.upsert(orgId, spec(severity = SignalSeverity.MEDIUM))

        assertIs<SignalOutcome.Created>(second)
        assertNotEquals(first.signalId, second.signalId)
        transaction { assertEquals(2, SecuritySignals.selectAll().count()) }
    }

    @Test
    fun `signals are isolated per organization`() {
        val mine = SignalWriter.upsert(orgId, spec(severity = SignalSeverity.MEDIUM))
        val theirs = SignalWriter.upsert(otherOrgId, spec(severity = SignalSeverity.MEDIUM))

        // Identical rule + dedup_key in two orgs are two distinct signals, never folded together.
        assertIs<SignalOutcome.Created>(theirs)
        assertNotEquals(mine.signalId, theirs.signalId)
        transaction {
            assertEquals(
                1,
                SecuritySignals.selectAll()
                    .where { (SecuritySignals.organizationId eq orgId) and (SecuritySignals.ruleId eq "cws-1") }
                    .count()
            )
            assertEquals(
                1,
                SecuritySignals.selectAll()
                    .where { (SecuritySignals.organizationId eq otherOrgId) and (SecuritySignals.ruleId eq "cws-1") }
                    .count()
            )
        }
    }

    private fun spec(severity: SignalSeverity) = SignalSpec(
        source = SignalSource.AGENT_RUNTIME,
        ruleId = "cws-1",
        ruleName = "Suspicious exec",
        severity = severity,
        dedupKey = "cws-1|web-01|bash",
        entities = mapOf("host" to "web-01", "process" to "bash"),
        evidenceType = "clickhouse_query",
        evidenceReference = "table=security_events dedup=cws-1|web-01|bash",
        occurredAtMs = 1_700_000_000_000
    )

    private fun seedOrg(name: String): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name
            } get Organizations.id
        }
}
