package com.moneat

import com.moneat.config.EnvConfig
import com.moneat.config.EnvironmentValidator
import com.moneat.config.configureClickHouse
import com.moneat.config.configureRedis
import com.moneat.plugins.*
import io.ktor.server.application.*
import io.ktor.server.netty.*

fun main(args: Array<String>) {
    // Load .env file into system properties before starting the server
    EnvConfig.initialize()
    
    // Validate critical environment variables and fail fast if missing
    EnvironmentValidator().validateAndFailFast()
    
    // Initialize Sentry for error monitoring (points to Moneat via SENTRY_DSN)
    com.moneat.config.SentryConfig.initialize()
    
    EngineMain.main(args)
}

fun Application.module() {
    try {
        configureSecurity()
        configureHTTP()
        configureSerialization()
        configureMonitoring()
        configureRateLimiting()
        configureRedis()
        configureClickHouse()
        configureDatabases()
        configureBackgroundJobs()
        configureRouting()
    } catch (e: Exception) {
        log.error("Failed to start application", e)
        throw e
    }
}
