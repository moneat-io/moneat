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

package com.moneat.llm

import com.moneat.enterprise.EnterpriseModule
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueSettings
import com.moneat.llm.routes.llmIngestRoutes
import com.moneat.llm.routes.llmRoutes
import com.moneat.llm.services.LlmDashboardService
import com.moneat.llm.services.LlmIngestionWorker
import io.ktor.server.application.Application
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class LlmModule : EnterpriseModule {
    override val name: String = "LLM"

    private val dashboardService by lazy { LlmDashboardService() }
    private var ingestionWorker: LlmIngestionWorker? = null

    override fun registerRoutes(route: Route) {
        logger.info { "Registering LLM routes..." }
        route.rateLimit(RateLimitName("ingestion")) {
            llmIngestRoutes()
        }
        route.llmRoutes(llmService = dashboardService)
        logger.info { "LLM routes registered" }
    }

    override fun startBackgroundJobs(application: Application) {
        startBackgroundJobs(application, startSchedulers = true, startIngestionWorkers = true)
    }

    override fun startBackgroundJobs(
        application: Application,
        startSchedulers: Boolean,
        startIngestionWorkers: Boolean,
    ) {
        if (!startIngestionWorkers || !IngestionQueueSettings.isSelected(IngestionPipeline.LLM)) {
            return
        }
        if (ingestionWorker != null) {
            return
        }
        val config = application.environment.config
        ingestionWorker = LlmIngestionWorker(
            config.propertyOrNull("llm.queueKey")?.getString() ?: "moneat:llm:queue",
            config.propertyOrNull("llm.dlqKey")?.getString() ?: "moneat:llm:dlq",
            config.propertyOrNull("llm.workerCount")?.getString()?.toIntOrNull() ?: 2,
        ).also { worker -> worker.start() }
    }

    override fun stopBackgroundJobs() {
        ingestionWorker?.stop()
        ingestionWorker = null
    }
}
