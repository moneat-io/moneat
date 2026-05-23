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
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.repositories.HostAlertRepositoryImpl
import com.moneat.monitor.repositories.HostRepositoryImpl
import com.moneat.monitor.services.MonitorService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val monitorService = MonitorService(HostRepositoryImpl(), HostAlertRepositoryImpl())
private val alertService = MonitorAlertService()

class ListAlertsTool : McpTool {
    override val name = "list_alerts"
    override val description =
        "List monitoring alerts for a specific host"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("host_id" to schemaInteger("Host ID (integer)"))
        ),
        required = listOf("host_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val hostIdRaw = args["host_id"]?.jsonPrimitive?.content
            ?: return errorResult("host_id is required")
        val hostId = hostIdRaw.toIntOrNull()
            ?: return errorResult("Invalid host_id format")
        val alerts = monitorService.listAlerts(hostId, context.organizationId)
        return jsonResult(alerts)
    }
}

class ListSilencePeriodsTool : McpTool {
    override val name = "list_silence_periods"
    override val description = "List alert silence periods for the org"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val periods = alertService.listSilencePeriods(
            context.organizationId
        )
        return jsonResult(periods)
    }
}
