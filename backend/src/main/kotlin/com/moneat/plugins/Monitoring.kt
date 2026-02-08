package com.moneat.plugins

import com.moneat.config.SentryConfig
import com.moneat.utils.SentryUtils
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SpanStatus
import mu.KotlinLogging
import org.slf4j.event.Level

private val logger = KotlinLogging.logger {}

// Attribute key for storing Sentry transaction in call
private val SentryTransactionKey = AttributeKey<ITransaction>("SentryTransaction")

fun Application.configureMonitoring() {
    // Sentry transaction interceptor for all requests
    intercept(ApplicationCallPipeline.Setup) {
        if (SentryConfig.isEnabled()) {
            val method = call.request.httpMethod.value
            val path = call.request.path()
            
            // Create transaction for this HTTP request
            val transaction = Sentry.startTransaction("$method $path", "http.server")
            transaction.setData("http.method", method)
            transaction.setData("http.url", path)
            transaction.setData("http.query", call.request.queryString())
            
            // Store transaction in call attributes
            call.attributes.put(SentryTransactionKey, transaction)
            
            // Add breadcrumb for request
            SentryUtils.breadcrumb("http.request", "HTTP $method $path", mapOf(
                "method" to method,
                "path" to path,
                "query" to (call.request.queryString().takeIf { it.isNotEmpty() } ?: "")
            ))
            
            try {
                proceed()
                
                // Set response status
                val status = call.response.status()
                transaction.setData("http.status_code", status?.value ?: 0)
                
                // Determine transaction status based on HTTP status code
                transaction.status = when (status?.value) {
                    in 200..299 -> SpanStatus.OK
                    in 400..499 -> SpanStatus.INVALID_ARGUMENT
                    in 500..599 -> SpanStatus.INTERNAL_ERROR
                    else -> SpanStatus.UNKNOWN_ERROR
                }
                
                // Add breadcrumb for response
                SentryUtils.breadcrumb("http.response", "HTTP ${status?.value ?: 0}", mapOf(
                    "status" to (status?.value ?: 0),
                    "description" to (status?.description ?: "")
                ))
            } catch (e: Exception) {
                transaction.status = SpanStatus.INTERNAL_ERROR
                transaction.throwable = e
                throw e
            } finally {
                transaction.finish()
            }
        } else {
            proceed()
        }
    }
    
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception: ${cause.message}" }
            
            // Send to Sentry if enabled
            if (SentryConfig.isEnabled()) {
                Sentry.captureException(cause) { scope ->
                    scope.setTag("http.method", call.request.httpMethod.value)
                    scope.setTag("http.path", call.request.path())
                    scope.setTag("http.status_code", "500")
                    scope.setExtra("user_agent", call.request.headers["User-Agent"] ?: "unknown")
                    scope.setExtra("remote_host", call.request.local.remoteHost)
                    scope.setExtra("query_string", call.request.queryString())
                    
                    // Add request headers as extra context (excluding sensitive ones)
                    val safeHeaders = call.request.headers.entries()
                        .filter { (key, _) -> 
                            !key.equals("Authorization", ignoreCase = true) && 
                            !key.equals("Cookie", ignoreCase = true)
                        }
                        .associate { (key, values) -> key to values.joinToString(", ") }
                    scope.setExtra("request_headers", safeHeaders.toString())
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

/**
 * Get the current Sentry transaction for this call
 */
fun ApplicationCall.getSentryTransaction(): ITransaction? {
    return attributes.getOrNull(SentryTransactionKey)
}
