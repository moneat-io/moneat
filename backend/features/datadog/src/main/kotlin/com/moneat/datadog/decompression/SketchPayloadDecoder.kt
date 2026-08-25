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

import com.moneat.datadog.decompression.ProtoWireConstants as Wire

import com.google.protobuf.CodedInputStream
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_3
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_4
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_5
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_6
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_7
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_8
import com.moneat.datadog.models.DatadogSketch
import com.moneat.datadog.models.DatadogSketchPayload
import com.moneat.datadog.models.DatadogSketchPoint

/**
 * Decodes the Datadog agent protobuf SketchPayload.
 *
 * Proto definition (agent-payload):
 *   SketchPayload {
 *     repeated Sketch sketches = 1;
 *   }
 *   Sketch {
 *     string metric = 1;
 *     string host   = 2;
 *     repeated string tags          = 3;
 *     repeated Distribution distributions = 4;
 *   }
 *   Distribution {
 *     int64  ts  = 1;
 *     int64  cnt = 2;
 *     double min = 3;
 *     double max = 4;
 *     double avg = 5;
 *     double sum = 6;
 *     repeated sint32 k = 7;  // DDSketch bucket keys (zigzag-encoded, packed)
 *     repeated uint32 n = 8;  // DDSketch bucket counts (packed)
 *   }
 */
object SketchPayloadDecoder {

    fun decode(proto: ByteArray): DatadogSketchPayload {
        val input = CodedInputStream.newInstance(proto)
        val sketches = mutableListOf<DatadogSketch>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                // field 1 (LEN): repeated Sketch sketches
                Wire.tag(1, Wire.WIRE_LENGTH_DELIMITED) -> sketches += decodeSketch(input.readByteArray())
                else -> input.skipField(tag)
            }
        }
        return DatadogSketchPayload(sketches = sketches)
    }

    private fun decodeSketch(bytes: ByteArray): DatadogSketch {
        val input = CodedInputStream.newInstance(bytes)
        var metric = ""
        var host = ""
        val tags = mutableListOf<String>()
        val distributions = mutableListOf<DatadogSketchPoint>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                // field 1 (LEN): string metric
                Wire.tag(1, Wire.WIRE_LENGTH_DELIMITED) -> metric = input.readString()
                // field 2 (LEN): string host
                Wire.tag(2, Wire.WIRE_LENGTH_DELIMITED) -> host = input.readString()
                // field 3 (LEN): repeated string tags
                Wire.tag(FIELD_3, Wire.WIRE_LENGTH_DELIMITED) -> tags += input.readString()
                // field 4 (LEN): repeated Distribution
                Wire.tag(FIELD_4, Wire.WIRE_LENGTH_DELIMITED) ->
                    distributions += decodeDistribution(input.readByteArray())
                else -> input.skipField(tag)
            }
        }
        return DatadogSketch(metric = metric, host = host, tags = tags, distributions = distributions)
    }

    private fun decodeDistribution(bytes: ByteArray): DatadogSketchPoint {
        val input = CodedInputStream.newInstance(bytes)
        var ts = 0L
        var cnt = 0L
        var min = 0.0
        var max = 0.0
        var avg = 0.0
        var sum = 0.0
        val k = mutableListOf<Int>()
        val n = mutableListOf<Int>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                // field 1 (VARINT): int64 ts
                Wire.tag(1, Wire.WIRE_VARINT) -> ts = input.readInt64()
                // field 2 (VARINT): int64 cnt
                Wire.tag(2, Wire.WIRE_VARINT) -> cnt = input.readInt64()
                // field 3 (64-bit): double min
                Wire.tag(FIELD_3, Wire.WIRE_FIXED64) -> min = input.readDouble()
                // field 4 (64-bit): double max
                Wire.tag(FIELD_4, Wire.WIRE_FIXED64) -> max = input.readDouble()
                // field 5 (64-bit): double avg
                Wire.tag(FIELD_5, Wire.WIRE_FIXED64) -> avg = input.readDouble()
                // field 6 (64-bit): double sum
                Wire.tag(FIELD_6, Wire.WIRE_FIXED64) -> sum = input.readDouble()
                // field 7 (LEN): packed repeated sint32 k (zigzag)
                Wire.tag(FIELD_7, Wire.WIRE_LENGTH_DELIMITED) -> k += decodePackedSint32(input.readByteArray())
                // field 7 (VARINT): non-packed sint32 k (single value)
                Wire.tag(FIELD_7, Wire.WIRE_VARINT) -> k += input.readSInt32()
                // field 8 (LEN): packed repeated uint32 n
                Wire.tag(FIELD_8, Wire.WIRE_LENGTH_DELIMITED) -> n += decodePackedUint32(input.readByteArray())
                // field 8 (VARINT): non-packed uint32 n (single value)
                Wire.tag(FIELD_8, Wire.WIRE_VARINT) -> n += input.readUInt32()
                else -> input.skipField(tag)
            }
        }
        return DatadogSketchPoint(ts = ts, cnt = cnt, min = min, max = max, avg = avg, sum = sum, k = k, n = n)
    }

    private fun decodePackedSint32(bytes: ByteArray): List<Int> {
        val input = CodedInputStream.newInstance(bytes)
        val result = mutableListOf<Int>()
        while (!input.isAtEnd) result += input.readSInt32()
        return result
    }

    private fun decodePackedUint32(bytes: ByteArray): List<Int> {
        val input = CodedInputStream.newInstance(bytes)
        val result = mutableListOf<Int>()
        while (!input.isAtEnd) result += input.readUInt32()
        return result
    }
}
