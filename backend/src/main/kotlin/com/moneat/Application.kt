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

package com.moneat

import com.moneat.config.EnvConfig
import com.moneat.config.EnvironmentValidator
import com.moneat.config.configureClickHouse
import com.moneat.config.configureRedis
import com.moneat.di.appModules
import com.moneat.enterprise.FeatureRegistry
import com.moneat.plugins.configureBackgroundJobs
import com.moneat.plugins.configureDatabases
import com.moneat.plugins.configureDemoModeRestrictions
import com.moneat.plugins.configureHTTP
import com.moneat.plugins.configureMonitoring
import com.moneat.plugins.configureRateLimiting
import com.moneat.plugins.configureRouting
import com.moneat.plugins.configureSecurity
import com.moneat.plugins.configureSerialization
import io.ktor.server.application.*
import io.ktor.server.netty.EngineMain
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun main(args: Array<String>) {
    // Add JVM shutdown hook to log when shutdown is triggered
    Runtime.getRuntime().addShutdownHook(
        Thread {
            System.err.println("=== JVM SHUTDOWN HOOK TRIGGERED ===")
            Thread.currentThread().stackTrace.forEach { frame ->
                System.err.println("  at $frame")
            }
        }
    )

    // Load .env file into system properties before starting the server
    EnvConfig.initialize()

    // Validate critical environment variables and fail fast if missing
    EnvironmentValidator().validateAndFailFast()

    // Discover and register enterprise modules (if on classpath)
    FeatureRegistry.initialize()

    // Initialize Sentry for error monitoring (points to Moneat via SENTRY_DSN)
    com.moneat.config.SentryConfig
        .initialize()

    EngineMain.main(args)
}

fun Application.module() {
    // Log stack trace when shutdown is triggered to identify the cause
    monitor.subscribe(ApplicationStopping) {
        log.warn("APPLICATION STOPPING - Stack trace to identify trigger:")
        Thread.currentThread().stackTrace.take(20).forEach { frame ->
            log.warn("  at $frame")
        }
    }

    try {
        install(Koin) {
            slf4jLogger()
            modules(appModules)
        }
        configureSecurity()
        configureHTTP()
        configureSerialization()
        configureMonitoring()
        configureRateLimiting()
        configureDemoModeRestrictions()
        configureRedis()
        configureClickHouse()
        configureDatabases()
        configureBackgroundJobs()
        log.info("About to configure routing...")
        configureRouting()
        log.info("Routing configured successfully, application startup complete")
    } catch (e: Exception) {
        log.error("Failed to start application", e)
        throw e
    }
}
