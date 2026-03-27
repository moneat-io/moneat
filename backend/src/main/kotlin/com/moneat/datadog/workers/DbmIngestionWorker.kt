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
import com.moneat.datadog.services.DbmIngestionService
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
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

private const val BRPOP_TIMEOUT_SECONDS = 5L
private const val ERROR_DELAY_MS = 1000L

class DbmIngestionWorker(
    private val queueKey: String = "moneat:dd:dbm:queue",
    private val dlqKey: String = "moneat:dd:dbm:dlq",
    private val workerCount: Int = 1,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        logger.info {
            "Starting DbmIngestionWorker with " +
                "$workerCount workers, queue=$queueKey"
        }
        jobs = (1..workerCount).map { workerId ->
            scope.launch { runWorker(workerId) }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        logger.info { "DbmIngestionWorker stopped" }
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
                        "DBM",
                        ERROR_DELAY_MS,
                        e,
                    )
                } catch (e: IOException) {
                    brpopLoopBackoff(
                        logger,
                        workerId,
                        "DBM",
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
    ) {
        suspendRunCatching {
            val batch = DbmIngestionService.decodeBatch(payload)
            DbmIngestionService.insertBatch(batch)
            logger.debug {
                "DBM worker $workerId processed batch: " +
                    "type=${batch.batchType} " +
                    "queries=${batch.queries.size} " +
                    "metrics=${batch.metrics.size} " +
                    "activity=${batch.activity.size}"
            }
        }.getOrElse { e ->
            handleDbmDlq(workerId, payload, e)
        }
    }

    private fun handleDbmDlq(
        workerId: Int,
        payload: String,
        e: Throwable,
    ) {
        logger.error(e) {
            "DBM worker $workerId failed, pushing to DLQ"
        }
        try {
            RedisConfig.sync().rpush(dlqKey, payload)
        } catch (dlqErr: RedisException) {
            logger.error(dlqErr) {
                "Failed to write to DLQ for worker $workerId, dlqKey=$dlqKey"
            }
        } catch (dlqErr: IOException) {
            logger.error(dlqErr) {
                "Failed to write to DLQ for worker $workerId, dlqKey=$dlqKey"
            }
        } catch (dlqErr: SerializationException) {
            logger.error(dlqErr) {
                "Failed to write to DLQ for worker $workerId, dlqKey=$dlqKey"
            }
        } catch (dlqErr: IllegalStateException) {
            logger.error(dlqErr) {
                "Failed to write to DLQ for worker $workerId, dlqKey=$dlqKey"
            }
        } catch (dlqErr: IllegalArgumentException) {
            logger.error(dlqErr) {
                "Failed to write to DLQ for worker $workerId, dlqKey=$dlqKey"
            }
        }
    }
}
