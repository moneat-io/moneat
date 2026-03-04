// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.analytics

import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.analytics.routes.analyticsIngestRoutes
import com.moneat.enterprise.analytics.routes.analyticsRoutes
import com.moneat.enterprise.analytics.services.AnalyticsIngestionWorker
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Enterprise module for privacy-focused product analytics.
 * Provides cookie-free web analytics with ClickHouse-backed storage.
 */
class AnalyticsModule : EnterpriseModule {
    private lateinit var ingestionWorker: AnalyticsIngestionWorker

    override val name: String = "Analytics"

    override fun registerRoutes(route: Route) {
        route.apply {
            analyticsIngestRoutes()
            analyticsRoutes()
        }
    }

    override fun startBackgroundJobs(application: Application) {
        logger.info { "Starting analytics enterprise background jobs" }
        ingestionWorker = AnalyticsIngestionWorker()
        ingestionWorker.start()
    }

    override fun stopBackgroundJobs() {
        logger.info { "Stopping analytics enterprise background jobs" }
        ingestionWorker.stop()
    }
}
