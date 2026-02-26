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

package com.moneat.monitor.services

import com.moneat.config.ClickHouseClient
import com.moneat.monitor.models.ApmErrorGroup
import com.moneat.monitor.models.ApmErrorsResponse
import com.moneat.monitor.models.ApmServiceMapEntry
import com.moneat.monitor.models.ApmServiceMapResponse
import com.moneat.monitor.models.ApmSpanResponse
import com.moneat.monitor.models.ApmTraceDetailResponse
import com.moneat.monitor.models.ApmTraceListItem
import com.moneat.monitor.models.ApmTraceListResponse
import com.moneat.utils.ClickHouseQueryUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

object ApmQueryService {
    private val clickhouseDb by lazy { ClickHouseClient.getDatabase() }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listTraces(
        organizationId: Int,
        service: String?,
        env: String?,
        limit: Int,
        offset: Int,
    ): ApmTraceListResponse {
        val effectiveLimit = limit.coerceAtMost(MAX_LIMIT)
        val filters = mutableListOf(
            ClickHouseQueryUtils.orgIdClause(organizationId.toLong())
        )
        service?.let { filters.add("service = '${escapeSql(it)}'") }
        env?.let { filters.add("env = '${escapeSql(it)}'") }
        val whereClause = filters.joinToString(" AND ")

        val countQuery = """
            SELECT count(DISTINCT trace_id)
            FROM $clickhouseDb.apm_spans
            WHERE $whereClause
        """.trimIndent()
        val countResult = ClickHouseClient.executeWithFormat(
            countQuery,
            "TabSeparated",
        )
        val totalCount = countResult.trim().toLongOrNull() ?: 0

        val query = """
            SELECT
                trace_id,
                min(service) as root_service,
                min(resource) as root_resource,
                min(name) as root_name,
                count() as span_count,
                max(duration) as duration_ns,
                toInt64(toUnixTimestamp64Nano(min(start))) as start_ns,
                max(error) as has_error
            FROM $clickhouseDb.apm_spans
            WHERE $whereClause
            GROUP BY trace_id
            ORDER BY start_ns DESC
            LIMIT $effectiveLimit OFFSET $offset
            FORMAT JSONEachRow
        """.trimIndent()

        val result = ClickHouseClient.executeWithFormat(query, "")
        val traces = parseTraceList(result)

        return ApmTraceListResponse(traces = traces, totalCount = totalCount)
    }

    suspend fun getTraceDetail(
        organizationId: Int,
        traceId: String,
    ): ApmTraceDetailResponse? {
        val parsedTraceId = traceId.toULongOrNull() ?: return null
        val query = """
            SELECT
                span_id, trace_id, parent_id,
                name, service, resource, type,
                toInt64(toUnixTimestamp64Nano(start)) as start_ns,
                duration, error,
                meta, metrics,
                host, env, version
            FROM $clickhouseDb.apm_spans
            WHERE ${ClickHouseQueryUtils.orgIdClause(organizationId.toLong())}
              AND trace_id = $parsedTraceId
            ORDER BY start_ns ASC
            FORMAT JSONEachRow
        """.trimIndent()

        val result = ClickHouseClient.executeWithFormat(query, "")
        if (result.isBlank()) return null

        val spans = result.trim().lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                ApmSpanResponse(
                    spanId = obj["span_id"]!!.jsonPrimitive.content,
                    traceId = obj["trace_id"]!!.jsonPrimitive.content,
                    parentId = obj["parent_id"]!!.jsonPrimitive.content,
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
                )
            }
        if (spans.isEmpty()) return null

