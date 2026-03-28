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
import com.moneat.incident.services.IncidentService
import com.moneat.monitor.models.AlertData
import com.moneat.monitor.models.CreateSilencePeriodRequest
import com.moneat.monitor.models.SilencePeriodResponse
import com.moneat.notifications.services.AlertNotificationPreferencesService
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.shared.models.AlertSilencePeriods
import com.moneat.shared.models.HostAlertSettings
import com.moneat.shared.models.HostAlertTemplateStates
import com.moneat.shared.models.HostAlerts
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.OrganizationAlertTemplates
import com.moneat.shared.services.TaskLock
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
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

class MonitorAlertService(
    private val emailService: EmailService = EmailService(),
    private val slackService: SlackService = SlackService(),
    private val discordService: DiscordService = DiscordService(),
    private val incidentService: IncidentService = IncidentService(),
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
    }

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
        val idPart = if (alert.templateAlertId != null) "tpl_${alert.templateAlertId}" else "id_${alert.id}"
        val alertKey = "alert_state:${alert.hostId}:$idPart"

        // Get recent metrics for the host
        val currentValue = getCurrentMetricValue(alert.hostId, alert.organizationId, alert.metric) ?: return

        // Check if alert condition is met
        val triggered = isThresholdTriggered(alert.condition, currentValue, alert.threshold)

        // Handle Recovery
        if (!triggered) {
            // Check if it was previously triggered
            val wasTriggered =
                suspendRunCatching {
                    if (RedisConfig.isConnected()) {
                        RedisConfig.sync().get(alertKey) == "TRIGGERED"
                    } else {
                        false // Fallback if Redis is down
                    }
                }.getOrElse { _ ->
                    false
                }

            if (wasTriggered) {
                // Clear state
                suspendRunCatching {
                    if (RedisConfig.isConnected()) {
                        RedisConfig.sync().del(alertKey)
                    }
                }.getOrElse { e ->
                    logger.error(e) { "Failed to clear alert state in Redis" }
                }

                // Send recovery notification
                sendRecoveryNotification(alert, hostName, organizationId)
                // Resolve incident for metric alerts (same dedup key used when firing)
                val dedupKey =
                    "moneat-host-alert-${alert.hostId}-$idPart"
                suspendRunCatching {
                    incidentService.resolveAlert(
                        organizationId = organizationId,
                        source = com.moneat.incident.models.AlertSource.HOST_ALERT,
                        deduplicationKey = dedupKey
                    )
                }.getOrElse { e ->
                    logger.error(e) { "Failed to resolve incident for recovered alert ${alert.id}" }
                }
                logger.info { "Alert ${alert.id} recovered for host ${alert.hostId}" }
            }
            return
        }

        // If triggered, check duration if specified
        if (alert.durationSeconds > 0) {
            val isSustained = checkSustainedCondition(alert)
            if (!isSustained) {
                return // Condition not sustained for required duration
            }
        }

        // Check throttling
        val now = Clock.System.now()
        if (isThrottledByInterval(alert.lastTriggeredAt, now)) {
            // Update Redis state even if throttled to ensure consistency
            suspendRunCatching {
                if (RedisConfig.isConnected()) {
                    RedisConfig.sync().set(alertKey, "TRIGGERED")
                }
            }.getOrElse { e ->
                logger.debug(e) { "Failed to update throttled alert state in Redis" }
            }
            return // Don't spam alerts
        }

        // Check if alerts are silenced for this organization
        if (isAnySilenceActive(organizationId)) {
            return
        }

        // Trigger the alert
        logger.info {
            "Alert ${alert.id} triggered for host ${alert.hostId}: " +
                "${alert.metric} ${alert.condition} ${alert.threshold} (current: $currentValue)"
        }

        // Update Redis state
        suspendRunCatching {
            if (RedisConfig.isConnected()) {
                RedisConfig.sync().set(alertKey, "TRIGGERED")
            }
        }.getOrElse { e ->
            logger.error(e) { "Failed to set alert state in Redis" }
        }

        // Update last triggered timestamp
        if (alert.scope == MonitorService.ALERT_SCOPE_GLOBAL && alert.templateAlertId != null) {
            transaction {
                val existing =
                    HostAlertTemplateStates
                        .selectAll()
                        .where {
                            (HostAlertTemplateStates.template_alert_id eq alert.templateAlertId) and
                                (HostAlertTemplateStates.host_id eq alert.hostId)
                        }.firstOrNull()

                if (existing != null) {
                    HostAlertTemplateStates.update({
                        (HostAlertTemplateStates.template_alert_id eq alert.templateAlertId) and
                            (HostAlertTemplateStates.host_id eq alert.hostId)
                    }) {
                        it[last_triggered_at] = now
                    }
                } else {
                    HostAlertTemplateStates.insert {
                        it[HostAlertTemplateStates.template_alert_id] = alert.templateAlertId
                        it[HostAlertTemplateStates.host_id] = alert.hostId
                        it[HostAlertTemplateStates.last_triggered_at] = now
                    }
                }
            }
        } else {
            transaction {
                HostAlerts.update({ HostAlerts.id eq alert.id }) {
                    it[last_triggered_at] = now
                }
            }
        }

        // Send notification
        sendAlertNotification(alert, hostName, organizationId, currentValue)
    }

    /**
     * Get the current value of a metric for a host from metrics table.
     */
    private suspend fun getCurrentMetricValue(
        hostId: Int,
        organizationId: Int,
        metric: String
    ): Double? {
        val (selectExpr, metricFilter) =
            when (metric) {
                "cpu_percent" -> "argMax(value, timestamp)" to "metric_name = 'system.cpu.percent'"
                "mem_percent" ->
                    "(1 - argMax(CASE WHEN metric_name='system.mem.available' THEN value END, timestamp) / " +
                        "nullIf(argMax(CASE WHEN metric_name='system.mem.total' THEN value END, timestamp), 0)) * 100" to
                        "metric_name IN ('system.mem.available','system.mem.total')"
                "disk_percent" ->
                    "argMax(CASE WHEN metric_name='system.disk.used' THEN value END, timestamp) / " +
                        "nullIf(argMax(CASE WHEN metric_name='system.disk.total' THEN value END, timestamp), 0) * 100" to
                        "metric_name IN ('system.disk.used','system.disk.total')"
                "load_1" -> "argMax(value, timestamp)" to "metric_name = 'system.load.1'"
                "load_5" -> "argMax(value, timestamp)" to "metric_name = 'system.load.5'"
                "load_15" -> "argMax(value, timestamp)" to "metric_name = 'system.load.15'"
                "temp_max" -> "argMax(value, timestamp)" to "metric_name = 'system.temp.max'"
                "gpu_percent" -> "argMax(value, timestamp)" to "metric_name = 'system.gpu.percent'"
                "battery_percent" -> "argMax(value, timestamp)" to "metric_name = 'system.battery.percent'"
                else -> return null
            }

        val query =
            """
            SELECT $selectExpr as value
            FROM `$clickhouseDb`.metrics
            WHERE organization_id = $organizationId
              AND tags['host_id'] = '$hostId'
              AND $metricFilter
            FORMAT JSONCompact
            """.trimIndent()

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

    /**
     * Check if the alert condition has been sustained for the required duration.
     */
    private suspend fun checkSustainedCondition(alert: AlertData): Boolean {
        val baseFilter =
            "organization_id = ${alert.organizationId} AND tags['host_id'] = '${alert.hostId}' " +
                "AND timestamp >= now64(3) - INTERVAL ${alert.durationSeconds} SECOND"

        val (query, usesDerived) =
            when (alert.metric) {
                "mem_percent", "disk_percent" -> {
                    val availName = if (alert.metric == "mem_percent") "system.mem.available" else null
                    val usedName = if (alert.metric == "mem_percent") "system.mem.used" else "system.disk.used"
                    val totalName = if (alert.metric == "mem_percent") "system.mem.total" else "system.disk.total"
                    val havingClause =
                        when (alert.condition) {
                            ">" -> "pct > ${alert.threshold}"
                            "<" -> "pct < ${alert.threshold}"
                            ">=" -> "pct >= ${alert.threshold}"
                            "<=" -> "pct <= ${alert.threshold}"
                            "==" -> "pct == ${alert.threshold}"
                            else -> return false
                        }
                    val pctExpr = if (availName != null) {
                        "(1 - max(CASE WHEN metric_name='$availName' THEN value END) / " +
                            "nullIf(max(CASE WHEN metric_name='$totalName' THEN value END), 0)) * 100"
                    } else {
                        "max(CASE WHEN metric_name='$usedName' THEN value END) / " +
                            "nullIf(max(CASE WHEN metric_name='$totalName' THEN value END), 0) * 100"
                    }
                    val metricFilter = if (availName != null) {
                        "metric_name IN ('$availName','$totalName')"
                    } else {
                        "metric_name IN ('$usedName','$totalName')"
                    }
                    val q =
                        """
                        SELECT count(*) as cnt FROM (
                            SELECT timestamp,
                                $pctExpr as pct
                            FROM `$clickhouseDb`.metrics
                            WHERE $baseFilter AND $metricFilter
                            GROUP BY timestamp
                            HAVING $havingClause
                        )
                        FORMAT JSONCompact
                        """.trimIndent()
                    q to true
                }
                else -> {
                    val metricName =
                        when (alert.metric) {
                            "cpu_percent" -> "system.cpu.percent"
                            "load_1" -> "system.load.1"
                            "load_5" -> "system.load.5"
                            "load_15" -> "system.load.15"
                            "temp_max" -> "system.temp.max"
                            "gpu_percent" -> "system.gpu.percent"
                            "battery_percent" -> "system.battery.percent"
                            else -> return false
                        }
                    val conditionSql =
                        when (alert.condition) {
                            ">" -> "value > ${alert.threshold}"
                            "<" -> "value < ${alert.threshold}"
                            ">=" -> "value >= ${alert.threshold}"
                            "<=" -> "value <= ${alert.threshold}"
                            "==" -> "value == ${alert.threshold}"
                            else -> return false
                        }
                    val q =
                        """
                        SELECT count(*) as cnt
                        FROM `$clickhouseDb`.metrics
                        WHERE $baseFilter AND metric_name = '$metricName' AND $conditionSql
                        FORMAT JSONCompact
                        """.trimIndent()
                    q to false
                }
            }

        return suspendRunCatching {
            val response = ClickHouseClient.execute(query)

            if (!response.status.isSuccess()) {
                logger.warn { "Failed to check sustained condition" }
                return false
            }

            val body = response.bodyAsText()
            if (body.isBlank()) return false

            val json = Json { ignoreUnknownKeys = true }
            val result = json.parseToJsonElement(body).jsonObject
            val data = result["data"]?.jsonArray?.firstOrNull()?.jsonArray ?: return false

            val count = data[0].toString().replace("\"", "").toLongOrNull() ?: 0

            // Check if we have enough data points
            val expectedDataPoints = if (alert.durationSeconds == 0) {
                0
            } else {
                kotlin.math.ceil(alert.durationSeconds.toDouble() / POLL_INTERVAL_SECONDS).toInt()
            }
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
        val prefsService = AlertNotificationPreferencesService()

        // Get users with email enabled for HOST_ALERT
        val emailRecipients =
            prefsService.getUsersWithChannelEnabled(
                organizationId = organizationId,
                alertSource = "HOST_ALERT",
                channel = "email"
            )

        val metricLabel = getMetricLabel(alert.metric)
        val conditionText = getConditionText(alert.condition)
        val subject = "⚠️ Alert: $hostName - $metricLabel $conditionText ${alert.threshold}"

        val formattedValue = formatMetricValue(alert.metric, currentValue)
        val formattedThreshold = formatMetricValue(alert.metric, alert.threshold)
        val dashboardUrl = "${config.property("email.frontendUrl").getString()}/monitoring/hosts/${alert.hostId}"

        // Send email notifications
        for ((_, email) in emailRecipients) {
            suspendRunCatching {
                val htmlBody =
                    loadHostAlertTemplate(
                        hostName = hostName,
                        metric = metricLabel,
                        condition = conditionText,
                        value = formattedValue,
                        threshold = formattedThreshold,
                        dashboardUrl = dashboardUrl
                    )

                val textBody =
                    """
                    ⚠️ Host Alert
                    
                    Heads up, something needs attention.
                    
                    We noticed that $metricLabel on $hostName has $conditionText the threshold of $formattedThreshold.
                    
                    Current Value: $formattedValue
                    ${if (alert.durationSeconds > 0) "Duration setting: ${alert.durationSeconds}s" else ""}
                    
                    Check Host Health: $dashboardUrl
                    
                    ---
                    Moneat Server Monitoring
                    """.trimIndent()

                emailService.sendEmail(email, subject, htmlBody, textBody, "monitor_alert")
            }.getOrElse { e ->
                logger.error(e) { "Failed to send alert notification to $email" }
            }
        }

        // Check if Slack is enabled for any user in the org
        val slackEnabled =
            prefsService
                .getUsersWithChannelEnabled(
                    organizationId = organizationId,
                    alertSource = "HOST_ALERT",
                    channel = "slack"
                ).isNotEmpty()

        if (slackEnabled) {
            suspendRunCatching {
                val baseUrl = config.property("email.frontendUrl").getString()
                slackService.sendHostAlert(
                    organizationId = organizationId,
                    hostName = hostName,
                    metric = metricLabel,
                    condition = alert.condition,
                    threshold = formattedThreshold,
                    currentValue = formattedValue,
                    hostId = alert.hostId,
                    baseUrl = baseUrl
                )
            }.getOrElse { e ->
                logger.error(e) { "Failed to send Slack notification for host alert" }
            }
        }

        // Check if Discord is enabled for any user in the org
        val discordEnabled =
            prefsService
                .getUsersWithChannelEnabled(
                    organizationId = organizationId,
                    alertSource = "HOST_ALERT",
                    channel = "discord"
                ).isNotEmpty()

        if (discordEnabled) {
            suspendRunCatching {
                val baseUrl = config.property("email.frontendUrl").getString()
                discordService.sendHostAlert(
                    organizationId = organizationId,
                    hostName = hostName,
                    metric = metricLabel,
                    condition = alert.condition,
                    threshold = formattedThreshold,
                    currentValue = formattedValue,
                    hostId = alert.hostId,
                    baseUrl = baseUrl
                )
            }.getOrElse { e ->
                logger.error(e) { "Failed to send Discord notification for host alert" }
            }
        }

        // Fire incident alert
        suspendRunCatching {
            val incidentSeverity =
                if (alert.scope == MonitorService.ALERT_SCOPE_GLOBAL && alert.templateAlertId != null) {
                    transaction {
                        OrganizationAlertTemplates
                            .selectAll()
                            .where { OrganizationAlertTemplates.id eq alert.templateAlertId }
                            .firstOrNull()
                            ?.get(OrganizationAlertTemplates.incident_severity)
                            ?.let {
                                com.moneat.incident.models.IncidentSeverity.fromString(it)
                            }
                    }
                } else {
                    transaction {
                        HostAlerts
                            .selectAll()
                            .where { HostAlerts.id eq alert.id }
                            .firstOrNull()
                            ?.get(HostAlerts.incident_severity)
                            ?.let {
                                com.moneat.incident.models.IncidentSeverity.fromString(it)
                            }
                    }
                }

            if (incidentSeverity != null) {
                val frontendUrl = config.property("email.frontendUrl").getString()
                val incidentEvent =
                    com.moneat.incident.models.IncidentEvent(
                        title = "$hostName - $metricLabel ${alert.condition} ${alert.threshold}",
                        description =
                        "Metric: $metricLabel\nCondition: ${alert.condition} $formattedThreshold" +
                            "\nCurrent Value: $formattedValue",
                        severity = incidentSeverity,
                        status = com.moneat.incident.models.IncidentStatus.FIRING,
                        source = com.moneat.incident.models.AlertSource.HOST_ALERT,
                        deduplicationKey = run {
                            val idPart = if (alert.templateAlertId != null) {
                                "tpl_${alert.templateAlertId}"
                            } else {
                                "id_${alert.id}"
                            }
                            "moneat-host-alert-${alert.hostId}-$idPart"
                        },
                        organizationId = organizationId,
                        metadata =
                        mapOf(
                            "host_id" to JsonPrimitive(alert.hostId.toString()),
                            "host_name" to JsonPrimitive(hostName),
                            "metric" to JsonPrimitive(alert.metric),
                            "current_value" to JsonPrimitive(formattedValue),
                            "threshold" to JsonPrimitive(formattedThreshold)
                        ),
                        moneatUrl = "$frontendUrl/monitoring/hosts/${alert.hostId}"
                    )
                incidentService.fireAlert(incidentEvent)
            }
        }.getOrElse { e ->
            logger.error(e) { "Failed to fire incident alert" }
        }
    }

    /**
     * Check host statuses and send down/up notifications.
     */
    private suspend fun checkHostStatuses() {
        val now = Clock.System.now()
        val downThreshold = now - HOST_DOWN_THRESHOLD_SECONDS.seconds

        // Get all hosts and check their last_seen_at
        val hosts =
            transaction {
                Hosts.selectAll().map { row ->
                    Triple(
                        row[Hosts.id],
                        row[Hosts.display_name] ?: row[Hosts.hostname],
                        row[Hosts.organization_id]
                    ) to
                        Pair(
                            row[Hosts.status],
                            row[Hosts.last_seen_at]
                        )
                }
            }

        for ((hostInfo, statusInfo) in hosts) {
            val (hostId, hostName, organizationId) = hostInfo
            val (currentStatus, lastSeenAt) = statusInfo

            val isDown = lastSeenAt < downThreshold

            // Skip pending hosts that have never reported
            if (currentStatus == "pending") {
                continue
            }

            val newStatus = if (isDown) "down" else "up"

            // Only send notification if status changed
            if (currentStatus != newStatus) {
                logger.info { "Host $hostId ($hostName) status changed: $currentStatus -> $newStatus" }

                // Update status in database (last_seen_at is only updated by heartbeat/metrics code)
                transaction {
                    Hosts.update({ Hosts.id eq hostId }) {
                        it[status] = newStatus
                    }
                }

                // Skip notifications if alerts are silenced for this organization
                if (isAnySilenceActive(organizationId)) {
                    continue
                }

                // Send notification
                if (newStatus == "down") {
                    sendHostDownNotification(hostId, hostName, organizationId, lastSeenAt)
                } else {
                    sendHostUpNotification(hostId, hostName, organizationId)
                }
            }
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
        val prefsService = AlertNotificationPreferencesService()

        // Get users with email enabled for HOST_DOWN
        val emailRecipients =
            prefsService.getUsersWithChannelEnabled(
                organizationId = organizationId,
                alertSource = "HOST_DOWN",
                channel = "email"
            )

        val lastSeenText =
            run {
                val minutesAgo = ((Clock.System.now() - lastSeenAt).inWholeSeconds / 60).toInt()
                "Last seen $minutesAgo minutes ago"
            }

        val hostUrl = "${config.property("email.frontendUrl").getString()}/monitoring/hosts/$hostId"

        // Send email notifications
        for ((_, email) in emailRecipients) {
            suspendRunCatching {
                emailService.sendHostDownEmail(email, hostName, lastSeenText, hostUrl)
            }.getOrElse { e ->
                logger.error(e) { "Failed to send host down notification to $email" }
            }
        }

        // Check if Slack is enabled for any user in the org
        val slackEnabled =
            prefsService
                .getUsersWithChannelEnabled(
                    organizationId = organizationId,
                    alertSource = "HOST_DOWN",
                    channel = "slack"
                ).isNotEmpty()

        if (slackEnabled) {
            suspendRunCatching {
                val baseUrl = config.property("email.frontendUrl").getString()
                slackService.sendHostDown(
                    organizationId = organizationId,
                    hostName = hostName,
                    lastSeen = lastSeenText,
                    hostId = hostId,
                    baseUrl = baseUrl
                )
            }.getOrElse { e ->
                logger.error(e) { "Failed to send Slack notification for host down" }
            }
        }

        // Check if Discord is enabled for any user in the org
        val discordEnabled =
            prefsService
                .getUsersWithChannelEnabled(
                    organizationId = organizationId,
                    alertSource = "HOST_DOWN",
                    channel = "discord"
                ).isNotEmpty()

        if (discordEnabled) {
            suspendRunCatching {
                val baseUrl = config.property("email.frontendUrl").getString()
                discordService.sendHostDown(
                    organizationId = organizationId,
                    hostName = hostName,
                    lastSeen = lastSeenText,
                    hostId = hostId,
                    baseUrl = baseUrl
                )
            }.getOrElse { e ->
                logger.error(e) { "Failed to send Discord notification for host down" }
            }
        }

        // Fire incident alert for host down
        suspendRunCatching {
            val frontendUrl = config.property("email.frontendUrl").getString()
            val incidentEvent =
                com.moneat.incident.models.IncidentEvent(
                    title = "Host Down: $hostName",
                    description = "The monitoring agent has stopped reporting metrics.\nStatus: $lastSeenText",
                    severity = com.moneat.incident.models.IncidentSeverity.CRITICAL,
                    status = com.moneat.incident.models.IncidentStatus.FIRING,
                    source = com.moneat.incident.models.AlertSource.HOST_DOWN,
                    deduplicationKey = "moneat-host-down-$hostId",
                    organizationId = organizationId,
                    metadata =
                    mapOf(
                        "host_id" to JsonPrimitive(hostId.toString()),
                        "host_name" to JsonPrimitive(hostName),
                        "last_seen" to JsonPrimitive(lastSeenText)
                    ),
                    moneatUrl = "$frontendUrl/monitoring/hosts/$hostId"
                )
            incidentService.fireAlert(incidentEvent)
        }.getOrElse { e ->
            logger.error(e) { "Failed to fire incident alert for host down" }
        }
    }

    /**
     * Send host up notification.
     */
    private suspend fun sendHostUpNotification(
        hostId: Int,
        hostName: String,
        organizationId: Int
    ) {
        val prefsService = AlertNotificationPreferencesService()

        val hostUrl = "${config.property("email.frontendUrl").getString()}/monitoring/hosts/$hostId"

        // Get users with email enabled for HOST_DOWN (recovery uses the same source)
        val emailRecipients =
            prefsService.getUsersWithChannelEnabled(
                organizationId = organizationId,
                alertSource = "HOST_DOWN",
                channel = "email"
            )

        for ((_, email) in emailRecipients) {
            suspendRunCatching {
                emailService.sendHostUpEmail(email, hostName, hostUrl)
            }.getOrElse { e ->
                logger.error(e) { "Failed to send host up notification to $email" }
            }
        }

        // Check if Slack is enabled for any user in the org
        val slackEnabled =
            prefsService
                .getUsersWithChannelEnabled(
                    organizationId = organizationId,
                    alertSource = "HOST_DOWN",
                    channel = "slack"
                ).isNotEmpty()

        if (slackEnabled) {
            suspendRunCatching {
                val baseUrl = config.property("email.frontendUrl").getString()
                slackService.sendHostUp(
                    organizationId = organizationId,
                    hostName = hostName,
                    hostId = hostId,
                    baseUrl = baseUrl
                )
            }.getOrElse { e ->
                logger.error(e) { "Failed to send Slack notification for host up" }
            }
        }

        // Check if Discord is enabled for any user in the org
        val discordEnabled =
            prefsService
                .getUsersWithChannelEnabled(
                    organizationId = organizationId,
                    alertSource = "HOST_DOWN",
                    channel = "discord"
                ).isNotEmpty()

        if (discordEnabled) {
            suspendRunCatching {
                val baseUrl = config.property("email.frontendUrl").getString()
                discordService.sendHostUp(
                    organizationId = organizationId,
                    hostName = hostName,
                    hostId = hostId,
                    baseUrl = baseUrl
                )
            }.getOrElse { e ->
                logger.error(e) { "Failed to send Discord notification for host up" }
            }
        }

        // Resolve incident alert for host up
        suspendRunCatching {
            incidentService.resolveAlert(
                organizationId = organizationId,
                source = com.moneat.incident.models.AlertSource.HOST_DOWN,
                deduplicationKey = "moneat-host-down-$hostId"
            )
        }.getOrElse { e ->
            logger.error(e) { "Failed to resolve incident alert for host up" }
        }
    }

    private fun getConditionText(condition: String): String {
        return when (condition) {
            ">" -> "exceeded"
            "<" -> "dropped below"
            ">=" -> "reached or exceeded"
            "<=" -> "reached or dropped below"
            "==" -> "is exactly"
            else -> condition
        }
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

    private fun loadHostAlertTemplate(
        hostName: String,
        metric: String,
        condition: String,
        value: String,
        threshold: String,
        dashboardUrl: String
    ): String {
        val templateResource = this::class.java.classLoader.getResourceAsStream("email-templates/host-alert-v1.html")

        val safeHostName = hostName.escapeHtml()
        return if (templateResource != null) {
            templateResource
                .bufferedReader()
                .use { it.readText() }
                .replace("{{ hostName }}", safeHostName)
                .replace("{{ metric }}", metric)
                .replace("{{ condition }}", condition)
                .replace("{{ value }}", value)
                .replace("{{ threshold }}", threshold)
                .replace("{{ dashboardUrl }}", dashboardUrl)
        } else {
            // Fallback inline HTML
            """
            <div style="padding: 20px; background: #fff1f2; border: 1px solid #fecaca; border-radius: 8px;">
                <h2 style="color: #991b1b;">Host Alert</h2>
                <p><strong>$safeHostName</strong> reported <strong>$metric</strong> at <strong>$value</strong>.</p>
                <p>Threshold: $condition $threshold</p>
                <a href="$dashboardUrl">View Dashboard</a>
            </div>
            """.trimIndent()
        }
    }

    private fun loadHostRecoveredTemplate(
        hostName: String,
        metric: String,
        duration: String,
        dashboardUrl: String
    ): String {
        val templateResource = this::class.java.classLoader.getResourceAsStream("email-templates/host-recovered.html")

        val safeHostName = hostName.escapeHtml()
        return if (templateResource != null) {
            templateResource
                .bufferedReader()
                .use { it.readText() }
                .replace("{{ hostName }}", safeHostName)
                .replace("{{ metric }}", metric)
                .replace("{{ duration }}", duration)
                .replace("{{ dashboardUrl }}", dashboardUrl)
        } else {
            // Fallback inline HTML
            """
            <div style="padding: 20px; background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 8px;">
                <h2 style="color: #166534;">Host Recovered</h2>
                <p><strong>$safeHostName</strong> is back to normal.</p>
                <p>Metric: $metric</p>
                <a href="$dashboardUrl">View Dashboard</a>
            </div>
            """.trimIndent()
        }
    }

    private suspend fun sendRecoveryNotification(
        alert: AlertData,
        hostName: String,
        organizationId: Int
    ) {
        val prefsService = AlertNotificationPreferencesService()

        // Get users with email enabled for HOST_ALERT
        val emailRecipients =
            prefsService.getUsersWithChannelEnabled(
                organizationId = organizationId,
                alertSource = "HOST_ALERT",
                channel = "email"
            )

        val metricLabel = getMetricLabel(alert.metric)
        val subject = "✅ Recovered: $hostName - $metricLabel"
        val dashboardUrl = "${config.property("email.frontendUrl").getString()}/monitoring/hosts/${alert.hostId}"
        val durationText = if (alert.durationSeconds > 0) "${alert.durationSeconds}s setting" else "N/A"

        for ((_, email) in emailRecipients) {
            suspendRunCatching {
                val htmlBody =
                    loadHostRecoveredTemplate(
                        hostName = hostName,
                        metric = metricLabel,
                        duration = durationText,
                        dashboardUrl = dashboardUrl
                    )

                val textBody =
                    """
                    ✅ Issue Resolved
                    
                    $hostName has recovered.
                    
                    The alert for $metricLabel is no longer active. The metric has returned to normal levels.
                    
                    View Dashboard: $dashboardUrl
                    """.trimIndent()

                emailService.sendEmail(email, subject, htmlBody, textBody, "monitor_recovery")
            }.getOrElse { e ->
                logger.error(e) { "Failed to send recovery notification to $email" }
            }
        }
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
            AlertSilencePeriods
                .selectAll()
                .where {
                    AlertSilencePeriods.organization_id eq organizationId
                }.map { row ->
                    SilencePeriodResponse(
                        id = row[AlertSilencePeriods.id],
                        organizationId = row[AlertSilencePeriods.organization_id],
                        reason = row[AlertSilencePeriods.reason],
                        startsAt = row[AlertSilencePeriods.starts_at].toEpochMilliseconds(),
                        endsAt = row[AlertSilencePeriods.ends_at].toEpochMilliseconds(),
                        createdBy = row[AlertSilencePeriods.created_by],
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
            val id =
                AlertSilencePeriods.insert {
                    it[AlertSilencePeriods.organization_id] = organizationId
                    it[AlertSilencePeriods.reason] = request.reason
                    it[AlertSilencePeriods.starts_at] = startsAt
                    it[AlertSilencePeriods.ends_at] = endsAt
                    it[AlertSilencePeriods.created_by] = userId
                    it[AlertSilencePeriods.created_at] = now
                } get AlertSilencePeriods.id

            SilencePeriodResponse(
                id = id,
                organizationId = organizationId,
                reason = request.reason,
                startsAt = startsAt.toEpochMilliseconds(),
                endsAt = endsAt.toEpochMilliseconds(),
                createdBy = userId,
                createdAt = now.toEpochMilliseconds()
            )
        }
    }

    fun deleteSilencePeriod(
        id: Int,
        organizationId: Int
    ): Boolean {
        return transaction {
            AlertSilencePeriods.deleteWhere {
                (AlertSilencePeriods.id eq id) and
                    (AlertSilencePeriods.organization_id eq organizationId)
            } > 0
        }
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
