package com.moneat.config

import io.sentry.Sentry
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

object SentryConfig {
    fun initialize() {
        val dsn = EnvConfig.get("SENTRY_DSN")
        
        if (dsn.isNullOrBlank()) {
            logger.info { "Sentry DSN not configured, skipping Sentry initialization" }
            return
        }
        
        Sentry.init { options ->
            options.dsn = dsn
            options.environment = EnvConfig.get("SENTRY_ENVIRONMENT", "production")
            options.release = System.getenv("RELEASE_VERSION") ?: "moneat@${System.getProperty("app.version", "dev")}"
            
            // Performance monitoring
            val tracesSampleRate = EnvConfig.get("SENTRY_TRACES_SAMPLE_RATE", "0.1").toDoubleOrNull() ?: 0.1
            options.tracesSampleRate = tracesSampleRate
            
            // Enable profiling (if supported)
            val profilesSampleRate = EnvConfig.get("SENTRY_PROFILES_SAMPLE_RATE", "0.1").toDoubleOrNull() ?: 0.1
            options.profilesSampleRate = profilesSampleRate
            
            // Set server name
            options.serverName = System.getenv("HOSTNAME") ?: "moneat-backend"
            
            // Enable performance monitoring for database queries
            options.enableTracing = true
            
            // Enable breadcrumbs
            options.maxBreadcrumbs = 100
            
            // Send default PII (Personal Identifiable Information)
            options.isSendDefaultPii = false
            
            // Enable attaching threads
            options.isAttachThreads = true
            
            // Enable attaching stack traces
            options.isAttachStacktrace = true
            
            // Add custom tags
            options.setTag("service", "moneat-backend")
            options.setTag("component", "ktor")
            
            logger.info { "Sentry initialized with DSN: ${dsn.take(20)}..., traces: $tracesSampleRate, profiles: $profilesSampleRate" }
        }
    }
    
    fun isEnabled(): Boolean {
        return Sentry.isEnabled()
    }
}
