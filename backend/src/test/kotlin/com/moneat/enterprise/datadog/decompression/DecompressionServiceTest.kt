// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.decompression

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DecompressionServiceTest {

    @Test
    fun `identity encoding returns data unchanged`() {
        val input = "hello world".toByteArray()
        val result = DecompressionService.decompress(input, null)
        assertContentEquals(input, result)
    }

    @Test
    fun `empty encoding returns data unchanged`() {
        val input = "hello world".toByteArray()
        val result = DecompressionService.decompress(input, "")
        assertContentEquals(input, result)
    }

    @Test
    fun `identity string returns data unchanged`() {
        val input = "test data".toByteArray()
        val result = DecompressionService.decompress(input, "identity")
        assertContentEquals(input, result)
    }

    @Test
    fun `gzip decompression works`() {
        val original = "Hello from gzip compressed data!"
        val compressed = gzipCompress(original.toByteArray())
        val result = DecompressionService.decompress(compressed, "gzip")
        assertEquals(original, result.decodeToString())
    }

    @Test
    fun `x-gzip decompression works`() {
        val original = "Hello from x-gzip!"
        val compressed = gzipCompress(original.toByteArray())
        val result = DecompressionService.decompress(compressed, "x-gzip")
        assertEquals(original, result.decodeToString())
    }

    @Test
    fun `deflate decompression works`() {
        val original = "Hello from deflate compressed data!"
        val compressed = deflateCompress(original.toByteArray())
        val result = DecompressionService.decompress(compressed, "deflate")
        assertEquals(original, result.decodeToString())
    }

    @Test
    fun `unknown encoding returns data unchanged`() {
        val input = "some data".toByteArray()
        val result = DecompressionService.decompress(input, "br")
        assertContentEquals(input, result)
    }

    @Test
    fun `zstd decompression works`() {
        val original = "Hello from zstd compressed data!"
        val compressed = com.github.luben.zstd.Zstd.compress(original.toByteArray())
        val result = DecompressionService.decompress(compressed, "zstd")
        assertEquals(original, result.decodeToString())
    }

    @Test
    fun `case insensitive encoding matching`() {
        val original = "case test"
        val compressed = gzipCompress(original.toByteArray())
        val result = DecompressionService.decompress(compressed, "GZIP")
        assertEquals(original, result.decodeToString())
    }

    @Test
    fun `large payload decompresses correctly`() {
        val original = "A".repeat(100_000)
        val compressed = gzipCompress(original.toByteArray())
        val result = DecompressionService.decompress(compressed, "gzip")
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
