// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.services

import com.moneat.ai.AiConversations
import com.moneat.ai.AiMessages
import com.moneat.billing.services.BillingQuotaService
import com.moneat.enterprise.ai.llm.CostRegistry
import com.moneat.enterprise.ai.llm.LlmConfig
import com.moneat.enterprise.ai.llm.LlmMessage
import com.moneat.enterprise.ai.llm.LlmProvider
import com.moneat.enterprise.ai.llm.LlmResponse
import com.moneat.enterprise.ai.models.AggregatedContext
import com.moneat.enterprise.ai.models.SseContextReady
import com.moneat.enterprise.ai.models.SseError
import com.moneat.enterprise.ai.models.SseResponseChunk
import com.moneat.enterprise.ai.models.SseSearchProgress
import com.moneat.shared.models.Projects
import com.moneat.utils.SentryUtils
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Writer
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Month
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}
private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Orchestrates the enterprise AI chat flow:
 * 1. Aggregates context from ClickHouse (logs, spans, events)
 * 2. Stores context snapshot in PostgreSQL
 * 3. On confirmation, sends context + question to the LLM
 * 4. Streams progress and response via SSE
 */
class EnterpriseAiChatService(
    private val llmProvider: LlmProvider,
    private val contextAggregator: AiContextAggregator,
    private val snapshotService: AiContextSnapshotService,
    private val billingQuotaService: BillingQuotaService = BillingQuotaService(),
) {

    /**
     * Phase 1: Search observability data and create a context snapshot.
     * Streams SSE progress events to the writer.
     */
    suspend fun searchAndPrepareContext(
        writer: Writer,
        userId: Int,
        orgId: Int,
        message: String,
        conversationId: Int?,
        currentPage: String?,
        timeRange: String?,
    ): Int {
        val convId = conversationId ?: createConversation(orgId, userId, message)
        persistMessage(convId, "user", message, currentPage, null, null)

        // Stream search progress for each source
        sendSse(
            writer,
            json.encodeToString(
                SseSearchProgress.serializer(),
                SseSearchProgress(source = "logs", status = "in_progress")
            )
        )
        sendSse(
            writer,
            json.encodeToString(
                SseSearchProgress.serializer(),
                SseSearchProgress(source = "spans", status = "in_progress")
            )
        )
        sendSse(
            writer,
            json.encodeToString(
                SseSearchProgress.serializer(),
                SseSearchProgress(source = "events", status = "in_progress")
            )
        )
        sendSse(
            writer,
            json.encodeToString(
                SseSearchProgress.serializer(),
                SseSearchProgress(source = "metrics", status = "in_progress")
            )
        )
        sendSse(
            writer,
            json.encodeToString(
                SseSearchProgress.serializer(),
                SseSearchProgress(source = "containers", status = "in_progress")
            )
        )

        val timeFilter = buildAiTimeFilter(message, timeRange)

        // Aggregate data from ClickHouse — resolve org → project IDs first
        val projectIds = transaction {
            Projects.selectAll().where { Projects.organization_id eq orgId }.map { it[Projects.id] }
        }
        val context = contextAggregator.aggregate(orgId, projectIds, timeFilter)

        sendSse(
            writer,
            json.encodeToString(
                SseSearchProgress.serializer(),
                SseSearchProgress(source = "logs", status = "done", count = context.summary.logCount)
            )
        )
        sendSse(
            writer,
            json.encodeToString(
                SseSearchProgress.serializer(),
                SseSearchProgress(source = "spans", status = "done", count = context.summary.spanCount)
            )
        )
        sendSse(
            writer,
            json.encodeToString(
                SseSearchProgress.serializer(),
                SseSearchProgress(source = "events", status = "done", count = context.summary.eventCount)
            )
        )
        sendSse(
            writer,
            json.encodeToString(
                SseSearchProgress.serializer(),
                SseSearchProgress(source = "metrics", status = "done", count = context.summary.metricCount)
            )
        )
        sendSse(
            writer,
            json.encodeToString(
                SseSearchProgress.serializer(),
                SseSearchProgress(source = "containers", status = "done", count = context.summary.containerCount)
            )
        )

        // Save snapshot and notify client, then immediately proceed to LLM
        val estimatedTokens = contextAggregator.estimateTokens(context)
        val snapshotId = snapshotService.createSnapshot(convId, orgId, userId, context, estimatedTokens)

        sendSse(
            writer,
            json.encodeToString(
                SseContextReady.serializer(),
                SseContextReady(
                    snapshotId = snapshotId,
                    totalTokens = estimatedTokens,
                    sources = mapOf(
                        "logs" to context.summary.logCount,
                        "spans" to context.summary.spanCount,
                        "events" to context.summary.eventCount,
                        "metrics" to context.summary.metricCount,
                        "containers" to context.summary.containerCount,
                    ),
                )
            )
        )

        // Immediately generate the AI response on the same SSE stream
        confirmAndGenerate(writer, userId, snapshotId)

        return convId
    }

    /**
     * Phase 2: Generate LLM response from a confirmed snapshot.
     * Can be called directly (auto-confirm) or via the /confirm endpoint.
     * Streams the LLM response via SSE.
     */
    suspend fun confirmAndGenerate(
        writer: Writer,
        userId: Int,
        snapshotId: Int,
    ) {
        val context = snapshotService.loadSnapshot(snapshotId, userId)
        if (context == null) {
            sendSse(
                writer,
                json.encodeToString(
                    SseError.serializer(),
                    SseError(error = "Context snapshot not found or expired")
                )
            )
            return
        }

        snapshotService.confirmSnapshot(snapshotId, userId)
        val conversationId = snapshotService.getSnapshotConversationId(snapshotId, userId) ?: return

        // Build LLM messages
        val messages = buildLlmMessages(conversationId, context)

        // Call LLM, wrapped in a Sentry transaction for AI observability
        try {
            val llmResponse = SentryUtils.withTransactionData("Moneat AI Chat", "ai.run") { setData ->
                setData("ai.model_id", llmProvider.model())
                setData("ai.provider", llmProvider.provider())
                setData("ai.streaming", false)

                val response = llmProvider.chatCompletion(
                    messages,
                    LlmConfig(
                        maxTokens = 4096,
                        temperature = 0.3,
                        jsonMode = false,
                    )
                )

                setData("ai.usage.prompt_tokens", response.inputTokens)
                setData("ai.usage.completion_tokens", response.outputTokens)
                setData("ai.usage.total_tokens", response.inputTokens + response.outputTokens)

                val cost = CostRegistry.calculateCost(response.model, response.inputTokens, response.outputTokens)
                setData("ai.cost_usd", cost.totalCost.toPlainString())

                persistAssistantMessage(conversationId, response, cost.totalCost)

                response
            }

            sendSse(
                writer,
                json.encodeToString(
                    SseResponseChunk.serializer(),
                    SseResponseChunk(content = llmResponse.content, done = true)
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "LLM call failed" }
            sendSse(
                writer,
                json.encodeToString(
                    SseError.serializer(),
                    SseError(error = "AI provider error: ${e.message}")
                )
            )
        }
    }

    private fun buildLlmMessages(conversationId: Int, context: AggregatedContext): List<LlmMessage> {
        val messages = mutableListOf<LlmMessage>()

        val systemPrompt = loadEnterpriseSystemPrompt()
        val contextStr = json.encodeToString(AggregatedContext.serializer(), context)
        messages.add(
            LlmMessage(
                "system",
                """$systemPrompt

--- OBSERVABILITY DATA ---
The following is real-time data from the user's monitoring platform.
Analyze this data to answer the user's question.

$contextStr"""
            )
        )

        // Conversation history (last 20 messages)
        val history = loadHistory(conversationId)
        messages.addAll(history.takeLast(MAX_HISTORY_MESSAGES))

        return messages
    }

    private fun loadEnterpriseSystemPrompt(): String {
        return EnterpriseAiChatService::class.java.classLoader
            .getResourceAsStream("ai_enterprise_system_prompt.txt")
            ?.bufferedReader()
            ?.readText()
            ?: (
                "You are Moneat AI, an observability analyst. " +
                    "Analyze the provided monitoring data and respond in markdown."
            )
    }

    private fun loadHistory(conversationId: Int): List<LlmMessage> {
        return transaction {
            AiMessages
                .selectAll()
                .where { AiMessages.conversation_id eq conversationId }
                .orderBy(AiMessages.created_at)
                .map { row ->
                    LlmMessage(
                        role = row[AiMessages.role],
                        content = row[AiMessages.content],
                    )
                }
        }
    }

    private fun createConversation(orgId: Int, userId: Int, firstMessage: String): Int {
        val now = Clock.System.now()
        return transaction {
            AiConversations.insert {
                it[organization_id] = orgId
                it[user_id] = userId
                it[title] = firstMessage.take(MAX_TITLE_LENGTH)
                it[created_at] = now
                it[updated_at] = now
            } get AiConversations.id
        }
    }

    private fun persistMessage(
        conversationId: Int,
        role: String,
        content: String,
        pageContext: String?,
        model: String?,
        tokensUsed: Int?,
    ) {
        val now = Clock.System.now()
        transaction {
            AiMessages.insert {
                it[AiMessages.conversation_id] = conversationId
                it[AiMessages.role] = role
                it[AiMessages.content] = content
                it[AiMessages.page_context] = pageContext
                it[AiMessages.model] = model
                it[AiMessages.tokens_used] = tokensUsed
                it[AiMessages.created_at] = now
            }
            AiConversations.update({ AiConversations.id eq conversationId }) {
                it[updated_at] = now
            }
        }
    }

    private fun persistAssistantMessage(conversationId: Int, response: LlmResponse, costUsd: BigDecimal) {
        val now = Clock.System.now()
        val orgId = transaction {
            AiMessages.insert {
                it[AiMessages.conversation_id] = conversationId
                it[AiMessages.role] = "assistant"
                it[AiMessages.content] = response.content
                it[AiMessages.model] = response.model
                it[AiMessages.tokens_used] = response.inputTokens + response.outputTokens
                it[AiMessages.input_tokens] = response.inputTokens
                it[AiMessages.output_tokens] = response.outputTokens
                it[AiMessages.cost_usd] = costUsd
                it[AiMessages.provider] = response.provider
                it[AiMessages.created_at] = now
            }
            AiConversations.update({ AiConversations.id eq conversationId }) {
                it[updated_at] = now
            }

            AiConversations
                .selectAll()
                .where { AiConversations.id eq conversationId }
                .firstOrNull()
                ?.get(AiConversations.organization_id)
        }

        if (orgId != null) {
            val totalTokens = (response.inputTokens + response.outputTokens).toLong()
            billingQuotaService.incrementUsageCounters(orgId, aiTokens = totalTokens)
        }
    }

    private fun buildAiTimeFilter(message: String, timeRange: String?): AiTimeFilter {
        parseDateFromMessage(message)?.let { return AiTimeFilter.SpecificDay(it) }
        return AiTimeFilter.LastHours(parseTimeRangeHours(timeRange))
    }

    private fun parseDateFromMessage(message: String): LocalDate? {
        val pattern = Regex(
            """(january|february|march|april|may|june|july|august|september|october|november|december|""" +
                """jan|feb|mar|apr|jun|jul|aug|sep|oct|nov|dec)\s+(\d{1,2})(?:st|nd|rd|th)?""",
            RegexOption.IGNORE_CASE,
        )
        val match = pattern.find(message) ?: return null
        val month = MONTH_NUMBERS[match.groupValues[1].lowercase()] ?: return null
        val day = match.groupValues[2].toIntOrNull() ?: return null
        val today = LocalDate.now()
        return try {
            val date = LocalDate.of(today.year, month, day)
            if (date.isAfter(today)) LocalDate.of(today.year - 1, month, day) else date
        } catch (_: Exception) { null }
    }

    private fun parseTimeRangeHours(timeRange: String?): Int {
        if (timeRange.isNullOrBlank()) return HOURS_24H
        return when (timeRange.lowercase()) {
            "1h" -> 1
            "6h" -> HOURS_6H
            "24h" -> HOURS_24H
            "7d" -> HOURS_7D
            "30d" -> HOURS_30D
            else -> HOURS_24H
        }
    }

    companion object {
        fun sendSse(writer: Writer, data: String) {
            writer.write("data: $data\n\n")
            writer.flush()
        }

        private const val MAX_HISTORY_MESSAGES = 20
        private const val MAX_TITLE_LENGTH = 100
        private const val HOURS_6H = 6
        private const val HOURS_24H = 24
        private const val HOURS_7D = 168
        private const val HOURS_30D = 720

        private val MONTH_NUMBERS: Map<String, Int> = Month.values().flatMap { m ->
            val name = m.name.lowercase()
            if (name.length > 3) {
                listOf(name to m.value, name.take(3) to m.value)
            } else {
                listOf(name to m.value)
            }
        }.toMap()
    }
}
