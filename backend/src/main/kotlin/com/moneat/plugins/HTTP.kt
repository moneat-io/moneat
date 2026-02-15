package com.moneat.plugins

import com.moneat.config.EnvConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*

fun Application.configureHTTP() {
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
        
        // Configure allowed origins from environment
        val allowedOrigins = EnvConfig.get("ALLOWED_ORIGINS", "https://moneat.io")
        allowedOrigins.split(",").forEach { origin ->
            val trimmedOrigin = origin.trim()
            if (trimmedOrigin.isNotEmpty()) {
                allowHost(trimmedOrigin.removePrefix("https://").removePrefix("http://"), schemes = listOf("https", "http"))
            }
        }
        
        allowCredentials = true
    }
    
    // Add security headers to all responses
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("X-Frame-Options", "DENY")
        call.response.headers.append("Referrer-Policy", "strict-origin-when-cross-origin")
        call.response.headers.append("X-XSS-Protection", "1; mode=block")
        
        // Content Security Policy
        call.response.headers.append("Content-Security-Policy", 
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self' data:; connect-src 'self'")
        
        // HSTS - only in production (not on localhost)
        if (!call.request.local.remoteHost.contains("localhost") && 
            !call.request.local.remoteHost.contains("127.0.0.1")) {
            call.response.headers.append("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }
    }
}
