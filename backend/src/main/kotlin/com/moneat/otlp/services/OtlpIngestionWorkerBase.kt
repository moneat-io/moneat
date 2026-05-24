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
import com.moneat.monitoring.OperationalMetrics
import com.moneat.utils.suspendRunCatching
import io.lettuce.core.RedisException
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import mu.KotlinLogging
import java.io.IOException
import java.sql.SQLException

private val logger = KotlinLogging.logger {}
private const val ERROR_RETRY_DELAY_MS = 1000L

abstract class OtlpIngestionWorkerBase(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int,
    private val workerName: String,
    private val workerLabel: String,
) {
    init {
        require(workerCount > 0) { "workerCount must be > 0" }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        logger.info {
            "Starting $workerName with $workerCount workers, queue=$queueKey"
        }
        OperationalMetrics.registerWorkerQueues("OTLP $workerLabel", queueKey, dlqKey)
        jobs = (1..workerCount).map { workerId ->
            scope.launch { runWorker(workerId) }
        }
    }

    suspend fun stop() {
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        scope.cancel()
        logger.info { "$workerName stopped" }
    }

    private suspend fun runWorker(workerId: Int) {
        var conn: StatefulRedisConnection<String, String>? = null
        try {
            while (scope.isActive) {
                when (val outcome = runBrpopIteration(workerId, conn)) {
                    is BrpopIterationOutcome.Break -> break
                    is BrpopIterationOutcome.Continue -> {
                        conn = outcome.connection
                    }
                }
            }
        } finally {
            conn?.let { disposeRedisConnection(workerId, it) }
        }
    }

    private sealed class BrpopIterationOutcome {
        data class Continue(val connection: StatefulRedisConnection<String, String>?) : BrpopIterationOutcome()
        data object Break : BrpopIterationOutcome()
    }

    private suspend fun runBrpopIteration(
        workerId: Int,
        conn: StatefulRedisConnection<String, String>?,
    ): BrpopIterationOutcome {
        var activeConn = conn
        return try {
            if (activeConn == null || !activeConn.isOpen) {
                activeConn?.let { disposeRedisConnection(workerId, it) }
                activeConn = RedisConfig.newStatefulBlockingConnection()
            }
            val redis = activeConn.sync()
            val result = redis.brpop(BRPOP_TIMEOUT_SECONDS, queueKey)
            val payload = result?.value ?: return BrpopIterationOutcome.Continue(activeConn)
            processBrpopPayload(workerId, payload)
            BrpopIterationOutcome.Continue(activeConn)
        } catch (e: CancellationException) {
            BrpopIterationOutcome.Break
        } catch (e: RedisException) {
            onOtlpBrpopLoopFailure(workerId, e, activeConn) { c ->
                disposeRedisConnection(workerId, c)
                activeConn = null
            }
            BrpopIterationOutcome.Continue(activeConn)
        } catch (e: IOException) {
            onOtlpBrpopLoopFailure(workerId, e, activeConn) { c ->
                disposeRedisConnection(workerId, c)
                activeConn = null
            }
            BrpopIterationOutcome.Continue(activeConn)
        }
    }

    private suspend fun processBrpopPayload(workerId: Int, payload: String) {
        try {
            processMessage(workerId, payload)
            OperationalMetrics.recordWorkerMessageProcessed("OTLP $workerLabel", workerId)
        } catch (proc: CancellationException) {
            throw proc
        } catch (proc: SerializationException) {
            onOtlpProcessMessageFailure(workerId, proc)
        } catch (proc: IOException) {
            onOtlpProcessMessageFailure(workerId, proc)
        } catch (proc: SQLException) {
            onOtlpProcessMessageFailure(workerId, proc)
        } catch (proc: RedisException) {
            onOtlpProcessMessageFailure(workerId, proc)
        } catch (proc: IllegalStateException) {
            onOtlpProcessMessageFailure(workerId, proc)
        } catch (proc: IllegalArgumentException) {
            onOtlpProcessMessageFailure(workerId, proc)
        }
    }

    private suspend fun onOtlpProcessMessageFailure(
        workerId: Int,
        proc: Throwable,
    ) {
        logger.error(proc) {
            "OTLP $workerLabel worker $workerId failed processing message"
        }
        OperationalMetrics.recordWorkerProcessingFailure("OTLP $workerLabel", workerId, proc)
        delay(ERROR_RETRY_DELAY_MS)
    }

    private suspend fun onOtlpBrpopLoopFailure(
        workerId: Int,
        e: Throwable,
        connection: StatefulRedisConnection<String, String>?,
        resetConnection: (StatefulRedisConnection<String, String>) -> Unit,
    ) {
        logger.error(e) { "OTLP $workerLabel worker $workerId error in BRPOP loop" }
        OperationalMetrics.recordWorkerBrpopFailure("OTLP $workerLabel", workerId, e)
        connection?.let(resetConnection)
        delay(ERROR_RETRY_DELAY_MS)
    }

    private fun disposeRedisConnection(workerId: Int, c: StatefulRedisConnection<String, String>) {
        suspendRunCatching {
            RedisConfig.closeBlockingConnection(c)
        }.getOrElse { e ->
            logger.warn(e) { "Failed to close Redis connection for OTLP worker $workerId" }
        }
    }

    protected abstract suspend fun processMessage(workerId: Int, payload: String)

    protected fun pushToDlq(workerId: Int, payload: String) {
        try {
            RedisConfig.sync().rpush(dlqKey, payload)
            OperationalMetrics.recordDlqPush("OTLP $workerLabel", dlqKey, "success")
        } catch (dlqErr: RedisException) {
            OperationalMetrics.recordDlqPush("OTLP $workerLabel", dlqKey, "failure")
            logger.error(dlqErr) { "Failed to push to DLQ $dlqKey for worker=$workerId" }
        }
    }
}
