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

import com.moneat.auth.services.RefreshTokenCleanupService
import com.moneat.billing.services.BillingBackgroundService
import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.dashboards.services.DashboardAlertService
import com.moneat.enterprise.FeatureRegistry
import com.moneat.events.services.EventService
import com.moneat.events.services.IngestionWorker
import com.moneat.llm.services.LlmIngestionWorker
import com.moneat.logs.services.LogIngestionWorker
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.otlp.services.OtlpMetricsIngestionWorker
import com.moneat.otlp.services.OtlpTraceIngestionWorker
import com.moneat.shared.services.ArtifactCleanupService
import com.moneat.shared.services.PulseService
import com.moneat.shared.services.RetentionBackgroundService
import com.moneat.shared.services.TaskLock
import com.moneat.shared.services.TraceFinalizerBackgroundService
import com.moneat.shared.services.UsageTrackingService
import com.moneat.uptime.services.UptimeScheduler
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.engine.temporal.ExecuteActionActivityImpl
import com.moneat.workflows.engine.temporal.PersistRunActivityImpl
import com.moneat.workflows.engine.temporal.TemporalClientProvider
import com.moneat.workflows.engine.temporal.WORKFLOW_TASK_QUEUE
import com.moneat.workflows.engine.temporal.WorkflowInterpreterWorkflowImpl
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import net.javacrumbs.shedlock.provider.exposed.ExposedLockProvider
import org.koin.core.context.GlobalContext
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}
private const val DEFAULT_WORKER_THREADS = 4

