// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.sso.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MockOidcDiscoveryServer : AutoCloseable {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> respondWithDiscovery(exchange) }
            this.executor = this@MockOidcDiscoveryServer.executor
            start()
        }

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun respondWithDiscovery(exchange: HttpExchange) {
        val requestBaseUrl = "http://127.0.0.1:${exchange.localAddress.port}"
        val body =
            """
            {
              "authorization_endpoint": "$requestBaseUrl/protocol/openid-connect/auth",
              "token_endpoint": "$requestBaseUrl/protocol/openid-connect/token"
            }
            """.trimIndent()
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
