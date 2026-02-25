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

import com.moneat.config.RedisConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class LogIngestionWorker(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int
) {
    private val logService = LogService()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        logger.info { "Starting LogIngestionWorker with $workerCount workers, queue=$queueKey" }
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
        while (scope.isActive) {
            try {
                val result = RedisConfig.syncBlocking().brpop(5, queueKey)
                val payload = result?.value ?: continue
                processMessageForTest(workerId, payload)
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                logger.error(e) { "Log worker $workerId error in BRPOP loop" }
                delay(1000)
            }
        }
    }

    internal suspend fun processMessageForTest(
        workerId: Int,
        payload: String,
        onDlq: (String) -> Unit = { message -> RedisConfig.syncBlocking().rpush(dlqKey, message) }
    ) {
        try {
            val batch = logService.decodeQueueMessage(payload)
            val inserted = logService.insertBatch(batch)
            logService.publishLiveLogs(batch.effectiveOrganizationId, inserted)
        } catch (e: Exception) {
            logger.error(e) { "Log worker $workerId failed to process message, pushing to DLQ" }
            onDlq(payload)
        }
    }
}
