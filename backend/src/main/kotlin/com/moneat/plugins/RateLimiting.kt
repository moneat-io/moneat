package com.moneat.plugins

import com.moneat.routes.extractPublicKey
import io.ktor.server.application.*
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ratelimit.*
import kotlin.time.Duration.Companion.seconds

fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(RateLimitName("ingestion")) {
            requestKey { call ->
                val projectId = call.parameters["projectId"] ?: "unknown"
                val auth = call.request.headers.get("X-Sentry-Auth")
                val key = auth?.let { h: String -> extractPublicKey(h) } ?: "anon"
                "$projectId:$key"
            }
            rateLimiter(limit = 100, refillPeriod = 1.seconds)
        }
        register(RateLimitName("api")) {
            requestKey { call ->
                val principal = call.principal<JWTPrincipal>()
                    ?: call.principal<AuthTokenPrincipal>()
                when (principal) {
                    is JWTPrincipal -> principal.payload.getClaim("userId").asInt().toString()
                    is AuthTokenPrincipal -> principal.userId.toString()
                    else -> "anon"
                }
            }
            rateLimiter(limit = 30, refillPeriod = 1.seconds)
        }
    }
}
