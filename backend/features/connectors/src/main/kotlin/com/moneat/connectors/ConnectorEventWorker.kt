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

package com.moneat.connectors

import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueSettings
import com.moneat.ingestion.queue.RedisQueueWorker
import com.moneat.monitoring.OperationalMetrics
import mu.KotlinLogging

private val connectorEventWorkerLogger = KotlinLogging.logger {}
private const val DEFAULT_CONNECTOR_EVENT_WORKERS = 2

class ConnectorEventWorker(
    private val connectorService: ConnectorService,
    private val workerCount: Int = DEFAULT_CONNECTOR_EVENT_WORKERS,
) {
    private var queueWorker: RedisQueueWorker? = null

    fun start() {
        val spec = IngestionQueueSettings.spec(
            pipeline = IngestionPipeline.CONNECTOR_EVENTS,
            queueKey = ConnectorService.CONNECTOR_EVENT_QUEUE_KEY,
            dlqKey = ConnectorService.CONNECTOR_EVENT_DLQ_KEY,
            workerCount = workerCount,
        )
        queueWorker = RedisQueueWorker(
            spec = spec,
            logger = connectorEventWorkerLogger,
            processMessage = ::processMessageForTest,
        ).also { worker -> worker.start() }
    }

    fun stop() {
        queueWorker?.stop()
        connectorEventWorkerLogger.info { "ConnectorEventWorker stopped" }
    }

    internal suspend fun processMessageForTest(
        workerId: Int,
        value: String,
    ) {
        val rawEventId = value.toLongOrNull()
            ?: throw IllegalArgumentException("Connector event payload must be a raw event ID")
        connectorService.processRawEvent(rawEventId)
        OperationalMetrics.recordWorkerMessageProcessed(IngestionPipeline.CONNECTOR_EVENTS.workerName, workerId)
    }
}
