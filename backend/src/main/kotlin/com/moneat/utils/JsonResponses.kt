package com.moneat.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Serializable response wrappers for common JSON responses.
 * Use these instead of mapOf() to avoid serialization issues with LinkedHashMap.
 */

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class ErrorResponse(val error: String?)

@Serializable
data class DetailedErrorResponse(
    val error: String,
    val message: String
)

@Serializable
data class BooleanResponse(val available: Boolean)

@Serializable
data class DemoLoginResponse(
    val token: String,
    val demoEpochMs: Long
)

/**
 * Create a JsonObject instead of Map for dynamic responses.
 * This is serializable by default and avoids LinkedHashMap issues.
 */
fun messageJson(message: String): JsonObject = buildJsonObject {
    put("message", message)
}

fun errorJson(error: String): JsonObject = buildJsonObject {
    put("error", error)
}

fun booleanJson(key: String, value: Boolean): JsonObject = buildJsonObject {
    put(key, value)
}
