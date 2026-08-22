// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.services

import com.moneat.ai.AiConversations
import com.moneat.ai.AiMessages
import com.moneat.enterprise.ai.llm.LlmMessage
import com.moneat.enterprise.ai.llm.LlmCost
import com.moneat.enterprise.ai.llm.LlmResponse
import com.moneat.enterprise.ai.llm.LlmToolCall
import com.moneat.enterprise.ai.models.AiApprovals
import com.moneat.enterprise.ai.models.AiRunEvidence
import com.moneat.enterprise.ai.models.AiRuns
import com.moneat.enterprise.ai.models.AiToolCalls
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

enum class AiRunStatus(val value: String) {
    PENDING("pending"),
    RUNNING("running"),
    WAITING_FOR_APPROVAL("waiting_for_approval"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
}

enum class AiToolCallStatus(val value: String) {
    PROPOSED("proposed"),
    AWAITING_APPROVAL("awaiting_approval"),
    EXECUTING("executing"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    DENIED("denied"),
    EXPIRED("expired"),
}

enum class AiApprovalStatus(val value: String) {
    PENDING("pending"),
    APPROVED("approved"),
    DENIED("denied"),
    EXPIRED("expired"),
}

data class AiRunSession(
    val internalRunId: Long,
    val runId: String,
    val internalConversationId: Int,
    val conversationId: String,
    val projectId: Long?,
    val status: AiRunStatus,
    val currentRound: Int,
    val messages: List<LlmMessage>,
    val pendingToolCalls: List<StoredAiToolCall>,
    val pendingApproval: StoredAiApproval?,
    val outputContent: String?,
    val created: Boolean,
)

data class StoredAiToolCall(
    val internalId: Long,
    val resourceId: String,
    val runId: Long,
    val providerCallId: String,
    val name: String,
    val arguments: JsonObject?,
    val readOnly: Boolean,
    val status: AiToolCallStatus,
)

data class AiTurnCheckpoint(
    val completed: Boolean,
    val toolCalls: List<StoredAiToolCall>,
)

data class StartAiRun(
    val organizationId: Int,
    val userId: Int,
    val conversationId: String?,
    val runId: String?,
    val projectId: Long?,
    val message: String,
)

data class StoredAiApproval(
    val internalId: Long,
    val resourceId: String,
    val runId: Long,
    val runResourceId: String,
    val conversationResourceId: String,
    val toolCall: StoredAiToolCall,
    val status: AiApprovalStatus,
    val response: String?,
)

sealed interface AiApprovalClaim {
    data class Execute(val approval: StoredAiApproval) : AiApprovalClaim
    data class Denied(val approval: StoredAiApproval) : AiApprovalClaim
    data class Completed(val approval: StoredAiApproval, val response: String) : AiApprovalClaim
    data class InFlight(val approval: StoredAiApproval) : AiApprovalClaim
    data object Expired : AiApprovalClaim
    data object Cancelled : AiApprovalClaim
    data object Missing : AiApprovalClaim
}

interface AiExecutionStore {
    fun beginRun(request: StartAiRun): AiRunSession

    fun resumeRun(internalRunId: Long): AiRunSession

    fun checkpointCompletion(
        session: AiRunSession,
        round: Int,
        response: LlmResponse,
        readOnlyTools: Set<String>,
        cost: LlmCost,
    ): AiTurnCheckpoint

    fun completeRun(runId: Long, content: String)

    fun claimToolExecution(toolCall: StoredAiToolCall): Boolean

    fun recordToolResult(toolCall: StoredAiToolCall, summary: String, isError: Boolean)

    fun createApproval(session: AiRunSession, toolCall: StoredAiToolCall, requestedBy: Int): StoredAiApproval

    fun claimApproval(resourceId: String, organizationId: Int, actorUserId: Int, approve: Boolean): AiApprovalClaim

    fun recordApprovalResponse(approvalId: Long, response: String)

    fun requestCancellation(runResourceId: String, organizationId: Int, actorUserId: Int): Boolean

