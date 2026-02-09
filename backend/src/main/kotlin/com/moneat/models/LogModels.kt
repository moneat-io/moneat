package com.moneat.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LogIngestEntry(
    val timestamp: String? = null,
    @SerialName("timestamp_ms") val timestampMs: Long? = null,
    val level: String? = null,
    val message: String? = null,
    val body: String? = null,
    val service: String? = null,
    val environment: String? = null,
    val host: String? = null,
    val source: String? = null,
    @SerialName("container_name") val containerName: String? = null,
    @SerialName("container_id") val containerId: String? = null,
    @SerialName("container_image") val containerImage: String? = null,
    @SerialName("trace_id") val traceId: String? = null,
    @SerialName("span_id") val spanId: String? = null,
    val tags: Map<String, String> = emptyMap(),
    @SerialName("resource_attributes") val resourceAttributes: Map<String, String> = emptyMap()
)

@Serializable
data class AgentLogsRequest(
    @SerialName("project_id") val projectId: Long? = null,
    val logs: List<AgentLogEntry> = emptyList()
)

@Serializable
data class AgentLogEntry(
    val timestamp: String? = null,
    @SerialName("timestamp_ms") val timestampMs: Long? = null,
    val level: String? = null,
    val message: String? = null,
    val body: String? = null,
    val stream: String? = null,
    val service: String? = null,
    val environment: String? = null,
    val host: String? = null,
    @SerialName("container_name") val containerName: String? = null,
    @SerialName("container_id") val containerId: String? = null,
    @SerialName("container_image") val containerImage: String? = null,
    @SerialName("trace_id") val traceId: String? = null,
    @SerialName("span_id") val spanId: String? = null,
    val tags: Map<String, String> = emptyMap(),
    @SerialName("resource_attributes") val resourceAttributes: Map<String, String> = emptyMap()
)

@Serializable
data class QueuedLogBatch(
    @SerialName("project_id") val projectId: Long,
    val source: String,
    val logs: List<QueuedLogEntry>
)

@Serializable
data class QueuedLogEntry(
    @SerialName("log_id") val logId: String,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val level: String,
    val message: String,
    val body: String,
    val service: String,
    val environment: String,
    val host: String,
    val source: String,
    @SerialName("container_name") val containerName: String,
    @SerialName("container_id") val containerId: String,
    @SerialName("container_image") val containerImage: String,
    @SerialName("trace_id") val traceId: String,
    @SerialName("span_id") val spanId: String,
    val tags: Map<String, String> = emptyMap(),
    @SerialName("resource_attributes") val resourceAttributes: Map<String, String> = emptyMap()
)

@Serializable
data class LogEntryResponse(
    @SerialName("log_id") val logId: String,
    val timestamp: String,
    val level: String,
    val message: String,
    val body: String,
    val service: String,
    val environment: String,
    val host: String,
    val source: String,
    @SerialName("container_name") val containerName: String,
    @SerialName("container_id") val containerId: String,
    @SerialName("container_image") val containerImage: String,
    @SerialName("trace_id") val traceId: String,
    @SerialName("span_id") val spanId: String,
    val tags: Map<String, String> = emptyMap(),
    @SerialName("resource_attributes") val resourceAttributes: Map<String, String> = emptyMap()
)

@Serializable
data class LogQueryResponse(
    val logs: List<LogEntryResponse>,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean
)

@Serializable
data class LogFilterOptionsResponse(
    val services: List<String>,
    val environments: List<String>,
    val levels: List<String>,
    @SerialName("tag_keys") val tagKeys: List<String>
)

data class LogQueryRequest(
    val limit: Int = 100,
    val cursor: String? = null,
    val query: String? = null,
    val levels: List<String> = emptyList(),
    val service: String? = null,
    val environment: String? = null,
    val from: String? = null,
    val to: String? = null,
    val tags: Map<String, String> = emptyMap()
)

data class LogTailFilters(
    val query: String? = null,
    val levels: Set<String> = emptySet(),
    val service: String? = null,
    val environment: String? = null
)
