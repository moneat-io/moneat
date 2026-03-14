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

package com.moneat.testsupport

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MockHttpServer(
    private val handler: (HttpExchange) -> Unit
) : AutoCloseable {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handler(exchange) }
            this.executor = this@MockHttpServer.executor
            start()
        }

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

fun HttpExchange.requestBodyText(): String {
    return requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
}

fun HttpExchange.respond(
    status: Int,
    body: String,
    contentType: String = "application/json"
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

/**
 * Default ClickHouse handler that returns empty data for common query patterns.
 * Use when tests only need "no data" responses.
 */
fun defaultEmptyClickHouseHandler(): (HttpExchange) -> Unit = { exchange ->
    val query = exchange.requestBodyText()
    val body = when {
        query.contains("count() as total") -> """{"total":0}"""
        else -> """{"data":[]}"""
    }
    exchange.respond(200, body, "text/plain")
}

/**
 * Builds a ClickHouse mock handler that responds based on query content.
 * Rules are checked in order; first matching substring wins.
 *
 * @param rules Pairs of (query substring to match, response body)
 * @param defaultBody Response when no rule matches
 * @param contentType Content-Type header value
 * @param captureQueries If non-null, appends each query before handling
 */
fun queryBasedClickHouseHandler(
    vararg rules: Pair<String, String>,
    defaultBody: String = "",
    contentType: String = "text/plain",
    captureQueries: MutableList<String>? = null
): (HttpExchange) -> Unit = { exchange ->
    val query = exchange.requestBodyText()
    captureQueries?.add(query)
    val body = rules.firstOrNull { query.contains(it.first) }?.second ?: defaultBody
    exchange.respond(200, body, contentType)
}