    fun isCancellationRequested(internalRunId: Long): Boolean

    fun failRun(runId: Long, code: String, message: String)
}

class ExposedAiExecutionStore(
    private val now: () -> kotlin.time.Instant = Clock.System::now,
) : AiExecutionStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun beginRun(request: StartAiRun): AiRunSession = transaction {
        val organizationId = request.organizationId
        val userId = request.userId
        val conversationId = request.conversationId
        val projectId = request.projectId
        val message = request.message
        val requestedRunId = request.runId?.let(::parsePublicUuid) ?: Uuid.random()
        val idempotencyKey = requestedRunId.toString()
        val fingerprint = sha256("${projectId.orEmpty()}|$message")
        val existing = AiRuns
            .selectAll()
            .where {
                (AiRuns.organization_id eq organizationId) and
                    (AiRuns.user_id eq userId) and
                    (AiRuns.idempotency_key eq idempotencyKey)
            }
            .firstOrNull()

        if (existing != null) {
            check(existing[AiRuns.request_fingerprint] == fingerprint) {
                "runId was already used for a different assistant request"
            }
            val existingConversation = AiConversations
                .selectAll()
                .where { AiConversations.id eq existing[AiRuns.conversation_id] }
                .first()
            conversationId?.let { requestedConversationId ->
                check(parsePublicUuid(requestedConversationId) == existingConversation[AiConversations.resource_id]) {
                    "runId belongs to a different conversation"
                }
            }
            return@transaction loadSession(
                existing[AiRuns.id],
                ConversationRecord(
                    existingConversation[AiConversations.id],
                    existingConversation[AiConversations.resource_id],
                ),
                created = false,
            )
        }

        val conversation = resolveConversation(organizationId, userId, conversationId, projectId, message)
        val instant = now()
        val internalRunId = AiRuns.insert {
            it[resource_id] = requestedRunId
            it[organization_id] = organizationId
            it[AiRuns.user_id] = userId
            it[AiRuns.conversation_id] = conversation.internalId
            it[AiRuns.project_id] = projectId
            it[idempotency_key] = idempotencyKey
            it[request_fingerprint] = fingerprint
            it[status] = AiRunStatus.RUNNING.value
            it[started_at] = instant
            it[created_at] = instant
            it[updated_at] = instant
        } get AiRuns.id
        appendMessage(conversation.internalId, internalRunId, LlmMessage("user", message))

        loadSession(internalRunId, conversation, created = true)
    }

    override fun resumeRun(internalRunId: Long): AiRunSession = transaction {
        val run = AiRuns.selectAll().where { AiRuns.id eq internalRunId }.first()
        val conversation = AiConversations
            .selectAll()
            .where { AiConversations.id eq run[AiRuns.conversation_id] }
            .first()
        loadSession(
            internalRunId,
            ConversationRecord(conversation[AiConversations.id], conversation[AiConversations.resource_id]),
            created = false,
        )
    }

