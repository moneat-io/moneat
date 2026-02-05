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
            val lines = body.lines()
            if (lines.isEmpty()) throw IllegalArgumentException("Empty envelope")
            
            // Parse header
            val headerJson = Json.parseToJsonElement(lines[0]).jsonObject
            val eventId = headerJson["event_id"]?.jsonPrimitive?.content 
                ?: UUID.randomUUID().toString()
            
            // Parse items
            val items = mutableListOf<EnvelopeItem>()
            var i = 1
            while (i < lines.size) {
                if (lines[i].isBlank()) {
                    i++
                    continue
                }
                
                val itemHeader = Json.parseToJsonElement(lines[i]).jsonObject
                val itemType = itemHeader["type"]?.jsonPrimitive?.content ?: "unknown"
                val length = itemHeader["length"]?.jsonPrimitive?.int ?: 0
                
                i++
                if (i < lines.size) {
                    val payload = lines[i]
                    items.add(EnvelopeItem(itemType, payload))
                }
                i++
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
    val timestamp: Double? = null,
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
    val server_name: String? = null
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
    val value: String,
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
    val in_app: Boolean? = null
)

@Serializable
data class UserInfo(
    val id: String? = null,
    val email: String? = null,
    val username: String? = null,
    val ip_address: String? = null
)
