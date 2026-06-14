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

import com.moneat.config.RedisConfig
import com.moneat.monitoring.OperationalMetrics
import io.lettuce.core.XAddArgs
import mu.KLogger

private const val FIELD_PAYLOAD = "payload"
private const val FIELD_PIPELINE = "pipeline"
private const val FIELD_ENQUEUED_AT = "enqueued_at_ms"
private const val FIELD_ERROR_TYPE = "error_type"
private const val FIELD_ERROR_MESSAGE = "error_message"
private const val FIELD_ORIGINAL_STREAM_ID = "original_stream_id"
private const val ERROR_MESSAGE_PREVIEW_CHARS = 1_000

data class IngestionDlqRequest(
    val spec: IngestionQueueSpec,
    val payload: String,
    val workerId: Int,
    val cause: Throwable,
    val streamId: String? = null,
)

object IngestionQueueClient {
    fun enqueue(
        pipeline: IngestionPipeline,
        queueKey: String,
        payload: String,
    ): String? {
        val spec = IngestionQueueSettings.spec(pipeline, queueKey, "$queueKey:dlq", workerCount = 1)
        return when (IngestionQueueSettings.backend()) {
            IngestionQueueBackend.REDIS_LIST -> {
                RedisConfig.sync().lpush(queueKey, payload)
                null
            }
            IngestionQueueBackend.REDIS_STREAMS ->
                RedisConfig.sync().xadd(spec.streamKey, streamAddArgs(spec.streamMaxLen), streamBody(pipeline, payload))
        }
    }

    fun pushToDlq(
        logger: KLogger,
        request: IngestionDlqRequest,
    ): Boolean {
        val spec = request.spec
        logger.error(request.cause) {
            "${spec.pipeline.workerName} worker ${request.workerId} failed, pushing to DLQ"
        }
        OperationalMetrics.recordWorkerProcessingFailure(spec.pipeline.workerName, request.workerId, request.cause)
        return runCatching {
            when (IngestionQueueSettings.backend()) {
                IngestionQueueBackend.REDIS_LIST -> RedisConfig.sync().rpush(spec.dlqKey, request.payload)
                IngestionQueueBackend.REDIS_STREAMS -> {
                    RedisConfig.sync().xadd(
                        spec.dlqStreamKey,
                        streamAddArgs(spec.dlqStreamMaxLen),
                        streamDlqBody(spec.pipeline, request.payload, request.cause, request.streamId)
                    )
                    1L
                }
            }
        }.onSuccess {
            OperationalMetrics.recordDlqPush(spec.pipeline.workerName, dlqMetricKey(spec), "success")
        }.onFailure { dlqErr ->
            OperationalMetrics.recordDlqPush(spec.pipeline.workerName, dlqMetricKey(spec), "failure")
            logger.error(dlqErr) {
                "Failed to write to DLQ for worker ${request.workerId}, dlqKey=${dlqMetricKey(spec)}"
            }
        }.isSuccess
    }

    internal fun payloadField(body: Map<String, String>): String? = body[FIELD_PAYLOAD]

    private fun streamBody(
        pipeline: IngestionPipeline,
        payload: String,
    ): Map<String, String> =
        mapOf(
            FIELD_PAYLOAD to payload,
            FIELD_PIPELINE to pipeline.id,
            FIELD_ENQUEUED_AT to System.currentTimeMillis().toString(),
        )

    private fun streamAddArgs(maxLen: Long): XAddArgs =
        XAddArgs()
            .maxlen(maxLen)
            .approximateTrimming()

    private fun streamDlqBody(
        pipeline: IngestionPipeline,
        payload: String,
        cause: Throwable,
        streamId: String?,
    ): Map<String, String> =
        buildMap {
            putAll(streamBody(pipeline, payload))
            put(FIELD_ERROR_TYPE, cause::class.simpleName ?: cause.javaClass.simpleName)
            put(FIELD_ERROR_MESSAGE, cause.message.orEmpty().take(ERROR_MESSAGE_PREVIEW_CHARS))
            if (streamId != null) {
                put(FIELD_ORIGINAL_STREAM_ID, streamId)
            }
        }

    private fun dlqMetricKey(spec: IngestionQueueSpec): String =
        if (IngestionQueueSettings.backend() == IngestionQueueBackend.REDIS_STREAMS) {
            spec.dlqStreamKey
        } else {
            spec.dlqKey
        }
}
