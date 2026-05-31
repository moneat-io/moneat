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
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertSeverity
import com.moneat.alerts.models.AlertStatus
import com.moneat.monitoring.OperationalMetrics
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.NotificationPreferences
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.services.WorkflowService
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

private const val STACK_FRAMES_COUNT = 5
private const val EPOCH_SECONDS_TO_MILLIS = 1000
private const val WEEKLY_SUMMARY_DAYS = 7L
private const val ERROR_BODY_PREVIEW_CHARS = 500
private const val WEEKLY_SUMMARY_HOUR = 9
private const val FULL_PERCENTAGE = 100
private const val MILLION = 1_000_000L
private const val THOUSAND = 1_000L
private const val WEEKLY_SUMMARY_JOB_NAME = "weekly_summary"

class NotificationService(
    private val emailService: EmailService,
    private val workflowService: WorkflowService = WorkflowService(),
) {
    enum class WeeklySummaryResult { SENT, SKIPPED, FAILED }
    private val config = ApplicationConfig("application.conf")
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val frontendUrl = config.property("email.frontendUrl").getString()
    private val json = Json { ignoreUnknownKeys = true }

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
        suspendRunCatching {
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
                    ?.takeLast(STACK_FRAMES_COUNT)
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
                            .ofEpochMilli((it * EPOCH_SECONDS_TO_MILLIS).toLong())
                            .toString()
                    } ?: java.time.Instant
                        .now()
                        .toString(),
                    stackTrace = stackTrace,
                    settingsUrl = settingsUrl,
                    unsubscribeUrl = "$settingsUrl?project=$projectId"
                )

            workflowService.publishAlertTriggered(
                AlertLifecycleEvent(
                    title = "New Issue: ${emailData.issueTitle}",
                    description = "${emailData.projectName} reported ${emailData.issueLevel.uppercase()}: " +
                        emailData.issueMessage,
                    severity = severityForIssueLevel(emailData.issueLevel),
                    status = AlertStatus.FIRING,
                    source = AlertSource.ERROR_ALERT,
                    deduplicationKey = "moneat-error-$issueId",
                    organizationId = orgId,
                    moneatUrl = issueUrl
                )
            )
        }.getOrElse { e ->
            logger.error(e) { "Error in onNewIssue handler" }
        }
    }

    suspend fun sendWeeklySummary() {
        val startedAt = Instant.now()
        val result = suspendRunCatching {
            logger.info { "Starting weekly summary generation" }

            val now = Instant.now()
            val endDate = now
            val startDate = now.minus(Duration.ofDays(WEEKLY_SUMMARY_DAYS))
            val priorStartDate = startDate.minus(Duration.ofDays(WEEKLY_SUMMARY_DAYS))

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

                            Pair(userId, user[Users.email])
                        }
                }

            logger.info { "Sending weekly summaries to ${usersToNotify.size} users" }

            var sentCount = 0
            var skippedCount = 0
            var failedCount = 0

            for ((userId, email) in usersToNotify) {
                val result = suspendRunCatching {
                    sendUserWeeklySummary(userId, email, startDate, endDate, priorStartDate)
                }.getOrElse { e ->
                    logger.error(e) { "Failed to send weekly summary to $email" }
                    WeeklySummaryResult.FAILED
                }
                when (result) {
                    WeeklySummaryResult.SENT -> sentCount++
                    WeeklySummaryResult.SKIPPED -> skippedCount++
                    WeeklySummaryResult.FAILED -> failedCount++
                }
            }

            logger.info {
                "Weekly summary complete: $sentCount sent," +
                    " $skippedCount skipped, $failedCount failed" +
                    " out of ${usersToNotify.size} users"
            }
            if (failedCount > 0) {
                logger.error {
                    "Weekly summary failed to send to $failedCount users" +
                        " out of ${usersToNotify.size} eligible users"
                }
            }
            failedCount == 0
        }

        result.onFailure { e ->
            logger.error(e) { "Error in sendWeeklySummary" }
        }
        OperationalMetrics.recordBackgroundJobRun(
            WEEKLY_SUMMARY_JOB_NAME,
            result.getOrDefault(false),
            Duration.between(startedAt, Instant.now()).toMillis().toDouble() / EPOCH_SECONDS_TO_MILLIS,
            result.exceptionOrNull()
        )
    }

    suspend fun sendWeeklySummaryForUser(
        userId: Int,
        email: String
    ): WeeklySummaryResult {
        val now = Instant.now()
        val endDate = now
        val startDate = now.minus(Duration.ofDays(WEEKLY_SUMMARY_DAYS))
        val priorStartDate = startDate.minus(Duration.ofDays(WEEKLY_SUMMARY_DAYS))
        return sendUserWeeklySummary(userId, email, startDate, endDate, priorStartDate)
    }

    private suspend fun sendUserWeeklySummary(
        userId: Int,
        email: String,
        startDate: Instant,
        endDate: Instant,
        priorStartDate: Instant
    ): WeeklySummaryResult {
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
            return WeeklySummaryResult.SKIPPED
        }

        val projectIds = projects.map { it.first }

        val currentStats = getStatsForPeriod(projectIds, startDate, endDate)
        val priorStats = getStatsForPeriod(projectIds, priorStartDate, startDate)

        if (currentStats == null) {
            logger.error { "Weekly summary failed to send to $email: ClickHouse stats query failed" }
            return WeeklySummaryResult.FAILED
        }

        val totalEvents = currentStats.totalEvents
        val eventsTrend = priorStats?.let {
            calculateTrend(currentStats.totalEvents, it.totalEvents)
        }
        val newIssues = currentStats.uniqueIssues
        val issuesTrend = priorStats?.let {
            calculateTrend(currentStats.uniqueIssues, it.uniqueIssues)
        }
        val affectedUsers = currentStats.uniqueUsers
        val usersTrend = priorStats?.let {
            calculateTrend(currentStats.uniqueUsers, it.uniqueUsers)
        }

        val topIssues = getTopIssues(projectIds, startDate, endDate, limit = 5)
        if (topIssues == null) {
            logger.error {
                "Weekly summary failed to send to $email: ClickHouse top issues query failed"
            }
            return WeeklySummaryResult.FAILED
        }

        val perProjectStats = getPerProjectStats(projectIds, startDate, endDate)
        if (perProjectStats == null) {
            logger.error {
                "Weekly summary failed to send to $email: ClickHouse per-project stats query failed"
            }
            return WeeklySummaryResult.FAILED
        }

        val projectSummaries =
            projects.map { (projectId, projectName) ->
                val stats = perProjectStats[projectId]
                val crashFreeRate = getCrashFreeRate(projectId, startDate, endDate)
                EmailService.ProjectSummary(
                    name = projectName,
                    events = formatNumber(stats?.totalEvents ?: 0),
                    issues = formatNumber(stats?.uniqueIssues ?: 0),
                    crashFree = crashFreeRate?.let {
                        String.format(Locale.US, "%.1f%%", it)
                    } ?: "N/A"
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
        return WeeklySummaryResult.SENT
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
    ): PeriodStats? {
        val startMs = startDate.toEpochMilli()
        val endMs = endDate.toEpochMilli()

        val query =
            """
            SELECT 
                count() as total_events,
                uniqIf(issue_id, issue_id != '') as unique_issues,
                uniqIf(user_id, user_id != '') as unique_users
            FROM `$clickhouseDb`.events
            WHERE project_id IN (${projectIds.joinToString(",")})
              AND timestamp >= fromUnixTimestamp64Milli($startMs)
              AND timestamp < fromUnixTimestamp64Milli($endMs)
            FORMAT JSON
            """.trimIndent()

        val response = ClickHouseClient.execute(query)
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val preview = responseBody.take(ERROR_BODY_PREVIEW_CHARS)
            logger.error("ClickHouse query failed in getStatsForPeriod: ${response.status} - $preview")
            return null
        }
        val jsonResponse = Json.parseToJsonElement(responseBody).jsonObject
        val data = jsonResponse["data"]?.jsonArray?.firstOrNull()?.jsonObject

        return PeriodStats(
            totalEvents = data?.get("total_events")?.jsonPrimitive?.longOrNull ?: 0,
            uniqueIssues = data?.get("unique_issues")?.jsonPrimitive?.longOrNull ?: 0,
            uniqueUsers = data?.get("unique_users")?.jsonPrimitive?.longOrNull ?: 0
        )
    }

    private suspend fun getPerProjectStats(
        projectIds: List<Long>,
        startDate: Instant,
        endDate: Instant
    ): Map<Long, PeriodStats>? {
        val startMs = startDate.toEpochMilli()
        val endMs = endDate.toEpochMilli()

        val query =
            """
            SELECT 
                project_id,
                count() as total_events,
                uniqIf(issue_id, issue_id != '') as unique_issues,
                uniqIf(user_id, user_id != '') as unique_users
            FROM `$clickhouseDb`.events
            WHERE project_id IN (${projectIds.joinToString(",")})
              AND timestamp >= fromUnixTimestamp64Milli($startMs)
              AND timestamp < fromUnixTimestamp64Milli($endMs)
            GROUP BY project_id
            FORMAT JSON
            """.trimIndent()

        val response = ClickHouseClient.execute(query)
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val preview = responseBody.take(ERROR_BODY_PREVIEW_CHARS)
            logger.error(
                "ClickHouse query failed in getPerProjectStats:" +
                    " ${response.status} - $preview"
            )
            return null
        }
        val jsonResponse = Json.parseToJsonElement(responseBody).jsonObject
        val rows = jsonResponse["data"]?.jsonArray ?: return emptyMap()

        return rows.mapNotNull { row ->
            val obj = row.jsonObject
            val projectId =
                obj["project_id"]?.jsonPrimitive?.longOrNull
                    ?: return@mapNotNull null
            projectId to PeriodStats(
                totalEvents = obj["total_events"]?.jsonPrimitive?.longOrNull ?: 0,
                uniqueIssues = obj["unique_issues"]?.jsonPrimitive?.longOrNull ?: 0,
                uniqueUsers = obj["unique_users"]?.jsonPrimitive?.longOrNull ?: 0
            )
        }.toMap()
    }

    private suspend fun getCrashFreeRate(
        projectId: Long,
        startDate: Instant,
        endDate: Instant
    ): Double? {
        val startMs = startDate.toEpochMilli()
        val endMs = endDate.toEpochMilli()

        val query =
            """
            SELECT countIf(errors = 0) * 100.0 / count() as rate
            FROM `$clickhouseDb`.sessions
            WHERE project_id = $projectId
              AND started >= fromUnixTimestamp64Milli($startMs)
              AND started < fromUnixTimestamp64Milli($endMs)
            FORMAT JSON
            """.trimIndent()

        return suspendRunCatching {
            val response = ClickHouseClient.execute(query)
            val responseBody = response.bodyAsText()
            if (!response.status.isSuccess()) return null
            val jsonResponse = Json.parseToJsonElement(responseBody).jsonObject
            val data = jsonResponse["data"]?.jsonArray?.firstOrNull()?.jsonObject
            val rate = data?.get("rate")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            if (rate == null || rate.isNaN() || rate.isInfinite()) null else rate
        }.getOrElse { e ->
            logger.warn(e) { "Failed to get crash-free rate for project $projectId" }
            null
        }
    }

    private suspend fun getTopIssues(
        projectIds: List<Long>,
        startDate: Instant,
        endDate: Instant,
        limit: Int
    ): List<EmailService.TopIssue>? {
        val startMs = startDate.toEpochMilli()
        val endMs = endDate.toEpochMilli()

        val query =
            """
            SELECT 
                issue_id,
                any(message) as title,
                any(exception_type) as culprit,
                any(project_id) as top_issue_project_id,
                count() as event_count
            FROM `$clickhouseDb`.events
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
            val preview = responseBody.take(ERROR_BODY_PREVIEW_CHARS)
            logger.error("ClickHouse query failed in getTopIssues: ${response.status} - $preview")
            return null
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
            val projectId = obj["top_issue_project_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
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
                .withHour(WEEKLY_SUMMARY_HOUR)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)

        // If we've already passed Monday 9am this week, schedule for next week
        if (nextRun.isBefore(now)) {
            nextRun = nextRun.plusWeeks(1)
        }

        val initialDelay = Duration.between(now, nextRun).toMillis()
        val period = Duration.ofDays(WEEKLY_SUMMARY_DAYS).toMillis()

        logger.info { "Scheduling weekly summary for $nextRun" }

        scheduler.scheduleAtFixedRate(
            {
                runBlocking {
                    suspendRunCatching {
                        sendWeeklySummary()
                    }.getOrElse { e ->
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
        if (previous == 0L) return if (current > 0) FULL_PERCENTAGE else 0
        return ((current - previous) * FULL_PERCENTAGE / previous).toInt()
    }

    private fun formatNumber(num: Long): String {
        return when {
            num >= MILLION -> String.format(Locale.US, "%.1fM", num / MILLION.toDouble())
            num >= THOUSAND -> String.format(Locale.US, "%.1fK", num / THOUSAND.toDouble())
            else -> num.toString()
        }
    }

    private fun formatDate(instant: Instant): String {
        val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US)
        return formatter.format(instant.atZone(ZoneId.of("UTC")))
    }

    private fun severityForIssueLevel(level: String): AlertSeverity {
        return when (level.lowercase(Locale.getDefault())) {
            "fatal" -> AlertSeverity.CRITICAL
            "error" -> AlertSeverity.HIGH
            "warning" -> AlertSeverity.MEDIUM
            else -> AlertSeverity.LOW
        }
    }

    fun shutdown() {
        scheduler.shutdown()
    }
}
