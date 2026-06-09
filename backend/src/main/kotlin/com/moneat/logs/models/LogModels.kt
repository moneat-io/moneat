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

package com.moneat.logs.models

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
    @SerialName("service_namespace") val serviceNamespace: String? = null,
    val environment: String? = null,
    val host: String? = null,
    val source: String? = null,
    @SerialName("project_id") val projectId: Long? = null,
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
    @SerialName("organization_id") val organizationId: Long? = null,
    @SerialName("project_id") val legacyProjectId: Long? = null,
    @SerialName("system_id") val systemId: String? = null,
    @SerialName("host_id") val hostId: Int? = null,
    val source: String,
    val logs: List<QueuedLogEntry>
) {
    /** Effective org ID: prefers organization_id, falls back to legacy project_id for backward compatibility. */
    val effectiveOrganizationId: Long
        get() = organizationId ?: legacyProjectId ?: 0L
}

@Serializable
data class QueuedLogEntry(
    @SerialName("log_id") val logId: String,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val level: String,
    val message: String,
    val body: String,
    val service: String,
    @SerialName("service_namespace") val serviceNamespace: String = "",
    val environment: String,
    val host: String,
    val source: String,
    @SerialName("project_id") val projectId: Long? = null,
    @SerialName("container_name") val containerName: String,
    @SerialName("container_id") val containerId: String,
    @SerialName("container_image") val containerImage: String,
    @SerialName("trace_id") val traceId: String,
    @SerialName("span_id") val spanId: String,
    val tags: Map<String, String> = emptyMap(),
    @SerialName("resource_attributes") val resourceAttributes: Map<String, String> = emptyMap(),
    @SerialName("system_id") val systemId: String? = null,
    @SerialName("index_name") val indexName: String = ""
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
    @SerialName("system_id") val systemId: String? = null,
    @SerialName("host_id") val hostId: Int? = null
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
    val host: String? = null,
    val traceId: String? = null,
    val messagePattern: String? = null,
    val from: String? = null,
    val to: String? = null,
    val tags: Map<String, String> = emptyMap(),
    val systemId: String? = null,
    val hostId: Int? = null,
    val containerName: String? = null,
    val excludeService: String? = null,
    val excludeEnvironment: String? = null,
    val excludeContainerName: String? = null,
    val excludeTags: Map<String, String> = emptyMap()
)

data class LogAnalyticsFilters(
    val from: String? = null,
    val to: String? = null,
    val query: String? = null,
    val levels: List<String> = emptyList(),
    val service: String? = null,
    val environment: String? = null,
    val host: String? = null,
    val traceId: String? = null,
    val messagePattern: String? = null,
    val tags: Map<String, String> = emptyMap(),
    val excludeService: String? = null,
    val excludeEnvironment: String? = null,
    val excludeContainerName: String? = null,
    val excludeTags: Map<String, String> = emptyMap()
)

data class LogTailFilters(
    val query: String? = null,
    val levels: Set<String> = emptySet(),
    val service: String? = null,
    val environment: String? = null,
    @SerialName("container_name") val containerName: String? = null,
    val tags: Map<String, String> = emptyMap(),
    @SerialName("exclude_service") val excludeService: String? = null,
    @SerialName("exclude_environment") val excludeEnvironment: String? = null,
    @SerialName("exclude_container_name") val excludeContainerName: String? = null,
    @SerialName("exclude_tags") val excludeTags: Map<String, String> = emptyMap()
)

data class LogPatternRequest(
    val logId: String? = null,
    val message: String? = null,
    val service: String? = null,
    val from: String? = null,
    val to: String? = null
)

@Serializable
data class LogPatternBreakdown(
    val value: String,
    val count: Long
)

