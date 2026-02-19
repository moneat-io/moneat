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

package com.moneat.plugins

import com.moneat.enterprise.FeatureRegistry
import com.moneat.services.BillingBackgroundService
import com.moneat.services.IngestionWorker
import com.moneat.services.LlmIngestionWorker
import com.moneat.services.LogIngestionWorker
import com.moneat.services.MonitorAlertService
import com.moneat.services.RefreshTokenCleanupService
import com.moneat.services.RetentionBackgroundService
import com.moneat.services.UptimeScheduler
import io.ktor.server.application.*
import io.ktor.events.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun Application.configureBackgroundJobs() {
    val monitorAlertService = MonitorAlertService()
    val billingBackgroundService = BillingBackgroundService()
    val retentionBackgroundService = RetentionBackgroundService()
    val refreshTokenCleanupService = RefreshTokenCleanupService()
    val uptimeScheduler = UptimeScheduler()
    val queueKey = environment.config.property("ingest.queueKey").getString()
    val dlqKey = environment.config.property("ingest.dlqKey").getString()
    val workerCount = environment.config.property("ingest.workerCount").getString().toInt()
    val ingestionWorker = IngestionWorker(queueKey, dlqKey, workerCount)
    val logQueueKey = environment.config.propertyOrNull("logs.queueKey")?.getString() ?: "moneat:logs:queue"
    val logDlqKey = environment.config.propertyOrNull("logs.dlqKey")?.getString() ?: "moneat:logs:dlq"
    val logWorkerCount = environment.config.propertyOrNull("logs.workerCount")?.getString()?.toIntOrNull() ?: 2
    val logIngestionWorker = LogIngestionWorker(logQueueKey, logDlqKey, logWorkerCount)
    val llmQueueKey = environment.config.propertyOrNull("llm.queueKey")?.getString() ?: "moneat:llm:queue"
    val llmDlqKey = environment.config.propertyOrNull("llm.dlqKey")?.getString() ?: "moneat:llm:dlq"
    val llmWorkerCount = environment.config.propertyOrNull("llm.workerCount")?.getString()?.toIntOrNull() ?: 2
    val llmIngestionWorker = LlmIngestionWorker(llmQueueKey, llmDlqKey, llmWorkerCount)

    // Create a coroutine scope for background jobs
    val jobScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Start core background jobs
    logger.info { "Starting background jobs" }
    monitorAlertService.start(jobScope)
    billingBackgroundService.start(jobScope)
    retentionBackgroundService.start(jobScope)
    refreshTokenCleanupService.start(jobScope)
    uptimeScheduler.start()
    ingestionWorker.start()
    logIngestionWorker.start()
    llmIngestionWorker.start()

    // Start enterprise background jobs (SSO, On-Call, etc.) if modules are present
    FeatureRegistry.startBackgroundJobs(this)

    // Register shutdown hook
    monitor.subscribe(ApplicationStopping) {
        monitorAlertService.stop()
        billingBackgroundService.stop()
        retentionBackgroundService.stop()
        refreshTokenCleanupService.stop()
        uptimeScheduler.stop()
        ingestionWorker.stop()
        logIngestionWorker.stop()
        llmIngestionWorker.stop()
        FeatureRegistry.stopBackgroundJobs()
    }
}
