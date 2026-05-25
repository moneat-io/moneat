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

package com.moneat.dashboards.services

import com.moneat.config.RedisConfig
import com.moneat.dashboards.models.CreateDashboardAlertRequest
import com.moneat.dashboards.models.CustomDataSourceResponse
import com.moneat.dashboards.models.CustomDataSourceType
import com.moneat.dashboards.models.DashboardAlertResponse
import com.moneat.dashboards.models.DashboardWidgetAlerts
import com.moneat.dashboards.models.DashboardWidgets
import com.moneat.dashboards.models.Dashboards
import com.moneat.dashboards.models.NotificationChannels
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.UpdateDashboardAlertRequest
import com.moneat.utils.suspendRunCatching
import com.moneat.incident.models.AlertSource
import com.moneat.incident.models.IncidentEvent
import com.moneat.incident.models.IncidentSeverity
import com.moneat.incident.models.IncidentStatus
import com.moneat.incident.services.IncidentService
import com.moneat.notifications.services.AlertNotificationPreferencesService
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.shared.services.TaskLock
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.firstOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

class DashboardAlertService(
    private val emailService: EmailService = EmailService(),
    private val slackService: SlackService = SlackService(),
    private val discordService: DiscordService = DiscordService(),
    private val incidentService: IncidentService = IncidentService(),
    private val prefsService: AlertNotificationPreferencesService = AlertNotificationPreferencesService(),
    private val queryEngine: DashboardQueryEngine = DashboardQueryEngine(),
    private val retentionPolicyService: RetentionPolicyService = RetentionPolicyService(),
    private val dataSourceService: CustomDataSourceService = CustomDataSourceService(),
    private val dataSourceExecutor: CustomDataSourceExecutor = CustomDataSourceExecutor(),
) {
    private val config = ApplicationConfig("application.conf")
    private val json = Json { ignoreUnknownKeys = true }
    private val pendingSinceFallback = ConcurrentHashMap<Long, Instant>()

    private var evaluationJob: Job? = null

    companion object {
        const val EVALUATION_INTERVAL_SECONDS = 60
        const val MIN_ALERT_INTERVAL_MINUTES = 15
        private const val DEFAULT_RETENTION_DAYS = 90
        private const val MILLIS_PER_SECOND = 1000
    }

    fun start(scope: CoroutineScope) {
        logger.info { "Starting DashboardAlertService background job" }
        evaluationJob = scope.launch {
            while (isActive) {
                TaskLock.tryWithLock(
                    "dashboard-alert-evaluation",
                    lockAtMostFor = 5.minutes,
                    lockAtLeastFor = (EVALUATION_INTERVAL_SECONDS - 5).seconds
                ) {
                    evaluateAlerts()
                }
                delay(EVALUATION_INTERVAL_SECONDS.seconds)
            }
        }
    }

    fun stop() {
        logger.info { "Stopping DashboardAlertService background job" }
        evaluationJob?.cancel()
    }

    // ---- CRUD ----

    fun createAlert(
        dashboardId: Long,
        orgId: Long,
        createdBy: Long,
        request: CreateDashboardAlertRequest
    ): DashboardAlertResponse {
        validateCondition(request.condition)
        val now = Clock.System.now()
        val channelsJson = json.encodeToString(NotificationChannels.serializer(), request.notificationChannels)

        return transaction {
            DashboardWidgets.selectAll().where {
                (DashboardWidgets.id eq request.widgetId) and (DashboardWidgets.dashboardId eq dashboardId)
            }.firstOrNull() ?: throw IllegalArgumentException("Widget not found in this dashboard")

            val id = DashboardWidgetAlerts.insert {
                it[DashboardWidgetAlerts.widgetId] = request.widgetId
                it[DashboardWidgetAlerts.dashboardId] = dashboardId
                it[DashboardWidgetAlerts.orgId] = orgId
                it[DashboardWidgetAlerts.name] = request.name
                it[DashboardWidgetAlerts.condition] = request.condition
                it[DashboardWidgetAlerts.threshold] = request.threshold
                it[DashboardWidgetAlerts.metricIndex] = request.metricIndex
                it[DashboardWidgetAlerts.durationSeconds] = request.durationSeconds
                it[DashboardWidgetAlerts.incidentSeverity] = request.incidentSeverity
                it[DashboardWidgetAlerts.enabled] = request.enabled
                it[DashboardWidgetAlerts.notificationChannels] = channelsJson
                it[DashboardWidgetAlerts.createdBy] = createdBy
                it[DashboardWidgetAlerts.createdAt] = now
                it[DashboardWidgetAlerts.updatedAt] = now
            } get DashboardWidgetAlerts.id

            toResponse(
                DashboardWidgetAlerts.selectAll().where {
                    DashboardWidgetAlerts.id eq id
                }.first()
            )
        }
    }

    fun listAlerts(dashboardId: Long, orgId: Long): List<DashboardAlertResponse> {
        return transaction {
            DashboardWidgetAlerts.selectAll().where {
                (DashboardWidgetAlerts.dashboardId eq dashboardId) and
                    (DashboardWidgetAlerts.orgId eq orgId)
            }.orderBy(DashboardWidgetAlerts.createdAt, SortOrder.DESC).map { toResponse(it) }
        }
    }

    fun updateAlert(
        alertId: Long,
        dashboardId: Long,
        orgId: Long,
        request: UpdateDashboardAlertRequest
    ): DashboardAlertResponse? {
        request.condition?.let { validateCondition(it) }
        val now = Clock.System.now()

        return transaction {
            DashboardWidgetAlerts.selectAll().where {
                (DashboardWidgetAlerts.id eq alertId) and
                    (DashboardWidgetAlerts.dashboardId eq dashboardId) and
                    (DashboardWidgetAlerts.orgId eq orgId)
            }.firstOrNull() ?: return@transaction null

            DashboardWidgetAlerts.update({
                (DashboardWidgetAlerts.id eq alertId) and (DashboardWidgetAlerts.orgId eq orgId)
            }) {
                request.name?.let { v -> it[name] = v }
                request.condition?.let { v -> it[condition] = v }
                request.threshold?.let { v -> it[threshold] = v }
                request.metricIndex?.let { v -> it[metricIndex] = v }
                request.durationSeconds?.let { v -> it[durationSeconds] = v }
                request.incidentSeverity?.let { v -> it[incidentSeverity] = v }
                request.enabled?.let { v -> it[enabled] = v }
                request.notificationChannels?.let { v ->
                    it[notificationChannels] = json.encodeToString(NotificationChannels.serializer(), v)
                }
                it[updatedAt] = now
            }

            toResponse(
                DashboardWidgetAlerts.selectAll().where {
                    DashboardWidgetAlerts.id eq alertId
                }.first()
            )
        }
    }

    fun deleteAlert(alertId: Long, dashboardId: Long, orgId: Long): Boolean {
        return transaction {
            DashboardWidgetAlerts.deleteWhere {
                (DashboardWidgetAlerts.id eq alertId) and
                    (DashboardWidgetAlerts.dashboardId eq dashboardId) and
                    (DashboardWidgetAlerts.orgId eq orgId)
            } > 0
        }
    }

    // ---- Background evaluation ----

    private data class AlertContext(
        val alertId: Long,
        val widgetId: Long,
        val dashboardId: Long,
        val orgId: Long,
        val name: String,
        val condition: String,
        val threshold: Double,
        val metricIndex: Int,
        val durationSeconds: Int,
        val incidentSeverity: String?,
        val notificationChannels: NotificationChannels,
        val lastTriggeredAt: Instant?,
        val queryConfigsJson: String,
        val dashboardTitle: String,
        val widgetTitle: String,
        val projectId: Long?
    )

    private suspend fun evaluateAlerts() {
        val alerts = transaction {
            DashboardWidgetAlerts
                .innerJoin(DashboardWidgets) { DashboardWidgetAlerts.widgetId eq DashboardWidgets.id }
                .innerJoin(Dashboards) { DashboardWidgets.dashboardId eq Dashboards.id }
                .selectAll()
                .where { DashboardWidgetAlerts.enabled eq true }
                .map { row ->
                    AlertContext(
                        alertId = row[DashboardWidgetAlerts.id],
                        widgetId = row[DashboardWidgetAlerts.widgetId],
                        dashboardId = row[DashboardWidgetAlerts.dashboardId],
                        orgId = row[DashboardWidgetAlerts.orgId],
                        name = row[DashboardWidgetAlerts.name],
                        condition = row[DashboardWidgetAlerts.condition],
                        threshold = row[DashboardWidgetAlerts.threshold],
                        metricIndex = row[DashboardWidgetAlerts.metricIndex],
                        durationSeconds = row[DashboardWidgetAlerts.durationSeconds],
                        incidentSeverity = row[DashboardWidgetAlerts.incidentSeverity],
                        notificationChannels = suspendRunCatching {
                            json.decodeFromString<NotificationChannels>(row[DashboardWidgetAlerts.notificationChannels])
                        }.getOrElse {
                            NotificationChannels()
                        },
                        lastTriggeredAt = row[DashboardWidgetAlerts.lastTriggeredAt],
                        queryConfigsJson = row[DashboardWidgets.queryConfigs],
                        dashboardTitle = row[Dashboards.title],
                        widgetTitle = row[DashboardWidgets.title] ?: "Untitled",
                        projectId = row[Dashboards.projectId]
                    )
                }
        }

        logger.debug { "Evaluating ${alerts.size} dashboard alerts" }

        for (alert in alerts) {
            suspendRunCatching {
                evaluateAlert(alert)
            }.onFailure { e ->
                logger.error(e) { "Error evaluating dashboard alert ${alert.alertId}" }
            }
        }
    }

    private suspend fun evaluateAlert(alert: AlertContext) {
        val alertKey = "dashboard_alert_state:${alert.alertId}"
        val pendingKey = "dashboard_alert_pending:${alert.alertId}"

        val queryConfigs: List<QueryDsl> = suspendRunCatching {
            json.decodeFromString<List<QueryDsl>>(alert.queryConfigsJson)
        }.getOrElse {
            return
        }
        if (queryConfigs.isEmpty()) return
        val (queryIndex, metricIndexInQuery) = resolveMetricTarget(queryConfigs, alert.metricIndex) ?: return
        val queryDsl = queryConfigs.getOrNull(queryIndex) ?: return

        val results = suspendRunCatching {
            executeQueryForAlert(alert.orgId, alert.projectId, queryDsl)
        }.getOrElse { e ->
            logger.warn(e) { "Failed to execute query for dashboard alert ${alert.alertId}" }
            return
        }

        val currentValue = extractMetricValue(results, queryDsl, metricIndexInQuery) ?: return

        // Update last_value
        transaction {
            DashboardWidgetAlerts.update({ DashboardWidgetAlerts.id eq alert.alertId }) {
                it[lastValue] = currentValue
            }
        }

        val triggered = isThresholdTriggered(alert.condition, currentValue, alert.threshold)

        // Handle recovery
        if (!triggered) {
            val wasTriggered = suspendRunCatching {
                if (RedisConfig.isConnected()) {
                    RedisConfig.sync().get(alertKey) == "TRIGGERED"
                } else {
                    false
                }
            }.getOrElse { false }

            clearPendingState(pendingKey, alert.alertId)

            if (wasTriggered) {
                suspendRunCatching {
                    if (RedisConfig.isConnected()) RedisConfig.sync().del(alertKey)
                }.onFailure { e ->
                    logger.error(e) { "Failed to clear dashboard alert state in Redis" }
                }
                sendRecoveryNotification(alert, currentValue)
                suspendRunCatching {
                    incidentService.autoResolveAlert(
                        organizationId = alert.orgId.toInt(),
                        source = AlertSource.DASHBOARD_ALERT,
                        deduplicationKey = "moneat-dashboard-alert-${alert.alertId}"
                    )
                }.onFailure { e ->
                    logger.error(e) { "Failed to resolve incident for recovered dashboard alert ${alert.alertId}" }
                }
                logger.info { "Dashboard alert ${alert.alertId} recovered" }
            }
            return
        }

        val now = Clock.System.now()
        if (alert.durationSeconds > 0) {
            val pendingSince = getOrSetPendingStart(pendingKey, alert.alertId, now)
            if ((now - pendingSince) < alert.durationSeconds.seconds) {
                return
            }
            clearPendingState(pendingKey, alert.alertId)
        } else {
            clearPendingState(pendingKey, alert.alertId)
        }

        // Throttle check
        if (alert.lastTriggeredAt != null) {
            if ((now - alert.lastTriggeredAt) < MIN_ALERT_INTERVAL_MINUTES.minutes) {
                suspendRunCatching {
                    if (RedisConfig.isConnected()) RedisConfig.sync().set(alertKey, "TRIGGERED")
                }
                return
            }
        }

        logger.info {
            "Dashboard alert ${alert.alertId} triggered: " +
                "${alert.name} ${alert.condition} ${alert.threshold} (current: $currentValue)"
        }

        suspendRunCatching {
            if (RedisConfig.isConnected()) RedisConfig.sync().set(alertKey, "TRIGGERED")
        }.onFailure { e ->
            logger.error(e) { "Failed to set dashboard alert state in Redis" }
        }

        transaction {
            DashboardWidgetAlerts.update({ DashboardWidgetAlerts.id eq alert.alertId }) {
                it[lastTriggeredAt] = now
                it[lastValue] = currentValue
            }
        }

        sendAlertNotification(alert, currentValue)
    }

    internal suspend fun executeQueryForAlert(
        orgId: Long,
        projectId: Long?,
        queryDsl: QueryDsl
    ): List<Map<String, JsonElement>> {
        val customDataSource = resolveCustomDataSource(orgId, queryDsl.dataSource)
        if (customDataSource != null) {
            return executeCustomDataSourceQuery(orgId, customDataSource, queryDsl)
        }

        val builtInProjectId = projectId ?: return emptyList()
        val retentionDays =
            retentionPolicyService.getRetentionDaysForProject(builtInProjectId) ?: DEFAULT_RETENTION_DAYS
        return queryEngine.executeQuery(queryDsl, builtInProjectId, null, retentionDays)
    }

    private fun resolveCustomDataSource(
        orgId: Long,
        dataSource: String
    ): CustomDataSourceResponse? {
        if (dataSource == "__prometheus") {
            return checkNotNull(
                dataSourceService.listDataSources(orgId)
                    .firstOrNull { source ->
                        source.enabled &&
                            CustomDataSourceType.fromString(source.sourceType) == CustomDataSourceType.PROMETHEUS
                    }
            ) { "No enabled Prometheus data source configured" }
        }
        if (!dataSource.startsWith("custom:")) return null

        val sourceId = requireNotNull(dataSource.removePrefix("custom:").toLongOrNull()) {
            "Invalid custom data source reference: $dataSource"
        }
        val source = checkNotNull(dataSourceService.getDataSource(sourceId, orgId)) {
            "Custom data source not found: $sourceId"
        }
        check(source.enabled) { "Custom data source is disabled: $sourceId" }
        return source
    }

    private suspend fun executeCustomDataSourceQuery(
        orgId: Long,
        dataSource: CustomDataSourceResponse,
        queryDsl: QueryDsl
    ): List<Map<String, JsonElement>> {
        val sourceType = checkNotNull(CustomDataSourceType.fromString(dataSource.sourceType)) {
            "Unsupported data source type: ${dataSource.sourceType}"
        }
        val rawQuery = requireNotNull(queryDsl.rawQuery?.takeIf { it.isNotBlank() }) {
            "Custom data source dashboard alerts require rawQuery"
        }
        val resolvedCredentials = dataSourceService.getDecryptedCredentials(dataSource.id, orgId)
        check(resolvedCredentials != null || !dataSource.hasCredentials) {
            "Failed to resolve credentials for custom data source: ${dataSource.id}"
        }
        val credentials = resolvedCredentials ?: DataSourceCredentials()

        return dataSourceExecutor.executeQuery(
            sourceId = dataSource.id,
            sourceType = sourceType,
            host = dataSource.host,
            port = dataSource.port,
            databaseName = dataSource.databaseName,
            credentials = credentials,
            query = rawQuery,
            limit = queryDsl.limit,
            timeRange = queryDsl.timeRange,
        )
    }

    private fun resolveMetricTarget(queryConfigs: List<QueryDsl>, globalMetricIndex: Int): Pair<Int, Int>? {
        if (globalMetricIndex < 0) return null
        var remaining = globalMetricIndex
        queryConfigs.forEachIndexed { queryIndex, query ->
            val count = if (query.metrics.isEmpty()) 1 else query.metrics.size
            if (remaining < count) return queryIndex to remaining
            remaining -= count
        }
        return null
    }

    private fun extractMetricValue(
        results: List<Map<String, JsonElement>>,
        queryDsl: QueryDsl,
        metricIndexInQuery: Int
    ): Double? {
        if (results.isEmpty()) return null
        val metricAliases = metricAliases(queryDsl)
        val targetAlias = metricAliases.getOrNull(metricIndexInQuery)

        return results.asReversed().firstNotNullOfOrNull { row ->
            primitiveDouble(targetAlias?.let { row[it] })?.let { return@firstNotNullOfOrNull it }

            val metricValues = row.entries
                .filter { (key, _) -> key !in setOf("time_bucket", "timestamp", "time", "day") }
                .map { it.value }

            primitiveDouble(metricValues.getOrNull(metricIndexInQuery))
                ?: metricValues.firstNotNullOfOrNull(::primitiveDouble)
        }
    }

    private fun primitiveDouble(value: JsonElement?): Double? = when (value) {
        is JsonPrimitive -> value.doubleOrNull ?: value.content.toDoubleOrNull()
        else -> null
    }

    private fun metricAliases(queryDsl: QueryDsl): List<String> {
        if (queryDsl.metrics.isEmpty()) return listOf("total")
        return queryDsl.metrics.map { metric ->
            metric.alias ?: "${metric.function.value}_${metric.field ?: "all"}"
        }
    }

    private fun getOrSetPendingStart(pendingKey: String, alertId: Long, now: Instant): Instant {
        if (RedisConfig.isConnected()) {
            suspendRunCatching {
                val redis = RedisConfig.sync()
                val existing = redis.get(pendingKey)?.toLongOrNull()
                if (existing != null) return Instant.fromEpochMilliseconds(existing * MILLIS_PER_SECOND)
                redis.set(pendingKey, (now.toEpochMilliseconds() / MILLIS_PER_SECOND).toString())
                return now
            }
        }
        return pendingSinceFallback.computeIfAbsent(alertId) { now }
    }

    private fun clearPendingState(pendingKey: String, alertId: Long) {
        if (RedisConfig.isConnected()) {
            suspendRunCatching {
                RedisConfig.sync().del(pendingKey)
            }
        }
        pendingSinceFallback.remove(alertId)
    }

    private fun isThresholdTriggered(condition: String, currentValue: Double, threshold: Double): Boolean {
        return when (condition) {
            ">" -> currentValue > threshold
            "<" -> currentValue < threshold
            ">=" -> currentValue >= threshold
            "<=" -> currentValue <= threshold
            "==" -> currentValue == threshold
            else -> false
        }
    }

    private suspend fun sendAlertNotification(alert: AlertContext, currentValue: Double) {
        val orgId = alert.orgId.toInt()
        val channels = alert.notificationChannels
        val baseUrl = config.property("email.frontendUrl").getString()
        val formattedValue = "%.2f".format(currentValue)
        val formattedThreshold = "%.2f".format(alert.threshold)
        val conditionText = getConditionText(alert.condition)

        if (channels.email) {
            val recipients = prefsService.getUsersWithChannelEnabled(orgId, "DASHBOARD_ALERT", "email")
            val subject = "📊 Dashboard Alert: ${alert.name} - $conditionText $formattedThreshold"
            for ((_, email) in recipients) {
                suspendRunCatching {
                    val htmlBody = buildAlertEmailHtml(alert, formattedValue, formattedThreshold, baseUrl)
                    val textBody = buildAlertEmailText(alert, formattedValue, formattedThreshold, baseUrl)
                    emailService.sendEmail(email, subject, htmlBody, textBody, "dashboard_alert")
                }.onFailure { e ->
                    logger.error(e) { "Failed to send dashboard alert email to $email" }
                }
            }
        }

        if (channels.slack) {
            val slackEnabled = prefsService.getUsersWithChannelEnabled(orgId, "DASHBOARD_ALERT", "slack").isNotEmpty()
            if (slackEnabled) {
                suspendRunCatching {
                    slackService.sendDashboardAlert(
                        organizationId = orgId, alertName = alert.name,
                        dashboardTitle = alert.dashboardTitle, widgetTitle = alert.widgetTitle,
                        condition = alert.condition, threshold = formattedThreshold,
                        currentValue = formattedValue, severity = alert.incidentSeverity,
                        dashboardId = alert.dashboardId, baseUrl = baseUrl
                    )
                }.onFailure { e ->
                    logger.error(e) { "Failed to send Slack notification for dashboard alert" }
                }
            }
        }

        if (channels.discord) {
            val discordEnabled = prefsService.getUsersWithChannelEnabled(
                orgId,
                "DASHBOARD_ALERT",
                "discord"
            ).isNotEmpty()
            if (discordEnabled) {
                suspendRunCatching {
                    discordService.sendDashboardAlert(
                        organizationId = orgId, alertName = alert.name,
                        dashboardTitle = alert.dashboardTitle, widgetTitle = alert.widgetTitle,
                        condition = alert.condition, threshold = formattedThreshold,
                        currentValue = formattedValue, severity = alert.incidentSeverity,
                        dashboardId = alert.dashboardId, baseUrl = baseUrl
                    )
                }.onFailure { e ->
                    logger.error(e) { "Failed to send Discord notification for dashboard alert" }
                }
            }
        }

        if (alert.incidentSeverity != null) {
            val severity = IncidentSeverity.fromString(alert.incidentSeverity)
            if (severity != null) {
                suspendRunCatching {
                    incidentService.fireAlert(
                        IncidentEvent(
                            title = "Dashboard Alert: ${alert.name}",
                            description = "${alert.widgetTitle} on ${alert.dashboardTitle}:" +
                                " value $formattedValue ${alert.condition} $formattedThreshold",
                            severity = severity,
                            status = IncidentStatus.FIRING,
                            source = AlertSource.DASHBOARD_ALERT,
                            deduplicationKey = "moneat-dashboard-alert-${alert.alertId}",
                            organizationId = orgId,
                            moneatUrl = "$baseUrl/dashboards/${alert.dashboardId}"
                        )
                    )
                }.onFailure { e ->
                    logger.error(e) { "Failed to trigger incident for dashboard alert" }
                }
            }
        }
    }

    private fun sendRecoveryNotification(alert: AlertContext, currentValue: Double) {
        val orgId = alert.orgId.toInt()
        val channels = alert.notificationChannels
        val baseUrl = config.property("email.frontendUrl").getString()
        val formattedValue = "%.2f".format(currentValue)

        if (channels.email) {
            val recipients = prefsService.getUsersWithChannelEnabled(orgId, "DASHBOARD_ALERT", "email")
            val subject = "✅ Dashboard Alert Resolved: ${alert.name}"
            for ((_, email) in recipients) {
                suspendRunCatching {
                    val htmlBody = """
                        <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 600px; margin: 0 auto;">
                            <div style="background: #059669; color: white; padding: 20px; border-radius: 8px 8px 0 0;">
                                <h2 style="margin: 0;">✅ Dashboard Alert Resolved: ${alert.name}</h2>
                            </div>
                            <div style="background: white; padding: 20px; border: 1px solid #e5e7eb; border-top: none;">
                                <p><strong>Dashboard:</strong> ${alert.dashboardTitle}</p>
                                <p><strong>Widget:</strong> ${alert.widgetTitle}</p>
                                <p>Current Value: <strong>$formattedValue</strong></p>
                                <a href="$baseUrl/dashboards/${alert.dashboardId}" style="display: inline-block; background: #059669; color: white; padding: 10px 20px; border-radius: 6px; text-decoration: none;">View Dashboard</a>
                            </div>
                        </div>
                    """.trimIndent()
                    val textBody = "✅ Dashboard Alert Resolved: ${alert.name}\n" +
                        "Dashboard: ${alert.dashboardTitle}\nWidget: ${alert.widgetTitle}\n" +
                        "Current Value: $formattedValue\nView: $baseUrl/dashboards/${alert.dashboardId}"
                    emailService.sendEmail(email, subject, htmlBody, textBody, "dashboard_alert_recovery")
                }.onFailure { e ->
                    logger.error(e) { "Failed to send dashboard alert recovery email to $email" }
                }
            }
        }
    }

    private fun buildAlertEmailHtml(alert: AlertContext, value: String, threshold: String, baseUrl: String): String {
        return """
            <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 600px; margin: 0 auto;">
                <div style="background: #7c3aed; color: white; padding: 20px; border-radius: 8px 8px 0 0;">
                    <h2 style="margin: 0;">📊 Dashboard Alert: ${alert.name}</h2>
                </div>
                <div style="background: white; padding: 20px; border: 1px solid #e5e7eb; border-top: none;">
                    <p><strong>Dashboard:</strong> ${alert.dashboardTitle}</p>
                    <p><strong>Widget:</strong> ${alert.widgetTitle}</p>
                    <div style="background: #f5f3ff; border: 1px solid #ddd6fe; border-radius: 8px; padding: 16px; text-align: center; margin: 16px 0;">
                        <p style="font-size: 32px; font-weight: bold; color: #7c3aed; margin: 0;">$value</p>
                        <p style="color: #6b7280; margin: 4px 0 0 0;">Threshold: ${alert.condition} $threshold</p>
                    </div>
                    <a href="$baseUrl/dashboards/${alert.dashboardId}" style="display: inline-block; background: #7c3aed; color: white; padding: 10px 20px; border-radius: 6px; text-decoration: none;">View Dashboard</a>
                </div>
            </div>
        """.trimIndent()
    }

    private fun buildAlertEmailText(alert: AlertContext, value: String, threshold: String, baseUrl: String): String {
        return """
            📊 Dashboard Alert: ${alert.name}
            
            Dashboard: ${alert.dashboardTitle}
            Widget: ${alert.widgetTitle}
            Current Value: $value
            Threshold: ${alert.condition} $threshold
            
            View Dashboard: $baseUrl/dashboards/${alert.dashboardId}
        """.trimIndent()
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

    private fun validateCondition(condition: String) {
        require(condition in listOf(">", "<", ">=", "<=", "==")) {
            "Invalid condition: $condition. Must be one of: >, <, >=, <=, =="
        }
    }

    private fun toResponse(row: ResultRow): DashboardAlertResponse {
        val channels: NotificationChannels = suspendRunCatching {
            json.decodeFromString<NotificationChannels>(row[DashboardWidgetAlerts.notificationChannels])
        }.getOrElse {
            NotificationChannels()
        }

        return DashboardAlertResponse(
            id = row[DashboardWidgetAlerts.id],
            widgetId = row[DashboardWidgetAlerts.widgetId],
            dashboardId = row[DashboardWidgetAlerts.dashboardId],
            name = row[DashboardWidgetAlerts.name],
            condition = row[DashboardWidgetAlerts.condition],
            threshold = row[DashboardWidgetAlerts.threshold],
            metricIndex = row[DashboardWidgetAlerts.metricIndex],
            durationSeconds = row[DashboardWidgetAlerts.durationSeconds],
            incidentSeverity = row[DashboardWidgetAlerts.incidentSeverity],
            enabled = row[DashboardWidgetAlerts.enabled],
            notificationChannels = channels,
            lastTriggeredAt = row[DashboardWidgetAlerts.lastTriggeredAt]?.toString(),
            lastValue = row[DashboardWidgetAlerts.lastValue],
            createdAt = row[DashboardWidgetAlerts.createdAt].toString(),
            updatedAt = row[DashboardWidgetAlerts.updatedAt].toString()
        )
    }
}
