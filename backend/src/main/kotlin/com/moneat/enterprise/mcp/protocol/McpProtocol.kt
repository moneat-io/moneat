// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.mcp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val MCP_PROTOCOL_VERSION = "2024-11-05"

// JSON-RPC 2.0 message types for MCP

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

// Standard JSON-RPC error codes
object JsonRpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}

// MCP Initialize

@Serializable
data class InitializeParams(
    val protocolVersion: String,
    val capabilities: ClientCapabilities? = null,
    val clientInfo: ClientInfo? = null
)

@Serializable
data class ClientCapabilities(
    val roots: RootsCapability? = null,
    val sampling: JsonObject? = null
)

@Serializable
data class RootsCapability(
    val listChanged: Boolean? = null
)

@Serializable
data class ClientInfo(
    val name: String,
    val version: String? = null
)

@Serializable
data class InitializeResult(
    val protocolVersion: String = MCP_PROTOCOL_VERSION,
    val capabilities: ServerCapabilities = ServerCapabilities(),
    val serverInfo: ServerInfo = ServerInfo()
)

@Serializable
data class ServerCapabilities(
    val tools: ToolsCapability? = ToolsCapability(),
    val resources: ResourcesCapability? = ResourcesCapability()
)

@Serializable
data class ToolsCapability(
    val listChanged: Boolean? = false
)

@Serializable
data class ResourcesCapability(
    val subscribe: Boolean? = false,
    val listChanged: Boolean? = false
)

@Serializable
data class ServerInfo(
    val name: String = "moneat-mcp-server",
    val version: String = "1.0.0"
)

// MCP Tools

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: InputSchema,
    val readOnly: Boolean = true,
)

@Serializable
data class InputSchema(
    val type: String = "object",
    val properties: JsonObject = JsonObject(emptyMap()),
    val required: List<String> = emptyList()
)

@Serializable
data class ToolsListResult(
    val tools: List<ToolDefinition>
)

@Serializable
data class ToolCallParams(
    val name: String,
    val arguments: JsonObject? = null
)

@Serializable
data class ToolCallResult(
    val content: List<ToolContent>,
    @SerialName("isError") val isError: Boolean = false
)

@Serializable
data class ToolContent(
    val type: String = "text",
    val text: String? = null
) {
    init {
        require(type != "text" || text != null) {
            "text-typed ToolContent must have non-null text"
        }
    }
}

// MCP Resources

@Serializable
data class ResourceDefinition(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = "application/json"
)

@Serializable
data class ResourcesListResult(
    val resources: List<ResourceDefinition>
)

@Serializable
data class ResourceReadParams(
    val uri: String
)

@Serializable
data class ResourceReadResult(
    val contents: List<ResourceContent>
)

@Serializable
data class ResourceContent(
    val uri: String,
    val mimeType: String? = "application/json",
    val text: String? = null
)
