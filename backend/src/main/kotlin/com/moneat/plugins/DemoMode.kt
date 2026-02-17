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

import com.moneat.config.EnvConfig
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.path
import io.ktor.server.response.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val demoUserId = EnvConfig.Demo.USER_ID
private val demoUserEmail = EnvConfig.Demo.USER_EMAIL
private val demoSafeWritePaths = setOf(
    "/auth/demo-login",
    "/auth/demo-refresh",
    "/auth/refresh",
    "/auth/logout"
)

/**
 * Plugin to block write operations for demo users.
 * Demo users can only perform read operations.
 */
fun Application.configureDemoModeRestrictions() {
    // Use Plugins phase which runs after authentication
    intercept(ApplicationCallPipeline.Plugins) {
        val isDemo = call.isDemoUser()

        if (isDemo) {
            val method = call.request.local.method
            val path = call.request.path()
            if (path in demoSafeWritePaths) {
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
        try {
            if (jwtPrincipal.payload.getClaim("isDemo")?.asBoolean() == true) return true
            val userId = jwtPrincipal.payload.getClaim("userId")?.asLong()
                ?: jwtPrincipal.payload.getClaim("userId")?.asInt()?.toLong()
            if (userId == demoUserId) return true
            val email = jwtPrincipal.payload.getClaim("email")?.asString()
            if (email != null && email.equals(demoUserEmail, ignoreCase = true)) return true
        } catch (_: Exception) {
            // Fall through and try other principal types.
        }
    }

    val tokenPrincipal = principal<AuthTokenPrincipal>()
    return if (tokenPrincipal != null) {
        tokenPrincipal.userId.toLong() == demoUserId
    } else {
        false
    }
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
    return try {
        principal.payload.getClaim("demoEpochMs")?.asLong()
            ?: if (isDemoUser()) EnvConfig.Demo.epochMs else null
    } catch (_: Exception) {
        if (isDemoUser()) EnvConfig.Demo.epochMs else null
    }
}
