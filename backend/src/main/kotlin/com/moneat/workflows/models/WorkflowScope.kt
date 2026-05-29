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

package com.moneat.workflows.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

val workflowJson: Json = Json {
    ignoreUnknownKeys = true
}

fun Map<String, String>.typedWorkflowScope(): Map<String, JsonElement> =
    mapValues { (_, value) -> JsonPrimitive(value) }

fun Map<String, JsonElement>.workflowStringView(): Map<String, String> =
    mapValues { (_, value) -> value.workflowStringValue() }

fun JsonElement.workflowStringValue(): String =
    when (this) {
        is JsonNull -> ""
        is JsonPrimitive -> booleanOrNull?.toString() ?: doubleOrNull?.toString() ?: contentOrNull.orEmpty()
        else -> workflowJson.encodeToString(this)
    }

fun Map<String, JsonElement>.asWorkflowJsonObject(): JsonObject =
    JsonObject(this)

fun JsonElement.workflowObjectValue(): Map<String, JsonElement> =
    (this as? JsonObject)?.jsonObject ?: emptyMap()

fun JsonElement.workflowArrayValue(): List<JsonElement> =
    (this as? JsonArray)?.toList() ?: emptyList()

fun Map<String, JsonElement>.workflowValue(reference: String): JsonElement? =
    this[reference] ?: nestedWorkflowValue(reference)

private fun Map<String, JsonElement>.nestedWorkflowValue(reference: String): JsonElement? {
    val segments = reference.split('.')
    var current: JsonElement = this[segments.firstOrNull() ?: return null] ?: return null
    segments.drop(1).forEach { segment ->
        current = (current as? JsonObject)?.get(segment) ?: return null
    }
    return current
}
