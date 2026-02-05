package com.moneat.plugins

import com.moneat.routes.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Moneat API v0.0.1")
        }
        
        get("/health") {
            call.respondText("OK")
        }
        
        // Sentry-compatible ingestion endpoints
        ingestRoutes()
        
        // Dashboard API endpoints
        apiRoutes()
        
        // Authentication endpoints
        authRoutes()
        
        // Auth token management endpoints
        authTokenRoutes()
        
        // Release and source map endpoints
        releaseRoutes()
    }
}
