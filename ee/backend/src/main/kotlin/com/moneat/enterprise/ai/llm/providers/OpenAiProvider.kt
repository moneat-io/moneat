// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.llm.providers

import com.moneat.config.EnvConfig
import com.moneat.enterprise.ai.llm.LlmConfig
import com.moneat.enterprise.ai.llm.LlmMessage
import com.moneat.enterprise.ai.llm.LlmProvider
import com.moneat.enterprise.ai.llm.LlmResponse
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class OpenAiProvider : LlmProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        engine { requestTimeout = 120_000 }
    }

    private val apiKey: String get() = EnvConfig.get("OPENAI_API_KEY", "")
    private val modelName: String get() = EnvConfig.get("OPENAI_MODEL", "gpt-4o-mini")

    override fun provider() = "openai"
    override fun model() = modelName
    override fun isEnabled() = apiKey.isNotBlank()

    override suspend fun chatCompletion(messages: List<LlmMessage>, config: LlmConfig): LlmResponse {
        val request = OpenAiRequest(
            model = modelName,
            messages = messages.map { OpenAiMsg(it.role, it.content) },
            maxTokens = config.maxTokens,
            temperature = config.temperature,
            responseFormat = if (config.jsonMode) OpenAiFormat("json_object") else null,
        )

        val response = client.post("https://api.openai.com/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(request)
        }

        val body = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            logger.error { "OpenAI API error (${response.status}): $body" }
            throw RuntimeException("OpenAI API error ${response.status.value}: $body")
        }

        val parsed = json.decodeFromString(OpenAiResponse.serializer(), body)
        val choice = parsed.choices.firstOrNull()
        return LlmResponse(
            content = choice?.message?.content ?: "",
            inputTokens = parsed.usage?.promptTokens ?: 0,
            outputTokens = parsed.usage?.completionTokens ?: 0,
            model = modelName,
            provider = "openai",
        )
    }

    // Internal DTOs
    @Serializable data class OpenAiMsg(val role: String, val content: String)

    @Serializable data class OpenAiFormat(val type: String)

    @Serializable data class OpenAiRequest(
        val model: String,
        val messages: List<OpenAiMsg>,
        @SerialName("max_tokens") val maxTokens: Int = 4096,
        val temperature: Double = 0.3,
        @SerialName("response_format") val responseFormat: OpenAiFormat? = null,
    )

    @Serializable data class OpenAiChoice(
        val message: OpenAiMsg,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    data class OpenAiUsage(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
        @SerialName("total_tokens") val totalTokens: Int = 0,
    )

    @Serializable
    data class OpenAiResponse(
        val choices: List<OpenAiChoice> = emptyList(),
        val usage: OpenAiUsage? = null,
    )
}