    override fun checkpointCompletion(
        session: AiRunSession,
        round: Int,
        response: LlmResponse,
        readOnlyTools: Set<String>,
        cost: LlmCost,
    ): AiTurnCheckpoint = transaction {
        val run = AiRuns.selectAll().where { AiRuns.id eq session.internalRunId }.first()
        if (run[AiRuns.current_round] >= round) {
            return@transaction AiTurnCheckpoint(
                completed = run[AiRuns.status] == AiRunStatus.COMPLETED.value,
                toolCalls = loadToolCalls(session.internalRunId, round),
            )
        }

        val instant = now()
        val completed = response.toolCalls.isEmpty()
        appendMessage(
            session.internalConversationId,
            session.internalRunId,
            LlmMessage(
                role = "assistant",
                content = response.content.ifBlank { null },
                toolCalls = response.toolCalls,
            ),
        )
        AiRuns.update({ AiRuns.id eq session.internalRunId }) {
            it[status] = if (completed) AiRunStatus.COMPLETED.value else AiRunStatus.RUNNING.value
            it[current_round] = round
            it[provider] = response.provider
            it[model] = response.model
            it[input_tokens] = run[AiRuns.input_tokens] + response.inputTokens.coerceAtLeast(0)
            it[output_tokens] = run[AiRuns.output_tokens] + response.outputTokens.coerceAtLeast(0)
            it[AiRuns.cost_usd] = run[AiRuns.cost_usd] + cost.totalCost
            it[cost_metadata] = json.encodeToString(
                buildJsonObject {
                    put("currency", cost.currency)
                    put("lastRound", round)
                    put("lastInputCost", cost.inputCost.toPlainString())
                    put("lastOutputCost", cost.outputCost.toPlainString())
                    put("lastTotalCost", cost.totalCost.toPlainString())
                },
            )
            it[output_content] = response.content.takeIf { content -> completed && content.isNotBlank() }
            it[updated_at] = instant
            if (completed) it[completed_at] = instant
            it[version] = run[AiRuns.version] + 1
        }

        response.toolCalls.forEach { call ->
            AiToolCalls.insert {
                it[resource_id] = Uuid.random()
                it[organization_id] = run[AiRuns.organization_id]
                it[run_id] = session.internalRunId
                it[AiToolCalls.round] = round
                it[provider_call_id] = call.id
                it[tool_name] = call.name
                it[AiToolCalls.arguments] = call.arguments?.let { arguments -> json.encodeToString(arguments) }
                it[arguments_valid] = call.arguments != null
                it[read_only] = call.name in readOnlyTools
                it[status] = AiToolCallStatus.PROPOSED.value
                it[effect_idempotency_key] = "${session.runId}:$round:${call.id}"
                it[created_at] = instant
                it[updated_at] = instant
            }
        }

        AiTurnCheckpoint(
            completed = completed,
            toolCalls = loadToolCalls(session.internalRunId, round),
        )
    }

    override fun completeRun(runId: Long, content: String) {
        transaction {
            val run = AiRuns.selectAll().where { AiRuns.id eq runId }.firstOrNull()
                ?: return@transaction
            val status = AiRunStatus.entries.first { value -> value.value == run[AiRuns.status] }
            if (status in TERMINAL_RUN_STATUSES) return@transaction
            val instant = now()
            appendMessage(
                run[AiRuns.conversation_id],
                runId,
                LlmMessage(role = "assistant", content = content),
            )
            AiRuns.update({ AiRuns.id eq runId }) {
                it[AiRuns.status] = AiRunStatus.COMPLETED.value
                it[output_content] = content
                it[completed_at] = instant
                it[updated_at] = instant
                it[version] = run[AiRuns.version] + 1
            }
        }
    }

    override fun claimToolExecution(toolCall: StoredAiToolCall): Boolean = transaction {
        val row = AiToolCalls.selectAll().where { AiToolCalls.id eq toolCall.internalId }.firstOrNull()
            ?: return@transaction false
        val current = AiToolCallStatus.entries.first { status -> status.value == row[AiToolCalls.status] }
        if (current == AiToolCallStatus.SUCCEEDED || current == AiToolCallStatus.FAILED) {
            return@transaction false
        }
        if (current == AiToolCallStatus.EXECUTING) return@transaction toolCall.readOnly
        if (current != AiToolCallStatus.PROPOSED) return@transaction false
        AiToolCalls.update({ AiToolCalls.id eq toolCall.internalId }) {
            it[status] = AiToolCallStatus.EXECUTING.value
            it[started_at] = now()
            it[updated_at] = now()
        }
        true
    }