@Serializable
data class LogPatternResponse(
    val pattern: String,
    val level: String,
    val count: Long,
    @SerialName("window_label") val windowLabel: String,
    @SerialName("first_seen") val firstSeen: String,
    @SerialName("last_seen") val lastSeen: String,
    @SerialName("trend_pct") val trendPct: Int? = null,
    val sparkline: List<Long> = emptyList(),
    @SerialName("top_services") val topServices: List<LogPatternBreakdown> = emptyList(),
    @SerialName("top_hosts") val topHosts: List<LogPatternBreakdown> = emptyList()
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

@Serializable
data class LogIndexResponse(
    val id: Int,
    val name: String,
    @SerialName("filter_query") val filterQuery: String,
    @SerialName("retention_days") val retentionDays: Int,
    @SerialName("sampling_rate") val samplingRate: Float,
    val priority: Int,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("daily_quota_gb") val dailyQuotaGb: Float? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreateLogIndexRequest(
    val name: String,
    @SerialName("filter_query") val filterQuery: String = "",
    @SerialName("retention_days") val retentionDays: Int = 30,
    @SerialName("sampling_rate") val samplingRate: Float = 1.0f,
    val priority: Int = 0,
    @SerialName("daily_quota_gb") val dailyQuotaGb: Float? = null
)

@Serializable
data class UpdateLogIndexRequest(
    val name: String? = null,
    @SerialName("filter_query") val filterQuery: String? = null,
    @SerialName("retention_days") val retentionDays: Int? = null,
    @SerialName("sampling_rate") val samplingRate: Float? = null,
    val priority: Int? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("daily_quota_gb") val dailyQuotaGb: Float? = null
)

@Serializable
data class LogIndexTestResponse(
    @SerialName("match_count") val matchCount: Long,
    @SerialName("total_count") val totalCount: Long
)

@Serializable
data class LogIndexUsageResponse(
    @SerialName("index_name") val indexName: String,
    @SerialName("bytes_today") val bytesToday: Long,
    @SerialName("count_today") val countToday: Long,
    @SerialName("quota_gb") val quotaGb: Float? = null,
    @SerialName("retention_days") val retentionDays: Int? = null
)

@Serializable
data class LogPipelineStep(
    val type: String,
    val enabled: Boolean = true,
    val condition: String = "",
    @SerialName("source_field") val sourceField: String = "",
    @SerialName("target_field") val targetField: String = "",
    val pattern: String = "",
    val replacement: String = "",
    val value: String = "",
    val tags: Map<String, String> = emptyMap()
)

@Serializable
data class LogPipelineResponse(
    val id: Int,
    val name: String,
    val description: String = "",
    val steps: List<LogPipelineStep> = emptyList(),
    val priority: Int,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreateLogPipelineRequest(
    val name: String,
    val description: String = "",
    val steps: List<LogPipelineStep> = emptyList(),
    val priority: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
data class UpdateLogPipelineRequest(
    val name: String? = null,
    val description: String? = null,
    val steps: List<LogPipelineStep>? = null,
    val priority: Int? = null,
    @SerialName("is_active") val isActive: Boolean? = null
)

@Serializable
data class LogPipelinePreviewEntry(
    val level: String = "info",
    val message: String = "",
    val body: String = "",
    val service: String = "",
    val environment: String = "",
    val host: String = "",
    val tags: Map<String, String> = emptyMap(),
    @SerialName("resource_attributes") val resourceAttributes: Map<String, String> = emptyMap()
)

@Serializable
data class LogPipelinePreviewRequest(
    val steps: List<LogPipelineStep> = emptyList(),
    @SerialName("sample_logs") val sampleLogs: List<LogPipelinePreviewEntry> = emptyList()
)

@Serializable
data class LogPipelinePreviewResult(
    val before: LogPipelinePreviewEntry,
    val after: LogPipelinePreviewEntry? = null,
    val dropped: Boolean = false
)

@Serializable
data class LogSavedViewState(
    val query: String = "",
    val levels: List<String> = emptyList(),
    val facets: Map<String, String> = emptyMap(),
    @SerialName("time_preset") val timePreset: String = "1h",
    val from: String? = null,
    val to: String? = null,
    val visualization: String = "timeline",
    @SerialName("group_by") val groupBy: String? = null,
    @SerialName("top_field") val topField: String? = null
)

@Serializable
data class LogSavedViewResponse(
    val id: Int,
    val name: String,
    val state: LogSavedViewState,
    @SerialName("is_shared") val isShared: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreateLogSavedViewRequest(
    val name: String,
    val state: LogSavedViewState,
    @SerialName("is_shared") val isShared: Boolean = true
)

@Serializable
data class UpdateLogSavedViewRequest(
    val name: String? = null,
    val state: LogSavedViewState? = null,
    @SerialName("is_shared") val isShared: Boolean? = null
)

@Serializable
data class LogMetricRuleResponse(
    val id: Int,
    val name: String,
    val query: String = "",
    val levels: List<String> = emptyList(),
    @SerialName("group_by") val groupBy: String? = null,
    val interval: String = "5m",
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreateLogMetricRuleRequest(
    val name: String,
    val query: String = "",
    val levels: List<String> = emptyList(),
    @SerialName("group_by") val groupBy: String? = null,
    val interval: String = "5m",
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
data class UpdateLogMetricRuleRequest(
    val name: String? = null,
    val query: String? = null,
    val levels: List<String>? = null,
    @SerialName("group_by") val groupBy: String? = null,
    val interval: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null
)

@Serializable
data class LogMonitorResponse(
    val id: Int,
    val name: String,
    val query: String = "",
    val levels: List<String> = emptyList(),
    @SerialName("group_by") val groupBy: String? = null,
    val condition: String = ">",
    val threshold: Double,
    @SerialName("warning_threshold") val warningThreshold: Double? = null,
    @SerialName("window_minutes") val windowMinutes: Int,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreateLogMonitorRequest(
    val name: String,
    val query: String = "",
    val levels: List<String> = emptyList(),
    @SerialName("group_by") val groupBy: String? = null,
    val condition: String = ">",
    val threshold: Double,
    @SerialName("warning_threshold") val warningThreshold: Double? = null,
    @SerialName("window_minutes") val windowMinutes: Int = 5,
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
data class UpdateLogMonitorRequest(
    val name: String? = null,
    val query: String? = null,
    val levels: List<String>? = null,
    @SerialName("group_by") val groupBy: String? = null,
    val condition: String? = null,
    val threshold: Double? = null,
    @SerialName("warning_threshold") val warningThreshold: Double? = null,
    @SerialName("window_minutes") val windowMinutes: Int? = null,
    @SerialName("is_active") val isActive: Boolean? = null
)

@Serializable
data class LogMonitorDraftRequest(
    val name: String,
    val query: String = "",
    val levels: List<String> = emptyList(),
    @SerialName("group_by") val groupBy: String? = null,
    val condition: String = ">",
    val threshold: Double,
    @SerialName("warning_threshold") val warningThreshold: Double? = null,
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    @SerialName("dashboard_id") val dashboardId: Long? = null,
    @SerialName("widget_id") val widgetId: Long? = null
)

@Serializable
data class LogMonitorDraftResponse(
    val name: String,
    val query: String,
    val levels: List<String> = emptyList(),
    @SerialName("group_by") val groupBy: String? = null,
    val condition: String,
    val threshold: Double,
    @SerialName("warning_threshold") val warningThreshold: Double? = null,
    @SerialName("dashboard_alert_created") val dashboardAlertCreated: Boolean = false,
    @SerialName("dashboard_alert_id") val dashboardAlertId: Long? = null
)
