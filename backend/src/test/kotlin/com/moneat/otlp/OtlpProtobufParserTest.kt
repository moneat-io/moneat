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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OtlpProtobufParserTest {

    // ──── attributesToMap ────

    @Nested
    inner class AttributesToMap {

        @Test
        fun `converts KeyValue list to string map`() {
            val attrs = listOf(
                kv("key1", stringAnyValue("val1")),
                kv("key2", intAnyValue(42)),
            )
            val map = OtlpProtobufParser.attributesToMap(attrs)
            assertEquals("val1", map["key1"])
            assertEquals("42", map["key2"])
        }

        @Test
        fun `returns empty map for empty list`() {
            assertEquals(emptyMap(), OtlpProtobufParser.attributesToMap(emptyList()))
        }

        @Test
        fun `skips entries with VALUE_NOT_SET`() {
            val attrs = listOf(
                kv("ok", stringAnyValue("good")),
                KeyValue.newBuilder().setKey("empty").setValue(AnyValue.getDefaultInstance()).build(),
            )
            val map = OtlpProtobufParser.attributesToMap(attrs)
            assertEquals(1, map.size)
            assertEquals("good", map["ok"])
        }
    }

    // ──── extractAnyValue ────

    @Nested
    inner class ExtractAnyValue {

        @Test
        fun `extracts string value`() {
            assertEquals("hello", OtlpProtobufParser.extractAnyValue(stringAnyValue("hello")))
        }

        @Test
        fun `extracts int value`() {
            assertEquals("99", OtlpProtobufParser.extractAnyValue(intAnyValue(99)))
        }

        @Test
        fun `extracts double value`() {
            assertEquals("3.14", OtlpProtobufParser.extractAnyValue(doubleAnyValue(3.14)))
        }

        @Test
        fun `extracts bool value`() {
            assertEquals("true", OtlpProtobufParser.extractAnyValue(boolAnyValue(true)))
        }

        @Test
        fun `returns null for VALUE_NOT_SET`() {
            assertNull(OtlpProtobufParser.extractAnyValue(AnyValue.getDefaultInstance()))
        }
    }

    // ──── extractResourceContext ────

    @Nested
    inner class ExtractResourceContext {

        @Test
        fun `extracts service, env, host, version from resource`() {
            val resource = Resource.newBuilder().addAllAttributes(
                listOf(
                    kv("service.name", stringAnyValue("my-svc")),
                    kv("deployment.environment", stringAnyValue("prod")),
                    kv("host.name", stringAnyValue("web-01")),
                    kv("service.version", stringAnyValue("1.0.0")),
                )
            ).build()

            val ctx = OtlpProtobufParser.extractResourceContext(resource)
            assertEquals("my-svc", ctx.serviceName)
            assertEquals("prod", ctx.environment)
            assertEquals("web-01", ctx.hostName)
            assertEquals("1.0.0", ctx.serviceVersion)
            assertEquals("my-svc", ctx.attributes["service.name"])
        }

        @Test
        fun `defaults to empty strings for missing fields`() {
            val ctx = OtlpProtobufParser.extractResourceContext(Resource.getDefaultInstance())
            assertEquals("", ctx.serviceName)
            assertEquals("", ctx.environment)
            assertEquals("", ctx.hostName)
            assertEquals("", ctx.serviceVersion)
        }
    }

    // ──── bytesToHex ────

    @Nested
    inner class BytesToHex {

        @Test
        fun `converts bytes to lowercase hex string`() {
            val bytes = ByteString.copyFrom(byteArrayOf(0x0A, 0xF7.toByte(), 0x65, 0x19))
            assertEquals("0af76519", OtlpProtobufParser.bytesToHex(bytes))
        }

        @Test
        fun `returns empty string for empty bytes`() {
            assertEquals("", OtlpProtobufParser.bytesToHex(ByteString.EMPTY))
        }
    }

    // ──── nanoToEpochMs ────

    @Nested
    inner class NanoToEpochMs {

        @Test
        fun `converts nanoseconds to milliseconds`() {
            assertEquals(1700000000000L, OtlpProtobufParser.nanoToEpochMs(1700000000000000000L))
        }

        @Test
        fun `returns null for zero`() {
            assertNull(OtlpProtobufParser.nanoToEpochMs(0L))
        }
    }

    // ──── Helpers ────

    private fun kv(key: String, value: AnyValue) =
        KeyValue.newBuilder().setKey(key).setValue(value).build()

    private fun stringAnyValue(s: String) =
        AnyValue.newBuilder().setStringValue(s).build()

    private fun intAnyValue(i: Long) =
        AnyValue.newBuilder().setIntValue(i).build()

    private fun doubleAnyValue(d: Double) =
        AnyValue.newBuilder().setDoubleValue(d).build()

    private fun boolAnyValue(b: Boolean) =
        AnyValue.newBuilder().setBoolValue(b).build()
}
