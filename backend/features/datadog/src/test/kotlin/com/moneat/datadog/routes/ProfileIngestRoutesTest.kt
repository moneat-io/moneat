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

package com.moneat.datadog.routes

import com.google.protobuf.CodedOutputStream
import com.moneat.datadog.buildJfrLikePayload
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProfileIngestRoutesTest {

    @Test
    fun `selectPprofPart prefers parseable pprof over non-pprof first file`() {
        val jfr = ProfileUploadFilePart(
            partName = "chunk_data",
            fileName = "recording.jfr",
            data = "jfr-bytes".toByteArray(),
        )
        val pprof = ProfileUploadFilePart(
            partName = "chunk_data",
            fileName = "auto.pprof",
            data = buildMinimalProfile(),
        )

        val selected = selectPprofPart(listOf(jfr, pprof))

        assertNotNull(selected)
        assertEquals("auto.pprof", selected.fileName)
    }

    @Test
    fun `selectPprofPart falls back to parseable content when names are generic`() {
        val invalid = ProfileUploadFilePart(
            partName = "file_a",
            fileName = "payload.bin",
            data = "not-pprof".toByteArray(),
        )
        val pprof = ProfileUploadFilePart(
            partName = "file_b",
            fileName = "payload.dat",
            data = buildMinimalProfile(),
        )

        val selected = selectPprofPart(listOf(invalid, pprof))

        assertNotNull(selected)
        assertEquals("payload.dat", selected.fileName)
    }

    @Test
    fun `selectPprofPart returns null when no parseable pprof exists`() {
        val selected = selectPprofPart(
            listOf(
                ProfileUploadFilePart(
                    partName = "f1",
                    fileName = "recording.jfr",
                    data = "jfr".toByteArray(),
                ),
                ProfileUploadFilePart(
                    partName = "f2",
                    fileName = "other.bin",
                    data = "invalid".toByteArray(),
                ),
            )
        )

        assertNull(selected)
    }

    @Test
    fun `selectProfilePart prefers parseable pprof when available`() {
        val selected = selectProfilePart(
            listOf(
                ProfileUploadFilePart(
                    partName = "f1",
                    fileName = "recording.jfr",
                    data = "jfr".toByteArray(),
                ),
                ProfileUploadFilePart(
                    partName = "f2",
                    fileName = "cpu.pprof",
                    data = buildMinimalProfile(),
                ),
            )
        )

        assertNotNull(selected)
        assertEquals("cpu.pprof", selected.fileName)
    }

    @Test
    fun `selectProfilePart falls back to jfr when no parseable pprof exists`() {
        val selected = selectProfilePart(
            listOf(
                ProfileUploadFilePart(
                    partName = "f1",
                    fileName = "recording.jfr",
                    data = "jfr".toByteArray(),
                ),
                ProfileUploadFilePart(
                    partName = "f2",
                    fileName = "other.bin",
                    data = "invalid".toByteArray(),
                ),
            )
        )

        assertNotNull(selected)
        assertEquals("recording.jfr", selected.fileName)
    }

    @Test
    fun `selectProfilePart returns null for single opaque payload`() {
        val selected = selectProfilePart(
            listOf(
                ProfileUploadFilePart(
                    partName = "chunk_data",
                    fileName = "payload.bin",
                    data = "opaque-profile".toByteArray(),
                ),
            )
        )

        assertNull(selected)
    }

    @Test
    fun `selectProfilePart detects jfr by payload when filename is pprof`() {
        val selected = selectProfilePart(
            listOf(
                ProfileUploadFilePart(
                    partName = "chunk_data",
                    fileName = "auto.pprof",
                    data = buildJfrLikePayload(),
                ),
            )
        )

        assertNotNull(selected)
        assertEquals("auto.pprof", selected.fileName)
        assertEquals("jfr", inferProfileType(selected))
    }

    @Test
    fun `selectProfilePart returns null for multiple unknown non-pprof files`() {
        val selected = selectProfilePart(
            listOf(
                ProfileUploadFilePart(
                    partName = "f1",
                    fileName = "payload-a.bin",
                    data = "opaque-a".toByteArray(),
                ),
                ProfileUploadFilePart(
                    partName = "f2",
                    fileName = "payload-b.bin",
                    data = "opaque-b".toByteArray(),
                ),
            )
        )

        assertNull(selected)
    }

    @Test
    fun `inferProfileTypeFromFilename derives expected profile type`() {
        assertEquals("heap", inferProfileTypeFromFilename("service.heap.pprof"))
        assertEquals("mutex", inferProfileTypeFromFilename("mutex.pb.gz"))
        assertEquals("jfr", inferProfileTypeFromFilename("recording.jfr"))
        assertEquals("cpu", inferProfileTypeFromFilename("auto.pprof"))
    }

    private fun buildMinimalProfile(): ByteArray {
        return encodeMessage { profile ->
            profile.writeMessageField(
                1,
                encodeMessage { valueType ->
                    valueType.writeInt64(1, 1)
                    valueType.writeInt64(2, 2)
                }
            )

            profile.writeMessageField(
                2,
                encodeMessage { sample ->
                    sample.writeUInt64(1, 1)
                    sample.writeInt64(2, 1)
                }
            )

            profile.writeMessageField(
                4,
                encodeMessage { location ->
                    location.writeUInt64(1, 1)
                    location.writeMessageField(
                        4,
                        encodeMessage { line ->
                            line.writeUInt64(1, 1)
                            line.writeInt64(2, 1)
                        }
                    )
                }
            )

            profile.writeMessageField(
                5,
                encodeMessage { function ->
                    function.writeUInt64(1, 1)
                    function.writeInt64(2, 3)
                    function.writeInt64(3, 3)
                    function.writeInt64(4, 4)
                }
            )

            profile.writeString(6, "")
            profile.writeString(6, "samples")
            profile.writeString(6, "count")
            profile.writeString(6, "main")
            profile.writeString(6, "main.go")
            profile.writeInt64(14, 1)
        }
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
