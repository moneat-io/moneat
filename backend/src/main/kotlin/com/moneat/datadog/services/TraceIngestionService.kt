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

package com.moneat.datadog.services

import com.google.protobuf.CodedInputStream
import com.moneat.apm.services.ApmServiceMapRollups
import com.moneat.apm.services.ApmServiceMapSpan
import com.moneat.config.ClickHouseClient
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_3
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_4
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_5
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_6
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_7
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_8
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_SHIFT
import com.moneat.datadog.models.DdApmErrorGroup
import com.moneat.datadog.models.DdApmErrorsResponse
import com.moneat.datadog.models.DdApmFacetItem
import com.moneat.datadog.models.DdApmLatencyPoint
import com.moneat.datadog.models.DdApmOverviewFacets
import com.moneat.datadog.models.DdApmOverviewPreviousStats
import com.moneat.datadog.models.DdApmOverviewResponse
import com.moneat.datadog.models.DdApmOverviewStats
import com.moneat.datadog.models.DdApmResourceHotspotItem
import com.moneat.datadog.models.DdApmServiceFacet
import com.moneat.datadog.models.DdApmServiceHealthItem
import com.moneat.datadog.models.DdResourceStatsItem
import com.moneat.datadog.models.DdResourceStatsResponse
import com.moneat.datadog.models.DdServiceMapEntry
import com.moneat.datadog.models.DdServiceMapResponse
import com.moneat.datadog.models.DdSpan
import com.moneat.datadog.models.DdSpanResponse
import com.moneat.datadog.models.DdStatsPayload
import com.moneat.datadog.models.DdTraceDetailResponse
import com.moneat.datadog.models.DdTraceListItem
import com.moneat.datadog.models.DdTraceListResponse
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.doubleMapToSqlMap
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import com.moneat.utils.ClickHouseSqlUtils.mapToSqlMap
import io.ktor.http.isSuccess
import io.sentry.ISpan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker

private val logger = KotlinLogging.logger {}

private const val APM_TRACES_FINAL_TABLE = "apm_traces_final"
private const val APM_ERROR_GROUPS_TABLE = "apm_error_groups_hourly"
private const val APM_RESOURCE_STATS_TABLE = "apm_resource_stats_hourly"
private const val APM_SERVICE_STATS_TABLE = "apm_service_stats_hourly"
private const val APM_SERVICE_EDGES_TABLE = "apm_service_edges_hourly"
private const val MAX_META_VALUE_LENGTH = 5000
private const val DEFAULT_QUERY_LIMIT = 50
private const val MAX_QUERY_LIMIT = 200
private const val OVERVIEW_LIMIT = 5
private const val HEX_RADIX = 16
private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
private const val STATUS_ERROR = "error"
private const val STATUS_OK = "ok"
private const val MAX_FILTER_FACETS = 100
private const val PROTO_FIELD_9 = 9
private const val PROTO_FIELD_10 = 10
private const val PROTO_FIELD_11 = 11
private const val PROTO_FIELD_12 = 12
private const val DATADOG_SOURCE = "datadog"

val defaultApmQueryTimeRange = DdApmQueryTimeRange(24, DdApmQueryTimeUnit.HOUR)

data class DdResourceStatsQuery(
    val service: String? = null,
    val env: String? = null,
    val source: String? = null,
    val status: String? = null,
    val search: String? = null,
    val limit: Int = DEFAULT_QUERY_LIMIT,
    val offset: Int = 0,
    val timeRange: DdApmQueryTimeRange = defaultApmQueryTimeRange,
)

data class DdTraceListQuery(
    val service: String? = null,
    val env: String? = null,
    val source: String? = null,
    val status: String? = null,
    val search: String? = null,
    val limit: Int = DEFAULT_QUERY_LIMIT,
    val offset: Int = 0,
    val timeRange: DdApmQueryTimeRange = defaultApmQueryTimeRange,
)

enum class DdApmQueryTimeUnit(val sql: String) {
    HOUR("HOUR"),
    DAY("DAY")
}

data class DdApmQueryTimeRange(
    val amount: Int,
    val unit: DdApmQueryTimeUnit
) {
    fun startClause(column: String = "start"): String =
        "$column >= now() - INTERVAL $amount ${unit.sql}"

    fun bucketStartClause(column: String = "bucket_start"): String =
        "$column >= toStartOfHour(now() - INTERVAL $amount ${unit.sql})"

    fun previousBucketStartClause(column: String = "bucket_start"): String =
        "$column >= toStartOfHour(now() - INTERVAL ${amount * 2} ${unit.sql}) " +
            "AND $column < toStartOfHour(now() - INTERVAL $amount ${unit.sql})"
}

object TraceIngestionService {
    private val clickhouseDb by lazy { ClickHouseClient.getDatabase() }
    private val json = Json { ignoreUnknownKeys = true }
    private val usageTracking = UsageTrackingService.instance

    /**
     * Parse a MessagePack trace payload (v0.4 format).
     * Format: array of traces, each trace is array of spans,
     * each span is a map of fields.
     */
    fun parseMsgpackTraces(bytes: ByteArray): List<List<DdSpan>> {
        val unpacker = MessagePack.newDefaultUnpacker(bytes)
        val traceCount = unpacker.unpackArrayHeader()
        val traces = mutableListOf<List<DdSpan>>()

        for (t in 0 until traceCount) {
            val spanCount = unpacker.unpackArrayHeader()
            val spans = mutableListOf<DdSpan>()
            for (s in 0 until spanCount) {
                spans.add(unpackSpan(unpacker))
            }
            traces.add(spans)
        }
        return traces
    }

    /**
     * Parse a JSON trace payload (fallback format).
     */
    fun parseJsonTraces(body: String): List<List<DdSpan>> {
        val root = json.parseToJsonElement(body).jsonArray
        return root.map { traceEl ->
            traceEl.jsonArray.map { spanEl ->
                val obj = spanEl.jsonObject
                DdSpan(
                    traceId = obj["trace_id"]?.jsonPrimitive?.content
                        ?.let { java.lang.Long.parseUnsignedLong(it).toULong() } ?: 0u,
                    spanId = obj["span_id"]?.jsonPrimitive?.content
                        ?.let { java.lang.Long.parseUnsignedLong(it).toULong() } ?: 0u,
                    parentId = obj["parent_id"]?.jsonPrimitive?.content
                        ?.let { java.lang.Long.parseUnsignedLong(it).toULong() } ?: 0u,
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    service = obj["service"]?.jsonPrimitive?.content ?: "",
                    resource = obj["resource"]?.jsonPrimitive?.content ?: "",
                    type = obj["type"]?.jsonPrimitive?.content ?: "",
                    start = obj["start"]?.jsonPrimitive?.long ?: 0,
                    duration = obj["duration"]?.jsonPrimitive?.long ?: 0,
                    error = obj["error"]?.jsonPrimitive?.int ?: 0,
                    meta = parseStringMap(obj["meta"]),
                    metrics = parseDoubleMap(obj["metrics"]),
                )
            }
        }
    }

