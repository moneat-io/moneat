// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp

import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.mcp.protocol.McpResourceRegistry
import com.moneat.enterprise.mcp.protocol.McpToolRegistry
import com.moneat.enterprise.mcp.resources.ActiveIncidentsResource
import com.moneat.enterprise.mcp.resources.AlertSilencesResource
import com.moneat.enterprise.mcp.resources.HostsStatusResource
import com.moneat.enterprise.mcp.resources.InfrastructureHealthResource
import com.moneat.enterprise.mcp.resources.OrgOverviewResource
import com.moneat.enterprise.mcp.resources.ProjectsListResource
import com.moneat.enterprise.mcp.resources.StatusPagesResource
import com.moneat.enterprise.mcp.resources.UptimeSummaryResource
import com.moneat.enterprise.mcp.protocol.McpTransport
import com.moneat.enterprise.mcp.routes.mcpRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * MCP (Model Context Protocol) enterprise module.
 * Exposes Moneat's observability platform via MCP,
 * enabling AI agents to act as SREs.
 */
class McpModule : EnterpriseModule {

    override val name: String = "MCP"

    private val toolRegistry = McpToolRegistry()
    private val resourceRegistry = McpResourceRegistry()
    private val requestScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun registerRoutes(route: Route) {
        registerTools()
        registerResources()
        route.mcpRoutes(toolRegistry, resourceRegistry, requestScope)
    }

    override fun startBackgroundJobs(application: Application) {
        logger.info { "MCP server module started" }
    }

    override fun stopBackgroundJobs() {
        requestScope.cancel()
        McpTransport.shutdown()
        logger.info { "MCP server module stopped" }
    }

    private fun registerTools() {
        McpToolRegistrar.registerAll(toolRegistry)

        logger.info {
            "Registered ${toolRegistry.listTools().size} MCP tools"
        }
    }

    private fun registerResources() {
        resourceRegistry.register(OrgOverviewResource())
        resourceRegistry.register(ProjectsListResource())
        resourceRegistry.register(HostsStatusResource())
        resourceRegistry.register(AlertSilencesResource())
        resourceRegistry.register(ActiveIncidentsResource())
        resourceRegistry.register(UptimeSummaryResource())
        resourceRegistry.register(StatusPagesResource())
        resourceRegistry.register(InfrastructureHealthResource())

        logger.info {
            "Registered ${resourceRegistry.listResources().size} " +
                "MCP resources"
        }
    }
}
