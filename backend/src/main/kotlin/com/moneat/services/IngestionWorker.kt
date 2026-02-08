package com.moneat.services

import com.moneat.config.RedisConfig
import com.moneat.models.SentryEnvelope
import kotlinx.coroutines.*
import mu.KotlinLogging
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
    private val workerCount: Int
) {
    private val emailService = EmailService()
    private val notificationService = NotificationService(emailService)
    private val eventService = EventService(notificationService)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        logger.info { "Starting IngestionWorker with $workerCount workers, queue=$queueKey" }
        jobs = (1..workerCount).map { id ->
            scope.launch {
                runWorker(id)
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        logger.info { "IngestionWorker stopped" }
    }

    private suspend fun runWorker(workerId: Int) {
        while (scope.isActive) {
            try {
                // BRPOP block with 5s timeout so we can check isActive periodically
                val result = RedisConfig.sync().brpop(5, queueKey)
                val value = result?.value ?: continue
                try {
                    val (projectId, envelopeBytes) = decodeMessage(value)
                    val envelope = SentryEnvelope.parse(envelopeBytes)
                    eventService.processEnvelope(projectId, envelope)
                } catch (e: Exception) {
                    logger.error(e) { "Worker $workerId failed to process message, sending to DLQ" }
                    RedisConfig.sync().rpush(dlqKey, value)
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                logger.error(e) { "Worker $workerId error in BRPOP loop" }
                delay(1000)
            }
        }
    }

    companion object {
        /**
         * Decode a queue message: Base64(8 bytes projectId big-endian + envelope bytes).
         */
        fun decodeMessage(encoded: String): Pair<Long, ByteArray> {
            val bytes = Base64.getDecoder().decode(encoded)
            if (bytes.size < 8) throw IllegalArgumentException("Message too short")
            val projectId = ((bytes[0].toLong() and 0xFF) shl 56) or
                ((bytes[1].toLong() and 0xFF) shl 48) or
                ((bytes[2].toLong() and 0xFF) shl 40) or
                ((bytes[3].toLong() and 0xFF) shl 32) or
                ((bytes[4].toLong() and 0xFF) shl 24) or
                ((bytes[5].toLong() and 0xFF) shl 16) or
                ((bytes[6].toLong() and 0xFF) shl 8) or
                (bytes[7].toLong() and 0xFF)
            val envelopeBytes = bytes.copyOfRange(8, bytes.size)
            return projectId to envelopeBytes
        }

        /**
         * Encode projectId and envelope bytes for the queue.
         */
        fun encodeMessage(projectId: Long, envelopeBytes: ByteArray): String {
            val bytes = ByteArray(8 + envelopeBytes.size)
            bytes[0] = (projectId shr 56).toByte()
            bytes[1] = (projectId shr 48).toByte()
            bytes[2] = (projectId shr 40).toByte()
            bytes[3] = (projectId shr 32).toByte()
            bytes[4] = (projectId shr 24).toByte()
            bytes[5] = (projectId shr 16).toByte()
            bytes[6] = (projectId shr 8).toByte()
            bytes[7] = projectId.toByte()
            envelopeBytes.copyInto(bytes, 8)
            return Base64.getEncoder().encodeToString(bytes)
        }
    }
}
