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

import com.moneat.config.EnvConfig
import kotlin.math.max

private const val DEFAULT_STREAM_BATCH_SIZE = 50
private const val DEFAULT_CLAIM_IDLE_MS = 300_000L
private const val DEFAULT_MAX_DELIVERIES = 5
private const val DEFAULT_READ_TIMEOUT_MS = 5_000L
private const val DEFAULT_STREAM_MAX_LEN = 250_000L
private const val DEFAULT_DLQ_STREAM_MAX_LEN = 10_000L
private const val MIN_STREAM_BATCH_SIZE = 1

data class IngestionQueueSpec(
    val pipeline: IngestionPipeline,
    val workerCount: Int,
    val streamKey: String,
    val dlqStreamKey: String,
    val consumerGroup: String,
    val batchSize: Int,
    val claimIdleMs: Long,
    val maxDeliveries: Int,
    val readTimeoutMs: Long,
    val streamMaxLen: Long,
    val dlqStreamMaxLen: Long,
)

object IngestionQueueSettings {
    fun spec(
        pipeline: IngestionPipeline,
        queueKey: String,
        dlqKey: String,
        workerCount: Int,
    ): IngestionQueueSpec {
        val prefix = pipeline.envKey
        return IngestionQueueSpec(
            pipeline = pipeline,
            workerCount = max(workerCount, 1),
            streamKey = EnvConfig.get("INGESTION_${prefix}_STREAM_KEY") ?: "$queueKey:stream",
            dlqStreamKey = EnvConfig.get("INGESTION_${prefix}_DLQ_STREAM_KEY") ?: "$dlqKey:stream",
            consumerGroup = EnvConfig.get("INGESTION_${prefix}_CONSUMER_GROUP") ?: pipeline.consumerGroup,
            batchSize = positiveInt("INGESTION_${prefix}_BATCH_SIZE", DEFAULT_STREAM_BATCH_SIZE),
            claimIdleMs = positiveLong("INGESTION_${prefix}_CLAIM_IDLE_MS", DEFAULT_CLAIM_IDLE_MS),
            maxDeliveries = positiveInt("INGESTION_${prefix}_MAX_DELIVERIES", DEFAULT_MAX_DELIVERIES),
            readTimeoutMs = positiveLong("INGESTION_${prefix}_READ_TIMEOUT_MS", DEFAULT_READ_TIMEOUT_MS),
            streamMaxLen = positiveLong("INGESTION_${prefix}_STREAM_MAXLEN", DEFAULT_STREAM_MAX_LEN),
            dlqStreamMaxLen = positiveLong("INGESTION_${prefix}_DLQ_STREAM_MAXLEN", DEFAULT_DLQ_STREAM_MAX_LEN),
        )
    }

    fun selectedPipelines(): Set<IngestionPipeline>? {
        val raw = EnvConfig.get("INGESTION_PIPELINES")?.trim()
        if (raw.isNullOrBlank() || raw.equals("all", ignoreCase = true)) return null
        if (raw.equals("none", ignoreCase = true)) return emptySet()
        return raw
            .split(',')
            .map { value ->
                IngestionPipeline.parse(value)
                    ?: throw IllegalArgumentException("Invalid INGESTION_PIPELINES value '$value'")
            }
            .toSet()
    }

    fun isSelected(pipeline: IngestionPipeline): Boolean =
        selectedPipelines()?.contains(pipeline) ?: true

    private fun positiveInt(
        key: String,
        defaultValue: Int,
    ): Int =
        EnvConfig.get(key)
            ?.toIntOrNull()
            ?.takeIf { it >= MIN_STREAM_BATCH_SIZE }
            ?: defaultValue

    private fun positiveLong(
        key: String,
        defaultValue: Long,
    ): Long =
        EnvConfig.get(key)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: defaultValue
}
