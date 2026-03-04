// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.workers

import com.moneat.config.RedisConfig
import com.moneat.enterprise.datadog.services.DatadogInfraService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val BRPOP_TIMEOUT_SECONDS = 5L
private const val ERROR_DELAY_MS = 1000L

class DatadogInfraIngestionWorker(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int
) {
    private val scope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        logger.info {
            "Starting DatadogInfraIngestionWorker with " +
                "$workerCount workers, queue=$queueKey"
        }
        jobs = (1..workerCount).map { workerId ->
            scope.launch {
                runWorker(workerId)
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        logger.info { "DatadogInfraIngestionWorker stopped" }
    }

    private suspend fun runWorker(workerId: Int) {
        val redis = RedisConfig.newBlockingConnection()
        try {
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
                } catch (e: Exception) {
                    logger.error(e) {
                        "DD infra worker $workerId error in BRPOP loop"
                    }
                    delay(ERROR_DELAY_MS)
                }
            }
        } finally {
        }
    }

    internal suspend fun processMessage(
        workerId: Int,
        payload: String,
        onDlq: (String) -> Unit = { message ->
            RedisConfig.sync().rpush(dlqKey, message)
        }
    ) {
        try {
            val batch =
                DatadogInfraService.decodeInfraBatch(payload)
            DatadogInfraService.insertInfraBatch(batch)
        } catch (e: Exception) {
            logger.error(e) {
                "DD infra worker $workerId failed, " +
                    "pushing to DLQ"
            }
            onDlq(payload)
        }
    }
}
