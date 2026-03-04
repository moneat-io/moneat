// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import com.moneat.events.services.DashboardService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val correlatedService = DashboardService()

private const val DEFAULT_FEEDBACK_LIMIT = 50
private const val MAX_FEEDBACK_LIMIT = 200

class GetIssueTransactionsTool : McpTool {
    override val name = "get_issue_transactions"
    override val description =
        "Get APM traces related to an error issue"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "issue_id" to schemaString("Issue ID"),
                "limit" to schemaNumber(
                    "Max results (default 50)"
                )
            )
        ),
        required = listOf("issue_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val issueId = args["issue_id"]?.jsonPrimitive?.content
            ?: return errorResult("issue_id is required")
        val limit = (
            args["limit"]?.jsonPrimitive?.intOrNull
                ?: DEFAULT_FEEDBACK_LIMIT
            ).coerceIn(1, MAX_FEEDBACK_LIMIT)
        val events = correlatedService.getIssueEvents(
            issueId,
            limit
        )
        return jsonResult(events)
    }
}

class ListFeedbackTool : McpTool {
    override val name = "list_feedback"
    override val description =
        "List user feedback for a project"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "project_id" to schemaNumber("Project ID"),
                "limit" to schemaNumber(
                    "Max results (default 50)"
                )
            )
        ),
        required = listOf("project_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val projectIdStr = args["project_id"]?.jsonPrimitive?.content
            ?: return errorResult("project_id is required")
        val projectId = projectIdStr.toLongOrNull()
            ?: return errorResult("project_id must be a numeric id")
        val limit = (
            args["limit"]?.jsonPrimitive?.intOrNull
                ?: DEFAULT_FEEDBACK_LIMIT
            ).coerceIn(1, MAX_FEEDBACK_LIMIT)
        val feedback = correlatedService.getFeedback(
            projectId = projectId,
            limit = limit
        )
        return jsonResult(feedback)
    }
}
