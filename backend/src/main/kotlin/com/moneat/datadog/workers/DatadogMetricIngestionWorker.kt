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
import com.moneat.utils.brpopLoopBackoff
import io.lettuce.core.RedisException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.io.IOException
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

private const val BRPOP_TIMEOUT_SECONDS = 5L
private const val ERROR_DELAY_MS = 1000L

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
                    processMessage(workerId, payload)
                } catch (e: CancellationException) {
                    break
                } catch (e: RedisException) {
                    brpopLoopBackoff(
                        logger,
                        workerId,
                        "DD metric",
                        ERROR_DELAY_MS,
                        e,
                    )
                } catch (e: IOException) {
                    brpopLoopBackoff(
                        logger,
                        workerId,
                        "DD metric",
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
        onDlq: (String) -> Unit = { message ->
            RedisConfig.sync().rpush(dlqKey, message)
        }
    ) {
        suspendRunCatching {
            val batch =
                DatadogMetricService.decodeMetricBatch(payload)
            DatadogMetricService.insertMetricBatch(batch)
        }.getOrElse { e ->
            handleMetricDlq(workerId, payload, e, onDlq)
        }
    }

    private fun handleMetricDlq(
        workerId: Int,
        payload: String,
        e: Throwable,
        onDlq: (String) -> Unit,
    ) {
        logger.error(e) {
            "DD metric worker $workerId failed, " +
                "pushing to DLQ"
        }
        onDlq(payload)
    }
}
