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

package com.moneat.config

import com.moneat.monitoring.OperationalMetrics
import com.moneat.utils.SentryUtils
import com.moneat.utils.suspendRunCatching
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.events.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.sentry.ISpan
import io.sentry.Sentry
import kotlinx.coroutines.CancellationException
import mu.KotlinLogging
import java.util.Locale

private val CLICKHOUSE_ERROR_CODE = Regex("""^Code:\s*(\d+)""")
private val logger = KotlinLogging.logger {}

class ClickHouseQueryException(
    val isTimeout: Boolean,
    internalDetail: String,
) : RuntimeException(if (isTimeout) "Query timed out" else "Query failed") {
    val detail: String = internalDetail
}

object ClickHouseClient {
    private const val MIGRATION_TIMEOUT_MS = 600_000L
    private const val HTTP_MAX_CONNECTIONS = 100
    private const val KEEP_ALIVE_MS = 5_000L
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val SOCKET_TIMEOUT_MS = 30_000L
    private const val MIGRATION_MAX_CONNECTIONS = 4
    private const val QUERY_LOG_MAX_LEN = 8192
    private const val ERROR_BODY_MAX_LEN = 500
    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val READ_QUERY_MAX_EXECUTION_SECONDS = 10
    private const val SLOW_QUERY_THRESHOLD_SECONDS = 3.0
    private const val CLICKHOUSE_TIMEOUT_ERROR_CODE = "159"
    private const val CLICKHOUSE_TIMEOUT_ERROR_NAME = "TIMEOUT_EXCEEDED"
    private const val CLICKHOUSE_TIMEOUT_MESSAGE = "timeout exceeded"

    @Volatile
    private var httpClient: HttpClient? = null

    @Volatile
    private var migrationClient: HttpClient? = null
    private var baseUrl: String = ""
    private var database: String = ""
    private var user: String = ""
    private var password: String = ""

    fun init(
        baseUrl: String,
        database: String,
        user: String,
        password: String
    ) {
        if (this.httpClient != null) return
        this.baseUrl = baseUrl
        this.database = database
        this.user = user
        this.password = password
        this.httpClient =
            HttpClient(CIO) {
                engine {
                    maxConnectionsCount = HTTP_MAX_CONNECTIONS
                    endpoint {
                        keepAliveTime = KEEP_ALIVE_MS
                        connectTimeout = CONNECT_TIMEOUT_MS
                        socketTimeout = SOCKET_TIMEOUT_MS
                    }
                }
            }
        this.migrationClient =
            HttpClient(CIO) {
                engine {
                    maxConnectionsCount = MIGRATION_MAX_CONNECTIONS
                    endpoint {
                        keepAliveTime = KEEP_ALIVE_MS
                        connectTimeout = CONNECT_TIMEOUT_MS
                        socketTimeout = MIGRATION_TIMEOUT_MS
                    }
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = MIGRATION_TIMEOUT_MS
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = MIGRATION_TIMEOUT_MS
                }
            }
    }

