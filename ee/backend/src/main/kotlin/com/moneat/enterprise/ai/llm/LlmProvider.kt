// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Provider-neutral interface used by every enterprise AI surface.
 *
 * Provider wire formats are translated at the adapter boundary. Callers only
 * deal in messages, tools, capabilities, token usage, and streamed text.
 */
interface LlmProvider {
    /** Execute a chat completion and return a structured response. */
    suspend fun chatCompletion(
        messages: List<LlmMessage>,
        config: LlmConfig = LlmConfig(),
        tools: List<LlmTool> = emptyList(),
    ): LlmResponse

    /**
     * Stream response text through a provider-neutral callback.
     *
     * Adapters may override this with native provider streaming. The default
     * keeps callers vendor-neutral and provides a safe single-chunk fallback.
     */
    suspend fun streamChatCompletion(
        messages: List<LlmMessage>,
        config: LlmConfig = LlmConfig(),
        tools: List<LlmTool> = emptyList(),
        onTextChunk: suspend (String) -> Unit,
    ): LlmResponse {
        val response = chatCompletion(messages, config, tools)
        if (response.content.isNotEmpty()) onTextChunk(response.content)
        return response
    }

    /** Provider identifier such as `openai`, `openai-compatible`, or `anthropic`. */
    fun provider(): String

    /** Configured model identifier. */
    fun model(): String

    /** Whether this provider is configured and available. */
    fun isEnabled(): Boolean

    /** Capabilities supported by this configured adapter. */
    fun capabilities(): Set<LlmCapability>
}

@Serializable
data class LlmMessage(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<LlmToolCall> = emptyList(),
)

@Serializable
data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject?,
)

data class LlmTool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class LlmConfig(
    val maxTokens: Int = 4096,
    val temperature: Double = 0.3,
    val jsonMode: Boolean = true,
    val toolChoice: LlmToolChoice = LlmToolChoice.AUTO,
)

enum class LlmToolChoice {
    AUTO,
    NONE,
    REQUIRED,
}

enum class LlmCapability {
    JSON_MODE,
    STREAMING,
    TOOL_CALLING,
}

@Serializable
data class LlmResponse(
    val content: String,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val model: String = "",
    val provider: String = "",
)
