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

package com.moneat.notifications.services

import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueSettings
import com.moneat.ingestion.queue.RedisQueueWorker
import com.moneat.monitoring.OperationalMetrics
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class SlackInboundWorker(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int,
    private val gateway: SlackInboundGateway,
) {
    private var queueWorker: RedisQueueWorker? = null

    fun start() {
        val spec = IngestionQueueSettings.spec(IngestionPipeline.SLACK_INBOUND, queueKey, dlqKey, workerCount)
        queueWorker = RedisQueueWorker(spec, logger, processMessage = ::processMessage).also { it.start() }
        logger.info { "Slack inbound worker started with $workerCount workers" }
    }

    fun stop() {
        queueWorker?.stop()
        queueWorker = null
        logger.info { "Slack inbound worker stopped" }
    }

    private suspend fun processMessage(workerId: Int, value: String) {
        gateway.process(value)
        OperationalMetrics.recordWorkerMessageProcessed(IngestionPipeline.SLACK_INBOUND.workerName, workerId)
    }
}
