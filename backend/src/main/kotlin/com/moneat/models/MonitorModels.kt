// Moneat - Mobile-First Error Monitoring Platform
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

package com.moneat.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.*

// Agent-facing models
@Serializable
data class SystemMetricsPayload(
    val timestamp: Long,
    val cpu_percent: Float,
    val mem_total: Long,
    val mem_used: Long,
    val mem_available: Long,
    val swap_total: Long,
    val swap_used: Long,
    val disk_total: Long,
    val disk_used: Long,
    val disk_read_bytes: Long,
    val disk_write_bytes: Long,
    val net_recv_bytes: Long,
    val net_sent_bytes: Long,
    val load_1: Float,
    val load_5: Float,
    val load_15: Float,
    val temp_max: Float? = null,
    val gpu_percent: Float? = null,
    val gpu_mem_percent: Float? = null,
    val gpu_power: Float? = null,
    val battery_percent: Float? = null,
    val containers: List<ContainerMetricsPayload>? = null,
    val agent_version: String? = null,
    val os: String? = null,
    val arch: String? = null,
    val host: String? = null
)

@Serializable
data class ContainerMetricsPayload(
    val name: String,
    val id: String,
    val image: String,
    val status: String,
    val cpu_percent: Float,
    val mem_used: Long,
    val mem_limit: Long,
    val net_recv_bytes: Long,
    val net_sent_bytes: Long
)

@Serializable
data class IngestResponse(
    val success: Boolean,
    val interval_seconds: Int,
    val message: String? = null
)

@Serializable
data class AgentLogIngestResponse(
    val accepted: Int? = null,
    @SerialName("system_id") val systemId: String? = null,
    val error: String? = null,
    val message: String? = null,
    val reason: String? = null,
    val usage: BillingUsageResponse? = null
)

// Dashboard-facing models
@Serializable
data class SystemResponse(
    val id: String,
    @SerialName("project_id") val projectId: Long,
    val name: String,
    val host: String?,
    val status: String,
    val last_seen_at: Long?,
    val agent_version: String?,
    val os: String?,
    val arch: String?,
    val created_at: Long,
    val latest_metrics: LatestMetrics?
)

@Serializable
data class LatestMetrics(
    val cpu_percent: Float,
    val mem_total: Long,
    val mem_used: Long,
    val mem_percent: Float,
    val disk_total: Long,
    val disk_used: Long,
    val disk_percent: Float,
    val net_recv_bytes: Long,
    val net_sent_bytes: Long,
    val net_recv_mbps: Float?,
    val net_sent_mbps: Float?,
    val load_1: Float,
    val temp_max: Float?,
    val gpu_percent: Float?,
    val battery_percent: Float?
)

@Serializable
data class CreateSystemRequest(
    val name: String
)

@Serializable
data class CreateSystemResponse(
    val system: SystemResponse,
    val agent_key: String,
    val docker_command: String
)

@Serializable
data class HistoricalMetricsResponse(
    val system_id: String,
    val from: Long,
    val to: Long,
    val interval_seconds: Int,
    val data_points: List<MetricDataPoint>
)

@Serializable
data class MetricDataPoint(
    val timestamp: Long,
    val cpu_percent: Float?,
    val mem_percent: Float?,
    val disk_percent: Float?,
    val net_recv_bytes: Long?,
    val net_sent_bytes: Long?,
    val load_1: Float?,
    val load_5: Float?,
    val load_15: Float?,
    val temp_max: Float?,
    val gpu_percent: Float?,
    val battery_percent: Float?
)

@Serializable
data class ContainerStatsResponse(
    val containers: List<ContainerStats>
)

@Serializable
data class ContainerStats(
    val name: String,
    val id: String,
    val image: String,
    val status: String,
    val cpu_percent: Float,
    val mem_used: Long,
    val mem_limit: Long,
    val net_recv_bytes: Long,
    val net_sent_bytes: Long,
    val mem_percent: Float
)

@Serializable
data class ContainerMetricsResponse(
    val container_name: String,
    val from: Long,
    val to: Long,
    val interval_seconds: Int,
    val data_points: List<ContainerMetricDataPoint>
)

@Serializable
data class ContainerMetricDataPoint(
    val timestamp: Long,
    val cpu_percent: Float?,
    val mem_used: Long?,
    val mem_limit: Long?,
    val net_recv_bytes: Long?,
    val net_sent_bytes: Long?
)

@Serializable
data class AlertResponse(
    val id: Int,
    @SerialName("system_id") val systemId: String? = null,
    val scope: String = "system",
    val metric: String,
    val condition: String,
    val threshold: Double,
    @SerialName("duration_seconds") val durationSeconds: Int,
    val enabled: Boolean,
    @SerialName("last_triggered_at") val lastTriggeredAt: Long?,
    @SerialName("created_at") val createdAt: Long
)

@Serializable
data class AlertConfigResponse(
    val scope: String,
    @SerialName("global_alerts") val globalAlerts: List<AlertResponse>,
    @SerialName("system_alerts") val systemAlerts: List<AlertResponse>,
    @SerialName("effective_alerts") val effectiveAlerts: List<AlertResponse>
)

@Serializable
data class CreateAlertRequest(
    val metric: String,
    val condition: String,
    val threshold: Double,
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    val enabled: Boolean = true
)

@Serializable
data class UpdateAlertScopeRequest(
    val scope: String
)

@Serializable
data class UpdateAlertRequest(
    val metric: String? = null,
    val condition: String? = null,
    val threshold: Double? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    val enabled: Boolean? = null
)

// Internal data classes
data class SystemData(
    val id: UUID,
    val organizationId: Int,
    val name: String,
    val host: String?,
    val agentKeyHash: String,
    val status: String,
    val lastSeenAt: kotlinx.datetime.Instant?,
    val agentVersion: String?,
    val os: String?,
    val arch: String?,
    val createdAt: kotlinx.datetime.Instant,
    val updatedAt: kotlinx.datetime.Instant
)

@Serializable
data class CreateSilencePeriodRequest(
    val reason: String? = null,
    @SerialName("starts_at") val startsAt: Long,
    @SerialName("ends_at") val endsAt: Long
)

@Serializable
data class SilencePeriodResponse(
    val id: Int,
    @SerialName("organization_id") val organizationId: Int,
    val reason: String?,
    @SerialName("starts_at") val startsAt: Long,
    @SerialName("ends_at") val endsAt: Long,
    @SerialName("created_by") val createdBy: Int,
    @SerialName("created_at") val createdAt: Long
)

data class AlertData(
    val id: Int,
    val systemId: UUID,
    val organizationId: Int,
    val metric: String,
    val condition: String,
    val threshold: Double,
    val durationSeconds: Int,
    val enabled: Boolean,
    val lastTriggeredAt: kotlinx.datetime.Instant?,
    val createdAt: kotlinx.datetime.Instant,
    val scope: String = "system",
    val templateAlertId: Int? = null
)
