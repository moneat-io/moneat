package com.moneat

import com.moneat.config.EnvConfig
import com.moneat.plugins.*
import io.ktor.server.application.*
import io.ktor.server.netty.*

fun main(args: Array<String>) {
    // Load .env file into system properties before starting the server
    EnvConfig.initialize()
    
    // Initialize Sentry for error monitoring
    com.moneat.config.SentryConfig.initialize()
    
    EngineMain.main(args)
}

fun Application.module() {
    configureSecurity()
    configureHTTP()
    configureSerialization()
    configureMonitoring()
    configureRouting()
    configureDatabases()
    configureBackgroundJobs()
}
