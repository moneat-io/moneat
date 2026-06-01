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

package com.moneat.datadog.workers

import com.moneat.config.EnvConfig
import com.moneat.config.RedisConfig
import com.moneat.datadog.services.DatadogMetricService
import com.moneat.datadog.services.QueuedMetricBatch
import com.moneat.monitoring.OperationalMetrics
import com.moneat.utils.brpopLoopBackoff
import com.moneat.utils.pushToDlq
import com.moneat.utils.suspendRunCatching
import io.lettuce.core.RedisException
import io.lettuce.core.api.sync.RedisCommands
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

private val logger = KotlinLogging.logger {}

private const val COMBINED_INSERT_MODE = "combined"
private const val SINGLE_INSERT_MODE = "single"
private const val SUCCESS_STATUS = "success"
private const val FAILURE_STATUS = "failure"
private const val BRPOP_TIMEOUT_SECONDS = 5L
private const val ERROR_DELAY_MS = 1000L
private const val WORKER_NAME = "DD metric"
private const val DEFAULT_MAX_ROWS = 20_000
private const val DEFAULT_MAX_PAYLOADS = 100
private const val MAX_ROWS_ENV = "DD_METRIC_BATCH_MAX_ROWS"
private const val MAX_PAYLOADS_ENV = "DD_METRIC_BATCH_MAX_PAYLOADS"

private data class DecodedMetricPayload(
    val originalPayload: String,
    val batch: QueuedMetricBatch,
)

