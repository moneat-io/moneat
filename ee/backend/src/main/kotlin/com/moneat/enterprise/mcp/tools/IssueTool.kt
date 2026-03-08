// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import com.moneat.events.models.IssueUpdateRequest
import com.moneat.events.services.DashboardService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private val dashboardService = DashboardService()

private const val DEFAULT_PAGE = 1
private const val DEFAULT_LIMIT = 25
private const val MAX_LIMIT = 1000
private const val DEFAULT_EVENT_LIMIT = 50
private const val MAX_EVENT_LIMIT = 500

private val ALLOWED_ISSUE_STATUSES = listOf(
    "unresolved", "resolved", "ignored",
    "resolvedInNextRelease"
)

class ListIssuesTool : McpTool {
    override val name = "list_issues"
    override val description =
        "List issues for a project with optional status filter"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "project_id" to schemaNumber("Project ID"),
                "status" to schemaEnum(
                    "Filter by status",
                    ALLOWED_ISSUE_STATUSES
                ),
                "page" to schemaNumber("Page number (default 1)"),
                "limit" to schemaNumber("Results per page (default 25)")
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
        val status = args["status"]?.jsonPrimitive?.content
        val page = args["page"]?.jsonPrimitive?.intOrNull ?: DEFAULT_PAGE
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val issues = dashboardService.getIssues(projectId, page, limit, status)
        return jsonResult(issues)
    }
}

class GetIssueTool : McpTool {
    override val name = "get_issue"
    override val description =
        "Get detailed information about a specific issue including stack trace"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("issue_id" to schemaString("Issue ID"))
        ),
        required = listOf("issue_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val issueId = args["issue_id"]?.jsonPrimitive?.content
            ?: return errorResult("issue_id is required")
        val issue = dashboardService.getIssue(issueId)
            ?: return errorResult("Issue not found: $issueId")
        return jsonResult(issue)
    }
}

class GetIssueEventsTool : McpTool {
    override val name = "get_issue_events"
    override val description = "Get events associated with a specific issue"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "issue_id" to schemaString("Issue ID"),
                "limit" to schemaNumber("Max events (default 50)")
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
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_EVENT_LIMIT).coerceIn(1, MAX_EVENT_LIMIT)
        val events = dashboardService.getIssueEvents(issueId, limit)
        return jsonResult(events)
    }
}

class UpdateIssueStatusTool : McpTool {
    override val name = "update_issue_status"
    override val description =
        "Update issue status (resolve, ignore, or reopen)"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "issue_id" to schemaString("Issue ID"),
                "status" to schemaEnum(
                    "New status",
                    ALLOWED_ISSUE_STATUSES
                )
            )
        ),
        required = listOf("issue_id", "status")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val issueId = args["issue_id"]?.jsonPrimitive?.content
            ?: return errorResult("issue_id is required")
        val status = args["status"]?.jsonPrimitive?.content
            ?: return errorResult("status is required")
        if (status !in ALLOWED_ISSUE_STATUSES) {
            return errorResult(
                "Invalid status: $status. Must be one of: ${ALLOWED_ISSUE_STATUSES.joinToString(", ")}"
            )
        }
        dashboardService.updateIssue(issueId, IssueUpdateRequest(status))
        return textResult("Issue $issueId status updated to $status")
    }
}

// Schema helper functions

internal fun schemaString(description: String): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("string"),
        "description" to JsonPrimitive(description)
    )
)

fun schemaNumber(description: String): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("number"),
        "description" to JsonPrimitive(description)
    )
)

fun schemaEnum(
    description: String,
    values: List<String>
): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("string"),
        "description" to JsonPrimitive(description),
        "enum" to JsonArray(values.map { JsonPrimitive(it) })
    )
)

fun schemaBoolean(description: String): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("boolean"),
        "description" to JsonPrimitive(description)
    )
)

internal fun schemaInteger(description: String): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("integer"),
        "description" to JsonPrimitive(description)
    )
)

internal fun schemaObject(description: String): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("object"),
        "description" to JsonPrimitive(description)
    )
)
