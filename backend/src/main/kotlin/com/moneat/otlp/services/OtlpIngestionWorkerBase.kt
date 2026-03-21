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

package com.moneat.otlp.services

import com.moneat.config.BRPOP_TIMEOUT_SECONDS
import com.moneat.config.RedisConfig
import io.lettuce.core.api.StatefulRedisConnection
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
private const val ERROR_RETRY_DELAY_MS = 1000L

abstract class OtlpIngestionWorkerBase(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int,
    private val workerName: String,
    private val workerLabel: String,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        logger.info {
            "Starting $workerName with $workerCount workers, queue=$queueKey"
        }
        jobs = (1..workerCount).map { workerId ->
            scope.launch { runWorker(workerId) }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        logger.info { "$workerName stopped" }
    }

    private suspend fun runWorker(workerId: Int) {
        var conn: StatefulRedisConnection<String, String>? = null
        try {
            while (scope.isActive) {
                try {
                    if (conn == null || !conn.isOpen) {
                        conn?.let { disposeRedisConnection(workerId, it) }
                        conn = RedisConfig.newStatefulBlockingConnection()
                    }
                    val redis = conn.sync()
                    val result = redis.brpop(BRPOP_TIMEOUT_SECONDS, queueKey)
                    val payload = result?.value ?: continue
                    try {
                        processMessage(workerId, payload)
                    } catch (proc: Exception) {
                        logger.error(proc) {
                            "OTLP $workerLabel worker $workerId failed processing message"
                        }
                        delay(ERROR_RETRY_DELAY_MS)
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    logger.error(e) { "OTLP $workerLabel worker $workerId error in BRPOP loop" }
                    conn?.let {
                        disposeRedisConnection(workerId, it)
                        conn = null
                    }
                    delay(ERROR_RETRY_DELAY_MS)
                }
            }
        } finally {
            conn?.let { disposeRedisConnection(workerId, it) }
        }
    }

    private fun disposeRedisConnection(workerId: Int, c: StatefulRedisConnection<String, String>) {
        try {
            RedisConfig.closeBlockingConnection(c)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to close Redis connection for OTLP worker $workerId" }
        }
    }

    protected abstract suspend fun processMessage(workerId: Int, payload: String)

    protected fun pushToDlq(workerId: Int, payload: String) {
        try {
            RedisConfig.sync().rpush(dlqKey, payload)
        } catch (dlqErr: Exception) {
            logger.error(dlqErr) { "Failed to push to DLQ $dlqKey" }
        }
    }
}
