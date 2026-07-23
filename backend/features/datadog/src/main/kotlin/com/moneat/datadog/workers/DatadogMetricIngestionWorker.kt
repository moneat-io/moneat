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
import com.moneat.datadog.services.DatadogHostService
import com.moneat.datadog.services.DatadogMetricService
import com.moneat.datadog.services.QueuedMetricBatch
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueSettings
import com.moneat.ingestion.queue.QueuedIngestionMessage
import com.moneat.ingestion.queue.RedisQueueWorker
import com.moneat.monitoring.OperationalMetrics
import com.moneat.utils.pushToDlq
import com.moneat.utils.suspendRunCatching
import kotlinx.serialization.SerializationException
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val COMBINED_INSERT_MODE = "combined"
private const val SUCCESS_STATUS = "success"
private const val FAILURE_STATUS = "failure"
private const val WORKER_NAME = "DD metric"
private const val DEFAULT_MAX_ROWS = 20_000
private const val DEFAULT_MAX_PAYLOADS = 100
private const val MAX_ROWS_ENV = "DD_METRIC_BATCH_MAX_ROWS"
private const val MAX_PAYLOADS_ENV = "DD_METRIC_BATCH_MAX_PAYLOADS"

private data class DecodedMetricPayload(
    val batch: QueuedMetricBatch,
)

class DatadogMetricIngestionWorker(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int,
    private val maxRows: Int = configuredPositiveInt(MAX_ROWS_ENV, DEFAULT_MAX_ROWS),
    private val maxPayloads: Int = configuredPositiveInt(MAX_PAYLOADS_ENV, DEFAULT_MAX_PAYLOADS),
) {
    private var queueWorker: RedisQueueWorker? = null

    fun start() {
        logger.info {
            "Starting DatadogMetricIngestionWorker with " +
                "$workerCount workers, queue=$queueKey"
        }
        val spec = IngestionQueueSettings.spec(IngestionPipeline.DD_METRICS, queueKey, dlqKey, workerCount)
        queueWorker = RedisQueueWorker(
            spec = spec,
            logger = logger,
            processMessage = ::processMessage,
            processBatch = ::processQueuedMessages,
        ).also { it.start() }
    }

    fun stop() {
        queueWorker?.stop()
        logger.info { "DatadogMetricIngestionWorker stopped" }
    }

    internal suspend fun processMessage(
        workerId: Int,
        payload: String,
    ) {
        processPayloads(workerId, listOf(payload))
    }

    private suspend fun processQueuedMessages(
        workerId: Int,
        messages: List<QueuedIngestionMessage>,
    ) {
        processPayloads(workerId, messages.map { it.payload })
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

    private fun decodePayload(
        workerId: Int,
        payload: String,
    ): DecodedMetricPayload? {
        return try {
            DecodedMetricPayload(
                batch = DatadogMetricService.decodeMetricBatch(payload),
            )
        } catch (e: SerializationException) {
            if (!pushToDlq(logger, dlqKey, payload, workerId, WORKER_NAME, e)) throw e
            null
        } catch (e: IllegalArgumentException) {
            if (!pushToDlq(logger, dlqKey, payload, workerId, WORKER_NAME, e)) throw e
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
            markProcessed(workerId, payloads.size)
            return
        }

        val combinedResult = insertCombinedBatch(nonEmptyBatches)
        combinedResult.getOrThrow()
        touchHostsBestEffort(nonEmptyBatches)
        markProcessed(workerId, payloads.size)
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

    private fun touchHostsBestEffort(batches: List<QueuedMetricBatch>) {
        batches.groupBy { it.organizationId }.forEach { (organizationId, organizationBatches) ->
            val hosts = organizationBatches
                .flatMap { batch -> batch.metrics.map { metric -> metric.host } }
                .filter { it.isNotBlank() }
                .toSet()
            runCatching {
                DatadogHostService.touchHostLastSeen(organizationId.toInt(), hosts)
            }.onFailure { error ->
                logger.warn(error) { "Failed to update host freshness after metric persistence" }
            }
        }
    }

    private fun markProcessed(
        workerId: Int,
        count: Int,
    ) {
        repeat(count) {
            recordProcessed(workerId)
        }
    }

    private fun recordProcessed(workerId: Int) {
        OperationalMetrics.recordWorkerMessageProcessed(WORKER_NAME, workerId)
    }
}

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
