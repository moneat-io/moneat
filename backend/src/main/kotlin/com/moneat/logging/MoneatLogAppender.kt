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

package com.moneat.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.spi.DeferredProcessingAware
import java.net.HttpURLConnection
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import com.moneat.utils.suspendRunCatching
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MAX
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MIN

class MoneatLogAppender : AppenderBase<ILoggingEvent>() {
    var endpoint: String = "https://api.moneat.io/v1/logs/otlp"
    var token: String = ""
    var serviceName: String = "moneat-backend"
    var environment: String = "development"

    companion object {
        private const val QUEUE_CAPACITY = 10_000
        private const val LOG_HTTP_TIMEOUT_MS = 1000
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
        private const val RATE_LIMIT_BACKOFF_MS = 5L * 60 * 1_000 // 5 minutes
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }

    @Volatile private var rateLimitedUntil: Long = 0L

    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(QUEUE_CAPACITY),
            RejectedExecutionHandler { _, _ ->
                // Drop when queue full to avoid OOM; caller continues without blocking
            }
        )

    private val isProdEndpoint get() = endpoint.contains("api.moneat.io")

    override fun start() {
        super.start()
        if (token.isBlank()) {
            token = System.getenv("MONEAT_LOGS_TOKEN") ?: System.getProperty("MONEAT_LOGS_TOKEN") ?: ""
        }
        if (endpoint.isBlank() || endpoint == "https://api.moneat.io/v1/logs/otlp") {
            val envEndpoint = System.getenv("MONEAT_LOGS_ENDPOINT") ?: System.getProperty("MONEAT_LOGS_ENDPOINT")
            if (!envEndpoint.isNullOrBlank()) endpoint = envEndpoint
        }
        if (environment == "development" && isProdEndpoint) {
            System.err.println(
                "[MoneatLogAppender] SAFETY: endpoint points to production but environment=development - logs will " +
                    "NOT be shipped. Set MONEAT_LOGS_ENDPOINT to your local instance."
            )
            return
        }
        if (token.isBlank()) {
            System.err.println("[MoneatLogAppender] WARNING: token is blank - logs will NOT be shipped to remote")
        } else {
            System.err.println(
                "[MoneatLogAppender] Initialized: endpoint=$endpoint, serviceName=$serviceName, " +
                    "environment=$environment, token=<set>"
            )
        }
    }

    override fun append(event: ILoggingEvent) {
        if (token.isBlank() || (environment == "development" && isProdEndpoint)) return
        if (System.currentTimeMillis() < rateLimitedUntil) return

        (event as? DeferredProcessingAware)?.prepareForDeferredProcessing()

        executor.submit {
            suspendRunCatching {
                sendLog(event)
            }.getOrElse { e ->
                // Use stderr to avoid log loops
                System.err.println("[MoneatLogAppender] Failed to ship log: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun sendLog(event: ILoggingEvent) {
        val payload =
            """
{
  "resourceLogs": [{
    "resource": {
      "attributes": [
        {"key": "service.name", "value": {"stringValue": "$serviceName"}},
        {"key": "deployment.environment", "value": {"stringValue": "$environment"}}
      ]
    },
    "scopeLogs": [{
      "logRecords": [{
        "timeUnixNano": "${event.timeStamp}000000",
        "severityText": "${event.level}",
        "body": {"stringValue": ${escapeJson(event.formattedMessage)}}
      }]
    }]
  }]
}
            """.trimIndent()

        val connection =
            java.net
                .URI(endpoint)
                .toURL()
                .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.doOutput = true
            connection.connectTimeout = LOG_HTTP_TIMEOUT_MS
            connection.readTimeout = LOG_HTTP_TIMEOUT_MS

            connection.outputStream.use { it.write(payload.toByteArray()) }
            val responseCode = connection.responseCode
            if (responseCode == HTTP_TOO_MANY_REQUESTS) {
                rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
            } else if (responseCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                val body = runCatching { connection.errorStream?.bufferedReader()?.readText() }.getOrNull()
                System.err.println(
                    "[MoneatLogAppender] Non-2xx response: $responseCode${if (body != null) " - $body" else ""}"
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun escapeJson(str: String): String {
        return "\"" +
            str
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\""
    }

    override fun stop() {
        executor.shutdown()
        executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        super.stop()
    }
}
