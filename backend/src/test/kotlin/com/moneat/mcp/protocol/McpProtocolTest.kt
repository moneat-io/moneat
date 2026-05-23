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

package com.moneat.mcp.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class McpProtocolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `ToolDefinition serialization`() {
        val tool = ToolDefinition(
            name = "test_tool",
            description = "A test tool",
            inputSchema = InputSchema(
                properties = JsonObject(
                    mapOf(
                        "param1" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("A parameter")
                            )
                        )
                    )
                ),
                required = listOf("param1")
            ),
            requiredScopes = setOf("project:read")
        )
        val encoded = json.encodeToString(ToolDefinition.serializer(), tool)
        val decoded = json.decodeFromString(ToolDefinition.serializer(), encoded)

        assertEquals("test_tool", decoded.name)
        assertEquals("A test tool", decoded.description)
        assertEquals(listOf("param1"), decoded.inputSchema.required)
        assertEquals(setOf("project:read"), decoded.requiredScopes)
    }

    @Test
    fun `ToolCallResult with text content`() {
        val result = ToolCallResult(
            content = listOf(ToolContent(text = "some result")),
            isError = false
        )
        val encoded = json.encodeToString(ToolCallResult.serializer(), result)
        val decoded = json.decodeFromString(ToolCallResult.serializer(), encoded)

        assertEquals(1, decoded.content.size)
        assertEquals("text", decoded.content[0].type)
        assertEquals("some result", decoded.content[0].text)
        assertEquals(false, decoded.isError)
    }

    @Test
    fun `ToolCallResult with error`() {
        val result = ToolCallResult(
            content = listOf(ToolContent(text = "error message")),
            isError = true
        )
        val encoded = json.encodeToString(ToolCallResult.serializer(), result)
        val decoded = json.decodeFromString(ToolCallResult.serializer(), encoded)

        assertEquals(true, decoded.isError)
        assertEquals(1, decoded.content.size)
        assertEquals("error message", decoded.content[0].text)
        assertEquals(result, decoded)
    }

    @Test
    fun `ResourceDefinition serialization`() {
        val resource = ResourceDefinition(
            uri = "moneat://org/overview",
            name = "Org Overview",
            description = "Organization summary",
            requiredScopes = setOf("org:read")
        )
        val encoded = json.encodeToString(ResourceDefinition.serializer(), resource)
        val decoded = json.decodeFromString(ResourceDefinition.serializer(), encoded)

        assertEquals("moneat://org/overview", decoded.uri)
        assertEquals("Org Overview", decoded.name)
        assertEquals("application/json", decoded.mimeType)
        assertEquals(setOf("org:read"), decoded.requiredScopes)
    }
}
