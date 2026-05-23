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
import com.moneat.uptime.models.UpdateUptimeMonitorRequest
import com.moneat.uptime.repositories.UptimeMonitorRepositoryImpl
import com.moneat.uptime.services.UptimeService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

private val uptimeCrudService = UptimeService(BillingQuotaService(), UptimeMonitorRepositoryImpl())

class UpdateUptimeMonitorTool : McpTool {
    override val name = "update_uptime_monitor"
    override val description = "Update an uptime monitor"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "monitor_id" to schemaString("Monitor UUID"),
                "name" to schemaString("Monitor name"),
                "url" to schemaString("URL to monitor"),
                "interval_seconds" to schemaInteger(
                    "Check interval in seconds"
                ),
                "active" to schemaBoolean("Enable/disable")
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
        val uuid = runCatching { UUID.fromString(monitorId) }
            .getOrNull()
            ?: return errorResult("Invalid monitor_id format")

        val intervalSeconds = if (args.containsKey("interval_seconds")) {
            args["interval_seconds"]?.jsonPrimitive?.intOrNull
                ?: return errorResult(
                    "interval_seconds must be a valid integer"
                )
        } else {
            null
        }
        val active = if (args.containsKey("active")) {
            args["active"]?.jsonPrimitive?.content
                ?.toBooleanStrictOrNull()
                ?: return errorResult(
                    "active must be true or false"
                )
        } else {
            null
        }
        val request = UpdateUptimeMonitorRequest(
            name = args["name"]?.jsonPrimitive?.content,
            url = args["url"]?.jsonPrimitive?.content,
            intervalSeconds = intervalSeconds,
            active = active
        )
        val monitor = uptimeCrudService.updateMonitor(
            uuid, context.organizationId, request
        ) ?: return errorResult("Monitor not found: $monitorId")
        return jsonResult(monitor)
    }
}

class DeleteUptimeMonitorTool : McpTool {
    override val name = "delete_uptime_monitor"
    override val description = "Delete an uptime monitor"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("monitor_id" to schemaString("Monitor UUID"))
        ),
        required = listOf("monitor_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val monitorId = args["monitor_id"]?.jsonPrimitive?.content
            ?: return errorResult("monitor_id is required")
        val uuid = runCatching { UUID.fromString(monitorId) }
            .getOrNull()
            ?: return errorResult("Invalid monitor_id format")

        val deleted = uptimeCrudService.deleteMonitor(
            uuid,
            context.organizationId
        )
        return if (deleted) {
            textResult("Monitor $monitorId deleted")
        } else {
            errorResult("Monitor not found")
        }
    }
}

class PauseUptimeMonitorTool : McpTool {
    override val name = "pause_uptime_monitor"
    override val description = "Pause an uptime monitor"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("monitor_id" to schemaString("Monitor UUID"))
        ),
        required = listOf("monitor_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val monitorId = args["monitor_id"]?.jsonPrimitive?.content
            ?: return errorResult("monitor_id is required")
        val uuid = runCatching { UUID.fromString(monitorId) }
            .getOrNull()
            ?: return errorResult("Invalid monitor_id format")

        val paused = uptimeCrudService.pauseMonitor(
            uuid,
            context.organizationId
        )
        return if (paused) {
            textResult("Monitor $monitorId paused")
        } else {
            errorResult("Monitor not found or already paused")
        }
    }
}

class ResumeUptimeMonitorTool : McpTool {
    override val name = "resume_uptime_monitor"
    override val description = "Resume a paused uptime monitor"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("monitor_id" to schemaString("Monitor UUID"))
        ),
        required = listOf("monitor_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val monitorId = args["monitor_id"]?.jsonPrimitive?.content
            ?: return errorResult("monitor_id is required")
        val uuid = runCatching { UUID.fromString(monitorId) }
            .getOrNull()
            ?: return errorResult("Invalid monitor_id format")

        val resumed = uptimeCrudService.resumeMonitor(
            uuid,
            context.organizationId
        )
        return if (resumed) {
            textResult("Monitor $monitorId resumed")
        } else {
            errorResult("Monitor not found or already active")
        }
    }
}
