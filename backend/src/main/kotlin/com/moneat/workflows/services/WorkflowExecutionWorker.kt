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

package com.moneat.workflows.services

import com.moneat.config.RedisConfig
import com.moneat.utils.brpopLoopBackoff
import com.moneat.utils.pushToDlq
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.models.WorkflowRunQueuedMessage
import io.lettuce.core.RedisException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.IOException

private val workflowWorkerLogger = KotlinLogging.logger {}
private const val WORKFLOW_BRPOP_TIMEOUT_SECONDS = 5L
private const val WORKFLOW_ERROR_BACKOFF_MS = 1000L

class WorkflowExecutionWorker(
    private val workflowService: WorkflowService,
    private val queueKey: String = WorkflowService.QUEUE_KEY,
    private val dlqKey: String = WorkflowService.DLQ_KEY,
    private val workerCount: Int = DEFAULT_WORKER_COUNT,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        workflowWorkerLogger.info { "Starting WorkflowExecutionWorker with $workerCount workers, queue=$queueKey" }
        jobs = (1..workerCount).map { workerId ->
            scope.launch { runWorker(workerId) }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        workflowWorkerLogger.info { "WorkflowExecutionWorker stopped" }
    }

    private suspend fun runWorker(workerId: Int) {
        val conn = RedisConfig.newBlockingConnection()
        try {
            val redis = conn.sync()
            while (scope.isActive) {
                try {
                    val result = redis.brpop(WORKFLOW_BRPOP_TIMEOUT_SECONDS, queueKey)
                    val payload = result?.value ?: continue
                    processMessage(workerId, payload)
                } catch (_: CancellationException) {
                    break
                } catch (e: RedisException) {
                    brpopLoopBackoff(
                        workflowWorkerLogger,
                        workerId,
                        "Workflow",
                        WORKFLOW_ERROR_BACKOFF_MS,
                        e
                    )
                } catch (e: IOException) {
                    brpopLoopBackoff(
                        workflowWorkerLogger,
                        workerId,
                        "Workflow",
                        WORKFLOW_ERROR_BACKOFF_MS,
                        e
                    )
                }
            }
        } finally {
            RedisConfig.closeBlockingConnection(conn)
        }
    }

    internal suspend fun processMessage(
        workerId: Int,
        payload: String
    ) {
        suspendRunCatching {
            val message = json.decodeFromString<WorkflowRunQueuedMessage>(payload)
            workflowService.executeRun(message.runId)
        }.onFailure { error ->
            pushToDlq(
                workflowWorkerLogger,
                dlqKey,
                payload,
                workerId,
                "Workflow",
                error
            )
        }
    }

    companion object {
        private const val DEFAULT_WORKER_COUNT = 2
    }
}
