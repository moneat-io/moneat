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

package com.moneat.analytics

import com.moneat.analytics.routes.analyticsIngestRoutes
import com.moneat.analytics.routes.analyticsRoutes
import com.moneat.analytics.routes.analyticsServerIngestRoutes
import com.moneat.analytics.routes.AnalyticsIngestRouteDependencies
import com.moneat.analytics.services.AnalyticsIngestionWorker
import com.moneat.analytics.services.AnalyticsService
import com.moneat.analytics.services.SessionHashService
import com.moneat.enterprise.EnterpriseModule
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.runtime.RuntimeMode
import com.moneat.shared.services.GeoIpService
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
    private val analyticsService = AnalyticsService()
    private val sessionHashService = SessionHashService()
    private val geoIpService = GeoIpService()

    override val name: String = "Analytics"

    override fun registerRoutes(route: Route) {
        route.apply {
            analyticsIngestRoutes(
                AnalyticsIngestRouteDependencies().apply {
                    sessionHashService = this@AnalyticsModule.sessionHashService
                    geoIpService = this@AnalyticsModule.geoIpService
                }
            )
            analyticsServerIngestRoutes()
            analyticsRoutes(analyticsService = analyticsService)
        }
    }

    override fun startBackgroundJobs(application: Application) {
        startBackgroundJobs(application, startSchedulers = true, startIngestionWorkers = true)
    }

    override fun startBackgroundJobs(
        application: Application,
        startSchedulers: Boolean,
        startIngestionWorkers: Boolean,
    ) {
        if (!startIngestionWorkers || !RuntimeMode.shouldStartPipeline(IngestionPipeline.ANALYTICS)) {
            logger.info { "Skipping analytics ingestion worker for this process role" }
            return
        }
        logger.info { "Starting analytics enterprise background jobs" }
        ingestionWorker = AnalyticsIngestionWorker()
        ingestionWorker.start()
    }

    override fun stopBackgroundJobs() {
        logger.info { "Stopping analytics enterprise background jobs" }
        if (::ingestionWorker.isInitialized) ingestionWorker.stop()
    }
}
