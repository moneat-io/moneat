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

package com.moneat.logs.services

import com.moneat.config.BRPOP_TIMEOUT_SECONDS
import com.moneat.config.RedisConfig
import com.moneat.logs.repositories.LogRepositoryImpl
import com.moneat.utils.brpopLoopBackoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import mu.KotlinLogging
import java.io.IOException
import kotlin.random.Random
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}
private const val FULL_SAMPLING_RATE = 1.0f
private const val ERROR_DELAY_MS = 1000L

class LogIngestionWorker(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int,
    private val logService: LogService = LogService(LogRepositoryImpl()),
    private val logIndexService: LogIndexService = LogIndexService(),
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        logger.info { "Starting LogIngestionWorker with $workerCount workers, queue=$queueKey" }
        jobs =
            (1..workerCount).map { workerId ->
                scope.launch {
                    runWorker(workerId)
                }
            }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        logger.info { "LogIngestionWorker stopped" }
    }

    private suspend fun runWorker(workerId: Int) {
        val conn = RedisConfig.newBlockingConnection()
        try {
            val redis = conn.sync()
            while (scope.isActive) {
                try {
                    val result = redis.brpop(BRPOP_TIMEOUT_SECONDS, queueKey)
                    val payload = result?.value ?: continue
                    processMessageForTest(workerId, payload)
                } catch (e: CancellationException) {
                    break
                } catch (e: SerializationException) {
                    brpopLoopBackoff(logger, workerId, "Log", ERROR_DELAY_MS, e)
                } catch (e: IOException) {
                    brpopLoopBackoff(logger, workerId, "Log", ERROR_DELAY_MS, e)
                } catch (e: IllegalStateException) {
                    brpopLoopBackoff(logger, workerId, "Log", ERROR_DELAY_MS, e)
                } catch (e: IllegalArgumentException) {
                    brpopLoopBackoff(logger, workerId, "Log", ERROR_DELAY_MS, e)
                }
            }
        } finally {
            RedisConfig.closeBlockingConnection(conn)
        }
    }

    internal suspend fun processMessageForTest(
        workerId: Int,
        payload: String,
        onDlq: (String) -> Unit = { message -> RedisConfig.sync().rpush(dlqKey, message) }
    ) {
        suspendRunCatching {
            val batch = logService.decodeQueueMessage(payload)
            val orgId = batch.effectiveOrganizationId
            val indexes = if (orgId in Int.MIN_VALUE..Int.MAX_VALUE) {
                logIndexService.getActiveIndexesCached(orgId.toInt())
            } else {
                emptyList()
            }

            val taggedLogs = if (indexes.isEmpty()) {
                batch.logs
            } else {
                batch.logs.mapNotNull { entry ->
                    applyIndexRouting(entry, indexes)
                }
            }

            if (taggedLogs.isEmpty()) return

            val taggedBatch = batch.copy(logs = taggedLogs)
            val inserted = logService.insertBatch(taggedBatch)
            logService.publishLiveLogs(orgId, inserted)
        }.getOrElse { e ->
            logger.error(e) { "Log worker $workerId failed to process message, pushing to DLQ" }
            onDlq(payload)
        }
    }

    /**
     * Match a log entry against indexes and apply sampling.
     * Returns null if the entry should be dropped by sampling.
     */
    private fun applyIndexRouting(
        entry: com.moneat.logs.models.QueuedLogEntry,
        indexes: List<com.moneat.logs.models.LogIndexResponse>
    ): com.moneat.logs.models.QueuedLogEntry? {
        val entryMap = mapOf(
            "level" to entry.level,
            "message" to entry.message,
            "body" to entry.body,
            "service" to entry.service,
            "environment" to entry.environment,
            "host" to entry.host,
            "source" to entry.source,
            "container_name" to entry.containerName,
            "container_id" to entry.containerId,
            "container_image" to entry.containerImage,
            "trace_id" to entry.traceId,
            "span_id" to entry.spanId
        ) + entry.tags + entry.resourceAttributes

        val parser = LogQueryParser()
        for (index in indexes) {
            val matches = if (index.filterQuery.isBlank()) {
                true
            } else {
                suspendRunCatching {
                    val parsed = parser.parse(index.filterQuery)
                    if (parsed.rootNode == null) {
                        logger.debug {
                            "Filter '${index.filterQuery}' for index '${index.name}' " +
                                "produced null AST; treating as non-match"
                        }
                        false
                    } else {
                        evaluateFilter(parsed.rootNode, entryMap)
                    }
                }.getOrElse { _ ->
                    false
                }
            }

            if (matches) {
                if (index.samplingRate < FULL_SAMPLING_RATE &&
                    Random.nextFloat() > index.samplingRate
                ) {
                    return null
                }
                return entry.copy(indexName = index.name)
            }
        }
        return entry
    }

    private fun evaluateFilter(
        node: LogQueryParser.QueryNode,
        entry: Map<String, String>
    ): Boolean {
        return when (node) {
            is LogQueryParser.QueryNode.FieldNode -> {
                val value = entry[node.field] ?: return false
                if (node.isWildcard) {
                    val escaped = Regex.escape(node.value)
                    val pattern = escaped
                        .replace("\\*", ".*")
                        .replace("\\?", ".")
                    suspendRunCatching {
                        value.matches(Regex(pattern, RegexOption.IGNORE_CASE))
                    }.getOrElse { _ ->
                        false
                    }
                } else {
                    value.equals(node.value, ignoreCase = true)
                }
            }
            is LogQueryParser.QueryNode.FullTextNode -> {
                val fields = listOf(
                    "message",
                    "body",
                    "service",
                    "environment",
                    "host",
                    "container_name"
                )
                fields.any { f ->
                    val v = entry[f] ?: return@any false
                    v.contains(node.term, ignoreCase = true)
                }
            }
            is LogQueryParser.QueryNode.AndNode ->
                evaluateFilter(node.left, entry) &&
                    evaluateFilter(node.right, entry)
            is LogQueryParser.QueryNode.OrNode ->
                evaluateFilter(node.left, entry) ||
                    evaluateFilter(node.right, entry)
            is LogQueryParser.QueryNode.NotNode ->
                !evaluateFilter(node.node, entry)
            is LogQueryParser.QueryNode.ExistsNode ->
                !entry[node.field].isNullOrEmpty()
            is LogQueryParser.QueryNode.TagExistsNode ->
                entry.containsKey(node.tagKey)
            is LogQueryParser.QueryNode.TermNode ->
                entry.values.any {
                    it.contains(node.term, ignoreCase = true)
                }
            is LogQueryParser.QueryNode.RangeNode -> {
                val v = entry[node.field]?.toDoubleOrNull() ?: return false
                val min = node.min.toDoubleOrNull() ?: return false
                val max = node.max.toDoubleOrNull() ?: return false
                v in min..max
            }
            is LogQueryParser.QueryNode.ComparisonNode -> {
                val v = entry[node.field]?.toDoubleOrNull() ?: return false
                val target = node.value.toDoubleOrNull() ?: return false
                when (node.operator) {
                    ">" -> v > target
                    ">=" -> v >= target
                    "<" -> v < target
                    "<=" -> v <= target
                    else -> false
                }
            }
        }
    }
}
