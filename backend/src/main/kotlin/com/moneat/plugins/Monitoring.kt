package com.moneat.plugins

import com.moneat.config.SentryConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.sentry.Sentry
import io.sentry.SentryLevel
import mu.KotlinLogging
import org.slf4j.event.Level

private val logger = KotlinLogging.logger {}

fun Application.configureMonitoring() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception: ${cause.message}" }
            
            // Send to Sentry if enabled
            if (SentryConfig.isEnabled()) {
                Sentry.captureException(cause) { scope ->
                    scope.setTag("http.method", call.request.httpMethod.value)
                    scope.setTag("http.path", call.request.path())
                    scope.setExtra("user_agent", call.request.headers["User-Agent"] ?: "unknown")
                    scope.setExtra("remote_host", call.request.local.remoteHost)
                }
            }
            
            cause.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Internal server error", "message" to (cause.message ?: "Unknown error"))
            )
        }
    }
    
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
        format { call ->
            val status = call.response.status()
            val httpMethod = call.request.httpMethod.value
            val userAgent = call.request.headers["User-Agent"]
            "Status: $status, HTTP method: $httpMethod, User agent: $userAgent"
        }
    }
}
