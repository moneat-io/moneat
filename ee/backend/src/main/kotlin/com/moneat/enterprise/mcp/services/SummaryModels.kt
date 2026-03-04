// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.services

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
    val id: Long,
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
    val incidentId: Long,
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
