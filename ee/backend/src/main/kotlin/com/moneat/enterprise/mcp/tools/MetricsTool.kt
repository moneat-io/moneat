// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import com.moneat.monitor.services.MonitorService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val metricsMonitorService = MonitorService()

private const val DEFAULT_METRIC_HOURS = 24
private const val MAX_METRIC_HOURS = 168

class GetHostMetricsTool : McpTool {
    override val name = "get_host_metrics"
    override val description =
        "Get historical metrics for a host (CPU, memory, disk, network)"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "host_id" to schemaString("Host ID (integer)"),
                "hours" to schemaNumber(
                    "Hours of history (default 24, max 168)"
                )
            )
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
        val hrs = args["hours"]?.jsonPrimitive?.intOrNull
            ?.coerceIn(1, MAX_METRIC_HOURS) ?: DEFAULT_METRIC_HOURS

        val now = System.currentTimeMillis()
        val from = now - hrs * 3600 * 1000L
        val metrics = metricsMonitorService.getHistoricalMetrics(
            hostId,
            from,
            now,
            intervalSeconds = null
        )
        return jsonResult(metrics)
    }
}

class GetContainerMetricsTool : McpTool {
    override val name = "get_container_metrics"
    override val description =
        "Get metrics for a specific container on a host"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "host_id" to schemaString("Host ID (integer)"),
                "container_name" to schemaString("Container name")
            )
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

        val containers = metricsMonitorService
            .getLatestContainers(hostId)
        val containerName = args["container_name"]
            ?.jsonPrimitive?.content
        val filtered = if (containerName != null) {
            containers.filter {
                it.name.contains(containerName, ignoreCase = true)
            }
        } else {
            containers
        }
        return jsonResult(filtered)
    }
}

class GetHostLogsTool : McpTool {
    override val name = "get_host_logs"
    override val description =
        "Get system-level logs for a host"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "host_id" to schemaString("Host ID (integer)"),
                "hours" to schemaNumber(
                    "Hours of history (default 24)"
                ),
                "level" to schemaEnum(
                    "Filter by log level",
                    listOf("error", "warn", "info", "debug")
                )
            )
        ),
        required = listOf("host_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val systemId = args["host_id"]?.jsonPrimitive?.content
            ?: return errorResult("host_id is required")

        // Delegates to query_logs with host_id filter
        return textResult(
            "Use query_logs tool with host_id=$systemId " +
                "for host-level log queries"
        )
    }
}

class GetAlertConfigTool : McpTool {
    override val name = "get_alert_config"
    override val description =
        "Get current alert configuration for a host"
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
        val alerts = metricsMonitorService.listAlerts(hostId, context.organizationId)
        return jsonResult(alerts)
    }
}
