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

package com.moneat.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

typealias TagsMap = HashMap<String, String>
typealias AttributesMap = HashMap<String, String>

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
    val tags: HashMap<String, String>? = null,
    @SerialName("resource_attributes") val resourceAttributes: HashMap<String, String>? = null
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
    val tags: HashMap<String, String>? = null,
    @SerialName("resource_attributes") val resourceAttributes: HashMap<String, String>? = null
)

@Serializable
data class QueuedLogBatch(
    @SerialName("project_id") val projectId: Long,
    @SerialName("system_id") val systemId: String? = null,
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
    @SerialName("resource_attributes") val resourceAttributes: Map<String, String> = emptyMap(),
    @SerialName("system_id") val systemId: String? = null
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
    @SerialName("resource_attributes") val resourceAttributes: Map<String, String> = emptyMap(),
    @SerialName("system_id") val systemId: String? = null
)

@Serializable
data class LogQueryResponse(
    val logs: List<LogEntryResponse>,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("total_count") val totalCount: Long? = null
)

@Serializable
data class LogFilterOptionsResponse(
    val services: List<String>,
    val environments: List<String>,
    val levels: List<String>,
    @SerialName("tag_keys") val tagKeys: List<String>
)

@Serializable
data class LogTagValuesResponse(
    val key: String,
    val values: List<String>
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
    val tags: Map<String, String> = emptyMap(),
    val systemId: String? = null,
    val containerName: String? = null,
    val excludeService: String? = null,
    val excludeEnvironment: String? = null,
    val excludeContainerName: String? = null,
    val excludeTags: Map<String, String> = emptyMap()
)

data class LogTailFilters(
    val query: String? = null,
    val levels: Set<String> = emptySet(),
    val service: String? = null,
    val environment: String? = null
)

@Serializable
data class LogAggregateBucket(
    val timestamp: String,
    val count: Long,
    val groups: Map<String, Long> = emptyMap()
)

@Serializable
data class LogAggregateResponse(
    val buckets: List<LogAggregateBucket>,
    @SerialName("total_count") val totalCount: Long,
    val interval: String
)

@Serializable
data class LogTopValue(
    val value: String,
    val count: Long
)

@Serializable
data class LogTopResponse(
    val field: String,
    val values: List<LogTopValue>,
    @SerialName("total_count") val totalCount: Long
)

@Serializable
data class LogFilterOptionWithCount(
    val value: String,
    val count: Long
)

@Serializable
data class LogFilterOptionsWithCountsResponse(
    val services: List<LogFilterOptionWithCount>,
    val environments: List<LogFilterOptionWithCount>,
    val levels: List<String>,
    @SerialName("tag_keys") val tagKeys: List<String>
)
