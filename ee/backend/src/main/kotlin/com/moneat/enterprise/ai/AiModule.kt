// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai

import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.ai.llm.LlmProviderFactory
import com.moneat.enterprise.ai.routes.aiAssistantRoutes
import com.moneat.enterprise.ai.routes.aiEnterpriseRoutes
import com.moneat.enterprise.ai.services.AiContextAggregator
import com.moneat.enterprise.ai.services.AiAssistantService
import com.moneat.enterprise.ai.services.AiContextSnapshotService
import com.moneat.enterprise.ai.services.EnterpriseAiChatService
import com.moneat.mcp.McpToolRegistrar
import com.moneat.mcp.protocol.McpToolRegistry
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Enterprise AI module providing:
 * - Provider-agnostic LLM integration (OpenAI / Anthropic)
 * - Server-side context aggregation from ClickHouse
 * - SSE streaming chat with cost tracking
 * - Context snapshot storage with TTL cleanup
 */
class AiModule : EnterpriseModule {

    override val name: String = "AI"

    private val snapshotService = AiContextSnapshotService()

    override fun registerRoutes(route: Route) {
        val llmProvider = LlmProviderFactory.create()
        val contextAggregator = AiContextAggregator()
        val chatService = EnterpriseAiChatService(llmProvider, contextAggregator, snapshotService)
        val toolRegistry = McpToolRegistry().apply { McpToolRegistrar.registerAll(this) }
        val assistantService = AiAssistantService(toolRegistry)

        route.apply {
            aiEnterpriseRoutes(chatService)
            aiAssistantRoutes(assistantService)
        }
    }

    override fun startBackgroundJobs(application: Application) {
        logger.info { "Starting AI enterprise background jobs" }
        snapshotService.startCleanupJob()
    }

    override fun stopBackgroundJobs() {
        logger.info { "Stopping AI enterprise background jobs" }
        snapshotService.stopCleanupJob()
    }
}
