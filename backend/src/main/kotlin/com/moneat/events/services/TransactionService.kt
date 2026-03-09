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

package com.moneat.events.services

import com.moneat.config.ClickHouseClient
import com.moneat.events.models.EventResponse
import com.moneat.events.models.PerformanceStatsResponse
import com.moneat.events.models.SpanDetailResponse
import com.moneat.events.models.SpanResponse
import com.moneat.events.models.TraceDetailResponse
import com.moneat.events.models.TransactionDetailResponse
import com.moneat.events.models.TransactionSummaryResponse
import com.moneat.events.models.TransactionWithSpansResponse
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class TransactionService(private val queryHelper: DashboardQueryHelper) {
    private val clickhouseDb: String get() = queryHelper.clickhouseDb
    private val json get() = queryHelper.json

    private fun mapSpanRow(obj: JsonObject): SpanResponse {
        val startMs = obj["start_ts_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        val endMs = obj["end_ts_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        val duration = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        val tagsMap = queryHelper.parseStringMap(obj["tags"])
        return SpanResponse(
            spanId = obj["span_id"]?.jsonPrimitive?.content ?: "",
            parentSpanId = obj["parent_span_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            traceId = obj["trace_id"]?.jsonPrimitive?.contentOrNull,
            transactionId = obj["transaction_id"]?.jsonPrimitive?.contentOrNull,
            op = obj["op"]?.jsonPrimitive?.content ?: "",
            description = obj["description"]?.jsonPrimitive?.content ?: "",
            startTimestamp = startMs / 1000.0,
            endTimestamp = endMs / 1000.0,
            duration = duration,
            status = obj["status"]?.jsonPrimitive?.contentOrNull,
            tags = tagsMap,
            data = obj["data"]?.jsonPrimitive?.contentOrNull
        )
    }

    suspend fun getProjectIdForTransaction(eventId: String): Long? {
        val normalizedEventId = queryHelper.normalizeUuid(eventId) ?: return null
        val query = """
            SELECT toInt64(project_id) as project_id
            FROM `$clickhouseDb`.events
            WHERE toString(event_id) = '$normalizedEventId' AND event_type = 'transaction'
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) return null
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            obj["project_id"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get project ID for transaction $eventId" }
            null
        }
    }

    suspend fun getTransactions(
        projectId: Long,
        period: String = "7d",
        environment: String? = null,
        operation: String? = null,
        demoEpochMs: Long? = null
    ): List<TransactionSummaryResponse> {
        val config = queryHelper.getPeriodConfig(period)
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
        val nowClause = queryHelper.demoNowClause(demoEpochMs)
        val filterClause = queryHelper.buildTransactionFilterClause(environment, operation)

        val query = """
            SELECT
                transaction_name as name,
                transaction_op as op,
                argMax(toString(event_id), timestamp) as latest_event_id,
                count() as count,
                quantile(0.5)(duration_ms) as p50,
                quantile(0.75)(duration_ms) as p75,
                quantile(0.95)(duration_ms) as p95,
                countIf(level = 'error' OR level = 'fatal') * 1.0 / count() as failure_rate,
                count() * 1.0 / (${config.periodMinutes} / 60.0) as tpm
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND event_type = 'transaction'
                AND timestamp >= $nowClause - INTERVAL ${config.hoursBack} HOUR
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
                $filterClause
            GROUP BY transaction_name, transaction_op
            ORDER BY count DESC
            LIMIT 100
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) return emptyList()
            body.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    TransactionSummaryResponse(
                        name = obj["name"]?.jsonPrimitive?.content ?: "",
                        op = obj["op"]?.jsonPrimitive?.content ?: "",
                        latestEventId = obj["latest_event_id"]?.jsonPrimitive?.contentOrNull,
                        count = obj["count"]?.jsonPrimitive?.long ?: 0,
                        p50 = obj["p50"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                        p75 = obj["p75"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                        p95 = obj["p95"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                        failureRate = obj["failure_rate"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                        tpm = obj["tpm"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                    )
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch transactions for project $projectId" }
            emptyList()
        }
    }

    suspend fun getPerformanceStats(
        projectId: Long,
        period: String = "7d",
        environment: String? = null,
        operation: String? = null,
        demoEpochMs: Long? = null
    ): PerformanceStatsResponse {
        val config = queryHelper.getPeriodConfig(period)
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
        val nowClause = queryHelper.demoNowClause(demoEpochMs)
        val filterClause = queryHelper.buildTransactionFilterClause(environment, operation)

        val totalQuery = """
            SELECT count() as total, avg(duration_ms) as avg_duration
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND event_type = 'transaction'
                AND timestamp >= $nowClause - INTERVAL ${config.hoursBack} HOUR
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
                $filterClause
            FORMAT JSONEachRow
        """.trimIndent()

        val throughputQuery = """
            SELECT
                formatDateTime(toStartOfInterval(timestamp, INTERVAL ${config.intervalMinutes} MINUTE), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as time,
                count() as count
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND event_type = 'transaction'
                AND timestamp >= $nowClause - INTERVAL ${config.hoursBack} HOUR
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
                $filterClause
            GROUP BY time
            ORDER BY time
            FORMAT JSONEachRow
        """.trimIndent()

        val slowestQuery = """
            SELECT
                toString(event_id) as event_id,
                transaction_name as name,
                transaction_op as op,
                duration_ms as duration,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as timestamp_iso
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND event_type = 'transaction'
                AND timestamp >= $nowClause - INTERVAL ${config.hoursBack} HOUR
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
                $filterClause
            ORDER BY duration_ms DESC
            LIMIT 10
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val totalResponse = ClickHouseClient.execute(totalQuery)
            val totalBody = totalResponse.bodyAsText()
            val (totalCount, avgDuration) = if (
                totalResponse.status.value in 200..299 && totalBody.isNotBlank()
            ) {
                val obj = json.parseToJsonElement(totalBody.lines().first()).jsonObject
                val count = obj["total"]?.jsonPrimitive?.long ?: 0L
                val avg = obj["avg_duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                count to avg
            } else {
                0L to 0.0
            }

            val throughput = queryHelper.executeTimelineQuery(throughputQuery)
            val slowest = queryHelper.executeSlowestTransactionsQuery(slowestQuery)

            val satisfied = totalCount * 0.94
            val tolerated = totalCount * 0.94
            val apdex = if (totalCount > 0) (satisfied + tolerated * 0.5) / totalCount else 0.0

            PerformanceStatsResponse(
                apdex = apdex.coerceIn(0.0, 1.0),
                throughput = throughput,
                slowestTransactions = slowest,
                totalTransactions = totalCount,
                avgDuration = avgDuration
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch performance stats for project $projectId" }
            PerformanceStatsResponse(
                apdex = 0.0,
                throughput = emptyList(),
                slowestTransactions = emptyList(),
                totalTransactions = 0,
                avgDuration = 0.0
            )
        }
    }

    suspend fun getTransaction(eventId: String): TransactionDetailResponse? {
        val normalizedEventId = queryHelper.normalizeUuid(eventId) ?: return null
        val query = """
            SELECT
                toString(event_id) as event_id,
                transaction_name as name,
                transaction_op as op,
                toUnixTimestamp64Milli(timestamp) - duration_ms as start_ts_ms,
                duration_ms as duration,
                JSONExtractString(contexts, 'trace', 'trace_id') as trace_id,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as timestamp,
                environment,
                release,
                JSONExtractString(contexts, 'trace', 'status') as status,
                tags,
                contexts,
                breadcrumbs,
                request
            FROM `$clickhouseDb`.events
            WHERE toString(event_id) = '$normalizedEventId' AND event_type = 'transaction'
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            val line = body.lines().firstOrNull { it.isNotBlank() } ?: return null
            val obj = json.parseToJsonElement(line).jsonObject
            val startTs = obj["start_ts_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
            val duration = obj["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
            val tagsMap = queryHelper.parseStringMap(obj["tags"])
            TransactionDetailResponse(
                eventId = obj["event_id"]?.jsonPrimitive?.content ?: return null,
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                op = obj["op"]?.jsonPrimitive?.content ?: "",
                startTimestamp = startTs / 1000.0,
                duration = duration,
                traceId = obj["trace_id"]?.jsonPrimitive?.content ?: "",
                timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
                environment = obj["environment"]?.jsonPrimitive?.contentOrNull,
                release = obj["release"]?.jsonPrimitive?.contentOrNull,
                status = obj["status"]?.jsonPrimitive?.contentOrNull,
                tags = tagsMap,
                contexts = obj["contexts"]?.jsonPrimitive?.content ?: "{}",
                breadcrumbs = obj["breadcrumbs"]?.jsonPrimitive?.contentOrNull,
                request = obj["request"]?.jsonPrimitive?.contentOrNull
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch transaction $eventId" }
            null
        }
    }

    suspend fun getTransactionSpans(eventId: String): TransactionWithSpansResponse? {
        val transaction = getTransaction(eventId) ?: return null
        val normalizedEventId = queryHelper.normalizeUuid(eventId) ?: return null

        val query = """
            SELECT
                span_id,
                parent_span_id,
                trace_id,
                toString(transaction_id) as transaction_id,
                op,
                description,
                toUnixTimestamp64Milli(start_timestamp) as start_ts_ms,
                toUnixTimestamp64Milli(end_timestamp) as end_ts_ms,
                duration_ms,
                status,
                tags,
                data
            FROM `$clickhouseDb`.spans
            WHERE toString(transaction_id) = '$normalizedEventId'
            ORDER BY start_timestamp ASC
            FORMAT JSONEachRow
        """.trimIndent()

        val spans = try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) return TransactionWithSpansResponse(transaction, emptyList())
            body.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    mapSpanRow(obj)
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch spans for transaction $eventId" }
            emptyList()
        }

        return TransactionWithSpansResponse(transaction, spans)
    }

    suspend fun getTraceDetails(
        projectId: Long,
        traceId: String
    ): TraceDetailResponse? {
        val escapedTraceId = escapeSql(traceId)
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)

        val query = """
            SELECT
                span_id,
                parent_span_id,
                trace_id,
                toString(transaction_id) as transaction_id,
                op,
                description,
                toUnixTimestamp64Milli(start_timestamp) as start_ts_ms,
                toUnixTimestamp64Milli(end_timestamp) as end_ts_ms,
                duration_ms,
                status,
                tags,
                data
            FROM `$clickhouseDb`.spans
            WHERE $projectIdClause AND trace_id = '$escapedTraceId'
            ORDER BY start_timestamp ASC
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.isBlank()) return null

            val spans = body.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    mapSpanRow(obj)
                }

            if (spans.isEmpty()) return null

            val startTs = spans.minOf { it.startTimestamp }
            val endTs = spans.maxOf { it.endTimestamp }
            val duration = endTs - startTs

            TraceDetailResponse(
                traceId = traceId,
                projectId = projectId,
                spans = spans,
                startTimestamp = startTs,
                endTimestamp = endTs,
                duration = duration * 1000.0
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch trace $traceId" }
            null
        }
    }

    suspend fun getSpanDetails(
        projectId: Long,
        spanId: String
    ): SpanDetailResponse? {
        val escapedSpanId = escapeSql(spanId)
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)

        val query = """
            SELECT
                span_id,
                parent_span_id,
                trace_id,
                toString(transaction_id) as transaction_id,
                op,
                description,
                toUnixTimestamp64Milli(start_timestamp) as start_ts_ms,
                toUnixTimestamp64Milli(end_timestamp) as end_ts_ms,
                duration_ms,
                status,
                tags,
                data
            FROM `$clickhouseDb`.spans
            WHERE $projectIdClause AND span_id = '$escapedSpanId'
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            val line = body.lines().firstOrNull { it.isNotBlank() } ?: return null
            val obj = json.parseToJsonElement(line).jsonObject
            val span = mapSpanRow(obj)
            val transactionId = obj["transaction_id"]?.jsonPrimitive?.contentOrNull
            val transaction = transactionId?.let { getTransaction(it) }
            SpanDetailResponse(span = span, transaction = transaction)
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch span $spanId" }
            null
        }
    }

    suspend fun getRelatedErrorsForTransaction(
        eventId: String,
        limit: Int = 20
    ): List<EventResponse> {
        val transaction = getTransaction(eventId) ?: return emptyList()
        val projectId = getProjectIdForTransaction(eventId) ?: return emptyList()
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
        val normalizedEventId = queryHelper.normalizeUuid(eventId) ?: return emptyList()
        val escapedTraceId = escapeSql(transaction.traceId)

        val query = """
            SELECT
                toString(event_id) as event_id,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as timestamp,
                message,
                platform,
                level,
                environment,
                release,
                user_id,
                user_email,
                user_username,
                tags,
                contexts,
                exception_value as exception,
                breadcrumbs
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND event_type = 'error'
                AND JSONExtractString(contexts, 'trace', 'trace_id') = '$escapedTraceId'
                AND toString(event_id) != '$normalizedEventId'
            ORDER BY timestamp DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) return emptyList()
            body.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    queryHelper.mapEventRow(obj)
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch related errors for transaction $eventId" }
            emptyList()
        }
    }
}
