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

package com.moneat.datadog.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

// Database table for Datadog API keys
object DdApiKeys : Table("agent_api_keys") {
    val id = integer("id").autoIncrement()
    val organizationId = integer("organization_id")
    val projectId = integer("project_id").nullable()
    val name = varchar("name", 255)
    val keyHash = varchar("key_hash", 255).uniqueIndex()
    val keyPrefix = varchar("key_prefix", 12)
    val createdBy = integer("created_by").nullable()
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    val isActive = bool("is_active").default(true)
    override val primaryKey = PrimaryKey(id)
}

// DTOs for API
@Serializable
data class CreateDdApiKeyRequest(
    val name: String,
    val projectId: Int? = null,
)

@Serializable
data class DdApiKeyResponse(
    val id: Int,
    val organizationId: Int,
    val projectId: Int?,
    val name: String,
    val keyPrefix: String,
    val createdAt: String,
    val lastUsedAt: String?,
    val isActive: Boolean,
)

@Serializable
data class CreateDdApiKeyResponse(
    val id: Int,
    val name: String,
    val key: String, // Full key, only returned once
    val keyPrefix: String,
)

// --- Phase 2: Logs ---

@Serializable
data class DatadogLogEntry(
    val message: String = "",
    val hostname: String = "",
    val service: String = "",
    val status: String = "",
    val ddsource: String = "",
    val ddtags: String = "",
    val timestamp: Long? = null,
)

// --- Phase 2: Metrics ---

@Serializable
data class DatadogMetricSeriesV1(
    val series: List<DatadogMetricV1> = emptyList(),
)

@Serializable
data class DatadogMetricV1(
    val metric: String,
    val type: String = "gauge",
    val host: String = "",
    val tags: List<String> = emptyList(),
    val points: List<List<Double>> = emptyList(),
    val unit: String = "",
    @SerialName("source_type_name")
    val sourceTypeName: String = "",
)

@Serializable
data class DatadogSketchPayload(
    val sketches: List<DatadogSketch> = emptyList(),
)

@Serializable
data class DatadogSketch(
    val metric: String,
    val host: String = "",
    val tags: List<String> = emptyList(),
    val distributions: List<DatadogSketchPoint> = emptyList(),
)

@Serializable
data class DatadogSketchPoint(
    val ts: Long,
    val cnt: Long = 0,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val avg: Double = 0.0,
    val sum: Double = 0.0,
    val k: List<Int> = emptyList(),
    val n: List<Int> = emptyList(),
)

@Serializable
data class DogStatsDPayload(
    val proxy: List<String> = emptyList(),
)

// --- Phase 5: Events ---

@Serializable
data class DatadogEventPayload(
    val events: List<DatadogEvent> = emptyList(),
)

@Serializable
data class DatadogEvent(
    val title: String = "",
    val text: String = "",
    @SerialName("date_happened") val dateHappened: Long? = null,
    val priority: String = "normal",
    val host: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("alert_type") val alertType: String = "info",
    @SerialName("aggregation_key")
    val aggregationKey: String = "",
    @SerialName("source_type_name")
    val sourceTypeName: String = "",
    @SerialName("device_name") val deviceName: String = "",
)

@Serializable
data class DatadogServiceCheckPayload(
    @SerialName("service_checks")
    val serviceChecks: List<DatadogServiceCheck> = emptyList(),
)

@Serializable
data class DatadogServiceCheck(
    val check: String = "",
    @SerialName("host_name") val hostName: String = "",
    val status: Int = 0,
    val timestamp: Long? = null,
    val tags: List<String> = emptyList(),
    val message: String = "",
)

// --- Phase 5: Host Metadata ---

@Serializable
data class DatadogHostMetadata(
    @SerialName("hostname") val hostname: String = "",
    @SerialName("gohai") val gohai: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("host-meta")
    val hostMeta: DatadogHostMeta? = null,
    @SerialName("agent_version")
    val agentVersion: String = "",
    val os: String = "",
    val platform: String = "",
    @SerialName("processor") val processor: String = "",
)