    override fun recordToolResult(toolCall: StoredAiToolCall, summary: String, isError: Boolean) {
        transaction {
            val row = AiToolCalls.selectAll().where { AiToolCalls.id eq toolCall.internalId }.firstOrNull()
                ?: return@transaction
            val status = AiToolCallStatus.entries.first { value -> value.value == row[AiToolCalls.status] }
            if (row[AiToolCalls.result_summary] != null) return@transaction
            if (status == AiToolCallStatus.SUCCEEDED || status == AiToolCallStatus.FAILED) return@transaction
            val instant = now()
            val evidenceResourceId = Uuid.random()
            val resultContent = buildJsonObject {
                put("summary", summary)
                put("isError", isError)
                put("toolCallId", toolCall.resourceId)
            }
            AiToolCalls.update({ AiToolCalls.id eq toolCall.internalId }) {
                it[AiToolCalls.status] = when {
                    status == AiToolCallStatus.DENIED -> AiToolCallStatus.DENIED.value
                    status == AiToolCallStatus.EXPIRED -> AiToolCallStatus.EXPIRED.value
                    isError -> AiToolCallStatus.FAILED.value
                    else -> AiToolCallStatus.SUCCEEDED.value
                }
                it[result] = json.encodeToString(resultContent)
                it[result_summary] = summary
                it[AiToolCalls.is_error] = isError
                it[result_audit_event_id] = evidenceResourceId
                it[completed_at] = instant
                it[updated_at] = instant
            }
            val run = AiRuns.selectAll().where { AiRuns.id eq toolCall.runId }.first()
            AiRunEvidence.insert {
                it[resource_id] = evidenceResourceId
                it[organization_id] = run[AiRuns.organization_id]
                it[run_id] = toolCall.runId
                it[evidence_type] = TOOL_OUTCOME_EVIDENCE_TYPE
                it[source_name] = toolCall.name
                it[source_resource_id] = toolCall.resourceId
                it[content] = json.encodeToString(resultContent)
                it[created_at] = instant
            }
            AiApprovals.update({ AiApprovals.tool_call_id eq toolCall.internalId }) {
                it[result_audit_event_id] = evidenceResourceId
                it[updated_at] = instant
            }
            appendMessage(
                run[AiRuns.conversation_id],
                toolCall.runId,
                LlmMessage(role = "tool", content = summary, toolCallId = toolCall.providerCallId),
            )
        }
    }

    override fun createApproval(
        session: AiRunSession,
        toolCall: StoredAiToolCall,
        requestedBy: Int,
    ): StoredAiApproval = transaction {
        val existing = AiApprovals.selectAll().where { AiApprovals.tool_call_id eq toolCall.internalId }.firstOrNull()
        if (existing != null) return@transaction mapApproval(existing)

        val instant = now()
        val proposal = buildProposal(toolCall)
        val approvalId = AiApprovals.insert {
            it[resource_id] = Uuid.random()
            it[organization_id] = organizationIdForRun(session.internalRunId)
            it[run_id] = session.internalRunId
            it[tool_call_id] = toolCall.internalId
            it[AiApprovals.requested_by] = requestedBy
            it[incident_resource_id] = incidentResourceId(toolCall.arguments)
            it[incident_version] = incidentVersion(toolCall.arguments)
            it[proposed_command] = proposal
            it[proposal_sha256] = sha256(proposal)
            it[status] = AiApprovalStatus.PENDING.value
            it[expires_at] = instant + APPROVAL_TTL
            it[created_at] = instant
            it[updated_at] = instant
        } get AiApprovals.id
        AiToolCalls.update({ AiToolCalls.id eq toolCall.internalId }) {
            it[status] = AiToolCallStatus.AWAITING_APPROVAL.value
            it[updated_at] = instant
        }
        AiRuns.update({ AiRuns.id eq session.internalRunId }) {
            it[status] = AiRunStatus.WAITING_FOR_APPROVAL.value
            it[updated_at] = instant
        }
        mapApproval(AiApprovals.selectAll().where { AiApprovals.id eq approvalId }.first())
    }

