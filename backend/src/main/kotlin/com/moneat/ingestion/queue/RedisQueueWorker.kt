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

package com.moneat.ingestion.queue

import com.moneat.config.BRPOP_TIMEOUT_SECONDS
import com.moneat.config.ClickHouseInsertDeduplication
import com.moneat.config.RedisConfig
import com.moneat.monitoring.OperationalMetrics
import com.moneat.shared.services.IngestionUsageDeduplication
import io.lettuce.core.Consumer
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisException
import io.lettuce.core.StreamMessage
import io.lettuce.core.XAutoClaimArgs
import io.lettuce.core.XGroupCreateArgs
import io.lettuce.core.XReadArgs
import io.lettuce.core.XReadArgs.StreamOffset
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
import mu.KLogger
import java.time.Duration

private const val ERROR_RETRY_DELAY_MS = 1_000L
private const val REDIS_GROUP_EXISTS = "BUSYGROUP"

data class QueuedIngestionMessage(
    val id: String?,
    val payload: String,
    val deliveryCount: Long = 1L,
)

class RedisQueueWorker(
    private val spec: IngestionQueueSpec,
    private val logger: KLogger,
    private val processMessage: suspend (workerId: Int, payload: String) -> Unit,
    private val processBatch: (suspend (workerId: Int, messages: List<QueuedIngestionMessage>) -> Unit)? = null,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        registerQueues()
        logger.info {
            "Starting ${spec.pipeline.workerName} queue worker with " +
                "${spec.workerCount} workers, queue=${spec.queueKey}, stream=${spec.streamKey}"
        }
        jobs = (1..spec.workerCount).map { workerId ->
            scope.launch { runWorker(workerId) }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        logger.info { "${spec.pipeline.workerName} queue worker stopped" }
    }

    private suspend fun runWorker(workerId: Int) {
        val conn = RedisConfig.newBlockingConnection()
        try {
            if (usesStreams()) {
                ensureConsumerGroup(conn)
            }
            while (scope.isActive) {
                try {
                    runQueueIteration(workerId, conn)
                } catch (e: CancellationException) {
                    break
                } catch (e: RedisException) {
                    onQueueLoopFailure(workerId, e)
                } catch (e: java.io.IOException) {
                    onQueueLoopFailure(workerId, e)
                } catch (e: IllegalStateException) {
                    onQueueLoopFailure(workerId, e)
                }
            }
        } finally {
            RedisConfig.closeBlockingConnection(conn)
        }
    }

    private suspend fun runQueueIteration(
        workerId: Int,
        conn: StatefulRedisConnection<String, String>,
    ) {
        when (IngestionQueueSettings.readMode()) {
            IngestionQueueReadMode.LIST -> consumeListBlocking(workerId, conn)
            IngestionQueueReadMode.STREAMS -> consumeStreams(workerId, conn)
            IngestionQueueReadMode.DUAL -> {
                if (!consumeListNonBlocking(workerId, conn)) {
                    consumeStreams(workerId, conn)
                }
            }
        }
    }

    private suspend fun consumeListBlocking(
        workerId: Int,
        conn: StatefulRedisConnection<String, String>,
    ) {
        val result = conn.sync().brpop(BRPOP_TIMEOUT_SECONDS, spec.queueKey)
        val payload = result?.value ?: return
        processMessage(workerId, payload)
    }

    private suspend fun consumeListNonBlocking(
        workerId: Int,
        conn: StatefulRedisConnection<String, String>,
    ): Boolean {
        val payload = conn.sync().rpop(spec.queueKey) ?: return false
        processMessage(workerId, payload)
        return true
    }

    private suspend fun consumeStreams(
        workerId: Int,
        conn: StatefulRedisConnection<String, String>,
    ) {
        val messages = readStreamMessages(workerId, conn)
        if (messages.isEmpty()) return
        val queuedMessages = messages.mapNotNull { message ->
            val payload = IngestionQueueClient.payloadField(message.body) ?: return@mapNotNull null
            QueuedIngestionMessage(
                id = message.id,
                payload = payload,
                deliveryCount = message.deliveredCount ?: 1L,
            )
        }
        if (queuedMessages.isEmpty()) return
        val batchProcessor = processBatch
        if (batchProcessor != null && queuedMessages.size > 1) {
            processStreamBatch(workerId, conn, queuedMessages, batchProcessor)
        } else {
            queuedMessages.forEach { message ->
                processStreamMessage(workerId, conn, message)
            }
        }
    }

    private fun readStreamMessages(
        workerId: Int,
        conn: StatefulRedisConnection<String, String>,
    ): List<StreamMessage<String, String>> {
        val redis = conn.sync()
        val consumer = Consumer.from(spec.consumerGroup, consumerName(workerId))
        val claimed = redis.xautoclaim(
            spec.streamKey,
            XAutoClaimArgs<String>()
                .consumer(consumer)
                .minIdleTime(Duration.ofMillis(spec.claimIdleMs))
                .startId("0-0")
                .count(spec.batchSize.toLong())
        ).messages
        if (claimed.isNotEmpty()) return claimed
        return redis.xreadgroup(
            consumer,
            XReadArgs()
                .block(Duration.ofMillis(spec.readTimeoutMs))
                .count(spec.batchSize.toLong()),
            StreamOffset.lastConsumed(spec.streamKey),
        )
    }

    private suspend fun processStreamBatch(
        workerId: Int,
        conn: StatefulRedisConnection<String, String>,
        messages: List<QueuedIngestionMessage>,
        batchProcessor: suspend (workerId: Int, messages: List<QueuedIngestionMessage>) -> Unit,
    ) {
        try {
            withClickHouseDeduplication(messages) {
                batchProcessor(workerId, messages)
            }
            ack(conn, messages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            handleStreamFailure(workerId, conn, messages, e)
        }
    }

    private suspend fun processStreamMessage(
        workerId: Int,
        conn: StatefulRedisConnection<String, String>,
        message: QueuedIngestionMessage,
    ) {
        try {
            withClickHouseDeduplication(listOf(message)) {
                processMessage(workerId, message.payload)
            }
            ack(conn, listOf(message))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            handleStreamFailure(workerId, conn, listOf(message), e)
        }
    }

    private suspend fun handleStreamFailure(
        workerId: Int,
        conn: StatefulRedisConnection<String, String>,
        messages: List<QueuedIngestionMessage>,
        cause: Throwable,
    ) {
        val shouldDlq = messages.any { message -> message.deliveryCount >= spec.maxDeliveries }
        if (shouldDlq) {
            messages.forEach { message ->
                IngestionQueueClient.pushToDlq(
                    logger = logger,
                    request = IngestionDlqRequest(
                        spec = spec,
                        payload = message.payload,
                        workerId = workerId,
                        cause = cause,
                        streamId = message.id,
                    ),
                )
            }
            ack(conn, messages)
            return
        }
        OperationalMetrics.recordWorkerProcessingFailure(spec.pipeline.workerName, workerId, cause)
        delay(ERROR_RETRY_DELAY_MS)
    }

    private fun ack(
        conn: StatefulRedisConnection<String, String>,
        messages: List<QueuedIngestionMessage>,
    ) {
        val ids = messages.mapNotNull { it.id }.toTypedArray()
        if (ids.isNotEmpty()) {
            conn.sync().xack(spec.streamKey, spec.consumerGroup, *ids)
        }
    }

    private suspend fun onQueueLoopFailure(
        workerId: Int,
        e: Throwable,
    ) {
        logger.error(e) { "${spec.pipeline.workerName} worker $workerId error in queue loop" }
        OperationalMetrics.recordWorkerBrpopFailure(spec.pipeline.workerName, workerId, e)
        delay(ERROR_RETRY_DELAY_MS)
    }

    private fun ensureConsumerGroup(conn: StatefulRedisConnection<String, String>) {
        try {
            conn.sync().xgroupCreate(
                StreamOffset.from(spec.streamKey, "0-0"),
                spec.consumerGroup,
                XGroupCreateArgs().mkstream(true),
            )
        } catch (e: RedisCommandExecutionException) {
            if (!e.message.orEmpty().contains(REDIS_GROUP_EXISTS, ignoreCase = true)) {
                throw e
            }
        }
    }

    private fun registerQueues() {
        OperationalMetrics.registerWorkerQueues(spec.pipeline.workerName, spec.queueKey, spec.dlqKey)
        if (usesStreams()) {
            OperationalMetrics.registerWorkerQueues(spec.pipeline.workerName, spec.streamKey, spec.dlqStreamKey)
        }
    }

    private fun usesStreams(): Boolean =
        when (IngestionQueueSettings.readMode()) {
            IngestionQueueReadMode.LIST -> false
            IngestionQueueReadMode.STREAMS,
            IngestionQueueReadMode.DUAL -> true
        }

    private fun consumerName(workerId: Int): String =
        "${spec.pipeline.id}-${resolveHostName()}-$workerId"

    private fun resolveHostName(): String =
        runCatching { java.net.InetAddress.getLocalHost().hostName }
            .getOrElse { UNKNOWN_HOST }

    companion object {
        private const val UNKNOWN_HOST = "unknown-host"
    }

    private suspend fun <T> withClickHouseDeduplication(
        messages: List<QueuedIngestionMessage>,
        block: suspend () -> T,
    ): T {
        val ids = messages.mapNotNull { it.id }
        val seed = if (ids.isEmpty()) null else "${spec.pipeline.id}:${ids.joinToString(",")}"
        return ClickHouseInsertDeduplication.withTokenSeed(seed) {
            IngestionUsageDeduplication.withSeed(seed, block)
        }
    }
}
