// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.services

import com.moneat.config.ClickHouseClient
import com.moneat.enterprise.ai.llm.CostRegistry
import com.moneat.enterprise.ai.llm.LlmConfig
import com.moneat.enterprise.ai.llm.LlmMessage
import com.moneat.enterprise.ai.llm.LlmProvider
import com.moneat.enterprise.ai.llm.LlmProviderFactory
import com.moneat.enterprise.ai.llm.LlmResponse
import com.moneat.enterprise.ai.llm.LlmTool
import com.moneat.enterprise.ai.llm.LlmToolCall
import com.moneat.enterprise.ai.models.AiAssistantConfirmRequest
import com.moneat.enterprise.ai.models.AiAssistantConfirmResponse
import com.moneat.enterprise.ai.models.AssistantConfirmationNeededEvent
import com.moneat.enterprise.ai.models.AssistantDoneEvent
import com.moneat.enterprise.ai.models.AssistantResponseEvent
import com.moneat.enterprise.ai.models.AssistantToolInvokingEvent
import com.moneat.enterprise.ai.models.AssistantToolResultEvent
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.mcp.protocol.ToolDefinition
import com.moneat.utils.ClickHouseSqlUtils
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import java.io.Writer
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}
private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
private const val ASSISTANT_MAX_TOKENS = 2_048
private const val ASSISTANT_TEMPERATURE = 0.2
private const val NANOS_PER_MILLI = 1_000_000.0

data class AiAssistantStreamCommand(
    val userId: Int,
    val organizationId: Int,
    val message: String,
    val conversationId: String?,
    val runId: String?,
    val projectId: Long?,
    val userTimezone: String?,
)

