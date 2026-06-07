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

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.config.EnvConfig
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.path
import io.ktor.server.response.respond
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val demoUserId = EnvConfig.Demo.USER_ID
private val demoUserEmail = EnvConfig.Demo.USER_EMAIL
private val jwtSecret by lazy { EnvConfig.get("JWT_SECRET") }
private val demoSafeWritePaths =
    setOf(
        "/auth/login",
        "/auth/demo-login",
        "/auth/demo-refresh",
        "/auth/refresh",
        "/auth/logout"
    )

// POST endpoints that are read-only in nature (send a body but only fetch data)
private val demoSafeWritePathSuffixes =
    setOf(
        "/query",
        "/query/batch"
    )

private fun String.removeBearerPrefix(): String {
    return removePrefix("Bearer ").removePrefix("bearer ").trim()
}

private fun ApplicationCall.extractAuthToken(): String? {
    val headerToken =
        request.headers["Authorization"]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.removeBearerPrefix()
            ?.takeIf { it.isNotBlank() }
    if (headerToken != null) return headerToken

    return request.cookies["auth_token"]?.trim()?.takeIf { it.isNotBlank() }
}

private fun isDemoToken(token: String?): Boolean {
    if (token.isNullOrBlank()) return false

    return suspendRunCatching {
        val decoded = JWT.require(Algorithm.HMAC256(jwtSecret)).build().verify(token)
        if (decoded.getClaim("isDemo")?.asBoolean() == true) return true

        val userId =
            decoded.getClaim("userId")?.asLong()
                ?: decoded.getClaim("userId")?.asInt()?.toLong()
        if (userId == demoUserId) return true

        val email = decoded.getClaim("email")?.asString()
        email != null && email.equals(demoUserEmail, ignoreCase = true)
    }.getOrElse { _ ->
        false
    }
}

/**
 * Plugin to block write operations for demo users.
 * Demo users can only perform read operations.
 */
fun Application.configureDemoModeRestrictions() {
    // Keep this early in the pipeline; we also decode the auth token directly
    // so demo restrictions still apply even when principal resolution has not run.
    intercept(ApplicationCallPipeline.Plugins) {
        val isDemo = call.isDemoUser()

        if (isDemo) {
            val method = call.request.local.method
            val path = call.request.path()
            if (path in demoSafeWritePaths || demoSafeWritePathSuffixes.any { path.endsWith(it) }) {
                return@intercept
            }

            // Allow only safe methods for demo users
            if (method != HttpMethod.Get && method != HttpMethod.Options && method != HttpMethod.Head) {
                logger.warn { "Demo user attempted ${method.value} on $path" }
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "Demo mode is read-only. Write operations are not allowed.")
                )
                finish()
            }
        }
    }
}

/**
 * Extension function to check if the current user is a demo user.
 * Useful for bypassing permission checks or other demo-specific logic.
 */
fun ApplicationCall.isDemoUser(): Boolean {
    val jwtPrincipal = principal<JWTPrincipal>()
    if (jwtPrincipal != null) {
        suspendRunCatching {
            if (jwtPrincipal.payload.getClaim("isDemo")?.asBoolean() == true) return true
            val userId =
                jwtPrincipal.payload.getClaim("userId")?.asLong()
                    ?: jwtPrincipal.payload
                        .getClaim("userId")
                        ?.asInt()
                        ?.toLong()
            if (userId == demoUserId) return true
            val email = jwtPrincipal.payload.getClaim("email")?.asString()
            if (email != null && email.equals(demoUserEmail, ignoreCase = true)) return true
        }.getOrElse { _ ->
            // Fall through and try other principal types.
        }
    }

    val tokenPrincipal = principal<AuthTokenPrincipal>()
    if (tokenPrincipal != null) {
        return tokenPrincipal.userId.toLong() == demoUserId
    }

    return isDemoToken(extractAuthToken())
}

/**
 * Extension function to get demo epoch milliseconds from JWT.
 * Returns null if not a demo user or if demoEpochMs is not set.
 */
fun ApplicationCall.getDemoEpochMs(): Long? {
    val principal = principal<JWTPrincipal>()
    if (principal == null) {
        return if (isDemoUser()) EnvConfig.Demo.epochMs else null
    }
    return suspendRunCatching {
        principal.payload.getClaim("demoEpochMs")?.asLong()
            ?: if (isDemoUser()) EnvConfig.Demo.epochMs else null
    }.getOrElse { _ ->
        if (isDemoUser()) EnvConfig.Demo.epochMs else null
    }
}
