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
import io.lettuce.core.api.sync.RedisCommands
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mu.KLogger
import java.io.IOException
import java.time.Duration

private const val ERROR_RETRY_DELAY_MS = 1_000L
private const val STOP_JOIN_TIMEOUT_MS = 5_000L
private const val REDIS_GROUP_EXISTS = "BUSYGROUP"
private const val REDIS_GROUP_MISSING = "NOGROUP"
private const val REDIS_STREAM_START_ID = "0-0"

data class QueuedIngestionMessage(
    val id: String?,
    val payload: String,
    val deliveryCount: Long = 1L,
)

internal object RedisStreamQueueOperations {
    /**
     * Redis Streams consumer groups can disappear if Redis evicts or trims the stream key.
     */
    fun readMessages(
        redis: RedisCommands<String, String>,
        spec: IngestionQueueSpec,
        consumer: Consumer<String>,
        logger: KLogger,
    ): List<StreamMessage<String, String>> =
        try {
            readMessagesOrThrow(redis, spec, consumer)
        } catch (e: RedisCommandExecutionException) {
            if (!isMissingConsumerGroup(e)) throw e
            ensureConsumerGroup(redis, spec.streamKey, spec.consumerGroup)
            logger.warn(e) {
                "Recreated missing Redis stream consumer group ${spec.consumerGroup} for ${spec.streamKey}"
            }
            emptyList()
        }

    fun ensureConsumerGroup(
        redis: RedisCommands<String, String>,
        streamKey: String,
        consumerGroup: String,
    ) {
        try {
            redis.xgroupCreate(
                StreamOffset.from(streamKey, REDIS_STREAM_START_ID),
                consumerGroup,
                XGroupCreateArgs().mkstream(true),
            )
        } catch (e: RedisCommandExecutionException) {
            if (!e.message.orEmpty().contains(REDIS_GROUP_EXISTS, ignoreCase = true)) {
                throw e
            }
        }
    }

    fun acknowledge(
        redis: RedisCommands<String, String>,
        streamKey: String,
        consumerGroup: String,
        ids: Array<String>,
        logger: KLogger,
    ) {
        if (ids.isEmpty()) return
        try {
            redis.xack(streamKey, consumerGroup, *ids)
        } catch (e: RedisCommandExecutionException) {
            if (!isMissingConsumerGroup(e)) throw e
            logger.warn(e) {
                "Redis stream consumer group $consumerGroup disappeared before ack for $streamKey"
            }
            return
        }
        runCatching {
            redis.xdel(streamKey, *ids)
        }.onFailure { e ->
            logger.warn(e) { "Failed to delete acknowledged Redis stream entries from $streamKey" }
        }
    }

    private fun readMessagesOrThrow(
        redis: RedisCommands<String, String>,
        spec: IngestionQueueSpec,
        consumer: Consumer<String>,
    ): List<StreamMessage<String, String>> {
        val claimed = redis.xautoclaim(
            spec.streamKey,
            XAutoClaimArgs<String>()
                .consumer(consumer)
                .minIdleTime(Duration.ofMillis(spec.claimIdleMs))
                .startId(REDIS_STREAM_START_ID)
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

    private fun isMissingConsumerGroup(e: RedisCommandExecutionException): Boolean =
        e.message.orEmpty().contains(REDIS_GROUP_MISSING, ignoreCase = true)
}

class RedisQueueWorker(
    private val spec: IngestionQueueSpec,
    private val logger: KLogger,
    private val processMessage: suspend (workerId: Int, payload: String) -> Unit,
    private val processBatch: (suspend (workerId: Int, messages: List<QueuedIngestionMessage>) -> Unit)? = null,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    init {
        require(spec.readTimeoutMs <= STOP_JOIN_TIMEOUT_MS) {
            "${spec.pipeline.workerName} queue read timeout must be <= ${STOP_JOIN_TIMEOUT_MS}ms"
        }
    }

    fun start() {
        registerQueues()
        logger.info {
            "Starting ${spec.pipeline.workerName} queue worker with " +
                "${spec.workerCount} workers, stream=${spec.streamKey}"
        }
        jobs = (1..spec.workerCount).map { workerId ->
            scope.launch { runWorker(workerId) }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        val stopped = runBlocking {
            withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) {
                jobs.joinAll()
            }
        } != null
        if (stopped) {
            logger.info { "${spec.pipeline.workerName} queue worker stopped" }
        } else {
            logger.warn { "${spec.pipeline.workerName} queue worker did not stop within ${STOP_JOIN_TIMEOUT_MS}ms" }
        }
    }

    private suspend fun runWorker(workerId: Int) {
        if (!scope.isActive) return
        var conn: StatefulRedisConnection<String, String>? = null
        try {
            conn = RedisConfig.newBlockingConnection()
            ensureConsumerGroup(conn)
            runWorkerLoop(workerId, conn)
        } catch (e: CancellationException) {
            // Worker is shutting down before or during connection setup.
        } catch (e: RedisException) {
            onQueueLoopFailure(workerId, e)
        } catch (e: IOException) {
            onQueueLoopFailure(workerId, e)
        } catch (e: IllegalStateException) {
            onActiveStartupFailure(workerId, e)
        } finally {
            conn?.let { RedisConfig.closeBlockingConnection(it) }
        }
    }

    private suspend fun runWorkerLoop(
        workerId: Int,
        conn: StatefulRedisConnection<String, String>,
    ) {
        while (scope.isActive) {
            try {
                consumeStreams(workerId, conn)
            } catch (e: CancellationException) {
                break
            } catch (e: RedisException) {
                onQueueLoopFailure(workerId, e)
            } catch (e: IOException) {
                onQueueLoopFailure(workerId, e)
            } catch (e: IllegalStateException) {
                onQueueLoopFailure(workerId, e)
            }
        }
    }

    private suspend fun onActiveStartupFailure(workerId: Int, error: IllegalStateException) {
        if (scope.isActive) {
            onQueueLoopFailure(workerId, error)
        }
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
        return RedisStreamQueueOperations.readMessages(redis, spec, consumer, logger)
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
        RedisStreamQueueOperations.acknowledge(conn.sync(), spec.streamKey, spec.consumerGroup, ids, logger)
    }

    private suspend fun onQueueLoopFailure(
        workerId: Int,
        e: Throwable,
    ) {
        logger.error(e) { "${spec.pipeline.workerName} worker $workerId error in queue loop" }
        OperationalMetrics.recordWorkerQueueLoopFailure(spec.pipeline.workerName, workerId, e)
        delay(ERROR_RETRY_DELAY_MS)
    }

    private fun ensureConsumerGroup(conn: StatefulRedisConnection<String, String>) {
        RedisStreamQueueOperations.ensureConsumerGroup(conn.sync(), spec.streamKey, spec.consumerGroup)
    }

    private fun registerQueues() {
        OperationalMetrics.registerWorkerQueues(
            spec.pipeline.workerName,
            spec.streamKey,
            spec.dlqStreamKey,
            spec.consumerGroup,
        )
        OperationalMetrics.registerWorkerStream(
            workerName = spec.pipeline.workerName,
            streamKey = spec.streamKey,
            streamType = "primary",
            consumerGroup = spec.consumerGroup,
        )
        OperationalMetrics.registerWorkerStream(
            workerName = spec.pipeline.workerName,
            streamKey = spec.dlqStreamKey,
            streamType = "dlq",
        )
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
