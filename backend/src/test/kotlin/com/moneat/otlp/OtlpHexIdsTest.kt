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

import kotlin.test.Test
import kotlin.test.assertEquals

class OtlpHexIdsTest {

    @Test
    fun `hexToULongPair maps 16 char id to high zero and full low`() {
        val hex = "b7ad6b7169203331"
        val (high, low) = hexToULongPair(hex)
        assertEquals(0uL, high)
        assertEquals(0xb7ad6b7169203331uL, low)
    }

    @Test
    fun `hexToULongPair splits 32 char trace id into two halves`() {
        val hex = "0af7651916cd43dd8448eb211c80319c"
        val (high, low) = hexToULongPair(hex)
        assertEquals(0x0af7651916cd43dduL, high)
        assertEquals(0x8448eb211c80319cuL, low)
    }

    @Test
    fun `hexToULongPair short input uses low only`() {
        val (high, low) = hexToULongPair("abc")
        assertEquals(0uL, high)
        assertEquals(0xabcuL, low)
    }

    @Test
    fun `hexToULongPair blank input returns zeros`() {
        val (high, low) = hexToULongPair("   ")
        assertEquals(0uL, high)
        assertEquals(0uL, low)
    }

    @Test
    fun `hexToULongPair invalid hex maps to zero chunks`() {
        val (high, low) = hexToULongPair("ghij")
        assertEquals(0uL, high)
        assertEquals(0uL, low)
    }

    @Test
    fun `hexToULongPair longer than 32 chars uses last 32 hex digits for high and low`() {
        // 8 ignored + 16 high + 16 low (see hexToULongPair: dropLast(16).takeLast(16) / takeLast(16))
        val hex = "11111111" + "bbbbbbbbbbbbbbbb" + "cccccccccccccccc"
        val (high, low) = hexToULongPair(hex)
        assertEquals(0xbbbbbbbbbbbbbbbbuL, high)
        assertEquals(0xccccccccccccccccuL, low)
    }
}
