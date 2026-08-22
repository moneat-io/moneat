// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.mcp

import com.moneat.enterprise.oncall.alertIdForResource
import com.moneat.enterprise.oncall.services.OnCallAlertService
import com.moneat.enterprise.oncall.services.OnCallScheduleService
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.mcp.tools.errorResult
import com.moneat.mcp.tools.jsonResult
import com.moneat.mcp.tools.schemaEnum
import com.moneat.mcp.tools.schemaNumber
import com.moneat.mcp.tools.schemaResourceId
import com.moneat.mcp.tools.stringContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val scheduleService = OnCallScheduleService()

private const val DEFAULT_ALERT_LIMIT = 50
private const val MAX_ALERT_LIMIT = 200

class ListOnCallAlertsTool(
    private val alertService: () -> OnCallAlertService,
) : McpTool {
    override val name = "list_on_call_alerts"
    override val description = "List on-call alerts and their escalation state"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "status" to schemaEnum(
                    "Filter by status",
                    listOf(
                        "triggered", "acknowledged", "resolved"
                    )
                ),
                "priority" to schemaEnum(
                    "Filter by priority",
                    listOf("P0", "P1", "P2", "P3", "P4", "P5")
                ),
                "limit" to schemaNumber(
                    "Max results (default 50)"
                )
            )
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val svc = alertService()
        val status = args["status"]?.jsonPrimitive?.content
        val priority = args["priority"]?.jsonPrimitive?.content
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_ALERT_LIMIT)
            .coerceIn(1, MAX_ALERT_LIMIT)

        val alerts = svc.listAlerts(
            organizationId = context.organizationId,
            status = status,
            priority = priority,
            limit = limit,
            currentUserId = context.userId
        )
        return jsonResult(alerts)
    }
}

class GetOnCallAlertTool(
    private val alertService: () -> OnCallAlertService,
) : McpTool {
    override val name = "get_on_call_alert"
    override val description =
        "Get an on-call alert and its escalation timeline"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("alert_id" to schemaResourceId("On-call alert resource ID"))
        ),
        required = listOf("alert_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val alertResourceId = args.stringContent("alert_id")
            ?: return errorResult("alert_id is required")
        val alertId = transaction {
            alertIdForResource(context.organizationId, alertResourceId)
        } ?: return errorResult("On-call alert not found: $alertResourceId")
        val svc = alertService()
        val alert = svc.getAlert(
            alertId,
            context.userId
        ) ?: return errorResult("On-call alert not found: $alertResourceId")
        return jsonResult(alert)
    }
}

class ListSchedulesTool : McpTool {
    override val name = "list_schedules"
    override val description = "List on-call schedules"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val schedules = scheduleService.listSchedules(
            context.organizationId
        )
        return jsonResult(schedules)
    }
}
