// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import com.moneat.monitor.services.AgentApiKeyService
import com.moneat.monitor.services.MonitorService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val monitorService = MonitorService()
private val agentApiKeyService = AgentApiKeyService()

class ListHostsTool : McpTool {
    override val name = "list_hosts"
    override val description =
        "List all monitored hosts/systems with current status"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val systems = monitorService.listHosts(context.organizationId)
        return jsonResult(systems)
    }
}

class GetHostStatusTool : McpTool {
    override val name = "get_host_status"
    override val description =
        "Get detailed status and info for a specific host"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("system_id" to schemaString("Host/system UUID"))
        ),
        required = listOf("system_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val systemId = args["system_id"]?.jsonPrimitive?.content
            ?: return errorResult("system_id is required")
        val hostId = systemId.toIntOrNull()
            ?: return errorResult("Invalid system_id format")
        val system = monitorService.getHostById(hostId)
            ?: return errorResult("Host not found: $systemId")
        return jsonResult(system)
    }
}

class CreateAgentKeyTool : McpTool {
    override val name = "create_agent_key"
    override val description =
        "Create a Datadog-compatible agent API key for monitoring"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("name" to schemaString("Key name"))
        ),
        required = listOf("name")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val name = args["name"]?.jsonPrimitive?.content
            ?: return errorResult("name is required")
        val response = agentApiKeyService.createKey(
            organizationId = context.organizationId,
            name = name,
            createdBy = null
        )
        return textResult(
            "Agent API key created: ${response.name} " +
                "(id=${response.id}, key=${response.key})"
        )
    }
}