class DatadogMetricIngestionWorker(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int,
    private val processingQueueKey: String = "$queueKey:processing",
    private val maxRows: Int = configuredPositiveInt(MAX_ROWS_ENV, DEFAULT_MAX_ROWS),
    private val maxPayloads: Int = configuredPositiveInt(MAX_PAYLOADS_ENV, DEFAULT_MAX_PAYLOADS),
) {
    private val scope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        registerQueues()
        recoverProcessingQueue()
        logger.info {
            "Starting DatadogMetricIngestionWorker with " +
                "$workerCount workers, queue=$queueKey"
        }
        jobs = (1..workerCount).map { workerId ->
            scope.launch {
                runWorker(workerId)
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        logger.info { "DatadogMetricIngestionWorker stopped" }
    }

    private suspend fun runWorker(workerId: Int) {
        val conn = RedisConfig.newBlockingConnection()
        try {
            val redis = conn.sync()
            while (scope.isActive) {
                try {
                    val payload = redis.brpoplpush(
                        BRPOP_TIMEOUT_SECONDS,
                        queueKey,
                        processingQueueKey,
                    ) ?: continue
                    processPayloads(
                        workerId,
                        collectPayloadsForProcessing(redis, payload)
                    )
                } catch (e: CancellationException) {
                    break
                } catch (e: RedisException) {
                    brpopLoopBackoff(
                        logger,
                        workerId,
                        WORKER_NAME,
                        ERROR_DELAY_MS,
                        e,
                    )
                } catch (e: IOException) {
                    brpopLoopBackoff(
                        logger,
                        workerId,
                        WORKER_NAME,
                        ERROR_DELAY_MS,
                        e,
                    )
                }
            }
        } finally {
            RedisConfig.closeBlockingConnection(conn)
        }
    }

    internal suspend fun processMessage(
        workerId: Int,
        payload: String,
    ) {
        processPayloads(workerId, listOf(payload))
    }

    internal fun collectPayloadsForProcessing(
        redis: RedisCommands<String, String>,
        firstPayload: String,
    ): List<String> {
        val payloads = ArrayList<String>(maxPayloads)
        payloads.add(firstPayload)

        val drainedPayloads = drainQueuedPayloads(redis)
        payloads.addAll(drainedPayloads)
        return payloads
    }

    internal suspend fun processPayloads(
        workerId: Int,
        payloads: List<String>,
    ) {
        val decodedPayloads = payloads.mapNotNull { payload ->
            decodePayload(workerId, payload)
        }
        processDecodedPayloads(workerId, decodedPayloads)
    }

    private fun drainQueuedPayloads(
        redis: RedisCommands<String, String>,
    ): List<String> {
        val count = maxPayloads - 1
        if (count <= 0) return emptyList()
        return try {
            val drainedPayloads = mutableListOf<String>()
            repeat(count) {
                val payload = redis.rpoplpush(queueKey, processingQueueKey)
                    ?: return drainedPayloads
                drainedPayloads.add(payload)
            }
            drainedPayloads
        } catch (e: RedisException) {
            logger.warn(e) {
                "Failed to drain additional Datadog metric payloads; processing BRPOP payload only"
            }
            emptyList()
        }
    }

    private fun decodePayload(
        workerId: Int,
        payload: String,
    ): DecodedMetricPayload? {
        return try {
            DecodedMetricPayload(
                originalPayload = payload,
                batch = DatadogMetricService.decodeMetricBatch(payload),
            )
        } catch (e: SerializationException) {
            pushToDlqAndAck(payload, workerId, e)
            null
        } catch (e: IllegalArgumentException) {
            pushToDlqAndAck(payload, workerId, e)
            null
        }
    }

    private suspend fun processDecodedPayloads(
        workerId: Int,
        payloads: List<DecodedMetricPayload>,
    ) {
        val pending = mutableListOf<DecodedMetricPayload>()
        var pendingRows = 0

        payloads.forEach { payload ->
            if (shouldFlushBeforeAdding(pending, pendingRows, payload.batch.metrics.size)) {
                insertPayloadChunk(workerId, pending)
                pending.clear()
                pendingRows = 0
            }

            pending.add(payload)
            pendingRows += payload.batch.metrics.size

            if (shouldFlushAfterAdding(pending, pendingRows)) {
                insertPayloadChunk(workerId, pending)
                pending.clear()
                pendingRows = 0
            }
        }

        insertPayloadChunk(workerId, pending)
    }

    private fun shouldFlushBeforeAdding(
        pending: List<DecodedMetricPayload>,
        pendingRows: Int,
        nextRows: Int,
    ): Boolean {
        return pending.isNotEmpty() &&
            (pending.size >= maxPayloads || pendingRows + nextRows > maxRows)
    }

    private fun shouldFlushAfterAdding(
        pending: List<DecodedMetricPayload>,
        pendingRows: Int,
    ): Boolean {
        return pending.size >= maxPayloads || pendingRows >= maxRows
    }

    private suspend fun insertPayloadChunk(
        workerId: Int,
        payloads: List<DecodedMetricPayload>,
    ) {
        if (payloads.isEmpty()) return

        val nonEmptyBatches = payloads.map { it.batch }.filter { it.metrics.isNotEmpty() }
        if (nonEmptyBatches.isEmpty()) {
            markProcessed(workerId, payloads)
            return
        }

        val combinedResult = insertCombinedBatch(nonEmptyBatches)
        if (combinedResult.isSuccess) {
            markProcessed(workerId, payloads)
            return
        }

        OperationalMetrics.recordDatadogMetricInsertFallback(
            payloadCount = payloads.size,
            rowCount = totalRows(payloads),
            cause = combinedResult.exceptionOrNull(),
        )
        logger.warn(combinedResult.exceptionOrNull()) {
            "Combined Datadog metric insert failed; falling back to per-payload inserts"
        }
        payloads.forEach { insertSinglePayload(workerId, it) }
    }

    private suspend fun insertCombinedBatch(
        batches: List<QueuedMetricBatch>,
    ): Result<Unit> {
        val startedAt = System.nanoTime()
        val result = suspendRunCatching {
            DatadogMetricService.insertMetricBatches(batches)
        }
        OperationalMetrics.recordDatadogMetricInsert(
            mode = COMBINED_INSERT_MODE,
            status = result.metricStatus(),
            payloadCount = batches.size,
            rowCount = batches.sumOf { it.metrics.size },
            durationSeconds = elapsedSecondsSince(startedAt),
            cause = result.exceptionOrNull(),
        )
        return result
    }

    private suspend fun insertSinglePayload(
        workerId: Int,
        payload: DecodedMetricPayload,
    ) {
        val startedAt = System.nanoTime()
        val result = suspendRunCatching {
            DatadogMetricService.insertMetricBatch(payload.batch)
        }
        OperationalMetrics.recordDatadogMetricInsert(
            mode = SINGLE_INSERT_MODE,
            status = result.metricStatus(),
            payloadCount = 1,
            rowCount = payload.batch.metrics.size,
            durationSeconds = elapsedSecondsSince(startedAt),
            cause = result.exceptionOrNull(),
        )
        result.onSuccess {
            markProcessed(workerId, listOf(payload))
        }.onFailure { e ->
            pushToDlqAndAck(payload.originalPayload, workerId, e)
        }
    }

    private fun markProcessed(
        workerId: Int,
        payloads: List<DecodedMetricPayload>,
    ) {
        payloads.forEach { payload ->
            acknowledgePayload(payload.originalPayload, workerId)
            recordProcessed(workerId)
        }
    }

    private fun pushToDlqAndAck(
        payload: String,
        workerId: Int,
        cause: Throwable,
    ) {
        if (pushToDlq(logger, dlqKey, payload, workerId, WORKER_NAME, cause)) {
            acknowledgePayload(payload, workerId)
        }
    }

    private fun acknowledgePayload(
        payload: String,
        workerId: Int,
    ) {
        runCatching {
            RedisConfig.sync().lrem(processingQueueKey, 1, payload)
        }.onSuccess {
            OperationalMetrics.recordDatadogMetricPayloadAck(SUCCESS_STATUS)
        }.onFailure { e ->
            OperationalMetrics.recordDatadogMetricPayloadAck(FAILURE_STATUS)
            logger.warn(e) {
                "Failed to acknowledge Datadog metric payload for worker $workerId; " +
                    "payload will be recovered on restart"
            }
        }
    }

    private fun recordProcessed(workerId: Int) {
        OperationalMetrics.recordWorkerMessageProcessed(WORKER_NAME, workerId)
    }

    private fun recoverProcessingQueue() {
        runCatching {
            val redis = RedisConfig.sync()
            var recovered = 0
            while (true) {
                redis.rpoplpush(processingQueueKey, queueKey) ?: break
                recovered += 1
            }
            recovered
        }.onSuccess { recovered ->
            OperationalMetrics.recordDatadogMetricProcessingRecovery(
                recoveredPayloads = recovered,
                status = SUCCESS_STATUS,
            )
            if (recovered > 0) {
                logger.warn {
                    "Recovered $recovered unacknowledged Datadog metric payloads from $processingQueueKey"
                }
            }
        }.onFailure { e ->
            OperationalMetrics.recordDatadogMetricProcessingRecovery(
                recoveredPayloads = 0,
                status = FAILURE_STATUS,
                cause = e,
            )
            logger.warn(e) {
                "Failed to recover Datadog metric processing queue $processingQueueKey"
            }
        }
    }

    private fun registerQueues() {
        OperationalMetrics.registerQueue(WORKER_NAME, queueKey, "primary")
        OperationalMetrics.registerQueue(WORKER_NAME, processingQueueKey, "processing")
        OperationalMetrics.registerDlq(WORKER_NAME, dlqKey)
    }
}

private fun totalRows(payloads: List<DecodedMetricPayload>): Int =
    payloads.sumOf { it.batch.metrics.size }

private fun Result<Unit>.metricStatus(): String =
    if (isSuccess) SUCCESS_STATUS else FAILURE_STATUS

private fun configuredPositiveInt(
    key: String,
    defaultValue: Int,
): Int =
    EnvConfig.get(key, defaultValue.toString())
        .toIntOrNull()
        ?.takeIf { it > 0 }
        ?: defaultValue

private fun elapsedSecondsSince(startedAt: Long): Double =
    (System.nanoTime() - startedAt).toDouble() / NANOS_PER_SECOND

private const val NANOS_PER_SECOND = 1_000_000_000
