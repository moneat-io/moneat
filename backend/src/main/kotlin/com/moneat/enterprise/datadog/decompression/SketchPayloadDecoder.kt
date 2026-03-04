// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.decompression

import com.google.protobuf.CodedInputStream
import com.moneat.enterprise.datadog.models.DatadogSketch
import com.moneat.enterprise.datadog.models.DatadogSketchPayload
import com.moneat.enterprise.datadog.models.DatadogSketchPoint

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
@Suppress("MagicNumber")
object SketchPayloadDecoder {

    fun decode(proto: ByteArray): DatadogSketchPayload {
        val input = CodedInputStream.newInstance(proto)
        val sketches = mutableListOf<DatadogSketch>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                // field 1 (LEN): repeated Sketch sketches
                (1 shl 3) or 2 -> sketches += decodeSketch(input.readByteArray())
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
                (1 shl 3) or 2 -> metric = input.readString()
                // field 2 (LEN): string host
                (2 shl 3) or 2 -> host = input.readString()
                // field 3 (LEN): repeated string tags
                (3 shl 3) or 2 -> tags += input.readString()
                // field 4 (LEN): repeated Distribution
                (4 shl 3) or 2 -> distributions += decodeDistribution(input.readByteArray())
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
                (1 shl 3) or 0 -> ts = input.readInt64()
                // field 2 (VARINT): int64 cnt
                (2 shl 3) or 0 -> cnt = input.readInt64()
                // field 3 (64-bit): double min
                (3 shl 3) or 1 -> min = input.readDouble()
                // field 4 (64-bit): double max
                (4 shl 3) or 1 -> max = input.readDouble()
                // field 5 (64-bit): double avg
                (5 shl 3) or 1 -> avg = input.readDouble()
                // field 6 (64-bit): double sum
                (6 shl 3) or 1 -> sum = input.readDouble()
                // field 7 (LEN): packed repeated sint32 k (zigzag)
                (7 shl 3) or 2 -> k += decodePackedSint32(input.readByteArray())
                // field 7 (VARINT): non-packed sint32 k (single value)
                (7 shl 3) or 0 -> k += input.readSInt32()
                // field 8 (LEN): packed repeated uint32 n
                (8 shl 3) or 2 -> n += decodePackedUint32(input.readByteArray())
                // field 8 (VARINT): non-packed uint32 n (single value)
                (8 shl 3) or 0 -> n += input.readUInt32()
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
