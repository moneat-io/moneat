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

package com.moneat.logs.services

import com.moneat.config.RedisConfig
import com.moneat.ingestion.queue.IngestionDlqRequest
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.ingestion.queue.IngestionQueueSettings
import com.moneat.ingestion.queue.RedisQueueWorker
import com.moneat.logs.repositories.LogRepositoryImpl
import com.moneat.monitoring.OperationalMetrics
import com.moneat.utils.suspendRunCatching
import mu.KotlinLogging
import kotlin.random.Random

private val logger = KotlinLogging.logger {}
private const val FULL_SAMPLING_RATE = 1.0f

class LogIngestionWorker(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int,
    private val logService: LogService = LogService(LogRepositoryImpl()),
    private val logIndexService: LogIndexService = LogIndexService(),
    private val logManagementService: LogManagementService = LogManagementService(),
) {
    private val filterEvaluator = LogEntryFilterEvaluator()
    private var queueWorker: RedisQueueWorker? = null

    fun start() {
        logger.info { "Starting LogIngestionWorker with $workerCount workers, queue=$queueKey" }
        val spec = IngestionQueueSettings.spec(IngestionPipeline.LOGS, queueKey, dlqKey, workerCount)
        queueWorker = RedisQueueWorker(spec, logger, processMessage = { workerId, payload ->
            processMessageForTest(workerId, payload) { message ->
                IngestionQueueClient.pushToDlq(
                    logger = logger,
                    request = IngestionDlqRequest(
                        spec = spec,
                        payload = message,
                        workerId = workerId,
                        cause = IllegalStateException("Log processing failed"),
                    ),
                )
            }
        }).also { it.start() }
    }

    fun stop() {
        queueWorker?.stop()
        logger.info { "LogIngestionWorker stopped" }
    }

    internal suspend fun processMessageForTest(
        workerId: Int,
        payload: String,
        onDlq: (String) -> Unit = { message -> RedisConfig.sync().rpush(dlqKey, message) }
    ) {
        suspendRunCatching {
            val batch = logService.decodeQueueMessage(payload)
            val orgId = batch.effectiveOrganizationId
            val indexes = if (orgId in Int.MIN_VALUE..Int.MAX_VALUE) {
                logIndexService.getActiveIndexesCached(orgId.toInt())
            } else {
                emptyList()
            }
            val pipelines = if (orgId in Int.MIN_VALUE..Int.MAX_VALUE) {
                logManagementService.getActivePipelinesCached(orgId.toInt())
            } else {
                emptyList()
            }
            val processedLogs = logManagementService.applyPipelines(batch.logs, pipelines)

            val taggedLogs = if (indexes.isEmpty()) {
                processedLogs
            } else {
                processedLogs.mapNotNull { entry ->
                    applyIndexRouting(entry, indexes)
                }
            }
            val quotaFilteredLogs = if (orgId in Int.MIN_VALUE..Int.MAX_VALUE) {
                logIndexService.filterWithinDailyQuota(orgId.toInt(), taggedLogs, indexes)
            } else {
                taggedLogs
            }

            if (quotaFilteredLogs.isEmpty()) return

            val taggedBatch = batch.copy(logs = quotaFilteredLogs)
            val inserted = logService.insertBatch(taggedBatch)
            logService.publishLiveLogs(orgId, inserted)
            OperationalMetrics.recordWorkerMessageProcessed("Log", workerId)
        }.getOrElse { e ->
            logger.error(e) { "Log worker $workerId failed to process message, pushing to DLQ" }
            OperationalMetrics.recordWorkerProcessingFailure("Log", workerId, e)
            suspendRunCatching {
                onDlq(payload)
            }.onSuccess {
                OperationalMetrics.recordDlqPush("Log", dlqKey, "success")
            }.onFailure { dlqErr ->
                OperationalMetrics.recordDlqPush("Log", dlqKey, "failure")
                logger.error(dlqErr) { "Failed to push log message to DLQ" }
            }.getOrThrow()
        }
    }

    /**
     * Match a log entry against indexes and apply sampling.
     * Returns null if the entry should be dropped by sampling.
     */
    private fun applyIndexRouting(
        entry: com.moneat.logs.models.QueuedLogEntry,
        indexes: List<com.moneat.logs.models.LogIndexResponse>
    ): com.moneat.logs.models.QueuedLogEntry? {
        val entryMap = mapOf(
            "level" to entry.level,
            "message" to entry.message,
            "body" to entry.body,
            "service" to entry.service,
            "environment" to entry.environment,
            "host" to entry.host,
            "source" to entry.source,
            "container_name" to entry.containerName,
            "container_id" to entry.containerId,
            "container_image" to entry.containerImage,
            "trace_id" to entry.traceId,
            "span_id" to entry.spanId
        ) + entry.tags + entry.resourceAttributes

        for (index in indexes) {
            val matches = if (index.filterQuery.isBlank()) {
                true
            } else {
                suspendRunCatching {
                    filterEvaluator.matches(index.filterQuery, entryMap)
                }.getOrElse { _ ->
                    false
                }
            }

            if (matches) {
                if (index.samplingRate < FULL_SAMPLING_RATE &&
                    Random.nextFloat() > index.samplingRate
                ) {
                    return null
                }
                return entry.copy(indexName = index.name)
            }
        }
        return entry
    }
}
