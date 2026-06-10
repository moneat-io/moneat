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
private val dashboardAlertPriorities = listOf(
    "P0", "P1", "P2", "P3", "P4", "P5", "CRITICAL", "HIGH", "MEDIUM", "LOW"
)
private const val DASHBOARD_ID_ARG = "dashboard_id"
private const val DASHBOARD_RESOURCE_ID_DESCRIPTION = "Dashboard resource ID"
private const val ERR_DASHBOARD_NOT_FOUND = "Dashboard not found"
private const val ERR_ALERT_NOT_FOUND = "Alert not found"

private fun dashboardAlertCondition(input: String): String = when (input) {
    "gt" -> ">"
    "lt" -> "<"
    "eq" -> "=="
    "gte" -> ">="
    "lte" -> "<="
    ">", "<", "==", ">=", "<=" -> input
    else -> throw IllegalArgumentException("Unknown dashboard alert condition: $input")
}

private fun dashboardAlertPriority(input: String?): String? {
    if (input == null) return null
    return when (input.uppercase()) {
        "P0", "CRITICAL" -> "P0"
        "P1", "HIGH" -> "P1"
        "P2", "MEDIUM" -> "P2"
        "P3", "LOW" -> "P3"
        "P4" -> "P4"
        "P5" -> "P5"
        else -> throw IllegalArgumentException("Unknown dashboard alert priority: $input")
    }
}

private fun JsonObject.requiredStringArg(name: String): String? =
    this[name]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

