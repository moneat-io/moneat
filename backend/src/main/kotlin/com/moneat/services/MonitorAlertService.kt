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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
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
    
    private var evaluationJob: Job? = null
    private var statusCheckJob: Job? = null
    
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
        
        logger.info { "MonitorAlertService background jobs started" }
    }
    
    /**
     * Stop the background jobs.
     */
    fun stop() {
        logger.info { "Stopping MonitorAlertService background jobs" }
        evaluationJob?.cancel()
        statusCheckJob?.cancel()
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
    private fun sendAlertNotification(
        alert: AlertData,
        systemName: String,
        organizationId: Int,
        currentValue: Double
    ) {
        // Get all users in the organization who should receive alerts
        val recipients = transaction {
            Users.innerJoin(Memberships)
                .selectAll().where { Memberships.organization_id eq organizationId }
                .map { it[Users.email] }
        }
        
        val metricLabel = getMetricLabel(alert.metric)
        val subject = "⚠️ Alert: $systemName - $metricLabel ${alert.condition} ${alert.threshold}"
        
        val formattedValue = formatMetricValue(alert.metric, currentValue)
        val formattedThreshold = formatMetricValue(alert.metric, alert.threshold)
        
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
                
                emailService.sendEmail(recipient, subject, htmlBody, textBody, "monitor_alert")
            } catch (e: Exception) {
                logger.error(e) { "Failed to send alert notification to $recipient" }
            }
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
    private fun sendSystemDownNotification(
        systemId: UUID,
        systemName: String,
        organizationId: Int,
        lastSeenAt: Instant?
    ) {
        val recipients = transaction {
            Users.innerJoin(Memberships)
                .selectAll().where { Memberships.organization_id eq organizationId }
                .map { it[Users.email] }
        }
        
        val subject = "🔴 System Down: $systemName"
        val lastSeenText = if (lastSeenAt != null) {
            val minutesAgo = ((Clock.System.now() - lastSeenAt).inWholeSeconds / 60).toInt()
            "Last seen $minutesAgo minutes ago"
        } else {
            "Never reported metrics"
        }
        
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
                        <div style="background-color: #fef2f2; border-left: 4px solid #dc2626; padding: 30px; border-radius: 8px;">
                            <h1 style="color: #dc2626; margin-bottom: 20px;">🔴 System Down</h1>
                            <p><strong>System:</strong> $systemName</p>
                            <p><strong>Status:</strong> $lastSeenText</p>
                            <p>The monitoring agent has stopped reporting metrics. Please check if the system is online and the agent is running.</p>
                            <div style="margin: 30px 0;">
                                <a href="${config.property("email.frontendUrl").getString()}/monitoring/$systemId" style="display: inline-block; background-color: #dc2626; color: white; padding: 12px 30px; text-decoration: none; border-radius: 6px; font-weight: 500;">View System</a>
                            </div>
                            <hr style="border: none; border-top: 1px solid #ddd; margin: 30px 0;">
                            <p style="color: #999; font-size: 12px;">Moneat Server Monitoring</p>
                        </div>
                    </body>
                    </html>
                """.trimIndent()
                
                val textBody = """
                    🔴 System Down
                    
                    System: $systemName
                    Status: $lastSeenText
                    
                    The monitoring agent has stopped reporting metrics. Please check if the system is online and the agent is running.
                    
                    View system: ${config.property("email.frontendUrl").getString()}/monitoring/$systemId
                    
                    ---
                    Moneat Server Monitoring
                """.trimIndent()
                
                emailService.sendEmail(recipient, subject, htmlBody, textBody, "system_down")
            } catch (e: Exception) {
                logger.error(e) { "Failed to send system down notification to $recipient" }
            }
        }
    }
    
    /**
     * Send system up notification.
     */
    private fun sendSystemUpNotification(
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
}
