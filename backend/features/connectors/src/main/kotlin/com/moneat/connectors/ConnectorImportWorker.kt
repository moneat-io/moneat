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

private val connectorImportWorkerLogger = KotlinLogging.logger {}
private const val DEFAULT_CONNECTOR_IMPORT_WORKERS = 1

class ConnectorImportWorker(
    private val connectorService: ConnectorService,
    private val workerCount: Int = DEFAULT_CONNECTOR_IMPORT_WORKERS,
) {
    private var queueWorker: RedisQueueWorker? = null

    fun start() {
        val spec = IngestionQueueSettings.spec(
            pipeline = IngestionPipeline.CONNECTOR_IMPORTS,
            queueKey = ConnectorService.CONNECTOR_IMPORT_QUEUE_KEY,
            dlqKey = ConnectorService.CONNECTOR_IMPORT_DLQ_KEY,
            workerCount = workerCount,
        )
        queueWorker = RedisQueueWorker(
            spec = spec,
            logger = connectorImportWorkerLogger,
            processMessage = ::processMessageForTest,
        ).also { worker -> worker.start() }
    }

    fun stop() {
        queueWorker?.stop()
        connectorImportWorkerLogger.info { "ConnectorImportWorker stopped" }
    }

    internal suspend fun processMessageForTest(
        workerId: Int,
        value: String,
    ) {
        val importRunId = value.toLongOrNull()
            ?: throw IllegalArgumentException("Connector import payload must be an import run ID")
        connectorService.processImportRun(importRunId)
        OperationalMetrics.recordWorkerMessageProcessed(IngestionPipeline.CONNECTOR_IMPORTS.workerName, workerId)
    }
}
