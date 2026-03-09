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

package com.moneat.datadog

import com.moneat.enterprise.EnterpriseModule
import com.moneat.datadog.routes.datadogDogStatsDRoutes
import com.moneat.datadog.routes.datadogEventQueryRoutes
import com.moneat.datadog.routes.datadogEventRoutes
import com.moneat.datadog.routes.datadogHostIngestRoutes
import com.moneat.datadog.routes.datadogHostQueryRoutes
import com.moneat.datadog.routes.datadogInfraQueryRoutes
import com.moneat.datadog.routes.datadogInfraRoutes
import com.moneat.datadog.routes.datadogLogRoutes
import com.moneat.datadog.routes.datadogMetricRoutes
import com.moneat.datadog.routes.datadogValidateRoutes
import com.moneat.datadog.routes.dbmIngestRoutes
import com.moneat.datadog.routes.debuggerIngestRoutes
import com.moneat.datadog.routes.debuggerProbeRoutes
import com.moneat.datadog.routes.miscIngestRoutes
import com.moneat.datadog.routes.orchestratorIngestRoutes
import com.moneat.datadog.routes.profileDashboardRoutes
import com.moneat.datadog.routes.profileIngestRoutes
import com.moneat.datadog.routes.telemetryProxyRoutes
import com.moneat.datadog.routes.traceDashboardRoutes
import com.moneat.datadog.routes.traceIngestRoutes
import com.moneat.datadog.networkdevices.ndmIngestRoutes
import com.moneat.datadog.networkdevices.ndmQueryRoutes
import com.moneat.datadog.networkdevices.NdmIngestionWorker
import com.moneat.datadog.security.securityIngestRoutes
import com.moneat.datadog.security.SecurityIngestionWorker
import com.moneat.datadog.services.ProfileStorageService
import com.moneat.datadog.workers.DatadogEventIngestionWorker
import com.moneat.datadog.workers.DatadogInfraIngestionWorker
import com.moneat.datadog.workers.DatadogMetricIngestionWorker
import com.moneat.datadog.workers.DbmIngestionWorker
import com.moneat.datadog.workers.DebuggerIngestionWorker
import com.moneat.datadog.workers.MiscIngestionWorker
import com.moneat.datadog.workers.OrchestratorIngestionWorker
import com.moneat.datadog.workers.TraceIngestionWorker
import io.ktor.server.application.Application
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val METRICS_QUEUE = "moneat:metrics:queue"
private const val METRICS_DLQ = "moneat:metrics:dlq"
private const val EVENTS_QUEUE = "moneat:infra_events:queue"
private const val EVENTS_DLQ = "moneat:infra_events:dlq"
private const val INFRA_QUEUE = "moneat:infra:queue"
private const val INFRA_DLQ = "moneat:infra:dlq"
private const val MISC_QUEUE = "moneat:dd:misc:queue"
private const val MISC_DLQ = "moneat:dd:misc:dlq"
private const val WORKER_COUNT_2 = 2
private const val WORKER_COUNT_1 = 1

class DatadogModule : EnterpriseModule {
    private lateinit var traceWorker: TraceIngestionWorker
    private lateinit var orchestratorWorker: OrchestratorIngestionWorker
    private lateinit var dbmWorker: DbmIngestionWorker
    private lateinit var debuggerWorker: DebuggerIngestionWorker
    private var metricWorker: DatadogMetricIngestionWorker? = null
    private var eventWorker: DatadogEventIngestionWorker? = null
    private var infraWorker: DatadogInfraIngestionWorker? = null
    private var miscWorker: MiscIngestionWorker? = null
    private var ndmWorker: NdmIngestionWorker? = null
    private var securityWorker: SecurityIngestionWorker? = null

    override val name: String = "Datadog"

    override fun registerRoutes(route: Route) {
        route.apply {
            datadogValidateRoutes()

            // Ingest routes rate-limited per DD API key
            rateLimit(RateLimitName("datadog-ingestion")) {
                datadogLogRoutes()
                datadogMetricRoutes()
                datadogDogStatsDRoutes()
                traceIngestRoutes()
                profileIngestRoutes()
                datadogEventRoutes()
                datadogHostIngestRoutes()
                datadogInfraRoutes()
                orchestratorIngestRoutes()
                dbmIngestRoutes()
                debuggerIngestRoutes()
                telemetryProxyRoutes()
                miscIngestRoutes()
                ndmIngestRoutes()
                securityIngestRoutes()
            }

            // Dashboard / query routes (JWT-protected, not agent ingest)
            traceDashboardRoutes()
            profileDashboardRoutes()
            datadogEventQueryRoutes()
            datadogHostQueryRoutes()
            datadogInfraQueryRoutes()
            debuggerProbeRoutes()
            ndmQueryRoutes()
        }
    }

    override fun startBackgroundJobs(application: Application) {
        logger.info { "Starting Datadog enterprise background jobs" }
        ProfileStorageService.init()
        traceWorker = TraceIngestionWorker()
        traceWorker.start()
        orchestratorWorker = OrchestratorIngestionWorker()
        orchestratorWorker.start()
        dbmWorker = DbmIngestionWorker()
        dbmWorker.start()
        debuggerWorker = DebuggerIngestionWorker()
        debuggerWorker.start()
        metricWorker = DatadogMetricIngestionWorker(
            METRICS_QUEUE, METRICS_DLQ, WORKER_COUNT_2
        )
        metricWorker?.start()
        eventWorker = DatadogEventIngestionWorker(
            EVENTS_QUEUE, EVENTS_DLQ, WORKER_COUNT_2
        )
        eventWorker?.start()
        infraWorker = DatadogInfraIngestionWorker(
            INFRA_QUEUE, INFRA_DLQ, WORKER_COUNT_1
        )
        infraWorker?.start()
        miscWorker = MiscIngestionWorker(
            MISC_QUEUE, MISC_DLQ, WORKER_COUNT_1
        )
        miscWorker?.start()
        ndmWorker = NdmIngestionWorker()
        ndmWorker?.start()
        securityWorker = SecurityIngestionWorker()
        securityWorker?.start()
    }

    override fun stopBackgroundJobs() {
        logger.info { "Stopping Datadog enterprise background jobs" }
        if (::traceWorker.isInitialized) traceWorker.stop()
        if (::orchestratorWorker.isInitialized) orchestratorWorker.stop()
        if (::dbmWorker.isInitialized) dbmWorker.stop()
        if (::debuggerWorker.isInitialized) debuggerWorker.stop()
        metricWorker?.stop()
        eventWorker?.stop()
        infraWorker?.stop()
        miscWorker?.stop()
        ndmWorker?.stop()
        securityWorker?.stop()
    }
}
