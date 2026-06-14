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

package com.moneat.otlp

import com.moneat.enterprise.EnterpriseModule
import com.moneat.events.services.EventService
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueSettings
import com.moneat.otlp.routes.otlpFeedbackRoutes
import com.moneat.otlp.routes.otlpMetricsRoutes
import com.moneat.otlp.routes.otlpTraceRoutes
import com.moneat.otlp.services.OtlpMetricsIngestionWorker
import com.moneat.otlp.services.OtlpTraceIngestionWorker
import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext

private const val DEFAULT_OTLP_TRACES_QUEUE_KEY = "moneat:otlp-traces:queue"
private const val DEFAULT_OTLP_TRACES_DLQ_KEY = "moneat:otlp-traces:dlq"
private const val DEFAULT_OTLP_METRICS_QUEUE_KEY = "moneat:otlp-metrics:queue"
private const val DEFAULT_OTLP_METRICS_DLQ_KEY = "moneat:otlp-metrics:dlq"
private const val DEFAULT_OTLP_WORKER_COUNT = 2

class OtlpModule : EnterpriseModule {
    override val name: String = "OTLP"

    private var traceWorker: OtlpTraceIngestionWorker? = null
    private var metricsWorker: OtlpMetricsIngestionWorker? = null

    override fun registerRoutes(route: Route) {
        route.rateLimit(RateLimitName("otlp-ingestion")) {
            otlpTraceRoutes()
            otlpMetricsRoutes()
            otlpFeedbackRoutes()
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
        if (!startIngestionWorkers) {
            return
        }
        val startTraces = IngestionQueueSettings.isSelected(IngestionPipeline.OTLP_TRACES)
        val startMetrics = IngestionQueueSettings.isSelected(IngestionPipeline.OTLP_METRICS)
        if (!startTraces && !startMetrics) {
            return
        }

        val config = application.environment.config
        val koin = GlobalContext.get()
        if (startTraces && traceWorker == null) {
            traceWorker = OtlpTraceIngestionWorker(
                queueKey = config.nonBlankConfigValue("otlp.tracesQueueKey", DEFAULT_OTLP_TRACES_QUEUE_KEY),
                dlqKey = config.nonBlankConfigValue("otlp.tracesDlqKey", DEFAULT_OTLP_TRACES_DLQ_KEY),
                workerCount = config.positiveWorkerCount("otlp.tracesWorkerCount", DEFAULT_OTLP_WORKER_COUNT),
                eventService = koin.get<EventService>(),
            ).also { worker ->
                worker.start()
            }
        }
        if (startMetrics && metricsWorker == null) {
            metricsWorker = OtlpMetricsIngestionWorker(
                queueKey = config.nonBlankConfigValue("otlp.metricsQueueKey", DEFAULT_OTLP_METRICS_QUEUE_KEY),
                dlqKey = config.nonBlankConfigValue("otlp.metricsDlqKey", DEFAULT_OTLP_METRICS_DLQ_KEY),
                workerCount = config.positiveWorkerCount("otlp.metricsWorkerCount", DEFAULT_OTLP_WORKER_COUNT),
            ).also { worker ->
                worker.start()
            }
        }
    }

    override fun stopBackgroundJobs() {
        runBlocking {
            traceWorker?.stop()
            metricsWorker?.stop()
        }
        traceWorker = null
        metricsWorker = null
    }
}

private fun ApplicationConfig.nonBlankConfigValue(
    path: String,
    defaultValue: String,
): String {
    val value = propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() } ?: defaultValue
    require(value.isNotBlank()) { "$path must not be blank" }
    return value
}

private fun ApplicationConfig.positiveWorkerCount(
    path: String,
    defaultValue: Int,
): Int {
    val value = propertyOrNull(path)?.getString()?.toIntOrNull() ?: defaultValue
    require(value > 0) { "$path must be greater than 0" }
    return value
}
