package com.moneat.ai

import com.moneat.config.EnvConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

object OpenAiClient {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        engine {
            requestTimeout = 60_000
        }
    }

    private val apiKey: String
        get() = EnvConfig.get("OPENAI_API_KEY", "")

    val model: String
        get() = EnvConfig.get("OPENAI_MODEL", "gpt-4o-mini")

    val maxTokens: Int
        get() = EnvConfig.get("OPENAI_MAX_TOKENS", "2048").toIntOrNull() ?: 2048

    val isEnabled: Boolean
        get() = EnvConfig.get("AI_CHAT_ENABLED", "false").toBoolean() && apiKey.isNotBlank()

    suspend fun chatCompletion(messages: List<OpenAiMessage>): OpenAiChatResponse {
        val request = OpenAiChatRequest(
            model = model,
            messages = messages,
            max_tokens = maxTokens,
            temperature = 0.3,
            response_format = OpenAiResponseFormat(type = "json_object")
        )

        val response = client.post("https://api.openai.com/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(request)
        }

        val body = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            logger.error { "OpenAI API error (${response.status}): $body" }
            throw RuntimeException("OpenAI API error: ${response.status}")
        }

        return json.decodeFromString(OpenAiChatResponse.serializer(), body)
    }
}
