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

package com.moneat.security

import kotlin.test.Test
import kotlin.test.assertNotEquals

class StableKeyEncodingTest {

    @Test
    fun `pipe characters cannot move data across segment boundaries`() {
        val left = encodeStableKeySegments(listOf("a" to "x|b", "c" to "d"))
        val right = encodeStableKeySegments(listOf("a" to "x", "b|c" to "d"))

        assertNotEquals(left, right)
    }

    @Test
    fun `equals characters cannot forge a different key value pair`() {
        val left = encodeStableKeySegments(listOf("a=b" to "c", "d" to "e"))
        val right = encodeStableKeySegments(listOf("a" to "b=c", "d" to "e"))

        assertNotEquals(left, right)
    }

    @Test
    fun `backslash zero text and literal nul encode distinctly`() {
        val escapedText = encodeStableKeySegments(listOf("host" to "\\0"))
        val literalNul = encodeStableKeySegments(listOf("host" to "\u0000"))
        val missing = encodeStableKeySegments(listOf("host"), emptyMap())

        assertNotEquals(escapedText, literalNul)
        assertNotEquals(missing, literalNul)
    }
}
