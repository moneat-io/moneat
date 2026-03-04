// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import com.moneat.statuspage.models.AddMonitorsRequest
import com.moneat.statuspage.models.CreateIncidentRequest
import com.moneat.statuspage.models.CreateIncidentUpdateRequest
import com.moneat.statuspage.models.CreateStatusPageRequest
import com.moneat.statuspage.models.MonitorAssignment
import com.moneat.statuspage.models.UpdateIncidentRequest
import com.moneat.statuspage.models.UpdateStatusPageRequest
import com.moneat.statuspage.services.StatusPageService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

private val statusPageSvc = StatusPageService()

class ListStatusPagesTool : McpTool {
    override val name = "list_status_pages"
    override val description = "List all status pages"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val pages = statusPageSvc.listStatusPages(
            context.organizationId
        )
        return jsonResult(pages)
    }
}

class CreateStatusPageTool : McpTool {
    override val name = "create_status_page"
    override val description = "Create a new status page"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "name" to schemaString("Page name"),
                "slug" to schemaString("URL slug"),
                "description" to schemaString("Page description"),
                "is_public" to schemaBoolean(
                    "Public visibility (default true)"
                )
            )
        ),
        required = listOf("name", "slug")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val name = args["name"]?.jsonPrimitive?.content
            ?: return errorResult("name is required")
        val slug = args["slug"]?.jsonPrimitive?.content
            ?: return errorResult("slug is required")

        val isPublic = if (args.containsKey("is_public")) {
            args["is_public"]?.jsonPrimitive?.content
                ?.toBooleanStrictOrNull()
                ?: return errorResult(
                    "is_public must be true or false"
                )
        } else {
            true
        }
        val request = CreateStatusPageRequest(
            name = name,
            slug = slug,
            description = args["description"]
                ?.jsonPrimitive?.content,
            isPublic = isPublic
        )
        val page = statusPageSvc.createStatusPage(
            context.organizationId, request
        )
        return jsonResult(page)
    }
}

class GetStatusPageTool : McpTool {
    override val name = "get_status_page"
    override val description = "Get status page details"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("page_id" to schemaString("Status page UUID"))
        ),
        required = listOf("page_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val pageId = args["page_id"]?.jsonPrimitive?.content
            ?: return errorResult("page_id is required")
        val uuid = runCatching { UUID.fromString(pageId) }
            .getOrNull()
            ?: return errorResult("Invalid page_id format")

        val page = statusPageSvc.getStatusPage(
            uuid, context.organizationId
        ) ?: return errorResult("Status page not found")
        return jsonResult(page)
    }
}

class UpdateStatusPageTool : McpTool {
    override val name = "update_status_page"
    override val description = "Update a status page"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "page_id" to schemaString("Status page UUID"),
                "name" to schemaString("Page name"),
                "description" to schemaString("Description"),
                "is_public" to schemaBoolean("Public visibility")
            )
        ),
        required = listOf("page_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val pageId = args["page_id"]?.jsonPrimitive?.content
            ?: return errorResult("page_id is required")
        val uuid = runCatching { UUID.fromString(pageId) }
            .getOrNull()
            ?: return errorResult("Invalid page_id format")

        val isPublic = if (args.containsKey("is_public")) {
            args["is_public"]?.jsonPrimitive?.content
                ?.toBooleanStrictOrNull()
                ?: return errorResult(
                    "is_public must be true or false"
                )
        } else {
            null
        }
        val request = UpdateStatusPageRequest(
            name = args["name"]?.jsonPrimitive?.content,
            description = args["description"]
                ?.jsonPrimitive?.content,
            isPublic = isPublic
        )
        val page = statusPageSvc.updateStatusPage(
            uuid, context.organizationId, request
        ) ?: return errorResult("Status page not found")
        return jsonResult(page)
    }
}

class AddStatusPageMonitorTool : McpTool {
    override val name = "add_status_page_monitor"
    override val description =
        "Add an uptime monitor to a status page"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "page_id" to schemaString("Status page UUID"),
                "monitor_id" to schemaString("Monitor UUID"),
                "display_name" to schemaString(
                    "Display name on status page"
                ),
                "sort_order" to schemaNumber("Sort order")
            )
        ),
        required = listOf("page_id", "monitor_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val pageId = args["page_id"]?.jsonPrimitive?.content
            ?: return errorResult("page_id is required")
        val uuid = runCatching { UUID.fromString(pageId) }
            .getOrNull()
            ?: return errorResult("Invalid page_id format")
        val monitorId = args["monitor_id"]?.jsonPrimitive?.content
            ?: return errorResult("monitor_id is required")
        runCatching { UUID.fromString(monitorId) }.getOrNull()
            ?: return errorResult("monitor_id must be a valid UUID")

        val request = AddMonitorsRequest(
            monitors = listOf(
                MonitorAssignment(
                    monitorId = monitorId,
                    displayName = args["display_name"]
                        ?.jsonPrimitive?.content,
                    sortOrder = args["sort_order"]
                        ?.jsonPrimitive?.intOrNull ?: 0
                )
            )
        )
        val result = statusPageSvc.addMonitors(
            uuid, context.organizationId, request
        )
        return jsonResult(result)
    }
}