    override fun claimApproval(
        resourceId: String,
        organizationId: Int,
        actorUserId: Int,
        approve: Boolean,
    ): AiApprovalClaim = transaction {
        val parsedId = runCatching { Uuid.parse(resourceId) }.getOrNull() ?: return@transaction AiApprovalClaim.Missing
        val row = AiApprovals
            .selectAll()
            .where {
                (AiApprovals.resource_id eq parsedId) and
                    (AiApprovals.organization_id eq organizationId)
            }
            .firstOrNull()
            ?: return@transaction AiApprovalClaim.Missing
        val approval = mapApproval(row)
        unavailableApprovalClaim(row, approval)?.let { claim -> return@transaction claim }

        claimApprovalDecision(approval, actorUserId, approve)
    }

    private fun claimApprovalDecision(
        approval: StoredAiApproval,
        actorUserId: Int,
        approve: Boolean,
    ): AiApprovalClaim {
        val instant = now()
        val nextApprovalStatus = if (approve) AiApprovalStatus.APPROVED else AiApprovalStatus.DENIED
        val nextToolStatus = if (approve) AiToolCallStatus.EXECUTING else AiToolCallStatus.DENIED
        val decisionClaimed = AiApprovals.update({
            (AiApprovals.id eq approval.internalId) and
                (AiApprovals.status eq AiApprovalStatus.PENDING.value)
        }) {
            it[status] = nextApprovalStatus.value
            it[decided_by] = actorUserId
            it[decided_at] = instant
            it[updated_at] = instant
        }
        if (decisionClaimed == 0) {
            return existingDecisionClaim(loadApproval(approval.internalId))
        }
        val activeRun = AiRuns.update({
            (AiRuns.id eq approval.runId) and
                (
                    (AiRuns.status eq AiRunStatus.WAITING_FOR_APPROVAL.value) or
                        (AiRuns.status eq AiRunStatus.RUNNING.value)
                )
        }) {
            it[status] = AiRunStatus.RUNNING.value
            it[updated_at] = instant
        }
        if (activeRun == 0) {
            val latestRunStatus = AiRuns.selectAll().where { AiRuns.id eq approval.runId }.first()[AiRuns.status]
            return if (latestRunStatus == AiRunStatus.CANCELLED.value) {
                AiApprovalClaim.Cancelled
            } else {
                AiApprovalClaim.InFlight(loadApproval(approval.internalId))
            }
        }
        AiToolCalls.update({ AiToolCalls.id eq approval.toolCall.internalId }) {
            it[status] = nextToolStatus.value
            it[updated_at] = instant
            if (approve) it[started_at] = instant
        }
        val claimed = loadApproval(approval.internalId)
        return if (approve) AiApprovalClaim.Execute(claimed) else AiApprovalClaim.Denied(claimed)
    }

    override fun recordApprovalResponse(approvalId: Long, response: String) {
        transaction {
            AiApprovals.update({ AiApprovals.id eq approvalId }) {
                it[AiApprovals.response] = response
                it[updated_at] = now()
            }
        }
    }

    override fun requestCancellation(runResourceId: String, organizationId: Int, actorUserId: Int): Boolean =
        transaction {
            val resourceId = runCatching { Uuid.parse(runResourceId) }.getOrNull() ?: return@transaction false
            val row = AiRuns
                .selectAll()
                .where {
                    (AiRuns.resource_id eq resourceId) and
                        (AiRuns.organization_id eq organizationId)
                }
                .firstOrNull()
                ?: return@transaction false
            val status = AiRunStatus.entries.first { value -> value.value == row[AiRuns.status] }
            if (status == AiRunStatus.COMPLETED || status == AiRunStatus.FAILED) return@transaction false
            if (status == AiRunStatus.CANCELLED) return@transaction true
            val instant = now()
            AiRuns.update({ AiRuns.id eq row[AiRuns.id] }) {
                it[AiRuns.status] = AiRunStatus.CANCELLED.value
                it[cancellation_requested_at] = instant
                it[cancellation_requested_by] = actorUserId
                it[completed_at] = instant
                it[updated_at] = instant
            }
            true
        }