fun Application.configureBackgroundJobs() {
    val backgroundJobsEnabled =
        environment.config
            .propertyOrNull("backgroundJobs.enabled")
            ?.getString()
            ?.toBooleanStrictOrNull() ?: true

    if (!backgroundJobsEnabled) {
        logger.info { "All background jobs disabled via BACKGROUND_JOBS_ENABLED=false (API-only mode)" }
        return
    }

    val database = attributes[ExposedDatabaseKey]
    TaskLock.initialize(ExposedLockProvider(database))

    val koin = GlobalContext.get()
    val monitorAlertService = koin.get<MonitorAlertService>()
    val dashboardAlertService = koin.get<DashboardAlertService>()
    val billingBackgroundService = koin.get<BillingBackgroundService>()
    val retentionBackgroundService = koin.get<RetentionBackgroundService>()
    val traceFinalizerBackgroundService = koin.get<TraceFinalizerBackgroundService>()
    val refreshTokenCleanupService = koin.get<RefreshTokenCleanupService>()
    val artifactCleanupService = koin.get<ArtifactCleanupService>()
    val uptimeScheduler = koin.get<UptimeScheduler>()
    val queueKey = environment.config.property("ingest.queueKey").getString()
    val dlqKey = environment.config.property("ingest.dlqKey").getString()
    val workerCount =
        environment.config
            .property("ingest.workerCount")
            .getString()
            .toInt()
    val ingestionWorker = IngestionWorker(queueKey, dlqKey, workerCount, eventService = koin.get())
    val logQueueKey = environment.config.propertyOrNull("logs.queueKey")?.getString() ?: "moneat:logs:queue"
    val logDlqKey = environment.config.propertyOrNull("logs.dlqKey")?.getString() ?: "moneat:logs:dlq"
    val logWorkerCount =
        environment.config
            .propertyOrNull("logs.workerCount")
            ?.getString()
            ?.toIntOrNull() ?: 2
    val logIngestionWorker = LogIngestionWorker(
        logQueueKey,
        logDlqKey,
        logWorkerCount,
        logService = koin.get(),
        logIndexService = koin.get(),
    )
    val llmQueueKey = environment.config.propertyOrNull("llm.queueKey")?.getString() ?: "moneat:llm:queue"
    val llmDlqKey = environment.config.propertyOrNull("llm.dlqKey")?.getString() ?: "moneat:llm:dlq"
    val llmWorkerCount =
        environment.config
            .propertyOrNull("llm.workerCount")
            ?.getString()
            ?.toIntOrNull() ?: 2
    val llmIngestionWorker = LlmIngestionWorker(llmQueueKey, llmDlqKey, llmWorkerCount)

    val otlpTracesQueueKey = environment.config.propertyOrNull("otlp.tracesQueueKey")
        ?.getString() ?: "moneat:otlp-traces:queue"
    val otlpTracesDlqKey = environment.config.propertyOrNull("otlp.tracesDlqKey")
        ?.getString() ?: "moneat:otlp-traces:dlq"
    val otlpTracesWorkerCount = environment.config.propertyOrNull("otlp.tracesWorkerCount")
        ?.getString()?.toIntOrNull() ?: 2
    val otlpTraceIngestionWorker = OtlpTraceIngestionWorker(
        otlpTracesQueueKey,
        otlpTracesDlqKey,
        otlpTracesWorkerCount,
        eventService = koin.get<EventService>()
    )

    val otlpMetricsQueueKey = environment.config.propertyOrNull("otlp.metricsQueueKey")
        ?.getString() ?: "moneat:otlp-metrics:queue"
    val otlpMetricsDlqKey = environment.config.propertyOrNull("otlp.metricsDlqKey")
        ?.getString() ?: "moneat:otlp-metrics:dlq"
    val otlpMetricsWorkerCount = environment.config.propertyOrNull("otlp.metricsWorkerCount")
        ?.getString()?.toIntOrNull() ?: 2
    val otlpMetricsIngestionWorker = OtlpMetricsIngestionWorker(
        otlpMetricsQueueKey,
        otlpMetricsDlqKey,
        otlpMetricsWorkerCount
    )
    val temporalClientProvider = koin.get<TemporalClientProvider>()
    val workflowWorkerFactory = temporalClientProvider.newWorkerFactory()
    val workflowWorker = workflowWorkerFactory.newWorker(WORKFLOW_TASK_QUEUE)
    workflowWorker.registerWorkflowImplementationTypes(WorkflowInterpreterWorkflowImpl::class.java)
    workflowWorker.registerActivitiesImplementations(
        koin.get<ExecuteActionActivityImpl>(),
        koin.get<PersistRunActivityImpl>()
    )

    // Create a coroutine scope for background jobs
    val jobScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Start core background jobs
    logger.info { "Starting background jobs" }
    monitorAlertService.start(jobScope)
    dashboardAlertService.start(jobScope)
    billingBackgroundService.start(jobScope)
    retentionBackgroundService.start(jobScope)
    traceFinalizerBackgroundService.start(jobScope)
    refreshTokenCleanupService.start(jobScope)
    artifactCleanupService.start(jobScope)
    uptimeScheduler.start()
    ingestionWorker.start()
    logIngestionWorker.start()
    llmIngestionWorker.start()
    otlpTraceIngestionWorker.start()
    otlpMetricsIngestionWorker.start()
    workflowWorkerFactory.start()

    // Start enterprise background jobs (SSO, On-Call, etc.) if modules are present
    FeatureRegistry.startBackgroundJobs(this)

    // Start telemetry pulse for self-hosted deployments (opt-out via TELEMETRY_ENABLED=false)
    val pulseService = if (PulseService.isEnabled()) {
        val telemetryIntervalHours =
            environment.config
                .propertyOrNull("pulse.intervalHours")
                ?.getString()
                ?.toIntOrNull()
                ?.takeIf { it > 0 } ?: DEFAULT_WORKER_THREADS
        PulseService(interval = telemetryIntervalHours.hours).also {
            logger.info { "Telemetry pulse enabled for self-hosted deployment" }
            it.start(jobScope)
        }
    } else {
        null
    }

    // Register shutdown hook
    monitor.subscribe(ApplicationStopping) {
        // Flush buffered usage data before stopping to prevent data loss
        suspendRunCatching {
            UsageTrackingService.instance.flushBuffer()
            logger.info { "Flushed usage tracking buffer on shutdown" }
        }.getOrElse { e ->
            logger.error(e) { "Failed to flush usage tracking buffer on shutdown" }
        }
        monitorAlertService.stop()
        dashboardAlertService.stop()
        billingBackgroundService.stop()
        retentionBackgroundService.stop()
        traceFinalizerBackgroundService.stop()
        refreshTokenCleanupService.stop()
        artifactCleanupService.stop()
        uptimeScheduler.stop()
        ingestionWorker.stop()
        logIngestionWorker.stop()
        llmIngestionWorker.stop()
        runBlocking {
            otlpTraceIngestionWorker.stop()
            otlpMetricsIngestionWorker.stop()
        }
        workflowWorkerFactory.shutdown()
        temporalClientProvider.close()
        pulseService?.stop()
        FeatureRegistry.stopBackgroundJobs()

        // Close infrastructure connections AFTER all workers have stopped
        // to prevent NPEs from workers trying to use closed connections
        logger.info { "Closing infrastructure connections..." }
        RedisConfig.close()
        ClickHouseClient.close()
        logger.info { "Infrastructure connections closed" }
    }
}
