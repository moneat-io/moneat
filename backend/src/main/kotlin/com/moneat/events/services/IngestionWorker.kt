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

package com.moneat.events.services

import com.moneat.config.BRPOP_TIMEOUT_SECONDS
import com.moneat.config.RedisConfig
import com.moneat.events.models.SentryEnvelope
import com.moneat.events.repositories.EventRepositoryImpl
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.NotificationService
import com.moneat.utils.SentryUtils
import com.moneat.utils.brpopLoopBackoff
import com.moneat.utils.suspendRunCatching
import io.sentry.Sentry
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
import java.nio.ByteBuffer
import java.util.*

private val logger = KotlinLogging.logger {}
private const val ERROR_DELAY_MS = 1000L

/**
 * Background worker that drains the ingestion queue (Redis list),
 * deserializes envelope messages, and processes them via EventService.
 * On failure, messages are pushed to a dead-letter queue.
 */
class IngestionWorker(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int,
    private val eventService: EventService = run {
        val emailService = EmailService()
        EventService(NotificationService(emailService), EventRepositoryImpl())
    },
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        logger.info { "Starting IngestionWorker with $workerCount workers, queue=$queueKey" }
        SentryUtils.breadcrumb(
            "worker",
            "IngestionWorker starting",
            mapOf(
                "worker_count" to workerCount,
                "queue" to queueKey
            )
        )
        jobs =
            (1..workerCount).map { id ->
                scope.launch {
                    runWorker(id)
                }
            }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        logger.info { "IngestionWorker stopped" }
        SentryUtils.breadcrumb(
            "worker",
            "IngestionWorker stopped",
            emptyMap()
        )
    }

    private suspend fun runWorker(workerId: Int) {
        val conn = RedisConfig.newBlockingConnection()
        try {
            val redis = conn.sync()
            while (scope.isActive) {
                try {
                    // BRPOP block with 5s timeout so we can check isActive periodically
                    val result = redis.brpop(BRPOP_TIMEOUT_SECONDS, queueKey)
                    val value = result?.value ?: continue
                    processMessageForTest(workerId, value) { message ->
                        RedisConfig.sync().rpush(dlqKey, message)
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: SerializationException) {
                    brpopLoopBackoff(logger, workerId, "Event", ERROR_DELAY_MS, e)
                } catch (e: IOException) {
                    brpopLoopBackoff(logger, workerId, "Event", ERROR_DELAY_MS, e)
                } catch (e: IllegalStateException) {
                    brpopLoopBackoff(logger, workerId, "Event", ERROR_DELAY_MS, e)
                } catch (e: IllegalArgumentException) {
                    brpopLoopBackoff(logger, workerId, "Event", ERROR_DELAY_MS, e)
                }
            }
        } finally {
            RedisConfig.closeBlockingConnection(conn)
        }
    }

    internal suspend fun processMessageForTest(
        workerId: Int,
        value: String,
        onDlq: (String) -> Unit = { message -> RedisConfig.sync().rpush(dlqKey, message) }
    ) {
        suspendRunCatching {
            val (projectId, envelopeBytes) = decodeMessage(value)

            SentryUtils.breadcrumb(
                "ingestion",
                "Processing envelope",
                mapOf(
                    "project_id" to projectId,
                    "size_bytes" to envelopeBytes.size
                )
            )

            val envelope = SentryEnvelope.parse(envelopeBytes)
            eventService.processEnvelope(projectId, envelope)
        }.onFailure { e ->
            logger.error(e) { "Worker $workerId failed to process message, sending to DLQ" }
            onDlq(value)
            Sentry.captureException(e) { scope ->
                scope.setTag("worker.operation", "process_message")
                scope.setTag("worker.id", workerId.toString())
                scope.setExtra("queue", queueKey)
            }
        }
    }

    companion object {
        private const val PROJECT_ID_BYTE_LENGTH = 8

        /**
         * Decode a queue message: Base64(8 bytes projectId big-endian + envelope bytes).
         */
        fun decodeMessage(encoded: String): Pair<Long, ByteArray> {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size >= PROJECT_ID_BYTE_LENGTH) { "Message too short" }
            val projectId = ByteBuffer.wrap(bytes, 0, PROJECT_ID_BYTE_LENGTH).long
            val envelopeBytes = bytes.copyOfRange(PROJECT_ID_BYTE_LENGTH, bytes.size)
            return projectId to envelopeBytes
        }

        /**
         * Encode projectId and envelope bytes for the queue.
         */
        fun encodeMessage(
            projectId: Long,
            envelopeBytes: ByteArray
        ): String {
            val bytes = ByteArray(PROJECT_ID_BYTE_LENGTH + envelopeBytes.size)
            ByteBuffer.wrap(bytes).putLong(projectId)
            envelopeBytes.copyInto(bytes, PROJECT_ID_BYTE_LENGTH)
            return Base64.getEncoder().encodeToString(bytes)
        }
    }
}
