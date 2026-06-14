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

import com.moneat.datadog.services.DebuggerIngestionService
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueSettings
import com.moneat.ingestion.queue.RedisQueueWorker
import com.moneat.monitoring.OperationalMetrics
import com.moneat.utils.pushToDlq
import com.moneat.utils.suspendRunCatching
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class DebuggerIngestionWorker(
    private val queueKey: String = "moneat:dd:debugger:queue",
    private val dlqKey: String = "moneat:dd:debugger:dlq",
    private val workerCount: Int = 1,
) {
    private var queueWorker: RedisQueueWorker? = null

    fun start() {
        logger.info {
            "Starting DebuggerIngestionWorker with " +
                "$workerCount workers, queue=$queueKey"
        }
        val spec = IngestionQueueSettings.spec(IngestionPipeline.DD_DEBUGGER, queueKey, dlqKey, workerCount)
        queueWorker = RedisQueueWorker(spec, logger, ::processMessage).also { it.start() }
    }

    fun stop() {
        queueWorker?.stop()
        logger.info { "DebuggerIngestionWorker stopped" }
    }

    internal suspend fun processMessage(
        workerId: Int,
        payload: String,
    ) {
        suspendRunCatching {
            val batch = DebuggerIngestionService.decodeBatch(payload)
            DebuggerIngestionService.insertBatch(batch)
            logger.debug {
                "Debugger worker $workerId processed batch: " +
                    "type=${batch.batchType} " +
                    "logs=${batch.logs.size} " +
                    "diagnostics=${batch.diagnostics.size}"
            }
            OperationalMetrics.recordWorkerMessageProcessed("Debugger", workerId)
        }.getOrElse { e ->
            pushToDlq(logger, dlqKey, payload, workerId, "Debugger", e)
        }
    }
}