class CreateStatusPageIncidentTool : McpTool {
    override val name = "create_status_page_incident"
    override val description =
        "Create an incident on a status page"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "page_id" to schemaString("Status page UUID"),
                "title" to schemaString("Incident title"),
                "status" to schemaEnum(
                    "Incident status",
                    listOf(
                        "investigating", "identified",
                        "monitoring", "resolved"
                    )
                ),
                "impact" to schemaEnum(
                    "Impact level",
                    listOf("none", "minor", "major", "critical")
                ),
                "message" to schemaString("Incident message")
            )
        ),
        required = listOf("page_id", "title", "status", "message")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val pageId = args["page_id"]?.jsonPrimitive?.content
            ?: return errorResult("page_id is required")
        val uuid = runCatching { UUID.fromString(pageId) }
            .getOrNull()
            ?: return errorResult("Invalid page_id format")

        val validStatuses = setOf(
            "investigating", "identified", "monitoring", "resolved"
        )
        val validImpacts = setOf("none", "minor", "major", "critical")
        val title = args["title"]?.jsonPrimitive?.content
            ?: return errorResult("title is required")
        val status = args["status"]?.jsonPrimitive?.content
            ?: return errorResult("status is required")
        if (status !in validStatuses) {
            return errorResult(
                "status must be one of: " +
                    validStatuses.joinToString(", ")
            )
        }
        val impact = args["impact"]?.jsonPrimitive?.content ?: "none"
        if (impact !in validImpacts) {
            return errorResult(
                "impact must be one of: " +
                    validImpacts.joinToString(", ")
            )
        }
        val request = CreateIncidentRequest(
            title = title,
            status = status,
            impact = impact,
            message = args["message"]?.jsonPrimitive?.content
                ?: return errorResult("message is required")
        )
        val incident = statusPageSvc.createIncident(
            uuid, context.organizationId, request
        )
        return jsonResult(incident)
    }
}

class UpdateStatusPageIncidentTool : McpTool {
    override val name = "update_status_page_incident"
    override val description =
        "Update a status page incident"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "page_id" to schemaString("Status page UUID"),
                "incident_id" to schemaString("Incident UUID"),
                "status" to schemaEnum(
                    "Incident status",
                    listOf(
                        "investigating", "identified",
                        "monitoring", "resolved"
                    )
                ),
                "impact" to schemaEnum(
                    "Impact level",
                    listOf("none", "minor", "major", "critical")
                ),
                "title" to schemaString("Updated title")
            )
        ),
        required = listOf("page_id", "incident_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val pageId = args["page_id"]?.jsonPrimitive?.content
            ?: return errorResult("page_id is required")
        val pageUuid = runCatching { UUID.fromString(pageId) }
            .getOrNull()
            ?: return errorResult("Invalid page_id format")
        val incidentId = args["incident_id"]
            ?.jsonPrimitive?.content
            ?: return errorResult("incident_id is required")
        val incidentUuid = runCatching {
            UUID.fromString(incidentId)
        }.getOrNull()
            ?: return errorResult("Invalid incident_id format")

        val request = UpdateIncidentRequest(
            title = args["title"]?.jsonPrimitive?.content,
            status = args["status"]?.jsonPrimitive?.content,
            impact = args["impact"]?.jsonPrimitive?.content
        )
        val incident = statusPageSvc.updateIncident(
            pageUuid, context.organizationId,
            incidentUuid, request
        ) ?: return errorResult("Incident not found")
        return jsonResult(incident)
    }
}

class PostIncidentUpdateTool : McpTool {
    override val name = "post_incident_update"
    override val description =
        "Post an update to a status page incident"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "page_id" to schemaString("Status page UUID"),
                "incident_id" to schemaString("Incident UUID"),
                "status" to schemaEnum(
                    "Status",
                    listOf(
                        "investigating", "identified",
                        "monitoring", "resolved"
                    )
                ),
                "message" to schemaString("Update message")
            )
        ),
        required = listOf(
            "page_id", "incident_id", "status", "message"
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val pageId = args["page_id"]?.jsonPrimitive?.content
            ?: return errorResult("page_id is required")
        val pageUuid = runCatching { UUID.fromString(pageId) }
            .getOrNull()
            ?: return errorResult("Invalid page_id format")
        val incidentId = args["incident_id"]
            ?.jsonPrimitive?.content
            ?: return errorResult("incident_id is required")
        val incidentUuid = runCatching {
            UUID.fromString(incidentId)
        }.getOrNull()
            ?: return errorResult("Invalid incident_id format")

        val request = CreateIncidentUpdateRequest(
            status = args["status"]?.jsonPrimitive?.content
                ?: return errorResult("status is required"),
            message = args["message"]?.jsonPrimitive?.content
                ?: return errorResult("message is required")
        )
        val result = statusPageSvc.createIncidentUpdate(
            pageUuid, context.organizationId,
            incidentUuid, request
        ) ?: return errorResult("Incident not found")
        return jsonResult(result)
    }
}
