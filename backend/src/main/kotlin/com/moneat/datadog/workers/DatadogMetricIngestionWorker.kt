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

private const val BRPOP_TIMEOUT_SECONDS = 5L
private const val ERROR_DELAY_MS = 1000L
private const val WORKER_NAME = "DD metric"
private const val MAX_ROWS = 20_000
private const val MAX_PAYLOADS = 100

private data class DecodedMetricPayload(
    val originalPayload: String,
    val batch: QueuedMetricBatch,
)

class DatadogMetricIngestionWorker(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int
) {
    private val scope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
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
                    val result = redis.brpop(
                        BRPOP_TIMEOUT_SECONDS,
                        queueKey
                    )
                    val payload = result?.value ?: continue
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
        val payloads = ArrayList<String>(MAX_PAYLOADS)
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
        val count = MAX_PAYLOADS - 1
        return try {
            redis.rpop(queueKey, count.toLong()).orEmpty()
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
            pushToDlq(logger, dlqKey, payload, workerId, WORKER_NAME, e)
            null
        } catch (e: IllegalArgumentException) {
            pushToDlq(logger, dlqKey, payload, workerId, WORKER_NAME, e)
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
            (pending.size >= MAX_PAYLOADS || pendingRows + nextRows > MAX_ROWS)
    }

    private fun shouldFlushAfterAdding(
        pending: List<DecodedMetricPayload>,
        pendingRows: Int,
    ): Boolean {
        return pending.size >= MAX_PAYLOADS || pendingRows >= MAX_ROWS
    }

    private suspend fun insertPayloadChunk(
        workerId: Int,
        payloads: List<DecodedMetricPayload>,
    ) {
        if (payloads.isEmpty()) return

        val nonEmptyBatches = payloads.map { it.batch }.filter { it.metrics.isNotEmpty() }
        if (nonEmptyBatches.isEmpty()) {
            payloads.forEach { recordProcessed(workerId) }
            return
        }

        if (insertCombinedBatch(nonEmptyBatches).isSuccess) {
            payloads.forEach { recordProcessed(workerId) }
            return
        }

        val retryResult = insertCombinedBatch(nonEmptyBatches)
        if (retryResult.isSuccess) {
            payloads.forEach { recordProcessed(workerId) }
            return
        }

        logger.warn(retryResult.exceptionOrNull()) {
            "Combined Datadog metric insert failed after retry; falling back to per-payload inserts"
        }
        payloads.forEach { insertSinglePayload(workerId, it) }
    }

    private suspend fun insertCombinedBatch(
        batches: List<QueuedMetricBatch>,
    ): Result<Unit> {
        return suspendRunCatching {
            DatadogMetricService.insertMetricBatches(batches)
        }
    }

    private suspend fun insertSinglePayload(
        workerId: Int,
        payload: DecodedMetricPayload,
    ) {
        suspendRunCatching {
            DatadogMetricService.insertMetricBatch(payload.batch)
        }.onSuccess {
            recordProcessed(workerId)
        }.onFailure { e ->
            pushToDlq(logger, dlqKey, payload.originalPayload, workerId, WORKER_NAME, e)
        }
    }

    private fun recordProcessed(workerId: Int) {
        OperationalMetrics.recordWorkerMessageProcessed(WORKER_NAME, workerId)
    }
}
