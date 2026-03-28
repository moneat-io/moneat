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

package com.moneat.uptime.services

import com.moneat.billing.services.BillingQuotaService
import com.moneat.incident.services.IncidentService
import com.moneat.notifications.services.AlertNotificationPreferencesService
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.shared.services.TaskLock
import com.moneat.uptime.repositories.UptimeMonitorRepositoryImpl
import com.moneat.utils.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import mu.KotlinLogging
import java.io.IOException
import java.sql.SQLException
import java.util.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Coroutine-based scheduler for uptime monitor checks.
 * Runs continuously and executes checks for monitors at their configured intervals.
 */
class UptimeScheduler(
    private val uptimeService: UptimeService = UptimeService(BillingQuotaService(), UptimeMonitorRepositoryImpl()),
    private val checkExecutor: UptimeCheckExecutor = UptimeCheckExecutor(),
    private val slackService: SlackService = SlackService(),
    private val discordService: DiscordService = DiscordService(),
    private val incidentService: IncidentService = IncidentService(),
    private val emailService: EmailService = EmailService(),
    private val prefsService: AlertNotificationPreferencesService = AlertNotificationPreferencesService(),
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

        schedulerJob =
            scope.launch {
                while (isActive) {
                    TaskLock.tryWithLock("uptime-scheduler", lockAtMostFor = 2.minutes, lockAtLeastFor = 55.seconds) {
                        checkMonitors()
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
        val monitors =
            suspendRunCatching {
                uptimeService.getMonitorsDueForCheck()
            }.getOrElse { e ->
                logger.error(e) { "Failed to fetch monitors due for check: ${e.message}" }
                return
            }

        if (monitors.isEmpty()) return

        // Launch check for each monitor in parallel
        monitors.forEach { monitor ->
            // Skip demo org monitors — their uptime data is managed by DemoDataReseeder
            if (monitor.organizationId == -1) return@forEach

            // Skip if already running a check for this monitor
            if (!runningChecks.add(monitor.id)) {
                return@forEach
            }

            scope.launch {
                try {
                    suspendRunCatching {
                        performCheck(monitor.id)
                    }.onFailure { e ->
                        logger.error(e) { "Failed to perform check for monitor ${monitor.id}: ${e.message}" }
                    }
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
        val monitor =
            suspendRunCatching {
                uptimeService.getMonitorsDueForCheck().find { it.id == monitorId }
            }.getOrElse { e ->
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
        val result =
            try {
                withTimeout(monitor.timeoutSeconds * 1000L + 5000) { // Add 5s buffer
                    checkExecutor.executeCheck(monitor)
                }
            } catch (e: TimeoutCancellationException) {
                logger.error(e) { "Check execution failed for monitor ${monitor.id}: ${e.message}" }
                com.moneat.uptime.models.CheckResult(0, -1, 0, "Check execution failed: ${e.message}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.error(e) { "Check execution failed for monitor ${monitor.id}: ${e.message}" }
                com.moneat.uptime.models.CheckResult(0, -1, 0, "Check execution failed: ${e.message}")
            } catch (e: IllegalStateException) {
                logger.error(e) { "Check execution failed for monitor ${monitor.id}: ${e.message}" }
                com.moneat.uptime.models.CheckResult(0, -1, 0, "Check execution failed: ${e.message}")
            } catch (e: SQLException) {
                logger.error(e) { "Check execution failed for monitor ${monitor.id}: ${e.message}" }
                com.moneat.uptime.models.CheckResult(0, -1, 0, "Check execution failed: ${e.message}")
            }

        // Handle retries for failed checks
        val finalResult =
            if (result.status == 0 && monitor.retries > 0) {
                handleRetries(monitor, result)
            } else {
                result
            }

        // Record heartbeat
        suspendRunCatching {
            uptimeService.recordHeartbeat(monitor.id, finalResult)
        }.getOrElse { e ->
            logger.error(e) { "Failed to record heartbeat for monitor ${monitor.id}: ${e.message}" }
        }

        // Update monitor status
        val oldStatus = monitor.status
        uptimeService.updateMonitorStatus(monitor.id, finalResult)

        // Detect status changes (up -> down or down -> up)
        val newStatus =
            when (finalResult.status) {
                1 -> "up"
                0 -> "down"
                else -> "pending"
            }

        if (oldStatus != newStatus && (oldStatus == "up" || oldStatus == "down") && (newStatus == "up" || newStatus == "down")) {
            logger.info { "Monitor ${monitor.name} status changed: $oldStatus -> $newStatus" }

            suspendRunCatching {
                notifyStatusChange(monitor, oldStatus, newStatus, finalResult)
            }.onFailure { e ->
                logger.error(e) { "Failed to send status change notification: ${e.message}" }
            }
        }
    }

    /**
     * Handle retries for failed checks.
     */
    private suspend fun handleRetries(
        monitor: com.moneat.uptime.models.UptimeMonitorData,
        initialResult: com.moneat.uptime.models.CheckResult
    ): com.moneat.uptime.models.CheckResult {
        var lastResult = initialResult

        for (retry in 1..monitor.retries) {
            delay(monitor.retryIntervalSeconds * 1000L)

            val retryResult =
                try {
                    withTimeout(monitor.timeoutSeconds * 1000L + 5000) {
                        checkExecutor.executeCheck(monitor)
                    }
                } catch (e: TimeoutCancellationException) {
                    logger.error(e) { "Retry $retry failed for monitor ${monitor.id}: ${e.message}" }
                    com.moneat.uptime.models.CheckResult(0, -1, 0, "Retry failed: ${e.message}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    logger.error(e) { "Retry $retry failed for monitor ${monitor.id}: ${e.message}" }
                    com.moneat.uptime.models.CheckResult(0, -1, 0, "Retry failed: ${e.message}")
                } catch (e: IllegalStateException) {
                    logger.error(e) { "Retry $retry failed for monitor ${monitor.id}: ${e.message}" }
                    com.moneat.uptime.models.CheckResult(0, -1, 0, "Retry failed: ${e.message}")
                } catch (e: SQLException) {
                    logger.error(e) { "Retry $retry failed for monitor ${monitor.id}: ${e.message}" }
                    com.moneat.uptime.models.CheckResult(0, -1, 0, "Retry failed: ${e.message}")
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
        monitor: com.moneat.uptime.models.UptimeMonitorData,
        oldStatus: String,
        newStatus: String,
        result: com.moneat.uptime.models.CheckResult
    ) {
        logger.info {
            "Uptime alert: Monitor '${monitor.name}' (${monitor.type}) changed from $oldStatus to $newStatus. " +
                "Message: ${result.message}"
        }

        val config =
            io.ktor.server.config
                .ApplicationConfig("application.conf")
        val baseUrl = config.property("email.frontendUrl").getString()
        val monitorUrl = "$baseUrl/uptime/${monitor.id}"
        // Send email notifications
        suspendRunCatching {
            val emailRecipients =
                prefsService.getUsersWithChannelEnabled(
                    organizationId = monitor.organizationId,
                    alertSource = "UPTIME_MONITOR",
                    channel = "email"
                )

            emailRecipients.forEach { (_, email) ->
                scope.launch {
                    suspendRunCatching {
                        emailService.sendUptimeAlertEmail(
                            to = email,
                            monitorName = monitor.name,
                            status = newStatus,
                            message = result.message,
                            monitorUrl = monitorUrl
                        )
                    }.onFailure { e ->
                        logger.error(e) { "Failed to send uptime alert email to $email" }
                    }
                }
            }
        }.onFailure { e ->
            logger.error(e) { "Failed to send uptime alert emails" }
        }

        // Send Slack notification
        val slackEnabled =
            suspendRunCatching {
                prefsService
                    .getUsersWithChannelEnabled(
                        organizationId = monitor.organizationId,
                        alertSource = "UPTIME_MONITOR",
                        channel = "slack"
                    ).isNotEmpty()
            }.getOrElse { e ->
                logger.error(e) { "Failed to evaluate Slack notification preferences for uptime monitor" }
                false
            }
        if (slackEnabled) {
            suspendRunCatching {
                slackService.sendUptimeAlert(
                    organizationId = monitor.organizationId,
                    monitorName = monitor.name,
                    oldStatus = oldStatus,
                    newStatus = newStatus,
                    message = result.message,
                    monitorId = monitor.id,
                    baseUrl = baseUrl
                )
            }.onFailure { e ->
                logger.error(e) { "Failed to send Slack notification for uptime monitor status change" }
            }
        }

        // Send Discord notification
        val discordEnabled =
            suspendRunCatching {
                prefsService
                    .getUsersWithChannelEnabled(
                        organizationId = monitor.organizationId,
                        alertSource = "UPTIME_MONITOR",
                        channel = "discord"
                    ).isNotEmpty()
            }.getOrElse { e ->
                logger.error(e) { "Failed to evaluate Discord notification preferences for uptime monitor" }
                false
            }
        if (discordEnabled) {
            suspendRunCatching {
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
            }.onFailure { e ->
                logger.error(e) { "Failed to send Discord notification for uptime monitor status change" }
            }
        }

        // Fire or resolve incident alert
        suspendRunCatching {
            if (newStatus == "down") {
                // Get severity from monitor override or fall back to routing rules
                // We always fire the incident; IncidentService will check routing rules
                val severityOverride =
                    monitor.incidentSeverity?.let {
                        com.moneat.incident.models.IncidentSeverity
                            .fromString(it)
                    }

                // Use override severity if set, otherwise use a default that routing rules can override
                val severity = severityOverride ?: com.moneat.incident.models.IncidentSeverity.HIGH

                val incidentEvent =
                    com.moneat.incident.models.IncidentEvent(
                        title = "Uptime Monitor Down: ${monitor.name}",
                        description = "Monitor '${monitor.name}' (${monitor.type}) is down.\nError: ${result.message}",
                        severity = severity,
                        status = com.moneat.incident.models.IncidentStatus.FIRING,
                        source = com.moneat.incident.models.AlertSource.UPTIME_MONITOR,
                        deduplicationKey = "moneat-uptime-${monitor.id}",
                        organizationId = monitor.organizationId,
                        metadata =
                        mapOf(
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
                    source = com.moneat.incident.models.AlertSource.UPTIME_MONITOR,
                    deduplicationKey = "moneat-uptime-${monitor.id}"
                )
            }
        }.onFailure { e ->
            logger.error(e) { "Failed to fire/resolve incident alert for uptime monitor" }
        }
    }
}
