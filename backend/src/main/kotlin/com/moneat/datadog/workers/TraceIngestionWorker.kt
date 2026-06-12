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

package com.moneat.datadog.workers

import com.moneat.config.RedisConfig
import com.moneat.datadog.services.TraceIngestionService
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueSettings
import com.moneat.ingestion.queue.RedisQueueWorker
import com.moneat.monitoring.OperationalMetrics
import com.moneat.utils.brpopLoopBackoff
import com.moneat.utils.pushToDlq
import io.lettuce.core.RedisException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.io.IOException
import java.util.Base64
import com.moneat.utils.suspendRunCatching

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
    private var queueWorker: RedisQueueWorker? = null

    fun start() {
        logger.info {
            "Starting TraceIngestionWorker with " +
                "$workerCount workers, queue=$queueKey"
        }
        val spec = IngestionQueueSettings.spec(IngestionPipeline.DD_TRACES, queueKey, dlqKey, workerCount)
        queueWorker = RedisQueueWorker(spec, logger, ::processMessage).also { it.start() }
    }

    fun stop() {
        queueWorker?.stop()
        logger.info { "TraceIngestionWorker stopped" }
    }
    private suspend fun runWorker(workerId: Int) {
        val conn = RedisConfig.newBlockingConnection()
        try {
            val redis = conn.sync()
            while (scope.isActive) {
                try {
                    val result = redis.brpop(
                        BRPOP_TIMEOUT_SECONDS,
                        queueKey
                    )
                    val payload = result?.value ?: continue
                    processMessage(workerId, payload)
                } catch (e: CancellationException) {
                    break
                } catch (e: RedisException) {
                    brpopLoopBackoff(
                        logger,
                        workerId,
                        "Trace",
                        ERROR_DELAY_MS,
                        e,
                    )
                } catch (e: IOException) {
                    brpopLoopBackoff(
                        logger,
                        workerId,
                        "Trace",
                        ERROR_DELAY_MS,
                        e,
                    )
                }
            }
        } finally {
            RedisConfig.closeBlockingConnection(conn)
        }
    }
    internal suspend fun processMessage(
        workerId: Int,
        payload: String,
    ) {
        suspendRunCatching {
            val wrapper = json.parseToJsonElement(payload).jsonObject
            val organizationId = requireNotNull(wrapper["organization_id"]) {
                "Missing organization_id"
            }.jsonPrimitive.int
            val hostname = wrapper["hostname"]
                ?.jsonPrimitive?.content ?: ""
            val env = wrapper["env"]?.jsonPrimitive?.content ?: ""
            val version = wrapper["version"]
                ?.jsonPrimitive?.content ?: ""
            val format = wrapper["format"]
                ?.jsonPrimitive?.content ?: "msgpack"

            val traces = if (format == "json") {
                val tracesJson = requireNotNull(wrapper["traces"]) {
                    "Missing traces"
                }.jsonArray.toString()
                TraceIngestionService.parseJsonTraces(tracesJson)
            } else if (format == "protobuf") {
                val b64 = requireNotNull(wrapper["data"]) {
                    "Missing data"
                }.jsonPrimitive.content
                val bytes = Base64.getDecoder().decode(b64)
                TraceIngestionService.parseProtobufAgentPayload(bytes)
            } else {
                val b64 = requireNotNull(wrapper["data"]) {
                    "Missing data"
                }.jsonPrimitive.content
                val bytes = Base64.getDecoder().decode(b64)
                TraceIngestionService.parseMsgpackTraces(bytes)
            }

            TraceIngestionService.insertTraces(
                organizationId,
                traces,
                hostname,
                env,
                version
            )
            OperationalMetrics.recordWorkerMessageProcessed("Trace", workerId)
        }.getOrElse { e ->
            pushToDlq(logger, dlqKey, payload, workerId, "Trace", e)
        }
    }
}
