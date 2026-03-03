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

package com.moneat.summary.services

import com.moneat.config.ClickHouseClient
import com.moneat.events.services.DashboardService
import com.moneat.events.services.ReleaseService
import com.moneat.incident.models.IncidentEventLog
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import com.moneat.shared.models.Projects
import com.moneat.summary.models.AlertContextInfo
import com.moneat.summary.models.AlertSummaryItem
import com.moneat.summary.models.DailyErrorCount
import com.moneat.summary.models.DeploymentInfo
import com.moneat.summary.models.ErrorSpikeEntry
import com.moneat.summary.models.ErrorSpikeInfo
import com.moneat.summary.models.HostErrorRate
import com.moneat.summary.models.HostMetricsWindow
import com.moneat.summary.models.HostResourceTrend
import com.moneat.summary.models.HostStatusChange
import com.moneat.summary.models.HostStatusCounts
import com.moneat.summary.models.IncidentContextResponse
import com.moneat.summary.models.InfrastructureSummaryResponse
import com.moneat.summary.models.IssueSummaryItem
import com.moneat.summary.models.LogVolumeComparison
import com.moneat.summary.models.OvernightSummaryResponse
import com.moneat.summary.models.RelatedLogEntry
import com.moneat.summary.models.TransactionLatencySummary
import com.moneat.summary.models.UptimeIncidentSummary
import com.moneat.summary.models.UptimeMonitorSummary
import com.moneat.summary.models.WeeklyReportResponse
import com.moneat.uptime.services.UptimeService
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

private const val DEFAULT_OVERNIGHT_START_HOUR = 22
private const val DEFAULT_OVERNIGHT_END_HOUR = 8
private const val METRICS_WINDOW_MINUTES = 30L
private const val MAX_TOP_ALERTS = 10
private const val MAX_TOP_HOSTS = 10
private const val MAX_LOG_ENTRIES = 50
private const val MAX_ERROR_SPIKES = 10
private const val MAX_NOISY_ISSUES = 5
private const val MAX_TRANSACTION_LATENCIES = 10
private const val PERCENT_MULTIPLIER = 100.0
private const val STATUS_ONLINE = "online"
private const val STATUS_OFFLINE = "offline"
private const val STATUS_DOWN = "down"
private const val SPIKE_FACTOR = 2
private const val WEEK_SECONDS = 7 * 24 * 3600L
private const val TWO_WEEKS_SECONDS = 14 * 24 * 3600L

