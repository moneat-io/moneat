// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import com.moneat.monitor.models.CreateAlertRequest
import com.moneat.monitor.models.CreateSilencePeriodRequest
import com.moneat.monitor.models.UpdateAlertRequest
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val alertMonitorService = MonitorService()
private val silenceAlertService = MonitorAlertService()

class CreateAlertTool : McpTool {
    override val name = "create_alert"
    override val description = "Create a monitoring alert for a host"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "system_id" to schemaString("Host/system UUID"),
                "metric" to schemaEnum(
                    "Metric to monitor",
                    listOf(
                        "cpu", "memory", "disk", "network_in",
                        "network_out", "load"
                    )
                ),
                "condition" to schemaEnum(
                    "Alert condition",
                    listOf("gt", "lt", "eq")
                ),
                "threshold" to schemaNumber("Threshold value"),
                "duration_seconds" to schemaNumber(
                    "Duration before firing (default 0)"
                ),
                "enabled" to schemaBoolean("Enable alert (default true)")
            )
        ),
        required = listOf("system_id", "metric", "condition", "threshold")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val systemId = args["system_id"]?.jsonPrimitive?.content
            ?: return errorResult("system_id is required")
        val hostId = systemId.toIntOrNull()
            ?: return errorResult("Invalid system_id format")
        val metric = args["metric"]?.jsonPrimitive?.content
            ?: return errorResult("metric is required")
        val condition = args["condition"]?.jsonPrimitive?.content
            ?: return errorResult("condition is required")
        val threshold = args["threshold"]?.jsonPrimitive?.content
            ?.toDoubleOrNull()
            ?: return errorResult("threshold must be a number")
        val duration = args["duration_seconds"]?.jsonPrimitive
            ?.intOrNull ?: 0
        val enabled = args["enabled"]?.jsonPrimitive?.content
            ?.toBooleanStrictOrNull() ?: true

        val request = CreateAlertRequest(
            metric = metric,
            condition = condition,
            threshold = threshold,
            durationSeconds = duration,
            enabled = enabled
        )
        val alert = alertMonitorService.createAlert(
            hostId,
            context.organizationId,
            request
        )
        return jsonResult(alert)
    }
}

class UpdateAlertTool : McpTool {
    override val name = "update_alert"
    override val description = "Update a monitoring alert"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "system_id" to schemaString("Host/system UUID"),
                "alert_id" to schemaNumber("Alert ID"),
                "metric" to schemaString("Metric to monitor"),
                "condition" to schemaEnum(
                    "Alert condition",
                    listOf("gt", "lt", "eq")
                ),
                "threshold" to schemaNumber("Threshold value"),
                "duration_seconds" to schemaNumber("Duration"),
                "enabled" to schemaBoolean("Enable/disable alert")
            )
        ),
        required = listOf("system_id", "alert_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val systemId = args["system_id"]?.jsonPrimitive?.content
            ?: return errorResult("system_id is required")
        val hostId = systemId.toIntOrNull()
            ?: return errorResult("Invalid system_id format")
        val alertId = args["alert_id"]?.jsonPrimitive?.intOrNull
            ?: return errorResult("alert_id is required")

        val request = UpdateAlertRequest(
            metric = args["metric"]?.jsonPrimitive?.content,
            condition = args["condition"]?.jsonPrimitive?.content,
            threshold = args["threshold"]?.jsonPrimitive?.content
                ?.toDoubleOrNull(),
            durationSeconds = args["duration_seconds"]
                ?.jsonPrimitive?.intOrNull,
            enabled = args["enabled"]?.jsonPrimitive?.content
                ?.toBooleanStrictOrNull()
        )
        val updated = alertMonitorService.updateAlert(
            alertId,
            hostId,
            context.organizationId,
            request
        )
        return if (updated) {
            textResult("Alert $alertId updated")
        } else {
            errorResult("Alert not found or update failed")
        }
    }
}

class DeleteAlertTool : McpTool {
    override val name = "delete_alert"
    override val description = "Delete a monitoring alert"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "system_id" to schemaString("Host/system UUID"),
                "alert_id" to schemaNumber("Alert ID")
            )
        ),
        required = listOf("system_id", "alert_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val systemId = args["system_id"]?.jsonPrimitive?.content
            ?: return errorResult("system_id is required")
        val hostId = systemId.toIntOrNull()
            ?: return errorResult("Invalid system_id format")
        val alertId = args["alert_id"]?.jsonPrimitive?.intOrNull
            ?: return errorResult("alert_id is required")

        val deleted = alertMonitorService.deleteAlert(
            alertId,
            hostId,
            context.organizationId
        )
        return if (deleted) {
            textResult("Alert $alertId deleted")
        } else {
            errorResult("Alert not found or delete failed")
        }
    }
}

class CreateSilencePeriodTool : McpTool {
    override val name = "create_silence_period"
    override val description =
        "Create an alert silence period for the organization"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "reason" to schemaString("Reason for silencing"),
                "starts_at" to schemaNumber(
                    "Start time (epoch millis)"
                ),
                "ends_at" to schemaNumber("End time (epoch millis)")
            )
        ),
        required = listOf("starts_at", "ends_at")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val startsAt = args["starts_at"]?.jsonPrimitive?.content
            ?.toLongOrNull()
            ?: return errorResult("starts_at is required")
        val endsAt = args["ends_at"]?.jsonPrimitive?.content
            ?.toLongOrNull()
            ?: return errorResult("ends_at is required")
        if (startsAt >= endsAt) {
            return errorResult("starts_at must be before ends_at")
        }
        val reason = args["reason"]?.jsonPrimitive?.content

        val request = CreateSilencePeriodRequest(
            reason = reason,
            startsAt = startsAt,
            endsAt = endsAt
        )
        val period = silenceAlertService.createSilencePeriod(
            context.organizationId,
            context.userId,
            request
        )
        return jsonResult(period)
    }
}

class DeleteSilencePeriodTool : McpTool {
    override val name = "delete_silence_period"
    override val description = "Delete an alert silence period"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("id" to schemaNumber("Silence period ID"))
        ),
        required = listOf("id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val id = args["id"]?.jsonPrimitive?.intOrNull
            ?: return errorResult("id is required")
        val deleted = silenceAlertService.deleteSilencePeriod(
            id,
            context.organizationId
        )
        return if (deleted) {
            textResult("Silence period $id deleted")
        } else {
            errorResult("Silence period not found")
        }
    }
}

class DeleteHostTool : McpTool {
    override val name = "delete_host"
    override val description = "Delete a monitored host"
    override val readOnly = false
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

        val deleted = alertMonitorService.deleteHost(
            hostId,
            context.organizationId
        )
        return if (deleted) {
            textResult("Host $systemId deleted")
        } else {
            errorResult("Host not found or delete failed")
        }
    }
}
