package com.moneat.plugins

import com.moneat.config.EnvConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*

fun Application.configureHTTP() {
    val frontendUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")
    
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
    intercept(ApplicationCallPipeline.Call) {
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("X-Frame-Options", "DENY")
        call.response.headers.append("Referrer-Policy", "strict-origin-when-cross-origin")
        call.response.headers.append("X-XSS-Protection", "1; mode=block")
        
        // Content Security Policy
        val backendUrl = EnvConfig.get("BACKEND_URL", "https://api.moneat.io")
        call.response.headers.append("Content-Security-Policy", 
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self' data:; connect-src 'self' $backendUrl")
        
        // HSTS - only in production (not on localhost)
        if (!call.request.local.remoteHost.contains("localhost") && 
            !call.request.local.remoteHost.contains("127.0.0.1")) {
            call.response.headers.append("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }
        
        proceed()
    }
}
