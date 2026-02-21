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

import com.moneat.ai.aiChatRoutes
import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.config.RedisConfig
import com.moneat.enterprise.FeatureRegistry
import com.moneat.routes.adminRoutes
import com.moneat.routes.apiRoutes
import com.moneat.routes.authRoutes
import com.moneat.routes.authTokenRoutes
import com.moneat.routes.incidentProviderRoutes
import com.moneat.routes.ingestRoutes
import com.moneat.routes.llmIngestRoutes
import com.moneat.routes.llmRoutes
import com.moneat.routes.logRoutes
import com.moneat.routes.monitorRoutes
import com.moneat.routes.orgManagementRoutes
import com.moneat.routes.releaseRoutes
import com.moneat.routes.statusPageRoutes
import com.moneat.routes.stripeWebhookRoutes
import com.moneat.routes.telemetryIngestRoutes
import com.moneat.routes.uptimeRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.application.call
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

@Serializable
data class HealthResponse(
    val status: String,
    val postgres: String,
    val clickhouse: String,
    val redis: String,
    val ingestQueueDepth: Long = 0
)

@Serializable
data class FeaturesResponse(
    val enterprise: Boolean,
    val modules: List<String>,
    val selfHost: Boolean
)

private suspend fun respondWithFullHealth(call: io.ktor.server.application.ApplicationCall) {
    val postgresStatus =
        try {
            transaction {
                exec("SELECT phone_number FROM users LIMIT 1")
                exec("SELECT pending_meter_batch_id, pending_meter_batch_units FROM subscriptions LIMIT 1")
            }
            "ok"
        } catch (_: Exception) {
            "error"
        }
    val clickhouseStatus =
        try {
            if (ClickHouseClient.ping()) "ok" else "error"
        } catch (_: Exception) {
            "error"
        }
    val redisStatus =
        try {
            if (RedisConfig.isConnected()) {
                RedisConfig.sync().ping()
                "ok"
            } else {
                "error"
            }
        } catch (_: Exception) {
            "error"
        }
    val ingestQueueDepth =
        try {
            if (RedisConfig.isConnected()) {
                val queueKey =
                    call.application.environment.config
                        .property("ingest.queueKey")
                        .getString()
                RedisConfig.sync().llen(queueKey)
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }
    val status = if (postgresStatus == "ok" && clickhouseStatus == "ok" && redisStatus == "ok") "ok" else "degraded"
    val response = HealthResponse(status, postgresStatus, clickhouseStatus, redisStatus, ingestQueueDepth)
    if (status == "ok") {
        call.respond(response)
    } else {
        call.respond(HttpStatusCode.ServiceUnavailable, response)
    }
}

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Moneat API v0.0.1")
        }

        get("/features") {
            call.respond(
                FeaturesResponse(
                    enterprise = FeatureRegistry.isEnterpriseAvailable,
                    modules = FeatureRegistry.registeredModules.map { it.name },
                    selfHost = EnvConfig.SelfHost.enabled
                )
            )
        }

        // Liveness probe: lightweight check that the JVM and HTTP server are responsive
        get("/health/live") {
            call.respond(mapOf("status" to "ok"))
        }

        // Readiness probe: full dependency check for routing traffic
        get("/health/ready") {
            respondWithFullHealth(call)
        }

        // Legacy health endpoint (backward compatible)
        get("/health") {
            respondWithFullHealth(call)
        }

        // Sentry-compatible ingestion endpoints (rate limited per project key)
        rateLimit(RateLimitName("ingestion")) {
            ingestRoutes()
            llmIngestRoutes()
        }

        // Telemetry pulse receiver — accepts anonymous heartbeats from self-hosted instances
        telemetryIngestRoutes()

        // Stripe webhooks
        stripeWebhookRoutes()

        // Dashboard API endpoints
        apiRoutes()

        // LLM observability API endpoints
        llmRoutes()

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

        // Logging ingestion and query endpoints
        logRoutes()

        // Uptime monitoring endpoints
        uptimeRoutes()

        // Status page endpoints
        statusPageRoutes()

        // Incident provider integration endpoints
        incidentProviderRoutes()

        // Organization team management endpoints
        orgManagementRoutes()

        // Enterprise modules (SSO, On-Call, etc.) — registered via ServiceLoader
        FeatureRegistry.registerRoutes(this)

        // AI chat assistant endpoints
        aiChatRoutes()
    }
}
