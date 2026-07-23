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

package com.moneat.monitor.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.AlertStatus
import com.moneat.incident.services.IncidentService
import com.moneat.monitor.models.AlertData
import com.moneat.monitor.models.CreateSilencePeriodRequest
import com.moneat.monitor.models.SilencePeriodResponse
import com.moneat.shared.models.AlertSilencePeriods
import com.moneat.shared.models.HostAlertSettings
import com.moneat.shared.models.HostAlertTemplateStates
import com.moneat.shared.models.HostAlerts
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.OrganizationAlertTemplates
import com.moneat.shared.services.organizationResourceId
import com.moneat.shared.services.requireResourceId
import com.moneat.shared.services.TaskLock
import com.moneat.shared.services.toUuidOrNull
import com.moneat.shared.services.userResourceId
import com.moneat.shared.services.userResourceIds
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.services.AlertResolvedWorkflowEvent
import com.moneat.workflows.services.WorkflowService
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private const val CURRENT_METRIC_LOOKBACK_MINUTES = 10
private const val EMAIL_FRONTEND_URL_CONFIG = "email.frontendUrl"
private const val LATEST_METRIC_VALUE_EXPR = "argMax(value, timestamp)"
private const val MEM_AVAILABLE_METRIC = "system.mem.available"
private const val MEM_USED_METRIC = "system.mem.used"
private const val MEM_TOTAL_METRIC = "system.mem.total"
private const val DISK_PERCENT_METRIC = "system.disk.percent"
private const val DISK_USED_METRIC = "system.disk.used"
private const val DISK_TOTAL_METRIC = "system.disk.total"
private const val DISK_ID_EXPRESSION = """
    if(
        metric_identity != '',
        metric_identity,
        multiIf(
            tags['device_name'] != '', tags['device_name'],
            tags['device'] != '', tags['device'],
            tags['mount_point'] != '', tags['mount_point'],
            tags['mountpoint'] != '', tags['mountpoint'],
            tags['filesystem'] != '', tags['filesystem'],
            'default'
        )
    )
"""

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private fun memoryPercentMetricFilter(): String =
    "metric_name IN ('$MEM_AVAILABLE_METRIC','$MEM_USED_METRIC','$MEM_TOTAL_METRIC')"

private fun latestMemoryPercentExpr(): String =
    """
    coalesce(
        if(
            countIf(metric_name = '$MEM_AVAILABLE_METRIC') > 0 AND
                countIf(metric_name = '$MEM_TOTAL_METRIC') > 0,
            (1 - argMaxIf(value, timestamp, metric_name = '$MEM_AVAILABLE_METRIC') /
                nullIf(argMaxIf(value, timestamp, metric_name = '$MEM_TOTAL_METRIC'), 0)) * 100,
            NULL
        ),
        if(
            countIf(metric_name = '$MEM_USED_METRIC') > 0 AND countIf(metric_name = '$MEM_TOTAL_METRIC') > 0,
            argMaxIf(value, timestamp, metric_name = '$MEM_USED_METRIC') /
                nullIf(argMaxIf(value, timestamp, metric_name = '$MEM_TOTAL_METRIC'), 0) * 100,
            NULL
        )
    )
    """.trimIndent()

private fun rollupMemoryPercentExpr(): String =
    """
    coalesce(
        if(
            sumIf(value_count, metric_name = '$MEM_AVAILABLE_METRIC') > 0 AND
                sumIf(value_count, metric_name = '$MEM_TOTAL_METRIC') > 0,
            (1 - (
                sumIf(value_sum, metric_name = '$MEM_AVAILABLE_METRIC') /
                nullIf(sumIf(value_count, metric_name = '$MEM_AVAILABLE_METRIC'), 0)
            ) / nullIf(
                sumIf(value_sum, metric_name = '$MEM_TOTAL_METRIC') /
                nullIf(sumIf(value_count, metric_name = '$MEM_TOTAL_METRIC'), 0),
                0
            )) * 100,
            NULL
        ),
        if(
            sumIf(value_count, metric_name = '$MEM_USED_METRIC') > 0 AND
                sumIf(value_count, metric_name = '$MEM_TOTAL_METRIC') > 0,
            (
                sumIf(value_sum, metric_name = '$MEM_USED_METRIC') /
                nullIf(sumIf(value_count, metric_name = '$MEM_USED_METRIC'), 0)
            ) / nullIf(
                sumIf(value_sum, metric_name = '$MEM_TOTAL_METRIC') /
                nullIf(sumIf(value_count, metric_name = '$MEM_TOTAL_METRIC'), 0),
                0
            ) * 100,
            NULL
        )
    )
    """.trimIndent()

private fun diskPercentMetricFilter(): String =
    "metric_name IN ('$DISK_PERCENT_METRIC','$DISK_USED_METRIC','$DISK_TOTAL_METRIC')"

private fun latestDiskPercentExpr(): String =
    """
    coalesce(
        if(
            countIf(metric_name = '$DISK_PERCENT_METRIC') > 0,
            argMaxIf(value, timestamp, metric_name = '$DISK_PERCENT_METRIC'),
            NULL
        ),
        if(
            countIf(metric_name = '$DISK_USED_METRIC') > 0 AND countIf(metric_name = '$DISK_TOTAL_METRIC') > 0,
            argMaxIf(value, timestamp, metric_name = '$DISK_USED_METRIC') /
                nullIf(argMaxIf(value, timestamp, metric_name = '$DISK_TOTAL_METRIC'), 0) * 100,
            NULL
        )
    )
    """.trimIndent()

