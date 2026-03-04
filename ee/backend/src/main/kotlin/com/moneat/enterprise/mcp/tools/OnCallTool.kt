// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import com.moneat.enterprise.oncall.services.EscalationEngineHolder
import com.moneat.enterprise.oncall.services.IncidentManagementService
import com.moneat.enterprise.oncall.services.OnCallScheduleService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val scheduleService = OnCallScheduleService()

private const val DEFAULT_INCIDENT_LIMIT = 50
private const val MAX_INCIDENT_LIMIT = 200

private fun getIncidentService(): IncidentManagementService? {
    val engine = EscalationEngineHolder.instance ?: return null
    return IncidentManagementService(engine)
}

class ListIncidentsTool : McpTool {
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
                    listOf("P1", "P2", "P3", "P4", "P5")
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
        val svc = getIncidentService()
            ?: return errorResult("On-call module not initialized")
        val status = args["status"]?.jsonPrimitive?.content
        val priority = args["priority"]?.jsonPrimitive?.content
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_INCIDENT_LIMIT)
            .coerceIn(1, MAX_INCIDENT_LIMIT)

        val incidents = svc.listIncidents(
            organizationId = context.organizationId,
            status = status,
            priorityLevel = priority,
            limit = limit,
            currentUserId = context.userId
        )
        return jsonResult(incidents)
    }
}

class GetIncidentTool : McpTool {
    override val name = "get_incident"
    override val description =
        "Get on-call incident details and timeline"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("incident_id" to schemaNumber("Incident ID"))
        ),
        required = listOf("incident_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val svc = getIncidentService()
            ?: return errorResult("On-call module not initialized")
        val incidentId = args["incident_id"]?.jsonPrimitive?.intOrNull
            ?: return errorResult("incident_id is required")
        val incident = svc.getIncident(
            incidentId,
            context.userId
        ) ?: return errorResult("Incident not found: $incidentId")
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
