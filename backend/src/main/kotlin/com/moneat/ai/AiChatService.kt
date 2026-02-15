package com.moneat.ai

import com.moneat.utils.SentryUtils
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

class AiChatService {

    suspend fun chat(
        userId: Int,
        orgId: Int,
        request: ChatRequest
    ): ChatApiResponse = SentryUtils.withTransaction("ai.chat", "ai") { tx ->
        SentryUtils.breadcrumb("ai", "Chat request", mapOf(
            "userId" to userId.toString(),
            "hasConversation" to (request.conversationId != null).toString(),
            "currentPage" to (request.currentPage ?: "none")
        ))

        // 1. Resolve or create conversation
        val conversationId = SentryUtils.withSpan(tx, "ai.resolve_conversation") {
            request.conversationId ?: createConversation(orgId, userId, request.message)
        }

        // 2. Persist user message
        persistMessage(conversationId, "user", request.message, request.currentPage, null, null)

        // 3. Load conversation history
        val history = loadHistory(conversationId)

        // 4. Resolve page context docs
        val contextDocNames = SentryUtils.withSpan(tx, "ai.resolve_context") {
            AiContextResolver.resolveDocsForPage(request.currentPage)
        }
        val contextDocs = AiContextResolver.loadDocs(contextDocNames)

        SentryUtils.breadcrumb("ai", "Context resolved", mapOf(
            "docs" to contextDocNames.joinToString(","),
            "model" to OpenAiClient.model
        ))

        // 5. Build messages for OpenAI
        val systemPrompt = AiContextResolver.loadSystemPrompt()
        val openAiMessages = buildOpenAiMessages(systemPrompt, contextDocs, history)

        // 6. Call OpenAI
        val openAiResponse = SentryUtils.withSpan(tx, "ai.openai_call", "OpenAI chat completion") {
            OpenAiClient.chatCompletion(openAiMessages)
        }

        val rawContent = openAiResponse.choices.firstOrNull()?.message?.content ?: """{"message":"I'm sorry, I couldn't generate a response."}"""
        val tokensUsed = openAiResponse.usage?.total_tokens

        SentryUtils.breadcrumb("ai", "OpenAI response received", mapOf(
            "tokensUsed" to (tokensUsed?.toString() ?: "unknown"),
            "model" to OpenAiClient.model
        ))

        // 7. Parse AI response
        val aiResponse = SentryUtils.withSpan(tx, "ai.parse_response") {
            parseAiResponse(rawContent)
        }

        // 8. Handle context_needed (two-step enrichment)
        val finalResponse = if (aiResponse.context_needed.isNotEmpty()) {
            SentryUtils.withSpan(tx, "ai.context_enrichment") {
                handleContextNeeded(aiResponse, openAiMessages, systemPrompt)
            }
        } else {
            aiResponse
        }

        // 9. Persist assistant message
        persistMessage(conversationId, "assistant", rawContent, null, OpenAiClient.model, tokensUsed)

        // 10. Update conversation title from first exchange
        if (request.conversationId == null) {
            updateConversationTitle(conversationId, finalResponse.message)
        }

        ChatApiResponse(
            conversationId = conversationId,
            response = finalResponse,
            model = OpenAiClient.model,
            tokensUsed = tokensUsed
        )
    }