    override fun isCancellationRequested(internalRunId: Long): Boolean = transaction {
        AiRuns
            .selectAll()
            .where { AiRuns.id eq internalRunId }
            .firstOrNull()
            ?.let { row ->
                row[AiRuns.status] == AiRunStatus.CANCELLED.value || row[AiRuns.cancellation_requested_at] != null
            }
            ?: true
    }

    override fun failRun(runId: Long, code: String, message: String) {
        transaction {
            val run = AiRuns.selectAll().where { AiRuns.id eq runId }.firstOrNull()
                ?: return@transaction
            val currentStatus = AiRunStatus.entries.first { value -> value.value == run[AiRuns.status] }
            if (currentStatus in TERMINAL_RUN_STATUSES) return@transaction
            val instant = now()
            AiRuns.update({ AiRuns.id eq runId }) {
                it[status] = AiRunStatus.FAILED.value
                it[error_code] = code
                it[error_message] = message
                it[completed_at] = instant
                it[updated_at] = instant
            }
        }
    }

    private fun resolveConversation(
        organizationId: Int,
        userId: Int,
        conversationId: String?,
        projectId: Long?,
        message: String,
    ): ConversationRecord {
        val requestedId = conversationId?.let(::parsePublicUuid)
        if (requestedId != null) {
            val row = AiConversations
                .selectAll()
                .where {
                    (AiConversations.resource_id eq requestedId) and
                        (AiConversations.organization_id eq organizationId) and
                        (AiConversations.user_id eq userId)
                }
                .firstOrNull()
                ?: throw IllegalArgumentException("Conversation not found")
            return ConversationRecord(row[AiConversations.id], row[AiConversations.resource_id])
        }

        val resourceId = Uuid.random()
        val instant = now()
        val internalId = AiConversations.insert {
            it[resource_id] = resourceId
            it[organization_id] = organizationId
            it[AiConversations.user_id] = userId
            it[title] = message.take(MAX_CONVERSATION_TITLE)
            it[AiConversations.project_id] = projectId
            it[channel] = ASSISTANT_CHANNEL
            it[created_at] = instant
            it[updated_at] = instant
        } get AiConversations.id
        return ConversationRecord(internalId, resourceId)
    }

    private fun loadSession(runId: Long, conversation: ConversationRecord, created: Boolean): AiRunSession {
        val row = AiRuns.selectAll().where { AiRuns.id eq runId }.first()
        return AiRunSession(
            internalRunId = runId,
            runId = row[AiRuns.resource_id].toString(),
            internalConversationId = conversation.internalId,
            conversationId = conversation.resourceId.toString(),
            projectId = row[AiRuns.project_id],
            status = AiRunStatus.entries.first { status -> status.value == row[AiRuns.status] },
            currentRound = row[AiRuns.current_round],
            messages = loadMessages(conversation.internalId),
            pendingToolCalls = loadPendingToolCalls(runId, row[AiRuns.current_round]),
            pendingApproval = loadPendingApproval(runId),
            outputContent = row[AiRuns.output_content],
            created = created,
        )
    }

    private fun loadMessages(conversationId: Int): List<LlmMessage> = AiMessages
        .selectAll()
        .where { AiMessages.conversation_id eq conversationId }
        .orderBy(AiMessages.id to SortOrder.ASC)
        .map { row ->
            val rawToolCalls = row[AiMessages.tool_calls]
            LlmMessage(
                role = row[AiMessages.role],
                content = row[AiMessages.content].takeIf(String::isNotBlank),
                toolCallId = row[AiMessages.tool_call_id],
                toolCalls = rawToolCalls
                    ?.let { encoded -> json.decodeFromString<List<LlmToolCall>>(encoded) }
                    .orEmpty(),
            )
        }

