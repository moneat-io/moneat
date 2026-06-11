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

import com.moneat.config.RedisConfig
import com.moneat.events.models.SentryEnvelope
import com.moneat.events.repositories.EventRepositoryImpl
import com.moneat.ingestion.queue.IngestionDlqRequest
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.ingestion.queue.IngestionQueueSettings
import com.moneat.ingestion.queue.RedisQueueWorker
import com.moneat.monitoring.OperationalMetrics
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.NotificationService
import com.moneat.utils.SentryUtils
import com.moneat.utils.suspendRunCatching
import io.sentry.Sentry
import mu.KotlinLogging
import java.nio.ByteBuffer
import java.util.Base64

private val logger = KotlinLogging.logger {}

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

    private var queueWorker: RedisQueueWorker? = null

    fun start() {
        logger.info { "Starting IngestionWorker with $workerCount workers, queue=$queueKey" }
        val spec = IngestionQueueSettings.spec(IngestionPipeline.EVENTS, queueKey, dlqKey, workerCount)
        queueWorker = RedisQueueWorker(spec, logger, processMessage = { workerId, payload ->
            processMessageForTest(workerId, payload) { message ->
                IngestionQueueClient.pushToDlq(
                    logger = logger,
                    request = IngestionDlqRequest(
                        spec = spec,
                        payload = message,
                        workerId = workerId,
                        cause = IllegalStateException("Event processing failed"),
                    ),
                )
            }
        }).also { it.start() }
        SentryUtils.breadcrumb(
            "worker",
            "IngestionWorker starting",
            mapOf(
                "worker_count" to workerCount,
                "queue" to queueKey
            )
        )
    }

    fun stop() {
        queueWorker?.stop()
        logger.info { "IngestionWorker stopped" }
        SentryUtils.breadcrumb(
            "worker",
            "IngestionWorker stopped",
            emptyMap()
        )
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
            OperationalMetrics.recordWorkerMessageProcessed("Event", workerId)
        }.onFailure { e ->
            logger.error(e) { "Worker $workerId failed to process message, sending to DLQ" }
            OperationalMetrics.recordWorkerProcessingFailure("Event", workerId, e)
            suspendRunCatching {
                onDlq(value)
            }.onSuccess {
                OperationalMetrics.recordDlqPush("Event", dlqKey, "success")
            }.onFailure { dlqErr ->
                OperationalMetrics.recordDlqPush("Event", dlqKey, "failure")
                logger.error(dlqErr) { "Failed to push event message to DLQ" }
            }.getOrThrow()
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