@Serializable
data class DatadogHostMeta(
    @SerialName("os") val os: String = "",
    @SerialName("platform") val platform: String = "",
    @SerialName("processor") val processor: String = "",
    @SerialName("cpu_cores") val cpuCores: Int = 0,
    @SerialName("memory_total_kb")
    val memoryTotalKb: Long = 0,
)

@Serializable
data class DatadogIntakePayload(
    @SerialName("apiKey") val apiKey: String = "",
    @SerialName("gohai") val gohai: String = "",
    @SerialName("host-tags")
    val hostTags: DatadogHostTags? = null,
    @SerialName("meta")
    val meta: DatadogIntakeMeta? = null,
)

@Serializable
data class DatadogHostTags(
    val system: List<String> = emptyList(),
)

@Serializable
data class DatadogIntakeMeta(
    val hostname: String = "",
    // DD agent v6 uses "agent-version" (hyphen), v7 uses "agent_version" (underscore).
    // We capture both and pick whichever is non-blank.
    @SerialName("agent-version")
    val agentVersionHyphen: String = "",
    @SerialName("agent_version")
    val agentVersionUnderscore: String = "",
    val os: String = "",
    val platform: String = "",
) {
    val agentVersion: String get() = agentVersionHyphen.ifBlank { agentVersionUnderscore }
}

// --- Phase 6: Infrastructure ---

@Serializable
data class DatadogProcessPayload(
    val processes: List<DatadogProcess> = emptyList(),
    val host: String = "",
    @SerialName("group_id") val groupId: Int = 0,
)

@Serializable
data class DatadogProcess(
    val pid: Int = 0,
    val name: String = "",
    val command: String = "",
    val user: String = "",
    @SerialName("cpu_percent")
    val cpuPercent: Double = 0.0,
    @SerialName("mem_rss") val memRss: Long = 0,
    @SerialName("mem_vms") val memVms: Long = 0,
    val state: String = "",
    @SerialName("thread_count") val threadCount: Int = 0,
    @SerialName("open_fd_count")
    val openFdCount: Int = 0,
    val tags: List<String> = emptyList(),
)

@Serializable
data class DatadogContainerPayload(
    val containers: List<DatadogContainer> = emptyList(),
    val host: String = "",
)

@Serializable
data class DatadogContainer(
    @SerialName("container_id")
    val containerId: String = "",
    val name: String = "",
    val image: String = "",
    val state: String = "running",
    @SerialName("cpu_percent")
    val cpuPercent: Double = 0.0,
    @SerialName("mem_usage") val memUsage: Long = 0,
    @SerialName("mem_limit") val memLimit: Long = 0,
    @SerialName("net_rx_bytes")
    val netRxBytes: Long = 0,
    @SerialName("net_tx_bytes")
    val netTxBytes: Long = 0,
    val tags: List<String> = emptyList(),
)

@Serializable
data class DdServiceCheckListResponse(
    val serviceChecks: List<Map<String, String>>,
    val totalCount: Long,
)

@Serializable
data class DatadogConnectionsPayload(
    val connections: List<DatadogConnection> = emptyList(),
    val host: String = "",
)

@Serializable
data class DatadogConnection(
    val pid: Int = 0,
    @SerialName("local_addr")
    val localAddr: String = "",
    @SerialName("local_port") val localPort: Int = 0,
    @SerialName("remote_addr")
    val remoteAddr: String = "",
    @SerialName("remote_port")
    val remotePort: Int = 0,
    val protocol: String = "tcp",
    val family: String = "IPv4",
    val direction: String = "",
    @SerialName("bytes_sent") val bytesSent: Long = 0,
    @SerialName("bytes_recv") val bytesRecv: Long = 0,
    val tags: List<String> = emptyList(),
)
