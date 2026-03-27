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
import com.moneat.utils.brpopLoopBackoff
import io.lettuce.core.RedisException
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.io.IOException
import java.sql.SQLException
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
                organizationId,
                traces,
                hostname,
                env,
                version
            )
        } catch (e: SerializationException) {
            handleTraceDlq(workerId, payload, e)
        } catch (e: IOException) {
            handleTraceDlq(workerId, payload, e)
        } catch (e: SQLException) {
            handleTraceDlq(workerId, payload, e)
        } catch (e: RedisException) {
            handleTraceDlq(workerId, payload, e)
        } catch (e: IllegalStateException) {
            handleTraceDlq(workerId, payload, e)
        } catch (e: IllegalArgumentException) {
            handleTraceDlq(workerId, payload, e)
        }
    }

    private fun handleTraceDlq(
        workerId: Int,
        payload: String,
        e: Throwable,
    ) {
        logger.error(e) {
            "Trace worker $workerId failed to process, " +
                "pushing to DLQ"
        }
        Sentry.captureException(e)
        RedisConfig.sync().rpush(dlqKey, payload)
    }
}