    private fun createConversation(orgId: Int, userId: Int, firstMessage: String): Int {
        val now = Clock.System.now()
        val title = firstMessage.take(100)
        return transaction {
            AiConversations.insert {
                it[organization_id] = orgId
                it[user_id] = userId
                it[AiConversations.title] = title
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
        tokensUsed: Int?
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

    private fun loadHistory(conversationId: Int): List<OpenAiMessage> {
        return transaction {
            AiMessages.selectAll()
                .where { AiMessages.conversation_id eq conversationId }
                .orderBy(AiMessages.created_at)
                .map { row ->
                    OpenAiMessage(
                        role = row[AiMessages.role],
                        content = row[AiMessages.content]
                    )
                }
        }
    }

    private fun buildOpenAiMessages(
        systemPrompt: String,
        contextDocs: String,
        history: List<OpenAiMessage>
    ): List<OpenAiMessage> {
        val messages = mutableListOf<OpenAiMessage>()

        // System prompt with context docs
        val fullSystemPrompt = if (contextDocs.isNotBlank()) {
            "$systemPrompt\n\n--- API DOCUMENTATION ---\n$contextDocs"
        } else {
            systemPrompt
        }
        messages.add(OpenAiMessage("system", fullSystemPrompt))

        // Conversation history (limit to last 20 messages to manage tokens)
        messages.addAll(history.takeLast(20))

        return messages
    }

    private fun parseAiResponse(rawContent: String): AiResponse {
        return try {
            json.decodeFromString(AiResponse.serializer(), rawContent)
        } catch (e: Exception) {
            logger.warn { "Failed to parse AI JSON response: ${e.message}" }
            AiResponse(message = rawContent)
        }
    }

    private suspend fun handleContextNeeded(
        initialResponse: AiResponse,
        previousMessages: List<OpenAiMessage>,
        systemPrompt: String
    ): AiResponse {
        val additionalDocs = AiContextResolver.loadDocs(initialResponse.context_needed)
        if (additionalDocs.isBlank()) return initialResponse

        val enrichedMessages = previousMessages.toMutableList()
        // Replace system message with enriched context
        enrichedMessages[0] = OpenAiMessage(
            "system",
            "$systemPrompt\n\n--- API DOCUMENTATION ---\n$additionalDocs"
        )
        // Add the AI's interim response and a follow-up instruction
        enrichedMessages.add(OpenAiMessage("assistant", json.encodeToString(AiResponse.serializer(), initialResponse)))
        enrichedMessages.add(OpenAiMessage("user", "Now that you have the documentation, please provide the complete response."))

        val enrichedResponse = OpenAiClient.chatCompletion(enrichedMessages)
        val content = enrichedResponse.choices.firstOrNull()?.message?.content ?: return initialResponse
        return parseAiResponse(content)
    }

    private fun updateConversationTitle(conversationId: Int, message: String) {
        val title = message.take(100)
        transaction {
            AiConversations.update({ AiConversations.id eq conversationId }) {
                it[AiConversations.title] = title
            }
        }
    }

    fun getConversations(userId: Int, orgId: Int): List<ConversationSummary> {
        return transaction {
            AiConversations.selectAll()
                .where {
                    (AiConversations.user_id eq userId) and
                    (AiConversations.organization_id eq orgId)
                }
                .orderBy(AiConversations.updated_at, SortOrder.DESC)
                .map { row ->
                    ConversationSummary(
                        id = row[AiConversations.id],
                        title = row[AiConversations.title],
                        createdAt = row[AiConversations.created_at].toString(),
                        updatedAt = row[AiConversations.updated_at].toString()
                    )
                }
        }
    }

    fun getConversation(conversationId: Int, userId: Int, orgId: Int): ConversationDetail? {
        return transaction {
            val conv = AiConversations.selectAll()
                .where {
                    (AiConversations.id eq conversationId) and
                    (AiConversations.user_id eq userId) and
                    (AiConversations.organization_id eq orgId)
                }
                .firstOrNull() ?: return@transaction null

            val messages = AiMessages.selectAll()
                .where { AiMessages.conversation_id eq conversationId }
                .orderBy(AiMessages.created_at)
                .map { row ->
                    MessageDto(
                        id = row[AiMessages.id],
                        role = row[AiMessages.role],
                        content = row[AiMessages.content],
                        pageContext = row[AiMessages.page_context],
                        model = row[AiMessages.model],
                        tokensUsed = row[AiMessages.tokens_used],
                        createdAt = row[AiMessages.created_at].toString()
                    )
                }

            ConversationDetail(
                id = conv[AiConversations.id],
                title = conv[AiConversations.title],
                messages = messages,
                createdAt = conv[AiConversations.created_at].toString(),
                updatedAt = conv[AiConversations.updated_at].toString()
            )
        }
    }

    fun deleteConversation(conversationId: Int, userId: Int, orgId: Int): Boolean {
        return transaction {
            val deleted = AiConversations.deleteWhere {
                (AiConversations.id eq conversationId) and
                (AiConversations.user_id eq userId) and
                (AiConversations.organization_id eq orgId)
            }
            deleted > 0
        }
    }
}
