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