    suspend fun execute(
        query: String,
        span: ISpan? = null
    ): HttpResponse {
        val client = checkNotNull(httpClient) { "ClickHouseClient is not initialized. Call init() first." }
        return if (span != null && Sentry.isEnabled()) {
            SentryUtils.withSpan(span, "db.clickhouse", "ClickHouse query") { childSpan ->
                childSpan?.setData("db.system", "clickhouse")
                childSpan?.setData("db.name", database)
                childSpan?.setData("db.statement", query.take(QUERY_LOG_MAX_LEN)) // Truncate long queries

                executePost(client, query, "execute")
            }
        } else {
            executePost(client, query, "execute")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executePost(
        client: HttpClient,
        query: String,
        operation: String
    ): HttpResponse {
        val startedAt = System.nanoTime()
        try {
            val response = client.post(baseUrl) {
                parameter("database", database)
                if (operation == "execute" && isReadQuery(query)) {
                    parameter("max_execution_time", READ_QUERY_MAX_EXECUTION_SECONDS)
                    parameter("timeout_overflow_mode", "throw")
                    parameter("timeout_before_checking_execution_speed", "0")
                }
                header("X-ClickHouse-User", user)
                header("X-ClickHouse-Key", password)
                contentType(ContentType.Text.Plain)
                setBody(query)
            }
            val elapsed = elapsedSeconds(startedAt)
            val status = if (response.status.isSuccess()) "success" else "http_${response.status.value}"
            OperationalMetrics.recordClickHouseRequest(operation, status, elapsed)
            if (elapsed >= SLOW_QUERY_THRESHOLD_SECONDS) {
                val elapsedText = String.format("%.1f", elapsed)
                val truncatedQuery = query.take(QUERY_LOG_MAX_LEN)
                logger.warn { "Slow ClickHouse query (${elapsedText}s): $truncatedQuery" }
            }
            return response
        } catch (e: HttpRequestTimeoutException) {
            OperationalMetrics.recordClickHouseRequestFailure(operation, e, elapsedSeconds(startedAt))
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OperationalMetrics.recordClickHouseRequestFailure(operation, e, elapsedSeconds(startedAt))
            throw e
        }
    }

    private fun elapsedSeconds(startedAt: Long): Double =
        (System.nanoTime() - startedAt) / NANOS_PER_SECOND

    private fun isReadQuery(query: String): Boolean {
        val normalized = query.trimStart().uppercase(Locale.ROOT)
        return normalized.startsWith("SELECT") ||
            normalized.startsWith("WITH") ||
            normalized.startsWith("SHOW") ||
            normalized.startsWith("DESCRIBE") ||
            normalized.startsWith("DESC") ||
            normalized.startsWith("EXISTS") ||
            normalized.startsWith("EXPLAIN")
    }

    suspend fun executeWithFormat(
        query: String,
        format: String,
        span: ISpan? = null
    ): String {
        val queryWithFormat = if (query.trimEnd().uppercase(Locale.ROOT).contains("FORMAT")) {
            query
        } else {
            "$query FORMAT $format"
        }
        val response = execute(queryWithFormat, span)
        val body = response.bodyAsText()
        val isError = response.isClickHouseError(body)
        if (isError) {
            val errorCode = clickHouseErrorCode(body)
            OperationalMetrics.recordClickHouseQueryError("execute", errorCode)
            val detail = "ClickHouse query failed (${response.status.value}): ${body.take(ERROR_BODY_MAX_LEN)}"
            logger.error { "$detail | query: ${query.take(QUERY_LOG_MAX_LEN)}" }
            throw ClickHouseQueryException(
                isTimeout = isClickHouseTimeout(body, errorCode),
                internalDetail = detail,
            )
        }
        return body
    }

    private fun isClickHouseTimeout(body: String, errorCode: String): Boolean {
        return errorCode == CLICKHOUSE_TIMEOUT_ERROR_CODE ||
            body.contains(CLICKHOUSE_TIMEOUT_ERROR_NAME, ignoreCase = true) ||
            body.contains(CLICKHOUSE_TIMEOUT_MESSAGE, ignoreCase = true)
    }

    /**
     * Execute a query using the migration client with extended timeouts.
     * Use for DDL and data-copy statements that may run for minutes.
     */
    suspend fun executeMigration(query: String): HttpResponse {
        val client = checkNotNull(migrationClient) { "ClickHouseClient is not initialized. Call init() first." }
        return executePost(client, query, "migration")
    }

    /**
     * Execute a long-running write statement (e.g. a background rollup/finalization INSERT...SELECT)
     * on the extended-timeout migration client, throwing [ClickHouseQueryException] on failure.
     * The normal [execute] path uses a 30s socket timeout, which is too short for these.
     */
    suspend fun executeLongRunning(query: String, operation: String = "finalize") {
        val client = checkNotNull(migrationClient) { "ClickHouseClient is not initialized. Call init() first." }
        val response = executePost(client, query, operation)
        val body = response.bodyAsText()
        if (response.isClickHouseError(body)) {
            val errorCode = clickHouseErrorCode(body)
            OperationalMetrics.recordClickHouseQueryError(operation, errorCode)
            val detail = "ClickHouse $operation failed (${response.status.value}): ${body.take(ERROR_BODY_MAX_LEN)}"
            logger.error { "$detail | query: ${query.take(QUERY_LOG_MAX_LEN)}" }
            throw ClickHouseQueryException(
                isTimeout = isClickHouseTimeout(body, errorCode),
                internalDetail = detail,
            )
        }
    }

    suspend fun ping(): Boolean {
        return suspendRunCatching {
            val response = httpClient!!.get("$baseUrl/ping")
            response.status == HttpStatusCode.OK
        }.getOrElse { _ ->
            false
        }
    }

    fun isInitialized(): Boolean = httpClient != null

    fun getDatabase(): String = database

    fun close() {
        httpClient?.close()
        httpClient = null
        migrationClient?.close()
        migrationClient = null
    }
}

private fun clickHouseErrorCode(body: String): String =
    CLICKHOUSE_ERROR_CODE.find(body.trimStart())?.groupValues?.get(1) ?: "unknown"

/** Returns true if the response body represents a ClickHouse error (e.g. "Code: 60, DB::Exception..."). */
fun String.isClickHouseError(): Boolean = trimStart().startsWith("Code:")

/** Returns true if the HTTP response or body indicates a ClickHouse failure. */
fun HttpResponse.isClickHouseError(body: String): Boolean = !status.isSuccess() || body.isClickHouseError()

fun Application.configureClickHouse() {
    // Skip ClickHouse in test environment if not configured
    val url =
        suspendRunCatching {
            environment.config.property("database.clickhouse.url").getString()
        }.getOrElse { _ ->
            log.warn("ClickHouse URL not configured, skipping ClickHouse initialization (test environment)")
            return
        }

    suspendRunCatching {
        val config = environment.config
        val database = config.property("database.clickhouse.database").getString()
        val user = config.property("database.clickhouse.user").getString()
        val password = config.property("database.clickhouse.password").getString()
        log.info("Initializing ClickHouse client for $url...")
        ClickHouseClient.init(url, database, user, password)
        log.info("ClickHouse client initialized")
        // Note: Shutdown is handled by BackgroundJobs to ensure correct ordering
        // (workers must stop before ClickHouse client is closed)
    }.getOrElse { e ->
        log.error("Failed to initialize ClickHouse client. Make sure ClickHouse is running and accessible.", e)
        throw e
    }
}
