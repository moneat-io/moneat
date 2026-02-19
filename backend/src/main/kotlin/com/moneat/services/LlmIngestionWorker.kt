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

package com.moneat.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.models.LlmGenerationIngest
import com.moneat.models.LlmIngestPayload
import com.moneat.utils.ClickHouseSqlUtils
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.time.Instant
import java.util.*

private val logger = KotlinLogging.logger {}

class LlmIngestionWorker(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int
) {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val json = Json { ignoreUnknownKeys = true }
    private val usageTracker = UsageTrackingService.instance
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        logger.info { "Starting LlmIngestionWorker with $workerCount workers, queue=$queueKey" }
        jobs = (1..workerCount).map { id ->
            scope.launch { runWorker(id) }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        logger.info { "LlmIngestionWorker stopped" }
    }

    private suspend fun runWorker(workerId: Int) {
        while (scope.isActive) {
            try {
                val result = RedisConfig.syncBlocking().brpop(5, queueKey)
                val value = result?.value ?: continue
                processMessageForTest(workerId, value)
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                logger.error(e) { "LLM worker $workerId error in BRPOP loop" }
                delay(1000)
            }
        }
    }

    internal suspend fun processMessageForTest(
        workerId: Int,
        value: String,
        onDlq: (String) -> Unit = { message -> RedisConfig.syncBlocking().rpush(dlqKey, message) }
    ) {
        try {
            val (projectId, payloadBytes) = decodeMessage(value)
            val payload = json.decodeFromString<LlmIngestPayload>(payloadBytes.decodeToString())
            insertGenerations(projectId, payload.generations)
            usageTracker.recordUsage(projectId, "llm", payloadBytes.size)
        } catch (e: Exception) {
            logger.error(e) { "LLM worker $workerId failed to process message, sending to DLQ" }
            onDlq(value)
        }
    }

    suspend fun insertGenerations(projectId: Long, generations: List<LlmGenerationIngest>) {
        if (generations.isEmpty()) return

        val rows = generations.mapNotNull { gen ->
            try {
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
            } catch (e: Exception) {
                logger.warn(e) { "Failed to build row for LLM generation" }
                null
            }
        }

        if (rows.isEmpty()) return

        val query = """
            INSERT INTO $clickhouseDb.llm_generations (
                generation_id, project_id, trace_id, span_id, parent_span_id,
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
            throw IllegalStateException("Failed to insert LLM generations: ${body.take(600)}")
        }
        logger.info { "Inserted ${rows.size} LLM generations for project $projectId" }
    }

    private fun parseTimestampMs(timestamp: String?): Long {
        if (timestamp.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            Instant.parse(timestamp).toEpochMilli()
        } catch (e: Exception) {
            try {
                timestamp.toLong()
            } catch (e2: Exception) {
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

    companion object {
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
            val payloadBytes = bytes.copyOfRange(8, bytes.size)
            return projectId to payloadBytes
        }

        fun encodeMessage(projectId: Long, payloadBytes: ByteArray): String {
            val bytes = ByteArray(8 + payloadBytes.size)
            bytes[0] = (projectId shr 56).toByte()
            bytes[1] = (projectId shr 48).toByte()
            bytes[2] = (projectId shr 40).toByte()
            bytes[3] = (projectId shr 32).toByte()
            bytes[4] = (projectId shr 24).toByte()
            bytes[5] = (projectId shr 16).toByte()
            bytes[6] = (projectId shr 8).toByte()
            bytes[7] = projectId.toByte()
            payloadBytes.copyInto(bytes, 8)
            return Base64.getEncoder().encodeToString(bytes)
        }
    }
}
