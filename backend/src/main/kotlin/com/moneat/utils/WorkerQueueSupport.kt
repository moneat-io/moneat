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

package com.moneat.utils

import com.moneat.ingestion.queue.IngestionDlqRequest
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.ingestion.queue.IngestionQueueSettings
import com.moneat.monitoring.OperationalMetrics
import kotlinx.coroutines.delay
import mu.KLogger

private val WORKER_PIPELINES =
    mapOf(
        "Event" to IngestionPipeline.EVENTS,
        "Log" to IngestionPipeline.LOGS,
        "LLM" to IngestionPipeline.LLM,
        "Analytics" to IngestionPipeline.ANALYTICS,
        "Trace" to IngestionPipeline.DD_TRACES,
        "DD metric" to IngestionPipeline.DD_METRICS,
        "DD event" to IngestionPipeline.DD_EVENTS,
        "DD infra" to IngestionPipeline.DD_INFRA,
        "Orchestrator" to IngestionPipeline.DD_ORCHESTRATOR,
        "DBM" to IngestionPipeline.DD_DBM,
        "Debugger" to IngestionPipeline.DD_DEBUGGER,
        "Misc" to IngestionPipeline.DD_MISC,
        "NDM" to IngestionPipeline.DD_NDM,
        "Security" to IngestionPipeline.DD_SECURITY,
        "Connector event" to IngestionPipeline.CONNECTOR_EVENTS,
    )

/**
 * Logs a Redis queue loop failure and backs off. Use after [catch] for
 * [io.lettuce.core.RedisException] or [java.io.IOException].
 */
suspend fun queueLoopBackoff(
    logger: KLogger,
    workerId: Int,
    scopeLabel: String,
    errorDelayMs: Long,
    e: Throwable,
) {
    logger.error(e) { "$scopeLabel worker $workerId error in queue loop" }
    OperationalMetrics.recordWorkerQueueLoopFailure(scopeLabel, workerId, e)
    delay(errorDelayMs)
}

/**
 * Logs a processing failure and pushes the payload to a Redis dead-letter
 * queue. DLQ write errors are caught and logged so they never propagate.
 */
@Suppress("TooGenericExceptionCaught")
fun pushToDlq(
    logger: KLogger,
    dlqKey: String,
    payload: String,
    workerId: Int,
    workerName: String,
    cause: Throwable,
): Boolean {
    val pipeline = workerName.toIngestionPipeline()
    if (pipeline == null) {
        logger.error(cause) { "Unknown ingestion pipeline for $workerName worker $workerId" }
        OperationalMetrics.recordWorkerProcessingFailure(workerName, workerId, cause)
        return false
    }
    val spec = IngestionQueueSettings.spec(
        pipeline = pipeline,
        queueKey = queueKeyForDlq(dlqKey),
        dlqKey = dlqKey,
        workerCount = 1,
    )
    return IngestionQueueClient.pushToDlq(
        logger = logger,
        request = IngestionDlqRequest(
            spec = spec,
            payload = payload,
            workerId = workerId,
            cause = cause,
        ),
    )
}

private fun String.toIngestionPipeline(): IngestionPipeline? =
    WORKER_PIPELINES[this]

private fun queueKeyForDlq(dlqKey: String): String =
    when {
        dlqKey.endsWith(":dlq") -> dlqKey.removeSuffix(":dlq") + ":queue"
        dlqKey.endsWith(":dead-letter") -> dlqKey.removeSuffix(":dead-letter") + ":queue"
        else -> "$dlqKey:queue"
    }
