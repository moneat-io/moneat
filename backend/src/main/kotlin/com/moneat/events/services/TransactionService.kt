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
import com.moneat.events.models.EventTraceResponse
import com.moneat.events.models.PerformanceStatsResponse
import com.moneat.events.models.SpanDetailResponse
import com.moneat.events.models.SpanResponse
import com.moneat.events.models.TraceDetailResponse
import com.moneat.events.models.TransactionDetailResponse
import com.moneat.events.models.TransactionSummaryResponse
import com.moneat.events.models.TransactionWithSpansResponse
import com.moneat.shared.models.Projects
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.moneat.utils.suspendRunCatching
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_RANGE

private val logger = KotlinLogging.logger {}

class TransactionService(private val queryHelper: DashboardQueryHelper) {
    private val clickhouseDb: String get() = queryHelper.clickhouseDb
    private val json get() = queryHelper.json

    private data class EventTraceLookup(
        val eventId: String,
        val eventType: String,
        val projectId: Long,
        val traceId: String
    )

    companion object {
        private const val APDEX_THRESHOLD_MS = 500
        private const val NANOS_PER_MILLI = 1_000_000.0
        private const val APDEX_FRUSTRATED_MULTIPLIER = 4
        private const val APDEX_TOLERATED_WEIGHT = 0.5
    }

    private fun getOrganizationIdForProject(projectId: Long): Int? =
        transaction {
            Projects
                .selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull()
                ?.get(Projects.organization_id)
        }