class AiAssistantService(
    private val toolRegistry: McpToolRegistry,
    private val llmProvider: LlmProvider = LlmProviderFactory.create(),
    private val executionStore: AiExecutionStore = ExposedAiExecutionStore(),
) {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val conversationLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun streamAssistant(
        writer: Writer,
        command: AiAssistantStreamCommand,
    ): String {
        val normalizedMessage = command.message.trim()
        if (normalizedMessage.isBlank()) {
            throw IllegalArgumentException("Message cannot be empty")
        }

        val session = executionStore.beginRun(
            StartAiRun(
                organizationId = command.organizationId,
                userId = command.userId,
                conversationId = command.conversationId,
                runId = command.runId,
                projectId = command.projectId,
                message = normalizedMessage,
            ),
        )
        val mutex = conversationLocks.computeIfAbsent(session.conversationId) { Mutex() }
        return mutex.withLock {
            if (session.status == AiRunStatus.COMPLETED) {
                sendCompletedResponse(writer, session.outputContent.orEmpty(), session)
                return@withLock session.conversationId
            }
            if (session.status == AiRunStatus.CANCELLED) {
                sendCompletedResponse(writer, CANCELLED_RESPONSE, session)
                return@withLock session.conversationId
            }

            val state = ConversationState(
                id = session.conversationId,
                run = session,
                projectId = session.projectId,
                messages = session.messages.toMutableList(),
            )
            refreshTimeContext(state, command.userTimezone)
            val callbacks = streamCallbacks(writer, state)

            val outcome = try {
                recoverPendingWork(state, command.userId, command.organizationId, callbacks)
                    ?: runAssistantLoop(state, command.userId, command.organizationId, callbacks)
            } catch (error: Exception) {
                executionStore.failRun(session.internalRunId, "assistant_failed", error.message.orEmpty())
                throw error
            }

            when (outcome) {
                is LoopOutcome.Completed -> {
                    val chunks = outcome.response.chunked(RESPONSE_CHUNK_SIZE).ifEmpty { listOf("") }
                    chunks.forEach { chunk ->
                        sendSse(
                            writer,
                            json.encodeToString(
                                AssistantResponseEvent.serializer(),
                                AssistantResponseEvent(content = chunk),
                            ),
                        )
                    }
                }
                is LoopOutcome.ConfirmationNeeded -> {
                    logger.info {
                        "Assistant paused for confirmation: ${outcome.tool} in conversation ${state.id}"
                    }
                }
            }

            sendSse(
                writer,
                json.encodeToString(
                    AssistantDoneEvent.serializer(),
                    AssistantDoneEvent(conversationId = state.id, runId = session.runId),
                ),
            )

            state.id
        }
    }

    suspend fun confirmPendingAction(
        userId: Int,
        orgId: Int,
        request: AiAssistantConfirmRequest,
    ): AiAssistantConfirmResponse {
        val claim = executionStore.claimApproval(request.requestId, orgId, userId, request.approve)
        if (claim is AiApprovalClaim.Completed) {
            return json.decodeFromString(AiAssistantConfirmResponse.serializer(), claim.response)
        }
        require(claim != AiApprovalClaim.Missing) { "Pending confirmation request not found" }
        require(claim != AiApprovalClaim.Expired) { "Pending confirmation request expired" }
        check(claim != AiApprovalClaim.Cancelled) { "Assistant run was cancelled" }
        check(claim !is AiApprovalClaim.InFlight) {
            "Approved action is already executing; it will not be repeated"
        }

        val approval = when (claim) {
            is AiApprovalClaim.Execute -> claim.approval
            is AiApprovalClaim.Denied -> claim.approval
        }
        val mutex = conversationLocks.computeIfAbsent(approval.conversationResourceId) { Mutex() }
        return mutex.withLock {
            val toolSummary = if (claim is AiApprovalClaim.Execute) {
                check(!executionStore.isCancellationRequested(approval.runId)) {
                    "Assistant run was cancelled before the approved action could execute"
                }
                val result = executeTool(
                    toolName = approval.toolCall.name,
                    args = checkNotNull(approval.toolCall.arguments),
                    userId = userId,
                    orgId = orgId,
                    sessionId = approval.conversationResourceId,
                )
                val summary = summarizeToolResult(result)
                executionStore.recordToolResult(approval.toolCall, summary, result.isError)
                summary
            } else {
                val summary = "User denied execution of ${approval.toolCall.name}."
                executionStore.recordToolResult(approval.toolCall, summary, false)
                summary
            }

            val session = executionStore.resumeRun(approval.runId)
            val state = ConversationState(
                id = session.conversationId,
                run = session,
                projectId = session.projectId,
                messages = session.messages.toMutableList(),
            )
            refreshTimeContext(state, null)

            val outcome = runAssistantLoop(state = state, userId = userId, orgId = orgId)
            val response = when (outcome) {
                is LoopOutcome.Completed -> {
                    AiAssistantConfirmResponse(
                        conversationId = approval.conversationResourceId,
                        runId = approval.runResourceId,
                        requestId = approval.resourceId,
                        approved = request.approve,
                        tool = approval.toolCall.name,
                        toolSummary = toolSummary,
                        response = outcome.response,
                    )
                }
                is LoopOutcome.ConfirmationNeeded -> {
                    AiAssistantConfirmResponse(
                        conversationId = approval.conversationResourceId,
                        runId = approval.runResourceId,
                        requestId = approval.resourceId,
                        approved = request.approve,
                        tool = approval.toolCall.name,
                        toolSummary = toolSummary,
                        response = "",
                        nextRequestId = outcome.requestId,
                    )
                }
            }
            executionStore.recordApprovalResponse(approval.internalId, json.encodeToString(response))
            response
        }
    }

    private fun streamCallbacks(writer: Writer, state: ConversationState): AssistantCallbacks = AssistantCallbacks(
        onToolInvoking = { tool, args ->
            sendSse(
                writer,
                json.encodeToString(
                    AssistantToolInvokingEvent.serializer(),
                    AssistantToolInvokingEvent(tool = tool, args = args),
                ),
            )
        },
        onToolResult = { tool, summary, isError ->
            sendSse(
                writer,
                json.encodeToString(
                    AssistantToolResultEvent.serializer(),
                    AssistantToolResultEvent(tool = tool, summary = summary, isError = isError),
                ),
            )
        },
        onConfirmationNeeded = { requestId, tool, args ->
            sendSse(
                writer,
                json.encodeToString(
                    AssistantConfirmationNeededEvent.serializer(),
                    AssistantConfirmationNeededEvent(
                        requestId = requestId,
                        conversationId = state.id,
                        runId = state.run.runId,
                        tool = tool,
                        args = args,
                    ),
                ),
            )
        },
    )

    private fun sendCompletedResponse(writer: Writer, response: String, session: AiRunSession) {
        response.chunked(RESPONSE_CHUNK_SIZE).ifEmpty { listOf("") }.forEach { chunk ->
            sendSse(
                writer,
                json.encodeToString(
                    AssistantResponseEvent.serializer(),
                    AssistantResponseEvent(content = chunk),
                ),
            )
        }
        sendSse(
            writer,
            json.encodeToString(
                AssistantDoneEvent.serializer(),
                AssistantDoneEvent(conversationId = session.conversationId, runId = session.runId),
            ),
        )
    }

    private suspend fun recoverPendingWork(
        state: ConversationState,
        userId: Int,
        orgId: Int,
        callbacks: AssistantCallbacks,
    ): LoopOutcome? {
        state.run.pendingApproval?.let { approval ->
            val arguments = approval.toolCall.arguments ?: JsonObject(emptyMap())
            callbacks.onConfirmationNeeded(approval.resourceId, approval.toolCall.name, arguments)
            return LoopOutcome.ConfirmationNeeded(approval.resourceId, approval.toolCall.name, arguments)
        }

        val definitions = toolRegistry.listTools().associateBy(ToolDefinition::name)
        for (toolCall in state.run.pendingToolCalls) {
            val arguments = toolCall.arguments
            if (arguments == null) {
                val summary = "Invalid arguments for MCP tool: ${toolCall.name}"
                executionStore.recordToolResult(toolCall, summary, true)
                state.messages.add(LlmMessage("tool", summary, toolCall.providerCallId))
                callbacks.onToolResult(toolCall.name, summary, true)
                continue
            }
            val definition = definitions[toolCall.name]
            if (definition == null) {
                val summary = "Unknown MCP tool: ${toolCall.name}"
                executionStore.recordToolResult(toolCall, summary, true)
                state.messages.add(LlmMessage("tool", summary, toolCall.providerCallId))
                callbacks.onToolResult(toolCall.name, summary, true)
                continue
            }
            if (!definition.readOnly) {
                if (toolCall.status == AiToolCallStatus.EXECUTING) {
                    error("Approved tool outcome is unknown after interruption; refusing to repeat the mutation")
                }
                val approval = executionStore.createApproval(state.run, toolCall, userId)
                callbacks.onConfirmationNeeded(approval.resourceId, toolCall.name, arguments)
                return LoopOutcome.ConfirmationNeeded(approval.resourceId, toolCall.name, arguments)
            }

            check(executionStore.claimToolExecution(toolCall)) {
                "Tool call ${toolCall.resourceId} is not recoverable"
            }
            callbacks.onToolInvoking(toolCall.name, arguments)
            val result = executeTool(toolCall.name, arguments, userId, orgId, state.id)
            val summary = summarizeToolResult(result)
            executionStore.recordToolResult(toolCall, summary, result.isError)
            state.messages.add(LlmMessage("tool", summary, toolCall.providerCallId))
            callbacks.onToolResult(toolCall.name, summary, result.isError)
        }
        return null
    }

    private suspend fun runAssistantLoop(
        state: ConversationState,
        userId: Int,
        orgId: Int,
        callbacks: AssistantCallbacks = AssistantCallbacks(),
    ): LoopOutcome {
        val toolDefinitions = toolRegistry.listTools()
        val toolMap = toolDefinitions.associateBy { it.name }
        val llmTools = toolDefinitions.map(::toLlmTool)

        for (round in (state.run.currentRound + 1)..MAX_TOOL_ROUNDS) {
            if (executionStore.isCancellationRequested(state.run.internalRunId)) {
                return LoopOutcome.Completed(CANCELLED_RESPONSE)
            }
            val startedAtMs = Clock.System.now().toEpochMilliseconds()
            val startedAtNs = System.nanoTime()
            val completion = llmProvider.chatCompletion(
                messages = state.messages.toList(),
                config = LlmConfig(
                    maxTokens = ASSISTANT_MAX_TOKENS,
                    temperature = ASSISTANT_TEMPERATURE,
                    jsonMode = false,
                ),
                tools = llmTools,
            )
            val durationMs = (System.nanoTime() - startedAtNs) / NANOS_PER_MILLI
            if (executionStore.isCancellationRequested(state.run.internalRunId)) {
                return LoopOutcome.Completed(CANCELLED_RESPONSE)
            }
            persistLlmGeneration(
                state = state,
                completion = completion,
                userId = userId,
                startedAtMs = startedAtMs,
                durationMs = durationMs,
            )
            val cost = CostRegistry.calculateCost(
                model = completion.model,
                inputTokens = completion.inputTokens,
                outputTokens = completion.outputTokens,
            )
            val responseContent = completion.content.ifBlank { DEFAULT_EMPTY_RESPONSE }
            val durableCompletion = if (completion.toolCalls.isEmpty()) {
                completion.copy(content = responseContent)
            } else {
                completion
            }
            val readOnlyTools = toolDefinitions
                .filter(ToolDefinition::readOnly)
                .mapTo(mutableSetOf(), ToolDefinition::name)
            val checkpoint = executionStore.checkpointCompletion(
                session = state.run,
                round = round,
                response = durableCompletion,
                readOnlyTools = readOnlyTools,
                cost = cost,
            )

            if (completion.toolCalls.isEmpty()) {
                state.messages.add(
                    LlmMessage(
                        role = "assistant",
                        content = responseContent,
                    ),
                )
                return LoopOutcome.Completed(responseContent)
            }

            state.messages.add(
                LlmMessage(
                    role = "assistant",
                    content = completion.content.ifBlank { null },
                    toolCalls = completion.toolCalls,
                ),
            )

            val toolOutcome = processToolCalls(
                state = state,
                completion = completion,
                checkpoint = checkpoint,
                context = ToolProcessingContext(toolMap, userId, orgId, callbacks),
            )
            if (toolOutcome != null) {
                return toolOutcome
            }
        }

        val limitMessage = "I reached the MCP tool iteration limit. Please refine the question and try again."
        executionStore.completeRun(state.run.internalRunId, limitMessage)
        state.messages.add(
            LlmMessage(
                role = "assistant",
                content = limitMessage,
            ),
        )
        return LoopOutcome.Completed(limitMessage)
    }

    private suspend fun processToolCalls(
        state: ConversationState,
        completion: LlmResponse,
        checkpoint: AiTurnCheckpoint,
        context: ToolProcessingContext,
    ): LoopOutcome? {
        val storedCalls = checkpoint.toolCalls.associateBy(StoredAiToolCall::providerCallId)
        for (toolCall in completion.toolCalls) {
            val storedCall = checkNotNull(storedCalls[toolCall.id]) {
                "Durable tool-call checkpoint missing for ${toolCall.id}"
            }
            val args = toolCall.arguments ?: run {
                recordToolFailure(
                    state = state,
                    toolCall = toolCall,
                    storedCall = storedCall,
                    summary = "Invalid arguments for MCP tool: ${toolCall.name}",
                    callbacks = context.callbacks,
                )
                continue
            }
            val definition = context.toolMap[toolCall.name] ?: run {
                recordToolFailure(
                    state = state,
                    toolCall = toolCall,
                    storedCall = storedCall,
                    summary = "Unknown MCP tool: ${toolCall.name}",
                    callbacks = context.callbacks,
                )
                continue
            }
            if (!definition.readOnly) {
                val approval = executionStore.createApproval(state.run, storedCall, context.userId)
                context.callbacks.onConfirmationNeeded(approval.resourceId, toolCall.name, args)
                return LoopOutcome.ConfirmationNeeded(approval.resourceId, toolCall.name, args)
            }

            check(executionStore.claimToolExecution(storedCall)) {
                "Tool call ${storedCall.resourceId} is not executable"
            }
            if (executionStore.isCancellationRequested(state.run.internalRunId)) {
                return LoopOutcome.Completed(CANCELLED_RESPONSE)
            }
            context.callbacks.onToolInvoking(toolCall.name, args)
            val toolResult = executeTool(toolCall.name, args, context.userId, context.orgId, state.id)
            val summary = summarizeToolResult(toolResult)
            executionStore.recordToolResult(storedCall, summary, toolResult.isError)
            state.messages.add(LlmMessage("tool", summary, toolCall.id))
            context.callbacks.onToolResult(toolCall.name, summary, toolResult.isError)
        }
        return null
    }

    private suspend fun recordToolFailure(
        state: ConversationState,
        toolCall: LlmToolCall,
        storedCall: StoredAiToolCall,
        summary: String,
        callbacks: AssistantCallbacks,
    ) {
        executionStore.recordToolResult(storedCall, summary, true)
        state.messages.add(LlmMessage("tool", summary, toolCall.id))
        callbacks.onToolResult(toolCall.name, summary, true)
    }

    private suspend fun executeTool(
        toolName: String,
        args: JsonObject,
        userId: Int,
        orgId: Int,
        sessionId: String,
    ) = toolRegistry.callTool(
        name = toolName,
        args = args,
        context = McpContext(
            organizationId = orgId,
            userId = userId,
            tokenId = -userId,
            scopes = assistantScopesFor(toolName),
            sessionId = sessionId,
        ),
    )

    fun cancelRun(userId: Int, orgId: Int, runId: String): Boolean =
        executionStore.requestCancellation(runId, orgId, userId)

    private fun assistantScopesFor(toolName: String): Set<String> =
        toolRegistry
            .listTools()
            .firstOrNull { tool -> tool.name == toolName }
            ?.requiredScopes
            .orEmpty()

    private fun summarizeToolResult(result: com.moneat.mcp.protocol.ToolCallResult): String {
        val text = result.content.mapNotNull { it.text }.joinToString("\n").trim()
        if (text.isBlank()) {
            return if (result.isError) "Tool failed with no details." else "Tool executed successfully."
        }
        return if (text.length <= MAX_TOOL_SUMMARY_CHARS) {
            text
        } else {
            "${text.take(MAX_TOOL_SUMMARY_CHARS)}..."
        }
    }

    private fun toLlmTool(tool: ToolDefinition): LlmTool {
        val requiredValues = tool.inputSchema.required.map { JsonPrimitive(it) }
        val rawParameters = JsonObject(
            mapOf(
                "type" to JsonPrimitive(tool.inputSchema.type),
                "properties" to tool.inputSchema.properties,
                "required" to JsonArray(requiredValues),
            ),
        )
        val sanitizedParameters = sanitizeSchemaElement(rawParameters).jsonObject

        return LlmTool(
            name = tool.name,
            description = tool.description,
            parameters = sanitizedParameters,
        )
    }

    private fun sanitizeSchemaElement(element: JsonElement): JsonElement {
        if (element !is JsonObject) {
            return element
        }

        val sanitizedEntries = element
            .mapValues { (_, value) -> sanitizeSchemaElement(value) }
            .toMutableMap()

        val typeValue = (element["type"] as? JsonPrimitive)?.content
        if (typeValue == "array" && !sanitizedEntries.containsKey("items")) {
            sanitizedEntries["items"] = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("string"),
                ),
            )
        }

        return JsonObject(sanitizedEntries)
    }

    private suspend fun persistLlmGeneration(
        state: ConversationState,
        completion: LlmResponse,
        userId: Int,
        startedAtMs: Long,
        durationMs: Double,
    ) {
        val projectId = state.projectId ?: return

        try {
            val totalTokens = (completion.inputTokens + completion.outputTokens).coerceAtLeast(0)
            val cost = CostRegistry.calculateCost(
                model = completion.model,
                inputTokens = completion.inputTokens,
                outputTokens = completion.outputTokens,
            )
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val type = if (completion.toolCalls.isNotEmpty()) "tool_call" else "agent"
            val latestUserInput = state.messages
                .asReversed()
                .firstOrNull { message -> message.role == "user" }
                ?.content
                .orEmpty()
            val outputPayload = if (completion.toolCalls.isEmpty()) {
                completion.content
            } else {
                json.encodeToString(completion.toolCalls)
            }
            val metadata = """{"startedAtMs":$startedAtMs,"toolCalls":${completion.toolCalls.size}}"""

            val query = """
                INSERT INTO $clickhouseDb.llm_generations (
                    generation_id, project_id, trace_id, span_id, parent_span_id,
                    timestamp, duration_ms, name, model, provider, type,
                    input, output, input_tokens, output_tokens, total_tokens, cost_usd,
                    temperature, max_tokens, top_p,
                    status, error_message, status_code,
                    user_id, session_id, environment, release, tags, metadata
                ) VALUES (
                    toUUID('${UUID.randomUUID()}'),
                    $projectId,
                    '${esc(state.id)}',
                    '',
                    '',
                    fromUnixTimestamp64Milli($nowMs),
                    $durationMs,
                    'ai_assistant',
                    '${esc(completion.model)}',
                    '${esc(completion.provider)}',
                    '$type',
                    '${esc(latestUserInput)}',
                    '${esc(outputPayload)}',
                    ${completion.inputTokens.coerceAtLeast(0)},
                    ${completion.outputTokens.coerceAtLeast(0)},
                    $totalTokens,
                    ${cost.totalCost},
                    ${ASSISTANT_TEMPERATURE},
                    $ASSISTANT_MAX_TOKENS,
                    0.0,
                    'success',
                    '',
                    200,
                    '${esc(userId.toString())}',
                    '${esc(state.id)}',
                    '',
                    '',
                    {'source':'ai_assistant'},
                    '${esc(metadata)}'
                )
            """.trimIndent()

            val response = ClickHouseClient.execute(query)
            if (!response.status.isSuccess()) {
                logger.warn {
                    "Failed to insert assistant LLM generation for project $projectId: ${response.status.value}"
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist assistant LLM generation" }
        }
    }

    private fun esc(value: String): String = ClickHouseSqlUtils.escapeSql(value)

    private fun buildTimeContext(userTimezone: String?): String {
        val utcNow = ZonedDateTime.now(ZoneId.of("UTC"))
        val utcFormatted = utcNow.format(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME
        )

        val zone = runCatching {
            userTimezone?.let { ZoneId.of(it) }
        }.getOrNull() ?: ZoneId.of("UTC")
        val userNow = utcNow.withZoneSameInstant(zone)
        val userFormatted = userNow.format(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME
        )
        val tzName = zone.id

        return """
            |Current time context:
            |  User local time: $userFormatted ($tzName)
            |  UTC time: $utcFormatted
        """.trimMargin()
    }

    private fun refreshTimeContext(
        state: ConversationState,
        userTimezone: String?,
    ) {
        val systemIdx = state.messages.indexOfFirst { it.role == "system" }
        val content = "${loadSystemPrompt()}\n\n${buildTimeContext(userTimezone)}"
        if (systemIdx >= 0) {
            state.messages[systemIdx] = LlmMessage(
                role = "system",
                content = content,
            )
        } else {
            state.messages.add(0, LlmMessage(role = "system", content = content))
        }
    }

    private fun loadSystemPrompt(): String {
        return AiAssistantService::class.java.classLoader
            .getResourceAsStream("ai_assistant_system_prompt.txt")
            ?.bufferedReader()
            ?.readText()
            ?.trim()
            .orEmpty()
            .ifBlank {
                "You are Moneat AI, a DevOps and SRE assistant. Use MCP tools for factual answers."
            }
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 8
        private const val MAX_TOOL_SUMMARY_CHARS = 2_000
        private const val RESPONSE_CHUNK_SIZE = 1_200
        private const val DEFAULT_EMPTY_RESPONSE = "I could not generate a response."
        private const val CANCELLED_RESPONSE = "This assistant run was cancelled."

        fun sendSse(writer: Writer, data: String) {
            writer.write("data: $data\n\n")
            writer.flush()
        }
    }
}

private data class ConversationState(
    val id: String,
    val run: AiRunSession,
    val projectId: Long?,
    val messages: MutableList<LlmMessage>,
)

private data class ToolProcessingContext(
    val toolMap: Map<String, ToolDefinition>,
    val userId: Int,
    val orgId: Int,
    val callbacks: AssistantCallbacks,
)

private data class AssistantCallbacks(
    val onToolInvoking: suspend (tool: String, args: JsonObject) -> Unit = { _, _ -> },
    val onToolResult: suspend (tool: String, summary: String, isError: Boolean) -> Unit = { _, _, _ -> },
    val onConfirmationNeeded: suspend (requestId: String, tool: String, args: JsonObject) -> Unit = { _, _, _ -> },
)

private sealed interface LoopOutcome {
    data class Completed(val response: String) : LoopOutcome
    data class ConfirmationNeeded(
        val requestId: String,
        val tool: String,
        val args: JsonObject,
    ) : LoopOutcome
}
