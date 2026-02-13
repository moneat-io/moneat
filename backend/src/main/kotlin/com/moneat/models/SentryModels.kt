package com.moneat.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import java.util.*

@Serializable
data class SentryEnvelope(
    val eventId: String,
    val items: List<EnvelopeItem>
) {
    companion object {
        fun parse(bodyBytes: ByteArray): SentryEnvelope {
            if (bodyBytes.isEmpty()) throw IllegalArgumentException("Empty envelope")
            var bytePos = 0

            // Parse envelope header (first line)
            val headerLineEnd = bodyBytes.indexOf('\n'.code.toByte())
            if (headerLineEnd == -1) throw IllegalArgumentException("Invalid envelope: missing header newline")
            val headerLine = bodyBytes.copyOfRange(bytePos, headerLineEnd).toString(Charsets.UTF_8)
            val headerJson = Json.parseToJsonElement(headerLine).jsonObject
            val eventId = headerJson["event_id"]?.jsonPrimitive?.content
                ?: UUID.randomUUID().toString()
            bytePos = headerLineEnd + 1

            // Known Sentry item types that indicate an item header line
            val knownItemTypes = setOf(
                "event", "transaction", "session", "attachment",
                "replay_event", "replay_recording", "replay_video",
                "feedback", "check_in", "statsd", "metric_buckets",
                "profile", "client_report", "user_report"
            )

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
                val itemHeader = try {
                    Json.parseToJsonElement(itemHeaderLine).jsonObject
                } catch (e: Exception) {
                    bytePos = itemHeaderEnd + 1
                    continue
                }
                val itemType = itemHeader["type"]?.jsonPrimitive?.content ?: "unknown"
                val explicitLength = try {
                    itemHeader["length"]?.jsonPrimitive?.long?.toInt()
                } catch (e: Exception) {
                    null
                }
                bytePos = itemHeaderEnd + 1

                if (explicitLength != null && explicitLength > 0 && bytePos + explicitLength <= bodyBytes.size) {
                    // Explicit length provided - read exact bytes
                    val payloadBytes = bodyBytes.copyOfRange(bytePos, bytePos + explicitLength)
                    if (itemType == "replay_video" || itemType == "replay_recording") {
                        val payload = java.util.Base64.getEncoder().encodeToString(payloadBytes)
                        items.add(EnvelopeItem(itemType, payload, payloadBytes, itemHeader))
                    } else {
                        val payload = payloadBytes.toString(Charsets.UTF_8)
                        items.add(EnvelopeItem(itemType, payload, null, itemHeader))
                    }
                    bytePos += explicitLength
                } else if (itemType != "unknown" && itemType in knownItemTypes) {
                    // No explicit length - scan forward for the next item header or end of envelope.
                    // This handles SDKs (like Android) that omit `length` for text-based items.
                    val payloadStart = bytePos
                    var payloadEnd = bodyBytes.size

                    var scanPos = bytePos
                    while (scanPos < bodyBytes.size) {
                        val lineEnd = (scanPos until bodyBytes.size).firstOrNull { bodyBytes[it] == '\n'.code.toByte() } ?: bodyBytes.size
                        val line = bodyBytes.copyOfRange(scanPos, lineEnd).toString(Charsets.UTF_8).trim()
                        if (line.isNotEmpty()) {
                            try {
                                val possibleHeader = Json.parseToJsonElement(line).jsonObject
                                val possibleType = possibleHeader["type"]?.jsonPrimitive?.content
                                if (possibleType != null && possibleType in knownItemTypes && possibleType != itemType) {
                                    // Found the next item header - payload ends here
                                    payloadEnd = scanPos
                                    break
                                }
                                // Also detect next item header if it has the same type but includes a length field
                                if (possibleType != null && possibleType in knownItemTypes && possibleHeader.containsKey("length")) {
                                    payloadEnd = scanPos
                                    break
                                }
                            } catch (_: Exception) {
                                // Not valid JSON - still part of current payload
                            }
                        }
                        scanPos = if (lineEnd < bodyBytes.size) lineEnd + 1 else bodyBytes.size
                    }

                    if (payloadStart < payloadEnd) {
                        // Trim trailing newlines from payload
                        var trimmedEnd = payloadEnd
                        while (trimmedEnd > payloadStart && (bodyBytes[trimmedEnd - 1] == '\n'.code.toByte() || bodyBytes[trimmedEnd - 1] == '\r'.code.toByte())) {
                            trimmedEnd--
                        }
                        val payloadBytes = bodyBytes.copyOfRange(payloadStart, trimmedEnd)
                        val payload = payloadBytes.toString(Charsets.UTF_8)
                        items.add(EnvelopeItem(itemType, payload, null, itemHeader))
                    }
                    bytePos = payloadEnd
                } else {
                    // Unknown type with no length - skip
                }
            }
            return SentryEnvelope(eventId, items)
        }
    }
}

@Serializable
data class EnvelopeItem(
    val type: String,
    val payload: String,
    @kotlinx.serialization.Transient
    val payloadBytes: ByteArray? = null,
    @kotlinx.serialization.Transient
    val headers: JsonObject? = null
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
    @Serializable(with = SentryMessageSerializer::class)
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

object SentryMessageSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SentryMessage", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            JsonNull -> null
            is JsonPrimitive -> element.contentOrNull ?: element.toString()
            is JsonObject -> {
                element["formatted"]?.jsonPrimitive?.contentOrNull
                    ?: element["message"]?.jsonPrimitive?.contentOrNull
                    ?: element["text"]?.jsonPrimitive?.contentOrNull
                    ?: element.toString()
            }
            else -> element.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        encoder.encodeString(value ?: "")
    }
}

object FlexibleTimestampSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleTimestamp", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            JsonNull -> null
            is JsonPrimitive -> {
                element.doubleOrNull ?: run {
                    // Try to parse as ISO 8601 timestamp string
                    val isoString = element.contentOrNull ?: return null
                    try {
                        val instant = java.time.Instant.parse(isoString)
                        instant.epochSecond.toDouble() + instant.nano / 1_000_000_000.0
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: Double?) {
        value?.let { encoder.encodeDouble(it) }
    }
}

@Serializable
data class SentryTransaction(
    val event_id: String? = null,
    val type: String? = null,
    val transaction: String? = null,
    @Serializable(with = FlexibleTimestampSerializer::class)
    val start_timestamp: Double? = null,
    @Serializable(with = FlexibleTimestampSerializer::class)
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
    @Serializable(with = FlexibleTimestampSerializer::class)
    val start_timestamp: Double? = null,
    @Serializable(with = FlexibleTimestampSerializer::class)
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

@Serializable
data class SentryFeedback(
    val event_id: String? = null,
    val timestamp: String? = null,
    val platform: String? = null,
    val level: String? = null,
    val environment: String? = null,
    val release: String? = null,
    val user: UserInfo? = null,
    val contexts: JsonObject? = null,
    val tags: Map<String, String>? = null,
    val sdk: SdkInfo? = null
)