    private fun mapSpanRowFromApm(obj: JsonObject): SpanResponse {
        val startNs = obj["start_ns"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val durationNs = obj["duration_ns"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val startMs = startNs / NANOS_PER_MILLI
        val endMs = startMs + (durationNs / NANOS_PER_MILLI)
        val durationMs = durationNs / NANOS_PER_MILLI
        val tagsMap = queryHelper.parseStringMap(obj["meta"])
        val errorVal = obj["error"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val status = if (errorVal > 0) "error" else "ok"
        return SpanResponse(
            spanId = obj["span_id"]?.jsonPrimitive?.content ?: "",
            parentSpanId = obj["parent_span_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            traceId = obj["trace_id"]?.jsonPrimitive?.contentOrNull,
            transactionId = tagsMap["sentry.transaction_id"],
            op = obj["op"]?.jsonPrimitive?.content ?: "",
            description = obj["description"]?.jsonPrimitive?.content ?: "",
            startTimestamp = startMs / 1000.0,
            endTimestamp = endMs / 1000.0,
            duration = durationMs,
            status = status,
            tags = tagsMap.filterKeys { !it.startsWith("sentry.") },
            data = null
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
        return queryHelper.executeProjectIdQuery(query, "Transaction", eventId)?.takeIf { it > 0 }
    }

    private suspend fun getTraceLookupForEvent(eventId: String): EventTraceLookup? {
        val normalizedEventId = queryHelper.normalizeUuid(eventId) ?: return null
        val query = """
            SELECT
                toString(event_id) as event_id,
                event_type,
                toInt64(project_id) as project_id,
                JSONExtractString(contexts, 'trace', 'trace_id') as trace_id
            FROM `$clickhouseDb`.events
            WHERE toString(event_id) = '$normalizedEventId'
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        val obj = queryHelper.executeJsonEachRowQuery(query, "Event trace lookup")?.firstOrNull() ?: return null
        return EventTraceLookup(
            eventId = obj["event_id"]?.jsonPrimitive?.content ?: normalizedEventId,
            eventType = obj["event_type"]?.jsonPrimitive?.content ?: "",
            projectId = obj["project_id"]?.jsonPrimitive?.longOrNull ?: return null,
            traceId = obj["trace_id"]?.jsonPrimitive?.contentOrNull ?: ""
        )
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

        val rows = queryHelper.executeJsonEachRowQuery(query, "Transactions") ?: return emptyList()
        return rows.map { obj ->
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

        val apdexToleratedUpper = APDEX_THRESHOLD_MS * APDEX_FRUSTRATED_MULTIPLIER
        val totalQuery = """
            SELECT count() as total, avg(duration_ms) as avg_duration,
                countIf(duration_ms <= $APDEX_THRESHOLD_MS) as satisfied,
                countIf(
                    duration_ms > $APDEX_THRESHOLD_MS
                    AND duration_ms <= $apdexToleratedUpper
                ) as tolerated
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
                formatDateTime(
                    toStartOfInterval(timestamp, INTERVAL ${config.intervalMinutes} MINUTE),
                    '%Y-%m-%dT%H:%i:%S.000Z',
                    'UTC'
                ) as time,
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

        return suspendRunCatching {
            val totalResponse = ClickHouseClient.execute(totalQuery)
            val totalBody = totalResponse.bodyAsText()
            val totalCount: Long
            val avgDuration: Double
            val satisfiedCount: Long
            val toleratedCount: Long
            if (totalResponse.status.value in HTTP_SUCCESS_RANGE && totalBody.isNotBlank()) {
                val obj = json.parseToJsonElement(totalBody.lines().first()).jsonObject
                totalCount = obj["total"]?.jsonPrimitive?.long ?: 0L
                avgDuration = obj["avg_duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                satisfiedCount = obj["satisfied"]?.jsonPrimitive?.long ?: 0L
                toleratedCount = obj["tolerated"]?.jsonPrimitive?.long ?: 0L
            } else {
                totalCount = 0L
                avgDuration = 0.0
                satisfiedCount = 0L
                toleratedCount = 0L
            }

            val throughput = queryHelper.executeTimelineQuery(throughputQuery)
            val slowest = queryHelper.executeSlowestTransactionsQuery(slowestQuery)

            val apdex = if (totalCount > 0) {
                ((satisfiedCount + toleratedCount * APDEX_TOLERATED_WEIGHT) / totalCount).coerceIn(0.0, 1.0)
            } else {
                0.0
            }

            PerformanceStatsResponse(
                apdex = apdex,
                throughput = throughput,
                slowestTransactions = slowest,
                totalTransactions = totalCount,
                avgDuration = avgDuration
            )
        }.getOrElse { e ->
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

        val obj = queryHelper.executeJsonEachRowQuery(query, "Transaction")?.firstOrNull() ?: return null
        val startTs = obj["start_ts_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        val duration = obj["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        val tagsMap = queryHelper.parseStringMap(obj["tags"])
        return TransactionDetailResponse(
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
            contexts = queryHelper.jsonFieldAsStoredString(obj, "contexts", "{}"),
            breadcrumbs = queryHelper.jsonFieldAsStoredStringOrNull(obj, "breadcrumbs"),
            request = queryHelper.jsonFieldAsStoredStringOrNull(obj, "request")
        )
    }

    private fun routedSpanProjectClause(projectId: Long): String =
        """
        (
            project_id = $projectId OR
            (meta['sentry.project_id'] = '$projectId' AND source = 'sentry')
        )
        """.trimIndent()

    suspend fun getTransactionSpans(eventId: String): TransactionWithSpansResponse? {
        val transaction = getTransaction(eventId) ?: return null
        val normalizedEventId = queryHelper.normalizeUuid(eventId) ?: return null
        val projectId = getProjectIdForTransaction(eventId) ?: return null
        val orgId = getOrganizationIdForProject(projectId)
            ?: return TransactionWithSpansResponse(transaction, emptyList())
        val escapedTraceId = escapeSql(transaction.traceId)
        val projectClause = routedSpanProjectClause(projectId)

        val query = """
            SELECT
                span_id_hex as span_id,
                parent_id_hex as parent_span_id,
                trace_id_hex as trace_id,
                meta,
                type as op,
                resource as description,
                toInt64(toUnixTimestamp64Nano(start)) as start_ns,
                duration as duration_ns,
                error
            FROM `$clickhouseDb`.apm_spans
            WHERE organization_id = $orgId
              AND (
                  (
                      meta['sentry.transaction_id'] = '${escapeSql(normalizedEventId)}'
                      AND meta['sentry.project_id'] = '$projectId'
                      AND source = 'sentry'
                  ) OR (
                      trace_id_hex = '$escapedTraceId'
                      AND $projectClause
                  )
              )
            ORDER BY start ASC
            FORMAT JSONEachRow
        """.trimIndent()

        val rows =
            queryHelper.executeJsonEachRowQuery(query, "Transaction spans")
                ?: return TransactionWithSpansResponse(transaction, emptyList())
        val spans = rows.map { mapSpanRowFromApm(it) }
        return TransactionWithSpansResponse(transaction, spans)
    }

    suspend fun getTraceForEvent(eventId: String): EventTraceResponse? {
        val lookup = getTraceLookupForEvent(eventId) ?: return null
        if (lookup.traceId.isBlank()) {
            return EventTraceResponse(
                eventId = lookup.eventId,
                eventType = lookup.eventType,
                projectId = lookup.projectId,
                traceId = "",
                transaction = null,
                spans = emptyList()
            )
        }

        val transactionWithSpans = if (lookup.eventType == "transaction") {
            getTransactionSpans(lookup.eventId)
        } else {
            null
        }
        val traceSpans = getTraceDetails(lookup.projectId, lookup.traceId)?.spans.orEmpty()

        return EventTraceResponse(
            eventId = lookup.eventId,
            eventType = lookup.eventType,
            projectId = lookup.projectId,
            traceId = lookup.traceId,
            transaction = transactionWithSpans?.transaction,
            spans = transactionWithSpans?.spans?.takeIf { it.isNotEmpty() } ?: traceSpans
        )
    }

    suspend fun getTraceForTraceId(projectId: Long, traceId: String): EventTraceResponse? {
        val trace = getTraceDetails(projectId, traceId) ?: return null
        return EventTraceResponse(
            eventId = null,
            eventType = null,
            projectId = projectId,
            traceId = traceId,
            transaction = null,
            spans = trace.spans
        )
    }

    suspend fun getTraceDetails(
        projectId: Long,
        traceId: String
    ): TraceDetailResponse? {
        val escapedTraceId = escapeSql(traceId)
        val orgId = getOrganizationIdForProject(projectId) ?: return null
        val projectClause = routedSpanProjectClause(projectId)

        val query = """
            SELECT
                span_id_hex as span_id,
                parent_id_hex as parent_span_id,
                trace_id_hex as trace_id,
                meta,
                type as op,
                resource as description,
                toInt64(toUnixTimestamp64Nano(start)) as start_ns,
                duration as duration_ns,
                error
            FROM `$clickhouseDb`.apm_spans
            WHERE organization_id = $orgId
              AND trace_id_hex = '$escapedTraceId'
              AND $projectClause
            ORDER BY start ASC
            FORMAT JSONEachRow
        """.trimIndent()

        val rows = queryHelper.executeJsonEachRowQuery(query, "Trace spans") ?: return null
        val spans = rows.map { mapSpanRowFromApm(it) }
        if (spans.isEmpty()) return null

        return suspendRunCatching {
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
        }.getOrElse { e ->
            logger.error(e) { "Failed to fetch trace $traceId" }
            null
        }
    }

    suspend fun getSpanDetails(
        projectId: Long,
        spanId: String
    ): SpanDetailResponse? {
        val escapedSpanId = escapeSql(spanId)
        val orgId = getOrganizationIdForProject(projectId) ?: return null
        val projectClause = routedSpanProjectClause(projectId)

        val query = """
            SELECT
                span_id_hex as span_id,
                parent_id_hex as parent_span_id,
                trace_id_hex as trace_id,
                meta,
                type as op,
                resource as description,
                toInt64(toUnixTimestamp64Nano(start)) as start_ns,
                duration as duration_ns,
                error
            FROM `$clickhouseDb`.apm_spans
            WHERE organization_id = $orgId
              AND span_id_hex = '$escapedSpanId'
              AND $projectClause
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        val obj = queryHelper.executeJsonEachRowQuery(query, "Span")?.firstOrNull() ?: return null
        val span = mapSpanRowFromApm(obj)
        val transactionId = queryHelper.parseStringMap(obj["meta"])["sentry.transaction_id"]
        val transaction = transactionId?.let { getTransaction(it) }
        return SpanDetailResponse(span = span, transaction = transaction)
    }

    suspend fun getRelatedErrorsForTransaction(
        eventId: String,
        limit: Int = 20
    ): List<EventResponse> {
        val transaction = getTransaction(eventId) ?: return emptyList()
        if (transaction.traceId.isBlank()) return emptyList()
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
                breadcrumbs,
                stack_trace
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND event_type = 'error'
                AND JSONExtractString(contexts, 'trace', 'trace_id') = '$escapedTraceId'
                AND toString(event_id) != '$normalizedEventId'
            ORDER BY timestamp DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()

        val rows = queryHelper.executeJsonEachRowQuery(query, "Related errors") ?: return emptyList()
        return rows.map { queryHelper.mapEventRow(it) }
    }
}
