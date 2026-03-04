// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.ai.llm

import kotlinx.serialization.Serializable

/**
 * Provider-agnostic interface for LLM chat completions.
 * Implementations wrap specific providers (OpenAI, Anthropic, etc.).
 */
interface LlmProvider {
    /** Execute a chat completion and return a structured response. */
    suspend fun chatCompletion(messages: List<LlmMessage>, config: LlmConfig = LlmConfig()): LlmResponse

    /** Provider identifier (e.g. "openai", "anthropic"). */
    fun provider(): String

    /** Model identifier (e.g. "gpt-4o-mini", "claude-sonnet-4-20250514"). */
    fun model(): String

    /** Whether this provider is configured and available. */
    fun isEnabled(): Boolean
}

@Serializable
data class LlmMessage(
    val role: String,
    val content: String,
)

data class LlmConfig(
    val maxTokens: Int = 4096,
    val temperature: Double = 0.3,
    val jsonMode: Boolean = true,
)

@Serializable
data class LlmResponse(
    val content: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val model: String,
    val provider: String,
)
