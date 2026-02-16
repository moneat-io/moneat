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

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Plugin to block write operations for demo users.
 * Demo users can only perform read operations (GET requests).
 */
fun Application.configureDemoModeRestrictions() {
    // Use Plugins phase which runs after authentication
    intercept(ApplicationCallPipeline.Plugins) {
        val principal = call.principal<JWTPrincipal>()
        val isDemo = principal?.payload?.getClaim("isDemo")?.asBoolean() ?: false

        if (isDemo) {
            val method = call.request.local.method
            val path = call.request.local.uri

            // Allow the demo login endpoint
            if (path.contains("/auth/demo-login")) {
                return@intercept
            }

            // Allow only GET and OPTIONS requests for demo users
            if (method != HttpMethod.Get && method != HttpMethod.Options) {
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
    val principal = principal<JWTPrincipal>() ?: return false
    return try {
        principal.payload.getClaim("isDemo")?.asBoolean() ?: false
    } catch (e: Exception) {
        false
    }
}

/**
 * Extension function to get demo epoch milliseconds from JWT.
 * Returns null if not a demo user or if demoEpochMs is not set.
 */
fun ApplicationCall.getDemoEpochMs(): Long? {
    val principal = principal<JWTPrincipal>() ?: return null
    return try {
        principal.payload.getClaim("demoEpochMs")?.asLong()
    } catch (e: Exception) {
        null
    }
}
