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

package com.moneat.mcp.tools

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolUtilsTest {

    @Test
    fun `textResult creates non-error result with text`() {
        val result = textResult("hello world")

        assertFalse(result.isError)
        assertEquals(1, result.content.size)
        assertEquals("text", result.content[0].type)
        assertEquals("hello world", result.content[0].text)
    }

    @Test
    fun `errorResult creates error result`() {
        val result = errorResult("something went wrong")

        assertTrue(result.isError)
        assertEquals(1, result.content.size)
        assertEquals("something went wrong", result.content[0].text)
    }

    @Test
    fun `jsonResult serializes list to JSON`() {
        val data = listOf("a", "b", "c")
        val result = jsonResult(data)

        assertFalse(result.isError)
        assertEquals(1, result.content.size)
        val text = result.content[0].text!!
        assertTrue(text.contains("\"a\""))
        assertTrue(text.contains("\"b\""))
        assertTrue(text.contains("\"c\""))
    }

    @Test
    fun `jsonResult serializes map to JSON`() {
        val data = mapOf("key" to "value", "count" to "42")
        val result = jsonResult(data)

        assertFalse(result.isError)
        val text = result.content[0].text!!
        assertTrue(text.contains("key"))
        assertTrue(text.contains("value"))
    }

    @Test
    fun `schemaString creates correct JSON schema object`() {
        val schema = schemaString("A description")

        assertEquals(
            "string",
            (schema["type"] as kotlinx.serialization.json.JsonPrimitive).content
        )
        assertEquals(
            "A description",
            (schema["description"] as kotlinx.serialization.json.JsonPrimitive).content
        )
    }

    @Test
    fun `schemaNumber creates correct JSON schema object`() {
        val schema = schemaNumber("A number param")

        assertEquals(
            "number",
            (schema["type"] as kotlinx.serialization.json.JsonPrimitive).content
        )
    }

    @Test
    fun `schemaEnum creates correct JSON schema with enum values`() {
        val schema = schemaEnum("Pick one", listOf("a", "b", "c"))

        assertEquals(
            "string",
            (schema["type"] as kotlinx.serialization.json.JsonPrimitive).content
        )
        val enumValues = schema["enum"] as kotlinx.serialization.json.JsonArray
        assertEquals(3, enumValues.size)
    }

    @Test
    fun `schemaBoolean creates correct JSON schema object`() {
        val schema = schemaBoolean("A bool param")

        assertEquals(
            "boolean",
            (schema["type"] as kotlinx.serialization.json.JsonPrimitive).content
        )
    }
}
