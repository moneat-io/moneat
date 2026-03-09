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

import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.events.routes.extractPublicKey
import com.moneat.logs.services.LogApiKeyService
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.origin
import io.ktor.server.request.ApplicationRequest
import kotlin.time.Duration.Companion.seconds

// Follows the same CF-Connecting-IP → X-Forwarded-For → remoteHost precedence used elsewhere.
private fun ApplicationRequest.clientIp(): String =
    headers["CF-Connecting-IP"]?.trim()?.takeIf { it.isNotBlank() }
        ?: headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ?: origin.remoteHost

private const val INGEST_RATE_LIMIT = 100
private const val INGEST_REFILL_SECONDS = 1
private const val TELEMETRY_RATE_LIMIT = 10
private const val TELEMETRY_REFILL_SECONDS = 60

fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(RateLimitName("ingestion")) {
            requestKey { call ->
                val projectId = call.parameters["projectId"] ?: "unknown"
                val auth = call.request.headers.get("X-Sentry-Auth")
                val sentryKey = call.request.queryParameters["sentry_key"]
                val key = extractPublicKey(auth, sentryKey) ?: "anon"
                "$projectId:$key"
            }
            rateLimiter(limit = 100, refillPeriod = 1.seconds)
        }
        register(RateLimitName("api")) {
            requestKey { call ->
                val principal =
                    call.principal<JWTPrincipal>()
                        ?: call.principal<AuthTokenPrincipal>()
                when (principal) {
                    is JWTPrincipal -> {
                        principal.payload
                            .getClaim("userId")
                            .asInt()
                            .toString()
                    }

                    is AuthTokenPrincipal -> {
                        principal.userId.toString()
                    }

                    else -> {
                        "anon"
                    }
                }
            }
            rateLimiter(limit = 30, refillPeriod = 1.seconds)
        }
        register(RateLimitName("datadog-ingestion")) {
            requestKey { call ->
                val apiKey = call.request.headers["DD-API-KEY"]
                    ?: call.request.headers["DD-Api-Key"]
                    ?: call.request.headers["dd-api-key"]
                    ?: call.request.queryParameters["api_key"]
                if (apiKey != null) {
                    DatadogAuthMiddleware.resolveOrgId(apiKey)
                        ?.let { "org:$it" }
                        ?: call.request.clientIp()
                } else {
                    call.request.clientIp()
                }
            }
            rateLimiter(limit = INGEST_RATE_LIMIT, refillPeriod = INGEST_REFILL_SECONDS.seconds)
        }
        register(RateLimitName("log-ingestion")) {
            val logApiKeyService = LogApiKeyService()
            requestKey { call ->
                val parts = call.request.headers[HttpHeaders.Authorization]?.split(Regex("\\s+"), limit = 2)
                val token = if (parts != null && parts.size == 2 && parts[0].equals("Bearer", ignoreCase = true)) {
                    parts[1].trim().takeIf { it.isNotBlank() }
                } else null
                if (token != null) {
                    logApiKeyService.validateKey(token)
                        ?.let { "org:$it" }
                        ?: call.request.clientIp()
                } else {
                    call.request.clientIp()
                }
            }
            rateLimiter(limit = INGEST_RATE_LIMIT, refillPeriod = INGEST_REFILL_SECONDS.seconds)
        }
        register(RateLimitName("telemetry")) {
            requestKey { call -> call.request.clientIp() }
            rateLimiter(limit = TELEMETRY_RATE_LIMIT, refillPeriod = TELEMETRY_REFILL_SECONDS.seconds)
        }
    }
}
