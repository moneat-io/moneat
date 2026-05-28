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

package com.moneat.mcp

import com.moneat.enterprise.FeatureRegistry
import com.moneat.mcp.protocol.McpResourceRegistry
import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.mcp.resources.ActiveIncidentsResource
import com.moneat.mcp.resources.AlertSilencesResource
import com.moneat.mcp.resources.HostsStatusResource
import com.moneat.mcp.resources.InfrastructureHealthResource
import com.moneat.mcp.resources.OrgOverviewResource
import com.moneat.mcp.resources.ProjectsListResource
import com.moneat.mcp.resources.StatusPagesResource
import com.moneat.mcp.resources.UptimeSummaryResource
import com.moneat.mcp.routes.mcpApiKeyRoutes
import com.moneat.mcp.routes.mcpRoutes
import io.ktor.server.routing.Route
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun interface McpToolContributor {
    fun contributeTools(registry: McpToolRegistry)
}

/**
 * Core MCP module. Licensed modules may contribute tools through [McpToolContributor],
 * but the public MCP endpoint itself is part of the OSS backend.
 */
object McpModule {
    private val toolRegistry: McpToolRegistry by lazy {
        McpToolRegistry().also { registry ->
            McpToolRegistrar.registerAll(registry)
            FeatureRegistry.registeredModules
                .filterIsInstance<McpToolContributor>()
                .forEach { contributor -> contributor.contributeTools(registry) }
            logger.info { "Registered ${registry.listTools().size} MCP tools" }
        }
    }

    private val resourceRegistry: McpResourceRegistry by lazy {
        McpResourceRegistry().also { registry ->
            registry.register(OrgOverviewResource())
            registry.register(ProjectsListResource())
            registry.register(HostsStatusResource())
            registry.register(AlertSilencesResource())
            registry.register(ActiveIncidentsResource())
            registry.register(UptimeSummaryResource())
            registry.register(StatusPagesResource())
            registry.register(InfrastructureHealthResource())
            logger.info { "Registered ${registry.listResources().size} MCP resources" }
        }
    }

    fun registerRoutes(route: Route) {
        route.mcpApiKeyRoutes(toolRegistry, resourceRegistry)
        route.mcpRoutes(toolRegistry, resourceRegistry)
    }
}
