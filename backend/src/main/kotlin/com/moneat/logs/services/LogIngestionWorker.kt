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

import com.moneat.config.BRPOP_TIMEOUT_SECONDS
import com.moneat.config.RedisConfig
import com.moneat.logs.repositories.LogRepositoryImpl
import com.moneat.monitoring.OperationalMetrics
import com.moneat.utils.brpopLoopBackoff
import com.moneat.utils.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import mu.KotlinLogging
import java.io.IOException
import kotlin.random.Random

private val logger = KotlinLogging.logger {}
private const val FULL_SAMPLING_RATE = 1.0f
private const val ERROR_DELAY_MS = 1000L

class LogIngestionWorker(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int,
    private val logService: LogService = LogService(LogRepositoryImpl()),
    private val logIndexService: LogIndexService = LogIndexService(),
    private val logManagementService: LogManagementService = LogManagementService(),
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        logger.info { "Starting LogIngestionWorker with $workerCount workers, queue=$queueKey" }
        OperationalMetrics.registerWorkerQueues("Log", queueKey, dlqKey)
        jobs =
            (1..workerCount).map { workerId ->
                scope.launch {
                    runWorker(workerId)
                }
            }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        logger.info { "LogIngestionWorker stopped" }
    }

    private suspend fun runWorker(workerId: Int) {
        val conn = RedisConfig.newBlockingConnection()
        try {
            val redis = conn.sync()
            while (scope.isActive) {
                try {
                    val result = redis.brpop(BRPOP_TIMEOUT_SECONDS, queueKey)
                    val payload = result?.value ?: continue
                    processMessageForTest(workerId, payload)
                } catch (e: CancellationException) {
                    break
                } catch (e: SerializationException) {
                    brpopLoopBackoff(logger, workerId, "Log", ERROR_DELAY_MS, e)
                } catch (e: IOException) {
                    brpopLoopBackoff(logger, workerId, "Log", ERROR_DELAY_MS, e)
                } catch (e: IllegalStateException) {
                    brpopLoopBackoff(logger, workerId, "Log", ERROR_DELAY_MS, e)
                } catch (e: IllegalArgumentException) {
                    brpopLoopBackoff(logger, workerId, "Log", ERROR_DELAY_MS, e)
                }
            }
        } finally {
            RedisConfig.closeBlockingConnection(conn)
        }
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

        val filterEvaluator = LogEntryFilterEvaluator()
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
