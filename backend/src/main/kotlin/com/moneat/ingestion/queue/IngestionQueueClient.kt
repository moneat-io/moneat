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
import io.lettuce.core.RedisException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.XAddArgs
import io.lettuce.core.api.sync.RedisCommands
import mu.KLogger

private const val FIELD_PAYLOAD = "payload"
private const val FIELD_PIPELINE = "pipeline"
private const val FIELD_ENQUEUED_AT = "enqueued_at_ms"
private const val FIELD_ERROR_TYPE = "error_type"
private const val FIELD_ERROR_MESSAGE = "error_message"
private const val FIELD_ORIGINAL_STREAM_ID = "original_stream_id"
private const val ERROR_MESSAGE_PREVIEW_CHARS = 1_000
private const val DEFAULT_RETRY_AFTER_SECONDS = 5

private val ADMIT_TO_STREAM_SCRIPT =
    """
    local depth = redis.call('XLEN', KEYS[1])
    if depth >= tonumber(ARGV[1]) then
        return nil
    end
    return redis.call(
        'XADD', KEYS[1], '*',
        'payload', ARGV[2],
        'pipeline', ARGV[3],
        'enqueued_at_ms', ARGV[4]
    )
    """.trimIndent()

sealed class IngestionQueueAdmissionException(
    val pipeline: IngestionPipeline,
    val retryAfterSeconds: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class IngestionQueueCapacityException(
    pipeline: IngestionPipeline,
    val capacity: Long,
    retryAfterSeconds: Int = DEFAULT_RETRY_AFTER_SECONDS,
) : IngestionQueueAdmissionException(
    pipeline = pipeline,
    retryAfterSeconds = retryAfterSeconds,
    message = "Ingestion queue ${pipeline.id} is at its configured capacity of $capacity entries",
)

class IngestionQueueUnavailableException(
    pipeline: IngestionPipeline,
    cause: Throwable,
    retryAfterSeconds: Int = DEFAULT_RETRY_AFTER_SECONDS,
) : IngestionQueueAdmissionException(
    pipeline = pipeline,
    retryAfterSeconds = retryAfterSeconds,
    message = "Ingestion queue ${pipeline.id} is temporarily unavailable",
    cause = cause,
)

inline fun <T> admitWithQuotaRefund(
    refund: () -> Unit,
    admit: () -> T,
): T =
    try {
        admit()
    } catch (e: IngestionQueueAdmissionException) {
        refund()
        throw e
    }

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
        val spec = IngestionQueueSettings.spec(
            pipeline,
            queueKey,
            IngestionQueueSettings.defaultDlqKey(queueKey),
            workerCount = 1,
        )
        OperationalMetrics.registerIngestionQueue(
            pipeline = pipeline,
            streamKey = spec.streamKey,
            dlqStreamKey = spec.dlqStreamKey,
            consumerGroup = spec.consumerGroup,
            capacity = spec.maxPendingEntries,
        )
        return try {
            val streamId = admit(RedisConfig.sync(), spec, payload, System.currentTimeMillis())
            if (streamId == null) {
                OperationalMetrics.recordIngestionAdmission(pipeline, "capacity_rejected")
                throw IngestionQueueCapacityException(pipeline, spec.maxPendingEntries)
            }
            OperationalMetrics.recordIngestionAdmission(pipeline, "accepted")
            streamId
        } catch (e: IngestionQueueCapacityException) {
            throw e
        } catch (e: RedisException) {
            OperationalMetrics.recordIngestionAdmission(pipeline, "unavailable")
            throw IngestionQueueUnavailableException(pipeline, e)
        } catch (e: IllegalStateException) {
            OperationalMetrics.recordIngestionAdmission(pipeline, "unavailable")
            throw IngestionQueueUnavailableException(pipeline, e)
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
            RedisConfig.sync().xadd(
                spec.dlqStreamKey,
                streamAddArgs(spec.dlqStreamMaxLen),
                streamDlqBody(spec.pipeline, request.payload, request.cause, request.streamId)
            )
            1L
        }.onSuccess {
            OperationalMetrics.recordDlqPush(spec.pipeline.workerName, spec.dlqStreamKey, "success")
            OperationalMetrics.recordIngestionDlqPush(spec.pipeline, "success")
        }.onFailure { dlqErr ->
            OperationalMetrics.recordDlqPush(spec.pipeline.workerName, spec.dlqStreamKey, "failure")
            OperationalMetrics.recordIngestionDlqPush(spec.pipeline, "failure")
            logger.error(dlqErr) {
                "Failed to write to DLQ for worker ${request.workerId}, dlqKey=${spec.dlqStreamKey}"
            }
        }.isSuccess
    }

    internal fun payloadField(body: Map<String, String>): String? = body[FIELD_PAYLOAD]

    fun admit(
        redis: RedisCommands<String, String>,
        spec: IngestionQueueSpec,
        payload: String,
        enqueuedAtMs: Long,
    ): String? =
        redis.eval(
            ADMIT_TO_STREAM_SCRIPT,
            ScriptOutputType.VALUE,
            arrayOf(spec.streamKey),
            spec.maxPendingEntries.toString(),
            payload,
            spec.pipeline.id,
            enqueuedAtMs.toString(),
        )

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
}
