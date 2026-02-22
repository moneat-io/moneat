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

package com.moneat.llm.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// --- Ingestion DTOs ---

@Serializable
data class LlmGenerationIngest(
    @SerialName("trace_id") val traceId: String = "",
    @SerialName("span_id") val spanId: String = "",
    @SerialName("parent_span_id") val parentSpanId: String = "",
    val name: String = "",
    val model: String = "",
    val provider: String = "",
    val type: String = "chat",
    val input: JsonElement? = null,
    val output: JsonElement? = null,
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cost_usd") val costUsd: Double = 0.0,
    @SerialName("duration_ms") val durationMs: Double = 0.0,
    val status: String = "success",
    @SerialName("error_message") val errorMessage: String = "",
    @SerialName("status_code") val statusCode: Int = 0,
    val timestamp: String? = null,
    val temperature: Float = 0f,
    @SerialName("max_tokens") val maxTokens: Int = 0,
    @SerialName("top_p") val topP: Float = 0f,
    @SerialName("user_id") val userId: String = "",
    @SerialName("session_id") val sessionId: String = "",
    val environment: String = "",
    val release: String = "",
    val tags: Map<String, String> = emptyMap(),
    val metadata: JsonElement? = null
)

@Serializable
data class LlmIngestPayload(
    val generations: List<LlmGenerationIngest>
)

// --- Dashboard API response DTOs ---

@Serializable
data class LlmOverviewResponse(
    val totalGenerations: Long,
    val totalTokens: Long,
    val totalCost: Double,
    val avgDurationMs: Double,
    val errorRate: Double,
    val timeline: List<LlmTimelinePoint>,
    val topModels: List<LlmModelStats>
)

@Serializable
data class LlmTimelinePoint(
    val timestamp: String,
    val count: Long,
    val tokens: Long,
    val cost: Double,
    val errors: Long
)

@Serializable
data class LlmModelStats(
    val model: String,
    val provider: String,
    val callCount: Long,
    val totalTokens: Long,
    val totalCost: Double,
    val avgDurationMs: Double,
    val errorRate: Double
)

@Serializable
data class LlmGenerationResponse(
    val generationId: String,
    val traceId: String,
    val spanId: String,
    val parentSpanId: String,
    val timestamp: String,
    val durationMs: Double,
    val name: String,
    val model: String,
    val provider: String,
    val type: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val costUsd: Double,
    val status: String,
    val errorMessage: String,
    val userId: String,
    val environment: String,
    val release: String
)

@Serializable
data class LlmGenerationDetailResponse(
    val generationId: String,
    val traceId: String,
    val spanId: String,
    val parentSpanId: String,
    val timestamp: String,
    val durationMs: Double,
    val name: String,
    val model: String,
    val provider: String,
    val type: String,
    val input: String,
    val output: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val costUsd: Double,
    val temperature: Float,
    val maxTokens: Int,
    val topP: Float,
    val status: String,
    val errorMessage: String,
    val statusCode: Int,
    val userId: String,
    val sessionId: String,
    val environment: String,
    val release: String,
    val tags: Map<String, String>,
    val metadata: String
)

@Serializable
data class LlmGenerationsListResponse(
    val generations: List<LlmGenerationResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

@Serializable
data class LlmTraceResponse(
    val traceId: String,
    val generations: List<LlmGenerationDetailResponse>,
    val totalDurationMs: Double,
    val totalTokens: Long,
    val totalCost: Double
)

@Serializable
data class LlmCostBreakdown(
    val model: String,
    val provider: String,
    val totalCost: Double,
    val totalTokens: Long,
    val callCount: Long
)

@Serializable
data class LlmCostsResponse(
    val totalCost: Double,
    val breakdown: List<LlmCostBreakdown>,
    val timeline: List<LlmTimelinePoint>
)
