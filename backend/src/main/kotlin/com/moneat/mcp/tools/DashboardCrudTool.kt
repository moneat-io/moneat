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

import com.moneat.dashboards.models.CreateDashboardAlertRequest
import com.moneat.dashboards.models.UpdateDashboardAlertRequest
import com.moneat.dashboards.models.UpdateDashboardRequest
import com.moneat.dashboards.repositories.DashboardFolderRepositoryImpl
import com.moneat.dashboards.repositories.DashboardRepositoryImpl
import com.moneat.dashboards.repositories.DashboardWidgetRepositoryImpl
import com.moneat.dashboards.services.CustomDashboardService
import com.moneat.dashboards.services.DashboardAlertService
import com.moneat.events.repositories.ProjectRepositoryImpl
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val dashCrudService = CustomDashboardService(
    DashboardFolderRepositoryImpl(),
    DashboardRepositoryImpl(),
    DashboardWidgetRepositoryImpl(),
    ProjectRepositoryImpl { col, _, _ -> col },
)
private val dashAlertService = DashboardAlertService()
private val dashboardAlertConditions = listOf(
    "gt", "lt", "eq", "gte", "lte", ">", "<", "==", ">=", "<="
)
private val dashboardAlertSeverities = listOf(
    "P0", "P1", "P2", "P3", "P4", "P5", "CRITICAL", "HIGH", "MEDIUM", "LOW"
)

private fun dashboardAlertCondition(input: String): String = when (input) {
    "gt" -> ">"
    "lt" -> "<"
    "eq" -> "=="
    "gte" -> ">="
    "lte" -> "<="
    else -> input
}

private fun dashboardAlertSeverity(input: String?): String? = when (input?.uppercase()) {
    "P0", "CRITICAL" -> "CRITICAL"
    "P1", "HIGH" -> "HIGH"
    "P2", "MEDIUM" -> "MEDIUM"
    "P3", "P4", "P5", "LOW" -> "LOW"
    else -> input
}

class UpdateDashboardTool : McpTool {
    override val name = "update_dashboard"
    override val description =
        "Update a dashboard (title, description, widgets)"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "dashboard_id" to schemaNumber("Dashboard ID"),
                "title" to schemaString("New title"),
                "description" to schemaString("New description")
            )
        ),
        required = listOf("dashboard_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val dashId = args["dashboard_id"]?.jsonPrimitive
            ?.content?.toLongOrNull()
            ?: return errorResult("dashboard_id is required")

        val title = args["title"]?.jsonPrimitive?.content
        val description = args["description"]?.jsonPrimitive?.content
        if (title.isNullOrBlank() && description.isNullOrBlank()) {
            return errorResult(
                "At least one of title or description is required"
            )
        }
        val request = UpdateDashboardRequest(
            title = title,
            description = description
        )
        val dashboard = dashCrudService.updateDashboard(
            id = dashId,
            orgId = context.organizationId.toLong(),
            request = request
        ) ?: return errorResult("Dashboard not found: $dashId")
        return jsonResult(dashboard)
    }
}

class DeleteDashboardTool : McpTool {
    override val name = "delete_dashboard"
    override val description = "Delete a dashboard"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("dashboard_id" to schemaNumber("Dashboard ID"))
        ),
        required = listOf("dashboard_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val dashId = args["dashboard_id"]?.jsonPrimitive
            ?.content?.toLongOrNull()
            ?: return errorResult("dashboard_id is required")

        val deleted = dashCrudService.deleteDashboard(
            id = dashId,
            orgId = context.organizationId.toLong()
        )
        return if (deleted) {
            textResult("Dashboard $dashId deleted")
        } else {
            errorResult("Dashboard not found")
        }
    }
}

class CreateDashboardAlertTool : McpTool {
    override val name = "create_dashboard_alert"
    override val description =
        "Create an alert on a dashboard widget"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "dashboard_id" to schemaNumber("Dashboard ID"),
                "widget_id" to schemaNumber("Widget ID"),
                "name" to schemaString("Alert name"),
                "condition" to schemaEnum(
                    "Condition", dashboardAlertConditions
                ),
                "threshold" to schemaNumber("Threshold value"),
                "duration_seconds" to schemaInteger(
                    "Duration before firing"
                ),
                "incident_severity" to schemaEnum(
                    "Severity",
                    dashboardAlertSeverities
                )
            )
        ),
        required = listOf(
            "dashboard_id",
            "widget_id",
            "name",
            "condition",
            "threshold"
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val dashId = args["dashboard_id"]?.jsonPrimitive
            ?.content?.toLongOrNull()
            ?: return errorResult("dashboard_id is required")
        val widgetId = args["widget_id"]?.jsonPrimitive
            ?.content?.toLongOrNull()
            ?: return errorResult("widget_id is required")
        val name = args["name"]?.jsonPrimitive?.content
            ?: return errorResult("name is required")
        val condition = args["condition"]?.jsonPrimitive?.content
            ?: return errorResult("condition is required")
        val threshold = args["threshold"]?.jsonPrimitive?.content
            ?.toDoubleOrNull()
            ?: return errorResult("threshold must be a number")

        val durationSeconds = if (args.containsKey("duration_seconds")) {
            args["duration_seconds"]?.jsonPrimitive?.intOrNull
                ?: return errorResult(
                    "duration_seconds must be a valid integer"
                )
        } else {
            0
        }
        val request = CreateDashboardAlertRequest(
            widgetId = widgetId,
            name = name,
            condition = dashboardAlertCondition(condition),
            threshold = threshold,
            durationSeconds = durationSeconds,
            incidentSeverity = dashboardAlertSeverity(
                args["incident_severity"]?.jsonPrimitive?.content
            )
        )
        val alert = dashAlertService.createAlert(
            dashboardId = dashId,
            orgId = context.organizationId.toLong(),
            createdBy = context.userId.toLong(),
            request = request
        )
        return jsonResult(alert)
    }
}

