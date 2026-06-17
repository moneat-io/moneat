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
import com.moneat.monitor.repositories.HostAlertRepositoryImpl
import com.moneat.monitor.repositories.HostRepositoryImpl
import com.moneat.monitor.services.MonitorService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val metricsMonitorService = MonitorService(HostRepositoryImpl(), HostAlertRepositoryImpl())

private const val DEFAULT_METRIC_HOURS = 24
private const val MAX_METRIC_HOURS = 168
private const val MILLIS_PER_HOUR = 3_600_000L
private const val HOST_RESOURCE_ID_DESCRIPTION = "Host resource ID"
private const val INVALID_HOST_ID_MESSAGE = "Invalid host_id"

class GetHostMetricsTool : McpTool {
    override val name = "get_host_metrics"
    override val description =
        "Get historical metrics for a host (CPU, memory, disk, network)"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "host_id" to schemaResourceId(HOST_RESOURCE_ID_DESCRIPTION),
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
        val hostId = resolveHostIdArg(args, context.organizationId, metricsMonitorService).getOrElse { error ->
            return errorResult(error.message ?: INVALID_HOST_ID_MESSAGE)
        }
        val hrs = args["hours"]?.jsonPrimitive?.intOrNull
            ?.coerceIn(1, MAX_METRIC_HOURS) ?: DEFAULT_METRIC_HOURS

        val now = System.currentTimeMillis()
        val from = now - hrs * MILLIS_PER_HOUR
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
                "host_id" to schemaResourceId(HOST_RESOURCE_ID_DESCRIPTION),
                "container_name" to schemaString("Container name")
            )
        ),
        required = listOf("host_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val hostId = resolveHostIdArg(args, context.organizationId, metricsMonitorService).getOrElse { error ->
            return errorResult(error.message ?: INVALID_HOST_ID_MESSAGE)
        }

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
        "Get host-level logs for a host"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "host_id" to schemaResourceId(HOST_RESOURCE_ID_DESCRIPTION),
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
            mapOf("host_id" to schemaResourceId(HOST_RESOURCE_ID_DESCRIPTION))
        ),
        required = listOf("host_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val hostId = resolveHostIdArg(args, context.organizationId, metricsMonitorService).getOrElse { error ->
            return errorResult(error.message ?: INVALID_HOST_ID_MESSAGE)
        }
        val alerts = metricsMonitorService.listAlerts(hostId, context.organizationId)
        return jsonResult(alerts)
    }
}
