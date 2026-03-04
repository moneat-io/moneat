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

package com.moneat.datadog.services

import com.google.protobuf.CodedOutputStream
import com.moneat.datadog.buildJfrLikePayload
import com.moneat.datadog.zstd
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatadogPprofFlamegraphServiceTest {

    @Test
    fun `parseToFrames builds flamegraph tree from protobuf pprof`() {
        val profile = buildMinimalProfile()

        val result = DatadogPprofFlamegraphService.parseToFrames(profile)
        val roots = result["frames"]!!.jsonArray

        assertEquals(1, roots.size)

        val main = roots[0].jsonObject
        assertEquals("main", main["name"]!!.jsonPrimitive.content)
        assertEquals(100L, main["value"]!!.jsonPrimitive.long)

        val mainChildren = main["children"]!!.jsonArray
        assertEquals(1, mainChildren.size)
        val handler = mainChildren[0].jsonObject
        assertEquals("handler", handler["name"]!!.jsonPrimitive.content)
        assertEquals(100L, handler["value"]!!.jsonPrimitive.long)

        val handlerChildren = handler["children"]!!.jsonArray
        assertEquals(1, handlerChildren.size)
        val db = handlerChildren[0].jsonObject
        assertEquals("db.query", db["name"]!!.jsonPrimitive.content)
        assertEquals(60L, db["value"]!!.jsonPrimitive.long)
    }

    @Test
    fun `parseToFrames parses gzipped pprof`() {
        val gzipped = gzip(buildMinimalProfile())

        val result = DatadogPprofFlamegraphService.parseToFrames(gzipped)
        val roots = result["frames"]!!.jsonArray

        assertTrue(roots.isNotEmpty())
        assertEquals("main", roots[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parseToFrames returns empty frames for invalid payload`() {
        val result = DatadogPprofFlamegraphService.parseToFrames(
            "not-a-pprof".toByteArray()
        )
        assertTrue(result["frames"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `parseToFrames returns empty frames for jfr payload`() {
        val result = DatadogPprofFlamegraphService.parseToFrames(
            buildJfrLikePayload()
        )
        assertTrue(result["frames"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `isSupportedPprof returns true for raw pprof`() {
        val profile = buildMinimalProfile()
        assertTrue(DatadogPprofFlamegraphService.isSupportedPprof(profile))
    }

    @Test
    fun `isSupportedPprof returns true for gzipped pprof`() {
        val gzipped = gzip(buildMinimalProfile())
        assertTrue(DatadogPprofFlamegraphService.isSupportedPprof(gzipped))
    }

    @Test
    fun `isSupportedPprof returns true for zstd pprof`() {
        val zstd = zstd(buildMinimalProfile())
        assertTrue(DatadogPprofFlamegraphService.isSupportedPprof(zstd))
    }

    @Test
    fun `isSupportedPprof returns false for jfr payload`() {
        assertFalse(DatadogPprofFlamegraphService.isSupportedPprof(buildJfrLikePayload()))
    }

    @Test
    fun `isSupportedPprof returns false for invalid payload`() {
        assertFalse(
            DatadogPprofFlamegraphService.isSupportedPprof(
                "not-a-pprof".toByteArray()
            ),
        )
    }

    @Test
    fun `isLikelyJfrPayload detects raw and zstd jfr`() {
        val jfr = buildJfrLikePayload()
        assertTrue(DatadogPprofFlamegraphService.isLikelyJfrPayload(jfr))
        assertTrue(DatadogPprofFlamegraphService.isLikelyJfrPayload(zstd(jfr)))
        assertFalse(DatadogPprofFlamegraphService.isLikelyJfrPayload(buildMinimalProfile()))
    }

    private fun buildMinimalProfile(): ByteArray {
        return encodeMessage { profile ->
            // sample_type = [{type: "samples", unit: "count"}]
            profile.writeMessageField(
                1,
                encodeMessage { vt ->
                    vt.writeInt64(1, 1)
                    vt.writeInt64(2, 2)
                }
            )

            // sample #1: main -> handler -> db.query (60)
            profile.writeMessageField(
                2,
                encodeMessage { sample ->
                    sample.writeUInt64(1, 3)
                    sample.writeUInt64(1, 2)
                    sample.writeUInt64(1, 1)
                    sample.writeInt64(2, 60)
                }
            )

            // sample #2: main -> handler (40)
            profile.writeMessageField(
                2,
                encodeMessage { sample ->
                    sample.writeUInt64(1, 2)
                    sample.writeUInt64(1, 1)
                    sample.writeInt64(2, 40)
                }
            )

            // location id=1 -> function id=1 (main)
            profile.writeMessageField(
                4,
                encodeMessage { location ->
                    location.writeUInt64(1, 1)
                    location.writeMessageField(
                        4,
                        encodeMessage { line ->
                            line.writeUInt64(1, 1)
                            line.writeInt64(2, 10)
                        }
                    )
                }
            )

            // location id=2 -> function id=2 (handler)
            profile.writeMessageField(
                4,
                encodeMessage { location ->
                    location.writeUInt64(1, 2)
                    location.writeMessageField(
                        4,
                        encodeMessage { line ->
                            line.writeUInt64(1, 2)
                            line.writeInt64(2, 20)
                        }
                    )
                }
            )

            // location id=3 -> function id=3 (db.query)
            profile.writeMessageField(
                4,
                encodeMessage { location ->
                    location.writeUInt64(1, 3)
                    location.writeMessageField(
                        4,
                        encodeMessage { line ->
                            line.writeUInt64(1, 3)
                            line.writeInt64(2, 30)
                        }
                    )
                }
            )

            // functions
            profile.writeMessageField(
                5,
                encodeMessage { fn ->
                    fn.writeUInt64(1, 1)
                    fn.writeInt64(2, 3)
                    fn.writeInt64(3, 3)
                    fn.writeInt64(4, 6)
                }
            )
            profile.writeMessageField(
                5,
                encodeMessage { fn ->
                    fn.writeUInt64(1, 2)
                    fn.writeInt64(2, 4)
                    fn.writeInt64(3, 4)
                    fn.writeInt64(4, 6)
                }
            )
            profile.writeMessageField(
                5,
                encodeMessage { fn ->
                    fn.writeUInt64(1, 3)
                    fn.writeInt64(2, 5)
                    fn.writeInt64(3, 5)
                    fn.writeInt64(4, 6)
                }
            )

            // string_table
            profile.writeString(6, "")
            profile.writeString(6, "samples")
            profile.writeString(6, "count")
            profile.writeString(6, "main")
            profile.writeString(6, "handler")
            profile.writeString(6, "db.query")
            profile.writeString(6, "app.go")

            // default_sample_type = "samples"
            profile.writeInt64(14, 1)
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            gzip.write(bytes)
        }
        return out.toByteArray()
    }

    private fun encodeMessage(write: (CodedOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val coded = CodedOutputStream.newInstance(out)
        write(coded)
        coded.flush()
        return out.toByteArray()
    }

    private fun CodedOutputStream.writeMessageField(
        fieldNumber: Int,
        bytes: ByteArray,
    ) {
        writeTag(fieldNumber, 2)
        writeUInt32NoTag(bytes.size)
        writeRawBytes(bytes)
    }
}
