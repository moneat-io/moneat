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

package com.moneat.overview.models

import kotlinx.serialization.Serializable

@Serializable
data class OverviewResponse(
    val systemStatus: OverviewSystemStatus,
    val kpis: List<OverviewKpi>,
    val serviceHealth: List<OverviewServiceRow>,
    val telemetry: OverviewTelemetryData,
    val triage: OverviewTriageData,
    val infra: OverviewInfraData,
    val uptime: OverviewUptimeData,
    val deploys: List<OverviewDeployRow>,
    val activity: List<OverviewActivityItem>,
)

@Serializable
data class OverviewSystemStatus(
    val state: String,
    val severity: String,
    val counts: OverviewCounts,
    val ai: OverviewStatusAi,
)

@Serializable
data class OverviewCounts(
    val incidents: Int,
    val alerts: Int,
    val degraded: Int,
    val hostsOffline: Int,
)

@Serializable
data class OverviewStatusAi(
    val summary: String,
    val incidentId: String? = null,
)

@Serializable
data class OverviewKpi(
    val id: String,
    val label: String,
    val value: String,
    val unit: String? = null,
    val delta: OverviewKpiDelta,
    val status: String,
    val spark: List<Int>,
)

@Serializable
data class OverviewKpiDelta(
    val value: String,
    val direction: String? = null,
    val tone: String,
)

@Serializable
data class OverviewServiceRow(
    val name: String,
    val env: String,
    val status: String,
    val reqPerMin: Int,
    val errorPct: Double,
    val p95Ms: Int? = null,
    val apdex: Double? = null,
    val trend: List<Int>,
    val issues: Int,
    val lag: String? = null,
    val deploy: OverviewServiceDeploy,
)

@Serializable
data class OverviewServiceDeploy(
    val version: String,
    val ageLabel: String,
    val tone: String,
)

@Serializable
data class OverviewTelemetryData(
    val errors: List<Int>,
    val latency: List<Int>,
    val throughput: List<Int>,
    val logs: List<Int>,
    val deployAtPct: Int,
    val deployLabel: String,
)

@Serializable
data class OverviewTriageData(
    val incidents: List<OverviewIncidentItem>,
    val alerts: List<OverviewAlertItem>,
    val issues: List<OverviewIssueItem>,
    val security: List<OverviewSecurityItem>,
)

@Serializable
data class OverviewIncidentItem(
    val id: String,
    val title: String,
    val priority: String,
    val status: String,
    val owner: String,
    val ageLabel: String,
)

@Serializable
data class OverviewAlertItem(
    val title: String,
    val detail: String,
    val level: String,
    val ageLabel: String,
)

@Serializable
data class OverviewIssueItem(
    val level: String,
    val title: String,
    val detail: String,
    val ageLabel: String,
)

@Serializable
data class OverviewSecurityItem(
    val title: String,
    val detail: String,
    val level: String,
    val ageLabel: String,
)

@Serializable
data class OverviewInfraData(
    val gauges: List<OverviewInfraGauge>,
    val containers: Int,
    val pods: Int,
    val upLabel: String,
    val offlineNode: String? = null,
)

@Serializable
data class OverviewInfraGauge(
    val label: String,
    val pct: Int,
    val tone: String,
)

@Serializable
data class OverviewUptimeData(
    val monitors: List<OverviewUptimeMonitor>,
    val upLabel: String,
    val syntheticFailing: String? = null,
    val statusPages: String,
)

@Serializable
data class OverviewUptimeMonitor(
    val name: String,
    val bars: List<String>,
    val uptimeLabel: String,
    val down: Boolean = false,
)

@Serializable
data class OverviewDeployRow(
    val version: String,
    val service: String,
    val status: String,
    val label: String,
    val ageLabel: String,
)

@Serializable
data class OverviewActivityItem(
    val kind: String,
    val text: String,
    val meta: String,
)
