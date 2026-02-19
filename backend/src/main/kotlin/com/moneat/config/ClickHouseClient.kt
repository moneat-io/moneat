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
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.events.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.sentry.ISpan
import io.sentry.Sentry

object ClickHouseClient {
    private var httpClient: HttpClient? = null
    private var baseUrl: String = ""
    private var database: String = ""
    private var user: String = ""
    private var password: String = ""

    fun init(baseUrl: String, database: String, user: String, password: String) {
        if (this.httpClient != null) return
        this.baseUrl = baseUrl
        this.database = database
        this.user = user
        this.password = password
        this.httpClient = HttpClient(CIO) {
            engine {
                maxConnectionsCount = 100
                endpoint {
                    keepAliveTime = 5000
                    connectTimeout = 10_000
                    socketTimeout = 30_000
                }
            }
        }
    }

    suspend fun execute(query: String, span: ISpan? = null): HttpResponse {
        return if (span != null && Sentry.isEnabled()) {
            SentryUtils.withSpan(span, "db.clickhouse", "ClickHouse query") { childSpan ->
                childSpan?.setData("db.system", "clickhouse")
                childSpan?.setData("db.name", database)
                childSpan?.setData("db.statement", query.take(200)) // Truncate long queries

                httpClient!!.post(baseUrl) {
                    parameter("database", database)
                    parameter("user", user)
                    parameter("password", password)
                    contentType(ContentType.Text.Plain)
                    setBody(query)
                }
            }
        } else {
            httpClient!!.post(baseUrl) {
                parameter("database", database)
                parameter("user", user)
                parameter("password", password)
                contentType(ContentType.Text.Plain)
                setBody(query)
            }
        }
    }

    suspend fun executeWithFormat(query: String, format: String, span: ISpan? = null): String {
        val queryWithFormat = if (query.trimEnd().uppercase().contains("FORMAT")) query else "$query FORMAT $format"
        val response = execute(queryWithFormat, span)
        return response.bodyAsText()
    }

    suspend fun ping(): Boolean {
        return try {
            val response = httpClient!!.get("$baseUrl/ping")
            response.status == HttpStatusCode.OK
        } catch (_: Exception) {
            false
        }
    }

    fun getDatabase(): String = database

    fun close() {
        httpClient?.close()
        httpClient = null
    }
}

fun Application.configureClickHouse() {
    // Skip ClickHouse in test environment if not configured
    val url = try {
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
        monitor.subscribe(ApplicationStopping) {
            ClickHouseClient.close()
        }
    } catch (e: Exception) {
        log.error("Failed to initialize ClickHouse client. Make sure ClickHouse is running and accessible.", e)
        throw e
    }
}
