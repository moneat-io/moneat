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

package com.moneat.otlp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val NANOS_PER_MILLI = 1_000_000L

data class ResourceContext(
    val attributes: Map<String, String>,
    val serviceName: String,
    val environment: String,
    val hostName: String,
    val serviceVersion: String
)

/**
 * Shared OTLP JSON parsing utilities used by log, trace, and metric ingestion.
 * Handles the common OTLP AnyValue/KeyValue attribute structures.
 */
object OtlpParsingUtils {

    fun attributesToMap(attributes: JsonElement?): Map<String, String> {
        val array = attributes as? JsonArray ?: return emptyMap()
        return array
            .mapNotNull { attributeElement ->
                val attribute = attributeElement.jsonObject
                val key = attribute["key"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                val value = extractAnyValue(attribute["value"])
                    ?: return@mapNotNull null
                key to value
            }.toMap()
    }

    fun extractAnyValue(anyValue: JsonElement?): String? {
        val obj = anyValue as? JsonObject
            ?: return anyValue?.jsonPrimitive?.contentOrNull
        return when {
            obj.containsKey("stringValue") -> obj["stringValue"]?.jsonPrimitive?.contentOrNull
            obj.containsKey("intValue") -> obj["intValue"]?.jsonPrimitive?.contentOrNull
            obj.containsKey("doubleValue") -> obj["doubleValue"]?.jsonPrimitive?.contentOrNull
            obj.containsKey("boolValue") -> obj["boolValue"]?.jsonPrimitive?.contentOrNull
            obj.containsKey("bytesValue") -> obj["bytesValue"]?.jsonPrimitive?.contentOrNull
            obj.containsKey("arrayValue") -> obj["arrayValue"]?.toString()
            obj.containsKey("kvlistValue") -> obj["kvlistValue"]?.toString()
            else -> null
        }
    }

    fun extractResourceContext(resource: JsonObject?): ResourceContext {
        val attrs = attributesToMap(resource?.get("attributes"))
        return ResourceContext(
            attributes = attrs,
            serviceName = attrs["service.name"] ?: "",
            environment = attrs["deployment.environment"]
                ?: attrs["service.environment"] ?: "",
            hostName = attrs["host.name"] ?: "",
            serviceVersion = attrs["service.version"] ?: ""
        )
    }

    fun nanoToEpochMs(nanoString: String?): Long? {
        val ns = nanoString?.toLongOrNull() ?: return null
        return ns / NANOS_PER_MILLI
    }

    fun nanoToEpochMs(nanoLong: Long?): Long? {
        return nanoLong?.div(NANOS_PER_MILLI)
    }

    fun extractTimestampNanos(record: JsonObject, vararg fields: String): Long? {
        for (field in fields) {
            val value = record[field]?.jsonPrimitive?.longOrNull
            if (value != null) return value
        }
        return null
    }

    fun extractScopeName(scopeElement: JsonObject): String {
        return scopeElement["scope"]?.jsonObject?.get("name")
            ?.jsonPrimitive?.contentOrNull ?: ""
    }

    fun extractScopeVersion(scopeElement: JsonObject): String {
        return scopeElement["scope"]?.jsonObject?.get("version")
            ?.jsonPrimitive?.contentOrNull ?: ""
    }

    fun safeJsonArray(element: JsonElement?): JsonArray {
        return element?.jsonArray ?: JsonArray(emptyList())
    }
}
