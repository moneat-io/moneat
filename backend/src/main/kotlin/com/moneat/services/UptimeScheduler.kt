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

package com.moneat.services

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
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

    private val slackService = SlackService()
    private val discordService = DiscordService()
    private val incidentService = com.moneat.services.incident.IncidentService()
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

        // Launch check for each monitor in parallel
        monitors.forEach { monitor ->
            // Skip if already running a check for this monitor
            if (!runningChecks.add(monitor.id)) {
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
                logger.debug { "Monitor ${monitor.name} recovered on retry $retry/${monitor.retries}" }
                break
            }
        }

        return lastResult
    }

    /**
     * Notify about status changes.
     */
    private suspend fun notifyStatusChange(
        monitor: com.moneat.models.UptimeMonitorData,
        oldStatus: String,
        newStatus: String,
        result: com.moneat.models.CheckResult
    ) {
        logger.info {
            "Uptime alert: Monitor '${monitor.name}' (${monitor.type}) changed from $oldStatus to $newStatus. " +
                "Message: ${result.message}"
        }

        val config = io.ktor.server.config.ApplicationConfig("application.conf")
        val baseUrl = config.property("email.frontendUrl").getString()
        val monitorUrl = "$baseUrl/uptime/${monitor.id}"
        val prefsService = com.moneat.services.AlertNotificationPreferencesService()

        // Send email notifications
        try {
            val emailRecipients = prefsService.getUsersWithChannelEnabled(
                organizationId = monitor.organizationId,
                alertSource = "UPTIME_MONITOR",
                channel = "email"
            )

            val emailService = com.moneat.services.EmailService()
            emailRecipients.forEach { (_, email) ->
                scope.launch {
                    try {
                        emailService.sendUptimeAlertEmail(
                            to = email,
                            monitorName = monitor.name,
                            status = newStatus,
                            message = result.message,
                            monitorUrl = monitorUrl
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to send uptime alert email to $email" }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to send uptime alert emails" }
        }

        // Send Slack notification
        val slackEnabled = try {
            prefsService.getUsersWithChannelEnabled(
                organizationId = monitor.organizationId,
                alertSource = "UPTIME_MONITOR",
                channel = "slack"
            ).isNotEmpty()
        } catch (e: Exception) {
            logger.error(e) { "Failed to evaluate Slack notification preferences for uptime monitor" }
            false
        }
        if (slackEnabled) {
            try {
                slackService.sendUptimeAlert(
                    organizationId = monitor.organizationId,
                    monitorName = monitor.name,
                    oldStatus = oldStatus,
                    newStatus = newStatus,
                    message = result.message,
                    monitorId = monitor.id,
                    baseUrl = baseUrl
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to send Slack notification for uptime monitor status change" }
            }
        }

        // Send Discord notification
        val discordEnabled = try {
            prefsService.getUsersWithChannelEnabled(
                organizationId = monitor.organizationId,
                alertSource = "UPTIME_MONITOR",
                channel = "discord"
            ).isNotEmpty()
        } catch (e: Exception) {
            logger.error(e) { "Failed to evaluate Discord notification preferences for uptime monitor" }
            false
        }
        if (discordEnabled) {
            try {
                discordService.sendUptimeAlert(
                    organizationId = monitor.organizationId,
                    monitorUrl = monitor.url ?: "N/A",
                    isDown = newStatus == "down",
                    statusCode = result.statusCode,
                    responseTime = result.responseTimeMs.toLong(),
                    errorMessage = if (result.message.isNotBlank()) result.message else null,
                    monitorId = monitor.id,
                    baseUrl = baseUrl
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to send Discord notification for uptime monitor status change" }
            }
        }

        // Fire or resolve incident alert
        try {
            if (newStatus == "down") {
                // Get severity from monitor override or fall back to routing rules
                // We always fire the incident; IncidentService will check routing rules
                val severityOverride = monitor.incidentSeverity?.let {
                    com.moneat.models.IncidentSeverity.fromString(it)
                }

                // Use override severity if set, otherwise use a default that routing rules can override
                val severity = severityOverride ?: com.moneat.models.IncidentSeverity.HIGH

                val incidentEvent = com.moneat.models.IncidentEvent(
                    title = "Uptime Monitor Down: ${monitor.name}",
                    description = "Monitor '${monitor.name}' (${monitor.type}) is down.\nError: ${result.message}",
                    severity = severity,
                    status = com.moneat.models.IncidentStatus.FIRING,
                    source = com.moneat.models.AlertSource.UPTIME_MONITOR,
                    deduplicationKey = "moneat-uptime-${monitor.id}",
                    organizationId = monitor.organizationId,
                    metadata = mapOf(
                        "monitor_id" to JsonPrimitive(monitor.id.toString()),
                        "monitor_name" to JsonPrimitive(monitor.name),
                        "monitor_type" to JsonPrimitive(monitor.type),
                        "error_message" to JsonPrimitive(result.message),
                        "response_time_ms" to JsonPrimitive(result.responseTimeMs.toString())
                    ),
                    moneatUrl = "$baseUrl/uptime/${monitor.id}"
                )
                // IncidentService will check routing rules and only fire if configured
                incidentService.fireAlert(incidentEvent)
            } else if (newStatus == "up") {
                // Resolve the incident
                incidentService.resolveAlert(
                    organizationId = monitor.organizationId,
                    source = com.moneat.models.AlertSource.UPTIME_MONITOR,
                    deduplicationKey = "moneat-uptime-${monitor.id}"
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fire/resolve incident alert for uptime monitor" }
        }
    }
}
