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

import com.moneat.config.ClickHouseClient
import com.moneat.shared.models.Organizations
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalServiceTest {
    companion object {
        private var db: Database? = null
        private const val ACTOR = 7
        private const val TEXT_PLAIN = "text/plain"
    }

    private val service = SignalService()
    private var orgId: Int = 0
    private var otherOrgId: Int = 0

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_signal_service;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        SignalSchemaTestSupport.reset()
        orgId = seedOrg("acme")
        otherOrgId = seedOrg("globex")
    }

    @AfterTest
    fun teardown() {
        ClickHouseClient.close()
    }

    @Test
    fun `list returns only the calling org's signals filtered and paged`() {
        SignalWriter.upsert(orgId, spec(ruleId = "a", severity = SignalSeverity.HIGH))
        SignalWriter.upsert(orgId, spec(ruleId = "b", severity = SignalSeverity.LOW))
        SignalWriter.upsert(otherOrgId, spec(ruleId = "c", severity = SignalSeverity.CRITICAL))

        val all = service.list(orgId, SignalFilters(), limit = 50, offset = 0)
        assertEquals(2, all.totalCount)
        assertTrue(all.signals.all { it.severity != "critical" })

        val highOnly = service.list(orgId, SignalFilters(severity = "high"), limit = 50, offset = 0)
        assertEquals(1, highOnly.totalCount)
        assertEquals("a", highOnly.signals.single().ruleId)
    }

    @Test
    fun `triage advances status and records an audit row with the actor`() {
        val created = SignalWriter.upsert(orgId, spec(ruleId = "a", severity = SignalSeverity.HIGH))

        val result = service.triage(orgId, created.signalId, ACTOR, TriageRequest(status = "under_review"))

        val ok = assertIs<TriageResult.Ok>(result)
        assertEquals("under_review", ok.signal.status)
        val audit = latestAudit(created.signalId)
        assertEquals(SignalAuditAction.STATUS_CHANGE.wire, audit.action)
        assertEquals(SignalStatus.OPEN.wire, audit.fromStatus)
        assertEquals(SignalStatus.UNDER_REVIEW.wire, audit.toStatus)
        assertEquals(ACTOR, audit.actorUserId)
    }

    @Test
    fun `archiving persists the reason and audits it`() {
        val created = SignalWriter.upsert(orgId, spec(ruleId = "a", severity = SignalSeverity.HIGH))
        service.triage(orgId, created.signalId, ACTOR, TriageRequest(status = "under_review"))

        val result = service.triage(
            orgId,
            created.signalId,
            ACTOR,
            TriageRequest(status = "archived", reason = "false_positive")
        )

        val ok = assertIs<TriageResult.Ok>(result)
        assertEquals("archived", ok.signal.status)
        assertEquals("false_positive", ok.signal.archiveReason)
        val audit = latestAudit(created.signalId)
        assertEquals("false_positive", audit.reason)
        assertEquals(SignalStatus.ARCHIVED.wire, audit.toStatus)
    }

    @Test
    fun `an invalid transition is rejected and leaves status unchanged`() {
        val created = SignalWriter.upsert(orgId, spec(ruleId = "a", severity = SignalSeverity.HIGH))

        val result = service.triage(orgId, created.signalId, ACTOR, TriageRequest(status = "archived"))

        assertIs<TriageResult.Invalid>(result)
        transaction {
            assertEquals(
                SignalStatus.OPEN.wire,
                SecuritySignals.selectAll().where { SecuritySignals.id eq created.signalId }
                    .single()[SecuritySignals.status]
            )
        }
    }

    @Test
    fun `assignment and notes are recorded without a status move`() {
        val created = SignalWriter.upsert(orgId, spec(ruleId = "a", severity = SignalSeverity.HIGH))

        val result = service.triage(
            orgId,
            created.signalId,
            ACTOR,
            TriageRequest(assigneeUserId = 42, note = "mine")
        )

        val ok = assertIs<TriageResult.Ok>(result)
        assertEquals(42, ok.signal.assigneeUserId)
        assertEquals("open", ok.signal.status)
        val actions = allAuditActions(created.signalId)
        assertTrue(actions.contains(SignalAuditAction.ASSIGN.wire))
        assertTrue(actions.contains(SignalAuditAction.NOTE.wire))
        assertTrue(actions.none { it == SignalAuditAction.STATUS_CHANGE.wire })
    }

    @Test
    fun `clear_assignee removes the assignee`() {
        val created = SignalWriter.upsert(orgId, spec(ruleId = "a", severity = SignalSeverity.HIGH))
        service.triage(orgId, created.signalId, ACTOR, TriageRequest(assigneeUserId = 42))

        val result = service.triage(orgId, created.signalId, ACTOR, TriageRequest(clearAssignee = true))

        assertNull(assertIs<TriageResult.Ok>(result).signal.assigneeUserId)
    }

    @Test
    fun `a caller cannot triage another org's signal`() {
        val created = SignalWriter.upsert(orgId, spec(ruleId = "a", severity = SignalSeverity.HIGH))

        val result = service.triage(otherOrgId, created.signalId, ACTOR, TriageRequest(status = "under_review"))

        assertIs<TriageResult.NotFound>(result)
        // The original org's signal is untouched.
        transaction {
            assertEquals(
                SignalStatus.OPEN.wire,
                SecuritySignals.selectAll().where { SecuritySignals.id eq created.signalId }
                    .single()[SecuritySignals.status]
            )
        }
    }

    @Test
    fun `compliance samples filter by framework resource type and resource id`() = runBlocking {
        val created = SignalWriter.upsert(
            orgId,
            complianceSpec(
                entities = mapOf(
                    "framework" to "cis-aws",
                    "resource_type" to "aws_account",
                    "resource" to "display-account",
                    "resource_id" to "acct-123",
                ),
            ),
        )
        val capturedSql = mutableListOf<String>()
        MockHttpServer { exchange ->
            capturedSql.add(exchange.requestBodyText())
            exchange.respond(200, complianceFindingRow(), contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test_db", "default", "")

            val detail = service.get(orgId, created.signalId)

            assertEquals(1, detail?.sampleEvents?.size)
        }

        val sql = capturedSql.single()
        assertTrue(sql.contains("framework = 'cis-aws'"))
        assertTrue(sql.contains("resource_type = 'aws_account'"))
        assertTrue(sql.contains("resource_id = 'acct-123'"))
        assertFalse(sql.contains("display-account"))
    }

    private fun latestAudit(signalId: Int): SignalAuditResponse =
        transaction {
            SecuritySignalAudit.selectAll()
                .where { SecuritySignalAudit.signalId eq signalId }
                .orderBy(SecuritySignalAudit.id to SortOrder.DESC)
                .first()
                .let {
                    SignalAuditResponse(
                        id = it[SecuritySignalAudit.id].value,
                        actorUserId = it[SecuritySignalAudit.actorUserId],
                        action = it[SecuritySignalAudit.action],
                        fromStatus = it[SecuritySignalAudit.fromStatus],
                        toStatus = it[SecuritySignalAudit.toStatus],
                        reason = it[SecuritySignalAudit.reason],
                        note = it[SecuritySignalAudit.note],
                        createdAt = it[SecuritySignalAudit.createdAt].toString()
                    )
                }
        }

    private fun allAuditActions(signalId: Int): List<String> =
        transaction {
            SecuritySignalAudit.selectAll()
                .where { SecuritySignalAudit.signalId eq signalId }
                .map { it[SecuritySignalAudit.action] }
        }

    private fun spec(ruleId: String, severity: SignalSeverity) = SignalSpec(
        source = SignalSource.AGENT_RUNTIME,
        ruleId = ruleId,
        ruleName = "Rule $ruleId",
        severity = severity,
        dedupKey = "$ruleId|host|proc",
        entities = mapOf("host" to "web-01", "process" to "bash"),
        evidenceType = "clickhouse_query",
        evidenceReference = "table=security_events dedup=$ruleId",
        occurredAtMs = 1_700_000_000_000
    )

    private fun complianceSpec(entities: Map<String, String>) = SignalSpec(
        source = SignalSource.AGENT_COMPLIANCE,
        ruleId = "cis-1.1",
        ruleName = "Root MFA",
        severity = SignalSeverity.HIGH,
        dedupKey = "cis-aws|cis-1.1|aws_account|acct-123",
        entities = entities,
        evidenceType = "clickhouse_query",
        evidenceReference = "table=compliance_findings rule_id=cis-1.1",
        occurredAtMs = 1_700_000_000_000,
    )

    private fun complianceFindingRow(): String =
        """{"finding_id":"f1","framework":"cis-aws","rule_id":"cis-1.1","rule_name":"Root MFA",""" +
            """"status":"failed","resource_type":"aws_account","resource_id":"acct-123",""" +
            """"resource_name":"Production","ts":"2026-05-31T00:00:00.000Z"}"""

    private fun seedOrg(name: String): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name
            } get Organizations.id
        }
}
