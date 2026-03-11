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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun Application.configureHTTP() {
    val frontendUrl = EnvConfig.get("FRONTEND_URL")!!

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Sentry-Auth")

        // Allow the frontend origin (derived from FRONTEND_URL)
        val frontendHost = frontendUrl.removePrefix("https://").removePrefix("http://")
        val frontendSchemes = if (frontendUrl.startsWith("https://")) listOf("https") else listOf("http")
        allowHost(frontendHost, schemes = frontendSchemes)

        // Allow any additional origins from ALLOWED_ORIGINS
        val allowedOrigins = EnvConfig.get("ALLOWED_ORIGINS", "")
        if (allowedOrigins.isNotEmpty()) {
            allowedOrigins.split(",").forEach { origin ->
                val trimmedOrigin = origin.trim()
                if (trimmedOrigin.isNotEmpty()) {
                    val scheme = if (trimmedOrigin.startsWith("https://")) listOf("https") else listOf("http")
                    allowHost(trimmedOrigin.removePrefix("https://").removePrefix("http://"), schemes = scheme)
                }
            }
        }

        allowCredentials = true
    }

    // Add security headers to all responses
    intercept(ApplicationCallPipeline.Plugins) {
        setSecurityHeader(call, "X-Content-Type-Options", "nosniff")
        setSecurityHeader(call, "X-Frame-Options", "DENY")
        setSecurityHeader(call, "Referrer-Policy", "strict-origin-when-cross-origin")
        setSecurityHeader(call, "X-XSS-Protection", "1; mode=block")

        // Content Security Policy
        val backendUrl = EnvConfig.get("BACKEND_URL", "https://api.moneat.io")
        setSecurityHeader(
            call,
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self' data:; connect-src 'self' $backendUrl"
        )

        // HSTS - only in production (not on localhost)
        if (!call.request.local.remoteHost
                .contains("localhost") &&
            !call.request.local.remoteHost
                .contains("127.0.0.1")
        ) {
            setSecurityHeader(call, "Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }

        proceed()
    }
}

private fun setSecurityHeader(
    call: ApplicationCall,
    name: String,
    value: String
) {
    if (call.response.isCommitted) return
    try {
        call.response.headers.append(name, value)
    } catch (_: UnsupportedOperationException) {
        logger.debug { "Skipped security header '$name' because response was already committed" }
    }
}