class UpdateDashboardTool : McpTool {
    override val name = "update_dashboard"
    override val description =
        "Update a dashboard's title and description"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                DASHBOARD_ID_ARG to schemaString(DASHBOARD_RESOURCE_ID_DESCRIPTION),
                "title" to schemaString("New title"),
                "description" to schemaString("New description")
            )
        ),
        required = listOf(DASHBOARD_ID_ARG)
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val dashboardResourceId = args.requiredStringArg(DASHBOARD_ID_ARG)
            ?: return errorResult("$DASHBOARD_ID_ARG is required")

        val title = args["title"]?.jsonPrimitive?.content
        val description = args["description"]?.jsonPrimitive?.content
        if (title.isNullOrBlank() && description.isNullOrBlank()) {
            return errorResult(
                "At least one of title or description is required"
            )
        }
        val dashId = dashCrudService.resolveDashboardId(dashboardResourceId, context.organizationId.toLong())
            ?: return errorResult(ERR_DASHBOARD_NOT_FOUND)
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
            mapOf(DASHBOARD_ID_ARG to schemaString(DASHBOARD_RESOURCE_ID_DESCRIPTION))
        ),
        required = listOf(DASHBOARD_ID_ARG)
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val dashboardResourceId = args.requiredStringArg(DASHBOARD_ID_ARG)
            ?: return errorResult("$DASHBOARD_ID_ARG is required")
        val dashId = dashCrudService.resolveDashboardId(dashboardResourceId, context.organizationId.toLong())
            ?: return errorResult(ERR_DASHBOARD_NOT_FOUND)

        val deleted = dashCrudService.deleteDashboard(
            id = dashId,
            orgId = context.organizationId.toLong()
        )
        return if (deleted) {
            textResult("Dashboard $dashId deleted")
        } else {
            errorResult(ERR_DASHBOARD_NOT_FOUND)
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
                DASHBOARD_ID_ARG to schemaString(DASHBOARD_RESOURCE_ID_DESCRIPTION),
                "widget_id" to schemaString("Widget resource ID"),
                "name" to schemaString("Alert name"),
                "condition" to schemaEnum(
                    "Condition", dashboardAlertConditions
                ),
                "threshold" to schemaNumber("Threshold value"),
                "duration_seconds" to schemaInteger(
                    "Duration before firing"
                ),
                "alert_priority" to schemaEnum(
                    "Alert priority",
                    dashboardAlertPriorities
                )
            )
        ),
        required = listOf(
            DASHBOARD_ID_ARG,
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
        val dashboardResourceId = args.requiredStringArg(DASHBOARD_ID_ARG)
            ?: return errorResult("$DASHBOARD_ID_ARG is required")
        val widgetId = args.requiredStringArg("widget_id")
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
        val normalizedCondition = try {
            dashboardAlertCondition(condition)
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "Invalid dashboard alert condition")
        }
        val normalizedPriority = try {
            dashboardAlertPriority(
                args["alert_priority"]?.jsonPrimitive?.content
                    ?: args["incident_severity"]?.jsonPrimitive?.content
            )
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "Invalid dashboard alert priority")
        }
        val dashId = dashCrudService.resolveDashboardId(dashboardResourceId, context.organizationId.toLong())
            ?: return errorResult(ERR_DASHBOARD_NOT_FOUND)
        val request = CreateDashboardAlertRequest(
            widgetId = widgetId,
            name = name,
            condition = normalizedCondition,
            threshold = threshold,
            durationSeconds = durationSeconds,
            alertPriority = normalizedPriority
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
                DASHBOARD_ID_ARG to schemaString(DASHBOARD_RESOURCE_ID_DESCRIPTION),
                "alert_id" to schemaString("Alert resource ID"),
                "name" to schemaString("Alert name"),
                "condition" to schemaEnum(
                    "Condition", dashboardAlertConditions
                ),
                "threshold" to schemaNumber("Threshold value"),
                "duration_seconds" to schemaInteger(
                    "Duration before firing"
                ),
                "alert_priority" to schemaEnum(
                    "Alert priority",
                    dashboardAlertPriorities
                ),
                "enabled" to schemaBoolean("Enable/disable")
            )
        ),
        required = listOf(DASHBOARD_ID_ARG, "alert_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val dashboardResourceId = args.requiredStringArg(DASHBOARD_ID_ARG)
            ?: return errorResult("$DASHBOARD_ID_ARG is required")
        val alertResourceId = args.requiredStringArg("alert_id")
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
        val normalizedCondition = try {
            condition?.let(::dashboardAlertCondition)
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "Invalid dashboard alert condition")
        }
        val alertPriority = try {
            dashboardAlertPriority(
                args["alert_priority"]?.jsonPrimitive?.content
                    ?: args["incident_severity"]?.jsonPrimitive?.content
            )
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "Invalid dashboard alert priority")
        }
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
            alertPriority == null &&
            enabled == null
        ) {
            return errorResult(
                "At least one field must be provided to update"
            )
        }
        val dashId = dashCrudService.resolveDashboardId(dashboardResourceId, context.organizationId.toLong())
            ?: return errorResult(ERR_DASHBOARD_NOT_FOUND)
        val alertId = dashAlertService.resolveAlertId(alertResourceId, dashId, context.organizationId.toLong())
            ?: return errorResult(ERR_ALERT_NOT_FOUND)
        val request = UpdateDashboardAlertRequest(
            name = name,
            condition = normalizedCondition,
            threshold = threshold,
            durationSeconds = durationSeconds,
            alertPriority = alertPriority,
            enabled = enabled
        )
        val alert = dashAlertService.updateAlert(
            alertId = alertId,
            dashboardId = dashId,
            orgId = context.organizationId.toLong(),
            request = request
        ) ?: return errorResult(ERR_ALERT_NOT_FOUND)
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
                DASHBOARD_ID_ARG to schemaString(DASHBOARD_RESOURCE_ID_DESCRIPTION),
                "alert_id" to schemaString("Alert resource ID")
            )
        ),
        required = listOf(DASHBOARD_ID_ARG, "alert_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val dashboardResourceId = args.requiredStringArg(DASHBOARD_ID_ARG)
            ?: return errorResult("$DASHBOARD_ID_ARG is required")
        val alertResourceId = args.requiredStringArg("alert_id")
            ?: return errorResult("alert_id is required")
        val dashId = dashCrudService.resolveDashboardId(dashboardResourceId, context.organizationId.toLong())
            ?: return errorResult(ERR_DASHBOARD_NOT_FOUND)
        val alertId = dashAlertService.resolveAlertId(alertResourceId, dashId, context.organizationId.toLong())
            ?: return errorResult(ERR_ALERT_NOT_FOUND)

        val deleted = dashAlertService.deleteAlert(
            alertId = alertId,
            dashboardId = dashId,
            orgId = context.organizationId.toLong()
        )
        return if (deleted) {
            textResult("Dashboard alert $alertId deleted")
        } else {
            errorResult(ERR_ALERT_NOT_FOUND)
        }
    }
}
