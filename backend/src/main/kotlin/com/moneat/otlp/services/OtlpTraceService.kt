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

import com.google.protobuf.InvalidProtocolBufferException
import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.otlp.OtlpParsingUtils
import com.moneat.otlp.OtlpProtobufParser
import com.moneat.otlp.ResourceContext
import com.moneat.otlp.calculateBillableBytes
import com.moneat.otlp.hexToULongPair
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.http.isSuccess
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.trace.v1.Span
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }
private const val OTLP_SPAN_STATUS_ERROR = 2

// OTLP SpanKind enum numeric codes (opentelemetry.proto.trace.v1.Span.SpanKind)
private const val OTLP_SPAN_KIND_INTERNAL = 1
private const val OTLP_SPAN_KIND_SERVER = 2
private const val OTLP_SPAN_KIND_CLIENT = 3
private const val OTLP_SPAN_KIND_PRODUCER = 4
private const val OTLP_SPAN_KIND_CONSUMER = 5
private const val ERROR_MESSAGE_PREVIEW_LENGTH = 500

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

    @Suppress("CyclomaticComplexMethod")
    fun parseOtlpTracesProtobuf(bytes: ByteArray): List<OtlpSpanInsert>? {
        val request =
            try {
                ExportTraceServiceRequest.parseFrom(bytes)
            } catch (e: InvalidProtocolBufferException) {
                logger.warn { "Invalid OTLP protobuf traces payload: ${e.message?.take(ERROR_MESSAGE_PREVIEW_LENGTH)}" }
                return null
            }

        val spans = mutableListOf<OtlpSpanInsert>()
        appendProtobufResourceSpans(request, spans)
        return spans
    }

    private fun appendProtobufResourceSpans(
        request: ExportTraceServiceRequest,
        spans: MutableList<OtlpSpanInsert>,
    ) {
        request.resourceSpansList.forEach { rs ->
            val resourceCtx = OtlpProtobufParser.extractResourceContext(rs.resource)
            rs.scopeSpansList.forEach { ss ->
                appendProtobufScopeSpans(ss, resourceCtx, spans)
            }
        }
    }

    private fun appendProtobufScopeSpans(
        ss: ScopeSpans,
        resourceCtx: ResourceContext,
        spans: MutableList<OtlpSpanInsert>,
    ) {
        val scopeName = ss.scope?.name ?: ""
        val scopeVersion = ss.scope?.version ?: ""
        ss.spansList.forEach { span ->
            spans += otlpSpanInsertFromProtobuf(span, resourceCtx, scopeName, scopeVersion)
        }
    }

    private fun otlpSpanInsertFromProtobuf(
        span: Span,
        resourceCtx: ResourceContext,
        scopeName: String,
        scopeVersion: String,
    ): OtlpSpanInsert {
        val traceId = OtlpProtobufParser.bytesToHex(span.traceId)
        val spanId = OtlpProtobufParser.bytesToHex(span.spanId)
        val parentSpanId = OtlpProtobufParser.bytesToHex(span.parentSpanId)
        val kind = mapSpanKind(span.kindValue)

        val startNanos = span.startTimeUnixNano
        val endNanos = span.endTimeUnixNano.takeIf { it != 0L } ?: startNanos
        val durationNanos = maxOf(0L, endNanos - startNanos)

        val statusCode = span.status?.codeValue ?: 0
        val statusMessage = span.status?.message ?: ""
        val error = if (statusCode == OTLP_SPAN_STATUS_ERROR) 1 else 0

        val attributes = OtlpProtobufParser.attributesToMap(span.attributesList)
        val events = protoEventsToJson(span.eventsList)
        val links = protoLinksToJson(span.linksList)

        return OtlpSpanInsert(
            traceIdHex = traceId,
            spanIdHex = spanId,
            parentIdHex = parentSpanId,
            organizationId = 0,
            name = span.name,
            service = resourceCtx.serviceName,
            resource = span.name,
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

    fun parseOtlpTracesJson(payload: String): List<OtlpSpanInsert>? {
        val parsed =
            try {
                json.parseToJsonElement(payload).jsonObject
            } catch (e: SerializationException) {
                logger.warn(e) { "Invalid OTLP traces JSON payload" }
                return null
            } catch (e: IllegalArgumentException) {
                logger.warn(e) { "Invalid OTLP traces JSON payload" }
                return null
            }

        val resourceSpans = parsed["resourceSpans"]?.jsonArray ?: return null
        val spans = mutableListOf<OtlpSpanInsert>()
        appendJsonResourceSpans(resourceSpans, spans)
        return spans
    }

    private fun appendJsonResourceSpans(
        resourceSpans: JsonArray,
        spans: MutableList<OtlpSpanInsert>,
    ) {
        resourceSpans.forEach { rsElement ->
            val rs = rsElement.jsonObject
            val resourceCtx = OtlpParsingUtils.extractResourceContext(
                rs["resource"]?.jsonObject
            )
            val scopeSpans = rs["scopeSpans"]?.jsonArray
                ?: rs["instrumentationLibrarySpans"]?.jsonArray
                ?: JsonArray(emptyList())
            appendJsonScopeSpans(scopeSpans, resourceCtx, spans)
        }
    }

    private fun appendJsonScopeSpans(
        scopeSpans: JsonArray,
        resourceCtx: ResourceContext,
        spans: MutableList<OtlpSpanInsert>,
    ) {
        scopeSpans.forEach { ssElement ->
            val ss = ssElement.jsonObject
            val scopeName = OtlpParsingUtils.extractScopeName(ss)
            val scopeVersion = OtlpParsingUtils.extractScopeVersion(ss)
            val spanArray = OtlpParsingUtils.safeJsonArray(ss["spans"])
            spanArray.forEach { spanElement ->
                spans += otlpSpanInsertFromJson(
                    spanElement.jsonObject,
                    resourceCtx,
                    scopeName,
                    scopeVersion
                )
            }
        }
    }

    private fun otlpSpanInsertFromJson(
        span: JsonObject,
        resourceCtx: ResourceContext,
        scopeName: String,
        scopeVersion: String,
    ): OtlpSpanInsert {
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

        return OtlpSpanInsert(
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
            val (traceIdHigh, traceIdLow) = hexToULongPair(s.traceIdHex)
            val (spanIdHigh, spanIdLow) = hexToULongPair(s.spanIdHex)
            val (parentIdHigh, parentIdLow) = hexToULongPair(s.parentIdHex)

            """(
                $spanIdLow,
                $spanIdHigh,
                $traceIdLow,
                $traceIdHigh,
                $parentIdLow,
                $parentIdHigh,
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
                span_id, span_id_high, trace_id, trace_id_high, parent_id, parent_id_high, organization_id,
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

        val totalBytes = batch.spans.calculateBillableBytes()
        usageTracking.recordOrgUsage(
            batch.organizationId.toInt(),
            "otlp_trace",
            totalBytes
        )
    }

    private fun mapSpanKind(kind: Int): String = when (kind) {
        OTLP_SPAN_KIND_INTERNAL -> "INTERNAL"
        OTLP_SPAN_KIND_SERVER -> "SERVER"
        OTLP_SPAN_KIND_CLIENT -> "CLIENT"
        OTLP_SPAN_KIND_PRODUCER -> "PRODUCER"
        OTLP_SPAN_KIND_CONSUMER -> "CONSUMER"
        else -> ""
    }

    private fun protoEventsToJson(events: List<Span.Event>): String {
        val jsonEvents =
            buildJsonArray {
                events.forEach { event ->
                    add(
                        buildJsonObject {
                            put("timeUnixNano", JsonPrimitive(event.timeUnixNano))
                            put("name", JsonPrimitive(event.name))
                            if (event.attributesList.isNotEmpty()) {
                                put("attributes", keyValuesToJsonArray(event.attributesList))
                            }
                            if (event.droppedAttributesCount > 0) {
                                put("droppedAttributesCount", JsonPrimitive(event.droppedAttributesCount))
                            }
                        }
                    )
                }
            }
        return json.encodeToString(jsonEvents)
    }

    private fun protoLinkToJsonObject(link: Span.Link): JsonObject =
        buildJsonObject {
            put("traceId", JsonPrimitive(OtlpProtobufParser.bytesToHex(link.traceId)))
            put("spanId", JsonPrimitive(OtlpProtobufParser.bytesToHex(link.spanId)))
            if (link.traceState.isNotEmpty()) {
                put("traceState", JsonPrimitive(link.traceState))
            }
            if (link.attributesList.isNotEmpty()) {
                put("attributes", keyValuesToJsonArray(link.attributesList))
            }
            if (link.droppedAttributesCount > 0) {
                put("droppedAttributesCount", JsonPrimitive(link.droppedAttributesCount))
            }
            if (link.flags != 0) {
                put("flags", JsonPrimitive(link.flags))
            }
        }

    private fun protoLinksToJson(links: List<Span.Link>): String {
        val jsonLinks = buildJsonArray { links.forEach { add(protoLinkToJsonObject(it)) } }
        return json.encodeToString(jsonLinks)
    }

    private fun keyValuesToJsonArray(values: List<KeyValue>): JsonArray =
        buildJsonArray {
            values.forEach { kv ->
                add(
                    buildJsonObject {
                        put("key", JsonPrimitive(kv.key))
                        put("value", anyValueToJsonElement(kv.value))
                    }
                )
            }
        }

    private fun anyValueToJsonElement(value: AnyValue): JsonElement =
        when (value.valueCase) {
            AnyValue.ValueCase.STRING_VALUE -> JsonPrimitive(value.stringValue)
            AnyValue.ValueCase.INT_VALUE -> JsonPrimitive(value.intValue)
            AnyValue.ValueCase.DOUBLE_VALUE -> JsonPrimitive(value.doubleValue)
            AnyValue.ValueCase.BOOL_VALUE -> JsonPrimitive(value.boolValue)
            AnyValue.ValueCase.BYTES_VALUE -> JsonPrimitive(
                OtlpProtobufParser.bytesToHex(value.bytesValue)
            )
            AnyValue.ValueCase.ARRAY_VALUE ->
                buildJsonArray {
                    value.arrayValue.valuesList.forEach { add(anyValueToJsonElement(it)) }
                }
            AnyValue.ValueCase.KVLIST_VALUE -> keyValuesToJsonArray(value.kvlistValue.valuesList)
            AnyValue.ValueCase.VALUE_NOT_SET -> JsonNull
            else -> value.stringValue.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull
        }

    private fun mapToSqlMap(map: Map<String, String>): String {
        if (map.isEmpty()) return "map()"
        val entries = map.entries.joinToString(", ") { (k, v) ->
            "'${escapeSql(k)}', '${escapeSql(v)}'"
        }
        return "map($entries)"
    }
}
