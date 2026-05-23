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

import com.moneat.dashboards.repositories.DashboardFolderRepositoryImpl
import com.moneat.dashboards.repositories.DashboardRepositoryImpl
import com.moneat.dashboards.repositories.DashboardWidgetRepositoryImpl
import com.moneat.events.repositories.ProjectRepositoryImpl
import com.moneat.dashboards.services.CustomDashboardService
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val dashboardService = CustomDashboardService(
    DashboardFolderRepositoryImpl(),
    DashboardRepositoryImpl(),
    DashboardWidgetRepositoryImpl(),
    ProjectRepositoryImpl { col, _, _ -> col },
)

class ListDashboardsTool : McpTool {
    override val name = "list_dashboards"
    override val description = "List all dashboards for the organization"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "project_id" to schemaNumber("Optional project ID filter")
            )
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val projectId = args["project_id"]?.jsonPrimitive
            ?.content?.toLongOrNull()
        val dashboards = dashboardService.listDashboards(
            orgId = context.organizationId.toLong(),
            projectId = projectId,
            userId = context.userId
        )
        return jsonResult(dashboards)
    }
}

class GetDashboardTool : McpTool {
    override val name = "get_dashboard"
    override val description = "Get a dashboard with its widgets"
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
        val dashboardId = args["dashboard_id"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return errorResult("dashboard_id is required or must be a number")
        val dashboard = dashboardService.getDashboard(
            id = dashboardId,
            orgId = context.organizationId.toLong(),
            userId = context.userId
        ) ?: return errorResult("Dashboard not found: $dashboardId")
        return jsonResult(dashboard)
    }
}

class CreateDashboardTool : McpTool {
    override val name = "create_dashboard"
    override val description = "Create a new dashboard"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "name" to schemaString("Dashboard name"),
                "description" to schemaString("Dashboard description")
            )
        ),
        required = listOf("name")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val name = args["name"]?.jsonPrimitive?.content
            ?: return errorResult("name is required")
        val desc = args["description"]?.jsonPrimitive?.content

        val request = com.moneat.dashboards.models.CreateDashboardRequest(
            title = name,
            description = desc
        )
        val dashboard = dashboardService.createDashboard(
            orgId = context.organizationId.toLong(),
            userId = context.userId.toLong(),
            request = request
        )
        return jsonResult(dashboard)
    }
}
