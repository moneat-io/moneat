// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
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
