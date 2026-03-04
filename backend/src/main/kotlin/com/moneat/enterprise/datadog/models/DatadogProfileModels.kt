// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.models

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
