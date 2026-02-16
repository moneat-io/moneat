// Moneat - Mobile-First Error Monitoring Platform
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

import com.moneat.config.EnvConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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
        get() = apiKey.isNotBlank()

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
