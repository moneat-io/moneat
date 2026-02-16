// Moneat - Mobile-First Error Monitoring Platform
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
import com.moneat.config.RedisConfig
import com.moneat.routes.accountDeletionRoutes
import com.moneat.routes.adminRoutes
import com.moneat.routes.apiRoutes
import com.moneat.routes.authRoutes
import com.moneat.routes.authTokenRoutes
import com.moneat.routes.deviceRoutes
import com.moneat.routes.escalationRoutes
import com.moneat.routes.incidentProviderRoutes
import com.moneat.routes.ingestRoutes
import com.moneat.routes.integrationRoutes
import com.moneat.routes.logRoutes
import com.moneat.routes.monitorRoutes
import com.moneat.routes.onCallRoutes
import com.moneat.routes.orgManagementRoutes
import com.moneat.routes.priorityRoutes
import com.moneat.routes.releaseRoutes
import com.moneat.routes.ssoRoutes
import com.moneat.routes.statusPageRoutes
import com.moneat.routes.stripeWebhookRoutes
import com.moneat.routes.uptimeRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.environment
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.response
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class HealthResponse(
    val status: String,
    val postgres: String,
    val clickhouse: String,
    val redis: String,
    val ingestQueueDepth: Long = 0
)

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Moneat API v0.0.1")
        }

        get("/health") {
            val postgresStatus = try {
                transaction { }
                "ok"
            } catch (_: Exception) {
                "error"
            }
            val clickhouseStatus = try {
                if (ClickHouseClient.ping()) "ok" else "error"
            } catch (_: Exception) {
                "error"
            }
            val redisStatus = try {
                if (RedisConfig.isConnected()) {
                    RedisConfig.sync().ping()
                    "ok"
                } else "error"
            } catch (_: Exception) {
                "error"
            }
            val ingestQueueDepth = try {
                if (RedisConfig.isConnected()) {
                    val queueKey = call.application.environment.config.property("ingest.queueKey").getString()
                    RedisConfig.sync().llen(queueKey)
                } else 0L
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

        // Sentry-compatible ingestion endpoints (rate limited per project key)
        rateLimit(RateLimitName("ingestion")) {
            ingestRoutes()
        }

        // Stripe webhooks
        stripeWebhookRoutes()
        
        // Dashboard API endpoints
        apiRoutes()
        
        // Authentication endpoints
        authRoutes()
        
        // SSO endpoints
        ssoRoutes()
        
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
        
        // On-call management endpoints
        onCallRoutes(::getSlackUserGroupSyncService, ::getPushNotificationService)
        escalationRoutes()
        priorityRoutes()
        deviceRoutes()
        incidentRoutes(::getIncidentManagementService)
        
        // AI chat assistant endpoints
        aiChatRoutes()
    }
}
