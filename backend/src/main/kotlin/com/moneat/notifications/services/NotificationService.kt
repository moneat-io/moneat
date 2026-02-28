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

package com.moneat.notifications.services

import com.moneat.config.ClickHouseClient
import com.moneat.events.models.SentryEvent
import com.moneat.incident.services.IncidentService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.NotificationPreferences
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

class NotificationService(private val emailService: EmailService) {
    private val config = ApplicationConfig("application.conf")
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val frontendUrl = config.property("email.frontendUrl").getString()
    private val json = Json { ignoreUnknownKeys = true }
    private val slackService = SlackService()
    private val discordService = DiscordService()
    private val incidentService =
        IncidentService()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Rate limiting: track last alert time per (user, project)
    private val lastAlertTimes = ConcurrentHashMap<Pair<Int, Long>, Instant>()

    // Weekly summary scheduler
    private val scheduler = Executors.newScheduledThreadPool(1)

    init {
        scheduleWeeklySummary()
    }

    suspend fun onNewIssue(
        projectId: Long,
        issueId: String,
        event: SentryEvent
    ) {
        try {
            logger.info { "Processing new issue alert for issue=$issueId project=$projectId" }

            // Get project details
            val project =
                transaction {
                    Projects.selectAll().where { Projects.id eq projectId }.firstOrNull()
                } ?: run {
                    logger.warn { "Project $projectId not found" }
                    return
                }

            val projectName = project[Projects.name]
            val orgId = project[Projects.organization_id]

            // Use project/global NotificationPreferences for issue email eligibility.
            val orgUsers =
                transaction {
                    Memberships
                        .innerJoin(Users)
                        .selectAll()
                        .where {
                            (Memberships.organization_id eq orgId) and
                                (Users.email_verified eq true)
                        }.map {
                            Triple(it[Users.id], it[Users.email], it[Users.name])
                        }
                }
            val usersToNotify =
                orgUsers.mapNotNull { (userId, email, userName) ->
                    val prefs = getPreferences(userId, projectId)
                    if (!prefs.issueAlerts || !prefs.errorAlerts) {
                        return@mapNotNull null
                    }

                    // Check rate limiting
                    val key = Pair(userId, projectId)
                    val lastAlert = lastAlertTimes[key]
                    val now = Instant.now()
                    if (lastAlert != null) {
                        val minutesSince = Duration.between(lastAlert, now).toMinutes()
                        if (minutesSince < prefs.alertFrequencyMinutes) {
                            logger.debug { "Rate limiting alert for user=$userId project=$projectId" }
                            return@mapNotNull null
                        }
                    }

                    // Update last alert time
                    lastAlertTimes[key] = now
                    Pair(email, userName)
                }

            if (usersToNotify.isEmpty()) {
                logger.debug { "No users to notify for issue $issueId" }
                return
            }

            // Build email data
            val issueUrl = "$frontendUrl/issues/$issueId"
            val settingsUrl = "$frontendUrl/settings/notifications"

            // Derive culprit from exception or use first frame
            val culprit =
                event.exception
                    ?.values
                    ?.firstOrNull()
                    ?.stacktrace
                    ?.frames
                    ?.firstOrNull()
                    ?.let { frame -> "${frame.filename}:${frame.function ?: "unknown"}" }
                    ?: "unknown"

            val stackTrace =
                event.exception
                    ?.values
                    ?.firstOrNull()
                    ?.stacktrace
                    ?.frames
                    ?.takeLast(5)
                    ?.joinToString("\n") { frame ->
                        "  at ${frame.function ?: "unknown"} (${frame.filename}:${frame.lineno})"
                    } ?: "No stack trace available"

            val emailData =
                EmailService.ErrorAlertData(
                    issueTitle =
                    event.message ?: event.exception
                        ?.values
                        ?.firstOrNull()
                        ?.value ?: "Unknown error",
                    issueLevel = event.level ?: "error",
                    issueCulprit = culprit,
                    issueMessage =
                    event.message ?: event.exception
                        ?.values
                        ?.firstOrNull()
                        ?.value ?: "",
                    issueCount = "1",
                    issueUrl = issueUrl,
                    projectName = projectName,
                    environment = event.environment ?: "production",
                    timestamp =
                    event.timestamp?.let {
                        java.time.Instant
                            .ofEpochMilli((it * 1000).toLong())
                            .toString()
                    } ?: java.time.Instant
                        .now()
                        .toString(),
                    stackTrace = stackTrace,
                    settingsUrl = settingsUrl,
                    unsubscribeUrl = "$settingsUrl?project=$projectId"
                )

            // Send emails asynchronously
            usersToNotify.forEach { (email, _) ->
                scope.launch {
                    try {
                        emailService.sendErrorAlertEmail(email, emailData)
                        logger.info { "Sent issue alert to $email for issue $issueId" }
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to send issue alert to $email" }
                    }
                }
            }

            // Check if Slack is enabled for any user in the org
            val prefsService = AlertNotificationPreferencesService()
            val slackEnabled =
                runCatching {
                    prefsService
                        .getUsersWithChannelEnabled(
                            organizationId = orgId,
                            alertSource = "ERROR_ALERT",
                            channel = "slack"
                        ).isNotEmpty()
                }.getOrElse { e ->
                    logger.warn(e) { "Unable to evaluate Slack alert preferences for org=$orgId" }
                    false
                }

            if (slackEnabled) {
                try {
                    slackService.sendErrorAlert(
                        organizationId = orgId,
                        projectName = projectName,
                        issueTitle = emailData.issueTitle,
                        level = emailData.issueLevel,
                        culprit = culprit,
                        issueId = issueId.toLongOrNull() ?: 0L,
                        projectId = projectId,
                        baseUrl = frontendUrl,
                        occurrenceCount = 1,
                        environment = emailData.environment,
                        timestamp = emailData.timestamp,
                        stackTrace = stackTrace
                    )
                } catch (e: Exception) {
                    logger.error(e) { "Failed to send Slack notification for new issue" }
                }
            }

            // Check if Discord is enabled for any user in the org
            val discordEnabled =
                runCatching {
                    prefsService
                        .getUsersWithChannelEnabled(
                            organizationId = orgId,
                            alertSource = "ERROR_ALERT",
                            channel = "discord"
                        ).isNotEmpty()
                }.getOrElse { e ->
                    logger.warn(e) { "Unable to evaluate Discord alert preferences for org=$orgId" }
                    false
                }

            if (discordEnabled) {
                try {
                    val discordIssueUrl = "$frontendUrl/issues/$issueId"
                    discordService.sendErrorAlert(
                        organizationId = orgId,
                        projectName = projectName,
                        issueTitle = emailData.issueTitle,
                        level = emailData.issueLevel,
                        firstSeen = emailData.timestamp,
                        eventCount = 1,
                        userCount = 0,
                        issueUrl = discordIssueUrl
                    )
                } catch (e: Exception) {
                    logger.error(e) { "Failed to send Discord notification for new issue" }
                }
            }

            // Fire incident alert for error
            try {
                val severityFromLevel =
                    when (emailData.issueLevel.lowercase()) {
                        "fatal", "critical" -> com.moneat.incident.models.IncidentSeverity.CRITICAL
                        "error" -> com.moneat.incident.models.IncidentSeverity.HIGH
                        "warning" -> com.moneat.incident.models.IncidentSeverity.MEDIUM
                        else -> com.moneat.incident.models.IncidentSeverity.LOW
                    }

                val incidentEvent =
                    com.moneat.incident.models.IncidentEvent(
                        title = "[$projectName] ${emailData.issueTitle}",
                        description = "Level: ${emailData.issueLevel}\nEnvironment: ${emailData.environment}\nCulprit: $culprit\n\nStack trace:\n$stackTrace",
                        severity = severityFromLevel,
                        status = com.moneat.incident.models.IncidentStatus.FIRING,
                        source = com.moneat.incident.models.AlertSource.ERROR_ALERT,
                        deduplicationKey = "moneat-error-$projectId-$issueId",
                        organizationId = orgId,
                        metadata =
                        mapOf(
                            "project_id" to JsonPrimitive(projectId.toString()),
                            "project_name" to JsonPrimitive(projectName),
                            "issue_id" to JsonPrimitive(issueId),
                            "level" to JsonPrimitive(emailData.issueLevel),
                            "environment" to JsonPrimitive(emailData.environment),
                            "culprit" to JsonPrimitive(culprit)
                        ),
                        moneatUrl = issueUrl
                    )
                incidentService.fireAlert(incidentEvent)
            } catch (e: Exception) {
                logger.error(e) { "Failed to fire incident alert for error" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error in onNewIssue handler" }
        }
    }

    suspend fun sendWeeklySummary() {
        try {
            logger.info { "Starting weekly summary generation" }

            val now = Instant.now()
            val endDate = now
            val startDate = now.minus(Duration.ofDays(7))
            val priorStartDate = startDate.minus(Duration.ofDays(7))

            // Get all users with weekly summary enabled
            val usersToNotify =
                transaction {
                    Users
                        .selectAll()
                        .where { Users.email_verified eq true }
                        .mapNotNull { user ->
                            val userId = user[Users.id]
                            val prefs = getPreferences(userId, null)
                            if (!prefs.weeklySummary) return@mapNotNull null

                            Triple(userId, user[Users.email], user[Users.name])
                        }
                }

            logger.info { "Sending weekly summaries to ${usersToNotify.size} users" }

            usersToNotify.forEach { (userId, email, userName) ->
                scope.launch {
                    try {
                        sendUserWeeklySummary(userId, email, userName, startDate, endDate, priorStartDate)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to send weekly summary to $email" }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error in sendWeeklySummary" }
        }
    }

    private suspend fun sendUserWeeklySummary(
        userId: Int,
        email: String,
        @Suppress("UNUSED_PARAMETER") userName: String?,
        startDate: Instant,
        endDate: Instant,
        priorStartDate: Instant
    ) {
        // Get user's projects
        val projects =
            transaction {
                val orgIds =
                    Memberships
                        .selectAll()
                        .where { Memberships.user_id eq userId }
                        .map { it[Memberships.organization_id] }

                Projects
                    .selectAll()
                    .where { Projects.organization_id inList orgIds }
                    .map { Pair(it[Projects.id], it[Projects.name]) }
            }

        if (projects.isEmpty()) {
            logger.debug { "User $userId has no projects, skipping summary" }
            return
        }

        val projectIds = projects.map { it.first }

        // Query ClickHouse for stats
        val currentStats = getStatsForPeriod(projectIds, startDate, endDate)
        val priorStats = getStatsForPeriod(projectIds, priorStartDate, startDate)

        val totalEvents = currentStats.totalEvents
        val eventsTrend = calculateTrend(currentStats.totalEvents, priorStats.totalEvents)
        val newIssues = currentStats.uniqueIssues
        val issuesTrend = calculateTrend(currentStats.uniqueIssues, priorStats.uniqueIssues)
        val affectedUsers = currentStats.uniqueUsers
        val usersTrend = calculateTrend(currentStats.uniqueUsers, priorStats.uniqueUsers)

        // Get top issues
        val topIssues = getTopIssues(projectIds, startDate, endDate, limit = 5)

        // Get per-project breakdown
        val projectSummaries =
            projects.map { (projectId, projectName) ->
                val stats = getStatsForPeriod(listOf(projectId), startDate, endDate)
                EmailService.ProjectSummary(
                    name = projectName,
                    events = formatNumber(stats.totalEvents),
                    issues = formatNumber(stats.uniqueIssues),
                    crashFree = "99.5" // TODO: Calculate actual crash-free rate
                )
            }

        val emailData =
            EmailService.WeeklySummaryData(
                startDate = formatDate(startDate),
                endDate = formatDate(endDate),
                totalEvents = formatNumber(totalEvents),
                eventsTrend = eventsTrend,
                newIssues = formatNumber(newIssues),
                issuesTrend = issuesTrend,
                affectedUsers = formatNumber(affectedUsers),
                usersTrend = usersTrend,
                topIssues = topIssues,
                projects = projectSummaries,
                dashboardUrl = frontendUrl,
                settingsUrl = "$frontendUrl/settings/notifications",
                unsubscribeUrl = "$frontendUrl/settings/notifications"
            )

        emailService.sendWeeklySummaryEmail(email, emailData)
        logger.info { "Sent weekly summary to $email" }
    }

    private data class PeriodStats(
        val totalEvents: Long,
        val uniqueIssues: Long,
        val uniqueUsers: Long
    )

    private suspend fun getStatsForPeriod(
        projectIds: List<Long>,
        startDate: Instant,
        endDate: Instant
    ): PeriodStats {
        val startMs = startDate.toEpochMilli()
        val endMs = endDate.toEpochMilli()

        val query =
            """
            SELECT 
                count() as total_events,
                uniq(issue_id) as unique_issues,
                uniq(user_id) as unique_users
            FROM $clickhouseDb.events
            WHERE project_id IN (${projectIds.joinToString(",")})
              AND timestamp >= fromUnixTimestamp64Milli($startMs)
              AND timestamp < fromUnixTimestamp64Milli($endMs)
            FORMAT JSON
            """.trimIndent()

        val response = ClickHouseClient.execute(query)
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            logger.error("ClickHouse query failed in getStatsForPeriod: ${response.status} - ${responseBody.take(500)}")
            return PeriodStats(totalEvents = 0, uniqueIssues = 0, uniqueUsers = 0)
        }
        val jsonResponse = Json.parseToJsonElement(responseBody).jsonObject
        val data = jsonResponse["data"]?.jsonArray?.firstOrNull()?.jsonObject

        return PeriodStats(
            totalEvents = data?.get("total_events")?.jsonPrimitive?.longOrNull ?: 0,
            uniqueIssues = data?.get("unique_issues")?.jsonPrimitive?.longOrNull ?: 0,
            uniqueUsers = data?.get("unique_users")?.jsonPrimitive?.longOrNull ?: 0
        )
    }

    private suspend fun getTopIssues(
        projectIds: List<Long>,
        startDate: Instant,
        endDate: Instant,
        limit: Int
    ): List<EmailService.TopIssue> {
        val startMs = startDate.toEpochMilli()
        val endMs = endDate.toEpochMilli()

        val query =
            """
            SELECT 
                issue_id,
                any(message) as title,
                any(culprit) as culprit,
                any(project_id) as project_id,
                count() as event_count
            FROM $clickhouseDb.events
            WHERE project_id IN (${projectIds.joinToString(",")})
              AND timestamp >= fromUnixTimestamp64Milli($startMs)
              AND timestamp < fromUnixTimestamp64Milli($endMs)
              AND issue_id != ''
            GROUP BY issue_id
            ORDER BY event_count DESC
            LIMIT $limit
            FORMAT JSON
            """.trimIndent()

        val response = ClickHouseClient.execute(query)
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            logger.error("ClickHouse query failed in getTopIssues: ${response.status} - ${responseBody.take(500)}")
            return emptyList()
        }
        val jsonResponse = Json.parseToJsonElement(responseBody).jsonObject
        val rows = jsonResponse["data"]?.jsonArray ?: return emptyList()

        // Get project names
        val projectMap =
            transaction {
                Projects
                    .selectAll()
                    .where { Projects.id inList projectIds }
                    .associate { it[Projects.id] to it[Projects.name] }
            }

        return rows.mapNotNull { row ->
            val obj = row.jsonObject
            val projectId = obj["project_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            EmailService.TopIssue(
                title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Unknown error",
                culprit = obj["culprit"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                project = projectMap[projectId] ?: "Unknown",
                count = formatNumber(obj["event_count"]?.jsonPrimitive?.longOrNull ?: 0)
            )
        }
    }

    private data class NotificationPrefs(
        val issueAlerts: Boolean,
        val errorAlerts: Boolean,
        val weeklySummary: Boolean,
        val alertFrequencyMinutes: Int
    )

    private fun getPreferences(
        userId: Int,
        projectId: Long?
    ): NotificationPrefs {
        return transaction {
            // First check for project-specific override
            val projectPrefs =
                if (projectId != null) {
                    NotificationPreferences
                        .selectAll()
                        .where {
                            (NotificationPreferences.user_id eq userId) and
                                (NotificationPreferences.project_id eq projectId)
                        }.firstOrNull()
                } else {
                    null
                }

            // Fall back to global preferences
            val prefs =
                projectPrefs ?: NotificationPreferences
                    .selectAll()
                    .where {
                        (NotificationPreferences.user_id eq userId) and
                            (NotificationPreferences.project_id.isNull())
                    }.firstOrNull()

            if (prefs != null) {
                NotificationPrefs(
                    issueAlerts = prefs[NotificationPreferences.issue_alerts],
                    errorAlerts = prefs[NotificationPreferences.error_alerts],
                    weeklySummary = prefs[NotificationPreferences.weekly_summary],
                    alertFrequencyMinutes = prefs[NotificationPreferences.alert_frequency_minutes]
                )
            } else {
                // Defaults
                NotificationPrefs(
                    issueAlerts = true,
                    errorAlerts = true,
                    weeklySummary = true,
                    alertFrequencyMinutes = 30
                )
            }
        }
    }

    private fun scheduleWeeklySummary() {
        // Schedule to run every Monday at 9:00 AM UTC
        val now = ZonedDateTime.now(ZoneId.of("UTC"))
        var nextRun =
            now
                .with(DayOfWeek.MONDAY)
                .withHour(9)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)

        // If we've already passed Monday 9am this week, schedule for next week
        if (nextRun.isBefore(now)) {
            nextRun = nextRun.plusWeeks(1)
        }

        val initialDelay = Duration.between(now, nextRun).toMillis()
        val period = Duration.ofDays(7).toMillis()

        logger.info { "Scheduling weekly summary for $nextRun" }

        scheduler.scheduleAtFixedRate(
            {
                runBlocking {
                    try {
                        sendWeeklySummary()
                    } catch (e: Exception) {
                        logger.error(e) { "Error in scheduled weekly summary" }
                    }
                }
            },
            initialDelay,
            period,
            TimeUnit.MILLISECONDS
        )
    }

    private fun calculateTrend(
        current: Long,
        previous: Long
    ): Int {
        if (previous == 0L) return if (current > 0) 100 else 0
        return ((current - previous) * 100 / previous).toInt()
    }

    private fun formatNumber(num: Long): String {
        return when {
            num >= 1_000_000 -> String.format("%.1fM", num / 1_000_000.0)
            num >= 1_000 -> String.format("%.1fK", num / 1_000.0)
            else -> num.toString()
        }
    }

    private fun formatTimestamp(timestamp: String): String {
        return try {
            val instant = Instant.parse(timestamp)
            val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm 'UTC'")
            formatter.format(instant.atZone(ZoneId.of("UTC")))
        } catch (e: Exception) {
            timestamp
        }
    }

    private fun formatDate(instant: Instant): String {
        val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy")
        return formatter.format(instant.atZone(ZoneId.of("UTC")))
    }

    fun shutdown() {
        scheduler.shutdown()
    }
}
