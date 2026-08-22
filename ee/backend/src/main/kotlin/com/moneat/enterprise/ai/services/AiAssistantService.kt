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

class AiAssistantService(
    private val toolRegistry: McpToolRegistry,
    private val llmProvider: LlmProvider = LlmProviderFactory.create(),
) {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val conversations = ConcurrentHashMap<String, ConversationState>()
    private val pendingActions = ConcurrentHashMap<String, PendingAction>()

    suspend fun streamAssistant(
        writer: Writer,
        userId: Int,
        orgId: Int,
        message: String,
        conversationId: String?,
        projectId: Long? = null,
        userTimezone: String? = null,
    ): String {
        val normalizedMessage = message.trim()
        if (normalizedMessage.isBlank()) {
            throw IllegalArgumentException("Message cannot be empty")
        }

        val state = getOrCreateConversation(conversationId, projectId)
        return state.mutex.withLock {
            refreshTimeContext(state, userTimezone)
            state.messages.add(LlmMessage(role = "user", content = normalizedMessage))

            val outcome = runAssistantLoop(
                state = state,
                userId = userId,
                orgId = orgId,
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
                                tool = tool,
                                args = args,
                            ),
                        ),
                    )
                },
            )

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
                    AssistantDoneEvent(conversationId = state.id),
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
        val pending = pendingActions.remove(request.requestId)
            ?: throw IllegalArgumentException("Pending confirmation request not found")

        if (pending.userId != userId || pending.orgId != orgId) {
            throw IllegalAccessException("Confirmation request does not belong to this user")
        }

        val state = conversations[pending.conversationId]
            ?: throw IllegalStateException("Conversation not found for pending request")

        return state.mutex.withLock {
            val toolSummary = if (request.approve) {
                val result = executeTool(
                    toolName = pending.tool,
                    args = pending.args,
                    userId = userId,
                    orgId = orgId,
                    sessionId = pending.conversationId,
                )
                summarizeToolResult(result)
            } else {
                "User denied execution of ${pending.tool}."
            }

            state.messages.add(
                LlmMessage(
                    role = "tool",
                    content = toolSummary,
                    toolCallId = pending.toolCallId,
                ),
            )

            val outcome = runAssistantLoop(state = state, userId = userId, orgId = orgId)
            return@withLock when (outcome) {
                is LoopOutcome.Completed -> {
                    AiAssistantConfirmResponse(
                        conversationId = pending.conversationId,
                        requestId = pending.requestId,
                        approved = request.approve,
                        tool = pending.tool,
                        toolSummary = toolSummary,
                        response = outcome.response,
                    )
                }
                is LoopOutcome.ConfirmationNeeded -> {
                    AiAssistantConfirmResponse(
                        conversationId = pending.conversationId,
                        requestId = pending.requestId,
                        approved = request.approve,
                        tool = pending.tool,
                        toolSummary = toolSummary,
                        response = "",
                        nextRequestId = outcome.requestId,
                    )
                }
            }
        }
    }

    private suspend fun runAssistantLoop(
        state: ConversationState,
        userId: Int,
        orgId: Int,
        onToolInvoking: suspend (tool: String, args: JsonObject) -> Unit = { _, _ -> },
        onToolResult: suspend (tool: String, summary: String, isError: Boolean) -> Unit = { _, _, _ -> },
        onConfirmationNeeded: suspend (requestId: String, tool: String, args: JsonObject) -> Unit = { _, _, _ -> },
    ): LoopOutcome {
        val toolDefinitions = toolRegistry.listTools()
        val toolMap = toolDefinitions.associateBy { it.name }
        val llmTools = toolDefinitions.map(::toLlmTool)

        repeat(MAX_TOOL_ROUNDS) {
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
            persistLlmGeneration(
                state = state,
                completion = completion,
                userId = userId,
                startedAtMs = startedAtMs,
                durationMs = durationMs,
            )

            if (completion.toolCalls.isEmpty()) {
                val responseContent = completion.content.ifBlank { DEFAULT_EMPTY_RESPONSE }
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

            for (toolCall in completion.toolCalls) {
                val args = toolCall.arguments
                val definition = toolMap[toolCall.name]

                if (definition == null) {
                    val unknownToolSummary = "Unknown MCP tool: ${toolCall.name}"
                    state.messages.add(
                        LlmMessage(
                            role = "tool",
                            content = unknownToolSummary,
                            toolCallId = toolCall.id,
                        ),
                    )
                    onToolResult(toolCall.name, unknownToolSummary, true)
                    continue
                }

                if (!definition.readOnly) {
                    val requestId = UUID.randomUUID().toString()
                    pendingActions[requestId] = PendingAction(
                        requestId = requestId,
                        conversationId = state.id,
                        userId = userId,
                        orgId = orgId,
                        tool = toolCall.name,
                        args = args,
                        toolCallId = toolCall.id,
                    )
                    onConfirmationNeeded(requestId, toolCall.name, args)
                    return LoopOutcome.ConfirmationNeeded(
                        requestId = requestId,
                        tool = toolCall.name,
                        args = args,
                    )
                }

                onToolInvoking(toolCall.name, args)
                val toolResult = executeTool(
                    toolName = toolCall.name,
                    args = args,
                    userId = userId,
                    orgId = orgId,
                    sessionId = state.id,
                )
                val summary = summarizeToolResult(toolResult)
                state.messages.add(
                    LlmMessage(
                        role = "tool",
                        content = summary,
                        toolCallId = toolCall.id,
                    ),
                )
                onToolResult(toolCall.name, summary, toolResult.isError)
            }
        }

        val limitMessage = "I reached the MCP tool iteration limit. Please refine the question and try again."
        state.messages.add(
            LlmMessage(
                role = "assistant",
                content = limitMessage,
            ),
        )
        return LoopOutcome.Completed(limitMessage)
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

    private fun getOrCreateConversation(
        conversationId: String?,
        projectId: Long?,
    ): ConversationState {
        val normalizedConversationId = conversationId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

        val state = conversations.computeIfAbsent(normalizedConversationId) {
            ConversationState(
                id = normalizedConversationId,
                projectId = projectId,
                messages = mutableListOf(
                    LlmMessage(role = "system", content = loadSystemPrompt()),
                ),
            )
        }

        if (state.projectId == null && projectId != null) {
            state.projectId = projectId
        }

        return state
    }

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

        fun sendSse(writer: Writer, data: String) {
            writer.write("data: $data\n\n")
            writer.flush()
        }
    }
}

private data class ConversationState(
    val id: String,
    var projectId: Long?,
    val messages: MutableList<LlmMessage>,
    val mutex: Mutex = Mutex(),
)

private data class PendingAction(
    val requestId: String,
    val conversationId: String,
    val userId: Int,
    val orgId: Int,
    val tool: String,
    val args: JsonObject,
    val toolCallId: String,
)

private sealed interface LoopOutcome {
    data class Completed(val response: String) : LoopOutcome
    data class ConfirmationNeeded(
        val requestId: String,
        val tool: String,
        val args: JsonObject,
    ) : LoopOutcome
}
