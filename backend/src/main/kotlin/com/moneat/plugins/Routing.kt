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
import com.moneat.apm.routes.apmServiceDashboardRoutes
import com.moneat.auth.routes.authRoutes
import com.moneat.auth.routes.authTokenRoutes
import com.moneat.billing.routes.stripeWebhookRoutes
import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.config.RedisConfig
import com.moneat.dashboards.routes.customDashboardRoutes
import com.moneat.enterprise.FeatureRegistry
import com.moneat.events.routes.apiRoutes
import com.moneat.events.routes.ingestRoutes
import com.moneat.events.routes.releaseRoutes
import com.moneat.events.routes.telemetryIngestRoutes
import com.moneat.featureflags.routes.featureFlagRoutes
import com.moneat.incident.routes.incidentProviderRoutes
import com.moneat.llm.routes.llmIngestRoutes
import com.moneat.llm.routes.llmRoutes
import com.moneat.logs.routes.logIngestRoutes
import com.moneat.logs.routes.logRoutes
import com.moneat.monitor.routes.cloudSourceRoutes
import com.moneat.monitor.routes.infraRoutes
import com.moneat.monitor.routes.monitorRoutes
import com.moneat.monitor.routes.resourceCatalogRoutes
import com.moneat.mcp.McpModule
import com.moneat.monitoring.OperationalMetrics
import com.moneat.org.routes.adminRoutes
import com.moneat.org.routes.orgManagementRoutes
import com.moneat.otlp.routes.otlpMetricsRoutes
import com.moneat.otlp.routes.otlpFeedbackRoutes
import com.moneat.otlp.routes.otlpTraceRoutes
import com.moneat.overview.routes.overviewRoutes
import com.moneat.security.detection.detectionRuleRoutes
import com.moneat.security.signals.signalRoutes
import com.moneat.security.vulnerabilities.vulnerabilityRoutes
import com.moneat.statuspage.routes.statusPageRoutes
import com.moneat.summary.routes.summaryRoutes
import com.moneat.uptime.routes.uptimeRoutes
import com.moneat.workflows.routes.workflowRoutes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.moneat.utils.suspendRunCatching

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
    val postgresStatus = dependencyStatus("postgres") {
        transaction {
            exec("SELECT phone_number FROM users LIMIT 1")
            exec("SELECT pending_meter_batch_id, pending_meter_batch_units FROM subscriptions LIMIT 1")
        }
        true
    }
    val clickhouseStatus = dependencyStatus("clickhouse") {
        ClickHouseClient.ping()
    }
    val redisStatus = dependencyStatus("redis") {
        RedisConfig.isConnected() && RedisConfig.sync().ping().equals("PONG", ignoreCase = true)
    }
    val ingestQueueDepth =
        suspendRunCatching {
            if (RedisConfig.isConnected()) {
                val queueKey =
                    call.application.environment.config
                        .property("ingest.queueKey")
                        .getString()
                OperationalMetrics.registerQueue("Event", queueKey, "primary")
                RedisConfig.sync().llen(queueKey)
            } else {
                0L
            }
        }.getOrElse { _ ->
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

private suspend fun dependencyStatus(
    dependency: String,
    check: suspend () -> Boolean,
): String {
    val startedAt = System.nanoTime()
    val result = suspendRunCatching { check() }
    val durationSeconds = (System.nanoTime() - startedAt).toDouble() / NANOS_PER_SECOND
    return result.fold(
        onSuccess = { healthy ->
            OperationalMetrics.recordDependencyHealth(dependency, healthy, durationSeconds)
            if (healthy) "ok" else "error"
        },
        onFailure = { cause ->
            OperationalMetrics.recordDependencyHealth(dependency, healthy = false, durationSeconds, cause)
            "error"
        }
    )
}

private val routingLogger = mu.KotlinLogging.logger("Routing")
private const val METRICS_CONTENT_TYPE = "text/plain; version=0.0.4"
private const val METRICS_SCRAPE_TOKEN = "metrics.scrapeToken"
private const val NANOS_PER_SECOND = 1_000_000_000.0

fun Application.configureRouting() {
    routing {
        routingLogger.info { "Starting route registration..." }

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

        get("/v1/features") {
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

        get("/metrics") {
            respondWithOperationalMetrics(call)
        }

        get("/metrics/") {
            respondWithOperationalMetrics(call)
        }

        routingLogger.info { "Registering ingestion routes..." }
        // Sentry-compatible ingestion endpoints (rate limited per project key)
        rateLimit(RateLimitName("ingestion")) {
            ingestRoutes()
            llmIngestRoutes()
        }
        routingLogger.info { "Ingestion routes registered" }

        // Telemetry pulse receiver — rate-limited by IP (unauthenticated, anonymous heartbeats)
        rateLimit(RateLimitName("telemetry")) {
            telemetryIngestRoutes()
        }

        // Stripe webhooks
        stripeWebhookRoutes()

        // Dashboard API endpoints
        apiRoutes()

        // Authenticated workspace overview aggregate
        overviewRoutes()

        // APM service dashboard endpoints are source-neutral and must not depend on vendor modules.
        apmServiceDashboardRoutes()

        // OpenFeature-compatible feature flag management and OFREP runtime endpoints
        featureFlagRoutes()

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
        resourceCatalogRoutes()
        cloudSourceRoutes()

        // Infra endpoints (containers, processes — deduplicated)
        infraRoutes()

        // Logging ingestion and query endpoints
        rateLimit(RateLimitName("log-ingestion")) {
            logIngestRoutes()
        }
        logRoutes()

        // OTLP trace and metrics ingestion endpoints
        rateLimit(RateLimitName("otlp-ingestion")) {
            otlpTraceRoutes()
            otlpMetricsRoutes()
            otlpFeedbackRoutes()
        }

        // Uptime monitoring endpoints
        uptimeRoutes()

        // Status page endpoints
        statusPageRoutes()

        // Summary and report endpoints
        summaryRoutes()

        // Incident provider integration endpoints
        incidentProviderRoutes()

        // Organization team management endpoints
        orgManagementRoutes()

        // Workflow automation endpoints
        rateLimit(RateLimitName("api")) {
            workflowRoutes()
        }

        // Security signals triage surface (OSS core)
        rateLimit(RateLimitName("api")) {
            signalRoutes()
        }

        // Detection rules: scheduled, declarative rules over logs → signals (OSS core)
        rateLimit(RateLimitName("api")) {
            detectionRuleRoutes()
        }

        // Vulnerability/SBOM inventory and findings (OSS core)
        rateLimit(RateLimitName("api")) {
            vulnerabilityRoutes(includeAgentRoutes = false)
        }

        // SBOM compatibility ingest is OSS core so direct package inventory works without EE.
        rateLimit(RateLimitName("datadog-ingestion")) {
            vulnerabilityRoutes(includeApiRoutes = false)
        }

        routingLogger.info { "Registering enterprise routes..." }
        // Enterprise modules (SSO, On-Call, etc.) — registered via ServiceLoader
        FeatureRegistry.registerRoutes(this)
        routingLogger.info { "Enterprise routes registered" }

        routingLogger.info { "Registering MCP routes..." }
        McpModule.registerRoutes(this)
        routingLogger.info { "MCP routes registered" }

        aiChatRoutes()

        // Custom dashboard builder endpoints
        customDashboardRoutes()

        routingLogger.info { "All routes registered successfully" }
    }
}

private suspend fun respondWithOperationalMetrics(call: io.ktor.server.application.ApplicationCall) {
    val scrapeToken = call.application.environment.config.propertyOrNull(METRICS_SCRAPE_TOKEN)?.getString()
    if (!scrapeToken.isNullOrBlank()) {
        val authorization = call.request.header(HttpHeaders.Authorization)
        if (authorization != "Bearer $scrapeToken") {
            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            return
        }
    }

    call.respondText(
        OperationalMetrics.scrape(),
        ContentType.parse(METRICS_CONTENT_TYPE)
    )
}
