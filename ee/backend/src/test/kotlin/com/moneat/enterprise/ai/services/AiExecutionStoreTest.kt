// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.services

import com.moneat.ai.AiConversations
import com.moneat.ai.AiMessages
import com.moneat.enterprise.ai.llm.LlmResponse
import com.moneat.enterprise.ai.llm.LlmCost
import com.moneat.enterprise.ai.llm.LlmToolCall
import com.moneat.enterprise.ai.models.AiApprovals
import com.moneat.enterprise.ai.models.AiRunEvidence
import com.moneat.enterprise.ai.models.AiRuns
import com.moneat.enterprise.ai.models.AiToolCalls
import com.moneat.enterprise.sso.support.EnterpriseTestDatabaseHelper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AiExecutionStoreTest {
    private val store = ExposedAiExecutionStore()

    @BeforeEach
    fun resetDatabase() {
        EnterpriseTestDatabaseHelper.resetSchema(
            AiTestUsers,
            AiTestOrganizations,
            AiConversations,
            AiRuns,
            AiMessages,
            AiToolCalls,
            AiRunEvidence,
            AiApprovals,
        )
    }

    @Test
    fun `run retries restore one scoped conversation without duplicating the user message`() {
        val firstActor = seedActor("first")
        val secondActor = seedActor("second")
        val runId = Uuid.random().toString()
        val first = store.beginRun(startRun(firstActor, "Investigate", runId = runId))
        val retryWithoutConversation = store.beginRun(startRun(firstActor, "Investigate", runId = runId))
        val retry = store.beginRun(
            startRun(firstActor, "Investigate", conversationId = first.conversationId, runId = runId),
        )

        assertTrue(first.created)
        assertFalse(retryWithoutConversation.created)
        assertEquals(first.conversationId, retryWithoutConversation.conversationId)
        assertFalse(retry.created)
        assertEquals(1, retry.messages.count { message -> message.role == "user" })
        transaction {
            assertEquals(1, AiConversations.selectAll().count())
        }
        assertFailsWith<IllegalStateException> {
            store.beginRun(startRun(firstActor, "Different", first.conversationId, runId))
        }
        assertFailsWith<IllegalArgumentException> {
            store.beginRun(startRun(secondActor, "Cross-org", first.conversationId))
        }
    }

    @Test
    fun `approval persists exact proposal and becomes idempotent after a response`() {
        val actor = seedActor("approval")
        val incidentId = Uuid.random()
        val session = store.beginRun(startRun(actor, "Resolve incident"))
        val checkpoint = store.checkpointCompletion(
            session = session,
            round = 1,
            response = LlmResponse(
                content = "",
                toolCalls = listOf(
                    LlmToolCall(
                        id = "provider-call",
                        name = "resolve_incident",
                        arguments = JsonObject(
                            mapOf(
                                "incidentId" to JsonPrimitive(incidentId.toString()),
                                "expectedVersion" to JsonPrimitive(7),
                            ),
                        ),
                    ),
                ),
                inputTokens = 12,
                outputTokens = 4,
                model = "test-model",
                provider = "test-provider",
            ),
            readOnlyTools = emptySet(),
            cost = llmCost("0.000004"),
        )
        val approval = store.createApproval(session, checkpoint.toolCalls.single(), actor.userId)

        transaction {
            val row = AiApprovals.selectAll().where { AiApprovals.id eq approval.internalId }.single()
            assertEquals(incidentId, row[AiApprovals.incident_resource_id])
            assertEquals(7, row[AiApprovals.incident_version])
            assertEquals(64, row[AiApprovals.proposal_sha256].length)
            assertTrue(row[AiApprovals.proposed_command].contains("resolve_incident"))
            assertTrue(row[AiApprovals.expires_at] > row[AiApprovals.created_at])
            val run = AiRuns.selectAll().where { AiRuns.id eq session.internalRunId }.single()
            assertEquals(12, run[AiRuns.input_tokens])
            assertEquals(4, run[AiRuns.output_tokens])
            assertEquals(BigDecimal("0.00000400"), run[AiRuns.cost_usd])
            assertTrue(run[AiRuns.cost_metadata].contains("lastTotalCost"))
        }

        val claimed = assertIs<AiApprovalClaim.Execute>(
            store.claimApproval(approval.resourceId, actor.orgId, actor.userId, approve = true),
        )
        assertIs<AiApprovalClaim.InFlight>(
            store.claimApproval(approval.resourceId, actor.orgId, actor.userId, approve = true),
        )
        store.recordToolResult(claimed.approval.toolCall, "resolved", isError = false)
        transaction {
            val tool = AiToolCalls.selectAll().where { AiToolCalls.id eq claimed.approval.toolCall.internalId }.single()
            val auditEventId = assertNotNull(tool[AiToolCalls.result_audit_event_id])
            val evidence = AiRunEvidence.selectAll().where { AiRunEvidence.resource_id eq auditEventId }.single()
            assertEquals("tool_outcome", evidence[AiRunEvidence.evidence_type])
            assertTrue(evidence[AiRunEvidence.content].contains("resolved"))
            val updatedApproval = AiApprovals.selectAll().where { AiApprovals.id eq approval.internalId }.single()
            assertEquals(auditEventId, updatedApproval[AiApprovals.result_audit_event_id])
        }
        store.recordApprovalResponse(approval.internalId, "{\"response\":\"resolved\"}")
        val replay = assertIs<AiApprovalClaim.Completed>(
            store.claimApproval(approval.resourceId, actor.orgId, actor.userId, approve = true),
        )
        assertEquals("{\"response\":\"resolved\"}", replay.response)
    }

    @Test
    fun `new store instance resumes checkpointed messages and pending approval`() {
        val actor = seedActor("restart")
        val session = store.beginRun(startRun(actor, "Create project"))
        val checkpoint = store.checkpointCompletion(
            session,
            1,
            LlmResponse(
                content = "",
                toolCalls = listOf(LlmToolCall("call-1", "create_project", JsonObject(emptyMap()))),
            ),
            emptySet(),
            llmCost(),
        )
        val approval = store.createApproval(session, checkpoint.toolCalls.single(), actor.userId)

        val restored = ExposedAiExecutionStore().resumeRun(session.internalRunId)

        assertEquals(AiRunStatus.WAITING_FOR_APPROVAL, restored.status)
        assertEquals(2, restored.messages.size)
        val restoredApproval = assertNotNull(restored.pendingApproval)
        assertEquals(approval.resourceId, restoredApproval.resourceId)
        assertEquals(AiToolCallStatus.AWAITING_APPROVAL, restored.pendingToolCalls.single().status)
    }

    @Test
    fun `cancellation is scoped and durable`() {
        val actor = seedActor("cancel")
        val other = seedActor("other")
        val session = store.beginRun(startRun(actor, "Long investigation"))

        assertFalse(store.requestCancellation(session.runId, other.orgId, other.userId))
        assertTrue(store.requestCancellation(session.runId, actor.orgId, actor.userId))
        assertTrue(ExposedAiExecutionStore().isCancellationRequested(session.internalRunId))
        assertEquals(AiRunStatus.CANCELLED, ExposedAiExecutionStore().resumeRun(session.internalRunId).status)
    }

    @Test
    fun `expired approval cannot execute and fails the run`() {
        var now = Instant.parse("2026-08-22T00:00:00Z")
        val expiringStore = ExposedAiExecutionStore { now }
        val actor = seedActor("expiry")
        val session = expiringStore.beginRun(startRun(actor, "Mutate"))
        val checkpoint = expiringStore.checkpointCompletion(
            session,
            1,
            LlmResponse(
                content = "",
                toolCalls = listOf(LlmToolCall("call-expiry", "create_project", JsonObject(emptyMap()))),
            ),
            emptySet(),
            llmCost(),
        )
        val approval = expiringStore.createApproval(session, checkpoint.toolCalls.single(), actor.userId)

        now += 16.minutes

        assertIs<AiApprovalClaim.Expired>(
            expiringStore.claimApproval(approval.resourceId, actor.orgId, actor.userId, approve = true),
        )
        assertEquals(AiRunStatus.FAILED, expiringStore.resumeRun(session.internalRunId).status)
    }

    private fun seedActor(slug: String): Actor = transaction {
        val userId = AiTestUsers.insert {
            it[email] = "$slug@example.test"
            it[password_hash] = "x"
            it[name] = slug
        } get AiTestUsers.id
        val orgId = AiTestOrganizations.insert {
            it[name] = "Organization $slug"
            it[AiTestOrganizations.slug] = slug
        } get AiTestOrganizations.id
        Actor(orgId, userId)
    }

    private fun startRun(
        actor: Actor,
        message: String,
        conversationId: String? = null,
        runId: String? = null,
    ) = StartAiRun(
        organizationId = actor.orgId,
        userId = actor.userId,
        conversationId = conversationId,
        runId = runId,
        projectId = null,
        message = message,
    )

    private fun llmCost(total: String = "0") = LlmCost(
        inputCost = BigDecimal.ZERO,
        outputCost = BigDecimal(total),
        totalCost = BigDecimal(total),
    )

    private data class Actor(val orgId: Int, val userId: Int)

    companion object {
        @JvmStatic
        @BeforeAll
        fun connectDatabase() {
            TransactionManager.defaultDatabase = Database.connect(
                url = "jdbc:h2:mem:moneat_ai_runs;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }

        @JvmStatic
        @AfterAll
        fun clearDatabase() {
            TransactionManager.defaultDatabase = null
        }
    }
}

private object AiTestUsers : Table("users") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", 255)
    val password_hash = varchar("password_hash", 255)
    val name = varchar("name", 255).nullable()
    override val primaryKey = PrimaryKey(id)
}

private object AiTestOrganizations : Table("organizations") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255)
    val slug = varchar("slug", 255)
    override val primaryKey = PrimaryKey(id)
}
