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
import com.moneat.events.models.CreateProjectRequest
import com.moneat.events.services.DashboardService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private val projectDashboardService = DashboardService.create()

class ListProjectsTool : McpTool {
    override val name = "list_projects"
    override val description =
        "List all projects in the organization with details"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val projects = projectDashboardService.getProjects(
            context.userId
        )
        return jsonResult(projects)
    }
}

class CreateProjectTool : McpTool {
    override val name = "create_project"
    override val description = "Create a new project"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "name" to schemaString("Project name"),
                "platform" to schemaString(
                    "Platform (e.g. javascript, python, java)"
                )
            )
        ),
        required = listOf("name", "platform")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val name = args["name"]?.jsonPrimitive?.content
            ?: return errorResult("name is required")
        val platform = args["platform"]?.jsonPrimitive?.content
            ?: return errorResult("platform is required")

        val project = projectDashboardService.createProject(
            userId = context.userId,
            request = CreateProjectRequest(
                name = name,
                framework = platform
            )
        )
        return jsonResult(project)
    }
}

class GetProjectTool : McpTool {
    override val name = "get_project"
    override val description =
        "Get project details including DSN and settings"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("project_id" to schemaNumber("Project ID"))
        ),
        required = listOf("project_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val projectId = args["project_id"]?.jsonPrimitive?.long
            ?: return errorResult("project_id is required")
        val project = projectDashboardService.getProject(projectId)
            ?: return errorResult("Project not found: $projectId")
        return jsonResult(project)
    }
}

class GetProjectStatsTool : McpTool {
    override val name = "get_project_stats"
    override val description =
        "Get project statistics: error counts, event volume"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "project_id" to schemaNumber("Project ID"),
                "period" to schemaEnum(
                    "Time period",
                    listOf("24h", "7d", "30d")
                )
            )
        ),
        required = listOf("project_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val projectId = args["project_id"]?.jsonPrimitive?.long
            ?: return errorResult("project_id is required")
        val period = args["period"]?.jsonPrimitive?.content ?: "7d"
        val stats = projectDashboardService.getProjectStats(
            projectId,
            period
        )
        return jsonResult(stats)
    }
}
