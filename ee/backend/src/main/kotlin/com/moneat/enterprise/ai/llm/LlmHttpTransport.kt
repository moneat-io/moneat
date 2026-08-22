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
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

data class LlmHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

data class LlmHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

fun interface LlmHttpTransport {
    suspend fun post(request: LlmHttpRequest): LlmHttpResponse
}

internal class LlmRetryPolicy(
    val delayAction: suspend (Long) -> Unit = { delayMillis -> delay(delayMillis) },
    val currentTime: () -> Instant = Instant::now,
    val jitterSource: (Long) -> Long = ::randomJitter,
)

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
        return LlmHttpResponse(
            status = response.status.value,
            body = response.bodyAsText(),
            headers = response.headers.entries().associate { (name, values) ->
                name to values.firstOrNull().orEmpty()
            },
        )
    }
}

internal suspend fun executeLlmRequest(
    transport: LlmHttpTransport,
    request: LlmHttpRequest,
    maxRetries: Int,
    retryPolicy: LlmRetryPolicy = LlmRetryPolicy(),
): LlmHttpResponse {
    var attempt = 0
    while (true) {
        val response = try {
            transport.post(request)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (attempt >= maxRetries) throw exception
            retryPolicy.delayAction(fallbackRetryDelayMillis(attempt, retryPolicy.jitterSource))
            attempt += 1
            continue
        }

        if (!response.isRetryable() || attempt >= maxRetries) return response
        retryPolicy.delayAction(
            response.retryDelayMillis(attempt, retryPolicy.currentTime(), retryPolicy.jitterSource),
        )
        attempt += 1
    }
}

private fun LlmHttpResponse.isRetryable(): Boolean = status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR

private fun LlmHttpResponse.retryDelayMillis(
    attempt: Int,
    currentTime: Instant,
    jitterSource: (Long) -> Long,
): Long {
    if (status != HTTP_TOO_MANY_REQUESTS) return fallbackRetryDelayMillis(attempt, jitterSource)
    val retryAfter = headers.entries
        .firstOrNull { (name, _) -> name.equals(RETRY_AFTER_HEADER, ignoreCase = true) }
        ?.value
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return fallbackRetryDelayMillis(attempt, jitterSource)

    val delayMillis = retryAfter.toLongOrNull()
        ?.let(::secondsToMillis)
        ?: parseRetryAfterDateMillis(retryAfter, currentTime)
        ?: return fallbackRetryDelayMillis(attempt, jitterSource)
    return delayMillis.coerceIn(0L, MAX_RETRY_DELAY_MILLIS)
}

private fun secondsToMillis(seconds: Long): Long = when {
    seconds <= 0 -> 0
    seconds >= MAX_RETRY_DELAY_MILLIS / MILLIS_PER_SECOND -> MAX_RETRY_DELAY_MILLIS
    else -> seconds * MILLIS_PER_SECOND
}

private fun parseRetryAfterDateMillis(value: String, currentTime: Instant): Long? = runCatching {
    val retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    Duration.between(currentTime, retryAt).toMillis().coerceAtLeast(0)
}.getOrNull()

private fun fallbackRetryDelayMillis(attempt: Int, jitterSource: (Long) -> Long): Long {
    val multiplier = 1L shl attempt.coerceIn(0, MAX_BACKOFF_EXPONENT)
    val baseDelay = (RETRY_BASE_DELAY_MILLIS * multiplier).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
    val maxJitter = minOf(baseDelay / 2, MAX_RETRY_JITTER_MILLIS)
    val jitter = jitterSource(maxJitter).coerceIn(0L, maxJitter)
    return (baseDelay + jitter).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
}

private fun randomJitter(maxInclusive: Long): Long = if (maxInclusive <= 0) {
    0
} else {
    Random.nextLong(maxInclusive + 1)
}

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private const val RETRY_BASE_DELAY_MILLIS = 100L
private const val MAX_RETRY_DELAY_MILLIS = 60_000L
private const val MAX_RETRY_JITTER_MILLIS = 250L
private const val MAX_BACKOFF_EXPONENT = 8
private const val MILLIS_PER_SECOND = 1_000L
private const val RETRY_AFTER_HEADER = "Retry-After"
