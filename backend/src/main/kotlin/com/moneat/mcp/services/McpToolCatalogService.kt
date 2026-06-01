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

package com.moneat.mcp.services

import com.moneat.mcp.models.McpResourceCatalogItem
import com.moneat.mcp.models.McpToolCatalogItem
import com.moneat.mcp.models.McpToolCatalogResponse
import com.moneat.mcp.models.McpToolCatalogSection
import com.moneat.mcp.protocol.McpResourceRegistry
import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.mcp.protocol.ToolDefinition

private data class ToolSection(
    val id: String,
    val label: String,
    val description: String,
)

private val SECTION_ORDER = listOf(
    ToolSection("issues", "Issues", "Investigate and update error groups and user feedback."),
    ToolSection("logs", "Logs", "Search, aggregate, and facet logs."),
    ToolSection("apm", "APM and traces", "Inspect transactions, traces, spans, profiles, and releases."),
    ToolSection("infrastructure", "Infrastructure", "Inspect hosts, containers, Kubernetes, processes, and networks."),
    ToolSection("alerts", "Alerts", "List, create, update, and silence alerts."),
    ToolSection("workflows", "Workflows", "List, author, publish, run, and audit workflows."),
    ToolSection("uptime", "Uptime", "Manage uptime monitors and heartbeats."),
    ToolSection("dashboards", "Dashboards", "List, query, create, and update dashboards."),
    ToolSection("status-pages", "Status pages", "Manage status pages, monitors, and public incidents."),
    ToolSection("projects", "Projects", "List, create, and inspect projects."),
    ToolSection("feature-flags", "Feature flags", "Manage flags, environments, targeting, and SDK keys."),
    ToolSection("on-call", "On-call", "Inspect incidents and schedules."),
    ToolSection("data-sources", "Data sources", "Manage custom data sources and execute data-source queries."),
    ToolSection("summaries", "Summaries and search", "Use summaries, correlated context, and global search."),
)

object McpToolCatalogService {

    fun buildCatalog(
        toolRegistry: McpToolRegistry,
        resourceRegistry: McpResourceRegistry,
    ): McpToolCatalogResponse {
        val toolsBySection = toolRegistry
            .listTools()
            .sortedBy { it.name }
            .groupBy { sectionIdForTool(it.name) }

        val sections = SECTION_ORDER.mapNotNull { section ->
            val tools = toolsBySection[section.id].orEmpty()
            if (tools.isEmpty()) {
                return@mapNotNull null
            }
            McpToolCatalogSection(
                id = section.id,
                label = section.label,
                description = section.description,
                tools = tools.map(::toCatalogTool),
            )
        }

        val resources = resourceRegistry
            .listResources()
            .sortedBy { it.uri }
            .map {
                McpResourceCatalogItem(
                    uri = it.uri,
                    name = it.name,
                    description = it.description,
                    mimeType = it.mimeType,
                )
            }

        return McpToolCatalogResponse(sections = sections, resources = resources)
    }

    private fun toCatalogTool(tool: ToolDefinition): McpToolCatalogItem {
        return McpToolCatalogItem(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.inputSchema,
            readOnly = tool.readOnly,
        )
    }

    private fun sectionIdForTool(name: String): String {
        return when {
            name.contains("workflow") -> "workflows"
            name.contains("issue") || name.contains("feedback") || name.contains("error") -> "issues"
            name.contains("log") -> "logs"
            name.contains("trace") ||
                name.contains("span") ||
                name.contains("transaction") ||
                name.contains("profile") ||
                name.contains("release") -> "apm"
            name.contains("status_page") -> "status-pages"
            name.contains("dashboard") -> "dashboards"
            name.contains("host") ||
                name.contains("container") ||
                name.contains("k8s") ||
                name.contains("process") ||
                name.contains("network") ||
                name.contains("dbm") ||
                name.contains("agent") -> "infrastructure"
            name.contains("alert") || name.contains("silence") || name.contains("notification") -> "alerts"
            name.contains("uptime") || name.contains("heartbeat") || name.contains("monitor") -> "uptime"
            name.contains("project") -> "projects"
            name.contains("feature_flag") -> "feature-flags"
            name.contains("incident") || name.contains("schedule") -> "on-call"
            name.contains("datasource") -> "data-sources"
            else -> "summaries"
        }
    }
}
