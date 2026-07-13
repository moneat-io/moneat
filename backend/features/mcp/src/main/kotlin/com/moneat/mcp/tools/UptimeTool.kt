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

import com.moneat.billing.services.BillingQuotaService
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.uptime.repositories.UptimeMonitorRepositoryImpl
import com.moneat.uptime.services.UptimeService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

private val uptimeService = UptimeService(BillingQuotaService(), UptimeMonitorRepositoryImpl())

private const val DEFAULT_HEARTBEAT_HOURS = 24
private const val MAX_HEARTBEAT_HOURS = 168
private const val DEFAULT_INTERVAL = 60
private const val DEFAULT_RETRIES = 1
private const val DEFAULT_RETRY_INTERVAL_SECONDS = 60
private const val MILLIS_PER_HOUR = 3_600_000L

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
            System.currentTimeMillis() - hrs * MILLIS_PER_HOUR
        )
        val heartbeats = uptimeService.getHeartbeats(uuid, from, to)
        return jsonResult(heartbeats)
    }
}

class CreateUptimeMonitorTool(
    private val service: UptimeService = uptimeService,
) : McpTool {
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
                ),
                "retries" to schemaInteger(
                    "Retries after a failed check (default 1)"
                ),
                "retry_interval_seconds" to schemaInteger(
                    "Seconds between failed-check retries (default 60)"
                ),
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
        val retries = if (args.containsKey("retries")) {
            args["retries"]?.jsonPrimitive?.intOrNull
                ?: return errorResult("retries must be a valid integer")
        } else {
            DEFAULT_RETRIES
        }
        val retryInterval = if (args.containsKey("retry_interval_seconds")) {
            args["retry_interval_seconds"]?.jsonPrimitive?.intOrNull
                ?: return errorResult("retry_interval_seconds must be a valid integer")
        } else {
            DEFAULT_RETRY_INTERVAL_SECONDS
        }

        val request =
            com.moneat.uptime.models.CreateUptimeMonitorRequest(
                name = name,
                url = url,
                type = type,
                intervalSeconds = interval,
                retries = retries,
                retryIntervalSeconds = retryInterval,
            )
        val monitor = service.createMonitor(
            context.organizationId,
            request
        )
        return jsonResult(monitor)
    }
}
