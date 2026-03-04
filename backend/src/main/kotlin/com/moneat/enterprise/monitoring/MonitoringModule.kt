// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.monitoring

import com.moneat.enterprise.EnterpriseModule
import com.moneat.monitor.routes.agentApiKeyRoutes
import com.moneat.security.routes.securityRoutes
import com.moneat.synthetics.routes.SyntheticsScheduler
import com.moneat.synthetics.routes.syntheticsRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Enterprise monitoring module that contributes infra/security/synthetics APIs
 * and the synthetics scheduler background job.
 */
class MonitoringModule : EnterpriseModule {
    private lateinit var syntheticsScheduler: SyntheticsScheduler

    override val name: String = "Monitoring"

    override fun registerRoutes(route: Route) {
        route.apply {
            // Note: infraRoutes() is registered in core Routing.kt, not here
            securityRoutes()
            syntheticsRoutes()
            agentApiKeyRoutes()
        }
    }

    override fun startBackgroundJobs(application: Application) {
        logger.info { "Starting monitoring enterprise background jobs" }
        syntheticsScheduler = SyntheticsScheduler()
        syntheticsScheduler.start()
    }

    override fun stopBackgroundJobs() {
        logger.info { "Stopping monitoring enterprise background jobs" }
        if (::syntheticsScheduler.isInitialized) {
            syntheticsScheduler.stop()
        }
    }
}
