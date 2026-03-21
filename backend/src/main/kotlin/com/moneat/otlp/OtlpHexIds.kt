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

private const val HEX_RADIX = 16
private const val HEX_CHUNK_LEN = 16

/**
 * Splits an OTLP-style hex string (up to 32 chars for trace ids) into two UInt64 halves.
 * Shorter strings map to high=0 and the full string parsed as low (when length ≤ 16),
 * or high=leading, low=last 16 hex digits when longer.
 */
fun hexToULongPair(hex: String): Pair<ULong, ULong> {
    if (hex.isBlank()) return 0uL to 0uL
    val normalized = hex.trim().lowercase()
    if (normalized.length <= HEX_CHUNK_LEN) {
        return 0uL to parseHexChunk(normalized)
    }
    val lowPart = normalized.takeLast(HEX_CHUNK_LEN)
    val highPart = normalized.dropLast(HEX_CHUNK_LEN).takeLast(HEX_CHUNK_LEN)
    return parseHexChunk(highPart) to parseHexChunk(lowPart)
}

private fun parseHexChunk(chunk: String): ULong {
    if (chunk.isEmpty()) return 0uL
    return try {
        chunk.toULong(HEX_RADIX)
    } catch (_: NumberFormatException) {
        0uL
    }
}
