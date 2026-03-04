// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import com.moneat.uptime.services.UptimeService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

private val uptimeService = UptimeService()

private const val DEFAULT_HEARTBEAT_HOURS = 24
private const val MAX_HEARTBEAT_HOURS = 168
private const val DEFAULT_INTERVAL = 60

class ListUptimeMonitorsTool : McpTool {
    override val name = "list_uptime_monitors"
    override val description =
        "List all uptime monitors with current status"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val monitors = uptimeService.listMonitors(context.organizationId)
        return jsonResult(monitors)
    }
}

class GetMonitorHeartbeatsTool : McpTool {
    override val name = "get_monitor_heartbeats"
    override val description =
        "Get heartbeats for an uptime monitor"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "monitor_id" to schemaString("Uptime monitor UUID"),
                "hours" to schemaNumber(
                    "Hours of history (default 24)"
                )
            )
        ),
        required = listOf("monitor_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val monitorId = args["monitor_id"]?.jsonPrimitive?.content
            ?: return errorResult("monitor_id is required")
        val uuid = runCatching { UUID.fromString(monitorId) }.getOrNull()
            ?: return errorResult("Invalid monitor_id format")
        val hrs = args["hours"]?.jsonPrimitive?.intOrNull
            ?.coerceIn(1, MAX_HEARTBEAT_HOURS) ?: DEFAULT_HEARTBEAT_HOURS

        val to = kotlin.time.Instant.fromEpochMilliseconds(
            System.currentTimeMillis()
        )
        val from = kotlin.time.Instant.fromEpochMilliseconds(
            System.currentTimeMillis() - hrs * 3600 * 1000L
        )
        val heartbeats = uptimeService.getHeartbeats(uuid, from, to)
        return jsonResult(heartbeats)
    }
}

class CreateUptimeMonitorTool : McpTool {
    override val name = "create_uptime_monitor"
    override val description = "Create a new uptime monitor"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "name" to schemaString("Monitor name"),
                "url" to schemaString("URL to monitor"),
                "type" to schemaEnum(
                    "Monitor type",
                    listOf("http", "tcp", "ping", "push")
                ),
                "interval_seconds" to schemaNumber(
                    "Check interval in seconds (default 60)"
                )
            )
        ),
        required = listOf("name", "url", "type")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val name = args["name"]?.jsonPrimitive?.content
            ?: return errorResult("name is required")
        val url = args["url"]?.jsonPrimitive?.content
            ?: return errorResult("url is required")
        val type = args["type"]?.jsonPrimitive?.content
            ?: return errorResult("type is required")
        val interval = args["interval_seconds"]?.jsonPrimitive?.intOrNull
            ?: DEFAULT_INTERVAL

        val request =
            com.moneat.uptime.models.CreateUptimeMonitorRequest(
                name = name,
                url = url,
                type = type,
                intervalSeconds = interval
            )
        val monitor = uptimeService.createMonitor(
            context.organizationId,
            request
        )
        return jsonResult(monitor)
    }
}
