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

private const val DEFAULT_INCIDENT_LIMIT = 50
private const val MAX_INCIDENT_LIMIT = 200

class ListIncidentsTool(
    private val incidentService: () -> OnCallAlertService,
) : McpTool {
    override val name = "list_incidents"
    override val description = "List on-call incidents"
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
        val svc = incidentService()
        val status = args["status"]?.jsonPrimitive?.content
        val priority = args["priority"]?.jsonPrimitive?.content
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_INCIDENT_LIMIT)
            .coerceIn(1, MAX_INCIDENT_LIMIT)

        val incidents = svc.listAlerts(
            organizationId = context.organizationId,
            status = status,
            priority = priority,
            limit = limit,
            currentUserId = context.userId
        )
        return jsonResult(incidents)
    }
}

class GetIncidentTool(
    private val incidentService: () -> OnCallAlertService,
) : McpTool {
    override val name = "get_incident"
    override val description =
        "Get on-call incident details and timeline"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("incident_id" to schemaResourceId("Incident resource ID"))
        ),
        required = listOf("incident_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val svc = incidentService()
        val incidentResourceId = args.stringContent("incident_id")
            ?: return errorResult("incident_id is required")
        val incidentId = transaction {
            alertIdForResource(context.organizationId, incidentResourceId)
        } ?: return errorResult("Incident not found: $incidentResourceId")
        val incident = svc.getAlert(
            incidentId,
            context.userId
        ) ?: return errorResult("Incident not found: $incidentResourceId")
        return jsonResult(incident)
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
