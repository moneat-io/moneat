package com.moneat.plugins

import com.moneat.services.BillingBackgroundService
import com.moneat.services.MonitorAlertService
import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun Application.configureBackgroundJobs() {
    val monitorAlertService = MonitorAlertService()
    val billingBackgroundService = BillingBackgroundService()
    
    // Create a coroutine scope for background jobs
    val jobScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Start the monitor alert service
    logger.info { "Starting background jobs" }
    monitorAlertService.start(jobScope)
    billingBackgroundService.start(jobScope)
    
    // Register shutdown hook
    environment.monitor.subscribe(ApplicationStopped) {
        logger.info { "Stopping background jobs" }
        monitorAlertService.stop()
    }
}