    /**
     * Insert parsed traces into ClickHouse apm_spans table.
     */
    suspend fun insertTraces(
        organizationId: Int,
        traces: List<List<DdSpan>>,
        hostname: String = "",
        env: String = "",
        appVersion: String = "",
    ) {
        val allSpans = traces.flatten()
        if (allSpans.isEmpty()) return

        val rows = allSpans.joinToString(",\n") { span ->
            val host = span.meta["_dd.hostname"]
                ?: hostname
            val spanEnv = span.meta["env"] ?: env
            val ver = span.meta["version"] ?: appVersion
            val parentIdHex = if (span.parentId != 0UL) {
                java.lang.Long.toUnsignedString(span.parentId.toLong(), HEX_RADIX)
            } else {
                ""
            }

            """(
                ${span.spanId},
                0,
                ${span.traceId},
                0,
                ${span.parentId},
                0,
                $organizationId,
                '${escapeSql(span.name)}',
                '${escapeSql(span.service)}',
                '${escapeSql(span.resource)}',
                '${escapeSql(span.type)}',
                fromUnixTimestamp64Nano(${span.start}),
                ${span.duration},
                ${span.error},
                ${mapToSqlMap(span.meta)},
                ${doubleMapToSqlMap(span.metrics)},
                '${escapeSql(host)}',
                '${escapeSql(spanEnv)}',
                '${escapeSql(ver)}',
                '${java.lang.Long.toUnsignedString(span.traceId.toLong(), HEX_RADIX)}',
                '${java.lang.Long.toUnsignedString(span.spanId.toLong(), HEX_RADIX)}',
                '$parentIdHex',
                '$DATADOG_SOURCE'
            )"""
        }

        val insert = """
            INSERT INTO `$clickhouseDb`.apm_spans (
                span_id, span_id_high, trace_id, trace_id_high, parent_id, parent_id_high, organization_id,
                name, service, resource, type,
                start, duration, error,
                meta, metrics, host, env, version,
                trace_id_hex, span_id_hex, parent_id_hex, source
            ) VALUES
            $rows
        """.trimIndent()

        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "Failed to insert DD spans into ClickHouse"
            )
        }
        ApmServiceMapRollups.insertForSpans(
            clickhouseDb,
            traces.toServiceMapSpans(organizationId, env)
        )

        val totalBytes = allSpans.sumOf {
            it.name.length + it.service.length +
                it.resource.length + it.meta.entries.sumOf { e ->
                    e.key.length + e.value.length
                }
        }
        usageTracking.recordOrgUsage(organizationId, "dd_trace", allSpans.size, totalBytes)
    }

    /**
     * Insert trace stats into ClickHouse trace_stats table.
     */
    suspend fun insertTraceStats(
        organizationId: Int,
        payload: DdStatsPayload,
    ) {
        val entries = payload.stats.flatMap { bucket ->
            bucket.stats.map { entry -> bucket to entry }
        }
        if (entries.isEmpty()) return

        val rows = entries.joinToString(",\n") { (bucket, entry) ->
            val startMs = bucket.start / NANOSECONDS_PER_MILLISECOND // ns to ms
            """(
                $organizationId,
                '${escapeSql(entry.service)}',
                '${escapeSql(entry.resource)}',
                '${escapeSql(entry.type)}',
                '${escapeSql(entry.name)}',
                ${entry.httpStatusCode},
                ${if (entry.synthetics) 1 else 0},
                fromUnixTimestamp64Milli($startMs),
                ${entry.duration},
                ${entry.hits},
                ${entry.topLevelHits},
                ${entry.errors},
                ${entry.okSummary?.count ?: 0},
                ${entry.okSummary?.sum ?: 0.0},
                ${entry.errorSummary?.count ?: 0},
                ${entry.errorSummary?.sum ?: 0.0}
            )"""
        }

        val insert = """
            INSERT INTO `$clickhouseDb`.trace_stats (
                organization_id, service, resource, type, name,
                http_status_code, synthetics, start, duration,
                hits, top_level_hits, errors,
                ok_summary_count, ok_summary_sum,
                error_summary_count, error_summary_sum
            ) VALUES
            $rows
        """.trimIndent()

        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "Failed to insert DD trace stats into ClickHouse"
            )
        }
    }

    // --- Dashboard query methods ---

    private fun traceSummarySubquery(
        organizationId: Int,
        query: DdTraceListQuery,
        previousWindow: Boolean = false,
    ): String {
        // apm_traces_final holds one finalized row per trace with plain columns, so the dashboard
        // reads it directly with a (organization_id, trace_bucket) key-prefix filter -- no per-trace
        // re-aggregation. FINAL collapses any superseded rows from re-finalization (see
        // TraceFinalizerBackgroundService). service/env/source/status/search filter plain columns, so
        // they all go in WHERE.
        val filters = mutableListOf(
            ClickHouseQueryUtils.orgIdClause(organizationId.toLong()),
            if (previousWindow) {
                query.timeRange.previousBucketStartClause("trace_bucket")
            } else {
                query.timeRange.bucketStartClause("trace_bucket")
            }
        )
        query.service?.let {
            filters.add("root_service = '${escapeSql(it)}'")
        }
        query.env?.let {
            filters.add("env = '${escapeSql(it)}'")
        }
        query.source?.let {
            filters.add("source = '${escapeSql(it)}'")
        }
        when (query.status) {
            STATUS_ERROR -> filters.add("has_error = 1")
            STATUS_OK -> filters.add("has_error = 0")
        }
        query.search?.trim()?.takeIf { it.isNotEmpty() }?.let {
            filters.add(traceSearchClause(it))
        }

        return """
            SELECT
                trace_id_canonical,
                root_service,
                root_resource,
                root_name,
                env,
                span_count,
                duration_ns,
                trace_start,
                toInt64(toUnixTimestamp64Nano(trace_start)) as start_ns,
                has_error,
                error_count,
                source
            FROM `$clickhouseDb`.$APM_TRACES_FINAL_TABLE FINAL
            WHERE ${filters.joinToString(" AND ")}
        """.trimIndent()
    }

    suspend fun listTraces(
        organizationId: Int,
        query: DdTraceListQuery,
        parentSpan: ISpan? = null,
    ): DdTraceListResponse {
        val subquery = traceSummarySubquery(
            organizationId = organizationId,
            query = query,
        )

        val countQuery = """
            SELECT count()
            FROM ($subquery)
        """.trimIndent()

        val dataQuery = """
            SELECT
                trace_id_canonical,
                root_service,
                root_resource,
                root_name,
                span_count,
                duration_ns,
                start_ns,
                has_error,
                source
            FROM ($subquery)
            ORDER BY start_ns DESC
            LIMIT ${query.limit} OFFSET ${query.offset}
            FORMAT JSONEachRow
        """.trimIndent()

        return coroutineScope {
            val countDeferred = async {
                val countResult = executeDashboardQuery(countQuery, "TabSeparated", parentSpan)
                countResult.trim().toLongOrNull() ?: 0
            }
            val dataDeferred = async {
                val result = executeDashboardQuery(dataQuery, "", parentSpan)
                if (result.isBlank()) {
                    emptyList()
                } else {
                    result.trim().lines()
                        .filter { it.isNotBlank() }
                        .map { line ->
                            val obj = json.parseToJsonElement(line).jsonObject
                            DdTraceListItem(
                                traceId = obj["trace_id_canonical"]!!
                                    .jsonPrimitive.content,
                                rootService = obj["root_service"]!!
                                    .jsonPrimitive.content,
                                rootResource = obj["root_resource"]!!
                                    .jsonPrimitive.content,
                                rootName = obj["root_name"]!!
                                    .jsonPrimitive.content,
                                spanCount = obj["span_count"]!!
                                    .jsonPrimitive.int,
                                durationNs = obj["duration_ns"]!!
                                    .jsonPrimitive.long,
                                startNs = obj["start_ns"]?.jsonPrimitive?.long
                                    ?: 0,
                                hasError = (
                                    obj["has_error"]
                                        ?.jsonPrimitive?.int ?: 0
                                    ) > 0,
                                source = obj["source"]
                                    ?.jsonPrimitive?.content ?: "datadog",
                            )
                        }
                }
            }

            DdTraceListResponse(traces = dataDeferred.await(), totalCount = countDeferred.await())
        }
    }

    suspend fun getApmOverview(
        organizationId: Int,
        query: DdTraceListQuery,
        parentSpan: ISpan? = null,
    ): DdApmOverviewResponse {
        val currentSubquery = traceSummarySubquery(
            organizationId = organizationId,
            query = query,
        )
        val previousSubquery = traceSummarySubquery(
            organizationId = organizationId,
            query = query,
            previousWindow = true,
        )

        return coroutineScope {
            val previousStatsDeferred = async { getOverviewPreviousStats(previousSubquery, parentSpan) }
            val statsDeferred = async { getOverviewStats(currentSubquery, emptyOverviewPreviousStats(), parentSpan) }
            val latencyDeferred = async { getLatencySeries(currentSubquery, parentSpan) }
            val serviceHealthDeferred = async { getServiceHealth(currentSubquery, parentSpan) }
            val resourceHotspotsDeferred = async { getResourceHotspots(currentSubquery, parentSpan) }
            val errorsDeferred = async { getOverviewErrors(currentSubquery, parentSpan) }
            val facetsDeferred = async { getOverviewFacetsCombined(currentSubquery, parentSpan) }

            val stats = statsDeferred.await()
            DdApmOverviewResponse(
                stats = stats.copy(previous = previousStatsDeferred.await()),
                latencySeries = latencyDeferred.await(),
                serviceHealth = serviceHealthDeferred.await(),
                resourceHotspots = resourceHotspotsDeferred.await(),
                errors = errorsDeferred.await(),
                facets = facetsDeferred.await(),
            )
        }
    }

    private suspend fun getOverviewStats(
        subquery: String,
        previousStats: DdApmOverviewPreviousStats,
        parentSpan: ISpan?,
    ): DdApmOverviewStats {
        val query = """
            SELECT
                count() as total_traces,
                sum(has_error) as error_traces,
                if(count() > 0, sum(has_error) / count(), 0) as error_rate,
                uniqExact(root_service) as service_count,
                uniqExact(source) as source_count,
                if(count() > 0, toUInt64(quantile(0.50)(duration_ns)), 0) as p50_duration_ns,
                if(count() > 0, toUInt64(quantile(0.95)(duration_ns)), 0) as p95_duration_ns,
                if(count() > 0, toUInt64(quantile(0.99)(duration_ns)), 0) as p99_duration_ns,
                avg(span_count) as avg_spans_per_trace
            FROM ($subquery)
            FORMAT JSONEachRow
        """.trimIndent()

        val obj = firstJsonRow(query, parentSpan) ?: return emptyOverviewStats(previousStats)
        return DdApmOverviewStats(
            totalTraces = obj.longValue("total_traces"),
            errorTraces = obj.longValue("error_traces"),
            errorRate = obj.doubleValue("error_rate"),
            serviceCount = obj.longValue("service_count"),
            sourceCount = obj.longValue("source_count"),
            p50DurationNs = obj.longValue("p50_duration_ns"),
            p95DurationNs = obj.longValue("p95_duration_ns"),
            p99DurationNs = obj.longValue("p99_duration_ns"),
            avgSpansPerTrace = obj.doubleValue("avg_spans_per_trace"),
            previous = previousStats,
        )
    }

    private suspend fun getOverviewPreviousStats(
        subquery: String,
        parentSpan: ISpan?,
    ): DdApmOverviewPreviousStats {
        val query = """
            SELECT
                count() as total_traces,
                if(count() > 0, sum(has_error) / count(), 0) as error_rate,
                if(count() > 0, toUInt64(quantile(0.50)(duration_ns)), 0) as p50_duration_ns,
                if(count() > 0, toUInt64(quantile(0.95)(duration_ns)), 0) as p95_duration_ns,
                if(count() > 0, toUInt64(quantile(0.99)(duration_ns)), 0) as p99_duration_ns,
                avg(span_count) as avg_spans_per_trace
            FROM ($subquery)
            FORMAT JSONEachRow
        """.trimIndent()

        val obj = firstJsonRow(query, parentSpan) ?: return emptyOverviewPreviousStats()
        return DdApmOverviewPreviousStats(
            totalTraces = obj.longValue("total_traces"),
            errorRate = obj.doubleValue("error_rate"),
            p50DurationNs = obj.longValue("p50_duration_ns"),
            p95DurationNs = obj.longValue("p95_duration_ns"),
            p99DurationNs = obj.longValue("p99_duration_ns"),
            avgSpansPerTrace = obj.doubleValue("avg_spans_per_trace"),
        )
    }

    private suspend fun getLatencySeries(
        subquery: String,
        parentSpan: ISpan?,
    ): List<DdApmLatencyPoint> {
        val query = """
            SELECT
                formatDateTime(toStartOfHour(trace_start), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as timestamp,
                toUInt64(quantile(0.50)(duration_ns)) as p50_duration_ns,
                toUInt64(quantile(0.95)(duration_ns)) as p95_duration_ns,
                toUInt64(quantile(0.99)(duration_ns)) as p99_duration_ns
            FROM ($subquery)
            GROUP BY timestamp
            ORDER BY timestamp
            FORMAT JSONEachRow
        """.trimIndent()

        return jsonRows(query, parentSpan).map { obj ->
            DdApmLatencyPoint(
                timestamp = obj.stringValue("timestamp"),
                p50DurationNs = obj.longValue("p50_duration_ns"),
                p95DurationNs = obj.longValue("p95_duration_ns"),
                p99DurationNs = obj.longValue("p99_duration_ns"),
            )
        }
    }

    private suspend fun getServiceHealth(
        subquery: String,
        parentSpan: ISpan?,
    ): List<DdApmServiceHealthItem> {
        val query = """
            SELECT
                root_service as service,
                argMax(source, start_ns) as source,
                count() as trace_count,
                sum(has_error) as error_count,
                if(count() > 0, sum(has_error) / count(), 0) as error_rate,
                toUInt64(quantile(0.95)(duration_ns)) as p95_duration_ns,
                avg(span_count) as avg_spans_per_trace
            FROM ($subquery)
            GROUP BY root_service
            ORDER BY error_rate DESC, p95_duration_ns DESC, trace_count DESC
            LIMIT $OVERVIEW_LIMIT
            FORMAT JSONEachRow
        """.trimIndent()

        return jsonRows(query, parentSpan).map { obj ->
            DdApmServiceHealthItem(
                service = obj.stringValue("service"),
                source = obj.stringValue("source"),
                traceCount = obj.longValue("trace_count"),
                errorCount = obj.longValue("error_count"),
                errorRate = obj.doubleValue("error_rate"),
                p95DurationNs = obj.longValue("p95_duration_ns"),
                avgSpansPerTrace = obj.doubleValue("avg_spans_per_trace"),
            )
        }
    }

    private suspend fun getResourceHotspots(
        subquery: String,
        parentSpan: ISpan?,
    ): List<DdApmResourceHotspotItem> {
        val query = """
            SELECT
                root_service as service,
                root_resource as resource,
                argMax(source, start_ns) as source,
                count() as trace_count,
                sum(has_error) as error_count,
                if(count() > 0, sum(has_error) / count(), 0) as error_rate,
                toUInt64(quantile(0.95)(duration_ns)) as p95_duration_ns
            FROM ($subquery)
            GROUP BY root_service, root_resource
            ORDER BY error_rate DESC, error_count DESC, p95_duration_ns DESC
            LIMIT $OVERVIEW_LIMIT
            FORMAT JSONEachRow
        """.trimIndent()

        return jsonRows(query, parentSpan).map { obj ->
            DdApmResourceHotspotItem(
                service = obj.stringValue("service"),
                resource = obj.stringValue("resource"),
                source = obj.stringValue("source"),
                traceCount = obj.longValue("trace_count"),
                errorCount = obj.longValue("error_count"),
                errorRate = obj.doubleValue("error_rate"),
                p95DurationNs = obj.longValue("p95_duration_ns"),
            )
        }
    }

    private suspend fun getOverviewErrors(
        subquery: String,
        parentSpan: ISpan?,
    ): List<DdApmErrorGroup> {
        val query = """
            SELECT
                root_service as service,
                root_resource as resource,
                sum(error_count) as error_count,
                formatDateTime(max(trace_start), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as last_seen,
                argMax(trace_id_canonical, start_ns) as sample_trace_id
            FROM ($subquery)
            WHERE has_error = 1
            GROUP BY root_service, root_resource
            ORDER BY error_count DESC, last_seen DESC
            LIMIT $OVERVIEW_LIMIT
            FORMAT JSONEachRow
        """.trimIndent()

        return jsonRows(query, parentSpan).mapIndexed { index, obj ->
            val service = obj.stringValue("service")
            val resource = obj.stringValue("resource")
            DdApmErrorGroup(
                id = "$service-$resource-$index",
                service = service,
                resource = resource,
                errorMessage = "Trace contains errored spans",
                errorType = "trace_error",
                count = obj.longValue("error_count"),
                lastSeen = obj.stringValue("last_seen"),
                traceId = obj.stringValue("sample_trace_id"),
            )
        }
    }

    private suspend fun getOverviewFacetsCombined(
        subquery: String,
        parentSpan: ISpan?,
    ): DdApmOverviewFacets {
        val query = """
            SELECT 'service' as facet_type, root_service as value, count() as count
            FROM ($subquery) WHERE root_service != ''
            GROUP BY root_service ORDER BY count DESC, value ASC LIMIT $MAX_FILTER_FACETS
            UNION ALL
            SELECT 'source' as facet_type, source as value, count() as count
            FROM ($subquery) WHERE source != ''
            GROUP BY source ORDER BY count DESC, value ASC LIMIT $MAX_FILTER_FACETS
            UNION ALL
            SELECT 'env' as facet_type, env as value, count() as count
            FROM ($subquery) WHERE env != ''
            GROUP BY env ORDER BY count DESC, value ASC LIMIT $MAX_FILTER_FACETS
            FORMAT JSONEachRow
        """.trimIndent()

        val rows = jsonRows(query, parentSpan)
        val services = mutableListOf<DdApmFacetItem>()
        val sources = mutableListOf<DdApmFacetItem>()
        val environments = mutableListOf<DdApmFacetItem>()
        for (obj in rows) {
            val item = DdApmFacetItem(
                value = obj.stringValue("value"),
                count = obj.longValue("count"),
            )
            when (obj.stringValue("facet_type")) {
                "service" -> services.add(item)
                "source" -> sources.add(item)
                "env" -> environments.add(item)
            }
        }
        return DdApmOverviewFacets(
            services = services,
            sources = sources,
            environments = environments,
        )
    }

    private suspend fun firstJsonRow(
        query: String,
        parentSpan: ISpan?,
    ): JsonObject? =
        jsonRows(query, parentSpan).firstOrNull()

    private suspend fun jsonRows(
        query: String,
        parentSpan: ISpan?,
    ): List<JsonObject> {
        val result = executeDashboardQuery(query, "", parentSpan)
        if (result.isBlank()) return emptyList()
        return result.trim().lines()
            .filter { it.isNotBlank() }
            .map { line -> json.parseToJsonElement(line).jsonObject }
    }

    private fun emptyOverviewStats(previousStats: DdApmOverviewPreviousStats): DdApmOverviewStats =
        DdApmOverviewStats(
            totalTraces = 0,
            errorTraces = 0,
            errorRate = 0.0,
            serviceCount = 0,
            sourceCount = 0,
            p50DurationNs = 0,
            p95DurationNs = 0,
            p99DurationNs = 0,
            avgSpansPerTrace = 0.0,
            previous = previousStats,
        )

    private fun emptyOverviewPreviousStats(): DdApmOverviewPreviousStats =
        DdApmOverviewPreviousStats(
            totalTraces = 0,
            errorRate = 0.0,
            p50DurationNs = 0,
            p95DurationNs = 0,
            p99DurationNs = 0,
            avgSpansPerTrace = 0.0,
        )

    private fun JsonObject.longValue(key: String): Long =
        this[key]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong() ?: 0L

    private fun JsonObject.doubleValue(key: String): Double =
        this[key]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0

    private fun JsonObject.stringValue(key: String): String =
        this[key]?.jsonPrimitive?.content ?: ""

    suspend fun getTraceDetail(
        organizationId: Int,
        traceId: String,
        parentSpan: ISpan? = null,
    ): DdTraceDetailResponse? {
        val orgClause = ClickHouseQueryUtils.orgIdClause(organizationId.toLong())

        fun detailQuery(traceClause: String) = """
            SELECT
                if(span_id_hex != '', span_id_hex, toString(span_id)) as span_id_out,
                if(trace_id_hex != '', trace_id_hex, toString(trace_id)) as trace_id_out,
                if(parent_id_hex != '', parent_id_hex, toString(parent_id)) as parent_id_out,
                name, service, resource, type,
                toInt64(toUnixTimestamp64Nano(start)) as start_ns,
                duration, error,
                meta, metrics,
                host, env, version,
                source, kind, status_code, status_message,
                events, links, resource_attributes
            FROM `$clickhouseDb`.apm_spans
            WHERE $orgClause
              AND $traceClause
            ORDER BY start_ns ASC
            FORMAT JSONEachRow
        """.trimIndent()

        var result = executeDashboardQuery(
            detailQuery("trace_id_hex = '${escapeSql(traceId)}'"),
            "",
            parentSpan,
        )
        if (result.isBlank() && parseTraceId(traceId) != null) {
            result = executeDashboardQuery(
                detailQuery("trace_id = ${parseTraceId(traceId)}"),
                "",
                parentSpan,
            )
        }
        if (result.isBlank()) return null

        val spans = result.trim().lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                DdSpanResponse(
                    spanId = obj["span_id_out"]!!.jsonPrimitive.content,
                    traceId = obj["trace_id_out"]!!.jsonPrimitive.content,
                    parentId = obj["parent_id_out"]!!.jsonPrimitive.content,
                    name = obj["name"]!!.jsonPrimitive.content,
                    service = obj["service"]!!.jsonPrimitive.content,
                    resource = obj["resource"]!!.jsonPrimitive.content,
                    type = obj["type"]!!.jsonPrimitive.content,
                    startNs = obj["start_ns"]?.jsonPrimitive?.long ?: 0,
                    durationNs = obj["duration"]!!.jsonPrimitive.long,
                    error = obj["error"]!!.jsonPrimitive.int,
                    meta = parseStringMap(obj["meta"]),
                    metrics = parseDoubleMap(obj["metrics"]),
                    host = obj["host"]?.jsonPrimitive?.content ?: "",
                    env = obj["env"]?.jsonPrimitive?.content ?: "",
                    version = obj["version"]?.jsonPrimitive?.content ?: "",
                    source = obj["source"]?.jsonPrimitive?.content ?: "datadog",
                    kind = obj["kind"]?.jsonPrimitive?.content ?: "",
                    statusCode = obj["status_code"]?.jsonPrimitive?.int ?: 0,
                    statusMessage = obj["status_message"]?.jsonPrimitive?.content ?: "",
                    events = obj["events"]?.jsonPrimitive?.content ?: "[]",
                    links = obj["links"]?.jsonPrimitive?.content ?: "[]",
                    resourceAttributes = parseStringMap(obj["resource_attributes"]),
                )
            }
        if (spans.isEmpty()) return null

        return DdTraceDetailResponse(traceId = traceId, spans = spans)
    }

    suspend fun getServiceMap(
        organizationId: Int,
        parentSpan: ISpan? = null,
    ): DdServiceMapResponse {
        val query = """
            SELECT
                service,
                sum(span_count) as span_count,
                sum(error_count) as error_count,
                if(sum(duration_count) = 0, 0, sum(duration_sum) / sum(duration_count)) as avg_duration_ns
            FROM `$clickhouseDb`.$APM_SERVICE_STATS_TABLE
            WHERE ${ClickHouseQueryUtils.orgIdClause(organizationId.toLong())}
              AND bucket_start >= toStartOfHour(now() - INTERVAL 1 HOUR)
            GROUP BY service
            ORDER BY span_count DESC
            LIMIT 100
            FORMAT JSONEachRow
        """.trimIndent()

        val result = executeDashboardQuery(query, "", parentSpan)
        val services = if (result.isBlank()) {
            emptyList()
        } else {
            result.trim().lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    DdServiceMapEntry(
                        service = obj["service"]!!
                            .jsonPrimitive.content,
                        spanCount = obj["span_count"]!!
                            .jsonPrimitive.long,
                        errorCount = obj["error_count"]!!
                            .jsonPrimitive.long,
                        avgDurationNs = obj["avg_duration_ns"]!!
                            .jsonPrimitive.double,
                        callsTo = emptyList(),
                    )
                }
        }

        // Build service relationships from parent-child spans
        val callsQuery = """
            SELECT
                from_service,
                to_service
            FROM `$clickhouseDb`.$APM_SERVICE_EDGES_TABLE
            WHERE ${ClickHouseQueryUtils.orgIdClause(organizationId.toLong())}
              AND bucket_start >= toStartOfHour(now() - INTERVAL 1 HOUR)
            GROUP BY from_service, to_service
            HAVING sum(call_count) > 0
            ORDER BY sum(call_count) DESC
            FORMAT JSONEachRow
        """.trimIndent()

        val callsResult = executeDashboardQuery(
            callsQuery,
            "",
            parentSpan,
        )
        val callsMap = mutableMapOf<String, MutableSet<String>>()
        if (callsResult.isNotBlank()) {
            callsResult.trim().lines()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    val from = obj["from_service"]!!
                        .jsonPrimitive.content
                    val to = obj["to_service"]!!
                        .jsonPrimitive.content
                    callsMap.getOrPut(from) { mutableSetOf() }.add(to)
                }
        }

        val enriched = services.map { svc ->
            svc.copy(
                callsTo = callsMap[svc.service]?.toList()
                    ?: emptyList()
            )
        }

        return DdServiceMapResponse(services = enriched)
    }

    suspend fun getApmErrors(
        organizationId: Int,
        service: String?,
        limit: Int,
        offset: Int,
        timeRange: DdApmQueryTimeRange = defaultApmQueryTimeRange,
        parentSpan: ISpan? = null,
    ): DdApmErrorsResponse {
        val effectiveLimit = limit.coerceAtMost(MAX_QUERY_LIMIT)
        val filters = mutableListOf(
            ClickHouseQueryUtils.orgIdClause(organizationId.toLong()),
            timeRange.bucketStartClause()
        )
        service?.let {
            filters.add("service = '${escapeSql(it)}'")
        }
        val whereClause = filters.joinToString(" AND ")

        val countQuery = """
            SELECT count()
            FROM (
                SELECT 1
                FROM `$clickhouseDb`.$APM_ERROR_GROUPS_TABLE
                WHERE $whereClause
                GROUP BY service, resource, error_message, error_type
            )
        """.trimIndent()
        val countResult = executeDashboardQuery(
            countQuery,
            "TabSeparated",
            parentSpan,
        )
        val totalCount = countResult.trim().toLongOrNull() ?: 0

        val query = """
            SELECT
                service,
                resource,
                error_message as error_msg,
                error_type,
                sumMerge(error_count_state) as error_count,
                toString(maxMerge(last_seen_state)) as last_seen,
                argMaxMerge(sample_trace_id_state) as sample_trace_id
            FROM `$clickhouseDb`.$APM_ERROR_GROUPS_TABLE
            WHERE $whereClause
            GROUP BY service, resource, error_message, error_type
            ORDER BY error_count DESC
            LIMIT $effectiveLimit OFFSET $offset
            FORMAT JSONEachRow
        """.trimIndent()

        val result = executeDashboardQuery(query, "", parentSpan)
        val errors = if (result.isBlank()) {
            emptyList()
        } else {
            result.trim().lines()
                .filter { it.isNotBlank() }
                .mapIndexed { index, line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    val svc = obj["service"]!!
                        .jsonPrimitive.content
                    val res = obj["resource"]!!
                        .jsonPrimitive.content
                    val msg = obj["error_msg"]
                        ?.jsonPrimitive?.content ?: ""
                    DdApmErrorGroup(
                        id = "${svc}_${res}_$index",
                        service = svc,
                        resource = res,
                        errorMessage = msg,
                        errorType = obj["error_type"]
                            ?.jsonPrimitive?.content ?: "",
                        count = obj["error_count"]!!
                            .jsonPrimitive.long,
                        lastSeen = obj["last_seen"]
                            ?.jsonPrimitive?.content ?: "",
                        traceId = obj["sample_trace_id"]
                            ?.jsonPrimitive?.content ?: "",
                    )
                }
        }

        val serviceFacetsQuery = """
            SELECT
                service,
                sumMerge(error_count_state) as error_count
            FROM `$clickhouseDb`.$APM_ERROR_GROUPS_TABLE
            WHERE ${ClickHouseQueryUtils.orgIdClause(organizationId.toLong())}
                AND ${timeRange.bucketStartClause()}
            GROUP BY service
            ORDER BY error_count DESC
            FORMAT JSONEachRow
        """.trimIndent()
        val serviceFacetsResult = executeDashboardQuery(serviceFacetsQuery, "", parentSpan)
        val serviceFacets = if (serviceFacetsResult.isBlank()) {
            emptyList()
        } else {
            serviceFacetsResult.trim().lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    DdApmServiceFacet(
                        service = obj["service"]!!
                            .jsonPrimitive.content,
                        count = obj["error_count"]!!
                            .jsonPrimitive.long,
                    )
                }
        }

        return DdApmErrorsResponse(
            errors = errors,
            totalCount = totalCount,
            serviceFacets = serviceFacets,
        )
    }

    suspend fun listResourceStats(
        organizationId: Int,
        service: String?,
        limit: Int,
        offset: Int,
        timeRange: DdApmQueryTimeRange = defaultApmQueryTimeRange,
        parentSpan: ISpan? = null,
    ): DdResourceStatsResponse =
        listResourceStats(
            organizationId = organizationId,
            query = DdResourceStatsQuery(
                service = service,
                limit = limit,
                offset = offset,
                timeRange = timeRange,
            ),
            parentSpan = parentSpan,
        )

    suspend fun listResourceStats(
        organizationId: Int,
        query: DdResourceStatsQuery,
        parentSpan: ISpan? = null,
    ): DdResourceStatsResponse {
        if (query.requiresTraceSummaryResources()) {
            return listTraceSummaryResourceStats(organizationId, query, parentSpan)
        }

        val effectiveLimit = query.limit.coerceAtMost(MAX_QUERY_LIMIT)
        val filters = mutableListOf(
            ClickHouseQueryUtils.orgIdClause(organizationId.toLong()),
            query.timeRange.bucketStartClause()
        )
        query.service?.let { filters.add("service = '${escapeSql(it)}'") }
        val whereClause = filters.joinToString(" AND ")

        val countQuery = """
            SELECT count()
            FROM (
                SELECT 1
                FROM `$clickhouseDb`.$APM_RESOURCE_STATS_TABLE
                WHERE $whereClause
                GROUP BY service, resource, name, type
            )
        """.trimIndent()
        val countResult = executeDashboardQuery(
            countQuery,
            "TabSeparated",
            parentSpan,
        )
        val totalCount = countResult.trim().toLongOrNull() ?: 0

        val querySql = """
            SELECT
                service,
                resource,
                name,
                type,
                SUM(total_hits) as total_hits,
                SUM(total_errors) as total_errors,
                SUM(ok_summary_sum) as ok_sum,
                SUM(ok_summary_count) as ok_count
            FROM `$clickhouseDb`.$APM_RESOURCE_STATS_TABLE
            WHERE $whereClause
            GROUP BY service, resource, name, type
            ORDER BY total_hits DESC
            LIMIT $effectiveLimit OFFSET ${query.offset}
            FORMAT JSONEachRow
        """.trimIndent()

        val result = executeDashboardQuery(querySql, "", parentSpan)
        val resources = if (result.isBlank()) {
            emptyList()
        } else {
            result.trim().lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    val totalHits = obj["total_hits"]!!.jsonPrimitive.long
                    val totalErrors = obj["total_errors"]!!
                        .jsonPrimitive.long
                    val okSum = obj["ok_sum"]!!.jsonPrimitive.double
                    val okCount = obj["ok_count"]!!.jsonPrimitive.long
                    val avgDurationNs = if (okCount > 0) {
                        (okSum / okCount).toLong()
                    } else {
                        0L
                    }
                    val errorRate = if (totalHits > 0) {
                        totalErrors.toDouble() / totalHits.toDouble()
                    } else {
                        0.0
                    }
                    DdResourceStatsItem(
                        service = obj["service"]!!.jsonPrimitive.content,
                        resource = obj["resource"]!!
                            .jsonPrimitive.content,
                        name = obj["name"]!!.jsonPrimitive.content,
                        type = obj["type"]!!.jsonPrimitive.content,
                        totalHits = totalHits,
                        totalErrors = totalErrors,
                        avgDurationNs = avgDurationNs,
                        errorRate = errorRate,
                    )
                }
        }

        return DdResourceStatsResponse(
            resources = resources,
            totalCount = totalCount,
        )
    }

    private suspend fun listTraceSummaryResourceStats(
        organizationId: Int,
        query: DdResourceStatsQuery,
        parentSpan: ISpan?,
    ): DdResourceStatsResponse {
        val subquery = traceSummarySubquery(
            organizationId = organizationId,
            query = DdTraceListQuery(
                service = query.service,
                env = query.env,
                source = query.source,
                status = query.status.takeUnless { it == STATUS_ERROR },
                search = query.search,
                timeRange = query.timeRange,
            ),
        )
        val effectiveLimit = query.limit.coerceAtMost(MAX_QUERY_LIMIT)
        val groupHavingClause = if (query.status == STATUS_ERROR) {
            "HAVING total_errors > 0"
        } else {
            ""
        }
        val groupedResources = """
            SELECT
                root_service as service,
                root_resource as resource,
                argMax(root_name, start_ns) as name,
                '' as type,
                count() as total_hits,
                sum(has_error) as total_errors,
                toUInt64(avg(duration_ns)) as avg_duration_ns,
                if(count() > 0, sum(has_error) / count(), 0) as error_rate
            FROM ($subquery)
            GROUP BY root_service, root_resource
            $groupHavingClause
        """.trimIndent()

        val countQuery = """
            SELECT count()
            FROM ($groupedResources)
        """.trimIndent()
        val countResult = executeDashboardQuery(
            countQuery,
            "TabSeparated",
            parentSpan,
        )
        val totalCount = countResult.trim().toLongOrNull() ?: 0

        val querySql = """
            SELECT
                service,
                resource,
                name,
                type,
                total_hits,
                total_errors,
                avg_duration_ns,
                error_rate
            FROM ($groupedResources)
            ORDER BY total_errors DESC, error_rate DESC, avg_duration_ns DESC, total_hits DESC
            LIMIT $effectiveLimit OFFSET ${query.offset}
            FORMAT JSONEachRow
        """.trimIndent()

        val resources = jsonRows(querySql, parentSpan).map { obj ->
            DdResourceStatsItem(
                service = obj.stringValue("service"),
                resource = obj.stringValue("resource"),
                name = obj.stringValue("name"),
                type = obj.stringValue("type"),
                totalHits = obj.longValue("total_hits"),
                totalErrors = obj.longValue("total_errors"),
                avgDurationNs = obj.longValue("avg_duration_ns"),
                errorRate = obj.doubleValue("error_rate"),
            )
        }

        return DdResourceStatsResponse(
            resources = resources,
            totalCount = totalCount,
        )
    }

    private fun DdResourceStatsQuery.requiresTraceSummaryResources(): Boolean =
        !env.isNullOrBlank() ||
            !source.isNullOrBlank() ||
            !status.isNullOrBlank() ||
            !search.isNullOrBlank()

    // --- Internal helpers ---
    private suspend fun executeDashboardQuery(
        query: String,
        format: String,
        parentSpan: ISpan?,
    ): String =
        if (parentSpan == null) {
            ClickHouseClient.executeWithFormat(query, format)
        } else {
            ClickHouseClient.executeWithFormat(query, format, parentSpan)
        }

    private fun traceSearchClause(search: String): String {
        val escapedSearch = escapeSql(search)
        return """
            (
                positionCaseInsensitive(trace_id_canonical, '$escapedSearch') > 0 OR
                positionCaseInsensitive(root_service, '$escapedSearch') > 0 OR
                positionCaseInsensitive(root_resource, '$escapedSearch') > 0 OR
                positionCaseInsensitive(root_name, '$escapedSearch') > 0
            )
        """.trimIndent()
    }

    internal fun parseTraceId(traceId: String): ULong? =
        traceId.toULongOrNull()

    private fun List<List<DdSpan>>.toServiceMapSpans(
        organizationId: Int,
        env: String,
    ): List<ApmServiceMapSpan> =
        flatten().map { span ->
            ApmServiceMapSpan(
                organizationId = organizationId.toLong(),
                traceKey = span.traceId.toString(),
                spanKey = span.spanId.toString(),
                parentKey = if (span.parentId == 0UL) "" else span.parentId.toString(),
                service = span.service,
                env = span.meta["env"] ?: env,
                source = DATADOG_SOURCE,
                startNanos = span.start,
                durationNanos = span.duration,
                error = span.error,
            )
        }

    @Suppress("NestedBlockDepth")
    private fun unpackSpan(unpacker: MessageUnpacker): DdSpan {
        val mapSize = unpacker.unpackMapHeader()
        var traceId = 0uL
        var spanId = 0uL
        var parentId = 0uL
        var name = ""
        var service = ""
        var resource = ""
        var type = ""
        var start = 0L
        var duration = 0L
        var error = 0
        val meta = mutableMapOf<String, String>()
        val metrics = mutableMapOf<String, Double>()

        for (i in 0 until mapSize) {
            val key = unpacker.unpackString()
            when (key) {
                "trace_id" -> traceId = unpacker.unpackBigInteger().toLong().toULong()
                "span_id" -> spanId = unpacker.unpackBigInteger().toLong().toULong()
                "parent_id" -> parentId = unpacker.unpackBigInteger().toLong().toULong()
                "name" -> name = unpacker.unpackString()
                "service" -> service = unpacker.unpackString()
                "resource" -> resource = unpacker.unpackString()
                "type" -> type = unpacker.unpackString()
                "start" -> start = unpacker.unpackLong()
                "duration" -> duration = unpacker.unpackLong()
                "error" -> error = unpacker.unpackInt()
                "meta" -> {
                    val mSize = unpacker.unpackMapHeader()
                    for (j in 0 until mSize) {
                        val mk = unpacker.unpackString()
                        val mv = unpacker.unpackString()
                            .take(MAX_META_VALUE_LENGTH)
                        meta[mk] = mv
                    }
                }
                "metrics" -> {
                    val mSize = unpacker.unpackMapHeader()
                    for (j in 0 until mSize) {
                        val mk = unpacker.unpackString()
                        val mv = unpacker.unpackDouble()
                        metrics[mk] = mv
                    }
                }
                else -> unpacker.skipValue()
            }
        }

        return DdSpan(
            traceId = traceId,
            spanId = spanId,
            parentId = parentId,
            name = name,
            service = service,
            resource = resource,
            type = type,
            start = start,
            duration = duration,
            error = error,
            meta = meta,
            metrics = metrics,
        )
    }

    private fun parseStringMap(
        element: kotlinx.serialization.json.JsonElement?,
    ): Map<String, String> {
        if (element == null || element !is JsonObject) return emptyMap()
        return element.mapValues { it.value.jsonPrimitive.content }
    }

    private fun parseDoubleMap(
        element: kotlinx.serialization.json.JsonElement?,
    ): Map<String, Double> {
        if (element == null || element !is JsonObject) return emptyMap()
        return element.mapValues { it.value.jsonPrimitive.double }
    }

    /**
     * Parse a protobuf AgentPayload (v0.2 format sent by dd-agent trace writer).
     * AgentPayload fields:
     *   1 (string): hostName
     *   2 (string): env
     *   5 (repeated TracerPayload): tracerPayloads
     */
    fun parseProtobufAgentPayload(bytes: ByteArray): List<List<DdSpan>> {
        val input = CodedInputStream.newInstance(bytes)
        val traces = mutableListOf<List<DdSpan>>()
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                (FIELD_5 shl FIELD_SHIFT) or 2 -> {
                    val payloadBytes = input.readBytes().toByteArray()
                    traces.addAll(decodeTracerPayload(payloadBytes))
                }
                else -> input.skipField(tag)
            }
        }
        return traces
    }

    // TracerPayload fields: 6 (repeated TraceChunk): chunks
    private fun decodeTracerPayload(bytes: ByteArray): List<List<DdSpan>> {
        val input = CodedInputStream.newInstance(bytes)
        val traces = mutableListOf<List<DdSpan>>()
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                (FIELD_6 shl FIELD_SHIFT) or 2 -> {
                    val chunkBytes = input.readBytes().toByteArray()
                    val spans = decodeTraceChunk(chunkBytes)
                    if (spans.isNotEmpty()) traces.add(spans)
                }
                else -> input.skipField(tag)
            }
        }
        return traces
    }

    // TraceChunk fields: 3 (repeated Span): spans
    private fun decodeTraceChunk(bytes: ByteArray): List<DdSpan> {
        val input = CodedInputStream.newInstance(bytes)
        val spans = mutableListOf<DdSpan>()
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                (FIELD_3 shl FIELD_SHIFT) or 2 -> {
                    val spanBytes = input.readBytes().toByteArray()
                    spans.add(decodeProtobufSpan(spanBytes))
                }
                else -> input.skipField(tag)
            }
        }
        return spans
    }

    // APM Span fields (proto field numbers from span.pb.go):
    //   1=service, 2=name, 3=resource, 4=traceID, 5=spanID,
    //   6=parentID, 7=start, 8=duration, 9=error, 10=meta, 11=metrics, 12=type
    @Suppress("CyclomaticComplexMethod")
    private fun decodeProtobufSpan(bytes: ByteArray): DdSpan {
        val input = CodedInputStream.newInstance(bytes)
        var service = ""
        var name = ""
        var resource = ""
        var type = ""
        var traceId = 0uL
        var spanId = 0uL
        var parentId = 0uL
        var start = 0L
        var duration = 0L
        var error = 0
        val meta = mutableMapOf<String, String>()
        val metrics = mutableMapOf<String, Double>()
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                (1 shl FIELD_SHIFT) or 2 -> service = input.readString()
                (2 shl FIELD_SHIFT) or 2 -> name = input.readString()
                (FIELD_3 shl FIELD_SHIFT) or 2 -> resource = input.readString()
                (FIELD_4 shl FIELD_SHIFT) or 0 -> traceId = input.readUInt64().toULong()
                (FIELD_5 shl FIELD_SHIFT) or 0 -> spanId = input.readUInt64().toULong()
                (FIELD_6 shl FIELD_SHIFT) or 0 -> parentId = input.readUInt64().toULong()
                (FIELD_7 shl FIELD_SHIFT) or 0 -> start = input.readInt64()
                (FIELD_8 shl FIELD_SHIFT) or 0 -> duration = input.readInt64()
                (PROTO_FIELD_9 shl FIELD_SHIFT) or 0 -> error = input.readInt32()
                (PROTO_FIELD_10 shl FIELD_SHIFT) or 2 -> {
                    val (k, v) = decodeStringStringEntry(input.readBytes().toByteArray())
                    meta[k] = v
                }
                (PROTO_FIELD_11 shl FIELD_SHIFT) or 2 -> {
                    val (k, v) = decodeStringDoubleEntry(input.readBytes().toByteArray())
                    metrics[k] = v
                }
                (PROTO_FIELD_12 shl FIELD_SHIFT) or 2 -> type = input.readString()
                else -> input.skipField(tag)
            }
        }
        return DdSpan(
            traceId = traceId, spanId = spanId, parentId = parentId,
            name = name, service = service, resource = resource, type = type,
            start = start, duration = duration, error = error,
            meta = meta, metrics = metrics,
        )
    }

    // Map entry: field 1 = key (string), field 2 = value (string)
    private fun decodeStringStringEntry(bytes: ByteArray): Pair<String, String> {
        val input = CodedInputStream.newInstance(bytes)
        var key = ""
        var value = ""
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                (1 shl FIELD_SHIFT) or 2 -> key = input.readString()
                (2 shl FIELD_SHIFT) or 2 -> value = input.readString()
                else -> input.skipField(tag)
            }
        }
        return key to value
    }

    // Map entry: field 1 = key (string), field 2 = value (fixed64 double)
    private fun decodeStringDoubleEntry(bytes: ByteArray): Pair<String, Double> {
        val input = CodedInputStream.newInstance(bytes)
        var key = ""
        var value = 0.0
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                (1 shl FIELD_SHIFT) or 2 -> key = input.readString()
                (2 shl FIELD_SHIFT) or 1 -> value = input.readDouble()
                else -> input.skipField(tag)
            }
        }
        return key to value
    }
}
