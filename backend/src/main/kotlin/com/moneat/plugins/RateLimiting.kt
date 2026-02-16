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
                val sentryKey = call.request.queryParameters["sentry_key"]
                val key = extractPublicKey(auth, sentryKey) ?: "anon"
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
