// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.decompression

import com.google.protobuf.CodedInputStream
import com.moneat.enterprise.datadog.models.DatadogMetricSeriesV1
import com.moneat.enterprise.datadog.models.DatadogMetricV1

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
                (1 shl 3) or 2 -> series += decodeSeries(input.readByteArray())
                else -> input.skipField(tag)
            }
        }
        return DatadogMetricSeriesV1(series = series)
    }

    @Suppress("MagicNumber")
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
                (1 shl 3) or 2 -> {
                    val resource = decodeResource(input.readByteArray())
                    if (resource.first == "host") host = resource.second
                }
                // field 2 (LEN): string metric
                (2 shl 3) or 2 -> metric = input.readString()
                // field 3 (LEN): repeated string tags
                (3 shl 3) or 2 -> tags += input.readString()
                // field 4 (LEN): repeated MetricPoint
                (4 shl 3) or 2 -> points += decodeMetricPoint(input.readByteArray())
                // field 5 (VARINT): MetricType
                (5 shl 3) or 0 -> typeInt = input.readEnum()
                // field 6 (LEN): string unit
                (6 shl 3) or 2 -> unit = input.readString()
                // field 7 (LEN): string source_type_name
                (7 shl 3) or 2 -> sourceTypeName = input.readString()
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
        var type = ""; var name = ""
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (1 shl 3) or 2 -> type = input.readString()
                (2 shl 3) or 2 -> name = input.readString()
                else -> input.skipField(tag)
            }
        }
        return Pair(type, name)
    }

    // MetricPoint: field 1 (double/fixed64) value, field 2 (varint) timestamp
    @Suppress("MagicNumber")
    private fun decodeMetricPoint(bytes: ByteArray): List<Double> {
        val input = CodedInputStream.newInstance(bytes)
        var value = 0.0; var timestamp = 0L
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (1 shl 3) or 1 -> value = input.readDouble()   // wire type 1 = 64-bit
                (2 shl 3) or 0 -> timestamp = input.readInt64() // wire type 0 = varint
                else -> input.skipField(tag)
            }
        }
        // DD v1 format: [timestamp, value]
        return listOf(timestamp.toDouble(), value)
    }
}
