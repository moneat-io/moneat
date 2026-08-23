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

import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.alerts.services.AlertFanoutPlan
import com.moneat.alerts.services.AlertLifecycleOrchestrator
import com.moneat.billing.services.BillingQuotaService
import com.moneat.incident.services.IncidentService
import com.moneat.shared.services.TaskLock
import com.moneat.uptime.models.CheckResult
import com.moneat.uptime.models.UptimeMonitorData
import com.moneat.uptime.repositories.UptimeMonitorRepositoryImpl
import com.moneat.utils.TimeConstants.MILLIS_PER_SECOND_LONG
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
import java.util.Collections
import java.util.UUID
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
    private val incidentService: IncidentService = IncidentService(),
    private val billingQuotaService: BillingQuotaService = BillingQuotaService(),
    private val frontendBaseUrl: String,
    private val alertOrchestrator: AlertLifecycleOrchestrator = AlertLifecycleOrchestrator(
        incidentFanoutProvider = { incidentService },
    ),
) {
    companion object {
        private const val CHECK_STATUS_DOWN = 0
        private const val CHECK_STATUS_UP = 1
        private const val TIMEOUT_BUFFER_MS = 5000L
    }

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
                    delay(MILLIS_PER_SECOND_LONG)
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
        val monitor = loadMonitorForCheck(monitorId) ?: return

        if (monitor.type.lowercase() == "push") {
            return
        }

        val result = executeCheckWithTimeout(monitor, "Check execution failed", "Check execution failed")
        val finalResult = result.withRetriesIfNeeded(monitor)
        val oldStatus = monitor.status
        recordCheckOutcome(monitor, finalResult)
        publishLifecycleIfNeeded(monitor, oldStatus, finalResult)
    }

    private suspend fun loadMonitorForCheck(monitorId: UUID): UptimeMonitorData? {
        val monitor =
            suspendRunCatching {
                uptimeService.getMonitorsDueForCheck().find { it.id == monitorId }
            }.getOrElse { e ->
                logger.error(e) { "Failed to fetch monitor $monitorId: ${e.message}" }
                return null
            }

        if (monitor == null) {
            logger.warn { "Monitor $monitorId not found or not active" }
        }

        return monitor
    }

    private suspend fun executeCheckWithTimeout(
        monitor: UptimeMonitorData,
        failureLogPrefix: String,
        failureMessagePrefix: String
    ): CheckResult =
        try {
            withTimeout(monitor.timeoutSeconds * MILLIS_PER_SECOND_LONG + TIMEOUT_BUFFER_MS) {
                checkExecutor.executeCheck(monitor)
            }
        } catch (e: TimeoutCancellationException) {
            failedCheckResult(monitor, e, failureLogPrefix, failureMessagePrefix)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            failedCheckResult(monitor, e, failureLogPrefix, failureMessagePrefix)
        } catch (e: IllegalStateException) {
            failedCheckResult(monitor, e, failureLogPrefix, failureMessagePrefix)
        } catch (e: SQLException) {
            failedCheckResult(monitor, e, failureLogPrefix, failureMessagePrefix)
        }

    private fun failedCheckResult(
        monitor: UptimeMonitorData,
        exception: Exception,
        failureLogPrefix: String,
        failureMessagePrefix: String
    ): CheckResult {
        logger.error(exception) { "$failureLogPrefix for monitor ${monitor.id}: ${exception.message}" }
        return CheckResult(CHECK_STATUS_DOWN, -1, 0, "$failureMessagePrefix: ${exception.message}")
    }

    private suspend fun CheckResult.withRetriesIfNeeded(monitor: UptimeMonitorData): CheckResult =
        if (status == CHECK_STATUS_DOWN && monitor.retries > 0) {
            handleRetries(monitor, this)
        } else {
            this
        }

    private suspend fun recordCheckOutcome(
        monitor: UptimeMonitorData,
        finalResult: CheckResult
    ) {
        suspendRunCatching {
            uptimeService.recordHeartbeat(monitor.id, finalResult)
        }.getOrElse { e ->
            logger.error(e) { "Failed to record heartbeat for monitor ${monitor.id}: ${e.message}" }
        }

        suspendRunCatching {
            incrementUptimeCheckCount(monitor.organizationId)
        }.getOrElse { e ->
            logger.debug(e) { "Failed to increment uptime check count for org ${monitor.organizationId}" }
        }

        uptimeService.updateMonitorStatus(monitor.id, finalResult)
    }

    private suspend fun publishLifecycleIfNeeded(
        monitor: UptimeMonitorData,
        oldStatus: String,
        finalResult: CheckResult
    ) {
        val newStatus = statusFromResult(finalResult)

        if (isUptimeStatusTransition(oldStatus, newStatus)) {
            logger.info { "Monitor ${monitor.name} status changed: $oldStatus -> $newStatus" }

            suspendRunCatching {
                notifyStatusChange(monitor, oldStatus, newStatus, finalResult)
            }.onFailure { e ->
                logger.error(e) { "Failed to send status change notification: ${e.message}" }
            }
            return
        }

        if (oldStatus == "down" && newStatus == "down") {
            suspendRunCatching {
                publishStillDownWorkflow(monitor, finalResult)
            }.onFailure { e ->
                logger.error(e) { "Failed to publish uptime reminder workflow: ${e.message}" }
            }
        }
    }

    private fun statusFromResult(result: CheckResult): String =
        when (result.status) {
            CHECK_STATUS_UP -> "up"
            CHECK_STATUS_DOWN -> "down"
            else -> "pending"
        }

    private fun isUptimeStatusTransition(oldStatus: String, newStatus: String): Boolean =
        oldStatus != newStatus && oldStatus.isUptimeStatus() && newStatus.isUptimeStatus()

    private fun String.isUptimeStatus(): Boolean = this == "up" || this == "down"

    /**
     * Handle retries for failed checks.
     */
    private suspend fun handleRetries(
        monitor: UptimeMonitorData,
        initialResult: CheckResult
    ): CheckResult {
        var lastResult = initialResult

        for (retry in 1..monitor.retries) {
            delay(monitor.retryIntervalSeconds * MILLIS_PER_SECOND_LONG)

            val retryResult = executeCheckWithTimeout(monitor, "Retry $retry failed", "Retry failed")

            lastResult = retryResult

            // If check succeeded, stop retrying
            if (retryResult.status == CHECK_STATUS_UP) {
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
        monitor: UptimeMonitorData,
        oldStatus: String,
        newStatus: String,
        result: CheckResult
    ) {
        logger.info {
            "Uptime alert: Monitor '${monitor.name}' (${monitor.type}) changed from $oldStatus to $newStatus. " +
                "Message: ${result.message}"
        }

        val baseUrl = frontendBaseUrl

        // Build an alert lifecycle event; IncidentService applies incident-provider routing.
        suspendRunCatching {
            if (newStatus == "down") {
                val alertLifecycleEvent = uptimeDownEvent(monitor, result, baseUrl)
                alertOrchestrator.process(alertLifecycleEvent, AlertFanoutPlan.FULL)
            } else if (newStatus == "up") {
                alertOrchestrator.process(
                    AlertLifecycleEvent(
                        title = "Uptime Monitor Recovered: ${monitor.name}",
                        description = "Monitor '${monitor.name}' (${monitor.type}) is back up.",
                        priority = monitor.alertPriority?.let { AlertPriority.fromString(it) } ?: AlertPriority.P1,
                        status = AlertStatus.RESOLVED,
                        source = AlertSource.UPTIME_MONITOR,
                        deduplicationKey = "moneat-uptime-${monitor.id}",
                        organizationId = monitor.organizationId,
                        moneatUrl = "$baseUrl/uptime/${monitor.id}",
                    ),
                    AlertFanoutPlan.FULL,
                )
            }
        }.onFailure { e ->
            logger.error(e) { "Failed to fire/resolve incident provider alert for uptime monitor" }
        }
    }

    private suspend fun publishStillDownWorkflow(
        monitor: UptimeMonitorData,
        result: CheckResult
    ) {
        alertOrchestrator.process(
            uptimeDownEvent(monitor, result, frontendBaseUrl),
            AlertFanoutPlan.WORKFLOW_ONLY,
        )
    }

    private fun uptimeDownEvent(
        monitor: UptimeMonitorData,
        result: CheckResult,
        baseUrl: String
    ): AlertLifecycleEvent {
        val priorityOverride = monitor.alertPriority?.let { AlertPriority.fromString(it) }
        val priority = priorityOverride ?: AlertPriority.P1
        return AlertLifecycleEvent(
            title = "Uptime Monitor Down: ${monitor.name}",
            description = "Monitor '${monitor.name}' (${monitor.type}) is down.\nError: ${result.message}",
            priority = priority,
            status = AlertStatus.FIRING,
            source = AlertSource.UPTIME_MONITOR,
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
    }

    private fun incrementUptimeCheckCount(organizationId: Int) {
        billingQuotaService.incrementUsageCounters(organizationId, uptimeChecks = 1)
    }
}
