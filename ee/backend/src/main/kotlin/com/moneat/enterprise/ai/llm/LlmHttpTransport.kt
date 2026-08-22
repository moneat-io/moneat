// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

data class LlmHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

data class LlmHttpResponse(
    val status: Int,
    val body: String,
)

fun interface LlmHttpTransport {
    suspend fun post(request: LlmHttpRequest): LlmHttpResponse
}

class KtorLlmHttpTransport(
    requestTimeoutMillis: Long,
) : LlmHttpTransport {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        engine {
            requestTimeout = requestTimeoutMillis
        }
    }

    override suspend fun post(request: LlmHttpRequest): LlmHttpResponse {
        val response = client.post(request.url) {
            contentType(ContentType.Application.Json)
            headers {
                request.headers.forEach { (name, value) -> append(name, value) }
            }
            setBody(request.body)
        }
        return LlmHttpResponse(response.status.value, response.bodyAsText())
    }
}

internal suspend fun executeLlmRequest(
    transport: LlmHttpTransport,
    request: LlmHttpRequest,
    maxRetries: Int,
): LlmHttpResponse {
    var attempt = 0
    while (true) {
        val response = try {
            transport.post(request)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (attempt >= maxRetries) throw exception
            retryDelay(attempt)
            attempt += 1
            continue
        }

        if (!response.isRetryable() || attempt >= maxRetries) return response
        retryDelay(attempt)
        attempt += 1
    }
}

private fun LlmHttpResponse.isRetryable(): Boolean = status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR

private suspend fun retryDelay(attempt: Int) {
    delay(RETRY_BASE_DELAY_MILLIS * (attempt + 1))
}

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private const val RETRY_BASE_DELAY_MILLIS = 100L
