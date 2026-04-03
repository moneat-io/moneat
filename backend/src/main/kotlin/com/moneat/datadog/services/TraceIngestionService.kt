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
import com.moneat.config.ClickHouseClient
import com.moneat.datadog.models.DdApmErrorGroup
import com.moneat.datadog.models.DdApmErrorsResponse
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker

private val logger = KotlinLogging.logger {}

/** Public trace id: prefer hex when stored, else numeric string. */
private const val CANONICAL_TRACE_ID_SQL =
    "if(trace_id_hex != '', trace_id_hex, toString(trace_id))"

private const val MAX_META_VALUE_LENGTH = 5000
private const val DEFAULT_QUERY_LIMIT = 50
private const val MAX_QUERY_LIMIT = 200
private const val HEX_RADIX = 16
private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
private const val PROTO_TAG_SHIFT = 3
private const val PROTO_FIELD_3 = 3
private const val PROTO_FIELD_4 = 4
private const val PROTO_FIELD_5 = 5
private const val PROTO_FIELD_6 = 6
private const val PROTO_FIELD_7 = 7
private const val PROTO_FIELD_8 = 8
private const val PROTO_FIELD_9 = 9
private const val PROTO_FIELD_10 = 10
private const val PROTO_FIELD_11 = 11
private const val PROTO_FIELD_12 = 12
private const val PROTO_FIELD_14 = 14

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
                'datadog'
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

        val totalBytes = allSpans.sumOf {
            it.name.length + it.service.length +
                it.resource.length + it.meta.entries.sumOf { e ->
                    e.key.length + e.value.length
                }
        }
        usageTracking.recordOrgUsage(organizationId, "dd_trace", totalBytes)
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

    suspend fun listTraces(
        organizationId: Int,
        service: String?,
        env: String?,
        limit: Int,
        offset: Int,
    ): DdTraceListResponse {
        val filters = mutableListOf(
            ClickHouseQueryUtils.orgIdClause(organizationId.toLong())
        )
        service?.let {
            filters.add("service = '${escapeSql(it)}'")
        }
        env?.let {
            filters.add("env = '${escapeSql(it)}'")
        }
        val whereClause = filters.joinToString(" AND ")

        val countQuery = """
            SELECT count(DISTINCT $CANONICAL_TRACE_ID_SQL)
            FROM `$clickhouseDb`.apm_spans
            WHERE $whereClause
        """.trimIndent()
        val countResult = ClickHouseClient.executeWithFormat(
            countQuery,
            "TabSeparated"
        )
        val totalCount = countResult.trim().toLongOrNull() ?: 0

        val query = """
            SELECT
                $CANONICAL_TRACE_ID_SQL as trace_id_canonical,
                argMin(service, if(parent_id = 0, 0, 1)) as root_service,
                argMin(resource, if(parent_id = 0, 0, 1)) as root_resource,
                argMin(name, if(parent_id = 0, 0, 1)) as root_name,
                count() as span_count,
                max(duration) as duration_ns,
                toInt64(toUnixTimestamp64Nano(min(start))) as start_ns,
                max(error) as has_error,
                argMin(source, if(parent_id = 0, 0, 1)) as source
            FROM `$clickhouseDb`.apm_spans
            WHERE $whereClause
            GROUP BY trace_id_canonical
            ORDER BY start_ns DESC
            LIMIT $limit OFFSET $offset
            FORMAT JSONEachRow
        """.trimIndent()

        val result = ClickHouseClient.executeWithFormat(query, "")
        val traces = if (result.isBlank()) {
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

        return DdTraceListResponse(traces = traces, totalCount = totalCount)
    }

    suspend fun getTraceDetail(
        organizationId: Int,
        traceId: String,
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

        var result = ClickHouseClient.executeWithFormat(
            detailQuery("trace_id_hex = '${escapeSql(traceId)}'"),
            "",
        )
        if (result.isBlank() && parseTraceId(traceId) != null) {
            result = ClickHouseClient.executeWithFormat(
                detailQuery("trace_id = ${parseTraceId(traceId)}"),
                "",
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
    ): DdServiceMapResponse {
        val query = """
            SELECT
                service,
                count() as span_count,
                countIf(error = 1) as error_count,
                avg(duration) as avg_duration_ns
            FROM `$clickhouseDb`.apm_spans
            WHERE ${ClickHouseQueryUtils.orgIdClause(organizationId.toLong())}
              AND start >= now() - INTERVAL 1 HOUR
            GROUP BY service
            ORDER BY span_count DESC
            LIMIT 100
            FORMAT JSONEachRow
        """.trimIndent()

        val result = ClickHouseClient.executeWithFormat(query, "")
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
                parent.service as from_service,
                child.service as to_service
            FROM `$clickhouseDb`.apm_spans child
            INNER JOIN `$clickhouseDb`.apm_spans parent
                ON child.parent_id = parent.span_id
                AND child.parent_id_high = parent.span_id_high
                AND child.trace_id = parent.trace_id
                AND child.trace_id_high = parent.trace_id_high
                AND child.organization_id = parent.organization_id
            WHERE ${ClickHouseQueryUtils.orgIdClause(organizationId.toLong(), "child.organization_id")}
              AND child.start >= now() - INTERVAL 1 HOUR
              AND parent.service != child.service
            GROUP BY parent.service, child.service
            FORMAT JSONEachRow
        """.trimIndent()

        val callsResult = ClickHouseClient.executeWithFormat(
            callsQuery,
            ""
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
    ): DdApmErrorsResponse {
        val effectiveLimit = limit.coerceAtMost(MAX_QUERY_LIMIT)
        val filters = mutableListOf(
            ClickHouseQueryUtils.orgIdClause(organizationId.toLong()),
            "error = 1"
        )
        service?.let {
            filters.add("service = '${escapeSql(it)}'")
        }
        val whereClause = filters.joinToString(" AND ")

        val countQuery = """
            SELECT count(DISTINCT
                concat(service, resource, meta['error.msg']))
            FROM `$clickhouseDb`.apm_spans
            WHERE $whereClause
        """.trimIndent()
        val countResult = ClickHouseClient.executeWithFormat(
            countQuery,
            "TabSeparated"
        )
        val totalCount = countResult.trim().toLongOrNull() ?: 0

        val query = """
            SELECT
                service,
                resource,
                meta['error.msg'] as error_msg,
                meta['error.type'] as error_type,
                count() as error_count,
                max(start) as last_seen,
                any($CANONICAL_TRACE_ID_SQL) as sample_trace_id
            FROM `$clickhouseDb`.apm_spans
            WHERE $whereClause
            GROUP BY service, resource,
                meta['error.msg'], meta['error.type']
            ORDER BY error_count DESC
            LIMIT $effectiveLimit OFFSET $offset
            FORMAT JSONEachRow
        """.trimIndent()

        val result = ClickHouseClient.executeWithFormat(query, "")
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

        return DdApmErrorsResponse(
            errors = errors,
            totalCount = totalCount,
        )
    }

    suspend fun listResourceStats(
        organizationId: Int,
        service: String?,
        limit: Int,
        offset: Int,
    ): DdResourceStatsResponse {
        val effectiveLimit = limit.coerceAtMost(MAX_QUERY_LIMIT)
        val filters = mutableListOf(
            ClickHouseQueryUtils.orgIdClause(organizationId.toLong())
        )
        service?.let { filters.add("service = '${escapeSql(it)}'") }
        val whereClause = filters.joinToString(" AND ")

        val countQuery = """
            SELECT count(DISTINCT (service, resource, name, type))
            FROM `$clickhouseDb`.trace_stats
            WHERE $whereClause
        """.trimIndent()
        val countResult = ClickHouseClient.executeWithFormat(
            countQuery,
            "TabSeparated"
        )
        val totalCount = countResult.trim().toLongOrNull() ?: 0

        val query = """
            SELECT
                service,
                resource,
                name,
                type,
                SUM(hits) as total_hits,
                SUM(errors) as total_errors,
                SUM(ok_summary_sum) as ok_sum,
                SUM(ok_summary_count) as ok_count
            FROM `$clickhouseDb`.trace_stats
            WHERE $whereClause
            GROUP BY service, resource, name, type
            ORDER BY total_hits DESC
            LIMIT $effectiveLimit OFFSET $offset
            FORMAT JSONEachRow
        """.trimIndent()

        val result = ClickHouseClient.executeWithFormat(query, "")
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

    // --- Internal helpers ---
    internal fun parseTraceId(traceId: String): ULong? =
        traceId.toULongOrNull()

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
                (PROTO_FIELD_5 shl PROTO_TAG_SHIFT) or 2 -> {
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
                (PROTO_FIELD_6 shl PROTO_TAG_SHIFT) or 2 -> {
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
                (PROTO_FIELD_3 shl PROTO_TAG_SHIFT) or 2 -> {
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
                (1 shl PROTO_TAG_SHIFT) or 2 -> service = input.readString()
                (2 shl PROTO_TAG_SHIFT) or 2 -> name = input.readString()
                (PROTO_FIELD_3 shl PROTO_TAG_SHIFT) or 2 -> resource = input.readString()
                (PROTO_FIELD_4 shl PROTO_TAG_SHIFT) or 0 -> traceId = input.readUInt64().toULong()
                (PROTO_FIELD_5 shl PROTO_TAG_SHIFT) or 0 -> spanId = input.readUInt64().toULong()
                (PROTO_FIELD_6 shl PROTO_TAG_SHIFT) or 0 -> parentId = input.readUInt64().toULong()
                (PROTO_FIELD_7 shl PROTO_TAG_SHIFT) or 0 -> start = input.readInt64()
                (PROTO_FIELD_8 shl PROTO_TAG_SHIFT) or 0 -> duration = input.readInt64()
                (PROTO_FIELD_9 shl PROTO_TAG_SHIFT) or 0 -> error = input.readInt32()
                (PROTO_FIELD_10 shl PROTO_TAG_SHIFT) or 2 -> {
                    val (k, v) = decodeStringStringEntry(input.readBytes().toByteArray())
                    meta[k] = v
                }
                (PROTO_FIELD_11 shl PROTO_TAG_SHIFT) or 2 -> {
                    val (k, v) = decodeStringDoubleEntry(input.readBytes().toByteArray())
                    metrics[k] = v
                }
                (PROTO_FIELD_12 shl PROTO_TAG_SHIFT) or 2 -> type = input.readString()
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
                (1 shl PROTO_TAG_SHIFT) or 2 -> key = input.readString()
                (2 shl PROTO_TAG_SHIFT) or 2 -> value = input.readString()
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
                (1 shl PROTO_TAG_SHIFT) or 2 -> key = input.readString()
                (2 shl PROTO_TAG_SHIFT) or 1 -> value = input.readDouble()
                else -> input.skipField(tag)
            }
        }
        return key to value
    }
}
