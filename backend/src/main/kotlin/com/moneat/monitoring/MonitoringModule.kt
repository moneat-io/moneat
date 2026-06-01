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

package com.moneat.monitoring

import com.moneat.enterprise.EnterpriseModule
import com.moneat.monitor.routes.agentApiKeyRoutes
import com.moneat.synthetics.routes.SyntheticsScheduler
import com.moneat.synthetics.routes.syntheticsRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Enterprise monitoring module that contributes agent/synthetics APIs and the
 * synthetics scheduler background job.
 */
class MonitoringModule : EnterpriseModule {
    private lateinit var syntheticsScheduler: SyntheticsScheduler

    override val name: String = "Monitoring"

    override fun registerRoutes(route: Route) {
        route.apply {
            // Note: infraRoutes() is registered in core Routing.kt, not here
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
