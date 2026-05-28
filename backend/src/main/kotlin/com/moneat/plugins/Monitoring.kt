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

package com.moneat.plugins

import kotlinx.serialization.SerializationException
import java.io.IOException

import com.moneat.config.ClickHouseQueryException
import com.moneat.config.SentryConfig
import com.moneat.monitoring.OperationalMetrics
import com.moneat.utils.ErrorResponse
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MAX
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MIN
import com.moneat.utils.SentryUtils
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.http.HttpStatusCode
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SpanStatus
import mu.KotlinLogging
import org.slf4j.event.Level

private val logger = KotlinLogging.logger {}

// Attribute key for storing Sentry transaction in call
private val SentryTransactionKey = AttributeKey<ITransaction>("SentryTransaction")
private val ingestPathRegex = Regex("^/api/[^/]+/(envelope|logs|store|security)/?$")
private val datadogApiV1IntakePaths = setOf(
    "/api/v1/container",
    "/api/v1/discovery",
)
private val datadogApiV2IntakePaths = setOf(
    "/api/v2/contimage",
    "/api/v2/contlcycle",
    "/api/v2/data_streams_messages",
    "/api/v2/databasequery",
    "/api/v2/dbmactivity",
    "/api/v2/dbmhealth",
    "/api/v2/dbmmetadata",
    "/api/v2/dbmmetrics",
    "/api/v2/events",
    "/api/v2/ndm",
    "/api/v2/ndmconfig",
    "/api/v2/ndmflow",
    "/api/v2/ndmtraps",
    "/api/v2/netpath",
    "/api/v2/sbom",
    "/api/v2/synthetics",
)

private const val HTTP_CLIENT_ERROR_MIN = 400
private const val HTTP_CLIENT_ERROR_MAX = 499
private const val HTTP_SERVER_ERROR_MIN = 500
private const val HTTP_SERVER_ERROR_MAX = 599

