// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog

import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.datadog.routes.datadogDogStatsDRoutes
import com.moneat.enterprise.datadog.routes.datadogEventQueryRoutes
import com.moneat.enterprise.datadog.routes.datadogEventRoutes
import com.moneat.enterprise.datadog.routes.datadogHostRoutes
import com.moneat.enterprise.datadog.routes.datadogInfraQueryRoutes
import com.moneat.enterprise.datadog.routes.datadogInfraRoutes
import com.moneat.enterprise.datadog.routes.datadogLogRoutes
import com.moneat.enterprise.datadog.routes.datadogMetricRoutes
import com.moneat.enterprise.datadog.routes.datadogRoutes
import com.moneat.enterprise.datadog.routes.datadogValidateRoutes
import com.moneat.enterprise.datadog.routes.dbmIngestRoutes
import com.moneat.enterprise.datadog.routes.debuggerIngestRoutes
import com.moneat.enterprise.datadog.routes.debuggerProbeRoutes
import com.moneat.enterprise.datadog.routes.miscIngestRoutes
import com.moneat.enterprise.datadog.routes.orchestratorIngestRoutes
import com.moneat.enterprise.datadog.routes.profileDashboardRoutes
import com.moneat.enterprise.datadog.routes.profileIngestRoutes
import com.moneat.enterprise.datadog.routes.telemetryProxyRoutes
import com.moneat.enterprise.datadog.routes.traceDashboardRoutes
import com.moneat.enterprise.datadog.routes.traceIngestRoutes
import com.moneat.enterprise.datadog.networkdevices.ndmIngestRoutes
import com.moneat.enterprise.datadog.networkdevices.ndmQueryRoutes
import com.moneat.enterprise.datadog.networkdevices.NdmIngestionWorker
import com.moneat.enterprise.datadog.security.securityIngestRoutes
import com.moneat.enterprise.datadog.security.securityQueryRoutes
import com.moneat.enterprise.datadog.security.SecurityIngestionWorker
import com.moneat.enterprise.datadog.services.ProfileStorageService
import com.moneat.enterprise.datadog.workers.DatadogEventIngestionWorker
import com.moneat.enterprise.datadog.workers.DatadogInfraIngestionWorker
import com.moneat.enterprise.datadog.workers.DatadogMetricIngestionWorker
import com.moneat.enterprise.datadog.workers.DbmIngestionWorker
import com.moneat.enterprise.datadog.workers.DebuggerIngestionWorker
import com.moneat.enterprise.datadog.workers.MiscIngestionWorker
import com.moneat.enterprise.datadog.workers.OrchestratorIngestionWorker
import com.moneat.enterprise.datadog.workers.TraceIngestionWorker
import io.ktor.server.application.Application
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
            // Phase 1: Foundation
            datadogValidateRoutes()
            // Phase 2: Logs & Metrics
            datadogLogRoutes()
            datadogMetricRoutes()
            datadogDogStatsDRoutes()
            // Phase 3-4: Traces & Profiles
            traceIngestRoutes()
            profileIngestRoutes()
            traceDashboardRoutes()
            profileDashboardRoutes()
            // Phase 5: Events & Hosts
            datadogEventRoutes()
            datadogHostRoutes()
            datadogEventQueryRoutes()
            // Phase 6: Infrastructure
            datadogInfraRoutes()
            datadogInfraQueryRoutes()
            // Phase 7: Advanced
            orchestratorIngestRoutes()
            dbmIngestRoutes()
            debuggerIngestRoutes()
            debuggerProbeRoutes()
            telemetryProxyRoutes()
            // Phase 8: Misc (symdb, pipeline, lineage, streams, synthetics, images, sbom)
            miscIngestRoutes()
            // Phase 9: Network Devices
            ndmIngestRoutes()
            ndmQueryRoutes()
            // Phase 10: Security
            securityIngestRoutes()
            securityQueryRoutes()
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
