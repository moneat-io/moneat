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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

// ── Exposed Tables ──────────────────────────────────────────────────────

object AiConversations : Table("ai_conversations") {
    val id = integer("id").autoIncrement()
    val resource_id = uuid("resource_id").clientDefault { Uuid.random() }
    val organization_id = integer("organization_id")
    val user_id = integer("user_id")
    val title = varchar("title", 255).nullable()
    val created_at = timestamp("created_at")
    val updated_at = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object AiMessages : Table("ai_messages") {
    val id = integer("id").autoIncrement()
    val resource_id = uuid("resource_id").clientDefault { Uuid.random() }
    val conversation_id = integer("conversation_id").references(AiConversations.id)
    val role = varchar("role", 20)
    val content = text("content")
    val page_context = varchar("page_context", 255).nullable()
    val model = varchar("model", 50).nullable()
    val tokens_used = integer("tokens_used").nullable()
    val input_tokens = integer("input_tokens").nullable()
    val output_tokens = integer("output_tokens").nullable()
    val cost_usd = decimal("cost_usd", 10, 6).nullable()
    val provider = varchar("provider", 20).nullable()
    val created_at = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

// ── API Request / Response DTOs ─────────────────────────────────────────

@Serializable
data class ChatRequest(
    val conversationId: String? = null,
    val message: String,
    val currentPage: String? = null
)

@Serializable
data class ExecuteActionRequest(
    val conversationId: String,
    val actionId: String,
    val params: Map<String, String> = emptyMap()
)

@Serializable
data class ConversationSummary(
    val id: String,
    val title: String?,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class ConversationDetail(
    val id: String,
    val title: String?,
    val messages: List<MessageDto>,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class MessageDto(
    val id: String,
    val role: String,
    val content: String,
    val pageContext: String? = null,
    val model: String? = null,
    val tokensUsed: Int? = null,
    val createdAt: String
)

// ── AI JSON Protocol Types ──────────────────────────────────────────────

@Serializable
data class AiResponse(
    val message: String = "",
    val actions: List<AiAction> = emptyList(),
    val clarifications: List<AiClarification> = emptyList(),
    @SerialName("data_queries") val dataQueries: List<AiDataQuery> = emptyList(),
    val links: List<AiLink> = emptyList(),
    @SerialName("context_needed") val contextNeeded: List<String> = emptyList()
)

@Serializable
data class AiAction(
    val id: String,
    val type: String = "api_call",
    val label: String,
    val method: String,
    val endpoint: String,
    val params: Map<String, String> = emptyMap()
)

@Serializable
data class AiClarification(
    val id: String,
    val question: String,
    val field: String,
    val options: List<AiClarificationOption> = emptyList(),
    val default: String? = null
)

@Serializable
data class AiClarificationOption(
    val label: String,
    val value: String
)

@Serializable
data class AiDataQuery(
    val id: String,
    val description: String,
    val endpoint: String,
    val params: Map<String, String> = emptyMap()
)

@Serializable
data class AiLink(
    val label: String,
    val url: String
)

// ── Chat API Response ───────────────────────────────────────────────────

@Serializable
data class ChatApiResponse(
    val conversationId: String,
    val response: AiResponse,
    val model: String? = null,
    val tokensUsed: Int? = null
)

@Serializable
data class ActionResult(
    val success: Boolean,
    val message: String,
    val data: Map<String, String>? = null
)

// ── OpenAI API Types ────────────────────────────────────────────────────

@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 2048,
    val temperature: Double = 0.3,
    @SerialName("response_format") val responseFormat: OpenAiResponseFormat? = null
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: String
)

@Serializable
data class OpenAiResponseFormat(
    val type: String
)

@Serializable
data class OpenAiChatResponse(
    val id: String = "",
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null
)

@Serializable
data class OpenAiChoice(
    val message: OpenAiMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)