class SummaryService(
    private val monitorService: MonitorService = MonitorService(),
    private val uptimeService: UptimeService = UptimeService(),
    private val alertService: MonitorAlertService = MonitorAlertService(),
    private val dashboardService: DashboardService = DashboardService(),
    private val releaseService: ReleaseService = ReleaseService()
) {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val json = Json { ignoreUnknownKeys = true }
    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    suspend fun getInfrastructureSummary(
        organizationId: Int,
        period: String
    ): InfrastructureSummaryResponse = coroutineScope {
        val hosts = monitorService.listHosts(organizationId)
        val monitors = uptimeService.listMonitors(organizationId)

        val hostCounts = HostStatusCounts(
            online = hosts.count { it.status == STATUS_ONLINE },
            offline = hosts.count { it.status == STATUS_OFFLINE },
            warning = hosts.count {
                it.status != STATUS_ONLINE && it.status != STATUS_OFFLINE
            },
            total = hosts.size
        )

        val uptimeSummaries = monitors.map { m ->
            UptimeMonitorSummary(
                id = m.id,
                name = m.name,
                status = m.status,
                uptime24h = m.uptime24h,
                uptime7d = m.uptime7d,
                uptime30d = m.uptime30d
            )
        }

        val topAlertsDeferred = async {
            getTopAlerts(organizationId, period)
        }
        val topErrorHostsDeferred = async {
            getTopErrorRateHosts(hosts, period)
        }

        InfrastructureSummaryResponse(
            period = period,
            hostCounts = hostCounts,
            uptimeMonitors = uptimeSummaries,
            topAlerts = topAlertsDeferred.await(),
            topErrorRateHosts = topErrorHostsDeferred.await()
        )
    }

    @Suppress("LongMethod")
    suspend fun getOvernightSummary(
        organizationId: Int,
        timezone: String
    ): OvernightSummaryResponse = coroutineScope {
        val zone = ZoneId.of(timezone)
        val now = ZonedDateTime.now(zone)
        val today = now.toLocalDate()

        val windowEnd = today.atTime(DEFAULT_OVERNIGHT_END_HOUR, 0)
            .atZone(zone).toInstant()
        val windowStart = today.minusDays(1)
            .atTime(DEFAULT_OVERNIGHT_START_HOUR, 0)
            .atZone(zone).toInstant()

        val prevEnd = today.minusDays(1)
            .atTime(DEFAULT_OVERNIGHT_END_HOUR, 0)
            .atZone(zone).toInstant()
        val prevStart = today.minusDays(2)
            .atTime(DEFAULT_OVERNIGHT_START_HOUR, 0)
            .atZone(zone).toInstant()

        val projectIds = getProjectIds(organizationId)

        val newIssuesDeferred = async {
            getNewIssues(projectIds, windowStart, windowEnd)
        }
        val errorCountDeferred = async {
            getErrorCount(projectIds, windowStart, windowEnd)
        }
        val baselineCountDeferred = async {
            getErrorCount(projectIds, prevStart, prevEnd)
        }
        val logErrorsDeferred = async {
            getLogErrorCount(organizationId, windowStart, windowEnd)
        }
        val prevLogErrorsDeferred = async {
            getLogErrorCount(organizationId, prevStart, prevEnd)
        }

        val hosts = monitorService.listHosts(organizationId)
        val monitors = uptimeService.listMonitors(organizationId)

        val overnightErrors = errorCountDeferred.await()
        val baselineErrors = baselineCountDeferred.await()
        val logErrors = logErrorsDeferred.await()
        val prevLogErrors = prevLogErrorsDeferred.await()

        val changePercent = if (prevLogErrors > 0) {
            ((logErrors - prevLogErrors).toDouble() / prevLogErrors) *
                PERCENT_MULTIPLIER
        } else {
            0.0
        }

        val hostChanges = hosts.filter { h ->
            h.lastSeenAt != null &&
                h.status == STATUS_OFFLINE &&
                h.lastSeenAt.toEpochMilliseconds() >= windowStart.toEpochMilli() &&
                h.lastSeenAt.toEpochMilliseconds() <= windowEnd.toEpochMilli()
        }.map { h ->
            HostStatusChange(
                systemId = h.id.toString(),
                systemName = h.displayName ?: h.hostname,
                previousStatus = STATUS_ONLINE,
                currentStatus = h.status,
                changedAt = h.lastSeenAt?.let {
                    isoFormatter.format(
                        Instant.ofEpochMilli(it.toEpochMilliseconds())
                    )
                } ?: ""
            )
        }

        val uptimeIncidents = monitors.filter {
            it.status == STATUS_DOWN
        }.map { m ->
            UptimeIncidentSummary(
                monitorId = m.id,
                monitorName = m.name,
                downtimeMinutes = 0
            )
        }

        OvernightSummaryResponse(
            timezone = timezone,
            windowStart = isoFormatter.format(windowStart),
            windowEnd = isoFormatter.format(windowEnd),
            newIssues = newIssuesDeferred.await(),
            regressedIssues = emptyList(),
            errorSpikes = ErrorSpikeInfo(
                overnightCount = overnightErrors,
                baselineCount = baselineErrors,
                spikeDetected = overnightErrors > baselineErrors * SPIKE_FACTOR
            ),
            hostStatusChanges = hostChanges,
            uptimeIncidents = uptimeIncidents,
            logErrorVolume = LogVolumeComparison(
                overnightErrors = logErrors,
                previousNightErrors = prevLogErrors,
                changePercent = changePercent
            )
        )
    }

    @Suppress("LongMethod")
    suspend fun getWeeklyReport(
        organizationId: Int
    ): WeeklyReportResponse = coroutineScope {
        val now = Instant.now()
        val weekAgo = now.minusSeconds(WEEK_SECONDS)
        val twoWeeksAgo = now.minusSeconds(TWO_WEEKS_SECONDS)

        val projectIds = getProjectIds(organizationId)
        val monitors = uptimeService.listMonitors(organizationId)
        val hosts = monitorService.listHosts(organizationId)

        val dailyErrorsDeferred = async {
            getDailyErrors(projectIds, weekAgo, now)
        }
        val prevWeekErrorsDeferred = async {
            getErrorCount(projectIds, twoWeeksAgo, weekAgo)
        }
        val thisWeekErrorsDeferred = async {
            getErrorCount(projectIds, weekAgo, now)
        }
        val topLatenciesDeferred = async {
            getTopTransactionLatencies(projectIds, weekAgo, now)
        }
        val noisyIssuesDeferred = async {
            getTopNoisyIssues(projectIds, weekAgo, now)
        }

        val thisWeekErrors = thisWeekErrorsDeferred.await()
        val prevWeekErrors = prevWeekErrorsDeferred.await()
        val weekOverWeekDelta = if (prevWeekErrors > 0) {
            (
                (thisWeekErrors - prevWeekErrors).toDouble() /
                    prevWeekErrors
                ) * PERCENT_MULTIPLIER
        } else {
            0.0
        }

        val uptimeSummaries = monitors.map { m ->
            UptimeMonitorSummary(
                id = m.id,
                name = m.name,
                status = m.status,
                uptime24h = m.uptime24h,
                uptime7d = m.uptime7d,
                uptime30d = m.uptime30d
            )
        }

        val hostMetrics = if (hosts.isEmpty()) {
            emptyList()
        } else {
            hosts.mapNotNull {
                runCatching {
                    monitorService.getLatestMetrics(it.id)
                }.getOrNull()
            }
        }

        val avgCpu = hostMetrics
            .map { it.cpu_percent.toDouble() }
            .takeIf { it.isNotEmpty() }?.average() ?: 0.0

        val avgMem = hostMetrics
            .map { it.mem_percent.toDouble() }
            .takeIf { it.isNotEmpty() }?.average() ?: 0.0

        WeeklyReportResponse(
            periodStart = isoFormatter.format(weekAgo),
            periodEnd = isoFormatter.format(now),
            errorTrend = dailyErrorsDeferred.await(),
            weekOverWeekDelta = weekOverWeekDelta,
            topTransactionLatencies = topLatenciesDeferred.await(),
            uptimeSummary = uptimeSummaries,
            incidentCount = 0,
            mttrMinutes = null,
            topNoisyIssues = noisyIssuesDeferred.await(),
            hostResourceTrends = HostResourceTrend(
                avgCpuPercent = avgCpu,
                avgMemoryPercent = avgMem,
                hostCount = hosts.size
            )
        )
    }

    @Suppress("LongMethod")
    suspend fun getIncidentContext(
        organizationId: Int,
        incidentId: Long
    ): IncidentContextResponse = coroutineScope {
        val hosts = monitorService.listHosts(organizationId)
        val monitors = uptimeService.listMonitors(organizationId)
        val projectIds = getProjectIds(organizationId)

        val now = Instant.now()
        val windowStart = now.minusSeconds(METRICS_WINDOW_MINUTES * 60)

        // Load the specific incident from IncidentEventLog by ID and org, then
        // parse the deduplication key to find the exact triggering host/alert.
        var alertContext: AlertContextInfo? = null
        var hostMetrics: HostMetricsWindow? = null

        val incidentLog = loadIncidentLog(organizationId, incidentId)
        if (incidentLog != null) {
            val hostId = parseHostIdFromDedupKey(incidentLog.deduplicationKey)
            val alertId = parseAlertIdFromDedupKey(incidentLog.deduplicationKey)
            val host = if (hostId != null) {
                hosts.firstOrNull { it.id == hostId }
            } else {
                null
            }
            if (host != null) {
                val alert = if (alertId != null) {
                    monitorService.listAlerts(host.id, host.organizationId).firstOrNull { it.id == alertId }
                } else {
                    monitorService.listAlerts(host.id, host.organizationId).firstOrNull { it.lastTriggeredAt != null }
                }
                alertContext = AlertContextInfo(
                    alertId = alert?.id ?: 0,
                    metric = alert?.metric ?: "",
                    condition = alert?.condition ?: "",
                    threshold = alert?.threshold ?: 0.0,
                    systemId = host.id.toString(),
                    systemName = host.displayName ?: host.hostname
                )
                val metrics = runCatching { monitorService.getLatestMetrics(host.id) }.getOrNull()
                if (metrics != null) {
                    hostMetrics = HostMetricsWindow(
                        systemId = host.id.toString(),
                        systemName = host.displayName ?: host.hostname,
                        avgCpu = metrics.cpu_percent.toDouble(),
                        maxCpu = metrics.cpu_percent.toDouble(),
                        avgMemory = metrics.mem_percent.toDouble(),
                        maxMemory = metrics.mem_percent.toDouble(),
                        windowStart = isoFormatter.format(windowStart),
                        windowEnd = isoFormatter.format(now)
                    )
                }
            }
        } else {
            // Fallback: use the most recently triggered alert across the org
            // when the incident log entry cannot be found.
            val mostRecent = hosts.flatMap { host ->
                monitorService.listAlerts(host.id, host.organizationId)
                    .filter { it.lastTriggeredAt != null }
                    .map { alert -> alert to host }
            }.maxByOrNull { it.first.lastTriggeredAt ?: 0 }
            if (mostRecent != null) {
                val (alert, host) = mostRecent
                alertContext = AlertContextInfo(
                    alertId = alert.id,
                    metric = alert.metric,
                    condition = alert.condition,
                    threshold = alert.threshold,
                    systemId = host.id.toString(),
                    systemName = host.displayName ?: host.hostname
                )
                val metrics = runCatching { monitorService.getLatestMetrics(host.id) }.getOrNull()
                if (metrics != null) {
                    hostMetrics = HostMetricsWindow(
                        systemId = host.id.toString(),
                        systemName = host.displayName ?: host.hostname,
                        avgCpu = metrics.cpu_percent.toDouble(),
                        maxCpu = metrics.cpu_percent.toDouble(),
                        avgMemory = metrics.mem_percent.toDouble(),
                        maxMemory = metrics.mem_percent.toDouble(),
                        windowStart = isoFormatter.format(windowStart),
                        windowEnd = isoFormatter.format(now)
                    )
                }
            }
        }

        val logsDeferred = async {
            getRecentLogErrors(organizationId, windowStart, now)
        }
        val errorSpikesDeferred = async {
            getErrorSpikes(projectIds, windowStart, now)
        }
        val deploymentsDeferred = async {
            getRecentDeployments(projectIds)
        }

        val affectedMonitors = monitors.filter {
            it.status == STATUS_DOWN
        }.map { m ->
            UptimeMonitorSummary(
                id = m.id,
                name = m.name,
                status = m.status,
                uptime24h = m.uptime24h,
                uptime7d = m.uptime7d,
                uptime30d = m.uptime30d
            )
        }

        IncidentContextResponse(
            incidentId = incidentId,
            triggeringAlert = alertContext,
            hostMetrics = hostMetrics,
            relatedLogs = logsDeferred.await(),
            errorSpikes = errorSpikesDeferred.await(),
            recentDeployments = deploymentsDeferred.await(),
            affectedUptimeMonitors = affectedMonitors
        )
    }

    // --- Private helpers ---

    /**
     * Look up the specific [IncidentEventLog] entry by its ID, scoped to the
     * organization for security. Returns null when no matching entry exists.
     */
    private data class IncidentLogEntry(
        val deduplicationKey: String,
        val alertSource: String,
        val title: String
    )

    private fun loadIncidentLog(organizationId: Int, incidentId: Long): IncidentLogEntry? {
        if (incidentId !in Int.MIN_VALUE..Int.MAX_VALUE) return null
        return transaction {
            IncidentEventLog
                .selectAll()
                .where {
                    (IncidentEventLog.id eq incidentId.toInt()) and
                        (IncidentEventLog.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.let { row ->
                    IncidentLogEntry(
                        deduplicationKey = row[IncidentEventLog.deduplicationKey],
                        alertSource = row[IncidentEventLog.alertSource],
                        title = row[IncidentEventLog.title]
                    )
                }
        }
    }

    /**
     * Deduplication key formats:
     * - `moneat-host-alert-{hostId}-id_{Int}` or `...-tpl_{Int}`
     * - `moneat-host-down-{hostId}`
     * - `moneat-uptime-{monitorId}` (uptime uses monitor ID, not host)
     * Returns the host ID when present, or null.
     */
    private fun parseHostIdFromDedupKey(key: String): Int? {
        val hostAlertPrefix = "moneat-host-alert-"
        val hostDownPrefix = "moneat-host-down-"
        val remainder = when {
            key.startsWith(hostAlertPrefix) -> {
                val rest = key.removePrefix(hostAlertPrefix)
                // hostId is numeric; the suffix (-id_ or -tpl_) follows
                val dashIdx = rest.indexOf('-')
                if (dashIdx > 0) rest.substring(0, dashIdx) else rest
            }
            key.startsWith(hostDownPrefix) -> key.removePrefix(hostDownPrefix)
            else -> null
        }
        return remainder?.toIntOrNull()
    }

    /**
     * Parses the alert ID integer from `moneat-host-alert-{hostId}-id_{Int}` keys.
     * Returns null for other key formats.
     */
    private fun parseAlertIdFromDedupKey(key: String): Int? {
        val idMarker = "-id_"
        val idx = key.indexOf(idMarker)
        if (idx < 0) return null
        return key.substring(idx + idMarker.length).toIntOrNull()
    }

    private fun getProjectIds(organizationId: Int): List<Long> {
        return transaction {
            Projects
                .selectAll()
                .where { Projects.organization_id eq organizationId }
                .map { it[Projects.id] }
        }
    }

    private fun getTopAlerts(
        organizationId: Int,
        period: String
    ): List<AlertSummaryItem> {
        val periodMs = periodToMillis(period)
        val cutoff = System.currentTimeMillis() - periodMs
        val hosts = monitorService.listHosts(organizationId)
        val allAlerts = hosts.flatMap { host ->
            monitorService.listAlerts(host.id, organizationId).map { alert ->
                AlertSummaryItem(
                    alertId = alert.id,
                    systemId = alert.hostId?.toString() ?: alert.systemId,
                    metric = alert.metric,
                    condition = alert.condition,
                    threshold = alert.threshold,
                    lastTriggeredAt = alert.lastTriggeredAt
                )
            }
        }
        return allAlerts
            .filter {
                it.lastTriggeredAt != null &&
                    it.lastTriggeredAt >= cutoff
            }
            .sortedByDescending { it.lastTriggeredAt }
            .take(MAX_TOP_ALERTS)
    }

    private fun getTopErrorRateHosts(
        hosts: List<com.moneat.monitor.models.HostData>,
        period: String
    ): List<HostErrorRate> {
        val periodMs = periodToMillis(period)
        val cutoff = System.currentTimeMillis() - periodMs
        return hosts.map { host ->
            val alertCount = monitorService.listAlerts(host.id, host.organizationId)
                .count { alert ->
                    alert.lastTriggeredAt != null &&
                        alert.lastTriggeredAt >= cutoff
                }
            HostErrorRate(
                systemId = host.id.toString(),
                systemName = host.displayName ?: host.hostname,
                alertCount = alertCount
            )
        }.filter { it.alertCount > 0 }
            .sortedByDescending { it.alertCount }
            .take(MAX_TOP_HOSTS)
    }

    private fun periodToMillis(period: String): Long {
        return when (period) {
            "24h" -> 24L * 3600 * 1000
            "7d" -> 7L * 24 * 3600 * 1000
            "30d" -> 30L * 24 * 3600 * 1000
            else -> 24L * 3600 * 1000
        }
    }

    private suspend fun getNewIssues(
        projectIds: List<Long>,
        from: Instant,
        to: Instant
    ): List<IssueSummaryItem> {
        if (projectIds.isEmpty()) return emptyList()
        val projectClause = projectIdClause(projectIds)
        val query = """
            SELECT issue_id, any(message) as title,
                   count() as event_count, any(project_id) as pid
            FROM $clickhouseDb.events
            WHERE $projectClause
                AND event_type = 'error'
                AND timestamp >= toDateTime('${formatClickhouse(from)}')
                AND timestamp <= toDateTime('${formatClickhouse(to)}')
            GROUP BY issue_id
            ORDER BY event_count DESC
            LIMIT $MAX_TOP_ALERTS
            FORMAT JSON
        """.trimIndent()
        return executeIssueQuery(query)
    }

    private suspend fun getErrorCount(
        projectIds: List<Long>,
        from: Instant,
        to: Instant
    ): Long {
        if (projectIds.isEmpty()) return 0
        val projectClause = projectIdClause(projectIds)
        val query = """
            SELECT count() as total
            FROM $clickhouseDb.events
            WHERE $projectClause
                AND event_type = 'error'
                AND timestamp >= toDateTime('${formatClickhouse(from)}')
                AND timestamp <= toDateTime('${formatClickhouse(to)}')
            FORMAT JSONEachRow
        """.trimIndent()
        return executeScalar(query)
    }

    private suspend fun getLogErrorCount(
        organizationId: Int,
        from: Instant,
        to: Instant
    ): Long {
        val orgId = organizationId.toLong()
        val query = """
            SELECT count() as total
            FROM $clickhouseDb.logs
            WHERE organization_id = $orgId
                AND level IN ('error', 'fatal', 'critical')
                AND timestamp >= toDateTime('${formatClickhouse(from)}')
                AND timestamp <= toDateTime('${formatClickhouse(to)}')
            FORMAT JSONEachRow
        """.trimIndent()
        return executeScalar(query)
    }

    private suspend fun getDailyErrors(
        projectIds: List<Long>,
        from: Instant,
        to: Instant
    ): List<DailyErrorCount> {
        if (projectIds.isEmpty()) return emptyList()
        val projectClause = projectIdClause(projectIds)
        val query = """
            SELECT toDate(timestamp) as date, count() as count
            FROM $clickhouseDb.events
            WHERE $projectClause
                AND event_type = 'error'
                AND timestamp >= toDateTime('${formatClickhouse(from)}')
                AND timestamp <= toDateTime('${formatClickhouse(to)}')
            GROUP BY date
            ORDER BY date
            FORMAT JSON
        """.trimIndent()
        return executeDailyErrorQuery(query)
    }

    private suspend fun getTopTransactionLatencies(
        projectIds: List<Long>,
        from: Instant,
        to: Instant
    ): List<TransactionLatencySummary> {
        if (projectIds.isEmpty()) return emptyList()
        val projectClause = projectIdClause(projectIds)
        val query = """
            SELECT transaction as name,
                   quantile(0.95)(duration) as p95,
                   count() as cnt
            FROM $clickhouseDb.events
            WHERE $projectClause
                AND event_type = 'transaction'
                AND timestamp >= toDateTime('${formatClickhouse(from)}')
                AND timestamp <= toDateTime('${formatClickhouse(to)}')
            GROUP BY name
            ORDER BY p95 DESC
            LIMIT $MAX_TRANSACTION_LATENCIES
            FORMAT JSON
        """.trimIndent()
        return executeLatencyQuery(query)
    }

    private suspend fun getTopNoisyIssues(
        projectIds: List<Long>,
        from: Instant,
        to: Instant
    ): List<IssueSummaryItem> {
        if (projectIds.isEmpty()) return emptyList()
        val projectClause = projectIdClause(projectIds)
        val query = """
            SELECT issue_id, any(message) as title,
                   count() as event_count, any(project_id) as pid
            FROM $clickhouseDb.events
            WHERE $projectClause
                AND event_type = 'error'
                AND timestamp >= toDateTime('${formatClickhouse(from)}')
                AND timestamp <= toDateTime('${formatClickhouse(to)}')
            GROUP BY issue_id
            ORDER BY event_count DESC
            LIMIT $MAX_NOISY_ISSUES
            FORMAT JSON
        """.trimIndent()
        return executeIssueQuery(query)
    }

    private suspend fun getRecentLogErrors(
        organizationId: Int,
        from: Instant,
        to: Instant
    ): List<RelatedLogEntry> {
        val orgId = organizationId.toLong()
        val query = """
            SELECT timestamp, level, message,
                   service_name as service
            FROM $clickhouseDb.logs
            WHERE organization_id = $orgId
                AND level IN ('error', 'fatal', 'critical')
                AND timestamp >= toDateTime('${formatClickhouse(from)}')
                AND timestamp <= toDateTime('${formatClickhouse(to)}')
            ORDER BY timestamp DESC
            LIMIT $MAX_LOG_ENTRIES
            FORMAT JSON
        """.trimIndent()
        return executeLogQuery(query)
    }

    private suspend fun getErrorSpikes(
        projectIds: List<Long>,
        from: Instant,
        to: Instant
    ): List<ErrorSpikeEntry> {
        if (projectIds.isEmpty()) return emptyList()
        val projectClause = projectIdClause(projectIds)
        val query = """
            SELECT issue_id, any(message) as title,
                   count() as cnt,
                   min(timestamp) as first_seen
            FROM $clickhouseDb.events
            WHERE $projectClause
                AND event_type = 'error'
                AND timestamp >= toDateTime('${formatClickhouse(from)}')
                AND timestamp <= toDateTime('${formatClickhouse(to)}')
            GROUP BY issue_id
            ORDER BY cnt DESC
            LIMIT $MAX_ERROR_SPIKES
            FORMAT JSON
        """.trimIndent()
        return executeErrorSpikeQuery(query)
    }

    private fun getRecentDeployments(
        projectIds: List<Long>
    ): List<DeploymentInfo> {
        return projectIds.flatMap { pid ->
            runCatching {
                releaseService.listReleases(pid).take(2).map { r ->
                    DeploymentInfo(
                        version = r.version,
                        projectId = pid,
                        createdAt = r.dateCreated
                    )
                }
            }.getOrDefault(emptyList())
        }
    }

    // --- Query execution helpers ---

    private suspend fun executeScalar(query: String): Long {
        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (body.isBlank()) return 0
            val obj = json.parseToJsonElement(body.lines().first())
                .jsonObject
            obj["total"]?.jsonPrimitive?.long ?: 0
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error(e) { "Summary scalar query failed" }
            0
        }
    }

    private suspend fun executeIssueQuery(
        query: String
    ): List<IssueSummaryItem> {
        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (body.isBlank()) return emptyList()
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            data.map { row ->
                val obj = row.jsonObject
                IssueSummaryItem(
                    issueId = obj["issue_id"]?.jsonPrimitive?.content
                        ?: "",
                    title = obj["title"]?.jsonPrimitive?.content ?: "",
                    eventCount = obj["event_count"]
                        ?.jsonPrimitive?.long ?: 0,
                    projectId = obj["pid"]
                        ?.jsonPrimitive?.long ?: 0
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error(e) { "Summary issue query failed" }
            emptyList()
        }
    }

    private suspend fun executeDailyErrorQuery(
        query: String
    ): List<DailyErrorCount> {
        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (body.isBlank()) return emptyList()
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            data.map { row ->
                val obj = row.jsonObject
                DailyErrorCount(
                    date = obj["date"]?.jsonPrimitive?.content ?: "",
                    count = obj["count"]?.jsonPrimitive?.long ?: 0
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error(e) { "Summary daily error query failed" }
            emptyList()
        }
    }

    private suspend fun executeLatencyQuery(
        query: String
    ): List<TransactionLatencySummary> {
        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (body.isBlank()) return emptyList()
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            data.map { row ->
                val obj = row.jsonObject
                TransactionLatencySummary(
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    p95 = obj["p95"]?.jsonPrimitive?.content
                        ?.toDoubleOrNull() ?: 0.0,
                    count = obj["cnt"]?.jsonPrimitive?.long ?: 0
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error(e) { "Summary latency query failed" }
            emptyList()
        }
    }

    private suspend fun executeLogQuery(
        query: String
    ): List<RelatedLogEntry> {
        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (body.isBlank()) return emptyList()
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            data.map { row ->
                val obj = row.jsonObject
                RelatedLogEntry(
                    timestamp = obj["timestamp"]
                        ?.jsonPrimitive?.content ?: "",
                    level = obj["level"]?.jsonPrimitive?.content ?: "",
                    message = obj["message"]
                        ?.jsonPrimitive?.content ?: "",
                    service = obj["service"]?.jsonPrimitive?.content
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error(e) { "Summary log query failed" }
            emptyList()
        }
    }

    private suspend fun executeErrorSpikeQuery(
        query: String
    ): List<ErrorSpikeEntry> {
        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (body.isBlank()) return emptyList()
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            data.map { row ->
                val obj = row.jsonObject
                ErrorSpikeEntry(
                    issueId = obj["issue_id"]
                        ?.jsonPrimitive?.content ?: "",
                    title = obj["title"]?.jsonPrimitive?.content ?: "",
                    count = obj["cnt"]?.jsonPrimitive?.long ?: 0,
                    firstSeen = obj["first_seen"]
                        ?.jsonPrimitive?.content ?: ""
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error(e) { "Summary error spike query failed" }
            emptyList()
        }
    }

    private fun projectIdClause(projectIds: List<Long>): String {
        return if (projectIds.size == 1) {
            "project_id = ${projectIds.first()}"
        } else {
            "project_id IN (${projectIds.joinToString(",")})"
        }
    }

    private fun formatClickhouse(instant: Instant): String {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("UTC"))
            .format(instant)
    }
}
