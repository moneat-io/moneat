package com.moneat.plugins

import com.moneat.routes.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.lettuce.core.RedisURI
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class HealthResponse(
    val status: String,
    val postgres: String,
    val clickhouse: String,
    val redis: String
)

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Moneat API v0.0.1")
        }

        get("/health") {
            val config = call.application.environment.config
            val postgresStatus = try {
                transaction { }
                "ok"
            } catch (_: Exception) {
                "error"
            }
            val clickhouseStatus = try {
                val url = config.property("database.clickhouse.url").getString()
                HttpClient(CIO).use { client ->
                    val response = client.get("$url/ping")
                    if (response.status == HttpStatusCode.OK) "ok" else "error"
                }
            } catch (_: Exception) {
                "error"
            }
            val redisStatus = try {
                val redisUrl = config.property("redis.url").getString()
                val uri = RedisURI.create(redisUrl)
                val redisClient = io.lettuce.core.RedisClient.create(uri)
                try {
                    redisClient.connect().use { connection ->
                        connection.sync().ping()
                        "ok"
                    }
                } finally {
                    redisClient.shutdown()
                }
            } catch (_: Exception) {
                "error"
            }
            val status = if (postgresStatus == "ok" && clickhouseStatus == "ok" && redisStatus == "ok") "ok" else "degraded"
            val response = HealthResponse(status, postgresStatus, clickhouseStatus, redisStatus)
            if (status == "ok") {
                call.respond(response)
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, response)
            }
        }

        // Sentry-compatible ingestion endpoints
        ingestRoutes()
        
        // Dashboard API endpoints
        apiRoutes()
        
        // Authentication endpoints
        authRoutes()
        
        // Auth token management endpoints
        authTokenRoutes()
        
        // Release and source map endpoints
        releaseRoutes()
        
        // Admin dashboard endpoints
        adminRoutes()
        
        // Server monitoring endpoints
        monitorRoutes()
    }
}
