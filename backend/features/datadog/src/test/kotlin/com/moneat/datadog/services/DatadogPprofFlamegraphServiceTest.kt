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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
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
    fun `parseToFrames lists sample types and threads`() {
        val result = DatadogPprofFlamegraphService.parseToFrames(buildMultiTypeProfile())

        val typeKeys = result["sampleTypes"]!!.jsonArray
            .map { it.jsonObject["key"]!!.jsonPrimitive.content }
        assertTrue(typeKeys.containsAll(listOf("samples", "cpu")))

        val threadIds = result["threads"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue(threadIds.containsAll(listOf("worker-1", "worker-2")))

        assertEquals("samples", result["selectedSampleType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parseToFrames switches sample type`() {
        val result = DatadogPprofFlamegraphService.parseToFrames(
            buildMultiTypeProfile(),
            sampleType = "cpu",
        )

        assertEquals("cpu", result["selectedSampleType"]!!.jsonPrimitive.content)
        assertEquals("nanoseconds", result["unit"]!!.jsonPrimitive.content)
        val main = result["frames"]!!.jsonArray[0].jsonObject
        assertEquals(1000L, main["value"]!!.jsonPrimitive.long)
    }

    @Test
    fun `parseToFrames filters by thread`() {
        val result = DatadogPprofFlamegraphService.parseToFrames(
            buildMultiTypeProfile(),
            thread = "worker-1",
        )

        assertEquals("worker-1", result["selectedThread"]!!.jsonPrimitive.content)
        val main = result["frames"]!!.jsonArray[0].jsonObject
        assertEquals("main", main["name"]!!.jsonPrimitive.content)
        assertEquals(60L, main["value"]!!.jsonPrimitive.long)
    }

    @Test
    fun `parseToFrames returns empty when samples have no sample types`() {
        val result = DatadogPprofFlamegraphService.parseToFrames(buildProfileWithoutSampleTypes())

        assertTrue(result["frames"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `parseToFrames falls back to last value when selected index is missing`() {
        val result = DatadogPprofFlamegraphService.parseToFrames(
            buildMissingSelectedValueProfile(),
            sampleType = "cpu",
        )

        assertEquals("cpu", result["selectedSampleType"]!!.jsonPrimitive.content)
        assertEquals(17L, result["totalSamples"]!!.jsonPrimitive.long)
    }

    @Test
    fun `parseToFrames ignores samples without values`() {
        val result = DatadogPprofFlamegraphService.parseToFrames(buildSampleWithoutValuesProfile())

        assertTrue(result["frames"]!!.jsonArray.isEmpty())
        assertEquals(0L, result["totalSamples"]!!.jsonPrimitive.long)
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

    private fun buildMultiTypeProfile(): ByteArray {
        return encodeMessage { profile ->
            // sample_type = [{samples,count}, {cpu,nanoseconds}]
            listOf(1L to 2L, 7L to 8L).forEach { (typeIdx, unitIdx) ->
                profile.writeMessageField(
                    1,
                    encodeMessage { vt ->
                        vt.writeInt64(1, typeIdx)
                        vt.writeInt64(2, unitIdx)
                    },
                )
            }

            // sample #1: main -> handler -> db.query; values [60, 600]; thread worker-1
            profile.writeMessageField(
                2,
                encodeMessage { sample ->
                    sample.writeUInt64(1, 3)
                    sample.writeUInt64(1, 2)
                    sample.writeUInt64(1, 1)
                    sample.writeInt64(2, 60)
                    sample.writeInt64(2, 600)
                    sample.writeMessageField(
                        3,
                        encodeMessage { label ->
                            label.writeInt64(1, 9)
                            label.writeInt64(2, 10)
                        },
                    )
                },
            )

            // sample #2: main -> handler; values [40, 400]; thread worker-2
            profile.writeMessageField(
                2,
                encodeMessage { sample ->
                    sample.writeUInt64(1, 2)
                    sample.writeUInt64(1, 1)
                    sample.writeInt64(2, 40)
                    sample.writeInt64(2, 400)
                    sample.writeMessageField(
                        3,
                        encodeMessage { label ->
                            label.writeInt64(1, 9)
                            label.writeInt64(2, 11)
                        },
                    )
                },
            )

            // locations 1..3 -> functions 1..3 (function id == location id)
            (1L..3L).forEach { id ->
                profile.writeMessageField(
                    4,
                    encodeMessage { loc ->
                        loc.writeUInt64(1, id)
                        loc.writeMessageField(
                            4,
                            encodeMessage { line ->
                                line.writeUInt64(1, id)
                                line.writeInt64(2, id * 10)
                            },
                        )
                    },
                )
            }

            // functions: name string indices 3=main, 4=handler, 5=db.query
            listOf(1L to 3L, 2L to 4L, 3L to 5L).forEach { (id, nameIdx) ->
                profile.writeMessageField(
                    5,
                    encodeMessage { fn ->
                        fn.writeUInt64(1, id)
                        fn.writeInt64(2, nameIdx)
                        fn.writeInt64(3, nameIdx)
                        fn.writeInt64(4, 6)
                    },
                )
            }

            // string_table
            listOf(
                "", "samples", "count", "main", "handler", "db.query", "app.go",
                "cpu", "nanoseconds", "thread name", "worker-1", "worker-2",
            ).forEach { profile.writeString(6, it) }

            // default_sample_type = "samples"
            profile.writeInt64(14, 1)
        }
    }

    private fun buildProfileWithoutSampleTypes(): ByteArray {
        return encodeMessage { profile ->
            profile.writeMessageField(
                2,
                encodeMessage { sample ->
                    sample.writeUInt64(1, 1)
                    sample.writeInt64(2, 1)
                    sample.writeMessageField(
                        3,
                        encodeMessage { label ->
                            label.writeString(UNKNOWN_FIELD_NUMBER, "ignored")
                        },
                    )
                },
            )
        }
    }

    private fun buildMissingSelectedValueProfile(): ByteArray {
        return buildSingleFrameProfile { sample ->
            sample.writeInt64(2, FALLBACK_SAMPLE_VALUE)
        }
    }

    private fun buildSampleWithoutValuesProfile(): ByteArray {
        return buildSingleFrameProfile { sample ->
            sample.writeMessageField(
                3,
                encodeMessage { label ->
                    label.writeInt64(1, 9)
                    label.writeInt64(2, 10)
                },
            )
        }
    }

    private fun buildSingleFrameProfile(
        writeSampleValues: (CodedOutputStream) -> Unit,
    ): ByteArray {
        return encodeMessage { profile ->
            listOf(1L to 2L, 7L to 8L).forEach { (typeIdx, unitIdx) ->
                profile.writeMessageField(
                    1,
                    encodeMessage { vt ->
                        vt.writeInt64(1, typeIdx)
                        vt.writeInt64(2, unitIdx)
                    },
                )
            }

            profile.writeMessageField(
                2,
                encodeMessage { sample ->
                    sample.writeUInt64(1, 1)
                    writeSampleValues(sample)
                },
            )

            profile.writeMessageField(
                4,
                encodeMessage { location ->
                    location.writeUInt64(1, 1)
                    location.writeMessageField(
                        4,
                        encodeMessage { line ->
                            line.writeUInt64(1, 1)
                            line.writeInt64(2, 10)
                        },
                    )
                },
            )
            profile.writeMessageField(
                5,
                encodeMessage { fn ->
                    fn.writeUInt64(1, 1)
                    fn.writeInt64(2, 3)
                    fn.writeInt64(3, 3)
                    fn.writeInt64(4, 6)
                },
            )
            listOf(
                "", "samples", "count", "main", "handler", "db.query", "app.go",
                "cpu", "nanoseconds", "thread name", "worker-1",
            ).forEach { profile.writeString(6, it) }
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

    private companion object {
        private const val FALLBACK_SAMPLE_VALUE = 17L
        private const val UNKNOWN_FIELD_NUMBER = 99
    }
}
