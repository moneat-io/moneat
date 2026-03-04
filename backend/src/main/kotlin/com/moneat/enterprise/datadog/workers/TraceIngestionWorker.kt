// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.workers

import com.moneat.config.RedisConfig
import com.moneat.enterprise.datadog.services.TraceIngestionService
import io.sentry.Sentry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging
import java.util.Base64

private val logger = KotlinLogging.logger {}

private const val BRPOP_TIMEOUT_SECONDS = 5L
private const val ERROR_DELAY_MS = 1000L

class TraceIngestionWorker(
    private val queueKey: String = "moneat:traces:queue",
    private val dlqKey: String = "moneat:traces:dlq",
    private val workerCount: Int = 2,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private var jobs: List<Job> = emptyList()

    fun start() {
        logger.info {
            "Starting TraceIngestionWorker with " +
                "$workerCount workers, queue=$queueKey"
        }
        jobs = (1..workerCount).map { workerId ->
            scope.launch { runWorker(workerId) }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        logger.info { "TraceIngestionWorker stopped" }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runWorker(workerId: Int) {
        val redis = RedisConfig.newBlockingConnection()
        try {
            while (scope.isActive) {
                try {
                    val result = redis.brpop(
                        BRPOP_TIMEOUT_SECONDS, queueKey
                    )
                    val payload = result?.value ?: continue
                    processMessage(workerId, payload)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    logger.error(e) {
                        "Trace worker $workerId error in BRPOP loop"
                    }
                    delay(ERROR_DELAY_MS)
                }
            }
        } finally {
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal suspend fun processMessage(
        workerId: Int,
        payload: String,
    ) {
        try {
            val wrapper = json.parseToJsonElement(payload).jsonObject
            val organizationId = wrapper["organization_id"]!!
                .jsonPrimitive.int
            val hostname = wrapper["hostname"]
                ?.jsonPrimitive?.content ?: ""
            val env = wrapper["env"]?.jsonPrimitive?.content ?: ""
            val version = wrapper["version"]
                ?.jsonPrimitive?.content ?: ""
            val format = wrapper["format"]
                ?.jsonPrimitive?.content ?: "msgpack"

            val traces = if (format == "json") {
                val tracesJson = wrapper["traces"]!!.jsonArray.toString()
                TraceIngestionService.parseJsonTraces(tracesJson)
            } else if (format == "protobuf") {
                val b64 = wrapper["data"]!!.jsonPrimitive.content
                val bytes = Base64.getDecoder().decode(b64)
                TraceIngestionService.parseProtobufAgentPayload(bytes)
            } else {
                val b64 = wrapper["data"]!!.jsonPrimitive.content
                val bytes = Base64.getDecoder().decode(b64)
                TraceIngestionService.parseMsgpackTraces(bytes)
            }

            TraceIngestionService.insertTraces(
                organizationId, traces, hostname, env, version
            )
        } catch (e: Exception) {
            logger.error(e) {
                "Trace worker $workerId failed to process, " +
                    "pushing to DLQ"
            }
            Sentry.captureException(e)
            RedisConfig.sync().rpush(dlqKey, payload)
        }
    }
}
