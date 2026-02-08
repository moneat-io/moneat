package com.moneat.config

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*

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

    suspend fun execute(query: String): HttpResponse {
        return httpClient!!.post(baseUrl) {
            parameter("database", database)
            parameter("user", user)
            parameter("password", password)
            contentType(ContentType.Text.Plain)
            setBody(query)
        }
    }

    suspend fun executeWithFormat(query: String, format: String): String {
        val queryWithFormat = if (query.trimEnd().uppercase().contains("FORMAT")) query else "$query FORMAT $format"
        val response = execute(queryWithFormat)
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
    val config = environment.config
    val url = config.property("database.clickhouse.url").getString()
    val database = config.property("database.clickhouse.database").getString()
    val user = config.property("database.clickhouse.user").getString()
    val password = config.property("database.clickhouse.password").getString()
    ClickHouseClient.init(url, database, user, password)
    environment.monitor.subscribe(ApplicationStopped) {
        ClickHouseClient.close()
    }
}
