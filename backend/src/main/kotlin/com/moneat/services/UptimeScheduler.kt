package com.moneat.services

import kotlinx.coroutines.*
import mu.KotlinLogging
import java.util.*

private val logger = KotlinLogging.logger {}

/**
 * Coroutine-based scheduler for uptime monitor checks.
 * Runs continuously and executes checks for monitors at their configured intervals.
 */
class UptimeScheduler(
    private val uptimeService: UptimeService = UptimeService(),
    private val checkExecutor: UptimeCheckExecutor = UptimeCheckExecutor()
) {
    
    private var schedulerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runningChecks = Collections.synchronizedSet(mutableSetOf<UUID>())
    
    /**
     * Start the scheduler.
     */
    fun start() {
        if (schedulerJob?.isActive == true) {
            logger.warn { "Uptime scheduler is already running" }
            return
        }
        
        logger.info { "Starting uptime monitor scheduler..." }
        
        schedulerJob = scope.launch {
            while (isActive) {
                try {
                    checkMonitors()
                } catch (e: Exception) {
                    logger.error(e) { "Error in uptime scheduler loop: ${e.message}" }
                }
                
                // Check every second
                delay(1000)
            }
        }
        
        logger.info { "Uptime monitor scheduler started" }
    }
    
    /**
     * Stop the scheduler.
     */
    fun stop() {
        logger.info { "Stopping uptime monitor scheduler..." }
        schedulerJob?.cancel()
        schedulerJob = null
        logger.info { "Uptime monitor scheduler stopped" }
    }
    
    /**
     * Check all monitors that are due for a check.
     */
    private suspend fun checkMonitors() {
        val monitors = try {
            uptimeService.getMonitorsDueForCheck()
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch monitors due for check: ${e.message}" }
            return
        }
        
        if (monitors.isEmpty()) return
        
        logger.debug { "Checking ${monitors.size} monitor(s)" }
        
        // Launch check for each monitor in parallel
        monitors.forEach { monitor ->
            // Skip if already running a check for this monitor
            if (!runningChecks.add(monitor.id)) {
                logger.debug { "Skipping monitor ${monitor.id} - check already in progress" }
                return@forEach
            }
            
            scope.launch {
                try {
                    performCheck(monitor.id)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to perform check for monitor ${monitor.id}: ${e.message}" }
                } finally {
                    runningChecks.remove(monitor.id)
                }
            }
        }
    }
    
    /**
     * Perform a check for a specific monitor.
     */
    private suspend fun performCheck(monitorId: UUID) {
        // Get latest monitor data from the list we already fetched
        val monitor = try {
            uptimeService.getMonitorsDueForCheck().find { it.id == monitorId }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch monitor $monitorId: ${e.message}" }
            return
        }
        
        if (monitor == null) {
            logger.warn { "Monitor $monitorId not found or not active" }
            return
        }
        
        // Skip push monitors (they don't have active checks)
        if (monitor.type.lowercase() == "push") {
            return
        }
        
        logger.debug { "Checking monitor ${monitor.name} (${monitor.type})" }
        
        // Execute the check
        val result = try {
            withTimeout(monitor.timeoutSeconds * 1000L + 5000) { // Add 5s buffer
                checkExecutor.executeCheck(monitor)
            }
        } catch (e: Exception) {
            logger.error(e) { "Check execution failed for monitor ${monitor.id}: ${e.message}" }
            com.moneat.models.CheckResult(0, -1, 0, "Check execution failed: ${e.message}")
        }
        
        // Handle retries for failed checks
        val finalResult = if (result.status == 0 && monitor.retries > 0) {
            handleRetries(monitor, result)
        } else {
            result
        }
        
        // Record heartbeat
        try {
            uptimeService.recordHeartbeat(monitor.id, finalResult)
        } catch (e: Exception) {
            logger.error(e) { "Failed to record heartbeat for monitor ${monitor.id}: ${e.message}" }
        }
        
        // Update monitor status
        val oldStatus = monitor.status
        uptimeService.updateMonitorStatus(monitor.id, finalResult)
        
        // Detect status changes (up -> down or down -> up)
        val newStatus = when (finalResult.status) {
            1 -> "up"
            0 -> "down"
            else -> "pending"
        }
        
        if (oldStatus != newStatus && (oldStatus == "up" || oldStatus == "down") && (newStatus == "up" || newStatus == "down")) {
            logger.info { "Monitor ${monitor.name} status changed: $oldStatus -> $newStatus" }
            
            // TODO: Trigger alert via MonitorAlertService or notification service
            // This would integrate with the existing alert system
            try {
                notifyStatusChange(monitor, oldStatus, newStatus, finalResult)
            } catch (e: Exception) {
                logger.error(e) { "Failed to send status change notification: ${e.message}" }
            }
        }
    }
    
    /**
     * Handle retries for failed checks.
     */
    private suspend fun handleRetries(
        monitor: com.moneat.models.UptimeMonitorData,
        initialResult: com.moneat.models.CheckResult
    ): com.moneat.models.CheckResult {
        var lastResult = initialResult
        
        for (retry in 1..monitor.retries) {
            logger.debug { "Retrying monitor ${monitor.name} (attempt $retry/${monitor.retries})" }
            
            delay(monitor.retryIntervalSeconds * 1000L)
            
            val retryResult = try {
                withTimeout(monitor.timeoutSeconds * 1000L + 5000) {
                    checkExecutor.executeCheck(monitor)
                }
            } catch (e: Exception) {
                logger.error(e) { "Retry $retry failed for monitor ${monitor.id}: ${e.message}" }
                com.moneat.models.CheckResult(0, -1, 0, "Retry failed: ${e.message}")
            }
            
            lastResult = retryResult
            
            // If check succeeded, stop retrying
            if (retryResult.status == 1) {
                logger.debug { "Monitor ${monitor.name} succeeded on retry $retry" }
                break
            }
        }
        
        return lastResult
    }
    
    /**
     * Notify about status changes.
     * This is a placeholder for integration with notification system.
     */
    private fun notifyStatusChange(
        monitor: com.moneat.models.UptimeMonitorData,
        oldStatus: String,
        newStatus: String,
        result: com.moneat.models.CheckResult
    ) {
        // TODO: Integrate with existing MonitorAlertService or notification system
        // For now, just log
        logger.info {
            "Uptime alert: Monitor '${monitor.name}' (${monitor.type}) changed from $oldStatus to $newStatus. " +
            "Message: ${result.message}"
        }
        
        // Example integration (commented out):
        /*
        val notificationService = NotificationService()
        val message = if (newStatus == "down") {
            "🔴 Monitor '${monitor.name}' is DOWN\n${result.message}"
        } else {
            "🟢 Monitor '${monitor.name}' is back UP"
        }
        
        notificationService.sendAlert(
            organizationId = monitor.organizationId,
            title = "Uptime Monitor Alert",
            message = message,
            severity = if (newStatus == "down") "critical" else "info"
        )
        */
    }
}
