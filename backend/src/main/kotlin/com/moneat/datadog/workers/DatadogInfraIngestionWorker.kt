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
import com.moneat.datadog.services.DatadogInfraService
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
import kotlinx.serialization.SerializationException
import mu.KotlinLogging
import java.io.IOException
import java.sql.SQLException

private val logger = KotlinLogging.logger {}

private const val BRPOP_TIMEOUT_SECONDS = 5L
private const val ERROR_DELAY_MS = 1000L

class DatadogInfraIngestionWorker(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int
) {
    private val scope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        logger.info {
            "Starting DatadogInfraIngestionWorker with " +
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
        logger.info { "DatadogInfraIngestionWorker stopped" }
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
                        "DD infra",
                        ERROR_DELAY_MS,
                        e,
                    )
                } catch (e: IOException) {
                    brpopLoopBackoff(
                        logger,
                        workerId,
                        "DD infra",
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
        try {
            val batch =
                DatadogInfraService.decodeInfraBatch(payload)
            DatadogInfraService.insertInfraBatch(batch)
        } catch (e: SerializationException) {
            handleInfraDlq(workerId, payload, e, onDlq)
        } catch (e: IOException) {
            handleInfraDlq(workerId, payload, e, onDlq)
        } catch (e: SQLException) {
            handleInfraDlq(workerId, payload, e, onDlq)
        } catch (e: RedisException) {
            handleInfraDlq(workerId, payload, e, onDlq)
        } catch (e: IllegalStateException) {
            handleInfraDlq(workerId, payload, e, onDlq)
        } catch (e: IllegalArgumentException) {
            handleInfraDlq(workerId, payload, e, onDlq)
        }
    }

    private fun handleInfraDlq(
        workerId: Int,
        payload: String,
        e: Throwable,
        onDlq: (String) -> Unit,
    ) {
        logger.error(e) {
            "DD infra worker $workerId failed, " +
                "pushing to DLQ"
        }
        onDlq(payload)
    }
}
