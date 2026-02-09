package com.moneat.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MoneatLogAppender : AppenderBase<ILoggingEvent>() {
    var endpoint: String = "http://localhost:8080/v1/logs/otlp"
    var dsn: String = ""
    var serviceName: String = "moneat-backend"
    var environment: String = "development"
    
    private val executor = Executors.newSingleThreadExecutor()
    
    override fun append(event: ILoggingEvent) {
        if (dsn.isBlank()) return
        
        executor.submit {
            try {
                sendLog(event)
            } catch (e: Exception) {
                // Silently fail to avoid log loops
            }
        }
    }
    
    private fun sendLog(event: ILoggingEvent) {
        val payload = """
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
        
        val connection = URL(endpoint).openConnection() as HttpURLConnection
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
        return "\"" + str
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
