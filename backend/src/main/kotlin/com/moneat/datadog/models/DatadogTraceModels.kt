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

// --- APM Trace Models ---

/** A single DD span as received from the agent. */
@Serializable
data class DdSpan(
    @SerialName("trace_id") val traceId: ULong = 0u,
    @SerialName("span_id") val spanId: ULong = 0u,
    @SerialName("parent_id") val parentId: ULong = 0u,
    val name: String = "",
    val service: String = "",
    val resource: String = "",
    val type: String = "",
    val start: Long = 0, // nanoseconds since epoch
    val duration: Long = 0, // nanoseconds
    val error: Int = 0,
    val meta: Map<String, String> = emptyMap(),
    val metrics: Map<String, Double> = emptyMap(),
)

/** A trace is a list of spans. A payload contains multiple traces. */
typealias DdTrace = List<DdSpan>
typealias DdTracePayload = List<DdTrace>

/** Trace stats bucket from the agent. */
@Serializable
data class DdStatsBucket(
    @SerialName("Start") val start: Long = 0,
    @SerialName("Duration") val duration: Long = 0,
    @SerialName("Stats") val stats: List<DdStatsEntry> = emptyList(),
)

@Serializable
data class DdStatsEntry(
    @SerialName("Name") val name: String = "",
    @SerialName("Service") val service: String = "",
    @SerialName("Resource") val resource: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("HTTPStatusCode") val httpStatusCode: Int = 0,
    @SerialName("Synthetics") val synthetics: Boolean = false,
    @SerialName("Hits") val hits: Long = 0,
    @SerialName("TopLevelHits") val topLevelHits: Long = 0,
    @SerialName("Errors") val errors: Long = 0,
    @SerialName("Duration") val duration: Long = 0,
    @SerialName("OkSummary") val okSummary: DdSketchSummary? = null,
    @SerialName("ErrorSummary") val errorSummary: DdSketchSummary? = null,
)

@Serializable
data class DdSketchSummary(
    val count: Long = 0,
    val sum: Double = 0.0,
)

@Serializable
data class DdStatsPayload(
    @SerialName("Stats") val stats: List<DdStatsBucket> = emptyList(),
    @SerialName("Hostname") val hostname: String = "",
    @SerialName("Env") val env: String = "",
    @SerialName("Version") val version: String = "",
)

// --- Dashboard API response models ---

@Serializable
data class DdSpanResponse(
    val spanId: String,
    val traceId: String,
    val parentId: String,
    val name: String,
    val service: String,
    val resource: String,
    val type: String,
    val startNs: Long,
    val durationNs: Long,
    val error: Int,
    val meta: Map<String, String>,
    val metrics: Map<String, Double>,
    val host: String,
    val env: String,
    val version: String,
)

@Serializable
data class DdTraceDetailResponse(
    val traceId: String,
    val spans: List<DdSpanResponse>,
)

@Serializable
data class DdTraceListItem(
    val traceId: String,
    val rootService: String,
    val rootResource: String,
    val rootName: String,
    val spanCount: Int,
    val durationNs: Long,
    val startNs: Long,
    val hasError: Boolean,
)

@Serializable
data class DdTraceListResponse(
    val traces: List<DdTraceListItem>,
    val totalCount: Long,
)

@Serializable
data class DdServiceMapEntry(
    val service: String,
    val spanCount: Long,
    val errorCount: Long,
    val avgDurationNs: Double,
    val callsTo: List<String>,
)

@Serializable
data class DdServiceMapResponse(
    val services: List<DdServiceMapEntry>,
)

// --- APM Resource Stats Models ---

@Serializable
data class DdResourceStatsItem(
    val service: String,
    val resource: String,
    val name: String,
    val type: String,
    val totalHits: Long,
    val totalErrors: Long,
    val avgDurationNs: Long,
    val errorRate: Double,
)

@Serializable
data class DdResourceStatsResponse(
    val resources: List<DdResourceStatsItem>,
    val totalCount: Long,
)

// --- APM Error Models ---

@Serializable
data class DdApmErrorGroup(
    val id: String,
    val service: String,
    val resource: String,
    val errorMessage: String,
    val errorType: String,
    val count: Long,
    val lastSeen: String,
    val traceId: String,
)

@Serializable
data class DdApmErrorsResponse(
    val errors: List<DdApmErrorGroup>,
    val totalCount: Long,
)
