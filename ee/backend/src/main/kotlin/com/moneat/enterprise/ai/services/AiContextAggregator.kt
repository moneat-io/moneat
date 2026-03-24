// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.services

import com.moneat.config.ClickHouseClient
import com.moneat.enterprise.ai.models.AggregatedContext
import com.moneat.enterprise.ai.models.ContainerEntry
import com.moneat.enterprise.ai.models.ContextSummary
import com.moneat.enterprise.ai.models.EventEntry
import com.moneat.enterprise.ai.models.LogEntry
import com.moneat.enterprise.ai.models.MetricEntry
import com.moneat.enterprise.ai.models.SpanEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.time.LocalDate

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/** Describes the time window to query observability data. */
sealed class AiTimeFilter {
    /** Relative: everything from the last [hours] hours up to now. */
    data class LastHours(val hours: Int) : AiTimeFilter()

    /** Absolute: a specific calendar day (midnight-to-midnight UTC). */
    data class SpecificDay(val date: LocalDate) : AiTimeFilter()

    /** Returns a SQL WHERE fragment for the given timestamp column. */
    fun toCondition(column: String): String = when (this) {
        is LastHours -> "$column >= now() - INTERVAL $hours HOUR"
        is SpecificDay -> "$column >= '$date' AND $column < '${date.plusDays(1)}'"
    }

    fun describe(): String = when (this) {
        is LastHours -> "last $hours hours"
        is SpecificDay -> date.toString()
    }
}

/**
 * Aggregates observability data from ClickHouse for AI context.
 * Uses safe parameterized queries — never passes raw LLM output into SQL.
 */
class AiContextAggregator {

    private companion object {
        const val MAX_LOGS = 200
        const val MAX_SPANS = 100
        const val MAX_EVENTS = 100
        const val MAX_METRICS = 50
        const val MAX_CONTAINERS = 50
    }

    /**
     * Query all observability sources for the given org/projects within a time range.
     * Returns structured context suitable for LLM consumption.
     */
    suspend fun aggregate(orgId: Int, projectIds: List<Long>, timeFilter: AiTimeFilter): AggregatedContext {
        val logs = if (projectIds.isNotEmpty()) queryLogs(projectIds.joinToString(","), timeFilter) else emptyList()
        val spans = if (projectIds.isNotEmpty()) querySpans(orgId, timeFilter) else emptyList()
        val events = if (projectIds.isNotEmpty()) queryEvents(projectIds.joinToString(","), timeFilter) else emptyList()
        val metrics = queryMetrics(orgId, timeFilter)
        val containers = queryContainers(orgId, timeFilter)

        return AggregatedContext(
            logs = logs,
            spans = spans,
            events = events,
            metrics = metrics,
            containers = containers,
            summary = ContextSummary(
                logCount = logs.size,
                spanCount = spans.size,
                eventCount = events.size,
                metricCount = metrics.size,
                containerCount = containers.size,
                timeRangeStart = timeFilter.describe(),
                timeRangeEnd = if (timeFilter is AiTimeFilter.SpecificDay) {
                    timeFilter.date.plusDays(
                        1
                    ).toString()
                } else {
                    "now()"
                },
            ),
        )
    }

    private suspend fun queryLogs(projectIdList: String, timeFilter: AiTimeFilter): List<LogEntry> {
        return try {
            val query = """
                SELECT timestamp, level, message, service
                FROM logs
                WHERE project_id IN ($projectIdList)
                  AND ${timeFilter.toCondition("timestamp")}
                ORDER BY timestamp DESC
                LIMIT $MAX_LOGS
                FORMAT JSON
            """.trimIndent()

            val result = ClickHouseClient.executeWithFormat(query, "JSON")
            parseLogResults(result)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to query logs for AI context" }
            emptyList()
        }
    }

    private suspend fun querySpans(orgId: Int, timeFilter: AiTimeFilter): List<SpanEntry> {
        return try {
            val query = """
                SELECT
                    trace_id_hex AS trace_id,
                    span_id_hex AS span_id,
                    type AS operation,
                    intDiv(duration, 1000000) AS duration_ms,
                    if(error > 0, 'error', 'ok') AS status,
                    service
                FROM apm_spans
                WHERE organization_id = $orgId
                  AND ${timeFilter.toCondition("start")}
                ORDER BY start DESC
                LIMIT $MAX_SPANS
                FORMAT JSON
            """.trimIndent()

            val result = ClickHouseClient.executeWithFormat(query, "JSON")
            parseSpanResults(result)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to query spans for AI context" }
            emptyList()
        }
    }

