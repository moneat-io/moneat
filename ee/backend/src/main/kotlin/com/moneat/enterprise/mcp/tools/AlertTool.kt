// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val monitorService = MonitorService()
private val alertService = MonitorAlertService()

class ListAlertsTool : McpTool {
    override val name = "list_alerts"
    override val description =
        "List monitoring alerts for a specific host"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("host_id" to schemaString("Host ID (integer)"))
        ),
        required = listOf("host_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val systemId = args["host_id"]?.jsonPrimitive?.content
            ?: return errorResult("host_id is required")
        val hostId = systemId.toIntOrNull()
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
