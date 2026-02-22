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

class MoneatLogAppender : AppenderBase<ILoggingEvent>() {
    var endpoint: String = "https://api.moneat.io/v1/logs/otlp"
    var dsn: String = ""
    var serviceName: String = "moneat-backend"
    var environment: String = "development"

    companion object {
        private const val QUEUE_CAPACITY = 10_000
    }

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
        if (dsn.isBlank()) {
            dsn = System.getenv("MONEAT_LOGS_DSN") ?: System.getProperty("MONEAT_LOGS_DSN") ?: ""
        }
        if (endpoint.isBlank() || endpoint == "https://api.moneat.io/v1/logs/otlp") {
            val envEndpoint = System.getenv("MONEAT_LOGS_ENDPOINT") ?: System.getProperty("MONEAT_LOGS_ENDPOINT")
            if (!envEndpoint.isNullOrBlank()) endpoint = envEndpoint
        }
        if (environment == "development" && isProdEndpoint) {
            System.err.println(
                "[MoneatLogAppender] SAFETY: endpoint points to production but environment=development - logs will NOT be shipped. Set MONEAT_LOGS_ENDPOINT to your local instance."
            )
            return
        }
        if (dsn.isBlank()) {
            System.err.println("[MoneatLogAppender] WARNING: DSN is blank - logs will NOT be shipped to remote")
        } else {
            System.err.println(
                "[MoneatLogAppender] Initialized: endpoint=$endpoint, serviceName=$serviceName, environment=$environment, dsn=<set>"
            )
        }
    }

    override fun append(event: ILoggingEvent) {
        if (dsn.isBlank() || (environment == "development" && isProdEndpoint)) return

        (event as? DeferredProcessingAware)?.prepareForDeferredProcessing()

        executor.submit {
            try {
                sendLog(event)
            } catch (e: Exception) {
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
            connection.setRequestProperty("X-Moneat-Dsn", dsn)
            connection.doOutput = true
            connection.connectTimeout = 1000
            connection.readTimeout = 1000

            connection.outputStream.use { it.write(payload.toByteArray()) }
            connection.responseCode // Trigger request
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
        executor.awaitTermination(5, TimeUnit.SECONDS)
        super.stop()
    }
}
