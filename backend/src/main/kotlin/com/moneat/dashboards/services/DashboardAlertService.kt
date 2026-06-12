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
import com.moneat.dashboards.services.handlers.withConnectionOptions
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.AlertStatus
import com.moneat.incident.services.IncidentService
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.shared.services.TaskLock
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.services.WorkflowService
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
import org.jetbrains.exposed.v1.core.inList
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
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}
private const val ALERT_CHANNEL_EMAIL_REFERENCE = "alert.channels.email"
private const val ALERT_CHANNEL_SLACK_REFERENCE = "alert.channels.slack"
private const val ALERT_CHANNEL_DISCORD_REFERENCE = "alert.channels.discord"
private const val ALERT_DISPLAY_TITLE_REFERENCE = "alert.display_title"
private const val ALERT_DASHBOARD_TITLE_REFERENCE = "alert.dashboard.title"
private const val ALERT_WIDGET_TITLE_REFERENCE = "alert.widget.title"
private const val ALERT_CONDITION_REFERENCE = "alert.condition"
private const val ALERT_THRESHOLD_REFERENCE = "alert.threshold"
private const val ALERT_CURRENT_VALUE_REFERENCE = "alert.current_value"

private enum class DashboardAlertLevel(val label: String) {
    WARNING("Warning"),
    ERROR("Error")
}

private data class AlertThresholdHit(
    val level: DashboardAlertLevel,
    val threshold: Double
)

