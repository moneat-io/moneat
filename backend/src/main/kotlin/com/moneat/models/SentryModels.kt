package com.moneat.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable
data class SentryEnvelope(
    val eventId: String,
    val items: List<EnvelopeItem>
) {
    companion object {
        fun parse(body: String): SentryEnvelope {
            if (body.isBlank()) throw IllegalArgumentException("Empty envelope")
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            var bytePos = 0

            // Parse envelope header (first line)
            val headerLineEnd = bodyBytes.indexOf('\n'.code.toByte())
            if (headerLineEnd == -1) throw IllegalArgumentException("Invalid envelope: missing header newline")
            val headerLine = bodyBytes.copyOfRange(bytePos, headerLineEnd).toString(Charsets.UTF_8)
            val headerJson = Json.parseToJsonElement(headerLine).jsonObject
            val eventId = headerJson["event_id"]?.jsonPrimitive?.content
                ?: UUID.randomUUID().toString()
            bytePos = headerLineEnd + 1

            val items = mutableListOf<EnvelopeItem>()
            while (bytePos < bodyBytes.size) {
                // Skip blank lines
                while (bytePos < bodyBytes.size && (bodyBytes[bytePos] == '\n'.code.toByte() || bodyBytes[bytePos] == '\r'.code.toByte())) {
                    bytePos++
                }
                if (bytePos >= bodyBytes.size) break

                // Parse item header line
                val itemHeaderEnd = (bytePos until bodyBytes.size).firstOrNull { bodyBytes[it] == '\n'.code.toByte() } ?: -1
                if (itemHeaderEnd == -1) break
                val itemHeaderLine = bodyBytes.copyOfRange(bytePos, itemHeaderEnd).toString(Charsets.UTF_8)
                val itemHeader = Json.parseToJsonElement(itemHeaderLine).jsonObject
                val itemType = itemHeader["type"]?.jsonPrimitive?.content ?: "unknown"
                val length = itemHeader["length"]?.jsonPrimitive?.int ?: 0
                bytePos = itemHeaderEnd + 1

                if (length > 0 && bytePos + length <= bodyBytes.size) {
                    val payloadBytes = bodyBytes.copyOfRange(bytePos, bytePos + length)
                    val payload = payloadBytes.toString(Charsets.UTF_8)
                    items.add(EnvelopeItem(itemType, payload))
                }
                bytePos += length
            }
            return SentryEnvelope(eventId, items)
        }
    }
}

@Serializable
data class EnvelopeItem(
    val type: String,
    val payload: String
)

@Serializable
data class SentryEvent(
    val event_id: String? = null,
    val timestamp: String? = null,  // ISO 8601 timestamp string
    val level: String? = null,
    val logger: String? = null,
    val platform: String? = null,
    val sdk: SdkInfo? = null,
    val exception: ExceptionInfo? = null,
    val message: String? = null,
    val environment: String? = null,
    val release: String? = null,
    val dist: String? = null,
    val tags: Map<String, String>? = null,
    val user: UserInfo? = null,
    val contexts: JsonObject? = null,
    val breadcrumbs: JsonArray? = null,
    val request: JsonObject? = null,
    val fingerprint: List<String>? = null,
    val server_name: String? = null,
    val threads: JsonObject? = null
)

@Serializable
data class SentryTransaction(
    val event_id: String? = null,
    val type: String? = null,
    val transaction: String? = null,
    val start_timestamp: Double? = null,
    val timestamp: Double? = null,
    val platform: String? = null,
    val environment: String? = null,
    val release: String? = null,
    val dist: String? = null,
    val tags: Map<String, String>? = null,
    val user: UserInfo? = null,
    val contexts: JsonObject? = null,
    val spans: List<SentrySpan>? = null,
    val sdk: SdkInfo? = null,
    val server_name: String? = null,
    val request: JsonObject? = null,
    val breadcrumbs: JsonArray? = null,
    val measurements: JsonObject? = null
)

@Serializable
data class SentrySpan(
    val span_id: String? = null,
    val parent_span_id: String? = null,
    val trace_id: String? = null,
    val op: String? = null,
    val description: String? = null,
    val start_timestamp: Double? = null,
    val timestamp: Double? = null,
    val status: String? = null,
    val tags: Map<String, String>? = null,
    val data: JsonObject? = null
)

@Serializable
data class SdkInfo(
    val name: String,
    val version: String
)

@Serializable
data class ExceptionInfo(
    val values: List<ExceptionValue>
)

@Serializable
data class ExceptionValue(
    val type: String,
    val value: String? = null,
    val stacktrace: StackTrace? = null,
    val mechanism: JsonObject? = null
)

@Serializable
data class StackTrace(
    val frames: List<StackFrame>
)

@Serializable
data class StackFrame(
    val filename: String? = null,
    val function: String? = null,
    val module: String? = null,
    val lineno: Int? = null,
    val colno: Int? = null,
    val abs_path: String? = null,
    val context_line: String? = null,
    val pre_context: List<String>? = null,
    val post_context: List<String>? = null,
    val in_app: Boolean? = null,
    val vars: JsonObject? = null
)

@Serializable
data class UserInfo(
    val id: String? = null,
    val email: String? = null,
    val username: String? = null,
    val ip_address: String? = null
)

@Serializable
data class SentryReplayEvent(
    val replay_id: String? = null,
    val segment_id: Int? = null,
    val timestamp: Double? = null,
    val replay_start_timestamp: Double? = null,
    val urls: List<String>? = null,
    val error_ids: List<String>? = null,
    val trace_ids: List<String>? = null,
    val platform: String? = null,
    val environment: String? = null,
    val release: String? = null,
    val user: UserInfo? = null,
    val contexts: JsonObject? = null,
    val sdk: SdkInfo? = null,
    val tags: Map<String, String>? = null,
    val replay_type: String? = null
)
