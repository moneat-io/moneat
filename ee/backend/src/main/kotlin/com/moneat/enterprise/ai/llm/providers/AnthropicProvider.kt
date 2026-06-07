// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.llm.providers

import com.moneat.config.EnvConfig
import com.moneat.enterprise.ai.llm.LlmConfig
import com.moneat.enterprise.ai.llm.LlmMessage
import com.moneat.enterprise.ai.llm.LlmProvider
import com.moneat.enterprise.ai.llm.LlmResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class AnthropicProvider : LlmProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        engine { requestTimeout = 120_000 }
    }

    private val apiKey: String get() = EnvConfig.get("ANTHROPIC_API_KEY", "")
    private val modelName: String get() = EnvConfig.get("ANTHROPIC_MODEL", "claude-sonnet-4-20250514")
    private val maxTokensCfg: Int get() = EnvConfig.get("ANTHROPIC_MAX_TOKENS", "16384").toIntOrNull() ?: 16384

    override fun provider() = "anthropic"
    override fun model() = modelName
    override fun isEnabled() = apiKey.isNotBlank()

    override suspend fun chatCompletion(messages: List<LlmMessage>, config: LlmConfig): LlmResponse {
        // Anthropic separates the system message from conversation messages
        val systemText = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val conversationMessages = messages.filter { it.role != "system" }
            .map { AnthropicMsg(it.role, it.content) }

        val request = AnthropicRequest(
            model = modelName,
            max_tokens = minOf(config.maxTokens, maxTokensCfg),
            system = systemText.ifBlank { null },
            messages = conversationMessages,
        )

        val response = client.post("https://api.anthropic.com/v1/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(request)
        }

        val body = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            logger.error { "Anthropic API error (${response.status}): $body" }
            throw RuntimeException("Anthropic API error ${response.status.value}: $body")
        }

        val parsed = json.decodeFromString(AnthropicResponse.serializer(), body)
        val textContent = parsed.content.firstOrNull { it.type == "text" }?.text ?: ""

        return LlmResponse(
            content = textContent,
            inputTokens = parsed.usage?.input_tokens ?: 0,
            outputTokens = parsed.usage?.output_tokens ?: 0,
            model = modelName,
            provider = "anthropic",
        )
    }

    // Internal DTOs
    @Serializable data class AnthropicMsg(val role: String, val content: String)

    @Serializable data class AnthropicRequest(
        val model: String,
        val max_tokens: Int,
        val system: String? = null,
        val messages: List<AnthropicMsg>,
    )

    @Serializable data class AnthropicContentBlock(val type: String, val text: String = "")

    @Serializable data class AnthropicUsage(val input_tokens: Int = 0, val output_tokens: Int = 0)

    @Serializable data class AnthropicResponse(
        val id: String = "",
        val content: List<AnthropicContentBlock> = emptyList(),
        val usage: AnthropicUsage? = null,
        @SerialName("stop_reason") val stopReason: String? = null,
    )
}
