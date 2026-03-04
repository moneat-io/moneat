// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.decompression

import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

private const val BUFFER_SIZE = 8192
private const val MAX_DECOMPRESSED_SIZE = 50 * 1024 * 1024 // 50 MB

object DecompressionService {

    fun decompress(data: ByteArray, encoding: String?): ByteArray {
        if (!encoding.isNullOrBlank() && encoding != "identity") {
            return when (encoding.lowercase()) {
                "gzip", "x-gzip" -> decompressGzip(data)
                "deflate" -> decompressDeflate(data)
                "zstd", "zstandard" -> decompressZstd(data)
                else -> data
            }
        }
        // Auto-detect by magic bytes when Content-Encoding header is absent
        if (data.size >= 2 && data[0] == 0x1F.toByte() && data[1] == 0x8B.toByte()) {
            return decompressGzip(data)
        }
        if (data.size >= 4 && data[0] == 0x28.toByte() && data[1] == 0xB5.toByte() &&
            data[2] == 0x2F.toByte() && data[3] == 0xFD.toByte()
        ) {
            return decompressZstd(data)
        }
        return data
    }

    private fun decompressGzip(data: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(data)).use { gzip ->
            return readWithLimit(gzip)
        }
    }

    private fun decompressDeflate(data: ByteArray): ByteArray {
        InflaterInputStream(ByteArrayInputStream(data)).use { deflate ->
            return readWithLimit(deflate)
        }
    }

    private fun decompressZstd(data: ByteArray): ByteArray {
        ZstdInputStream(ByteArrayInputStream(data)).use { zstd ->
            return readWithLimit(zstd)
        }
    }

    private fun readWithLimit(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var totalRead = 0
        var bytesRead: Int

        while (input.read(buffer).also { bytesRead = it } != -1) {
            totalRead += bytesRead
            if (totalRead > MAX_DECOMPRESSED_SIZE) {
                throw IllegalStateException(
                    "Decompressed payload exceeds maximum size of $MAX_DECOMPRESSED_SIZE bytes"
                )
            }
            output.write(buffer, 0, bytesRead)
        }
        return output.toByteArray()
    }
}
