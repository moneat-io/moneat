// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class McpProtocolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `JsonRpcRequest serialization round-trip`() {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = "tools/list",
            params = JsonObject(mapOf("cursor" to JsonPrimitive("abc")))
        )
        val encoded = json.encodeToString(JsonRpcRequest.serializer(), request)
        val decoded = json.decodeFromString(JsonRpcRequest.serializer(), encoded)

        assertEquals("2.0", decoded.jsonrpc)
        assertEquals(JsonPrimitive(1), decoded.id)
        assertEquals("tools/list", decoded.method)
        assertNotNull(decoded.params)
    }

    @Test
    fun `JsonRpcResponse with result`() {
        val response = JsonRpcResponse(
            id = JsonPrimitive(42),
            result = JsonPrimitive("hello")
        )
        val encoded = json.encodeToString(JsonRpcResponse.serializer(), response)
        val decoded = json.decodeFromString(JsonRpcResponse.serializer(), encoded)

        assertEquals(JsonPrimitive(42), decoded.id)
        assertNotNull(decoded.result)
        assertNull(decoded.error)
    }

    @Test
    fun `JsonRpcResponse with error`() {
        val response = JsonRpcResponse(
            id = JsonPrimitive(1),
            error = JsonRpcError(
                code = JsonRpcErrorCodes.METHOD_NOT_FOUND,
                message = "Method not found"
            )
        )
        val encoded = json.encodeToString(JsonRpcResponse.serializer(), response)
        val decoded = json.decodeFromString(JsonRpcResponse.serializer(), encoded)

        assertEquals(JsonPrimitive(1), decoded.id)
        assertNull(decoded.result)
        assertNotNull(decoded.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, decoded.error.code)
    }

    @Test
    fun `InitializeResult has correct defaults`() {
        val result = InitializeResult()
        assertEquals(MCP_PROTOCOL_VERSION, result.protocolVersion)
        assertEquals("moneat-mcp-server", result.serverInfo.name)
        assertNotNull(result.capabilities.tools)
        assertNotNull(result.capabilities.resources)
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
            )
        )
        val encoded = json.encodeToString(ToolDefinition.serializer(), tool)
        val decoded = json.decodeFromString(ToolDefinition.serializer(), encoded)

        assertEquals("test_tool", decoded.name)
        assertEquals("A test tool", decoded.description)
        assertEquals(listOf("param1"), decoded.inputSchema.required)
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
            description = "Organization summary"
        )
        val encoded = json.encodeToString(ResourceDefinition.serializer(), resource)
        val decoded = json.decodeFromString(ResourceDefinition.serializer(), encoded)

        assertEquals("moneat://org/overview", decoded.uri)
        assertEquals("Org Overview", decoded.name)
        assertEquals("application/json", decoded.mimeType)
    }

    @Test
    fun `ToolCallParams deserialization`() {
        val jsonStr = """{"name":"list_issues","arguments":{"project_id":1}}"""
        val params = json.decodeFromString(ToolCallParams.serializer(), jsonStr)

        assertEquals("list_issues", params.name)
        assertNotNull(params.arguments)
    }

    @Test
    fun `JsonRpcRequest supports string ID`() {
        val jsonStr = """{"jsonrpc":"2.0","id":"abc-123","method":"tools/list"}"""
        val decoded = json.decodeFromString(JsonRpcRequest.serializer(), jsonStr)

        assertEquals(JsonPrimitive("abc-123"), decoded.id)
        assertEquals("tools/list", decoded.method)
    }

    @Test
    fun `JsonRpcRequest supports numeric ID`() {
        val jsonStr = """{"jsonrpc":"2.0","id":42,"method":"ping"}"""
        val decoded = json.decodeFromString(JsonRpcRequest.serializer(), jsonStr)

        assertEquals(JsonPrimitive(42), decoded.id)
    }

    @Test
    fun `JsonRpcRequest without ID is a notification`() {
        val jsonStr = """{"jsonrpc":"2.0","method":"initialized"}"""
        val decoded = json.decodeFromString(JsonRpcRequest.serializer(), jsonStr)

        assertNull(decoded.id)
    }
}
