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

package com.moneat.datadog.decompression

import com.google.protobuf.CodedInputStream
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_3
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_4
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_5
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_6
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_7
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_SHIFT
import com.moneat.datadog.models.DatadogMetricSeriesV1
import com.moneat.datadog.models.DatadogMetricV1

/**
 * Decodes the Datadog agent v2/series protobuf payload (MetricPayload).
 *
 * Proto definition (agent-payload/v5/process/agent_payload.proto):
 *   MetricPayload {
 *     repeated MetricSeries series = 1;
 *   }
 *   MetricSeries {
 *     repeated Resource resources = 1;  // {type="host", name=<host>}
 *     string metric     = 2;
 *     repeated string tags = 3;
 *     repeated MetricPoint points = 4;
 *     MetricType type   = 5;            // 0=unspecified,1=count,2=rate,3=gauge
 *     string unit       = 6;
 *     string source_type_name = 7;
 *   }
 *   Resource  { string type = 1; string name = 2; }
 *   MetricPoint { double value = 1; int64 timestamp = 2; }
 */
object MetricPayloadDecoder {

    private val METRIC_TYPES = mapOf(0 to "gauge", 1 to "count", 2 to "rate", 3 to "gauge")

    /** Decodes a raw (already decompressed) protobuf MetricPayload into DatadogMetricSeriesV1. */
    fun decode(proto: ByteArray): DatadogMetricSeriesV1 {
        val input = CodedInputStream.newInstance(proto)
        val series = mutableListOf<DatadogMetricV1>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                // field 1 (LEN): repeated MetricSeries series
                (1 shl FIELD_SHIFT) or 2 -> series += decodeSeries(input.readByteArray())
                else -> input.skipField(tag)
            }
        }
        return DatadogMetricSeriesV1(series = series)
    }

    private fun decodeSeries(bytes: ByteArray): DatadogMetricV1 {
        val input = CodedInputStream.newInstance(bytes)
        var metric = ""
        val tags = mutableListOf<String>()
        val points = mutableListOf<List<Double>>()
        var typeInt = 0
        var unit = ""
        var sourceTypeName = ""
        var host = ""

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                // field 1 (LEN): repeated Resource
                (1 shl FIELD_SHIFT) or 2 -> {
                    val resource = decodeResource(input.readByteArray())
                    if (resource.first == "host") host = resource.second
                }
                // field 2 (LEN): string metric
                (2 shl FIELD_SHIFT) or 2 -> metric = input.readString()
                // field 3 (LEN): repeated string tags
                (FIELD_3 shl FIELD_SHIFT) or 2 -> tags += input.readString()
                // field 4 (LEN): repeated MetricPoint
                (FIELD_4 shl FIELD_SHIFT) or 2 -> points += decodeMetricPoint(input.readByteArray())
                // field 5 (VARINT): MetricType
                (FIELD_5 shl FIELD_SHIFT) or 0 -> typeInt = input.readEnum()
                // field 6 (LEN): string unit
                (FIELD_6 shl FIELD_SHIFT) or 2 -> unit = input.readString()
                // field 7 (LEN): string source_type_name
                (FIELD_7 shl FIELD_SHIFT) or 2 -> sourceTypeName = input.readString()
                else -> input.skipField(tag)
            }
        }

        return DatadogMetricV1(
            metric = metric,
            type = METRIC_TYPES[typeInt] ?: "gauge",
            host = host,
            tags = tags,
            points = points,
            unit = unit,
            sourceTypeName = sourceTypeName
        )
    }

    // Resource: field 1 (string) type, field 2 (string) name
    private fun decodeResource(bytes: ByteArray): Pair<String, String> {
        val input = CodedInputStream.newInstance(bytes)
        var type = ""
        var name = ""
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (1 shl FIELD_SHIFT) or 2 -> type = input.readString()
                (2 shl FIELD_SHIFT) or 2 -> name = input.readString()
                else -> input.skipField(tag)
            }
        }
        return Pair(type, name)
    }

    // MetricPoint: field 1 (double/fixed64) value, field 2 (varint) timestamp
    private fun decodeMetricPoint(bytes: ByteArray): List<Double> {
        val input = CodedInputStream.newInstance(bytes)
        var value = 0.0
        var timestamp = 0L
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (1 shl FIELD_SHIFT) or 1 -> value = input.readDouble() // wire type 1 = 64-bit
                (2 shl FIELD_SHIFT) or 0 -> timestamp = input.readInt64() // wire type 0 = varint
                else -> input.skipField(tag)
            }
        }
        // DD v1 format: [timestamp, value]
        return listOf(timestamp.toDouble(), value)
    }
}
