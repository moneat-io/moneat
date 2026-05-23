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

package com.moneat.mcp.services

import com.moneat.billing.services.BillingQuotaService
import com.moneat.config.ClickHouseClient
import com.moneat.enterprise.FeatureRegistry
import com.moneat.monitor.repositories.HostAlertRepositoryImpl
import com.moneat.monitor.repositories.HostRepositoryImpl
import com.moneat.monitor.services.MonitorService
import com.moneat.uptime.repositories.UptimeMonitorRepositoryImpl
import com.moneat.uptime.services.UptimeService
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

private const val MAX_ALERTS_DISPLAY = 10
private const val HOURS_IN_7_DAYS = 168
private const val HOURS_IN_30_DAYS = 720
private const val DEFAULT_PERIOD_HOURS = 24
private const val OVERNIGHT_END_HOUR = 8
private const val OVERNIGHT_WINDOW_HOURS = 10L
private const val DAYS_IN_WEEK = 7L

class SummaryService(
    private val monitorService: MonitorService = MonitorService(HostRepositoryImpl(), HostAlertRepositoryImpl()),
    private val uptimeService: UptimeService = UptimeService(BillingQuotaService(), UptimeMonitorRepositoryImpl()),
) {

    // ── Infrastructure Summary ────────────────────────────────────────────────

    suspend fun getInfrastructureSummary(
        organizationId: Int,
        period: String,
    ): InfrastructureSummaryResponse {
        val normalizedPeriod = when (period) {
            "7d", "30d" -> period
            else -> "24h"
        }
        val systems = monitorService.listHosts(organizationId)

        val hostSummary = HostStatusSummary(
            total = systems.size,
            online = systems.count { it.status == "online" },
            offline = systems.count { it.status == "offline" },
            warning = systems.count { it.status == "warning" },
        )

        val uptimeMonitors = uptimeService.listMonitors(organizationId).map { m ->
            UptimeMonitorSummary(
                id = m.id,
                name = m.name,
                status = m.status,
                uptime24h = m.uptime24h,
                uptime7d = m.uptime7d,
                uptime30d = m.uptime30d,
            )
        }

        val topAlerts = systems.flatMap { sys ->
            collectAlertsForSystem(sys)
        }.sortedByDescending { it.lastTriggeredAt }.take(MAX_ALERTS_DISPLAY)

        val topErrorHosts = queryTopErrorHosts(organizationId, normalizedPeriod)

        return InfrastructureSummaryResponse(
            period = normalizedPeriod,
            hostSummary = hostSummary,
            uptimeMonitors = uptimeMonitors,
            topAlerts = topAlerts,
            topErrorHosts = topErrorHosts,
        )
    }

    private suspend fun queryTopErrorHosts(
        organizationId: Int,
        period: String,
    ): List<HostErrorSummary> {
        val intervalHours = when (period) {
            "7d" -> HOURS_IN_7_DAYS
            "30d" -> HOURS_IN_30_DAYS
            else -> DEFAULT_PERIOD_HOURS
        }
        return try {
            val query = """
                SELECT host, count() AS error_count
                FROM logs
                WHERE organization_id = $organizationId
                  AND level = 'error'
                  AND timestamp >= now() - INTERVAL $intervalHours HOUR
                GROUP BY host
                ORDER BY error_count DESC
                LIMIT 5
                FORMAT JSON
            """.trimIndent()
            val response = ClickHouseClient.execute(query)
            if (!response.status.isSuccess()) return emptyList()
            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            root["data"]?.jsonArray?.map { row ->
                val obj = row.jsonObject
                HostErrorSummary(
                    systemId = obj["host"]?.jsonPrimitive?.content ?: "",
                    systemName = obj["host"]?.jsonPrimitive?.content ?: "",
                    errorCount = obj["error_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                )
            } ?: emptyList()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to query top error hosts" }
            emptyList()
        }
    }

    // ── Overnight Summary ─────────────────────────────────────────────────────

    suspend fun getOvernightSummary(
        organizationId: Int,
        timezone: String,
    ): OvernightSummaryResponse {
        val resolvedZoneId = runCatching { ZoneId.of(timezone) }
            .getOrElse {
                logger.warn { "Invalid timezone '$timezone', falling back to America/New_York" }
                ZoneId.of("America/New_York")
            }
        val resolvedTimezone = resolvedZoneId.id
        val now = ZonedDateTime.now(resolvedZoneId)
        val windowEnd = now.with(LocalTime.of(OVERNIGHT_END_HOUR, 0))
            .let { if (it.isAfter(now)) it.minusDays(1) else it }
        val windowStart = windowEnd.minusHours(OVERNIGHT_WINDOW_HOURS)

        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val startStr = windowStart.withZoneSameInstant(ZoneId.of("UTC")).format(fmt)
        val endStr = windowEnd.withZoneSameInstant(ZoneId.of("UTC")).format(fmt)

        val logVolume = queryLogVolume(organizationId, startStr, endStr)

        return OvernightSummaryResponse(
            timezone = resolvedTimezone,
            windowStart = windowStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            windowEnd = windowEnd.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            hostStatusChanges = emptyList(),
            triggeredIncidents = emptyList(),
            logErrorVolume = logVolume,
        )
    }

    private suspend fun queryLogVolume(
        organizationId: Int,
        startUtc: String,
        endUtc: String,
    ): LogVolumeSummary {
        return try {
            val query = """
                SELECT
                    countIf(level = 'error') AS error_count,
                    countIf(level = 'warn') AS warn_count,
                    count() AS total_count
                FROM logs
                WHERE organization_id = $organizationId
                  AND timestamp >= '$startUtc'
                  AND timestamp <= '$endUtc'
                FORMAT JSON
            """.trimIndent()
            val response = ClickHouseClient.execute(query)
            if (!response.status.isSuccess()) return LogVolumeSummary(0, 0, 0)
            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            val row = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return LogVolumeSummary(0, 0, 0)
            LogVolumeSummary(
                errorCount = row["error_count"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                warnCount = row["warn_count"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                totalCount = row["total_count"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to query overnight log volume" }
            LogVolumeSummary(0, 0, 0)
        }
    }

    // ── Weekly Report ─────────────────────────────────────────────────────────

    suspend fun getWeeklyReport(organizationId: Int): WeeklyReportResponse {
        val today = LocalDate.now()
        val weekAgo = today.minusDays(DAYS_IN_WEEK)

        val uptimeMonitors = uptimeService.listMonitors(organizationId).map { m ->
            UptimeMonitorSummary(
                id = m.id,
                name = m.name,
                status = m.status,
                uptime24h = m.uptime24h,
                uptime7d = m.uptime7d,
                uptime30d = m.uptime30d,
            )
        }

        val logTrend = queryDailyLogTrend(organizationId, weekAgo.toString(), today.toString())

        return WeeklyReportResponse(
            periodStart = weekAgo.toString(),
            periodEnd = today.toString(),
            uptimeMonitors = uptimeMonitors,
            incidentStats = IncidentStats(total = 0, resolved = 0, avgResolutionMinutes = null),
            logTrend = logTrend,
        )
    }

    private suspend fun queryDailyLogTrend(
        organizationId: Int,
        startDate: String,
        endDate: String,
    ): List<DailyLogCount> {
        return try {
            val query = """
                SELECT
                    formatDateTime(timestamp, '%Y-%m-%d') AS date,
                    countIf(level = 'error') AS error_count,
                    count() AS total_count
                FROM logs
                WHERE organization_id = $organizationId
                  AND timestamp >= '$startDate'
                  AND timestamp < '$endDate'
                GROUP BY date
                ORDER BY date ASC
                FORMAT JSON
            """.trimIndent()
            val response = ClickHouseClient.execute(query)
            if (!response.status.isSuccess()) return emptyList()
            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            root["data"]?.jsonArray?.map { row ->
                val obj = row.jsonObject
                DailyLogCount(
                    date = obj["date"]?.jsonPrimitive?.content ?: "",
                    errorCount = obj["error_count"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                    totalCount = obj["total_count"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                )
            } ?: emptyList()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to query weekly log trend" }
            emptyList()
        }
    }

    // ── Incident Context ──────────────────────────────────────────────────────

    suspend fun getIncidentContext(
        organizationId: Int,
        incidentId: Long,
        userId: Int,
    ): IncidentContextResponse {
        val systems = monitorService.listHosts(organizationId)

        val hostMetrics = systems.map { sys ->
            val metrics = runCatching {
                monitorService.getLatestMetrics(sys.id)
            }.getOrNull()
            HostMetricSnapshot(
                systemId = sys.id.toString(),
                systemName = sys.displayName ?: sys.hostname,
                cpuPercent = metrics?.cpuPercent,
                memPercent = metrics?.memPercent,
                status = sys.status,
            )
        }

        val relatedAlerts = systems.flatMap { sys ->
            collectAlertsForSystem(sys)
        }.sortedByDescending { it.lastTriggeredAt }.take(MAX_ALERTS_DISPLAY)

        val recentLogErrors = queryRecentLogErrors(organizationId)

        val incident = runCatching {
            FeatureRegistry.getOnCallBridge()?.getIncident(incidentId.toInt(), userId)
                ?.let { info ->
                    IncidentSummary(
                        id = info.id.toLong(),
                        title = info.title,
                        priorityLevel = "unknown",
                        status = info.status,
                        triggeredAt = null,
                    )
                }
        }.getOrNull()

        return IncidentContextResponse(
            incidentId = incidentId,
            incident = incident,
            relatedAlerts = relatedAlerts,
            hostMetricsSummary = hostMetrics,
            recentLogErrors = recentLogErrors,
        )
    }

    private fun collectAlertsForSystem(sys: com.moneat.monitor.models.HostData): List<AlertSummary> =
        runCatching {
            monitorService.listAlerts(sys.id, sys.organizationId)
                .filter { it.lastTriggeredAt != null }
                .map { alert ->
                    AlertSummary(
                        systemId = alert.systemId,
                        systemName = sys.displayName ?: sys.hostname,
                        metric = alert.metric,
                        condition = alert.condition,
                        threshold = alert.threshold,
                        lastTriggeredAt = alert.lastTriggeredAt,
                    )
                }
        }.getOrElse { e ->
            logger.warn(e) { "Failed to collect alerts for system ${sys.id}" }
            emptyList()
        }

    private suspend fun queryRecentLogErrors(organizationId: Int): Long {
        return try {
            val query = """
                SELECT count() AS error_count
                FROM logs
                WHERE organization_id = $organizationId
                  AND level = 'error'
                  AND timestamp >= now() - INTERVAL 1 HOUR
                FORMAT JSON
            """.trimIndent()
            val response = ClickHouseClient.execute(query)
            if (!response.status.isSuccess()) return 0L
            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            val row = root["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return 0L
            row["error_count"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            logger.warn(e) { "Failed to query recent log errors" }
            0L
        }
    }
}
