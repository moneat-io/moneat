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

import com.moneat.mcp.models.McpContext
import com.moneat.mcp.auth.McpScopes
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpResource
import com.moneat.mcp.protocol.McpResourceRegistry
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.mcp.protocol.ResourceContent
import com.moneat.mcp.protocol.ToolCallResult
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpToolCatalogServiceTest {

    @Test
    fun `buildCatalog groups tools by product section in display order`() {
        val toolRegistry = McpToolRegistry()
        listOf(
            "get_issue",
            "aggregate_logs",
            "search_logs",
            "get_trace",
            "list_hosts",
            "list_alerts",
            "list_workflows",
            "check_uptime",
            "get_dashboard",
            "list_status_pages",
            "list_projects",
            "list_security_signals",
            "list_incidents",
            "test_datasource",
            "summarize_org",
        ).forEach { toolRegistry.register(CatalogTool(it)) }
        val resourceRegistry = McpResourceRegistry().also {
            it.register(CatalogResource("moneat://z-resource"))
            it.register(CatalogResource("moneat://a-resource"))
        }

        val catalog = McpToolCatalogService.buildCatalog(toolRegistry, resourceRegistry)

        assertEquals(
            listOf(
                "issues",
                "logs",
                "apm",
                "infrastructure",
                "alerts",
                "workflows",
                "uptime",
                "dashboards",
                "status-pages",
                "projects",
                "security",
                "on-call",
                "data-sources",
                "summaries",
            ),
            catalog.sections.map { it.id },
        )
        assertEquals(
            listOf("aggregate_logs", "search_logs"),
            catalog.sections.single { it.id == "logs" }.tools.map { it.name },
        )
        assertTrue(catalog.sections.single { it.id == "issues" }.tools.single().readOnly)
        assertEquals(listOf("moneat://a-resource", "moneat://z-resource"), catalog.resources.map { it.uri })
        assertEquals("application/json", catalog.resources.first().mimeType)
    }

    @Test
    fun `buildCatalog returns empty sections and resources for empty registries`() {
        val catalog = McpToolCatalogService.buildCatalog(McpToolRegistry(), McpResourceRegistry())

        assertTrue(catalog.sections.isEmpty())
        assertTrue(catalog.resources.isEmpty())
    }
}

private class CatalogTool(override val name: String) : McpTool {
    override val description: String = "$name description"
    override val inputSchema: InputSchema = InputSchema()
    override val readOnly: Boolean = !name.startsWith("update")
    override val requiredScopes: Set<String> = setOf(McpScopes.ORG_READ)

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult =
        ToolCallResult(emptyList())
}

private class CatalogResource(override val uri: String) : McpResource {
    override val name: String = "$uri name"
    override val description: String = "$uri description"

    override suspend fun read(context: McpContext): ResourceContent =
        ResourceContent(uri = uri, text = "{}")
}
