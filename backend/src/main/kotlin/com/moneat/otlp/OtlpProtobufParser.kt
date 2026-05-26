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

import com.google.protobuf.ByteString
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.resource.v1.Resource

private const val NANOS_PER_MILLI = 1_000_000L
private val HEX_CHARS = "0123456789abcdef".toCharArray()
private const val BYTE_MASK = 0xFF
private const val HEX_SHIFT = 4
private const val NIBBLE_MASK = 0x0F

/**
 * Shared utilities for converting OTLP protobuf objects to Moneat internal models.
 * Mirrors [OtlpParsingUtils] which operates on JSON; this operates on generated proto classes.
 */
object OtlpProtobufParser {

    fun attributesToMap(attrs: List<KeyValue>): Map<String, String> =
        attrs.mapNotNull { kv ->
            val value = extractAnyValue(kv.value) ?: return@mapNotNull null
            kv.key to value
        }.toMap()

    fun extractAnyValue(value: AnyValue): String? =
        when (value.valueCase) {
            AnyValue.ValueCase.STRING_VALUE -> value.stringValue
            AnyValue.ValueCase.INT_VALUE -> value.intValue.toString()
            AnyValue.ValueCase.DOUBLE_VALUE -> value.doubleValue.toString()
            AnyValue.ValueCase.BOOL_VALUE -> value.boolValue.toString()
            AnyValue.ValueCase.BYTES_VALUE -> value.bytesValue.toStringUtf8()
            AnyValue.ValueCase.ARRAY_VALUE -> value.arrayValue.valuesList.toString()
            AnyValue.ValueCase.KVLIST_VALUE -> value.kvlistValue.valuesList.toString()
            AnyValue.ValueCase.VALUE_NOT_SET -> null
            else -> value.stringValue.ifEmpty { null }
        }

    fun extractResourceContext(resource: Resource): ResourceContext {
        val attrs = attributesToMap(resource.attributesList)
        return ResourceContext(
            attributes = attrs,
            serviceNamespace = attrs["service.namespace"] ?: "",
            serviceName = attrs["service.name"] ?: "",
            environment = attrs["deployment.environment"]
                ?: attrs["service.environment"] ?: "",
            hostName = attrs["host.name"] ?: "",
            serviceVersion = attrs["service.version"] ?: "",
        )
    }

    fun nanoToEpochMs(nanos: Long): Long? {
        if (nanos == 0L) return null
        return nanos / NANOS_PER_MILLI
    }

    fun bytesToHex(bytes: ByteString): String {
        if (bytes.isEmpty) return ""
        val hex = CharArray(bytes.size() * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and BYTE_MASK
            hex[i * 2] = HEX_CHARS[v ushr HEX_SHIFT]
            hex[i * 2 + 1] = HEX_CHARS[v and NIBBLE_MASK]
        }
        return String(hex)
    }
}
