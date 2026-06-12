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
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueSettings
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
private const val DEFAULT_PULSE_INTERVAL_HOURS = 4
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
    val workflowWorkers = if (startSchedulers) initializeWorkflowWorkers() else WorkflowWorkers()

    logger.info { "Starting background jobs" }
    if (startSchedulers) {
        schedulerJobs.start(jobScope)
        workflowWorkers.factory?.start()
    }
    coreWorkers.startSelected(startIngestionWorkers)

    FeatureRegistry.startBackgroundJobs(this, startSchedulers, startIngestionWorkers)
    val pulseService = startPulseIfEnabled(startSchedulers, jobScope)
    registerBackgroundShutdown(schedulerJobs, coreWorkers, workflowWorkers, pulseService)
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
    private val monitorAlertService = koin.get<MonitorAlertService>()
    private val dashboardAlertService = koin.get<DashboardAlertService>()
    private val billingBackgroundService = koin.get<BillingBackgroundService>()
    private val retentionBackgroundService = koin.get<RetentionBackgroundService>()
    private val traceFinalizerBackgroundService = koin.get<TraceFinalizerBackgroundService>()
    private val refreshTokenCleanupService = koin.get<RefreshTokenCleanupService>()
    private val artifactCleanupService = koin.get<ArtifactCleanupService>()
    private val uptimeScheduler = koin.get<UptimeScheduler>()
    private val detectionScheduler = koin.get<DetectionScheduler>()
    private val vulnerabilityAdvisorySyncJob = koin.get<VulnerabilityAdvisorySyncJob>()
    private val demoLivenessBackgroundService = koin.get<DemoLivenessBackgroundService>()

    fun start(jobScope: CoroutineScope) {
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
        demoLivenessBackgroundService.start(jobScope)
    }

    fun stop() {
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
    private val logIngestionWorker = LogIngestionWorker(
        config.propertyOrNull("logs.queueKey")?.getString() ?: "moneat:logs:queue",
        config.propertyOrNull("logs.dlqKey")?.getString() ?: "moneat:logs:dlq",
        config.propertyOrNull("logs.workerCount")?.getString()?.toIntOrNull() ?: 2,
        logService = koin.get(),
        logIndexService = koin.get(),
    )
    private val llmIngestionWorker = LlmIngestionWorker(
        config.propertyOrNull("llm.queueKey")?.getString() ?: "moneat:llm:queue",
        config.propertyOrNull("llm.dlqKey")?.getString() ?: "moneat:llm:dlq",
        config.propertyOrNull("llm.workerCount")?.getString()?.toIntOrNull() ?: 2,
    )
    private val otlpTraceIngestionWorker = OtlpTraceIngestionWorker(
        config.propertyOrNull("otlp.tracesQueueKey")?.getString() ?: "moneat:otlp-traces:queue",
        config.propertyOrNull("otlp.tracesDlqKey")?.getString() ?: "moneat:otlp-traces:dlq",
        config.propertyOrNull("otlp.tracesWorkerCount")?.getString()?.toIntOrNull() ?: 2,
        eventService = koin.get<EventService>(),
    )
    private val otlpMetricsIngestionWorker = OtlpMetricsIngestionWorker(
        config.propertyOrNull("otlp.metricsQueueKey")?.getString() ?: "moneat:otlp-metrics:queue",
        config.propertyOrNull("otlp.metricsDlqKey")?.getString() ?: "moneat:otlp-metrics:dlq",
        config.propertyOrNull("otlp.metricsWorkerCount")?.getString()?.toIntOrNull() ?: 2,
    )

    fun startSelected(startIngestionWorkers: Boolean) {
        if (shouldStartIngestionPipeline(startIngestionWorkers, IngestionPipeline.EVENTS)) {
            ingestionWorker.start()
        }
        if (shouldStartIngestionPipeline(startIngestionWorkers, IngestionPipeline.LOGS)) {
            logIngestionWorker.start()
        }
        if (shouldStartIngestionPipeline(startIngestionWorkers, IngestionPipeline.LLM)) {
            llmIngestionWorker.start()
        }
        if (shouldStartIngestionPipeline(startIngestionWorkers, IngestionPipeline.OTLP_TRACES)) {
            otlpTraceIngestionWorker.start()
        }
        if (shouldStartIngestionPipeline(startIngestionWorkers, IngestionPipeline.OTLP_METRICS)) {
            otlpMetricsIngestionWorker.start()
        }
    }

    fun stop() {
        ingestionWorker.stop()
        logIngestionWorker.stop()
        llmIngestionWorker.stop()
        runBlocking {
            otlpTraceIngestionWorker.stop()
            otlpMetricsIngestionWorker.stop()
        }
    }
}

private data class WorkflowWorkers(
    val provider: TemporalClientProvider? = null,
    val factory: WorkerFactory? = null,
)

private fun Application.initializeWorkflowWorkers(): WorkflowWorkers {
    val koin = GlobalContext.get()
    val workflowWorkerMode = workflowWorkerMode()
    return try {
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
        WorkflowWorkers(provider, factory)
    } catch (e: Throwable) {
        logger.error(e) {
            "Temporal workflow worker initialization failed; workflow execution disabled on this instance"
        }
        WorkflowWorkers()
    }
}

private fun Application.startPulseIfEnabled(
    startSchedulers: Boolean,
    jobScope: CoroutineScope,
): PulseService? {
    if (!startSchedulers || !PulseService.isEnabled()) return null
    val telemetryIntervalHours =
        environment.config
            .propertyOrNull("pulse.intervalHours")
            ?.getString()
            ?.toIntOrNull()
            ?.takeIf { it > 0 } ?: DEFAULT_PULSE_INTERVAL_HOURS
    return PulseService(interval = telemetryIntervalHours.hours).also {
        logger.info { "Telemetry pulse enabled for self-hosted deployment" }
        it.start(jobScope)
    }
}

private fun Application.registerBackgroundShutdown(
    schedulerJobs: SchedulerBackgroundJobs,
    coreWorkers: CoreIngestionWorkers,
    workflowWorkers: WorkflowWorkers,
    pulseService: PulseService?,
) {
    monitor.subscribe(ApplicationStopping) {
        flushUsageOnShutdown()
        schedulerJobs.stop()
        coreWorkers.stop()
        stopWorkflowWorkers(workflowWorkers)
        pulseService?.stop()
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

private fun stopWorkflowWorkers(workflowWorkers: WorkflowWorkers) {
    val factoryToStop = workflowWorkers.factory
    val providerToStop = workflowWorkers.provider
    if (factoryToStop != null && providerToStop != null) {
        shutdownWorkflowWorker(factoryToStop, providerToStop)
    }
}

private fun closeInfrastructureConnections() {
    logger.info { "Closing infrastructure connections..." }
    RedisConfig.close()
    ClickHouseClient.close()
    logger.info { "Infrastructure connections closed" }
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

private fun shouldStartIngestionPipeline(
    startIngestionWorkers: Boolean,
    pipeline: IngestionPipeline,
): Boolean =
    startIngestionWorkers && IngestionQueueSettings.isSelected(pipeline)
