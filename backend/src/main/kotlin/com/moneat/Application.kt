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
import com.moneat.di.buildAppModules
import com.moneat.enterprise.FeatureRegistry
import com.moneat.plugins.configureBackgroundJobs
import com.moneat.plugins.configureDatabases
import com.moneat.plugins.configureDemoModeRestrictions
import com.moneat.plugins.configureEgressWorkflowWorker
import com.moneat.plugins.configureHealthRouting
import com.moneat.plugins.configureHTTP
import com.moneat.plugins.configureMonitoring
import com.moneat.plugins.configureRateLimiting
import com.moneat.plugins.configureRouting
import com.moneat.plugins.configureSecurity
import com.moneat.plugins.configureSerialization
import com.moneat.plugins.WorkflowWorkerMode
import com.moneat.plugins.workflowWorkerMode
import com.moneat.runtime.MoneatProcessRole
import com.moneat.runtime.RuntimeMode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.netty.EngineMain
import mu.KotlinLogging
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

private const val STACK_TRACE_DEPTH = 20

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    // Add JVM shutdown hook to log when shutdown is triggered
    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.warn { "=== JVM SHUTDOWN HOOK TRIGGERED ===" }
            Thread.currentThread().stackTrace.forEach { frame ->
                logger.warn { "  at $frame" }
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
        Thread.currentThread().stackTrace.take(STACK_TRACE_DEPTH).forEach { frame ->
            log.warn("  at $frame")
        }
    }

    install(Koin) {
        slf4jLogger()
        val frontendBaseUrl = environment.config.property("email.frontendUrl").getString()
        modules(buildAppModules(frontendBaseUrl = frontendBaseUrl) + FeatureRegistry.koinModules())
    }
    val processRole = RuntimeMode.role()
    if (processRole == MoneatProcessRole.WORKFLOW_EGRESS || workflowWorkerMode() == WorkflowWorkerMode.EGRESS) {
        configureSerialization()
        configureEgressWorkflowWorker()
        log.info("Workflow egress worker startup complete")
        return
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
    when (processRole) {
        MoneatProcessRole.ALL -> {
            configureBackgroundJobs(startSchedulers = true, startIngestionWorkers = true)
            log.info("About to configure routing...")
            configureRouting()
            log.info("Routing configured successfully, application startup complete")
        }
        MoneatProcessRole.API -> {
            configureBackgroundJobs(startSchedulers = false, startIngestionWorkers = false)
            log.info("Starting API-only process")
            configureRouting()
        }
        MoneatProcessRole.SCHEDULER -> {
            configureBackgroundJobs(startSchedulers = true, startIngestionWorkers = false)
            log.info("Starting scheduler-only process")
            configureHealthRouting()
        }
        MoneatProcessRole.INGESTION_WORKER -> {
            configureBackgroundJobs(startSchedulers = false, startIngestionWorkers = true)
            log.info("Starting ingestion-worker process")
            configureHealthRouting()
        }
        MoneatProcessRole.WORKFLOW_EGRESS -> {
            // Handled before full infrastructure startup.
        }
    }
}
