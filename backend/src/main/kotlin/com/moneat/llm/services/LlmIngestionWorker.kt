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

package com.moneat.llm.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.ingestion.queue.IngestionDlqRequest
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.ingestion.queue.IngestionQueueSettings
import com.moneat.ingestion.queue.QueuedIngestionMessage
import com.moneat.ingestion.queue.RedisQueueWorker
import com.moneat.llm.models.LlmGenerationIngest
import com.moneat.llm.models.LlmIngestPayload
import com.moneat.monitoring.OperationalMetrics
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.ClickHouseSqlUtils
import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.nio.ByteBuffer
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

private val logger = KotlinLogging.logger {}

private const val ERROR_BODY_PREVIEW_CHARS = 600
private const val PROJECT_ID_HEADER_SIZE = 8

class LlmIngestionWorker(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int
) {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val json = Json { ignoreUnknownKeys = true }
    private val usageTracker = UsageTrackingService.instance
    private var queueWorker: RedisQueueWorker? = null

    fun start() {
        logger.info { "Starting LlmIngestionWorker with $workerCount workers, queue=$queueKey" }
        val spec = IngestionQueueSettings.spec(IngestionPipeline.LLM, queueKey, dlqKey, workerCount)
        queueWorker = RedisQueueWorker(
            spec = spec,
            logger = logger,
            processMessage = { workerId, payload ->
                processMessageForTest(workerId, payload) { message ->
                    IngestionQueueClient.pushToDlq(
                        logger = logger,
                        request = IngestionDlqRequest(
                            spec = spec,
                            payload = message,
                            workerId = workerId,
                            cause = IllegalStateException("LLM processing failed"),
                        ),
                    )
                }
            },
            processBatch = ::processBatch,
        ).also { it.start() }
    }

    fun stop() {
        queueWorker?.stop()
        logger.info { "LlmIngestionWorker stopped" }
    }

    internal suspend fun processMessageForTest(
        workerId: Int,
        value: String,
        onDlq: (String) -> Unit = { message -> RedisConfig.sync().rpush(dlqKey, message) }
    ) {
        suspendRunCatching {
            val (projectId, payloadBytes) = decodeMessage(value)
            val payload = json.decodeFromString<LlmIngestPayload>(payloadBytes.decodeToString())
            insertGenerations(projectId, payload.generations)
            usageTracker.recordUsage(projectId, "llm", payloadBytes.size)
            OperationalMetrics.recordWorkerMessageProcessed("LLM", workerId)
        }.getOrElse { e ->
            handleLlmDlq(workerId, value, e, onDlq)
        }
    }

    private suspend fun handleLlmDlq(
        workerId: Int,
        value: String,
        e: Throwable,
        onDlq: (String) -> Unit,
    ) {
        logger.error(e) { "LLM worker $workerId failed to process message, sending to DLQ" }
        OperationalMetrics.recordWorkerProcessingFailure("LLM", workerId, e)
        suspendRunCatching {
            onDlq(value)
        }.onSuccess {
            OperationalMetrics.recordDlqPush("LLM", dlqKey, "success")
        }.onFailure { dlqErr ->
            OperationalMetrics.recordDlqPush("LLM", dlqKey, "failure")
            logger.error(dlqErr) { "Failed to push LLM message to DLQ" }
        }.getOrThrow()
    }

    private suspend fun processBatch(
        workerId: Int,
        messages: List<QueuedIngestionMessage>,
    ) {
        val decodedMessages =
            messages.map { message ->
                val (projectId, payloadBytes) = decodeMessage(message.payload)
                DecodedLlmMessage(
                    projectId = projectId,
                    payloadBytes = payloadBytes,
                    payload = json.decodeFromString<LlmIngestPayload>(payloadBytes.decodeToString()),
                )
            }

        decodedMessages
            .groupBy { message -> message.projectId }
            .forEach { (projectId, messagesForProject) ->
                insertGenerations(
                    projectId = projectId,
                    generations = messagesForProject.flatMap { message -> message.payload.generations },
                )
            }

        decodedMessages.forEach { message ->
            usageTracker.recordUsage(message.projectId, "llm", message.payloadBytes.size)
            OperationalMetrics.recordWorkerMessageProcessed("LLM", workerId)
        }
    }

    suspend fun insertGenerations(
        projectId: Long,
        generations: List<LlmGenerationIngest>
    ) {
        if (generations.isEmpty()) return

        val rows =
            generations.mapNotNull { gen ->
                runCatching {
                    val generationId = UUID.randomUUID().toString()
                    val timestampMs = parseTimestampMs(gen.timestamp)
                    val totalTokens = gen.inputTokens + gen.outputTokens
                    val typeValue = mapType(gen.type)
                    val statusValue = if (gen.status == "error") "error" else "success"
                    val inputStr = gen.input?.toString() ?: ""
                    val outputStr = gen.output?.toString() ?: ""
                    val metadataStr = gen.metadata?.toString() ?: "{}"
                    val tags = tagsToMap(gen.tags)

                    """(
                    toUUID('$generationId'),
                    $projectId,
                    $projectId,
                    '${esc(gen.traceId)}',
                    '${esc(gen.spanId)}',
                    '${esc(gen.parentSpanId)}',
                    fromUnixTimestamp64Milli($timestampMs),
                    ${gen.durationMs},
                    '${esc(gen.name)}',
                    '${esc(gen.model)}',
                    '${esc(gen.provider)}',
                    '$typeValue',
                    '${esc(inputStr)}',
                    '${esc(outputStr)}',
                    ${gen.inputTokens},
                    ${gen.outputTokens},
                    $totalTokens,
                    ${gen.costUsd},
                    ${gen.temperature},
                    ${gen.maxTokens},
                    ${gen.topP},
                    '$statusValue',
                    '${esc(gen.errorMessage)}',
                    ${gen.statusCode},
                    '${esc(gen.userId)}',
                    '${esc(gen.sessionId)}',
                    '${esc(gen.environment)}',
                    '${esc(gen.release)}',
                    $tags,
                    '${esc(metadataStr)}'
                )
                    """.trimIndent()
                }.fold(
                    onSuccess = { it },
                    onFailure = { e ->
                        logger.warn(e) { "Failed to build row for LLM generation" }
                        null
                    },
                )
            }

        if (rows.isEmpty()) return

        val query =
            """
            INSERT INTO `$clickhouseDb`.llm_generations (
                generation_id, service_id, project_id, trace_id, span_id, parent_span_id,
                timestamp, duration_ms, name, model, provider, type,
                input, output, input_tokens, output_tokens, total_tokens, cost_usd,
                temperature, max_tokens, top_p,
                status, error_message, status_code,
                user_id, session_id, environment, release, tags, metadata
            ) VALUES
            ${rows.joinToString(",\n")}
            """.trimIndent()

        val response = ClickHouseClient.execute(query)
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw IllegalStateException("Failed to insert LLM generations: ${body.take(ERROR_BODY_PREVIEW_CHARS)}")
        }
        logger.info { "Inserted ${rows.size} LLM generations for project $projectId" }
    }

    private fun parseTimestampMs(timestamp: String?): Long {
        if (timestamp.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            Instant.parse(timestamp).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                timestamp.toLong()
            } catch (_: NumberFormatException) {
                System.currentTimeMillis()
            }
        }
    }

    private fun mapType(type: String): String {
        return when (type.lowercase()) {
            "chat" -> "chat"
            "completion" -> "completion"
            "embedding" -> "embedding"
            "tool_call" -> "tool_call"
            "agent" -> "agent"
            "chain" -> "chain"
            "retriever" -> "retriever"
            else -> "chat"
        }
    }

    private fun esc(value: String): String = ClickHouseSqlUtils.escapeSql(value)

    private fun tagsToMap(tags: Map<String, String>?): String {
        if (tags.isNullOrEmpty()) return "{}"
        return "{${tags.entries.joinToString(",") { "'${esc(it.key)}':'${esc(it.value)}'" }}}"
    }

    private class DecodedLlmMessage(
        val projectId: Long,
        val payloadBytes: ByteArray,
        val payload: LlmIngestPayload,
    )

    companion object {
        fun decodeMessage(encoded: String): Pair<Long, ByteArray> {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size >= PROJECT_ID_HEADER_SIZE) { "Message too short" }
            val projectId = ByteBuffer.wrap(bytes, 0, PROJECT_ID_HEADER_SIZE).long
            val payloadBytes = bytes.copyOfRange(PROJECT_ID_HEADER_SIZE, bytes.size)
            return projectId to payloadBytes
        }

        fun encodeMessage(
            projectId: Long,
            payloadBytes: ByteArray
        ): String {
            val bytes = ByteArray(PROJECT_ID_HEADER_SIZE + payloadBytes.size)
            ByteBuffer.wrap(bytes).putLong(projectId)
            payloadBytes.copyInto(bytes, PROJECT_ID_HEADER_SIZE)
            return Base64.getEncoder().encodeToString(bytes)
        }
    }
}
