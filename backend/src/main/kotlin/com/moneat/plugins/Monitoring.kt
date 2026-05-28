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
private const val HTTP_METHOD_TAG = "http.method"
private const val HTTP_PATH_TAG = "http.path"
private const val HTTP_STATUS_CODE_TAG = "http.status_code"
private const val HTTP_SERVER_OPERATION = "http.server"
private const val SENTRY_INTERNAL_ERROR_STATUS = "500"

fun Application.configureMonitoring() {
    OperationalMetrics.bindSystemMetrics()
    install(MicrometerMetrics) {
        registry = OperationalMetrics.registry
    }

    installSentryTracing()
    installErrorHandling()
    installRequestLogging()
}

private fun Application.installSentryTracing() {
    // Sentry transaction interceptor for non-ingestion, non-health requests
    intercept(ApplicationCallPipeline.Setup) {
        if (!SentryConfig.isEnabled()) {
            proceed()
            return@intercept
        }

        val method = call.request.httpMethod.value
        val path = call.request.path()
        if (shouldSkipTracing(path, method)) {
            proceed()
            return@intercept
        }

        val queryString = call.request.queryString()
        val transaction = Sentry.startTransaction("$method $path", HTTP_SERVER_OPERATION)
        transaction.setRequestData(method, path, queryString)
        call.attributes.put(SentryTransactionKey, transaction)
        addRequestBreadcrumb(method, path, queryString)

        try {
            proceed()
            recordTransactionResponse(call, transaction)
        } catch (e: Exception) {
            transaction.status = SpanStatus.INTERNAL_ERROR
            transaction.throwable = e
            throw e
        } finally {
            transaction.finish()
        }
    }
}

private fun ITransaction.setRequestData(method: String, path: String, queryString: String) {
    setData(HTTP_METHOD_TAG, method)
    setData("http.url", path)
    setData("http.query", queryString)
}

private fun addRequestBreadcrumb(method: String, path: String, queryString: String) {
    SentryUtils.breadcrumb(
        "http.request",
        "HTTP $method $path",
        mapOf(
            "method" to method,
            "path" to path,
            "query" to (queryString.takeIf { it.isNotEmpty() } ?: "")
        )
    )
}

private fun recordTransactionResponse(call: ApplicationCall, transaction: ITransaction) {
    val status = call.response.status()
    val statusCode = status?.value ?: 0
    transaction.setData(HTTP_STATUS_CODE_TAG, statusCode)
    transaction.status = spanStatusFor(statusCode)
    SentryUtils.breadcrumb(
        "http.response",
        "HTTP $statusCode",
        mapOf(
            "status" to statusCode,
            "description" to (status?.description ?: "")
        )
    )
}

private fun spanStatusFor(statusCode: Int): SpanStatus {
    return when (statusCode) {
        in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX -> SpanStatus.OK
        in HTTP_CLIENT_ERROR_MIN..HTTP_CLIENT_ERROR_MAX -> SpanStatus.INVALID_ARGUMENT
        in HTTP_SERVER_ERROR_MIN..HTTP_SERVER_ERROR_MAX -> SpanStatus.INTERNAL_ERROR
        else -> SpanStatus.UNKNOWN_ERROR
    }
}

private fun Application.installErrorHandling() {
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            handleBadRequest(call, cause)
        }
        exception<ClickHouseQueryException> { call, cause ->
            handleClickHouseQueryException(call, cause)
        }
        exception<Throwable> { call, cause ->
            handleUnhandledException(call, cause)
        }
    }
}

private suspend fun handleBadRequest(call: ApplicationCall, cause: BadRequestException) {
    if (!call.response.isCommitted) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(cause.message ?: "Bad request"),
        )
    }
}

private suspend fun handleClickHouseQueryException(call: ApplicationCall, cause: ClickHouseQueryException) {
    logger.error { "ClickHouse query error on ${call.request.path()}: ${cause.detail}" }
    captureClickHouseQueryException(call, cause)
    if (!call.response.isCommitted) {
        val status = if (cause.isTimeout) HttpStatusCode.GatewayTimeout else HttpStatusCode.InternalServerError
        val message = if (cause.isTimeout) "Query timed out" else "Data unavailable"
        call.respond(status, mapOf("error" to message))
    }
}

private fun captureClickHouseQueryException(call: ApplicationCall, cause: ClickHouseQueryException) {
    if (!SentryConfig.isEnabled()) {
        return
    }
    Sentry.captureException(cause) { scope ->
        scope.setTag(HTTP_METHOD_TAG, call.request.httpMethod.value)
        scope.setTag(HTTP_PATH_TAG, call.request.path())
        scope.setTag("clickhouse.timeout", cause.isTimeout.toString())
        scope.setExtra("clickhouse.detail", cause.detail)
    }
}

private suspend fun handleUnhandledException(call: ApplicationCall, cause: Throwable) {
    logger.error(cause) { "Unhandled exception: ${cause.message}" }
    captureUnhandledException(call, cause)
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

private fun captureUnhandledException(call: ApplicationCall, cause: Throwable) {
    if (!SentryConfig.isEnabled()) {
        return
    }
    Sentry.captureException(cause) { scope ->
        scope.setTag(HTTP_METHOD_TAG, call.request.httpMethod.value)
        scope.setTag(HTTP_PATH_TAG, call.request.path())
        scope.setTag(HTTP_STATUS_CODE_TAG, SENTRY_INTERNAL_ERROR_STATUS)
        scope.setExtra("user_agent", call.request.headers["User-Agent"] ?: "unknown")
        scope.setExtra("remote_host", call.request.local.remoteHost)
        scope.setExtra("query_string", call.request.queryString())
        scope.setExtra("request_headers", call.safeRequestHeaders().toString())
    }
}

private fun ApplicationCall.safeRequestHeaders(): Map<String, String> {
    return request.headers
        .entries()
        .filter { (key, _) ->
            !key.equals("Authorization", ignoreCase = true) &&
                !key.equals("Cookie", ignoreCase = true)
        }.associate { (key, values) -> key to values.joinToString(", ") }
}

private fun Application.installRequestLogging() {
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