        return ApmTraceDetailResponse(traceId = traceId, spans = spans)
    }

    suspend fun getServiceMap(organizationId: Int): ApmServiceMapResponse {
        val orgClause = ClickHouseQueryUtils.orgIdClause(
            organizationId.toLong()
        )
        val query = """
            SELECT
                service,
                count() as span_count,
                countIf(error = 1) as error_count,
                avg(duration) as avg_duration_ns
            FROM $clickhouseDb.apm_spans
            WHERE $orgClause
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
                    ApmServiceMapEntry(
                        service = obj["service"]!!.jsonPrimitive.content,
                        spanCount = obj["span_count"]!!.jsonPrimitive.long,
                        errorCount = obj["error_count"]!!.jsonPrimitive.long,
                        avgDurationNs = obj["avg_duration_ns"]!!
                            .jsonPrimitive.double,
                        callsTo = emptyList(),
                    )
                }
        }

        val childOrgClause = ClickHouseQueryUtils.orgIdClause(
            organizationId.toLong(),
            "child.organization_id",
        )
        val callsQuery = """
            SELECT
                parent.service as from_service,
                child.service as to_service
            FROM $clickhouseDb.apm_spans child
            INNER JOIN $clickhouseDb.apm_spans parent
                ON child.parent_id = parent.span_id
                AND child.trace_id = parent.trace_id
                AND child.organization_id = parent.organization_id
            WHERE $childOrgClause
              AND child.start >= now() - INTERVAL 1 HOUR
              AND parent.service != child.service
            GROUP BY parent.service, child.service
            FORMAT JSONEachRow
        """.trimIndent()

        val callsResult = ClickHouseClient.executeWithFormat(callsQuery, "")
        val callsMap = mutableMapOf<String, MutableSet<String>>()
        if (callsResult.isNotBlank()) {
            callsResult.trim().lines()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    val from = obj["from_service"]!!.jsonPrimitive.content
                    val to = obj["to_service"]!!.jsonPrimitive.content
                    callsMap.getOrPut(from) { mutableSetOf() }.add(to)
                }
        }

        val enriched = services.map { svc ->
            svc.copy(callsTo = callsMap[svc.service]?.toList() ?: emptyList())
        }

        return ApmServiceMapResponse(services = enriched)
    }

    suspend fun getApmErrors(
        organizationId: Int,
        service: String?,
        limit: Int,
        offset: Int,
    ): ApmErrorsResponse {
        val effectiveLimit = limit.coerceAtMost(MAX_LIMIT)
        val filters = mutableListOf(
            ClickHouseQueryUtils.orgIdClause(organizationId.toLong()),
            "error = 1"
        )
        service?.let { filters.add("service = '${escapeSql(it)}'") }
        val whereClause = filters.joinToString(" AND ")

        val countQuery = """
            SELECT count(DISTINCT concat(service, resource, meta['error.msg']))
            FROM $clickhouseDb.apm_spans
            WHERE $whereClause
        """.trimIndent()
        val countResult = ClickHouseClient.executeWithFormat(
            countQuery,
            "TabSeparated",
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
                any(trace_id) as sample_trace_id
            FROM $clickhouseDb.apm_spans
            WHERE $whereClause
            GROUP BY service, resource, meta['error.msg'], meta['error.type']
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
                    val svc = obj["service"]!!.jsonPrimitive.content
                    val res = obj["resource"]!!.jsonPrimitive.content
                    val msg = obj["error_msg"]?.jsonPrimitive?.content ?: ""
                    ApmErrorGroup(
                        id = "${svc}_${res}_$index",
                        service = svc,
                        resource = res,
                        errorMessage = msg,
                        errorType = obj["error_type"]
                            ?.jsonPrimitive?.content ?: "",
                        count = obj["error_count"]!!.jsonPrimitive.long,
                        lastSeen = obj["last_seen"]
                            ?.jsonPrimitive?.content ?: "",
                        traceId = obj["sample_trace_id"]
                            ?.jsonPrimitive?.content ?: "",
                    )
                }
        }

        return ApmErrorsResponse(errors = errors, totalCount = totalCount)
    }

    // --- Internal helpers ---

    private fun parseTraceList(result: String): List<ApmTraceListItem> {
        if (result.isBlank()) return emptyList()
        return result.trim().lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                val errorFlag = obj["has_error"]
                    ?.jsonPrimitive?.int ?: 0
                ApmTraceListItem(
                    traceId = obj["trace_id"]!!.jsonPrimitive.content,
                    rootService = obj["root_service"]!!
                        .jsonPrimitive.content,
                    rootResource = obj["root_resource"]!!
                        .jsonPrimitive.content,
                    rootName = obj["root_name"]!!.jsonPrimitive.content,
                    spanCount = obj["span_count"]!!.jsonPrimitive.int,
                    durationNs = obj["duration_ns"]!!.jsonPrimitive.long,
                    startNs = obj["start_ns"]?.jsonPrimitive?.long ?: 0,
                    hasError = errorFlag > 0,
                )
            }
    }

    private fun parseStringMap(
        element: kotlinx.serialization.json.JsonElement?,
    ): Map<String, String> {
        if (element == null ||
            element !is kotlinx.serialization.json.JsonObject
        ) {
            return emptyMap()
        }
        return element.mapValues { it.value.jsonPrimitive.content }
    }

    private fun parseDoubleMap(
        element: kotlinx.serialization.json.JsonElement?,
    ): Map<String, Double> {
        if (element == null ||
            element !is kotlinx.serialization.json.JsonObject
        ) {
            return emptyMap()
        }
        return element.mapValues { it.value.jsonPrimitive.double }
    }

    private fun escapeSql(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")
}