private fun rollupDiskPercentExpr(): String =
    """
    coalesce(
        if(
            sumIf(value_count, metric_name = '$DISK_PERCENT_METRIC') > 0,
            sumIf(value_sum, metric_name = '$DISK_PERCENT_METRIC') /
                nullIf(sumIf(value_count, metric_name = '$DISK_PERCENT_METRIC'), 0),
            NULL
        ),
        if(
            sumIf(value_count, metric_name = '$DISK_USED_METRIC') > 0 AND
                sumIf(value_count, metric_name = '$DISK_TOTAL_METRIC') > 0,
            (
                sumIf(value_sum, metric_name = '$DISK_USED_METRIC') /
                nullIf(sumIf(value_count, metric_name = '$DISK_USED_METRIC'), 0)
            ) / nullIf(
                sumIf(value_sum, metric_name = '$DISK_TOTAL_METRIC') /
                nullIf(sumIf(value_count, metric_name = '$DISK_TOTAL_METRIC'), 0),
                0
            ) * 100,
            NULL
        )
    )
    """.trimIndent()

class MonitorAlertService(
    private val incidentService: IncidentService = IncidentService(),
    private val workflowService: WorkflowService = WorkflowService(),
) {
    private val config = ApplicationConfig("application.conf")
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()

    private var evaluationJob: Job? = null
    private var statusCheckJob: Job? = null
    private var cleanupJob: Job? = null

    companion object {
        const val EVALUATION_INTERVAL_SECONDS = 30
        const val STATUS_CHECK_INTERVAL_SECONDS = 60
        const val HOST_DOWN_THRESHOLD_SECONDS = 300 // 5 minutes
        const val MIN_ALERT_INTERVAL_MINUTES = 15 // Don't spam alerts
        const val POLL_INTERVAL_SECONDS = 15
        const val MIN_DATA_POINT_RATIO = 0.8
        private const val SECONDS_PER_MINUTE = 60
    }

    private data class HostStatusSnapshot(
        val hostId: Int,
        val hostName: String,
        val organizationId: Int,
        val currentStatus: String,
        val lastSeenAt: Instant
    )

    /**
     * Start the background jobs for alert evaluation and status checking.
     */
    fun start(scope: CoroutineScope) {
        logger.info { "Starting MonitorAlertService background jobs" }

        // Alert evaluation job
        evaluationJob =
            scope.launch {
                while (isActive) {
                    TaskLock.tryWithLock(
                        "monitor-alert-evaluation",
                        lockAtMostFor = 5.minutes,
                        lockAtLeastFor = (EVALUATION_INTERVAL_SECONDS - 5).seconds
                    ) {
                        evaluateAlerts()
                    }
                    delay(EVALUATION_INTERVAL_SECONDS.seconds)
                }
            }

        // Host status check job
        statusCheckJob =
            scope.launch {
                while (isActive) {
                    TaskLock.tryWithLock(
                        "monitor-status-check",
                        lockAtMostFor = 5.minutes,
                        lockAtLeastFor = (STATUS_CHECK_INTERVAL_SECONDS - 5).seconds
                    ) {
                        checkHostStatuses()
                    }
                    delay(STATUS_CHECK_INTERVAL_SECONDS.seconds)
                }
            }

        // Expired silence period cleanup job (runs every 5 minutes)
        cleanupJob =
            scope.launch {
                while (isActive) {
                    TaskLock.tryWithLock(
                        "monitor-silence-cleanup",
                        lockAtMostFor = 5.minutes,
                        lockAtLeastFor = 4.minutes + 55.seconds
                    ) {
                        cleanupExpiredSilencePeriods()
                    }
                    delay(5.minutes)
                }
            }

        logger.info { "MonitorAlertService background jobs started" }
    }

    /**
     * Stop the background jobs.
     */
    fun stop() {
        logger.info { "Stopping MonitorAlertService background jobs" }
        evaluationJob?.cancel()
        statusCheckJob?.cancel()
        cleanupJob?.cancel()
    }

    /**
     * Evaluate all active alerts.
     */
    private suspend fun evaluateAlerts() {
        val alerts =
            transaction {
                val results = mutableListOf<Triple<AlertData, String, Int>>()

                val globalScopeHostIds =
                    HostAlertSettings
                        .selectAll()
                        .where {
                            HostAlertSettings.scope eq MonitorService.ALERT_SCOPE_GLOBAL
                        }.map { it[HostAlertSettings.host_id] }

                val hostScopedAlerts =
                    if (globalScopeHostIds.isEmpty()) {
                        HostAlerts
                            .innerJoin(Hosts)
                            .selectAll()
                            .where { HostAlerts.enabled eq true }
                            .toList()
                    } else {
                        HostAlerts
                            .innerJoin(Hosts)
                            .selectAll()
                            .where {
                                (HostAlerts.enabled eq true) and
                                    (HostAlerts.host_id notInList globalScopeHostIds)
                            }.toList()
                    }

                hostScopedAlerts.forEach { row ->
                    val hostName = row[Hosts.display_name] ?: row[Hosts.hostname]
                    results +=
                        Triple(
                            AlertData(
                                id = row[HostAlerts.id],
                                hostId = row[HostAlerts.host_id],
                                organizationId = row[HostAlerts.organization_id],
                                metric = row[HostAlerts.metric],
                                condition = row[HostAlerts.condition],
                                threshold = row[HostAlerts.threshold],
                                durationSeconds = row[HostAlerts.duration_seconds],
                                enabled = row[HostAlerts.enabled],
                                lastTriggeredAt = row[HostAlerts.last_triggered_at],
                                createdAt = row[HostAlerts.created_at],
                                scope = MonitorService.ALERT_SCOPE_HOST,
                                templateAlertId = null
                            ),
                            hostName,
                            row[Hosts.organization_id]
                        )
                }

                if (globalScopeHostIds.isNotEmpty()) {
                    val globalTemplates =
                        OrganizationAlertTemplates
                            .selectAll()
                            .where {
                                OrganizationAlertTemplates.enabled eq true
                            }.toList()

                    if (globalTemplates.isNotEmpty()) {
                        val globalHosts =
                            Hosts
                                .innerJoin(HostAlertSettings)
                                .selectAll()
                                .where {
                                    (HostAlertSettings.scope eq MonitorService.ALERT_SCOPE_GLOBAL) and
                                        (HostAlertSettings.host_id inList globalScopeHostIds)
                                }.toList()

                        val templateIds = globalTemplates.map { it[OrganizationAlertTemplates.id] }
                        val stateMap =
                            if (templateIds.isEmpty()) {
                                emptyMap()
                            } else {
                                HostAlertTemplateStates
                                    .selectAll()
                                    .where {
                                        (HostAlertTemplateStates.template_alert_id inList templateIds) and
                                            (HostAlertTemplateStates.host_id inList globalScopeHostIds)
                                    }.associate {
                                        Pair(
                                            it[HostAlertTemplateStates.template_alert_id],
                                            it[HostAlertTemplateStates.host_id]
                                        ) to it[HostAlertTemplateStates.last_triggered_at]
                                    }
                            }

                        globalHosts.forEach { hostRow ->
                            val hostId = hostRow[Hosts.id]
                            val hostName = hostRow[Hosts.display_name] ?: hostRow[Hosts.hostname]
                            val orgId = hostRow[Hosts.organization_id]

                            globalTemplates
                                .filter { template -> template[OrganizationAlertTemplates.organization_id] == orgId }
                                .forEach { template ->
                                    val templateId = template[OrganizationAlertTemplates.id]
                                    results +=
                                        Triple(
                                            AlertData(
                                                id = templateId,
                                                hostId = hostId,
                                                organizationId = orgId,
                                                metric = template[OrganizationAlertTemplates.metric],
                                                condition = template[OrganizationAlertTemplates.condition],
                                                threshold = template[OrganizationAlertTemplates.threshold],
                                                durationSeconds = template[OrganizationAlertTemplates.duration_seconds],
                                                enabled = template[OrganizationAlertTemplates.enabled],
                                                lastTriggeredAt = stateMap[Pair(templateId, hostId)],
                                                createdAt = template[OrganizationAlertTemplates.created_at],
                                                scope = MonitorService.ALERT_SCOPE_GLOBAL,
                                                templateAlertId = templateId
                                            ),
                                            hostName,
                                            orgId
                                        )
                                }
                        }
                    }
                }

                results
            }

        logger.debug { "Evaluating ${alerts.size} alerts" }

        for ((alert, hostName, orgId) in alerts) {
            suspendRunCatching {
                evaluateAlert(alert, hostName, orgId)
            }.getOrElse { e ->
                logger.error(e) { "Error evaluating alert ${alert.id}" }
            }
        }
    }

    /**
     * Evaluate a single alert.
     */
    private suspend fun evaluateAlert(
        alert: AlertData,
        hostName: String,
        organizationId: Int
    ) {
        val alertKey = hostAlertRedisKey(alert)
        val currentValue = getCurrentMetricValue(alert.hostId, alert.organizationId, alert.metric) ?: return
        val triggered = isThresholdTriggered(alert.condition, currentValue, alert.threshold)

        if (!triggered) {
            handleRecoveredAlert(alert, hostName, organizationId, alertKey)
            return
        }

        if (alert.durationSeconds > 0 && !checkSustainedCondition(alert)) {
            return
        }

        val now = Clock.System.now()
        if (isThrottledByInterval(alert.lastTriggeredAt, now)) {
            setAlertTriggeredState(alertKey, isThrottled = true)
            return
        }

        if (isAnySilenceActive(organizationId)) {
            return
        }

        triggerAlert(alert, hostName, organizationId, currentValue, alertKey, now)
    }

    private suspend fun handleRecoveredAlert(
        alert: AlertData,
        hostName: String,
        organizationId: Int,
        alertKey: String
    ) {
        if (!wasAlertTriggered(alert, alertKey)) return

        clearAlertState(alert, alertKey)

        val metricLabel = getMetricLabel(alert.metric)
        val dashboardUrl = hostDashboardUrl(alert.hostId, organizationId)
        val alertPriorityOverride = hostAlertPriorityOverride(alert)
        val title = "$hostName - $metricLabel recovered"
        val description = "The alert for $metricLabel is no longer active."
        val deduplicationKey = hostAlertDedupKey(alert)

        publishRecoveredHostAlertWorkflow(
            alert = alert,
            organizationId = organizationId,
            deduplicationKey = deduplicationKey,
            title = title,
            description = description,
            dashboardUrl = dashboardUrl,
            priority = alertPriorityOverride ?: AlertPriority.P1
        )
        autoResolveRecoveredHostIncident(
            alert = alert,
            organizationId = organizationId,
            deduplicationKey = deduplicationKey,
            title = title,
            description = description,
            dashboardUrl = dashboardUrl,
            alertPriorityOverride = alertPriorityOverride
        )
        logger.info { "Alert ${alert.id} recovered for host ${alert.hostId}" }
    }

    private suspend fun publishRecoveredHostAlertWorkflow(
        alert: AlertData,
        organizationId: Int,
        deduplicationKey: String,
        title: String,
        description: String,
        dashboardUrl: String,
        priority: AlertPriority
    ) {
        suspendRunCatching {
            workflowService.publishAlertResolved(
                AlertResolvedWorkflowEvent(
                    organizationId = organizationId,
                    source = AlertSource.HOST_ALERT.name,
                    deduplicationKey = deduplicationKey,
                    title = title,
                    description = description,
                    moneatUrl = dashboardUrl,
                    priority = priority,
                )
            )
        }.getOrElse { e ->
            logger.error(e) { "Failed to publish recovered host alert workflow ${alert.id}" }
        }
    }

    private suspend fun autoResolveRecoveredHostIncident(
        alert: AlertData,
        organizationId: Int,
        deduplicationKey: String,
        title: String,
        description: String,
        dashboardUrl: String,
        alertPriorityOverride: AlertPriority?
    ) {
        if (alertPriorityOverride == null) return

        suspendRunCatching {
            incidentService.autoResolveAlert(
                organizationId = organizationId,
                source = AlertSource.HOST_ALERT,
                deduplicationKey = deduplicationKey,
                title = title,
                description = description,
                moneatUrl = dashboardUrl,
                publishWorkflow = false
            )
        }.getOrElse { e ->
            logger.error(e) { "Failed to resolve incident for recovered alert ${alert.id}" }
        }
    }

    private suspend fun triggerAlert(
        alert: AlertData,
        hostName: String,
        organizationId: Int,
        currentValue: Double,
        alertKey: String,
        now: Instant
    ) {
        logger.info {
            "Alert ${alert.id} triggered for host ${alert.hostId}: " +
                "${alert.metric} ${alert.condition} ${alert.threshold} (current: $currentValue)"
        }

        setAlertTriggeredState(alertKey)
        updateLastTriggeredAt(alert, now)
        sendAlertNotification(alert, hostName, organizationId, currentValue)
    }

    private fun wasAlertTriggered(
        alert: AlertData,
        alertKey: String
    ): Boolean =
        alert.lastTriggeredAt != null || wasAlertTriggeredInRedis(alertKey)

    private fun wasAlertTriggeredInRedis(alertKey: String): Boolean =
        suspendRunCatching {
            RedisConfig.isConnected() && RedisConfig.sync().get(alertKey) == "TRIGGERED"
        }.getOrElse {
            false
        }

    private fun clearAlertState(
        alert: AlertData,
        alertKey: String
    ) {
        suspendRunCatching {
            if (RedisConfig.isConnected()) {
                RedisConfig.sync().del(alertKey)
            }
        }.getOrElse { e ->
            logger.error(e) { "Failed to clear alert state in Redis" }
        }
        clearPersistedAlertState(alert)
    }

    private fun clearPersistedAlertState(alert: AlertData) {
        if (alert.scope == MonitorService.ALERT_SCOPE_GLOBAL && alert.templateAlertId != null) {
            clearTemplateAlertTriggeredAt(alert)
            return
        }

        transaction {
            HostAlerts.update({ HostAlerts.id eq alert.id }) {
                it[last_triggered_at] = null
            }
        }
    }

    private fun clearTemplateAlertTriggeredAt(alert: AlertData) {
        val templateAlertId = alert.templateAlertId ?: return
        transaction {
            HostAlertTemplateStates.update({
                (HostAlertTemplateStates.template_alert_id eq templateAlertId) and
                    (HostAlertTemplateStates.host_id eq alert.hostId)
            }) {
                it[last_triggered_at] = null
            }
        }
    }

    private fun setAlertTriggeredState(
        alertKey: String,
        isThrottled: Boolean = false
    ) {
        suspendRunCatching {
            if (RedisConfig.isConnected()) {
                RedisConfig.sync().set(alertKey, "TRIGGERED")
            }
        }.getOrElse { e ->
            if (isThrottled) {
                logger.debug(e) { "Failed to update throttled alert state in Redis" }
            } else {
                logger.error(e) { "Failed to set alert state in Redis" }
            }
        }
    }

    private fun updateLastTriggeredAt(alert: AlertData, now: Instant) {
        if (alert.scope == MonitorService.ALERT_SCOPE_GLOBAL && alert.templateAlertId != null) {
            upsertTemplateAlertTriggeredAt(alert, now)
            return
        }

        transaction {
            HostAlerts.update({ HostAlerts.id eq alert.id }) {
                it[last_triggered_at] = now
            }
        }
    }

    private fun upsertTemplateAlertTriggeredAt(alert: AlertData, now: Instant) {
        val templateAlertId = alert.templateAlertId ?: return
        transaction {
            val query =
                (HostAlertTemplateStates.template_alert_id eq templateAlertId) and
                    (HostAlertTemplateStates.host_id eq alert.hostId)
            val existing = HostAlertTemplateStates.selectAll().where { query }.firstOrNull()

            if (existing != null) {
                HostAlertTemplateStates.update({ query }) {
                    it[last_triggered_at] = now
                }
            } else {
                HostAlertTemplateStates.insert {
                    it[HostAlertTemplateStates.template_alert_id] = templateAlertId
                    it[HostAlertTemplateStates.host_id] = alert.hostId
                    it[HostAlertTemplateStates.last_triggered_at] = now
                }
            }
        }
    }

    /**
     * Get the current value of a metric for a host from metrics table.
     */
    internal suspend fun getCurrentMetricValue(
        hostId: Int,
        organizationId: Int,
        metric: String
    ): Double? {
        val query = currentMetricValueQuery(hostId, organizationId, metric) ?: return null

        return suspendRunCatching {
            val response = ClickHouseClient.execute(query)

            if (!response.status.isSuccess()) {
                logger.warn { "Failed to fetch metric value for alert" }
                return null
            }

            val body = response.bodyAsText()
            if (body.isBlank()) return null

            val json = Json { ignoreUnknownKeys = true }
            val result = json.parseToJsonElement(body).jsonObject
            val data = result["data"]?.jsonArray?.firstOrNull()?.jsonArray ?: return null

            data[0].toString().replace("\"", "").toDoubleOrNull()
        }.getOrElse { e ->
            logger.error(e) { "Error fetching metric value" }
            null
        }
    }

    internal fun currentMetricValueQuery(
        hostId: Int,
        organizationId: Int,
        metric: String
    ): String? {
        if (metric == "disk_percent") {
            return diskPercentCurrentMetricQuery(hostId, organizationId)
        }

        val (selectExpr, metricFilter) =
            when (metric) {
                "cpu_percent" -> LATEST_METRIC_VALUE_EXPR to "metric_name = 'system.cpu.percent'"
                "mem_percent" -> latestMemoryPercentExpr() to memoryPercentMetricFilter()
                "load_1" -> LATEST_METRIC_VALUE_EXPR to "metric_name = 'system.load.1'"
                "load_5" -> LATEST_METRIC_VALUE_EXPR to "metric_name = 'system.load.5'"
                "load_15" -> LATEST_METRIC_VALUE_EXPR to "metric_name = 'system.load.15'"
                "temp_max" -> LATEST_METRIC_VALUE_EXPR to "metric_name = 'system.temp.max'"
                "gpu_percent" -> LATEST_METRIC_VALUE_EXPR to "metric_name = 'system.gpu.percent'"
                "battery_percent" -> LATEST_METRIC_VALUE_EXPR to "metric_name = 'system.battery.percent'"
                else -> return null
            }

        return """
            SELECT $selectExpr as value
            FROM `$clickhouseDb`.metrics_latest_by_host
            WHERE organization_id = $organizationId
              AND host_id = $hostId
              AND $metricFilter
              AND timestamp >= now64(3) - INTERVAL $CURRENT_METRIC_LOOKBACK_MINUTES MINUTE
            FORMAT JSONCompact
        """.trimIndent()
    }

    private fun diskPercentCurrentMetricQuery(
        hostId: Int,
        organizationId: Int
    ): String =
        """
        SELECT
            max(pct) AS value
        FROM (
            SELECT
                $DISK_ID_EXPRESSION AS disk_identity,
                ${latestDiskPercentExpr()} AS pct
            FROM `$clickhouseDb`.metrics_latest_by_host
            WHERE organization_id = $organizationId
              AND host_id = $hostId
              AND ${diskPercentMetricFilter()}
              AND timestamp >= now64(3) - INTERVAL $CURRENT_METRIC_LOOKBACK_MINUTES MINUTE
            GROUP BY disk_identity
        )
        FORMAT JSONCompact
        """.trimIndent()

    private fun sustainedConditionQuery(alert: AlertData, baseFilter: String): String? =
        when (alert.metric) {
            "mem_percent" -> derivedSustainedConditionQuery(
                alert = alert,
                baseFilter = baseFilter,
                percentExpression = rollupMemoryPercentExpr(),
                metricFilter = memoryPercentMetricFilter(),
            )
            "disk_percent" -> diskSustainedConditionQuery(alert, baseFilter)
            else -> scalarSustainedConditionQuery(alert, baseFilter)
        }

    private fun diskSustainedConditionQuery(alert: AlertData, baseFilter: String): String? {
        val havingClause = conditionSql("pct", alert.condition, alert.threshold) ?: return null
        return """
            SELECT count(*) as cnt FROM (
                SELECT bucket_start, max(pct) as pct
                FROM (
                    SELECT bucket_start,
                        metric_identity,
                        ${rollupDiskPercentExpr()} as pct
                    FROM `$clickhouseDb`.metrics_rollup_1m
                    WHERE $baseFilter AND ${diskPercentMetricFilter()}
                    GROUP BY bucket_start, metric_identity
                    HAVING pct IS NOT NULL
                )
                GROUP BY bucket_start
                HAVING $havingClause
            )
            FORMAT JSONCompact
        """.trimIndent()
    }

    private fun derivedSustainedConditionQuery(
        alert: AlertData,
        baseFilter: String,
        percentExpression: String,
        metricFilter: String
    ): String? {
        val havingClause = conditionSql("pct", alert.condition, alert.threshold) ?: return null
        return """
            SELECT count(*) as cnt FROM (
                SELECT bucket_start,
                    $percentExpression as pct
                FROM `$clickhouseDb`.metrics_rollup_1m
                WHERE $baseFilter AND $metricFilter
                GROUP BY bucket_start
                HAVING $havingClause
            )
            FORMAT JSONCompact
        """.trimIndent()
    }

    private fun scalarSustainedConditionQuery(alert: AlertData, baseFilter: String): String? {
        val metricName = scalarAlertMetricName(alert.metric) ?: return null
        val conditionSql = conditionSql("value", alert.condition, alert.threshold) ?: return null
        return """
            SELECT count(*) as cnt
            FROM (
                SELECT bucket_start,
                    sum(value_sum) / nullIf(sum(value_count), 0) as value
                FROM `$clickhouseDb`.metrics_rollup_1m
                WHERE $baseFilter AND metric_name = '$metricName'
                GROUP BY bucket_start
                HAVING $conditionSql
            )
            FORMAT JSONCompact
        """.trimIndent()
    }

    private fun scalarAlertMetricName(metric: String): String? =
        when (metric) {
            "cpu_percent" -> "system.cpu.percent"
            "load_1" -> "system.load.1"
            "load_5" -> "system.load.5"
            "load_15" -> "system.load.15"
            "temp_max" -> "system.temp.max"
            "gpu_percent" -> "system.gpu.percent"
            "battery_percent" -> "system.battery.percent"
            else -> null
        }

    private fun conditionSql(fieldName: String, condition: String, threshold: Double): String? =
        when (condition) {
            ">" -> "$fieldName > $threshold"
            "<" -> "$fieldName < $threshold"
            ">=" -> "$fieldName >= $threshold"
            "<=" -> "$fieldName <= $threshold"
            "==" -> "$fieldName == $threshold"
            else -> null
        }

    private fun sustainedConditionCount(body: String): Long? {
        if (body.isBlank()) {
            return null
        }
        val json = Json { ignoreUnknownKeys = true }
        val result = json.parseToJsonElement(body).jsonObject
        val data = result["data"]?.jsonArray?.firstOrNull()?.jsonArray ?: return null
        return data[0].toString().replace("\"", "").toLongOrNull() ?: 0
    }

    private fun expectedSustainedDataPoints(durationSeconds: Int): Int {
        if (durationSeconds == 0) {
            return 0
        }
        return kotlin.math.ceil(durationSeconds.toDouble() / SECONDS_PER_MINUTE).toInt()
    }

    /**
     * Check if the alert condition has been sustained for the required duration.
     */
    private suspend fun checkSustainedCondition(alert: AlertData): Boolean {
        val baseFilter =
            "organization_id = ${alert.organizationId} AND host_id = ${alert.hostId} " +
                "AND bucket_start >= now64(3) - INTERVAL ${alert.durationSeconds} SECOND"
        val query = sustainedConditionQuery(alert, baseFilter) ?: return false

        return suspendRunCatching {
            val response = ClickHouseClient.execute(query)

            if (!response.status.isSuccess()) {
                logger.warn { "Failed to check sustained condition" }
                return false
            }

            val count = sustainedConditionCount(response.bodyAsText()) ?: return false
            val expectedDataPoints = expectedSustainedDataPoints(alert.durationSeconds)
            count >= expectedDataPoints * MIN_DATA_POINT_RATIO
        }.getOrElse { e ->
            logger.error(e) { "Error checking sustained condition" }
            false
        }
    }

    /**
     * Send alert notification via email.
     */
    private suspend fun sendAlertNotification(
        alert: AlertData,
        hostName: String,
        organizationId: Int,
        currentValue: Double
    ) {
        val metricLabel = getMetricLabel(alert.metric)
        val formattedValue = formatMetricValue(alert.metric, currentValue)
        val formattedThreshold = formatMetricValue(alert.metric, alert.threshold)
        val hostResourceId = hostResourceId(alert.hostId, organizationId)
        val dashboardUrl = hostDashboardUrl(alert.hostId, organizationId)
        val metadata =
            mutableMapOf(
                "host_name" to JsonPrimitive(hostName),
                "metric" to JsonPrimitive(alert.metric),
                "current_value" to JsonPrimitive(formattedValue),
                "threshold" to JsonPrimitive(formattedThreshold)
            )
        hostResourceId?.let { metadata["host_id"] = JsonPrimitive(it) }

        val alertPriority = hostAlertPriorityOverride(alert)
        val alertLifecycleEvent =
            AlertLifecycleEvent(
                title = "$hostName - $metricLabel ${alert.condition} ${alert.threshold}",
                description =
                "Metric: $metricLabel\nCondition: ${alert.condition} $formattedThreshold" +
                    "\nCurrent Value: $formattedValue",
                priority = alertPriority ?: AlertPriority.P1,
                status = AlertStatus.FIRING,
                source = AlertSource.HOST_ALERT,
                deduplicationKey = hostAlertDedupKey(alert),
                organizationId = organizationId,
                metadata = metadata,
                moneatUrl = dashboardUrl
            )

        val shouldNotifyIncidentProvider = suspendRunCatching {
            workflowService.publishAlertTriggered(alertLifecycleEvent)
        }.getOrElse { e ->
            logger.error(e) { "Failed to publish host alert workflow" }
            true
        }
        if (alertPriority != null && shouldNotifyIncidentProvider) {
            suspendRunCatching {
                incidentService.fireAlert(alertLifecycleEvent, publishWorkflow = false)
            }.getOrElse { e ->
                logger.error(e) { "Failed to fire host alert" }
            }
        }
    }

    private fun hostAlertPriorityOverride(alert: AlertData): AlertPriority? =
        if (alert.scope == MonitorService.ALERT_SCOPE_GLOBAL && alert.templateAlertId != null) {
            transaction {
                OrganizationAlertTemplates
                    .selectAll()
                    .where { OrganizationAlertTemplates.id eq alert.templateAlertId }
                    .firstOrNull()
                    ?.get(OrganizationAlertTemplates.alert_priority)
                    ?.let { AlertPriority.fromString(it) }
            }
        } else {
            transaction {
                HostAlerts
                    .selectAll()
                    .where { HostAlerts.id eq alert.id }
                    .firstOrNull()
                    ?.get(HostAlerts.alert_priority)
                    ?.let { AlertPriority.fromString(it) }
            }
        }

    /**
     * Check host statuses and send down/up notifications.
     */
    private suspend fun checkHostStatuses() {
        val now = Clock.System.now()
        val downThreshold = now - HOST_DOWN_THRESHOLD_SECONDS.seconds

        val hosts =
            transaction {
                Hosts.selectAll().map { row ->
                    HostStatusSnapshot(
                        hostId = row[Hosts.id],
                        hostName = row[Hosts.display_name] ?: row[Hosts.hostname],
                        organizationId = row[Hosts.organization_id],
                        currentStatus = row[Hosts.status],
                        lastSeenAt = row[Hosts.last_seen_at]
                    )
                }
            }

        for (host in hosts) {
            checkHostStatus(host, downThreshold)
        }
    }

    private suspend fun checkHostStatus(
        host: HostStatusSnapshot,
        downThreshold: Instant
    ) {
        if (host.currentStatus == "pending") return

        val newStatus = if (host.lastSeenAt < downThreshold) "down" else "up"
        if (host.currentStatus == newStatus) return

        logger.info {
            "Host ${host.hostId} (${host.hostName}) status changed: ${host.currentStatus} -> $newStatus"
        }

        if (host.currentStatus == "down" && newStatus == "up" &&
            !resolveHostDownIncident(host.hostId, host.hostName, host.organizationId)
        ) {
            return
        }

        transaction {
            Hosts.update({ Hosts.id eq host.hostId }) {
                it[status] = newStatus
            }
        }

        if (isAnySilenceActive(host.organizationId)) return

        sendHostStatusNotification(host, newStatus)
    }

    private suspend fun sendHostStatusNotification(
        host: HostStatusSnapshot,
        newStatus: String
    ) {
        if (newStatus == "down") {
            sendHostDownNotification(host.hostId, host.hostName, host.organizationId, host.lastSeenAt)
        }
    }

    /**
     * Send host down notification.
     */
    private suspend fun sendHostDownNotification(
        hostId: Int,
        hostName: String,
        organizationId: Int,
        lastSeenAt: Instant
    ) {
        val lastSeenText =
            run {
                val minutesAgo = ((Clock.System.now() - lastSeenAt).inWholeSeconds / SECONDS_PER_MINUTE).toInt()
                "Last seen $minutesAgo minutes ago"
            }

        suspendRunCatching {
            val dashboardUrl = hostDashboardUrl(hostId, organizationId)
            val metadata =
                mutableMapOf(
                    "host_name" to JsonPrimitive(hostName),
                    "last_seen" to JsonPrimitive(lastSeenText)
                )
            hostResourceId(hostId, organizationId)?.let { metadata["host_id"] = JsonPrimitive(it) }
            val alertLifecycleEvent =
                AlertLifecycleEvent(
                    title = "Host Down: $hostName",
                    description = "The monitoring agent has stopped reporting metrics.\nStatus: $lastSeenText",
                    priority = AlertPriority.P0,
                    status = AlertStatus.FIRING,
                    source = AlertSource.HOST_DOWN,
                    deduplicationKey = hostDownDedupKey(hostId),
                    organizationId = organizationId,
                    metadata = metadata,
                    moneatUrl = dashboardUrl
                )
            incidentService.fireAlert(alertLifecycleEvent)
        }.getOrElse { e ->
            logger.error(e) { "Failed to fire incident provider alert for host down" }
        }
    }

    private suspend fun resolveHostDownIncident(
        hostId: Int,
        hostName: String,
        organizationId: Int
    ): Boolean =
        suspendRunCatching {
            val hostUrl = hostDashboardUrl(hostId, organizationId)
            incidentService.autoResolveAlert(
                organizationId = organizationId,
                source = AlertSource.HOST_DOWN,
                deduplicationKey = hostDownDedupKey(hostId),
                title = "Host Recovered: $hostName",
                description = "$hostName is reporting metrics again.",
                moneatUrl = hostUrl
            )
            true
        }.getOrElse { e ->
            logger.error(e) { "Failed to resolve incident alert for host up" }
            false
        }

    private fun hostAlertRedisKey(alert: AlertData): String {
        return "alert_state:${alert.hostId}:${hostAlertIdPart(alert)}"
    }

    private fun hostAlertDedupKey(alert: AlertData): String {
        return "moneat-host-alert-${alert.hostId}-${hostAlertIdPart(alert)}"
    }

    private fun hostAlertIdPart(alert: AlertData): String {
        return if (alert.templateAlertId != null) {
            "tpl_${alert.templateAlertId}"
        } else {
            "id_${alert.id}"
        }
    }

    private fun hostDownDedupKey(hostId: Int): String {
        return "moneat-host-down-$hostId"
    }

    internal fun isThresholdTriggered(
        condition: String,
        currentValue: Double,
        threshold: Double
    ): Boolean {
        return when (condition) {
            ">" -> currentValue > threshold
            "<" -> currentValue < threshold
            ">=" -> currentValue >= threshold
            "<=" -> currentValue <= threshold
            "==" -> currentValue == threshold
            else -> false
        }
    }

    internal fun isThrottledByInterval(
        lastTriggeredAt: Instant?,
        now: Instant = Clock.System.now()
    ): Boolean {
        if (lastTriggeredAt == null) return false
        val timeSinceLastTrigger = now - lastTriggeredAt
        return timeSinceLastTrigger < MIN_ALERT_INTERVAL_MINUTES.minutes
    }

    private fun getMetricLabel(metric: String): String {
        return when (metric) {
            "cpu_percent" -> "CPU Usage"
            "mem_percent" -> "Memory Usage"
            "disk_percent" -> "Disk Usage"
            "load_1" -> "Load Average (1m)"
            "load_5" -> "Load Average (5m)"
            "load_15" -> "Load Average (15m)"
            "temp_max" -> "Max Temperature"
            "gpu_percent" -> "GPU Usage"
            "battery_percent" -> "Battery Level"
            else -> metric
        }
    }

    private fun formatMetricValue(
        metric: String,
        value: Double
    ): String {
        return when (metric) {
            "cpu_percent", "mem_percent", "disk_percent", "gpu_percent", "battery_percent" -> {
                String.format(Locale.US, "%.1f%%", value)
            }

            "temp_max" -> {
                String.format(Locale.US, "%.1f°C", value)
            }

            "load_1", "load_5", "load_15" -> {
                String.format(Locale.US, "%.2f", value)
            }

            else -> {
                value.toString()
            }
        }
    }

    // --- Silence Period Methods ---

    fun isAnySilenceActive(organizationId: Int): Boolean {
        val now = Clock.System.now()
        return transaction {
            AlertSilencePeriods
                .selectAll()
                .where {
                    (AlertSilencePeriods.organization_id eq organizationId) and
                        (AlertSilencePeriods.starts_at lessEq now) and
                        (AlertSilencePeriods.ends_at greaterEq now)
                }.count() > 0
        }
    }

    fun listSilencePeriods(organizationId: Int): List<SilencePeriodResponse> {
        return transaction {
            val rows = AlertSilencePeriods
                .selectAll()
                .where {
                    AlertSilencePeriods.organization_id eq organizationId
                }
                .toList()
            val orgResourceId = organizationResourceId(organizationId)
            val userResourceIds = userResourceIds(rows.map { row -> row[AlertSilencePeriods.created_by] })
            rows.map { row ->
                SilencePeriodResponse(
                    id = row[AlertSilencePeriods.resource_id].toString(),
                    organizationId = orgResourceId,
                    reason = row[AlertSilencePeriods.reason],
                    startsAt = row[AlertSilencePeriods.starts_at].toEpochMilliseconds(),
                    endsAt = row[AlertSilencePeriods.ends_at].toEpochMilliseconds(),
                    createdBy = userResourceIds.requireResourceId(row[AlertSilencePeriods.created_by], "user"),
                    createdAt = row[AlertSilencePeriods.created_at].toEpochMilliseconds()
                )
            }
        }
    }

    fun createSilencePeriod(
        organizationId: Int,
        userId: Int,
        request: CreateSilencePeriodRequest
    ): SilencePeriodResponse {
        val startsAt = Instant.fromEpochMilliseconds(request.startsAt)
        val endsAt = Instant.fromEpochMilliseconds(request.endsAt)
        val now = Clock.System.now()

        return transaction {
            val resourceId =
                AlertSilencePeriods.insert {
                    it[AlertSilencePeriods.organization_id] = organizationId
                    it[AlertSilencePeriods.reason] = request.reason
                    it[AlertSilencePeriods.starts_at] = startsAt
                    it[AlertSilencePeriods.ends_at] = endsAt
                    it[AlertSilencePeriods.created_by] = userId
                    it[AlertSilencePeriods.created_at] = now
                } get AlertSilencePeriods.resource_id

            SilencePeriodResponse(
                id = resourceId.toString(),
                organizationId = organizationResourceId(organizationId),
                reason = request.reason,
                startsAt = startsAt.toEpochMilliseconds(),
                endsAt = endsAt.toEpochMilliseconds(),
                createdBy = userResourceId(userId),
                createdAt = now.toEpochMilliseconds()
            )
        }
    }

    fun deleteSilencePeriod(
        id: String,
        organizationId: Int
    ): Boolean =
        id.toUuidOrNull()
            ?.let { resourceId -> deleteSilencePeriodByResourceId(resourceId, organizationId) }
            ?: false

    fun deleteSilencePeriodByResourceId(
        resourceId: Uuid,
        organizationId: Int
    ): Boolean {
        return transaction {
            AlertSilencePeriods.deleteWhere {
                (AlertSilencePeriods.resource_id eq resourceId) and
                    (AlertSilencePeriods.organization_id eq organizationId)
            } > 0
        }
    }

    private fun hostDashboardUrl(hostId: Int, organizationId: Int): String {
        val frontendUrl = config.property(EMAIL_FRONTEND_URL_CONFIG).getString()
        val hostResourceId = hostResourceId(hostId, organizationId)

        return if (hostResourceId == null) {
            "$frontendUrl/monitoring/hosts"
        } else {
            "$frontendUrl/monitoring/hosts/$hostResourceId"
        }
    }

    private fun hostResourceId(hostId: Int, organizationId: Int): String? =
        transaction {
            Hosts
                .selectAll()
                .where {
                    (Hosts.id eq hostId) and
                        (Hosts.organization_id eq organizationId)
                }
                .firstOrNull()
                ?.get(Hosts.resource_id)
                ?.toString()
        }

    private fun cleanupExpiredSilencePeriods() {
        val now = Clock.System.now()
        val deleted =
            transaction {
                AlertSilencePeriods.deleteWhere {
                    AlertSilencePeriods.ends_at lessEq now
                }
            }
        if (deleted > 0) {
            logger.info { "Cleaned up $deleted expired silence periods" }
        }
    }
}
