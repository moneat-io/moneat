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

import kotlinx.serialization.Serializable

// ── Infrastructure Summary ────────────────────────────────────────────────────

@Serializable
data class InfrastructureSummaryResponse(
    val period: String,
    val hostSummary: HostStatusSummary,
    val uptimeMonitors: List<UptimeMonitorSummary>,
    val topAlerts: List<AlertSummary>,
    val topErrorHosts: List<HostErrorSummary>,
)

@Serializable
data class HostStatusSummary(
    val total: Int,
    val online: Int,
    val offline: Int,
    val warning: Int,
)

@Serializable
data class UptimeMonitorSummary(
    val id: String,
    val name: String,
    val status: String,
    val uptime24h: Float?,
    val uptime7d: Float?,
    val uptime30d: Float?,
)

@Serializable
data class AlertSummary(
    val systemId: String?,
    val systemName: String?,
    val metric: String,
    val condition: String,
    val threshold: Double,
    val lastTriggeredAt: Long?,
)

@Serializable
data class HostErrorSummary(
    val systemId: String,
    val systemName: String,
    val errorCount: Int,
)

// ── Overnight Summary ─────────────────────────────────────────────────────────

@Serializable
data class OvernightSummaryResponse(
    val timezone: String,
    val windowStart: String,
    val windowEnd: String,
    val hostStatusChanges: List<HostStatusChange>,
    val triggeredIncidents: List<IncidentSummary>,
    val logErrorVolume: LogVolumeSummary,
)

@Serializable
data class HostStatusChange(
    val systemId: String,
    val systemName: String,
    val fromStatus: String,
    val toStatus: String,
    val changedAt: Long,
)

@Serializable
data class IncidentSummary(
    val id: String,
    val title: String,
    val priorityLevel: String,
    val status: String,
    val triggeredAt: Long?,
)

@Serializable
data class LogVolumeSummary(
    val errorCount: Long,
    val warnCount: Long,
    val totalCount: Long,
)

// ── Weekly Report ─────────────────────────────────────────────────────────────

@Serializable
data class WeeklyReportResponse(
    val periodStart: String,
    val periodEnd: String,
    val uptimeMonitors: List<UptimeMonitorSummary>,
    val incidentStats: IncidentStats,
    val logTrend: List<DailyLogCount>,
)

@Serializable
data class IncidentStats(
    val total: Int,
    val resolved: Int,
    val avgResolutionMinutes: Double?,
)

@Serializable
data class DailyLogCount(
    val date: String,
    val errorCount: Long,
    val totalCount: Long,
)

// ── Incident Context ──────────────────────────────────────────────────────────

@Serializable
data class IncidentContextResponse(
    val incidentId: String,
    val incident: IncidentSummary?,
    val relatedAlerts: List<AlertSummary>,
    val hostMetricsSummary: List<HostMetricSnapshot>,
    val recentLogErrors: Long,
)

@Serializable
data class HostMetricSnapshot(
    val systemId: String,
    val systemName: String,
    val cpuPercent: Float?,
    val memPercent: Float?,
    val status: String,
)
