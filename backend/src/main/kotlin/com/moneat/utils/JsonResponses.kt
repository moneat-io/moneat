// Moneat - Mobile-First Error Monitoring Platform
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