    private fun appendMessage(conversationId: Int, runId: Long, message: LlmMessage) {
        val sequence = AiMessages
            .selectAll()
            .where { AiMessages.conversation_id eq conversationId }
            .maxOfOrNull { row -> row[AiMessages.sequence_number] ?: row[AiMessages.id].toLong() }
            ?.plus(1)
            ?: 1L
        AiMessages.insert {
            it[resource_id] = Uuid.random()
            it[conversation_id] = conversationId
            it[AiMessages.run_id] = runId
            it[role] = message.role
            it[content] = message.content.orEmpty()
            it[tool_call_id] = message.toolCallId
            it[tool_calls] = message.toolCalls
                .takeIf(List<LlmToolCall>::isNotEmpty)
                ?.let { calls -> json.encodeToString(calls) }
            it[sequence_number] = sequence
            it[created_at] = now()
        }
        AiConversations.update({ AiConversations.id eq conversationId }) {
            it[updated_at] = now()
            it[state_version] = AiConversations.state_version + 1
        }
    }

    private fun loadToolCalls(runId: Long, round: Int): List<StoredAiToolCall> = AiToolCalls
        .selectAll()
        .where { (AiToolCalls.run_id eq runId) and (AiToolCalls.round eq round) }
        .orderBy(AiToolCalls.id to SortOrder.ASC)
        .map(::mapToolCall)

    private fun loadPendingToolCalls(runId: Long, round: Int): List<StoredAiToolCall> =
        loadToolCalls(runId, round).filter { toolCall ->
            toolCall.status in setOf(
                AiToolCallStatus.PROPOSED,
                AiToolCallStatus.AWAITING_APPROVAL,
                AiToolCallStatus.EXECUTING,
            )
        }

    private fun loadPendingApproval(runId: Long): StoredAiApproval? = AiApprovals
        .selectAll()
        .where { AiApprovals.run_id eq runId }
        .orderBy(AiApprovals.id to SortOrder.DESC)
        .firstOrNull { row ->
            row[AiApprovals.status] == AiApprovalStatus.PENDING.value ||
                (row[AiApprovals.status] == AiApprovalStatus.APPROVED.value && row[AiApprovals.response] == null)
        }
        ?.let(::mapApproval)

    private fun mapToolCall(row: org.jetbrains.exposed.v1.core.ResultRow): StoredAiToolCall {
        val encodedArguments = row[AiToolCalls.arguments]
        val arguments = encodedArguments
            ?.takeIf { row[AiToolCalls.arguments_valid] }
            ?.let { encoded -> runCatching { json.decodeFromString<JsonObject>(encoded) }.getOrNull() }
        return StoredAiToolCall(
            internalId = row[AiToolCalls.id],
            resourceId = row[AiToolCalls.resource_id].toString(),
            runId = row[AiToolCalls.run_id],
            providerCallId = row[AiToolCalls.provider_call_id],
            name = row[AiToolCalls.tool_name],
            arguments = arguments,
            readOnly = row[AiToolCalls.read_only],
            status = AiToolCallStatus.entries.first { status -> status.value == row[AiToolCalls.status] },
        )
    }

    private fun mapApproval(row: org.jetbrains.exposed.v1.core.ResultRow): StoredAiApproval {
        val run = AiRuns.selectAll().where { AiRuns.id eq row[AiApprovals.run_id] }.first()
        val conversation = AiConversations
            .selectAll()
            .where { AiConversations.id eq run[AiRuns.conversation_id] }
            .first()
        val toolCall = AiToolCalls
            .selectAll()
            .where { AiToolCalls.id eq row[AiApprovals.tool_call_id] }
            .first()
        return StoredAiApproval(
            internalId = row[AiApprovals.id],
            resourceId = row[AiApprovals.resource_id].toString(),
            runId = row[AiApprovals.run_id],
            runResourceId = run[AiRuns.resource_id].toString(),
            conversationResourceId = conversation[AiConversations.resource_id].toString(),
            toolCall = mapToolCall(toolCall),
            status = AiApprovalStatus.entries.first { status -> status.value == row[AiApprovals.status] },
            response = row[AiApprovals.response],
        )
    }

    private fun loadApproval(approvalId: Long): StoredAiApproval = mapApproval(
        AiApprovals.selectAll().where { AiApprovals.id eq approvalId }.first(),
    )

