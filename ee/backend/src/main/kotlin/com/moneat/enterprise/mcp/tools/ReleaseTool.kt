// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import com.moneat.events.services.DashboardService
import com.moneat.events.services.ReleaseService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private val releaseService = ReleaseService()
private val dashboardService = DashboardService.create()

class ListReleasesTool : McpTool {
    override val name = "list_releases"
    override val description = "List releases for a project"
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
        val releases = releaseService.listReleases(projectId)
        return jsonResult(releases)
    }
}

class GetReleaseStatsTool : McpTool {
    override val name = "get_release_stats"
    override val description =
        "Get release list with error/performance stats"
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
        val releases = dashboardService.getReleases(projectId)
        return jsonResult(releases)
    }
}
