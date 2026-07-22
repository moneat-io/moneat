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

import com.moneat.datadog.services.DatadogHostService
import com.moneat.datadog.services.DatadogMetricService
import com.moneat.datadog.services.QueuedSketchBatch
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueSettings
import com.moneat.ingestion.queue.QueuedIngestionMessage
import com.moneat.ingestion.queue.RedisQueueWorker
import com.moneat.monitoring.OperationalMetrics
import com.moneat.utils.pushToDlq
import kotlinx.serialization.SerializationException
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private const val WORKER_NAME = "DD sketch"

class DatadogSketchIngestionWorker(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int,
) {
    private var queueWorker: RedisQueueWorker? = null

    fun start() {
        val spec = IngestionQueueSettings.spec(IngestionPipeline.DD_SKETCHES, queueKey, dlqKey, workerCount)
        queueWorker = RedisQueueWorker(
            spec = spec,
            logger = logger,
            processMessage = ::processMessage,
            processBatch = ::processBatch,
        ).also { it.start() }
    }

    fun stop() {
        queueWorker?.stop()
    }

    internal suspend fun processMessage(workerId: Int, payload: String) {
        processPayloads(workerId, listOf(payload))
    }

    private suspend fun processBatch(workerId: Int, messages: List<QueuedIngestionMessage>) {
        processPayloads(workerId, messages.map { it.payload })
    }

    internal suspend fun processPayloads(workerId: Int, payloads: List<String>) {
        val batches = payloads.mapNotNull { payload -> decodePayload(workerId, payload) }
        DatadogMetricService.insertSketchBatches(batches)
        touchHostsBestEffort(batches)
        repeat(batches.size) {
            OperationalMetrics.recordWorkerMessageProcessed(WORKER_NAME, workerId)
        }
    }

    private fun decodePayload(workerId: Int, payload: String): QueuedSketchBatch? =
        try {
            DatadogMetricService.decodeSketchBatch(payload)
        } catch (error: SerializationException) {
            pushToDlq(logger, dlqKey, payload, workerId, WORKER_NAME, error)
            null
        } catch (error: IllegalArgumentException) {
            pushToDlq(logger, dlqKey, payload, workerId, WORKER_NAME, error)
            null
        }

    private fun touchHostsBestEffort(batches: List<QueuedSketchBatch>) {
        batches.groupBy { it.organizationId }.forEach { (organizationId, organizationBatches) ->
            val hosts = organizationBatches
                .flatMap { batch -> batch.sketches.map { sketch -> sketch.host } }
                .filter { it.isNotBlank() }
                .toSet()
            runCatching {
                DatadogHostService.touchHostLastSeen(organizationId.toInt(), hosts)
            }.onFailure { error ->
                logger.warn(error) { "Failed to update host freshness after sketch persistence" }
            }
        }
    }
}