    private fun existingDecisionClaim(approval: StoredAiApproval): AiApprovalClaim = approval.response
        ?.let { response -> AiApprovalClaim.Completed(approval, response) }
        ?: AiApprovalClaim.InFlight(approval)

    private fun unavailableApprovalClaim(
        row: org.jetbrains.exposed.v1.core.ResultRow,
        approval: StoredAiApproval,
    ): AiApprovalClaim? {
        val runStatus = AiRuns
            .selectAll()
            .where { AiRuns.id eq approval.runId }
            .first()[AiRuns.status]
        if (runStatus == AiRunStatus.CANCELLED.value) return AiApprovalClaim.Cancelled
        if (approval.status == AiApprovalStatus.PENDING && row[AiApprovals.expires_at] <= now()) {
            expireApproval(row[AiApprovals.id], row[AiApprovals.tool_call_id], row[AiApprovals.run_id])
            return AiApprovalClaim.Expired
        }
        return when (approval.status) {
            AiApprovalStatus.APPROVED, AiApprovalStatus.DENIED -> existingDecisionClaim(approval)
            AiApprovalStatus.EXPIRED -> AiApprovalClaim.Expired
            AiApprovalStatus.PENDING -> null
        }
    }

    private fun expireApproval(approvalId: Long, toolCallId: Long, runId: Long) {
        val instant = now()
        AiApprovals.update({ AiApprovals.id eq approvalId }) {
            it[status] = AiApprovalStatus.EXPIRED.value
            it[updated_at] = instant
        }
        AiToolCalls.update({ AiToolCalls.id eq toolCallId }) {
            it[status] = AiToolCallStatus.EXPIRED.value
            it[updated_at] = instant
        }
        AiRuns.update({ AiRuns.id eq runId }) {
            it[status] = AiRunStatus.FAILED.value
            it[error_code] = "approval_expired"
            it[error_message] = "The proposed tool action expired before approval"
            it[completed_at] = instant
            it[updated_at] = instant
        }
    }

    private fun organizationIdForRun(runId: Long): Int = AiRuns
        .selectAll()
        .where { AiRuns.id eq runId }
        .first()[AiRuns.organization_id]

    private fun buildProposal(toolCall: StoredAiToolCall): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("tool", toolCall.name)
            put("arguments", toolCall.arguments ?: JsonObject(emptyMap()))
            put("effectIdempotencyKey", "${toolCall.runId}:${toolCall.providerCallId}")
        },
    )

    private fun incidentResourceId(arguments: JsonObject?): Uuid? {
        val raw = arguments?.get("incidentId")?.jsonPrimitive?.contentOrNull
            ?: arguments?.get("incident_id")?.jsonPrimitive?.contentOrNull
            ?: return null
        return runCatching { Uuid.parse(raw) }.getOrNull()
    }

    private fun incidentVersion(arguments: JsonObject?): Long? =
        arguments?.get("expectedVersion")?.jsonPrimitive?.longOrNull
            ?: arguments?.get("incidentVersion")?.jsonPrimitive?.longOrNull
            ?: arguments?.get("incident_version")?.jsonPrimitive?.longOrNull

    private fun parsePublicUuid(raw: String): Uuid = runCatching { Uuid.parse(raw.trim()) }
        .getOrElse { throw IllegalArgumentException("Expected an opaque UUID") }

    private fun Long?.orEmpty(): String = this?.toString().orEmpty()

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class ConversationRecord(val internalId: Int, val resourceId: Uuid)

    private companion object {
        const val ASSISTANT_CHANNEL = "assistant"
        const val MAX_CONVERSATION_TITLE = 100
        const val TOOL_OUTCOME_EVIDENCE_TYPE = "tool_outcome"
        val APPROVAL_TTL = 15.minutes
        val TERMINAL_RUN_STATUSES = setOf(
            AiRunStatus.COMPLETED,
            AiRunStatus.FAILED,
            AiRunStatus.CANCELLED,
        )
    }
}
