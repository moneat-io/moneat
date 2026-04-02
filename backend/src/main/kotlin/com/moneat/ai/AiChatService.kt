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

package com.moneat.ai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val aiChatResponseJson = Json { ignoreUnknownKeys = true }

/**
 * Legacy core AI service stub.
 *
 * Enterprise builds provide the actual AI implementation in moneat-enterprise.
 */
class AiChatService {

    companion object {
        private const val LOG_CONTEXT_LENGTH = 50
        private const val MAX_USER_INPUT_LENGTH = 4000
        private const val MAX_HISTORY_MESSAGES = 20
    }

    suspend fun chat(userId: Int, orgId: Int, request: ChatRequest): ChatApiResponse {
        return unavailable(
            operation = "chat",
            userId = userId,
            orgId = orgId,
            context = "message=${request.message.take(LOG_CONTEXT_LENGTH)}",
        )
    }

    /** Shared helpers for AI message shaping; `internal` for unit tests and enterprise module reuse. */
    internal fun sanitizeUserInput(input: String): String {
        val injectionPatterns = listOf(
            Regex("ignore previous instructions", RegexOption.IGNORE_CASE),
            Regex("system:", RegexOption.IGNORE_CASE),
            Regex("###"),
        )
        var sanitized = input
        for (pattern in injectionPatterns) {
            sanitized = pattern.replace(sanitized, "")
        }
        return sanitized.take(MAX_USER_INPUT_LENGTH)
    }

    internal fun buildOpenAiMessages(
        systemPrompt: String,
        docBlock: String,
        history: List<OpenAiMessage>,
    ): List<OpenAiMessage> {
        val systemContent = "$systemPrompt\n\n== API DOCUMENTATION ==\n$docBlock"
        val systemMessage = OpenAiMessage(role = "system", content = systemContent)
        val recentHistory = history.takeLast(MAX_HISTORY_MESSAGES)
        return listOf(systemMessage) + recentHistory
    }

    internal fun parseAiResponse(jsonStr: String): AiResponse {
        return try {
            aiChatResponseJson.decodeFromString<AiResponse>(jsonStr)
        } catch (_: SerializationException) {
            AiResponse(message = jsonStr)
        }
    }

    fun getConversations(userId: Int, orgId: Int): List<ConversationSummary> {
        return unavailable(operation = "getConversations", userId = userId, orgId = orgId)
    }

    fun getConversation(conversationId: Int, userId: Int, orgId: Int): ConversationDetail? {
        return unavailable(
            operation = "getConversation",
            userId = userId,
            orgId = orgId,
            context = "conversationId=$conversationId",
        )
    }

    fun deleteConversation(conversationId: Int, userId: Int, orgId: Int): Boolean {
        return unavailable(
            operation = "deleteConversation",
            userId = userId,
            orgId = orgId,
            context = "conversationId=$conversationId",
        )
    }

    private fun unavailable(
        operation: String,
        userId: Int,
        orgId: Int,
        context: String = "",
    ): Nothing {
        throw UnsupportedOperationException(
            "Core AI operation '$operation' is unavailable for user=$userId org=$orgId $context. " +
                "Enable moneat-enterprise AI module.",
        )
    }
}
