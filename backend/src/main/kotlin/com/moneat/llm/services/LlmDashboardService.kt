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

package com.moneat.llm.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.isClickHouseError
import com.moneat.llm.models.LlmCostBreakdown
import com.moneat.llm.models.LlmCostsResponse
import com.moneat.llm.models.LlmGenerationDetailResponse
import com.moneat.llm.models.LlmGenerationResponse
import com.moneat.llm.models.LlmGenerationsListResponse
import com.moneat.llm.models.LlmModelStats
import com.moneat.llm.models.LlmOverviewResponse
import com.moneat.llm.models.LlmTimelinePoint
import com.moneat.llm.models.LlmTraceResponse
import com.moneat.utils.ClickHouseSqlUtils
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class LlmDashboardService {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val json = Json { ignoreUnknownKeys = true }

    private fun projectIdClause(projectId: Long): String {
        return if (projectId < 0) {
            "toInt64(project_id) = $projectId"
        } else {
            "project_id = $projectId"
        }
    }

    private suspend fun extractBody(response: HttpResponse): String? {
        if (response.status != HttpStatusCode.OK) return null
        val body = response.bodyAsText()
        if (body.isClickHouseError()) {
            logger.warn { "ClickHouse error: ${body.take(200)}" }
            return null
        }
        return body
    }

    private fun intervalFromRange(range: String): String {
        return when (range) {
            "1h" -> "1 HOUR"
            "6h" -> "6 HOUR"
            "24h" -> "24 HOUR"
            "7d" -> "7 DAY"
            "14d" -> "14 DAY"
            "30d" -> "30 DAY"
            "90d" -> "90 DAY"
            else -> "24 HOUR"
        }
    }

    private fun bucketFromRange(range: String): String {
        return when (range) {
            "1h", "6h" -> "toStartOfFiveMinutes"
            "24h" -> "toStartOfHour"
            "7d", "14d" -> "toStartOfHour"
            "30d", "90d" -> "toStartOfDay"
            else -> "toStartOfHour"
        }
    }

    private fun nowClause(demoEpochMs: Long?): String {
        return if (demoEpochMs != null) {
            "toDateTime64(${demoEpochMs / 1000.0}, 3)"
        } else {
            "now()"
        }
    }

    private fun rangeClause(
        range: String,
        demoEpochMs: Long?
    ): String {
        val interval = intervalFromRange(range)
        return "timestamp >= ${nowClause(demoEpochMs)} - INTERVAL $interval"
    }

    suspend fun getOverview(
        projectId: Long,
        range: String,
        demoEpochMs: Long? = null
    ): LlmOverviewResponse {
        val bucket = bucketFromRange(range)
        val projectFilter = projectIdClause(projectId)
        val timeFilter = rangeClause(range, demoEpochMs)

        // Stats query
        val statsQuery =
            """
            SELECT
                count() as total_generations,
                sum(total_tokens) as total_tokens,
                sum(cost_usd) as total_cost,
                avg(duration_ms) as avg_duration_ms,
                countIf(status = 'error') * 100.0 / greatest(count(), 1) as error_rate
            FROM `$clickhouseDb`.llm_generations
            WHERE $projectFilter
              AND $timeFilter
            FORMAT JSONEachRow
            """.trimIndent()

        // Timeline query
        val timelineQuery =
            """
            SELECT
                formatDateTime($bucket(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts,
                count() as cnt,
                sum(total_tokens) as tokens,
                sum(cost_usd) as cost,
                countIf(status = 'error') as errors
            FROM `$clickhouseDb`.llm_generations
            WHERE $projectFilter
              AND $timeFilter
            GROUP BY ts
            ORDER BY ts
            FORMAT JSONEachRow
            """.trimIndent()

        // Top models query
        val modelsQuery =
            """
            SELECT
                model,
                provider,
                count() as call_count,
                sum(total_tokens) as total_tokens,
                sum(cost_usd) as total_cost,
                avg(duration_ms) as avg_duration_ms,
                countIf(status = 'error') * 100.0 / greatest(count(), 1) as error_rate
            FROM `$clickhouseDb`.llm_generations
            WHERE $projectFilter
              AND $timeFilter
            GROUP BY model, provider
            ORDER BY call_count DESC
            LIMIT 20
            FORMAT JSONEachRow
            """.trimIndent()

        val statsResponse = ClickHouseClient.execute(statsQuery)
        val timelineResponse = ClickHouseClient.execute(timelineQuery)
        val modelsResponse = ClickHouseClient.execute(modelsQuery)

        val statsBody = extractBody(statsResponse) ?: ""
        val statsLine = statsBody.trim().lines().firstOrNull()
        val statsObj = statsLine?.let { json.parseToJsonElement(it).jsonObject }

        val timeline =
            (extractBody(timelineResponse) ?: "").trim().lines().filter { it.isNotBlank() }.map { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                LlmTimelinePoint(
                    timestamp = obj["ts"]?.jsonPrimitive?.content ?: "",
                    count = obj["cnt"]?.jsonPrimitive?.longOrNull ?: 0,
                    tokens = obj["tokens"]?.jsonPrimitive?.longOrNull ?: 0,
                    cost = obj["cost"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    errors = obj["errors"]?.jsonPrimitive?.longOrNull ?: 0
                )
            }

        val topModels =
            (extractBody(modelsResponse) ?: "").trim().lines().filter { it.isNotBlank() }.map { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                LlmModelStats(
                    model = obj["model"]?.jsonPrimitive?.content ?: "",
                    provider = obj["provider"]?.jsonPrimitive?.content ?: "",
                    callCount = obj["call_count"]?.jsonPrimitive?.longOrNull ?: 0,
                    totalTokens = obj["total_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
                    totalCost = obj["total_cost"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    avgDurationMs = obj["avg_duration_ms"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    errorRate = obj["error_rate"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                )
            }

        return LlmOverviewResponse(
            totalGenerations = statsObj?.get("total_generations")?.jsonPrimitive?.longOrNull ?: 0,
            totalTokens = statsObj?.get("total_tokens")?.jsonPrimitive?.longOrNull ?: 0,
            totalCost = statsObj?.get("total_cost")?.jsonPrimitive?.doubleOrNull ?: 0.0,
            avgDurationMs = statsObj?.get("avg_duration_ms")?.jsonPrimitive?.doubleOrNull ?: 0.0,
            errorRate = statsObj?.get("error_rate")?.jsonPrimitive?.doubleOrNull ?: 0.0,
            timeline = timeline,
            topModels = topModels
        )
    }

    suspend fun getGenerations(
        projectId: Long,
        range: String,
        model: String?,
        provider: String?,
        type: String?,
        status: String?,
        page: Int,
        pageSize: Int,
        demoEpochMs: Long? = null
    ): LlmGenerationsListResponse {
        val offset = (page - 1) * pageSize
        val projectFilter = projectIdClause(projectId)
        val timeFilter = rangeClause(range, demoEpochMs)

        val filters =
            buildList {
                add(projectFilter)
                add(timeFilter)
                model?.let { add("model = '${ClickHouseSqlUtils.escapeSql(it)}'") }
                provider?.let { add("provider = '${ClickHouseSqlUtils.escapeSql(it)}'") }
                type?.let { add("type = '${ClickHouseSqlUtils.escapeSql(it)}'") }
                status?.let { add("status = '${ClickHouseSqlUtils.escapeSql(it)}'") }
            }
        val where = filters.joinToString(" AND ")

        val countQuery =
            """
            SELECT count() as total FROM `$clickhouseDb`.llm_generations WHERE $where FORMAT JSONEachRow
            """.trimIndent()

        val dataQuery =
            """
            SELECT
                toString(generation_id) as generation_id,
                trace_id, span_id, parent_span_id,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts,
                duration_ms, name, model, provider,
                toString(type) as type,
                input_tokens, output_tokens, total_tokens, cost_usd,
                toString(status) as status, error_message,
                user_id, environment, release
            FROM `$clickhouseDb`.llm_generations
            WHERE $where
            ORDER BY timestamp DESC
            LIMIT $pageSize OFFSET $offset
            FORMAT JSONEachRow
            """.trimIndent()

        val countResponse = ClickHouseClient.execute(countQuery)
        val dataResponse = ClickHouseClient.execute(dataQuery)

        val countBody = extractBody(countResponse) ?: ""
        val total =
            countBody.trim().lines().firstOrNull()?.let {
                json
                    .parseToJsonElement(it)
                    .jsonObject["total"]
                    ?.jsonPrimitive
                    ?.longOrNull
            } ?: 0

        val generations =
            (extractBody(dataResponse) ?: "").trim().lines().filter { it.isNotBlank() }.map { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                LlmGenerationResponse(
                    generationId = obj["generation_id"]?.jsonPrimitive?.content ?: "",
                    traceId = obj["trace_id"]?.jsonPrimitive?.content ?: "",
                    spanId = obj["span_id"]?.jsonPrimitive?.content ?: "",
                    parentSpanId = obj["parent_span_id"]?.jsonPrimitive?.content ?: "",
                    timestamp = obj["ts"]?.jsonPrimitive?.content ?: "",
                    durationMs = obj["duration_ms"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    model = obj["model"]?.jsonPrimitive?.content ?: "",
                    provider = obj["provider"]?.jsonPrimitive?.content ?: "",
                    type = obj["type"]?.jsonPrimitive?.content ?: "",
                    inputTokens = obj["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    outputTokens = obj["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalTokens = obj["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    costUsd = obj["cost_usd"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    status = obj["status"]?.jsonPrimitive?.content ?: "",
                    errorMessage = obj["error_message"]?.jsonPrimitive?.content ?: "",
                    userId = obj["user_id"]?.jsonPrimitive?.content ?: "",
                    environment = obj["environment"]?.jsonPrimitive?.content ?: "",
                    release = obj["release"]?.jsonPrimitive?.content ?: ""
                )
            }

        return LlmGenerationsListResponse(
            generations = generations,
            total = total,
            page = page,
            pageSize = pageSize
        )
    }

    suspend fun getGenerationDetail(
        projectId: Long,
        generationId: String
    ): LlmGenerationDetailResponse? {
        val escapedId = ClickHouseSqlUtils.escapeSql(generationId)
        val projectFilter = projectIdClause(projectId)
        val query =
            """
            SELECT
                toString(generation_id) as generation_id,
                trace_id, span_id, parent_span_id,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts,
                duration_ms, name, model, provider,
                toString(type) as type,
                input, output,
                input_tokens, output_tokens, total_tokens, cost_usd,
                temperature, max_tokens, top_p,
                toString(status) as status, error_message, status_code,
                user_id, session_id, environment, release,
                tags, metadata
            FROM `$clickhouseDb`.llm_generations
            WHERE $projectFilter AND toString(generation_id) = '$escapedId'
            LIMIT 1
            FORMAT JSONEachRow
            """.trimIndent()

        val response = ClickHouseClient.execute(query)
        val body = extractBody(response) ?: return null
        val line = body.trim().lines().firstOrNull() ?: return null
        val obj = json.parseToJsonElement(line).jsonObject

        val tagsObj = obj["tags"]?.jsonObject
        val tagsMap = tagsObj?.entries?.associate { (k, v) -> k to (v.jsonPrimitive.contentOrNull ?: "") } ?: emptyMap()

        return LlmGenerationDetailResponse(
            generationId = obj["generation_id"]?.jsonPrimitive?.content ?: "",
            traceId = obj["trace_id"]?.jsonPrimitive?.content ?: "",
            spanId = obj["span_id"]?.jsonPrimitive?.content ?: "",
            parentSpanId = obj["parent_span_id"]?.jsonPrimitive?.content ?: "",
            timestamp = obj["ts"]?.jsonPrimitive?.content ?: "",
            durationMs = obj["duration_ms"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            name = obj["name"]?.jsonPrimitive?.content ?: "",
            model = obj["model"]?.jsonPrimitive?.content ?: "",
            provider = obj["provider"]?.jsonPrimitive?.content ?: "",
            type = obj["type"]?.jsonPrimitive?.content ?: "",
            input = obj["input"]?.jsonPrimitive?.content ?: "",
            output = obj["output"]?.jsonPrimitive?.content ?: "",
            inputTokens = obj["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            outputTokens = obj["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = obj["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            costUsd = obj["cost_usd"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            temperature = obj["temperature"]?.jsonPrimitive?.floatOrNull ?: 0f,
            maxTokens = obj["max_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            topP = obj["top_p"]?.jsonPrimitive?.floatOrNull ?: 0f,
            status = obj["status"]?.jsonPrimitive?.content ?: "",
            errorMessage = obj["error_message"]?.jsonPrimitive?.content ?: "",
            statusCode = obj["status_code"]?.jsonPrimitive?.intOrNull ?: 0,
            userId = obj["user_id"]?.jsonPrimitive?.content ?: "",
            sessionId = obj["session_id"]?.jsonPrimitive?.content ?: "",
            environment = obj["environment"]?.jsonPrimitive?.content ?: "",
            release = obj["release"]?.jsonPrimitive?.content ?: "",
            tags = tagsMap,
            metadata = obj["metadata"]?.jsonPrimitive?.content ?: "{}"
        )
    }

    suspend fun getTrace(
        projectId: Long,
        traceId: String
    ): LlmTraceResponse? {
        val escapedTraceId = ClickHouseSqlUtils.escapeSql(traceId)
        val projectFilter = projectIdClause(projectId)
        val query =
            """
            SELECT
                toString(generation_id) as generation_id,
                trace_id, span_id, parent_span_id,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts,
                duration_ms, name, model, provider,
                toString(type) as type,
                input, output,
                input_tokens, output_tokens, total_tokens, cost_usd,
                temperature, max_tokens, top_p,
                toString(status) as status, error_message, status_code,
                user_id, session_id, environment, release,
                tags, metadata
            FROM `$clickhouseDb`.llm_generations
            WHERE $projectFilter AND trace_id = '$escapedTraceId'
            ORDER BY timestamp ASC
            FORMAT JSONEachRow
            """.trimIndent()

        val response = ClickHouseClient.execute(query)
        val body = extractBody(response) ?: return null
        val lines = body.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val generations =
            lines.map { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                val tagsObj = obj["tags"]?.jsonObject
                val tagsMap =
                    tagsObj?.entries?.associate { (k, v) ->
                        k to (v.jsonPrimitive.contentOrNull ?: "")
                    } ?: emptyMap()

                LlmGenerationDetailResponse(
                    generationId = obj["generation_id"]?.jsonPrimitive?.content ?: "",
                    traceId = obj["trace_id"]?.jsonPrimitive?.content ?: "",
                    spanId = obj["span_id"]?.jsonPrimitive?.content ?: "",
                    parentSpanId = obj["parent_span_id"]?.jsonPrimitive?.content ?: "",
                    timestamp = obj["ts"]?.jsonPrimitive?.content ?: "",
                    durationMs = obj["duration_ms"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    model = obj["model"]?.jsonPrimitive?.content ?: "",
                    provider = obj["provider"]?.jsonPrimitive?.content ?: "",
                    type = obj["type"]?.jsonPrimitive?.content ?: "",
                    input = obj["input"]?.jsonPrimitive?.content ?: "",
                    output = obj["output"]?.jsonPrimitive?.content ?: "",
                    inputTokens = obj["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    outputTokens = obj["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalTokens = obj["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    costUsd = obj["cost_usd"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    temperature = obj["temperature"]?.jsonPrimitive?.floatOrNull ?: 0f,
                    maxTokens = obj["max_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    topP = obj["top_p"]?.jsonPrimitive?.floatOrNull ?: 0f,
                    status = obj["status"]?.jsonPrimitive?.content ?: "",
                    errorMessage = obj["error_message"]?.jsonPrimitive?.content ?: "",
                    statusCode = obj["status_code"]?.jsonPrimitive?.intOrNull ?: 0,
                    userId = obj["user_id"]?.jsonPrimitive?.content ?: "",
                    sessionId = obj["session_id"]?.jsonPrimitive?.content ?: "",
                    environment = obj["environment"]?.jsonPrimitive?.content ?: "",
                    release = obj["release"]?.jsonPrimitive?.content ?: "",
                    tags = tagsMap,
                    metadata = obj["metadata"]?.jsonPrimitive?.content ?: "{}"
                )
            }

        return LlmTraceResponse(
            traceId = traceId,
            generations = generations,
            totalDurationMs = generations.sumOf { it.durationMs },
            totalTokens = generations.sumOf { it.totalTokens.toLong() },
            totalCost = generations.sumOf { it.costUsd }
        )
    }

    suspend fun getCosts(
        projectId: Long,
        range: String,
        demoEpochMs: Long? = null
    ): LlmCostsResponse {
        val bucket = bucketFromRange(range)
        val projectFilter = projectIdClause(projectId)
        val timeFilter = rangeClause(range, demoEpochMs)

        val breakdownQuery =
            """
            SELECT
                model, provider,
                sum(cost_usd) as total_cost,
                sum(total_tokens) as total_tokens,
                count() as call_count
            FROM `$clickhouseDb`.llm_generations
            WHERE $projectFilter AND $timeFilter
            GROUP BY model, provider
            ORDER BY total_cost DESC
            FORMAT JSONEachRow
            """.trimIndent()

        val timelineQuery =
            """
            SELECT
                formatDateTime($bucket(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts,
                count() as cnt,
                sum(total_tokens) as tokens,
                sum(cost_usd) as cost,
                countIf(status = 'error') as errors
            FROM `$clickhouseDb`.llm_generations
            WHERE $projectFilter AND $timeFilter
            GROUP BY ts
            ORDER BY ts
            FORMAT JSONEachRow
            """.trimIndent()

        val breakdownResponse = ClickHouseClient.execute(breakdownQuery)
        val timelineResponse = ClickHouseClient.execute(timelineQuery)

        val breakdown =
            (extractBody(breakdownResponse) ?: "").trim().lines().filter { it.isNotBlank() }.map { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                LlmCostBreakdown(
                    model = obj["model"]?.jsonPrimitive?.content ?: "",
                    provider = obj["provider"]?.jsonPrimitive?.content ?: "",
                    totalCost = obj["total_cost"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    totalTokens = obj["total_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
                    callCount = obj["call_count"]?.jsonPrimitive?.longOrNull ?: 0
                )
            }

        val timeline =
            (extractBody(timelineResponse) ?: "").trim().lines().filter { it.isNotBlank() }.map { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                LlmTimelinePoint(
                    timestamp = obj["ts"]?.jsonPrimitive?.content ?: "",
                    count = obj["cnt"]?.jsonPrimitive?.longOrNull ?: 0,
                    tokens = obj["tokens"]?.jsonPrimitive?.longOrNull ?: 0,
                    cost = obj["cost"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    errors = obj["errors"]?.jsonPrimitive?.longOrNull ?: 0
                )
            }

        return LlmCostsResponse(
            totalCost = breakdown.sumOf { it.totalCost },
            breakdown = breakdown,
            timeline = timeline
        )
    }

    suspend fun getModels(
        projectId: Long,
        range: String,
        demoEpochMs: Long? = null
    ): List<LlmModelStats> {
        val projectFilter = projectIdClause(projectId)
        val timeFilter = rangeClause(range, demoEpochMs)
        val query =
            """
            SELECT
                model, provider,
                count() as call_count,
                sum(total_tokens) as total_tokens,
                sum(cost_usd) as total_cost,
                avg(duration_ms) as avg_duration_ms,
                countIf(status = 'error') * 100.0 / greatest(count(), 1) as error_rate
            FROM `$clickhouseDb`.llm_generations
            WHERE $projectFilter AND $timeFilter
            GROUP BY model, provider
            ORDER BY call_count DESC
            FORMAT JSONEachRow
            """.trimIndent()

        val response = ClickHouseClient.execute(query)
        return (extractBody(response) ?: "").trim().lines().filter { it.isNotBlank() }.map { line ->
            val obj = json.parseToJsonElement(line).jsonObject
            LlmModelStats(
                model = obj["model"]?.jsonPrimitive?.content ?: "",
                provider = obj["provider"]?.jsonPrimitive?.content ?: "",
                callCount = obj["call_count"]?.jsonPrimitive?.longOrNull ?: 0,
                totalTokens = obj["total_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
                totalCost = obj["total_cost"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                avgDurationMs = obj["avg_duration_ms"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                errorRate = obj["error_rate"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            )
        }
    }
}