class DashboardAlertService(
    private val incidentService: IncidentService = IncidentService(),
    private val workflowService: WorkflowService = WorkflowService(),
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

    private fun parseUuid(value: String): Uuid? =
        value.toUuidOrNull()

    fun isValidResourceId(value: String?): Boolean =
        value?.let(::parseUuid) != null

    fun resolveAlertId(resourceId: String, dashboardId: Long, orgId: Long): Long? =
        parseUuid(resourceId)?.let { parsed ->
            transaction {
                DashboardWidgetAlerts.selectAll()
                    .where {
                        (DashboardWidgetAlerts.resourceId eq parsed) and
                            (DashboardWidgetAlerts.dashboardId eq dashboardId) and
                            (DashboardWidgetAlerts.orgId eq orgId)
                    }
                    .firstOrNull()
                    ?.get(DashboardWidgetAlerts.id)
            }
        }

    private fun resolveWidgetId(resourceId: String, dashboardId: Long, orgId: Long): Long =
        requireNotNull(parseUuid(resourceId)) {
            "Invalid widget ID"
        }.let { parsed ->
            transaction {
                DashboardWidgets.innerJoin(Dashboards)
                    .selectAll()
                    .where {
                        (DashboardWidgets.resourceId eq parsed) and
                            (DashboardWidgets.dashboardId eq dashboardId) and
                            (Dashboards.id eq dashboardId) and
                            (Dashboards.orgId eq orgId)
                    }
                    .firstOrNull()
                    ?.get(DashboardWidgets.id)
            }
        } ?: throw IllegalArgumentException("Widget not found in this dashboard")

    // ---- CRUD ----

    fun createAlert(
        dashboardId: Long,
        orgId: Long,
        createdBy: Long,
        request: CreateDashboardAlertRequest
    ): DashboardAlertResponse {
        validateCondition(request.condition)
        validateWarningThreshold(request.condition, request.warningThreshold, request.threshold)
        val now = Clock.System.now()
        val channelsJson = json.encodeToString(NotificationChannels.serializer(), request.notificationChannels)
        val alertPriority = normalizeAlertPriority(request.alertPriority ?: request.legacyIncidentSeverity)
        val widgetId = resolveWidgetId(request.widgetId, dashboardId, orgId)

        return transaction {
            DashboardWidgets.innerJoin(Dashboards)
                .selectAll()
                .where {
                    (DashboardWidgets.id eq widgetId) and
                        (DashboardWidgets.dashboardId eq dashboardId) and
                        (Dashboards.id eq dashboardId) and
                        (Dashboards.orgId eq orgId)
                }
                .firstOrNull() ?: throw IllegalArgumentException("Widget not found in this dashboard")

            val id = DashboardWidgetAlerts.insert {
                it[DashboardWidgetAlerts.widgetId] = widgetId
                it[DashboardWidgetAlerts.dashboardId] = dashboardId
                it[DashboardWidgetAlerts.orgId] = orgId
                it[DashboardWidgetAlerts.name] = request.name
                it[DashboardWidgetAlerts.condition] = request.condition
                it[DashboardWidgetAlerts.threshold] = request.threshold
                it[DashboardWidgetAlerts.warningThreshold] = request.warningThreshold
                it[DashboardWidgetAlerts.metricIndex] = request.metricIndex
                it[DashboardWidgetAlerts.durationSeconds] = request.durationSeconds
                it[DashboardWidgetAlerts.alertPriority] = alertPriority
                it[DashboardWidgetAlerts.enabled] = request.enabled
                it[DashboardWidgetAlerts.notificationChannels] = channelsJson
                it[DashboardWidgetAlerts.createdBy] = createdBy
                it[DashboardWidgetAlerts.createdAt] = now
                it[DashboardWidgetAlerts.updatedAt] = now
            } get DashboardWidgetAlerts.id

            val row =
                DashboardWidgetAlerts.selectAll().where {
                    DashboardWidgetAlerts.id eq id
                }.first()
            toResponse(row, resourceIdsForRows(listOf(row)))
        }
    }

    fun listAlerts(dashboardId: Long, orgId: Long): List<DashboardAlertResponse> {
        return transaction {
            val rows =
                DashboardWidgetAlerts.selectAll().where {
                    (DashboardWidgetAlerts.dashboardId eq dashboardId) and
                        (DashboardWidgetAlerts.orgId eq orgId)
                }.orderBy(DashboardWidgetAlerts.createdAt, SortOrder.DESC).toList()
            val resourceIds = resourceIdsForRows(rows)
            rows.map { toResponse(it, resourceIds) }
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
            val existing = DashboardWidgetAlerts.selectAll().where {
                (DashboardWidgetAlerts.id eq alertId) and
                    (DashboardWidgetAlerts.dashboardId eq dashboardId) and
                    (DashboardWidgetAlerts.orgId eq orgId)
            }.firstOrNull() ?: return@transaction null

            val effectiveCondition = request.condition ?: existing[DashboardWidgetAlerts.condition]
            val effectiveThreshold = request.threshold ?: existing[DashboardWidgetAlerts.threshold]
            val warningThresholdProvided = request.warningThresholdProvided || request.warningThreshold != null
            val effectiveWarningThreshold = if (warningThresholdProvided) {
                request.warningThreshold
            } else {
                existing[DashboardWidgetAlerts.warningThreshold]
            }
            validateWarningThreshold(effectiveCondition, effectiveWarningThreshold, effectiveThreshold)

            DashboardWidgetAlerts.update({
                (DashboardWidgetAlerts.id eq alertId) and (DashboardWidgetAlerts.orgId eq orgId)
            }) {
                request.name?.let { v -> it[name] = v }
                request.condition?.let { v -> it[condition] = v }
                request.threshold?.let { v -> it[threshold] = v }
                if (warningThresholdProvided) it[warningThreshold] = request.warningThreshold
                request.metricIndex?.let { v -> it[metricIndex] = v }
                request.durationSeconds?.let { v -> it[durationSeconds] = v }
                val requestedAlertPriority = request.alertPriority ?: request.legacyIncidentSeverity
                requestedAlertPriority?.let { v -> it[alertPriority] = normalizeAlertPriority(v) }
                request.enabled?.let { v -> it[enabled] = v }
                request.notificationChannels?.let { v ->
                    it[notificationChannels] = json.encodeToString(NotificationChannels.serializer(), v)
                }
                it[updatedAt] = now
            }

            val row =
                DashboardWidgetAlerts.selectAll().where {
                    DashboardWidgetAlerts.id eq alertId
                }.first()
            toResponse(row, resourceIdsForRows(listOf(row)))
        }
    }

    fun updateAlert(
        alertId: String,
        dashboardId: Long,
        orgId: Long,
        request: UpdateDashboardAlertRequest
    ): DashboardAlertResponse? {
        val numericAlertId = resolveAlertId(alertId, dashboardId, orgId) ?: return null
        return updateAlert(numericAlertId, dashboardId, orgId, request)
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

    fun deleteAlert(alertId: String, dashboardId: Long, orgId: Long): Boolean {
        val numericAlertId = resolveAlertId(alertId, dashboardId, orgId) ?: return false
        return deleteAlert(numericAlertId, dashboardId, orgId)
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
        val warningThreshold: Double?,
        val metricIndex: Int,
        val durationSeconds: Int,
        val alertPriority: String?,
        val notificationChannels: NotificationChannels,
        val lastTriggeredAt: Instant?,
        val lastTriggeredLevel: String?,
        val queryConfigsJson: String,
        val dashboardTitle: String,
        val dashboardResourceId: String,
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
                        warningThreshold = row[DashboardWidgetAlerts.warningThreshold],
                        metricIndex = row[DashboardWidgetAlerts.metricIndex],
                        durationSeconds = row[DashboardWidgetAlerts.durationSeconds],
                        alertPriority = row[DashboardWidgetAlerts.alertPriority],
                        notificationChannels = suspendRunCatching {
                            json.decodeFromString<NotificationChannels>(row[DashboardWidgetAlerts.notificationChannels])
                        }.getOrElse {
                            NotificationChannels()
                        },
                        lastTriggeredAt = row[DashboardWidgetAlerts.lastTriggeredAt],
                        lastTriggeredLevel = row[DashboardWidgetAlerts.lastTriggeredLevel],
                        queryConfigsJson = row[DashboardWidgets.queryConfigs],
                        dashboardTitle = row[Dashboards.title],
                        dashboardResourceId = row[Dashboards.resourceId].toString(),
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

        val currentValue = readCurrentMetricValue(alert) ?: return
        updateLastValue(alert.alertId, currentValue)

        val trigger = resolveTriggeredThreshold(alert, currentValue)
        val previousLevel = getActiveAlertLevel(alertKey, alert.lastTriggeredLevel)
        if (trigger == null) {
            handleRecoveredAlert(alert, alertKey, pendingKey, previousLevel, currentValue)
            return
        }

        val now = Clock.System.now()
        if (!hasMetDuration(alert, pendingKey, now)) return
        if (shouldThrottleRepeatedAlert(alert, alertKey, previousLevel, trigger, now)) return

        triggerAlert(alert, alertKey, currentValue, trigger, now)
    }

    private suspend fun readCurrentMetricValue(alert: AlertContext): Double? {
        val queryConfigs: List<QueryDsl> = suspendRunCatching {
            json.decodeFromString<List<QueryDsl>>(alert.queryConfigsJson)
        }.getOrElse {
            return null
        }
        if (queryConfigs.isEmpty()) return null
        val (queryIndex, metricIndexInQuery) = resolveMetricTarget(queryConfigs, alert.metricIndex) ?: return null
        val queryDsl = queryConfigs.getOrNull(queryIndex) ?: return null

        val results = suspendRunCatching {
            executeQueryForAlert(alert.orgId, alert.projectId, queryDsl)
        }.getOrElse { e ->
            logger.warn(e) { "Failed to execute query for dashboard alert ${alert.alertId}" }
            return null
        }

        return extractMetricValue(results, queryDsl, metricIndexInQuery)
    }

    private fun updateLastValue(alertId: Long, currentValue: Double) {
        transaction {
            DashboardWidgetAlerts.update({ DashboardWidgetAlerts.id eq alertId }) {
                it[lastValue] = currentValue
            }
        }
    }

    private suspend fun handleRecoveredAlert(
        alert: AlertContext,
        alertKey: String,
        pendingKey: String,
        previousLevel: DashboardAlertLevel?,
        currentValue: Double
    ) {
        clearPendingState(pendingKey, alert.alertId)
        if (previousLevel == null) return

        clearActiveAlertState(alertKey)
        transaction {
            DashboardWidgetAlerts.update({ DashboardWidgetAlerts.id eq alert.alertId }) {
                it[lastTriggeredAt] = null
                it[lastTriggeredLevel] = null
                it[lastValue] = currentValue
            }
        }

        val baseUrl = config.property("email.frontendUrl").getString()
        val configuredAlertPriority =
            if (previousLevel == DashboardAlertLevel.ERROR) {
                alert.alertPriority?.let { AlertPriority.fromString(it) }
            } else {
                null
            }
        val workflowPriority = configuredAlertPriority ?: when (previousLevel) {
            DashboardAlertLevel.WARNING -> AlertPriority.P3
            DashboardAlertLevel.ERROR -> AlertPriority.P1
        }
        val formattedValue = "%.2f".format(currentValue)
        val recoveredThreshold = when (previousLevel) {
            DashboardAlertLevel.WARNING -> alert.warningThreshold ?: alert.threshold
            DashboardAlertLevel.ERROR -> alert.threshold
        }
        val title = "Dashboard Alert Resolved: ${alert.name}"
        val description = "${alert.widgetTitle} on ${alert.dashboardTitle} recovered. " +
            "Current value: $formattedValue"
        val moneatUrl = "$baseUrl/dashboards/${alert.dashboardResourceId}"
        val event =
            AlertLifecycleEvent(
                title = title,
                description = description,
                priority = workflowPriority,
                status = AlertStatus.RESOLVED,
                source = AlertSource.DASHBOARD_ALERT,
                deduplicationKey = "moneat-dashboard-alert-${alert.alertId}",
                organizationId = alert.orgId.toInt(),
                metadata = alert.notificationChannels.workflowScopeMetadata(
                    alert.workflowScopeMetadata(formattedValue, "%.2f".format(recoveredThreshold))
                ),
                moneatUrl = moneatUrl
            )
        suspendRunCatching {
            workflowService.publishAlertTriggered(event)
        }.onFailure { e ->
            logger.error(e) { "Failed to publish recovered dashboard alert workflow ${alert.alertId}" }
        }
        if (configuredAlertPriority != null) {
            suspendRunCatching {
                incidentService.autoResolveAlert(
                    organizationId = alert.orgId.toInt(),
                    source = AlertSource.DASHBOARD_ALERT,
                    deduplicationKey = "moneat-dashboard-alert-${alert.alertId}",
                    title = title,
                    description = description,
                    moneatUrl = moneatUrl,
                    publishWorkflow = false
                )
            }.onFailure { e ->
                logger.error(e) { "Failed to resolve incident for recovered dashboard alert ${alert.alertId}" }
            }
        }
        logger.info { "Dashboard alert ${alert.alertId} recovered" }
    }

    private fun hasMetDuration(alert: AlertContext, pendingKey: String, now: Instant): Boolean {
        if (alert.durationSeconds > 0) {
            val pendingSince = getOrSetPendingStart(pendingKey, alert.alertId, now)
            if ((now - pendingSince) < alert.durationSeconds.seconds) {
                return false
            }
            clearPendingState(pendingKey, alert.alertId)
        } else {
            clearPendingState(pendingKey, alert.alertId)
        }
        return true
    }

    private fun shouldThrottleRepeatedAlert(
        alert: AlertContext,
        alertKey: String,
        previousLevel: DashboardAlertLevel?,
        trigger: AlertThresholdHit,
        now: Instant
    ): Boolean {
        if (previousLevel != trigger.level || alert.lastTriggeredAt == null) return false
        if ((now - alert.lastTriggeredAt) >= MIN_ALERT_INTERVAL_MINUTES.minutes) return false

        setActiveAlertState(alertKey, trigger.level)
        return true
    }

    private suspend fun triggerAlert(
        alert: AlertContext,
        alertKey: String,
        currentValue: Double,
        trigger: AlertThresholdHit,
        now: Instant
    ) {
        logger.info {
            "Dashboard alert ${alert.alertId} triggered: " +
                "${alert.name} ${trigger.level.name} ${alert.condition} ${trigger.threshold} (current: $currentValue)"
        }

        setActiveAlertState(alertKey, trigger.level)

        transaction {
            DashboardWidgetAlerts.update({ DashboardWidgetAlerts.id eq alert.alertId }) {
                it[lastTriggeredAt] = now
                it[lastTriggeredLevel] = trigger.level.name
                it[lastValue] = currentValue
            }
        }

        sendAlertNotification(alert, currentValue, trigger)
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
        return queryEngine.executeQuery(queryDsl, builtInProjectId, null, retentionDays, orgId)
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

        val sourceId = dataSource.removePrefix("custom:")
        val source = checkNotNull(dataSourceService.getDataSource(sourceId, orgId)) {
            "Invalid custom data source reference: $dataSource"
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
        val resolvedCredentials = dataSourceService.getDecryptedCredentials(dataSource.numericId, orgId)
        check(resolvedCredentials != null || !dataSource.hasCredentials) {
            "Failed to resolve credentials for custom data source: ${dataSource.id}"
        }
        val credentials = resolvedCredentials ?: DataSourceCredentials()

        return dataSourceExecutor.executeQuery(
            sourceId = dataSource.numericId,
            sourceType = sourceType,
            host = dataSource.host,
            port = dataSource.port,
            databaseName = dataSource.databaseName,
            credentials = credentials.withConnectionOptions(dataSource.extraConfig),
            query = rawQuery,
            limit = queryDsl.limit,
            timeRange = queryDsl.timeRange,
        )
    }

    private fun setActiveAlertState(alertKey: String, level: DashboardAlertLevel) {
        suspendRunCatching {
            if (RedisConfig.isConnected()) RedisConfig.sync().set(alertKey, level.name)
        }.onFailure { e ->
            logger.error(e) { "Failed to set dashboard alert state in Redis" }
        }
    }

    private fun clearActiveAlertState(alertKey: String) {
        suspendRunCatching {
            if (RedisConfig.isConnected()) RedisConfig.sync().del(alertKey)
        }.onFailure { e ->
            logger.error(e) { "Failed to clear dashboard alert state in Redis" }
        }
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

    private fun resolveTriggeredThreshold(alert: AlertContext, currentValue: Double): AlertThresholdHit? {
        if (isThresholdTriggered(alert.condition, currentValue, alert.threshold)) {
            return AlertThresholdHit(DashboardAlertLevel.ERROR, alert.threshold)
        }

        val warningThreshold = alert.warningThreshold ?: return null
        if (isThresholdTriggered(alert.condition, currentValue, warningThreshold)) {
            return AlertThresholdHit(DashboardAlertLevel.WARNING, warningThreshold)
        }
        return null
    }

    private fun getActiveAlertLevel(alertKey: String, fallbackLevel: String?): DashboardAlertLevel? {
        val redisLevel = suspendRunCatching {
            if (RedisConfig.isConnected()) {
                RedisConfig.sync().get(alertKey)
            } else {
                null
            }
        }.getOrNull()

        return parseAlertLevel(redisLevel) ?: parseAlertLevel(fallbackLevel)
    }

    private fun parseAlertLevel(value: String?): DashboardAlertLevel? {
        return when (value) {
            DashboardAlertLevel.WARNING.name -> DashboardAlertLevel.WARNING
            DashboardAlertLevel.ERROR.name, "TRIGGERED" -> DashboardAlertLevel.ERROR
            else -> null
        }
    }

    private suspend fun sendAlertNotification(
        alert: AlertContext,
        currentValue: Double,
        trigger: AlertThresholdHit
    ) {
        val orgId = alert.orgId.toInt()
        val baseUrl = config.property("email.frontendUrl").getString()
        val formattedValue = "%.2f".format(currentValue)
        val formattedThreshold = "%.2f".format(trigger.threshold)

        val configuredAlertPriority =
            if (trigger.level == DashboardAlertLevel.ERROR) {
                alert.alertPriority?.let { AlertPriority.fromString(it) }
            } else {
                null
            }
        val workflowPriority = configuredAlertPriority ?: when (trigger.level) {
            DashboardAlertLevel.WARNING -> AlertPriority.P3
            DashboardAlertLevel.ERROR -> AlertPriority.P1
        }
        val event =
            AlertLifecycleEvent(
                title = "Dashboard ${trigger.level.label}: ${alert.name}",
                description = "${alert.widgetTitle} on ${alert.dashboardTitle}:" +
                    " value $formattedValue ${alert.condition} $formattedThreshold",
                priority = workflowPriority,
                status = AlertStatus.FIRING,
                source = AlertSource.DASHBOARD_ALERT,
                deduplicationKey = "moneat-dashboard-alert-${alert.alertId}",
                organizationId = orgId,
                metadata = alert.notificationChannels.workflowScopeMetadata(
                    alert.workflowScopeMetadata(formattedValue, formattedThreshold)
                ),
                moneatUrl = "$baseUrl/dashboards/${alert.dashboardResourceId}"
            )

        suspendRunCatching {
            workflowService.publishAlertTriggered(event)
        }.onFailure { e ->
            logger.error(e) { "Failed to publish dashboard alert workflow" }
        }
        if (configuredAlertPriority != null) {
            suspendRunCatching {
                incidentService.fireAlert(event.copy(priority = configuredAlertPriority), publishWorkflow = false)
            }.onFailure { e ->
                logger.error(e) { "Failed to fire dashboard alert incident" }
            }
        }
    }

    private fun validateCondition(condition: String) {
        require(condition in listOf(">", "<", ">=", "<=", "==")) {
            "Invalid condition: $condition. Must be one of: >, <, >=, <=, =="
        }
    }

    private fun validateWarningThreshold(condition: String, warningThreshold: Double?, errorThreshold: Double) {
        if (warningThreshold == null) return

        when (condition) {
            ">", ">=" -> require(warningThreshold < errorThreshold) {
                "Warning threshold must be lower than the error threshold for $condition alerts"
            }
            "<", "<=" -> require(warningThreshold > errorThreshold) {
                "Warning threshold must be higher than the error threshold for $condition alerts"
            }
            "==" -> require(warningThreshold == errorThreshold) {
                "Warning threshold must match the error threshold for == alerts"
            }
        }
    }

    private fun normalizeAlertPriority(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return requireNotNull(AlertPriority.wireValue(value)) {
            "Invalid alert priority: $value"
        }
    }

    private fun NotificationChannels.workflowScopeMetadata(
        alertMetadata: Map<String, JsonElement>
    ): Map<String, JsonElement> =
        mapOf(
            ALERT_CHANNEL_EMAIL_REFERENCE to JsonPrimitive(email),
            ALERT_CHANNEL_SLACK_REFERENCE to JsonPrimitive(slack),
            ALERT_CHANNEL_DISCORD_REFERENCE to JsonPrimitive(discord)
        ) + alertMetadata

    private fun AlertContext.workflowScopeMetadata(
        formattedCurrentValue: String,
        formattedThreshold: String
    ): Map<String, JsonElement> =
        mapOf(
            ALERT_DISPLAY_TITLE_REFERENCE to JsonPrimitive(name),
            ALERT_DASHBOARD_TITLE_REFERENCE to JsonPrimitive(dashboardTitle),
            ALERT_WIDGET_TITLE_REFERENCE to JsonPrimitive(widgetTitle),
            ALERT_CONDITION_REFERENCE to JsonPrimitive(condition),
            ALERT_THRESHOLD_REFERENCE to JsonPrimitive(formattedThreshold),
            ALERT_CURRENT_VALUE_REFERENCE to JsonPrimitive(formattedCurrentValue)
        )

    private data class DashboardAlertResourceIds(
        val widgets: Map<Long, String>,
        val dashboards: Map<Long, String>
    ) {
        fun widget(widgetId: Long): String =
            widgets[widgetId] ?: error("Missing resource_id for dashboard widget $widgetId")

        fun dashboard(dashboardId: Long): String =
            dashboards[dashboardId] ?: error("Missing resource_id for dashboard $dashboardId")
    }

    private fun resourceIdsForRows(rows: List<ResultRow>): DashboardAlertResourceIds {
        if (rows.isEmpty()) return DashboardAlertResourceIds(emptyMap(), emptyMap())
        val widgetIds = rows.map { row -> row[DashboardWidgetAlerts.widgetId] }.distinct()
        val dashboardIds = rows.map { row -> row[DashboardWidgetAlerts.dashboardId] }.distinct()
        val widgets =
            DashboardWidgets
                .selectAll()
                .where { DashboardWidgets.id inList widgetIds }
                .associate { row -> row[DashboardWidgets.id] to row[DashboardWidgets.resourceId].toString() }
        val dashboards =
            Dashboards
                .selectAll()
                .where { Dashboards.id inList dashboardIds }
                .associate { row -> row[Dashboards.id] to row[Dashboards.resourceId].toString() }
        return DashboardAlertResourceIds(widgets = widgets, dashboards = dashboards)
    }

    private fun toResponse(
        row: ResultRow,
        resourceIds: DashboardAlertResourceIds
    ): DashboardAlertResponse {
        val channels: NotificationChannels = suspendRunCatching {
            json.decodeFromString<NotificationChannels>(row[DashboardWidgetAlerts.notificationChannels])
        }.getOrElse {
            NotificationChannels()
        }

        return DashboardAlertResponse(
            id = row[DashboardWidgetAlerts.resourceId].toString(),
            widgetId = resourceIds.widget(row[DashboardWidgetAlerts.widgetId]),
            dashboardId = resourceIds.dashboard(row[DashboardWidgetAlerts.dashboardId]),
            name = row[DashboardWidgetAlerts.name],
            condition = row[DashboardWidgetAlerts.condition],
            threshold = row[DashboardWidgetAlerts.threshold],
            warningThreshold = row[DashboardWidgetAlerts.warningThreshold],
            metricIndex = row[DashboardWidgetAlerts.metricIndex],
            durationSeconds = row[DashboardWidgetAlerts.durationSeconds],
            alertPriority = row[DashboardWidgetAlerts.alertPriority]?.let { priority ->
                AlertPriority.wireValue(priority) ?: priority
            },
            enabled = row[DashboardWidgetAlerts.enabled],
            notificationChannels = channels,
            lastTriggeredAt = row[DashboardWidgetAlerts.lastTriggeredAt]?.toString(),
            lastTriggeredLevel = row[DashboardWidgetAlerts.lastTriggeredLevel],
            lastValue = row[DashboardWidgetAlerts.lastValue],
            createdAt = row[DashboardWidgetAlerts.createdAt].toString(),
            updatedAt = row[DashboardWidgetAlerts.updatedAt].toString()
        )
    }
}
