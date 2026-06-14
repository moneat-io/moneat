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

package com.moneat.logs

import com.moneat.enterprise.EnterpriseModule
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueSettings
import com.moneat.logs.routes.logIngestRoutes
import com.moneat.logs.routes.logRoutes
import com.moneat.logs.services.LogIngestionWorker
import io.ktor.server.application.Application
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route
import org.koin.core.context.GlobalContext

class LogsModule : EnterpriseModule {
    override val name: String = "Logs"

    private var logIngestionWorker: LogIngestionWorker? = null

    override fun registerRoutes(route: Route) {
        route.rateLimit(RateLimitName("log-ingestion")) {
            logIngestRoutes()
        }
        route.logRoutes()
    }

    override fun startBackgroundJobs(application: Application) {
        startBackgroundJobs(application, startSchedulers = true, startIngestionWorkers = true)
    }

    override fun startBackgroundJobs(
        application: Application,
        startSchedulers: Boolean,
        startIngestionWorkers: Boolean,
    ) {
        if (!startIngestionWorkers ||
            !IngestionQueueSettings.isSelected(IngestionPipeline.LOGS) ||
            logIngestionWorker != null
        ) {
            return
        }

        val config = application.environment.config
        val koin = GlobalContext.get()
        logIngestionWorker = LogIngestionWorker(
            config.propertyOrNull("logs.queueKey")?.getString() ?: "moneat:logs:queue",
            config.propertyOrNull("logs.dlqKey")?.getString() ?: "moneat:logs:dlq",
            config.propertyOrNull("logs.workerCount")?.getString()?.toIntOrNull() ?: 2,
            logService = koin.get(),
            logIndexService = koin.get(),
        ).also { worker ->
            worker.start()
        }
    }

    override fun stopBackgroundJobs() {
        logIngestionWorker?.stop()
        logIngestionWorker = null
    }
}