class UpdateDashboardAlertTool : McpTool {
    override val name = "update_dashboard_alert"
    override val description = "Update a dashboard alert"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "dashboard_id" to schemaNumber("Dashboard ID"),
                "alert_id" to schemaNumber("Alert ID"),
                "name" to schemaString("Alert name"),
                "condition" to schemaEnum(
                    "Condition", dashboardAlertConditions
                ),
                "threshold" to schemaNumber("Threshold value"),
                "duration_seconds" to schemaInteger(
                    "Duration before firing"
                ),
                "incident_severity" to schemaEnum(
                    "Severity",
                    dashboardAlertSeverities
                ),
                "enabled" to schemaBoolean("Enable/disable")
            )
        ),
        required = listOf("dashboard_id", "alert_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val dashId = args["dashboard_id"]?.jsonPrimitive
            ?.content?.toLongOrNull()
            ?: return errorResult("dashboard_id is required")
        val alertId = args["alert_id"]?.jsonPrimitive
            ?.content?.toLongOrNull()
            ?: return errorResult("alert_id is required")

        val name = args["name"]?.jsonPrimitive?.content
        val condition = args["condition"]?.jsonPrimitive?.content
        val threshold = if (args.containsKey("threshold")) {
            args["threshold"]?.jsonPrimitive?.content
                ?.toDoubleOrNull()
                ?: return errorResult("threshold must be a number")
        } else {
            null
        }
        val durationSeconds = if (args.containsKey("duration_seconds")) {
            args["duration_seconds"]?.jsonPrimitive?.intOrNull
                ?: return errorResult(
                    "duration_seconds must be a valid integer"
                )
        } else {
            null
        }
        val incidentSeverity = dashboardAlertSeverity(
            args["incident_severity"]?.jsonPrimitive?.content
        )
        val enabled = if (args.containsKey("enabled")) {
            args["enabled"]?.jsonPrimitive?.content
                ?.toBooleanStrictOrNull()
                ?: return errorResult(
                    "enabled must be true or false"
                )
        } else {
            null
        }
        if (
            name == null &&
            condition == null &&
            threshold == null &&
            durationSeconds == null &&
            incidentSeverity == null &&
            enabled == null
        ) {
            return errorResult(
                "At least one field must be provided to update"
            )
        }
        val request = UpdateDashboardAlertRequest(
            name = name,
            condition = condition?.let(::dashboardAlertCondition),
            threshold = threshold,
            durationSeconds = durationSeconds,
            incidentSeverity = incidentSeverity,
            enabled = enabled
        )
        val alert = dashAlertService.updateAlert(
            alertId = alertId,
            dashboardId = dashId,
            orgId = context.organizationId.toLong(),
            request = request
        ) ?: return errorResult("Alert not found")
        return jsonResult(alert)
    }
}

class DeleteDashboardAlertTool : McpTool {
    override val name = "delete_dashboard_alert"
    override val description = "Delete a dashboard alert"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "dashboard_id" to schemaNumber("Dashboard ID"),
                "alert_id" to schemaNumber("Alert ID")
            )
        ),
        required = listOf("dashboard_id", "alert_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val dashId = args["dashboard_id"]?.jsonPrimitive
            ?.content?.toLongOrNull()
            ?: return errorResult("dashboard_id is required")
        val alertId = args["alert_id"]?.jsonPrimitive
            ?.content?.toLongOrNull()
            ?: return errorResult("alert_id is required")

        val deleted = dashAlertService.deleteAlert(
            alertId = alertId,
            dashboardId = dashId,
            orgId = context.organizationId.toLong()
        )
        return if (deleted) {
            textResult("Dashboard alert $alertId deleted")
        } else {
            errorResult("Alert not found")
        }
    }
}
