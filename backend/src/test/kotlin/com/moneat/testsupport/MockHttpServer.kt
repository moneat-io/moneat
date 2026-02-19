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
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
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

fun HttpExchange.respond(status: Int, body: String, contentType: String = "application/json") {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
