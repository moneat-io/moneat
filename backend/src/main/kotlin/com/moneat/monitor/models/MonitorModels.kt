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

package com.moneat.monitor.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.*
import kotlin.time.Instant

object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): UUID {
        return UUID.fromString(decoder.decodeString())
    }
}

object KotlinInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("KotlinInstant", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeLong(value.toEpochMilliseconds())
    }

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.fromEpochMilliseconds(decoder.decodeLong())
    }
}

// Dashboard-facing models
@Serializable
data class HostResponse(
    val id: Int,
    @SerialName("project_id") val projectId: Long,
    val name: String,
    val hostname: String,
    val status: String,
    val last_seen_at: Long?,
    @SerialName("first_seen_at") val firstSeenAt: Long? = null,
    val agent_version: String?,
    val os: String?,
    val arch: String?,
    val platform: String? = null,
    val processor: String? = null,
    @SerialName("cpu_cores") val cpuCores: Int? = null,
    @SerialName("memory_total_kb") val memoryTotalKb: Long? = null,
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
data class HistoricalMetricsResponse(
    val system_id: String,
    val host_id: Int? = null,
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
data class ContainerWithSystem(
    @SerialName("system_id") val systemId: String,
    @SerialName("host_id") val hostId: Int? = null,
    @SerialName("system_name") val systemName: String,
    val name: String,
    val id: String,
    val image: String,
    val status: String,
    @SerialName("cpu_percent") val cpuPercent: Float,
    @SerialName("mem_used") val memUsed: Long,
    @SerialName("mem_limit") val memLimit: Long,
    @SerialName("net_recv_bytes") val netRecvBytes: Long,
    @SerialName("net_sent_bytes") val netSentBytes: Long,
    @SerialName("mem_percent") val memPercent: Float
)

@Serializable
data class AllContainersResponse(
    val containers: List<ContainerWithSystem>
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
    @SerialName("host_id") val hostId: Int? = null,
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
@Serializable
data class HostData(
    val id: Int,
    val organizationId: Int,
    val hostname: String,
    val displayName: String?,
    val status: String,
    @Serializable(with = KotlinInstantSerializer::class)
    val lastSeenAt: kotlin.time.Instant?,
    val agentVersion: String?,
    val os: String?,
    val arch: String?,
    val platform: String? = null,
    val processor: String? = null,
    val cpuCores: Int? = null,
    val memoryTotalKb: Long? = null,
    @Serializable(with = KotlinInstantSerializer::class)
    val firstSeenAt: kotlin.time.Instant,
    @Serializable(with = KotlinInstantSerializer::class)
    val createdAt: kotlin.time.Instant
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
    val hostId: Int,
    val organizationId: Int,
    val metric: String,
    val condition: String,
    val threshold: Double,
    val durationSeconds: Int,
    val enabled: Boolean,
    val lastTriggeredAt: kotlin.time.Instant?,
    val createdAt: kotlin.time.Instant,
    val scope: String = "host",
    val templateAlertId: Int? = null
)

/** Row returned from HostAlerts or OrganizationAlertTemplates by repository methods. */
data class AlertRow(
    val id: Int,
    val hostId: Int,
    val organizationId: Int,
    val metric: String,
    val condition: String,
    val threshold: Double,
    val durationSeconds: Int,
    val enabled: Boolean,
    val lastTriggeredAt: kotlin.time.Instant?,
    val createdAt: kotlin.time.Instant,
    val scope: String = "host"
)

/** Row returned from HostAlertSettings by repository methods. */
data class AlertSettingRow(
    val hostId: Int,
    val organizationId: Int,
    val scope: String,
    val updatedAt: kotlin.time.Instant
)

/** Data required to create a new alert (host-scoped or global). */
data class CreateAlertData(
    val hostId: Int,
    val organizationId: Int,
    val metric: String,
    val condition: String,
    val threshold: Double,
    val durationSeconds: Int,
    val enabled: Boolean,
    val scope: String
)

/** Partial update fields for an existing alert. */
data class UpdateAlertData(
    val metric: String? = null,
    val condition: String? = null,
    val threshold: Double? = null,
    val durationSeconds: Int? = null,
    val enabled: Boolean? = null
)

@Serializable
data class CreateAgentApiKeyRequest(
    val name: String
)

@Serializable
data class CreateAgentApiKeyResponse(
    val id: Int,
    val name: String,
    @SerialName("key_prefix") val keyPrefix: String,
    val key: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class AgentApiKeyResponse(
    val id: Int,
    val name: String,
    @SerialName("key_prefix") val keyPrefix: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String? = null
)
