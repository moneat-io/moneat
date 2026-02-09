package com.moneat.plugins

import com.moneat.services.BillingBackgroundService
import com.moneat.services.IngestionWorker
import com.moneat.services.LogIngestionWorker
import com.moneat.services.MonitorAlertService
import com.moneat.services.RetentionBackgroundService
import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun Application.configureBackgroundJobs() {
    val monitorAlertService = MonitorAlertService()
    val billingBackgroundService = BillingBackgroundService()
    val retentionBackgroundService = RetentionBackgroundService()
    val queueKey = environment.config.property("ingest.queueKey").getString()
    val dlqKey = environment.config.property("ingest.dlqKey").getString()
    val workerCount = environment.config.property("ingest.workerCount").getString().toInt()
    val ingestionWorker = IngestionWorker(queueKey, dlqKey, workerCount)
    val logQueueKey = environment.config.propertyOrNull("logs.queueKey")?.getString() ?: "moneat:logs:queue"
    val logDlqKey = environment.config.propertyOrNull("logs.dlqKey")?.getString() ?: "moneat:logs:dlq"
    val logWorkerCount = environment.config.propertyOrNull("logs.workerCount")?.getString()?.toIntOrNull() ?: 2
    val logIngestionWorker = LogIngestionWorker(logQueueKey, logDlqKey, logWorkerCount)

    // Create a coroutine scope for background jobs
    val jobScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Start the monitor alert service, billing service, retention service, and ingestion workers
    logger.info { "Starting background jobs" }
    monitorAlertService.start(jobScope)
    billingBackgroundService.start(jobScope)
    retentionBackgroundService.start(jobScope)
    ingestionWorker.start()
    logIngestionWorker.start()

    // Register shutdown hook
    environment.monitor.subscribe(ApplicationStopped) {
        logger.info { "Stopping background jobs" }
        monitorAlertService.stop()
        billingBackgroundService.stop()
        retentionBackgroundService.stop()
        ingestionWorker.stop()
        logIngestionWorker.stop()
    }
}
