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

import com.moneat.utils.SentryUtils
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.events.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.sentry.ISpan
import io.sentry.Sentry

object ClickHouseClient {
    private const val MIGRATION_TIMEOUT_MS = 600_000L

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
                    maxConnectionsCount = 100
                    endpoint {
                        keepAliveTime = 5000
                        connectTimeout = 10_000
                        socketTimeout = 30_000
                    }
                }
            }
        this.migrationClient =
            HttpClient(CIO) {
                engine {
                    maxConnectionsCount = 4
                    endpoint {
                        keepAliveTime = 5000
                        connectTimeout = 10_000
                        socketTimeout = MIGRATION_TIMEOUT_MS
                    }
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = MIGRATION_TIMEOUT_MS
                    connectTimeoutMillis = 10_000
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
                childSpan?.setData("db.statement", query.take(200)) // Truncate long queries

                client.post(baseUrl) {
                    parameter("database", database)
                    header("X-ClickHouse-User", user)
                    header("X-ClickHouse-Key", password)
                    contentType(ContentType.Text.Plain)
                    setBody(query)
                }
            }
        } else {
            client.post(baseUrl) {
                parameter("database", database)
                header("X-ClickHouse-User", user)
                header("X-ClickHouse-Key", password)
                contentType(ContentType.Text.Plain)
                setBody(query)
            }
        }
    }

    suspend fun executeWithFormat(
        query: String,
        format: String,
        span: ISpan? = null
    ): String {
        val queryWithFormat = if (query.trimEnd().uppercase().contains("FORMAT")) query else "$query FORMAT $format"
        val response = execute(queryWithFormat, span)
        val body = response.bodyAsText()
        if (response.isClickHouseError(body)) {
            throw IllegalStateException(
                "ClickHouse query failed (${response.status.value}): ${body.take(500)}"
            )
        }
        return body
    }

    /**
     * Execute a query using the migration client with extended timeouts.
     * Use for DDL and data-copy statements that may run for minutes.
     */
    suspend fun executeMigration(query: String): HttpResponse {
        val client = checkNotNull(migrationClient) { "ClickHouseClient is not initialized. Call init() first." }
        return client.post(baseUrl) {
            parameter("database", database)
            header("X-ClickHouse-User", user)
            header("X-ClickHouse-Key", password)
            contentType(ContentType.Text.Plain)
            setBody(query)
        }
    }

    suspend fun ping(): Boolean {
        return try {
            val response = httpClient!!.get("$baseUrl/ping")
            response.status == HttpStatusCode.OK
        } catch (_: Exception) {
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

/** Returns true if the response body represents a ClickHouse error (e.g. "Code: 60, DB::Exception..."). */
fun String.isClickHouseError(): Boolean = trimStart().startsWith("Code:")

/** Returns true if the HTTP response or body indicates a ClickHouse failure. */
fun HttpResponse.isClickHouseError(body: String): Boolean = !status.isSuccess() || body.isClickHouseError()

fun Application.configureClickHouse() {
    // Skip ClickHouse in test environment if not configured
    val url =
        try {
            environment.config.property("database.clickhouse.url").getString()
        } catch (e: Exception) {
            log.warn("ClickHouse URL not configured, skipping ClickHouse initialization (test environment)")
            return
        }

    try {
        val config = environment.config
        val database = config.property("database.clickhouse.database").getString()
        val user = config.property("database.clickhouse.user").getString()
        val password = config.property("database.clickhouse.password").getString()
        log.info("Initializing ClickHouse client for $url...")
        ClickHouseClient.init(url, database, user, password)
        log.info("ClickHouse client initialized")
        // Note: Shutdown is handled by BackgroundJobs to ensure correct ordering
        // (workers must stop before ClickHouse client is closed)
    } catch (e: Exception) {
        log.error("Failed to initialize ClickHouse client. Make sure ClickHouse is running and accessible.", e)
        throw e
    }
}
