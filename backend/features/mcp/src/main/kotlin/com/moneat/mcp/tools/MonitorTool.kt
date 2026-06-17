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

import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.monitor.services.AgentApiKeyService
import com.moneat.monitor.repositories.HostAlertRepositoryImpl
import com.moneat.monitor.repositories.HostRepositoryImpl
import com.moneat.monitor.services.MonitorService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val monitorService = MonitorService(HostRepositoryImpl(), HostAlertRepositoryImpl())
private val agentApiKeyService = AgentApiKeyService()
private const val KEY_LOG_PREFIX_LENGTH = 4

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
            mapOf("host_id" to schemaResourceId("Host resource ID"))
        ),
        required = listOf("host_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val hostId = resolveHostIdArg(args, context.organizationId, monitorService).getOrElse { error ->
            return errorResult(error.message ?: "Invalid host_id")
        }
        val host = monitorService.getHostById(hostId)
            ?: return errorResult("Host not found")
        return jsonResult(host)
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
                "(id=${response.id}, key=${response.key.take(KEY_LOG_PREFIX_LENGTH)}***)"
        )
    }
}
