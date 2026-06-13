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

package com.moneat.datadog

import com.moneat.ingest.DecompressionService
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecompressionServiceTest {

    // ──── Auto-detect by magic bytes ────

    @Test
    fun `auto-detects gzip from magic bytes when encoding is null`() {
        val original = "auto-detect gzip payload"
        val compressed = gzipCompress(original.toByteArray())
        val result = DecompressionService.decompress(compressed, null)
        assertEquals(original, result.decodeToString())
    }

    @Test
    fun `auto-detects zstd from magic bytes when encoding is null`() {
        val original = "auto-detect zstd payload"
        val compressed = com.github.luben.zstd.Zstd.compress(original.toByteArray())
        val result = DecompressionService.decompress(compressed, null)
        assertEquals(original, result.decodeToString())
    }

    @Test
    fun `returns raw data for non-matching magic bytes and null encoding`() {
        val input = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)
        val result = DecompressionService.decompress(input, null)
        assertContentEquals(input, result)
    }

    // ──── Roundtrip compress and decompress ────

    @Test
    fun `roundtrip gzip with multi-byte unicode content`() {
        val original = "Unicode: \u00E9\u00E8\u00EA \u4E16\u754C \uD83D\uDE80"
        val compressed = gzipCompress(original.toByteArray(Charsets.UTF_8))
        val result = DecompressionService.decompress(compressed, "gzip")
        assertEquals(original, result.decodeToString())
    }

    @Test
    fun `roundtrip deflate with binary-like content`() {
        val original = ByteArray(256) { it.toByte() }
        val compressed = deflateCompress(original)
        val result = DecompressionService.decompress(compressed, "deflate")
        assertContentEquals(original, result)
    }

    @Test
    fun `roundtrip zstd with repeating data`() {
        val original = "ABCDEF".repeat(500)
        val compressed = com.github.luben.zstd.Zstd.compress(original.toByteArray())
        val result = DecompressionService.decompress(compressed, "zstd")
        assertEquals(original, result.decodeToString())
    }

    @Test
    fun `zstandard alias decompresses correctly`() {
        val original = "zstandard alias test"
        val compressed = com.github.luben.zstd.Zstd.compress(original.toByteArray())
        val result = DecompressionService.decompress(compressed, "zstandard")
        assertEquals(original, result.decodeToString())
    }

    // ──── Empty input handling ────

    @Test
    fun `empty byte array with null encoding returns empty`() {
        val result = DecompressionService.decompress(byteArrayOf(), null)
        assertContentEquals(byteArrayOf(), result)
    }

    @Test
    fun `empty byte array with identity encoding returns empty`() {
        val result = DecompressionService.decompress(byteArrayOf(), "identity")
        assertContentEquals(byteArrayOf(), result)
    }

    @Test
    fun `empty byte array with unknown encoding returns empty`() {
        val result = DecompressionService.decompress(byteArrayOf(), "snappy")
        assertContentEquals(byteArrayOf(), result)
    }

    // ──── Invalid compressed data ────

    @Test
    fun `invalid gzip data throws exception`() {
        val garbage = byteArrayOf(0x1F, 0x8B.toByte(), 0x00, 0x00, 0xFF.toByte())
        assertFailsWith<Exception> {
            DecompressionService.decompress(garbage, "gzip")
        }
    }

    @Test
    fun `invalid deflate data throws exception`() {
        val garbage = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte())
        assertFailsWith<Exception> {
            DecompressionService.decompress(garbage, "deflate")
        }
    }

    // ──── Single byte data ────

    @Test
    fun `single byte with null encoding returned unchanged`() {
        val input = byteArrayOf(0x42)
        val result = DecompressionService.decompress(input, null)
        assertContentEquals(input, result)
    }

    // ──── Gzip empty payload roundtrip ────

    @Test
    fun `gzip compress then decompress empty string`() {
        val original = ""
        val compressed = gzipCompress(original.toByteArray())
        val result = DecompressionService.decompress(compressed, "gzip")
        assertEquals(original, result.decodeToString())
    }

    @Test
    fun `deflate compress then decompress empty string`() {
        val original = ""
        val compressed = deflateCompress(original.toByteArray())
        val result = DecompressionService.decompress(compressed, "deflate")
        assertEquals(original, result.decodeToString())
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun deflateCompress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        DeflaterOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }
}
