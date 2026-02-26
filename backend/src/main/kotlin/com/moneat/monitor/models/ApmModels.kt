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

import kotlinx.serialization.Serializable

@Serializable
data class ApmSpanResponse(
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
data class ApmTraceDetailResponse(
    val traceId: String,
    val spans: List<ApmSpanResponse>,
)

@Serializable
data class ApmTraceListItem(
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
data class ApmTraceListResponse(
    val traces: List<ApmTraceListItem>,
    val totalCount: Long,
)

@Serializable
data class ApmServiceMapEntry(
    val service: String,
    val spanCount: Long,
    val errorCount: Long,
    val avgDurationNs: Double,
    val callsTo: List<String>,
)

@Serializable
data class ApmServiceMapResponse(
    val services: List<ApmServiceMapEntry>,
)

@Serializable
data class ApmErrorGroup(
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
data class ApmErrorsResponse(
    val errors: List<ApmErrorGroup>,
    val totalCount: Long,
)
