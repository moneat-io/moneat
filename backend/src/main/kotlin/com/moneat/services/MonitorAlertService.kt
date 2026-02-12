package com.moneat.services

import com.moneat.config.ClickHouseClient
import com.moneat.models.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

class MonitorAlertService {
    private val config = ApplicationConfig("application.conf")
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val emailService = EmailService()
    private val slackService = SlackService()
    private val discordService = DiscordService()
    private val incidentService = com.moneat.services.incident.IncidentService()
    
    private var evaluationJob: Job? = null
    private var statusCheckJob: Job? = null
    private var cleanupJob: Job? = null
    
    companion object {
        const val EVALUATION_INTERVAL_SECONDS = 30
        const val STATUS_CHECK_INTERVAL_SECONDS = 60
        const val SYSTEM_DOWN_THRESHOLD_SECONDS = 300 // 5 minutes
        const val MIN_ALERT_INTERVAL_MINUTES = 15 // Don't spam alerts
    }
    
    /**
     * Start the background jobs for alert evaluation and status checking.
     */
    fun start(scope: CoroutineScope) {
        logger.info { "Starting MonitorAlertService background jobs" }
        
        // Alert evaluation job
        evaluationJob = scope.launch {
            while (isActive) {
                try {
                    evaluateAlerts()
                } catch (e: Exception) {
                    logger.error(e) { "Error evaluating alerts" }
                }
                delay(EVALUATION_INTERVAL_SECONDS.seconds)
            }
        }
        
        // System status check job
        statusCheckJob = scope.launch {
            while (isActive) {
                try {
                    checkSystemStatuses()
                } catch (e: Exception) {
                    logger.error(e) { "Error checking system statuses" }
                }
                delay(STATUS_CHECK_INTERVAL_SECONDS.seconds)
            }
        }
        
        // Expired silence period cleanup job (runs every 5 minutes)
        cleanupJob = scope.launch {
            while (isActive) {
                try {
                    cleanupExpiredSilencePeriods()
                } catch (e: Exception) {
                    logger.error(e) { "Error cleaning up expired silence periods" }
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
        val alerts = transaction {
            val results = mutableListOf<Triple<AlertData, String, Int>>()

            val globalScopeSystemIds = SystemAlertSettings.selectAll().where {
                SystemAlertSettings.scope eq MonitorService.ALERT_SCOPE_GLOBAL
            }.map { it[SystemAlertSettings.system_id] }

            val systemScopedAlerts = if (globalScopeSystemIds.isEmpty()) {
                SystemAlerts.innerJoin(Systems)
                    .selectAll().where { SystemAlerts.enabled eq true }
                    .toList()
            } else {
                SystemAlerts.innerJoin(Systems)
                    .selectAll().where {
                        (SystemAlerts.enabled eq true) and
                        (SystemAlerts.system_id notInList globalScopeSystemIds)
                    }
                    .toList()
            }

            systemScopedAlerts.forEach { row ->
                results += Triple(
                    AlertData(
                        id = row[SystemAlerts.id],
                        systemId = row[SystemAlerts.system_id],
                        organizationId = row[SystemAlerts.organization_id],
                        metric = row[SystemAlerts.metric],
                        condition = row[SystemAlerts.condition],
                        threshold = row[SystemAlerts.threshold],
                        durationSeconds = row[SystemAlerts.duration_seconds],
                        enabled = row[SystemAlerts.enabled],
                        lastTriggeredAt = row[SystemAlerts.last_triggered_at],
                        createdAt = row[SystemAlerts.created_at],
                        scope = MonitorService.ALERT_SCOPE_SYSTEM,
                        templateAlertId = null
                    ),
                    row[Systems.name],
                    row[Systems.organization_id]
                )
            }

            if (globalScopeSystemIds.isNotEmpty()) {
                val globalTemplates = OrganizationAlertTemplates.selectAll().where {
                    OrganizationAlertTemplates.enabled eq true
                }.toList()

                if (globalTemplates.isNotEmpty()) {
                    val globalSystems = Systems.innerJoin(SystemAlertSettings)
                        .selectAll().where {
                            (SystemAlertSettings.scope eq MonitorService.ALERT_SCOPE_GLOBAL) and
                            (SystemAlertSettings.system_id inList globalScopeSystemIds)
                        }
                        .toList()

                    val templateIds = globalTemplates.map { it[OrganizationAlertTemplates.id] }
                    val stateMap = if (templateIds.isEmpty()) {
                        emptyMap()
                    } else {
                        SystemAlertTemplateStates.selectAll().where {
                            (SystemAlertTemplateStates.template_alert_id inList templateIds) and
                            (SystemAlertTemplateStates.system_id inList globalScopeSystemIds)
                        }.associate {
                            Pair(
                                it[SystemAlertTemplateStates.template_alert_id],
                                it[SystemAlertTemplateStates.system_id]
                            ) to it[SystemAlertTemplateStates.last_triggered_at]
                        }
                    }

                    globalSystems.forEach { systemRow ->
                        val systemId = systemRow[Systems.id]
                        val systemName = systemRow[Systems.name]
                        val orgId = systemRow[Systems.organization_id]

                        globalTemplates
                            .filter { template -> template[OrganizationAlertTemplates.organization_id] == orgId }
                            .forEach { template ->
                                val templateId = template[OrganizationAlertTemplates.id]
                                results += Triple(
                                    AlertData(
                                        id = templateId,
                                        systemId = systemId,
                                        organizationId = orgId,
                                        metric = template[OrganizationAlertTemplates.metric],
                                        condition = template[OrganizationAlertTemplates.condition],
                                        threshold = template[OrganizationAlertTemplates.threshold],
                                        durationSeconds = template[OrganizationAlertTemplates.duration_seconds],
                                        enabled = template[OrganizationAlertTemplates.enabled],
                                        lastTriggeredAt = stateMap[Pair(templateId, systemId)],
                                        createdAt = template[OrganizationAlertTemplates.created_at],
                                        scope = MonitorService.ALERT_SCOPE_GLOBAL,
                                        templateAlertId = templateId
                                    ),
                                    systemName,
                                    orgId
                                )
                            }
                    }
                }
            }

            results
        }
        
        logger.debug { "Evaluating ${alerts.size} alerts" }
        
        for ((alert, systemName, orgId) in alerts) {
            try {
                evaluateAlert(alert, systemName, orgId)
            } catch (e: Exception) {
                logger.error(e) { "Error evaluating alert ${alert.id}" }
            }
        }
    }
    
    /**
     * Evaluate a single alert.
     */
    private suspend fun evaluateAlert(alert: AlertData, systemName: String, organizationId: Int) {
        // Check if we should throttle this alert
        val now = Clock.System.now()
        if (alert.lastTriggeredAt != null) {
            val timeSinceLastTrigger = now - alert.lastTriggeredAt
            if (timeSinceLastTrigger < MIN_ALERT_INTERVAL_MINUTES.minutes) {
                return // Don't spam alerts
            }
        }
        
        // Check if alerts are silenced for this organization
        if (isAnySilenceActive(organizationId)) {
            return
        }
        
        // Get recent metrics for the system
        val currentValue = getCurrentMetricValue(alert.systemId, alert.metric) ?: return
        
        // Check if alert condition is met
        val triggered = when (alert.condition) {
            ">" -> currentValue > alert.threshold
            "<" -> currentValue < alert.threshold
            ">=" -> currentValue >= alert.threshold
            "<=" -> currentValue <= alert.threshold
            "==" -> currentValue == alert.threshold
            else -> false
        }
        
        if (!triggered) {
            return // Alert condition not met
        }
        
        // If duration is specified, check if condition has been true for that duration
        if (alert.durationSeconds > 0) {
            val isSustained = checkSustainedCondition(alert)
            if (!isSustained) {
                return // Condition not sustained for required duration
            }
        }
        
        // Trigger the alert
        logger.info { "Alert ${alert.id} triggered for system ${alert.systemId}: ${alert.metric} ${alert.condition} ${alert.threshold} (current: $currentValue)" }
        
        // Update last triggered timestamp
        if (alert.scope == MonitorService.ALERT_SCOPE_GLOBAL && alert.templateAlertId != null) {
            transaction {
                val existing = SystemAlertTemplateStates.selectAll().where {
                    (SystemAlertTemplateStates.template_alert_id eq alert.templateAlertId) and
                    (SystemAlertTemplateStates.system_id eq alert.systemId)
                }.firstOrNull()

                if (existing != null) {
                    SystemAlertTemplateStates.update({
                        (SystemAlertTemplateStates.template_alert_id eq alert.templateAlertId) and
                        (SystemAlertTemplateStates.system_id eq alert.systemId)
                    }) {
                        it[last_triggered_at] = now
                    }
                } else {
                    SystemAlertTemplateStates.insert {
                        it[SystemAlertTemplateStates.template_alert_id] = alert.templateAlertId
                        it[SystemAlertTemplateStates.system_id] = alert.systemId
                        it[SystemAlertTemplateStates.last_triggered_at] = now
                    }
                }
            }
        } else {
            transaction {
                SystemAlerts.update({ SystemAlerts.id eq alert.id }) {
                    it[last_triggered_at] = now
                }
            }
        }
        
        // Send notification
        sendAlertNotification(alert, systemName, organizationId, currentValue)
    }
    
    /**
     * Get the current value of a metric for a system.
     */
    private suspend fun getCurrentMetricValue(systemId: UUID, metric: String): Double? {
        val metricColumn = when (metric) {
            "cpu_percent" -> "cpu_percent"
            "mem_percent" -> "(mem_used / mem_total * 100)"
            "disk_percent" -> "(disk_used / disk_total * 100)"
            "load_1" -> "load_1"
            "load_5" -> "load_5"
            "load_15" -> "load_15"
            "temp_max" -> "temp_max"
            "gpu_percent" -> "gpu_percent"
            "battery_percent" -> "battery_percent"
            else -> return null
        }
        
        val query = """
            SELECT $metricColumn as value
            FROM $clickhouseDb.system_metrics
            WHERE system_id = toUUID('$systemId')
            ORDER BY timestamp DESC
            LIMIT 1
            FORMAT JSONCompact
        """.trimIndent()
        
        return try {
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
        } catch (e: Exception) {
            logger.error(e) { "Error fetching metric value" }
            null
        }
    }
    
    /**
     * Check if the alert condition has been sustained for the required duration.
     */
    private suspend fun checkSustainedCondition(alert: AlertData): Boolean {
        val metricColumn = when (alert.metric) {
            "cpu_percent" -> "cpu_percent"
            "mem_percent" -> "(mem_used / mem_total * 100)"
            "disk_percent" -> "(disk_used / disk_total * 100)"
            "load_1" -> "load_1"
            "load_5" -> "load_5"
            "load_15" -> "load_15"
            "temp_max" -> "temp_max"
            "gpu_percent" -> "gpu_percent"
            "battery_percent" -> "battery_percent"
            else -> return false
        }
        
        val conditionSql = when (alert.condition) {
            ">" -> "$metricColumn > ${alert.threshold}"
            "<" -> "$metricColumn < ${alert.threshold}"
            ">=" -> "$metricColumn >= ${alert.threshold}"
            "<=" -> "$metricColumn <= ${alert.threshold}"
            "==" -> "$metricColumn == ${alert.threshold}"
            else -> return false
        }
        
        val query = """
            SELECT count(*) as cnt
            FROM $clickhouseDb.system_metrics
            WHERE system_id = toUUID('${alert.systemId}')
              AND timestamp >= now() - INTERVAL ${alert.durationSeconds} SECOND
              AND $conditionSql
            FORMAT JSONCompact
        """.trimIndent()
        
        return try {
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
            val expectedDataPoints = alert.durationSeconds / 15 // Assuming 15s poll interval
            count >= expectedDataPoints * 0.8 // Allow 20% missing data points
        } catch (e: Exception) {
            logger.error(e) { "Error checking sustained condition" }
            false
        }
    }
    
    /**
     * Send alert notification via email.
     */
    private suspend fun sendAlertNotification(
        alert: AlertData,
        systemName: String,
        organizationId: Int,
        currentValue: Double
    ) {
        val prefsService = AlertNotificationPreferencesService()
        
        // Get users with email enabled for SYSTEM_ALERT
        val emailRecipients = prefsService.getUsersWithChannelEnabled(
            organizationId = organizationId,
            alertSource = "SYSTEM_ALERT",
            channel = "email"
        )
        
        val metricLabel = getMetricLabel(alert.metric)
        val subject = "⚠️ Alert: $systemName - $metricLabel ${alert.condition} ${alert.threshold}"
        
        val formattedValue = formatMetricValue(alert.metric, currentValue)
        val formattedThreshold = formatMetricValue(alert.metric, alert.threshold)
        
        // Send email notifications
        for ((_, email) in emailRecipients) {
            try {
                val htmlBody = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    </head>
                    <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                        <div style="background-color: #fef2f2; border-left: 4px solid #dc2626; padding: 30px; border-radius: 8px;">
                            <h1 style="color: #dc2626; margin-bottom: 20px;">⚠️ System Alert</h1>
                            <p><strong>System:</strong> $systemName</p>
                            <p><strong>Metric:</strong> $metricLabel</p>
                            <p><strong>Condition:</strong> ${alert.condition} $formattedThreshold</p>
                            <p><strong>Current Value:</strong> <span style="color: #dc2626; font-weight: bold;">$formattedValue</span></p>
                            ${if (alert.durationSeconds > 0) "<p><strong>Duration:</strong> ${alert.durationSeconds}s</p>" else ""}
                            <div style="margin: 30px 0;">
                                <a href="${config.property("email.frontendUrl").getString()}/monitoring/${alert.systemId}" style="display: inline-block; background-color: #dc2626; color: white; padding: 12px 30px; text-decoration: none; border-radius: 6px; font-weight: 500;">View System</a>
                            </div>
                            <hr style="border: none; border-top: 1px solid #ddd; margin: 30px 0;">
                            <p style="color: #999; font-size: 12px;">Moneat Server Monitoring</p>
                        </div>
                    </body>
                    </html>
                """.trimIndent()
                
                val textBody = """
                    ⚠️ System Alert
                    
                    System: $systemName
                    Metric: $metricLabel
                    Condition: ${alert.condition} $formattedThreshold
                    Current Value: $formattedValue
                    ${if (alert.durationSeconds > 0) "Duration: ${alert.durationSeconds}s" else ""}
                    
                    View system: ${config.property("email.frontendUrl").getString()}/monitoring/${alert.systemId}
                    
                    ---
                    Moneat Server Monitoring
                """.trimIndent()
                
                emailService.sendEmail(email, subject, htmlBody, textBody, "monitor_alert")
            } catch (e: Exception) {
                logger.error(e) { "Failed to send alert notification to $email" }
            }
        }
        
        // Check if Slack is enabled for any user in the org
        val slackEnabled = prefsService.getUsersWithChannelEnabled(
            organizationId = organizationId,
            alertSource = "SYSTEM_ALERT",
            channel = "slack"
        ).isNotEmpty()
        
        if (slackEnabled) {
            try {
                val baseUrl = config.property("email.frontendUrl").getString()
                slackService.sendSystemAlert(
                    organizationId = organizationId,
                    systemName = systemName,
                    metric = metricLabel,
                    condition = alert.condition,
                    threshold = formattedThreshold,
                    currentValue = formattedValue,
                    systemId = alert.systemId,
                    baseUrl = baseUrl
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to send Slack notification for system alert" }
            }
        }
        
        // Check if Discord is enabled for any user in the org
        val discordEnabled = prefsService.getUsersWithChannelEnabled(
            organizationId = organizationId,
            alertSource = "SYSTEM_ALERT",
            channel = "discord"
        ).isNotEmpty()
        
        if (discordEnabled) {
            try {
                val baseUrl = config.property("email.frontendUrl").getString()
                discordService.sendSystemAlert(
                    organizationId = organizationId,
                    systemName = systemName,
                    metric = metricLabel,
                    condition = alert.condition,
                    threshold = formattedThreshold,
                    currentValue = formattedValue,
                    systemId = alert.systemId,
                    baseUrl = baseUrl
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to send Discord notification for system alert" }
            }
        }
        
        // Fire incident alert
        try {
            val incidentSeverity = transaction {
                SystemAlerts.selectAll()
                    .where { SystemAlerts.id eq alert.id }
                    .firstOrNull()?.get(SystemAlerts.incident_severity)
                    ?.let { com.moneat.models.IncidentSeverity.fromString(it) }
            }
            
            if (incidentSeverity != null) {
                val frontendUrl = config.property("email.frontendUrl").getString()
                val incidentEvent = com.moneat.models.IncidentEvent(
                    title = "$systemName - $metricLabel ${alert.condition} ${alert.threshold}",
                    description = "Metric: $metricLabel\nCondition: ${alert.condition} $formattedThreshold\nCurrent Value: $formattedValue",
                    severity = incidentSeverity,
                    status = com.moneat.models.IncidentStatus.FIRING,
                    source = com.moneat.models.AlertSource.SYSTEM_ALERT,
                    deduplicationKey = "moneat-system-alert-${alert.id}",
                    organizationId = organizationId,
                    metadata = mapOf(
                        "system_id" to JsonPrimitive(alert.systemId.toString()),
                        "system_name" to JsonPrimitive(systemName),
                        "metric" to JsonPrimitive(alert.metric),
                        "current_value" to JsonPrimitive(formattedValue),
                        "threshold" to JsonPrimitive(formattedThreshold)
                    ),
                    moneatUrl = "$frontendUrl/monitoring/${alert.systemId}"
                )
                incidentService.fireAlert(incidentEvent)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fire incident alert" }
        }
    }
    
    /**
     * Check system statuses and send down/up notifications.
     */
    private suspend fun checkSystemStatuses() {
        val now = Clock.System.now()
        val downThreshold = now - SYSTEM_DOWN_THRESHOLD_SECONDS.seconds
        
        // Get all systems and check their last_seen_at
        val systems = transaction {
            Systems.selectAll().map { row ->
                Triple(
                    row[Systems.id],
                    row[Systems.name],
                    row[Systems.organization_id]
                ) to Pair(
                    row[Systems.status],
                    row[Systems.last_seen_at]
                )
            }
        }
        
        for ((systemInfo, statusInfo) in systems) {
            val (systemId, systemName, organizationId) = systemInfo
            val (currentStatus, lastSeenAt) = statusInfo
            
            val isDown = lastSeenAt == null || lastSeenAt < downThreshold
            
            // Skip pending systems that have never reported
            if (lastSeenAt == null && currentStatus == "pending") {
                continue
            }
            
            val newStatus = if (isDown) "down" else "up"
            
            // Only send notification if status changed
            if (currentStatus != newStatus) {
                logger.info { "System $systemId ($systemName) status changed: $currentStatus -> $newStatus" }
                
                // Update status in database
                transaction {
                    Systems.update({ Systems.id eq systemId }) {
                        it[status] = newStatus
                        it[updated_at] = now
                    }
                }
                
                // Skip notifications if alerts are silenced for this organization
                if (isAnySilenceActive(organizationId)) {
                    continue
                }
                
                // Send notification
                if (newStatus == "down") {
                    sendSystemDownNotification(systemId, systemName, organizationId, lastSeenAt)
                } else {
                    sendSystemUpNotification(systemId, systemName, organizationId)
                }
            }
        }
    }
    
    /**
     * Send system down notification.
     */
    private suspend fun sendSystemDownNotification(
        systemId: UUID,
        systemName: String,
        organizationId: Int,
        lastSeenAt: Instant?
    ) {
        val prefsService = AlertNotificationPreferencesService()
        
        // Get users with email enabled for SYSTEM_DOWN
        val emailRecipients = prefsService.getUsersWithChannelEnabled(
            organizationId = organizationId,
            alertSource = "SYSTEM_DOWN",
            channel = "email"
        )
        
        val lastSeenText = if (lastSeenAt != null) {
            val minutesAgo = ((Clock.System.now() - lastSeenAt).inWholeSeconds / 60).toInt()
            "Last seen $minutesAgo minutes ago"
        } else {
            "Never reported metrics"
        }
        
        val systemUrl = "${config.property("email.frontendUrl").getString()}/monitoring/$systemId"
        
        // Send email notifications
        for ((_, email) in emailRecipients) {
            try {
                emailService.sendSystemDownEmail(email, systemName, lastSeenText, systemUrl)
            } catch (e: Exception) {
                logger.error(e) { "Failed to send system down notification to $email" }
            }
        }
        
        // Check if Slack is enabled for any user in the org
        val slackEnabled = prefsService.getUsersWithChannelEnabled(
            organizationId = organizationId,
            alertSource = "SYSTEM_DOWN",
            channel = "slack"
        ).isNotEmpty()
        
        if (slackEnabled) {
            try {
                val baseUrl = config.property("email.frontendUrl").getString()
                slackService.sendSystemDown(
                    organizationId = organizationId,
                    systemName = systemName,
                    lastSeen = lastSeenText,
                    systemId = systemId,
                    baseUrl = baseUrl
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to send Slack notification for system down" }
            }
        }
        
        // Check if Discord is enabled for any user in the org
        val discordEnabled = prefsService.getUsersWithChannelEnabled(
            organizationId = organizationId,
            alertSource = "SYSTEM_DOWN",
            channel = "discord"
        ).isNotEmpty()
        
        if (discordEnabled) {
            try {
                val baseUrl = config.property("email.frontendUrl").getString()
                discordService.sendSystemDown(
                    organizationId = organizationId,
                    systemName = systemName,
                    lastSeen = lastSeenText,
                    systemId = systemId,
                    baseUrl = baseUrl
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to send Discord notification for system down" }
            }
        }
        
        // Fire incident alert for system down
        try {
            val frontendUrl = config.property("email.frontendUrl").getString()
            val incidentEvent = com.moneat.models.IncidentEvent(
                title = "System Down: $systemName",
                description = "The monitoring agent has stopped reporting metrics.\nStatus: $lastSeenText",
                severity = com.moneat.models.IncidentSeverity.CRITICAL,
                status = com.moneat.models.IncidentStatus.FIRING,
                source = com.moneat.models.AlertSource.SYSTEM_DOWN,
                deduplicationKey = "moneat-system-down-$systemId",
                organizationId = organizationId,
                metadata = mapOf(
                    "system_id" to JsonPrimitive(systemId.toString()),
                    "system_name" to JsonPrimitive(systemName),
                    "last_seen" to JsonPrimitive(lastSeenText)
                ),
                moneatUrl = "$frontendUrl/monitoring/$systemId"
            )
            incidentService.fireAlert(incidentEvent)
        } catch (e: Exception) {
            logger.error(e) { "Failed to fire incident alert for system down" }
        }
    }
    
    /**
     * Send system up notification.
     */
    private suspend fun sendSystemUpNotification(
        systemId: UUID,
        systemName: String,
        organizationId: Int
    ) {
        val recipients = transaction {
            Users.innerJoin(Memberships)
                .selectAll().where { Memberships.organization_id eq organizationId }
                .map { it[Users.email] }
        }
        
        val subject = "✅ System Recovered: $systemName"
        
        // Send email notifications
        for (recipient in recipients) {
            try {
                val htmlBody = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    </head>
                    <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                        <div style="background-color: #f0fdf4; border-left: 4px solid #16a34a; padding: 30px; border-radius: 8px;">
                            <h1 style="color: #16a34a; margin-bottom: 20px;">✅ System Recovered</h1>
                            <p><strong>System:</strong> $systemName</p>
                            <p>The system is now reporting metrics again.</p>
                            <div style="margin: 30px 0;">
                                <a href="${config.property("email.frontendUrl").getString()}/monitoring/$systemId" style="display: inline-block; background-color: #16a34a; color: white; padding: 12px 30px; text-decoration: none; border-radius: 6px; font-weight: 500;">View System</a>
                            </div>
                            <hr style="border: none; border-top: 1px solid #ddd; margin: 30px 0;">
                            <p style="color: #999; font-size: 12px;">Moneat Server Monitoring</p>
                        </div>
                    </body>
                    </html>
                """.trimIndent()
                
                val textBody = """
                    ✅ System Recovered
                    
                    System: $systemName
                    
                    The system is now reporting metrics again.
                    
                    View system: ${config.property("email.frontendUrl").getString()}/monitoring/$systemId
                    
                    ---
                    Moneat Server Monitoring
                """.trimIndent()
                
                emailService.sendEmail(recipient, subject, htmlBody, textBody, "system_up")
            } catch (e: Exception) {
                logger.error(e) { "Failed to send system up notification to $recipient" }
            }
        }
        
        // Send Slack notification
        try {
            val baseUrl = config.property("email.frontendUrl").getString()
            slackService.sendSystemUp(
                organizationId = organizationId,
                systemName = systemName,
                systemId = systemId,
                baseUrl = baseUrl
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to send Slack notification for system up" }
        }
        
        // Send Discord notification
        try {
            val baseUrl = config.property("email.frontendUrl").getString()
            discordService.sendSystemUp(
                organizationId = organizationId,
                systemName = systemName,
                systemId = systemId,
                baseUrl = baseUrl
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to send Discord notification for system up" }
        }
        
        // Resolve incident alert for system up
        try {
            incidentService.resolveAlert(
                organizationId = organizationId,
                source = com.moneat.models.AlertSource.SYSTEM_DOWN,
                deduplicationKey = "moneat-system-down-$systemId"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to resolve incident alert for system up" }
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
    
    private fun formatMetricValue(metric: String, value: Double): String {
        return when (metric) {
            "cpu_percent", "mem_percent", "disk_percent", "gpu_percent", "battery_percent" -> 
                String.format("%.1f%%", value)
            "temp_max" -> 
                String.format("%.1f°C", value)
            "load_1", "load_5", "load_15" -> 
                String.format("%.2f", value)
            else -> value.toString()
        }
    }
    
    // --- Silence Period Methods ---
    
    fun isAnySilenceActive(organizationId: Int): Boolean {
        val now = Clock.System.now()
        return transaction {
            AlertSilencePeriods.selectAll().where {
                (AlertSilencePeriods.organization_id eq organizationId) and
                (AlertSilencePeriods.starts_at lessEq now) and
                (AlertSilencePeriods.ends_at greaterEq now)
            }.count() > 0
        }
    }
    
    fun listSilencePeriods(organizationId: Int): List<SilencePeriodResponse> {
        return transaction {
            AlertSilencePeriods.selectAll().where {
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
    
    fun createSilencePeriod(organizationId: Int, userId: Int, request: CreateSilencePeriodRequest): SilencePeriodResponse {
        val startsAt = Instant.fromEpochMilliseconds(request.startsAt)
        val endsAt = Instant.fromEpochMilliseconds(request.endsAt)
        val now = Clock.System.now()
        
        return transaction {
            val id = AlertSilencePeriods.insert {
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
    
    fun deleteSilencePeriod(id: Int, organizationId: Int): Boolean {
        return transaction {
            AlertSilencePeriods.deleteWhere {
                (AlertSilencePeriods.id eq id) and
                (AlertSilencePeriods.organization_id eq organizationId)
            } > 0
        }
    }
    
    private fun cleanupExpiredSilencePeriods() {
        val now = Clock.System.now()
        val deleted = transaction {
            AlertSilencePeriods.deleteWhere {
                AlertSilencePeriods.ends_at lessEq now
            }
        }
        if (deleted > 0) {
            logger.info { "Cleaned up $deleted expired silence periods" }
        }
    }
}
