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

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.enterprise.FeatureRegistry
import com.moneat.events.services.IngestionWorker
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueSettings
import com.moneat.shared.services.ArtifactCleanupService
import com.moneat.shared.services.DemoLivenessBackgroundService
import com.moneat.shared.services.RetentionBackgroundService
import com.moneat.shared.services.TaskLock
import com.moneat.shared.services.TraceFinalizerBackgroundService
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.suspendRunCatching
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mu.KotlinLogging
import net.javacrumbs.shedlock.provider.exposed.ExposedLockProvider
import org.koin.core.context.GlobalContext

private val logger = KotlinLogging.logger {}
private const val DEFAULT_WORKER_THREADS = 4

fun Application.configureBackgroundJobs(
    startSchedulers: Boolean = true,
    startIngestionWorkers: Boolean = true,
) {
    if (!backgroundJobsEnabled()) {
        logger.info { "All background jobs disabled via BACKGROUND_JOBS_ENABLED=false (API-only mode)" }
        return
    }

    if (startSchedulers) {
        initializeTaskLock()
    }

    val jobScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val schedulerJobs = SchedulerBackgroundJobs()
    val coreWorkers = CoreIngestionWorkers(this)

    logger.info { "Starting background jobs" }
    if (startSchedulers) {
        schedulerJobs.start(jobScope)
    }
    coreWorkers.startSelected(startIngestionWorkers)

    FeatureRegistry.startBackgroundJobs(this, startSchedulers, startIngestionWorkers)
    registerBackgroundShutdown(schedulerJobs, coreWorkers)
}

private fun Application.backgroundJobsEnabled(): Boolean =
    environment.config
        .propertyOrNull("backgroundJobs.enabled")
        ?.getString()
        ?.toBooleanStrictOrNull() ?: true

private fun Application.initializeTaskLock() {
    TaskLock.initialize(ExposedLockProvider(attributes[ExposedDatabaseKey]))
}

private class SchedulerBackgroundJobs {
    private val koin = GlobalContext.get()
    private val retentionBackgroundService = koin.get<RetentionBackgroundService>()
    private val traceFinalizerBackgroundService = koin.get<TraceFinalizerBackgroundService>()
    private val artifactCleanupService = koin.get<ArtifactCleanupService>()
    private val demoLivenessBackgroundService = koin.get<DemoLivenessBackgroundService>()

    fun start(jobScope: CoroutineScope) {
        retentionBackgroundService.start(jobScope)
        traceFinalizerBackgroundService.start(jobScope)
        artifactCleanupService.start(jobScope)
        demoLivenessBackgroundService.start(jobScope)
    }

    fun stop() {
        retentionBackgroundService.stop()
        traceFinalizerBackgroundService.stop()
        artifactCleanupService.stop()
        demoLivenessBackgroundService.stop()
    }
}

private class CoreIngestionWorkers(application: Application) {
    private val koin = GlobalContext.get()
    private val config = application.environment.config
    private val ingestionWorker = IngestionWorker(
        config.property("ingest.queueKey").getString(),
        config.property("ingest.dlqKey").getString(),
        config.property("ingest.workerCount").getString().toInt(),
        eventService = koin.get(),
    )

    fun startSelected(startIngestionWorkers: Boolean) {
        if (shouldStartIngestionPipeline(startIngestionWorkers, IngestionPipeline.EVENTS)) {
            ingestionWorker.start()
        }
    }

    fun stop() {
        ingestionWorker.stop()
    }
}

private fun Application.registerBackgroundShutdown(
    schedulerJobs: SchedulerBackgroundJobs,
    coreWorkers: CoreIngestionWorkers,
) {
    monitor.subscribe(ApplicationStopping) {
        flushUsageOnShutdown()
        schedulerJobs.stop()
        coreWorkers.stop()
        FeatureRegistry.stopBackgroundJobs()
        closeInfrastructureConnections()
    }
}

private fun flushUsageOnShutdown() {
    suspendRunCatching {
        UsageTrackingService.instance.flushBuffer()
        logger.info { "Flushed usage tracking buffer on shutdown" }
    }.getOrElse { e ->
        logger.error(e) { "Failed to flush usage tracking buffer on shutdown" }
    }
}

private fun closeInfrastructureConnections() {
    logger.info { "Closing infrastructure connections..." }
    RedisConfig.close()
    ClickHouseClient.close()
    logger.info { "Infrastructure connections closed" }
}

private fun shouldStartIngestionPipeline(
    startIngestionWorkers: Boolean,
    pipeline: IngestionPipeline,
): Boolean =
    startIngestionWorkers && IngestionQueueSettings.isSelected(pipeline)
