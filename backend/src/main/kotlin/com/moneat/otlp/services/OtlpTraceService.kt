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

package com.moneat.otlp.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.otlp.OtlpParsingUtils
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }
private const val OTLP_SPAN_STATUS_ERROR = 2
private const val HEX_RADIX = 16

@Serializable
data class OtlpSpanInsert(
    val traceIdHex: String,
    val spanIdHex: String,
    val parentIdHex: String,
    val organizationId: Long,
    val name: String,
    val service: String,
    val resource: String,
    val kind: String,
    val startNanos: Long,
    val durationNanos: Long,
    val error: Int,
    val statusCode: Int,
    val statusMessage: String,
    val meta: Map<String, String>,
    val resourceAttributes: Map<String, String>,
    val host: String,
    val env: String,
    val version: String,
    val scopeName: String,
    val scopeVersion: String,
    val events: String,
    val links: String,
)

@Serializable
data class QueuedOtlpTraceBatch(
    val organizationId: Long,
    val spans: List<OtlpSpanInsert>
)

class OtlpTraceService(
    private val usageTracking: UsageTrackingService = UsageTrackingService(),
) {
    private val clickhouseDb = ClickHouseClient.getDatabase()

    fun parseOtlpTracesJson(payload: String): List<OtlpSpanInsert>? {
        val parsed =
            try {
                json.parseToJsonElement(payload).jsonObject
            } catch (e: Exception) {
                logger.warn(e) { "Invalid OTLP traces JSON payload" }
                return null
            }

        val resourceSpans = parsed["resourceSpans"]?.jsonArray ?: return null
        val spans = mutableListOf<OtlpSpanInsert>()

        resourceSpans.forEach { rsElement ->
            val rs = rsElement.jsonObject
            val resourceCtx = OtlpParsingUtils.extractResourceContext(
                rs["resource"]?.jsonObject
            )
            val scopeSpans = rs["scopeSpans"]?.jsonArray
                ?: rs["instrumentationLibrarySpans"]?.jsonArray
                ?: JsonArray(emptyList())

            scopeSpans.forEach { ssElement ->
                val ss = ssElement.jsonObject
                val scopeName = OtlpParsingUtils.extractScopeName(ss)
                val scopeVersion = OtlpParsingUtils.extractScopeVersion(ss)
                val spanArray = OtlpParsingUtils.safeJsonArray(ss["spans"])

                spanArray.forEach { spanElement ->
                    val span = spanElement.jsonObject
                    val traceId = span["traceId"]?.jsonPrimitive?.contentOrNull ?: ""
                    val spanId = span["spanId"]?.jsonPrimitive?.contentOrNull ?: ""
                    val parentSpanId = span["parentSpanId"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = span["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val kindInt = span["kind"]?.jsonPrimitive?.intOrNull ?: 0
                    val kind = mapSpanKind(kindInt)

                    val startNanos = OtlpParsingUtils.extractTimestampNanos(
                        span, "startTimeUnixNano"
                    ) ?: 0L
                    val endNanos = OtlpParsingUtils.extractTimestampNanos(
                        span, "endTimeUnixNano"
                    ) ?: startNanos
                    val durationNanos = maxOf(0L, endNanos - startNanos)

                    val statusObj = span["status"]?.jsonObject
                    val statusCode = statusObj?.get("code")?.jsonPrimitive?.intOrNull ?: 0
                    val statusMessage = statusObj?.get("message")
                        ?.jsonPrimitive?.contentOrNull ?: ""
                    val error = if (statusCode == OTLP_SPAN_STATUS_ERROR) 1 else 0

                    val attributes = OtlpParsingUtils.attributesToMap(span["attributes"])
                    val events = span["events"]?.toString() ?: "[]"
                    val links = span["links"]?.toString() ?: "[]"

                    spans += OtlpSpanInsert(
                        traceIdHex = traceId,
                        spanIdHex = spanId,
                        parentIdHex = parentSpanId,
                        organizationId = 0,
                        name = name,
                        service = resourceCtx.serviceName,
                        resource = name,
                        kind = kind,
                        startNanos = startNanos,
                        durationNanos = durationNanos,
                        error = error,
                        statusCode = statusCode,
                        statusMessage = statusMessage,
                        meta = attributes,
                        resourceAttributes = resourceCtx.attributes,
                        host = resourceCtx.hostName,
                        env = resourceCtx.environment,
                        version = resourceCtx.serviceVersion,
                        scopeName = scopeName,
                        scopeVersion = scopeVersion,
                        events = events,
                        links = links,
                    )
                }
            }
        }

        return spans
    }

    fun enqueueTraces(
        organizationId: Long,
        spans: List<OtlpSpanInsert>,
        queueKey: String
    ): Int {
        if (spans.isEmpty()) return 0
        val withOrg = spans.map { it.copy(organizationId = organizationId) }
        val batch = QueuedOtlpTraceBatch(
            organizationId = organizationId,
            spans = withOrg
        )
        val encoded = json.encodeToString(batch)
        RedisConfig.sync().lpush(queueKey, encoded)
        return withOrg.size
    }

    fun decodeBatch(payload: String): QueuedOtlpTraceBatch =
        json.decodeFromString(payload)

    suspend fun insertBatch(batch: QueuedOtlpTraceBatch) {
        if (batch.spans.isEmpty()) return

        val rows = batch.spans.joinToString(",\n") { s ->
            val traceIdLow = hexToULong(s.traceIdHex)
            val spanIdNum = hexToULong(s.spanIdHex)
            val parentIdNum = hexToULong(s.parentIdHex)

            """(
                $spanIdNum,
                $traceIdLow,
                $parentIdNum,
                ${s.organizationId},
                '${escapeSql(s.name)}',
                '${escapeSql(s.service)}',
                '${escapeSql(s.resource)}',
                '',
                fromUnixTimestamp64Nano(${s.startNanos}),
                ${s.durationNanos},
                ${s.error},
                ${mapToSqlMap(s.meta)},
                map(),
                '${escapeSql(s.host)}',
                '${escapeSql(s.env)}',
                '${escapeSql(s.version)}',
                '${escapeSql(s.traceIdHex)}',
                '${escapeSql(s.spanIdHex)}',
                '${escapeSql(s.parentIdHex)}',
                'otlp',
                '${escapeSql(s.kind)}',
                ${s.statusCode},
                '${escapeSql(s.statusMessage)}',
                '${escapeSql(s.events)}',
                '${escapeSql(s.links)}',
                ${mapToSqlMap(s.resourceAttributes)},
                '${escapeSql(s.scopeName)}',
                '${escapeSql(s.scopeVersion)}'
            )"""
        }

        val insert = """
            INSERT INTO `$clickhouseDb`.apm_spans (
                span_id, trace_id, parent_id, organization_id,
                name, service, resource, type,
                start, duration, error,
                meta, metrics, host, env, version,
                trace_id_hex, span_id_hex, parent_id_hex,
                source, kind, status_code, status_message,
                events, links, resource_attributes,
                scope_name, scope_version
            ) VALUES
            $rows
        """.trimIndent()

        val response = ClickHouseClient.execute(insert)
        check(response.status.isSuccess()) { "Failed to insert OTLP spans into ClickHouse" }

        val totalBytes = batch.spans.sumOf { span ->
            span.name.length + span.service.length +
                span.meta.entries.sumOf { e -> e.key.length + e.value.length }
        }
        usageTracking.recordOrgUsage(
            batch.organizationId.toInt(),
            "otlp_trace",
            totalBytes
        )
    }

    private fun mapSpanKind(kind: Int): String = when (kind) {
        1 -> "INTERNAL"
        2 -> "SERVER"
        3 -> "CLIENT"
        4 -> "PRODUCER"
        5 -> "CONSUMER"
        else -> ""
    }

    private fun hexToULong(hex: String): ULong {
        if (hex.isBlank()) return 0u
        val lower = if (hex.length > HEX_RADIX) hex.takeLast(HEX_RADIX) else hex
        return try {
            lower.toULong(HEX_RADIX)
        } catch (_: NumberFormatException) {
            0u
        }
    }

    private fun mapToSqlMap(map: Map<String, String>): String {
        if (map.isEmpty()) return "map()"
        val entries = map.entries.joinToString(", ") { (k, v) ->
            "'${escapeSql(k)}', '${escapeSql(v)}'"
        }
        return "map($entries)"
    }
}
