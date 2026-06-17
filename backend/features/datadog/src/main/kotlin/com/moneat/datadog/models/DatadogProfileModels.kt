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

// --- Profiling Models ---

/** Metadata from the profiling event JSON part. */
@Serializable
data class DdProfileEvent(
    val version: String = "4",
    val family: String = "",
    val start: String = "", // ISO 8601
    val end: String = "", // ISO 8601
    val tags: String = "", // comma-separated key:value pairs
    @SerialName("tags_profiler") val tagsProfiler: String = "",
    val endpoint: DdProfileEndpoint? = null,
)

@Serializable
data class DdProfileEndpoint(
    @SerialName("local_root_span_id") val localRootSpanId: Long = 0,
    @SerialName("trace_id") val traceId: Long = 0,
)

// --- Dashboard API response models ---

@Serializable
data class DdProfileResponse(
    val profileId: String,
    val host: String,
    val service: String,
    val env: String,
    val version: String,
    val runtime: String,
    val language: String,
    val profileType: String,
    val startTime: String,
    val endTime: String,
    val durationNs: Long,
    val sizeBytes: Long,
    val tags: Map<String, String>,
    val source: String = "datadog",
)

@Serializable
data class DdProfileListResponse(
    val profiles: List<DdProfileResponse>,
    val totalCount: Long,
)

/** A profile type and how many profiles of that type a service has. */
@Serializable
data class DdProfileTypeCount(
    val profileType: String,
    val count: Long,
)

/** A single bucket in a per-service activity sparkline (ts = epoch millis). */
@Serializable
data class DdProfileSeriesPoint(
    val ts: Long,
    val count: Long,
)

/** Per-service rollup powering the Profiles overview cards. */
@Serializable
data class DdProfileServiceSummary(
    val service: String,
    val languages: List<String>,
    val runtimes: List<String>,
    val environments: List<String>,
    val types: List<DdProfileTypeCount>,
    val hostCount: Long,
    val profileCount: Long,
    val totalSizeBytes: Long,
    val firstSeen: String,
    val lastSeen: String,
    val avgDurationNs: Long,
    val series: List<DdProfileSeriesPoint>,
)

@Serializable
data class DdProfileServicesResponse(
    val services: List<DdProfileServiceSummary>,
    val totalProfiles: Long,
    val totalSizeBytes: Long,
    val serviceCount: Int,
    val hostCount: Long,
    val typeCount: Int,
)

/** A bucket in a service/window profile-volume time series (ts = epoch millis). */
@Serializable
data class DdProfileTimeseriesPoint(
    val ts: Long,
    val count: Long,
    val sizeBytes: Long,
)

@Serializable
data class DdProfileTimeseriesResponse(
    val points: List<DdProfileTimeseriesPoint>,
    val bucketSeconds: Long,
)
