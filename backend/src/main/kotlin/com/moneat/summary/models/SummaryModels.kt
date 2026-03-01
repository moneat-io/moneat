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

package com.moneat.summary.models

import kotlinx.serialization.Serializable

// --- Infrastructure Summary ---

@Serializable
data class InfrastructureSummaryResponse(
    val period: String,
    val hostCounts: HostStatusCounts,
    val uptimeMonitors: List<UptimeMonitorSummary>,
    val topAlerts: List<AlertSummaryItem>,
    val topErrorRateHosts: List<HostErrorRate>
)

@Serializable
data class HostStatusCounts(
    val online: Int,
    val offline: Int,
    val warning: Int,
    val total: Int
)

@Serializable
data class UptimeMonitorSummary(
    val id: String,
    val name: String,
    val status: String,
    val uptime24h: Float?,
    val uptime7d: Float?,
    val uptime30d: Float?
)

@Serializable
data class AlertSummaryItem(
    val alertId: Int,
    val systemId: String?,
    val metric: String,
    val condition: String,
    val threshold: Double,
    val lastTriggeredAt: Long?
)

@Serializable
data class HostErrorRate(
    val systemId: String,
    val systemName: String,
    val alertCount: Int
)

// --- Overnight Summary ---

@Serializable
data class OvernightSummaryResponse(
    val timezone: String,
    val windowStart: String,
    val windowEnd: String,
    val newIssues: List<IssueSummaryItem>,
    val regressedIssues: List<IssueSummaryItem>,
    val errorSpikes: ErrorSpikeInfo,
    val hostStatusChanges: List<HostStatusChange>,
    val uptimeIncidents: List<UptimeIncidentSummary>,
    val logErrorVolume: LogVolumeComparison
)

@Serializable
data class IssueSummaryItem(
    val issueId: String,
    val title: String,
    val eventCount: Long,
    val projectId: Long
)

@Serializable
data class ErrorSpikeInfo(
    val overnightCount: Long,
    val baselineCount: Long,
    val spikeDetected: Boolean
)

@Serializable
data class HostStatusChange(
    val systemId: String,
    val systemName: String,
    val previousStatus: String,
    val currentStatus: String,
    val changedAt: String
)

@Serializable
data class UptimeIncidentSummary(
    val monitorId: String,
    val monitorName: String,
    val downtimeMinutes: Int
)

@Serializable
data class LogVolumeComparison(
    val overnightErrors: Long,
    val previousNightErrors: Long,
    val changePercent: Double
)

// --- Weekly Report ---

@Serializable
data class WeeklyReportResponse(
    val periodStart: String,
    val periodEnd: String,
    val errorTrend: List<DailyErrorCount>,
    val weekOverWeekDelta: Double,
    val topTransactionLatencies: List<TransactionLatencySummary>,
    val uptimeSummary: List<UptimeMonitorSummary>,
    val incidentCount: Int,
    val mttrMinutes: Double?,
    val topNoisyIssues: List<IssueSummaryItem>,
    val hostResourceTrends: HostResourceTrend
)

@Serializable
data class DailyErrorCount(
    val date: String,
    val count: Long
)

@Serializable
data class TransactionLatencySummary(
    val name: String,
    val p95: Double,
    val count: Long
)

@Serializable
data class HostResourceTrend(
    val avgCpuPercent: Double,
    val avgMemoryPercent: Double,
    val hostCount: Int
)

// --- Incident Context ---

@Serializable
data class IncidentContextResponse(
    val incidentId: Long,
    val triggeringAlert: AlertContextInfo?,
    val hostMetrics: HostMetricsWindow?,
    val relatedLogs: List<RelatedLogEntry>,
    val errorSpikes: List<ErrorSpikeEntry>,
    val recentDeployments: List<DeploymentInfo>,
    val affectedUptimeMonitors: List<UptimeMonitorSummary>
)

@Serializable
data class AlertContextInfo(
    val alertId: Int,
    val metric: String,
    val condition: String,
    val threshold: Double,
    val systemId: String?,
    val systemName: String?
)

@Serializable
data class HostMetricsWindow(
    val systemId: String,
    val systemName: String,
    val avgCpu: Double,
    val maxCpu: Double,
    val avgMemory: Double,
    val maxMemory: Double,
    val windowStart: String,
    val windowEnd: String
)

@Serializable
data class RelatedLogEntry(
    val timestamp: String,
    val level: String,
    val message: String,
    val service: String?
)

@Serializable
data class ErrorSpikeEntry(
    val issueId: String,
    val title: String,
    val count: Long,
    val firstSeen: String
)

@Serializable
data class DeploymentInfo(
    val version: String,
    val projectId: Long,
    val createdAt: String
)