fun Application.configureMonitoring() {
    OperationalMetrics.bindSystemMetrics()
    install(MicrometerMetrics) {
        registry = OperationalMetrics.registry
    }

    // Sentry transaction interceptor for non-ingestion, non-health requests
    intercept(ApplicationCallPipeline.Setup) {
        if (SentryConfig.isEnabled()) {
            val method = call.request.httpMethod.value
            val path = call.request.path()

            if (shouldSkipTracing(path, method)) {
                proceed()
                return@intercept
            }

            // Create transaction for this HTTP request
            val transaction = Sentry.startTransaction("$method $path", "http.server")
            transaction.setData("http.method", method)
            transaction.setData("http.url", path)
            transaction.setData("http.query", call.request.queryString())

            // Store transaction in call attributes
            call.attributes.put(SentryTransactionKey, transaction)

            // Add breadcrumb for request
            SentryUtils.breadcrumb(
                "http.request",
                "HTTP $method $path",
                mapOf(
                    "method" to method,
                    "path" to path,
                    "query" to (call.request.queryString().takeIf { it.isNotEmpty() } ?: "")
                )
            )

            try {
                proceed()

                // Set response status
                val status = call.response.status()
                transaction.setData("http.status_code", status?.value ?: 0)

                // Determine transaction status based on HTTP status code
                transaction.status =
                    when (status?.value) {
                        in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX -> SpanStatus.OK
                        in HTTP_CLIENT_ERROR_MIN..HTTP_CLIENT_ERROR_MAX -> SpanStatus.INVALID_ARGUMENT
                        in HTTP_SERVER_ERROR_MIN..HTTP_SERVER_ERROR_MAX -> SpanStatus.INTERNAL_ERROR
                        else -> SpanStatus.UNKNOWN_ERROR
                    }

                // Add breadcrumb for response
                SentryUtils.breadcrumb(
                    "http.response",
                    "HTTP ${status?.value ?: 0}",
                    mapOf(
                        "status" to (status?.value ?: 0),
                        "description" to (status?.description ?: "")
                    )
                )
            } catch (e: SerializationException) {
                transaction.status = SpanStatus.INTERNAL_ERROR
                transaction.throwable = e
                throw e
            } catch (e: IOException) {
                transaction.status = SpanStatus.INTERNAL_ERROR
                transaction.throwable = e
                throw e
            } catch (e: IllegalStateException) {
                transaction.status = SpanStatus.INTERNAL_ERROR
                transaction.throwable = e
                throw e
            } catch (e: IllegalArgumentException) {
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
        exception<BadRequestException> { call, cause ->
            if (!call.response.isCommitted) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(cause.message ?: "Bad request"),
                )
            }
        }
        exception<ClickHouseQueryException> { call, cause ->
            logger.error { "ClickHouse query error on ${call.request.path()}: ${cause.detail}" }
            if (SentryConfig.isEnabled()) {
                Sentry.captureException(cause) { scope ->
                    scope.setTag("http.method", call.request.httpMethod.value)
                    scope.setTag("http.path", call.request.path())
                    scope.setTag("clickhouse.timeout", cause.isTimeout.toString())
                    scope.setExtra("clickhouse.detail", cause.detail)
                }
            }
            if (!call.response.isCommitted) {
                val status = if (cause.isTimeout) HttpStatusCode.GatewayTimeout else HttpStatusCode.InternalServerError
                val message = if (cause.isTimeout) "Query timed out" else "Data unavailable"
                call.respond(status, mapOf("error" to message))
            }
        }
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception: ${cause.message}" }

            // Send to Sentry if enabled
            if (SentryConfig.isEnabled()) {
                Sentry.captureException(cause) { scope ->
                    scope.setTag("http.method", call.request.httpMethod.value)
                    scope.setTag("http.path", call.request.path())
                    scope.setTag("http.status_code", "500")

                    val userAgent = call.request.headers["User-Agent"] ?: "unknown"
                    scope.setExtra("user_agent", userAgent)
                    scope.setExtra("remote_host", call.request.local.remoteHost)
                    scope.setExtra("query_string", call.request.queryString())

                    // Add request headers as extra context (excluding sensitive ones)
                    val safeHeaders =
                        call.request.headers
                            .entries()
                            .filter { (key, _) ->
                                !key.equals("Authorization", ignoreCase = true) &&
                                    !key.equals("Cookie", ignoreCase = true)
                            }.associate { (key, values) -> key to values.joinToString(", ") }
                    scope.setExtra("request_headers", safeHeaders.toString())
                }
            }

            cause.printStackTrace()
            if (!call.response.isCommitted) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Internal server error")
                )
            } else {
                logger.debug {
                    "Skipping error response because response is already committed for ${call.request.path()}"
                }
            }
        }
    }

    install(CallLogging) {
        level = Level.TRACE
        filter { call -> call.request.path().startsWith("/") }
        format { call ->
            val status = call.response.status()
            val httpMethod = call.request.httpMethod.value
            val userAgent = call.request.headers["User-Agent"]
            "Status: $status, HTTP method: $httpMethod, User agent: $userAgent"
        }
    }
}

internal fun shouldSkipTracing(path: String, method: String): Boolean {
    val normalizedPath = path.trimEnd('/').ifEmpty { "/" }
    val normalizedMethod = method.uppercase()
    if (normalizedPath == "/health" || normalizedPath.startsWith("/health/")) {
        return true
    }
    if (normalizedPath == "/metrics") {
        return true
    }
    if (normalizedPath.startsWith("/dd/")) {
        return true
    }
    if (ingestPathRegex.matches(normalizedPath)) {
        return true
    }
    if (normalizedMethod != "POST" && normalizedMethod != "PUT") {
        return false
    }
    if (normalizedPath in datadogApiV1IntakePaths || normalizedPath in datadogApiV2IntakePaths) {
        return true
    }
    return normalizedPath in setOf(
        "/v1/logs",
        "/v1/logs/ingest",
        "/v1/logs/otlp",
        "/v1/metrics",
        "/v1/metrics/otlp",
        "/v1/pulse",
        "/v1/traces",
        "/v1/traces/otlp",
    )
}

/**
 * Get the current Sentry transaction for this call
 */
fun ApplicationCall.getSentryTransaction(): ITransaction? {
    return attributes.getOrNull(SentryTransactionKey)
}
