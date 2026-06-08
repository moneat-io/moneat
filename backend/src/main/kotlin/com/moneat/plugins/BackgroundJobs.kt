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
import com.moneat.security.detection.DetectionScheduler
import com.moneat.security.vulnerabilities.VulnerabilityAdvisorySyncJob
import com.moneat.shared.services.ArtifactCleanupService
import com.moneat.shared.services.DemoLivenessBackgroundService
import com.moneat.shared.services.PulseService
import com.moneat.shared.services.RetentionBackgroundService
import com.moneat.shared.services.TaskLock
import com.moneat.shared.services.TraceFinalizerBackgroundService
import com.moneat.shared.services.UsageTrackingService
import com.moneat.uptime.services.UptimeScheduler
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.engine.temporal.ExecuteActionActivityImpl
import com.moneat.workflows.engine.temporal.ExecuteEgressActionActivityImpl
import com.moneat.workflows.engine.temporal.PersistRunActivityImpl
import com.moneat.workflows.engine.temporal.RequestApprovalActivityImpl
import com.moneat.workflows.engine.temporal.TemporalClientProvider
import com.moneat.workflows.engine.temporal.WORKFLOW_EGRESS_TASK_QUEUE
import com.moneat.workflows.engine.temporal.WORKFLOW_TASK_QUEUE
import com.moneat.workflows.engine.temporal.WorkflowInterpreterWorkflowImpl
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.temporal.worker.WorkerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import net.javacrumbs.shedlock.provider.exposed.ExposedLockProvider
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}
private const val DEFAULT_WORKER_THREADS = 4
private const val WORKFLOW_WORKER_SHUTDOWN_TIMEOUT_SECONDS = 30L
private const val WORKFLOW_WORKER_MODE_CONFIG = "workflows.workerMode"

enum class WorkflowWorkerMode {
    ALL,
    TRUSTED,
    EGRESS,
    NONE
}

fun Application.workflowWorkerMode(): WorkflowWorkerMode {
    return parseWorkflowWorkerMode(
        environment.config
            .propertyOrNull(WORKFLOW_WORKER_MODE_CONFIG)
            ?.getString()
    )
}

internal fun parseWorkflowWorkerMode(rawMode: String?): WorkflowWorkerMode {
    val normalized = rawMode?.trim()?.uppercase()
    if (normalized.isNullOrBlank()) {
        return WorkflowWorkerMode.TRUSTED
    }
    return WorkflowWorkerMode.entries.firstOrNull { mode -> mode.name == normalized }
        ?: throw IllegalArgumentException(
            "Invalid $WORKFLOW_WORKER_MODE_CONFIG value '$normalized'. Expected one of: " +
                WorkflowWorkerMode.entries.joinToString { mode -> mode.name.lowercase() }
        )
}

fun Application.configureEgressWorkflowWorker() {
    val koin = GlobalContext.get()
    val temporalClientProvider = koin.get<TemporalClientProvider>()
    val workflowWorkerFactory = temporalClientProvider.newWorkerFactory()
    workflowWorkerFactory
        .newWorker(WORKFLOW_EGRESS_TASK_QUEUE)
        .registerActivitiesImplementations(koin.get<ExecuteEgressActionActivityImpl>())
    logger.info { "Starting isolated Temporal egress worker on $WORKFLOW_EGRESS_TASK_QUEUE" }
    workflowWorkerFactory.start()
    monitor.subscribe(ApplicationStopping) {
        shutdownWorkflowWorker(workflowWorkerFactory, temporalClientProvider)
    }
}

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
    val detectionScheduler = koin.get<DetectionScheduler>()
    val vulnerabilityAdvisorySyncJob = koin.get<VulnerabilityAdvisorySyncJob>()
    val demoLivenessBackgroundService = koin.get<DemoLivenessBackgroundService>()
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
    // Temporal worker initialization must not take down the whole backend: if the
    // Temporal cluster is unreachable or a worker/activity fails to register, log it
    // and continue serving (workflow execution is disabled on this instance) rather
    // than aborting application startup.
    val workflowWorkerMode = workflowWorkerMode()
    var temporalClientProvider: TemporalClientProvider? = null
    var workflowWorkerFactory: WorkerFactory? = null
    try {
        val provider = koin.get<TemporalClientProvider>()
        val factory = provider.newWorkerFactory()
        if (workflowWorkerMode == WorkflowWorkerMode.ALL || workflowWorkerMode == WorkflowWorkerMode.TRUSTED) {
            val workflowWorker = factory.newWorker(WORKFLOW_TASK_QUEUE)
            workflowWorker.registerWorkflowImplementationTypes(WorkflowInterpreterWorkflowImpl::class.java)
            workflowWorker.registerActivitiesImplementations(
                koin.get<ExecuteActionActivityImpl>(),
                koin.get<PersistRunActivityImpl>(),
                koin.get<RequestApprovalActivityImpl>()
            )
        }
        if (workflowWorkerMode == WorkflowWorkerMode.ALL || workflowWorkerMode == WorkflowWorkerMode.EGRESS) {
            factory
                .newWorker(WORKFLOW_EGRESS_TASK_QUEUE)
                .registerActivitiesImplementations(koin.get<ExecuteEgressActionActivityImpl>())
        }
        temporalClientProvider = provider
        workflowWorkerFactory = factory
    } catch (e: Throwable) {
        logger.error(e) {
            "Temporal workflow worker initialization failed; workflow execution disabled on this instance"
        }
    }

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
    detectionScheduler.start(jobScope)
    vulnerabilityAdvisorySyncJob.start(jobScope)
    ingestionWorker.start()
    logIngestionWorker.start()
    llmIngestionWorker.start()
    otlpTraceIngestionWorker.start()
    otlpMetricsIngestionWorker.start()
    workflowWorkerFactory?.start()
    demoLivenessBackgroundService.start(jobScope)

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
        detectionScheduler.stop()
        vulnerabilityAdvisorySyncJob.stop()
        ingestionWorker.stop()
        logIngestionWorker.stop()
        llmIngestionWorker.stop()
        runBlocking {
            otlpTraceIngestionWorker.stop()
            otlpMetricsIngestionWorker.stop()
        }
        val factoryToStop = workflowWorkerFactory
        val providerToStop = temporalClientProvider
        if (factoryToStop != null && providerToStop != null) {
            shutdownWorkflowWorker(factoryToStop, providerToStop)
        }
        demoLivenessBackgroundService.stop()
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

private fun shutdownWorkflowWorker(
    workflowWorkerFactory: WorkerFactory,
    temporalClientProvider: TemporalClientProvider
) {
    try {
        workflowWorkerFactory.shutdown()
        workflowWorkerFactory.awaitTermination(WORKFLOW_WORKER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!workflowWorkerFactory.isTerminated) {
            logger.warn {
                "Temporal workflow worker did not stop within " +
                    "$WORKFLOW_WORKER_SHUTDOWN_TIMEOUT_SECONDS seconds; forcing shutdown"
            }
            workflowWorkerFactory.shutdownNow()
        }
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        logger.warn(error) { "Interrupted while stopping Temporal workflow worker; forcing shutdown" }
        workflowWorkerFactory.shutdownNow()
    } finally {
        temporalClientProvider.close()
    }
}