    private suspend fun queryEvents(projectIdList: String, timeFilter: AiTimeFilter): List<EventEntry> {
        return try {
            val query = """
                SELECT timestamp, message AS title, level, fingerprint[1] AS fingerprint, count() AS cnt
                FROM events
                WHERE project_id IN ($projectIdList)
                  AND ${timeFilter.toCondition("timestamp")}
                GROUP BY timestamp, message, level, fingerprint
                ORDER BY cnt DESC
                LIMIT $MAX_EVENTS
                FORMAT JSON
            """.trimIndent()

            val result = ClickHouseClient.executeWithFormat(query, "JSON")
            parseEventResults(result)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to query events for AI context" }
            emptyList()
        }
    }

    private suspend fun queryMetrics(orgId: Int, timeFilter: AiTimeFilter): List<MetricEntry> {
        return try {
            val query = """
                SELECT
                    formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%SZ') AS timestamp,
                    cpu_percent,
                    mem_used,
                    mem_total,
                    load_1
                FROM system_metrics
                WHERE org_id = $orgId
                  AND ${timeFilter.toCondition("timestamp")}
                ORDER BY timestamp DESC
                LIMIT $MAX_METRICS
                FORMAT JSON
            """.trimIndent()

            val result = ClickHouseClient.executeWithFormat(query, "JSON")
            parseMetricResults(result)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to query metrics for AI context" }
            emptyList()
        }
    }

    private suspend fun queryContainers(orgId: Int, timeFilter: AiTimeFilter): List<ContainerEntry> {
        return try {
            val query = """
                SELECT
                    formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%SZ') AS timestamp,
                    container_name,
                    status,
                    cpu_percent,
                    mem_used
                FROM container_metrics
                WHERE org_id = $orgId
                  AND ${timeFilter.toCondition("timestamp")}
                ORDER BY timestamp DESC
                LIMIT $MAX_CONTAINERS
                FORMAT JSON
            """.trimIndent()

            val result = ClickHouseClient.executeWithFormat(query, "JSON")
            parseContainerResults(result)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to query containers for AI context" }
            emptyList()
        }
    }

    private fun parseLogResults(jsonStr: String): List<LogEntry> {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            data.map { row ->
                val obj = row.jsonObject
                LogEntry(
                    timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
                    level = obj["level"]?.jsonPrimitive?.content ?: "",
                    message = obj["message"]?.jsonPrimitive?.content ?: "",
                    service = obj["service"]?.jsonPrimitive?.content,
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse log results" }
            emptyList()
        }
    }

    private fun parseSpanResults(jsonStr: String): List<SpanEntry> {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            data.map { row ->
                val obj = row.jsonObject
                SpanEntry(
                    traceId = obj["trace_id"]?.jsonPrimitive?.content ?: "",
                    spanId = obj["span_id"]?.jsonPrimitive?.content ?: "",
                    operation = obj["operation"]?.jsonPrimitive?.content ?: "",
                    duration = obj["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                    status = obj["status"]?.jsonPrimitive?.content ?: "",
                    service = obj["service"]?.jsonPrimitive?.content,
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse span results" }
            emptyList()
        }
    }

    private fun parseEventResults(jsonStr: String): List<EventEntry> {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            data.map { row ->
                val obj = row.jsonObject
                EventEntry(
                    timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
                    title = obj["title"]?.jsonPrimitive?.content ?: "",
                    level = obj["level"]?.jsonPrimitive?.content ?: "",
                    fingerprint = obj["fingerprint"]?.jsonPrimitive?.content,
                    count = obj["cnt"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse event results" }
            emptyList()
        }
    }

    private fun parseMetricResults(jsonStr: String): List<MetricEntry> {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            data.map { row ->
                val obj = row.jsonObject
                MetricEntry(
                    timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
                    cpuPercent = obj["cpu_percent"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                    memUsedBytes = obj["mem_used"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                    memTotalBytes = obj["mem_total"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                    load1 = obj["load_1"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse metric results" }
            emptyList()
        }
    }

    private fun parseContainerResults(jsonStr: String): List<ContainerEntry> {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            data.map { row ->
                val obj = row.jsonObject
                ContainerEntry(
                    timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
                    containerName = obj["container_name"]?.jsonPrimitive?.content ?: "",
                    status = obj["status"]?.jsonPrimitive?.content ?: "",
                    cpuPercent = obj["cpu_percent"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                    memUsedBytes = obj["mem_used"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse container results" }
            emptyList()
        }
    }

    /** Estimate token count for the aggregated context (rough: ~4 chars per token). */
    fun estimateTokens(context: AggregatedContext): Int {
        val contextJson = json.encodeToString(AggregatedContext.serializer(), context)
        return contextJson.length / 4
    }
}
