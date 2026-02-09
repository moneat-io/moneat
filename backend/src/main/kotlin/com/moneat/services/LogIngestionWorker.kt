package com.moneat.services

import com.moneat.config.RedisConfig
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

class LogIngestionWorker(
    private val queueKey: String,
    private val dlqKey: String,
    private val workerCount: Int
) {
    private val logService = LogService()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        logger.info { "Starting LogIngestionWorker with $workerCount workers, queue=$queueKey" }
        jobs = (1..workerCount).map { workerId ->
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
        while (scope.isActive) {
            try {
                val result = RedisConfig.syncBlocking().brpop(5, queueKey)
                val payload = result?.value ?: continue

                try {
                    val batch = logService.decodeQueueMessage(payload)
                    val inserted = logService.insertBatch(batch)
                    logService.publishLiveLogs(batch.projectId, inserted)
                } catch (e: Exception) {
                    logger.error(e) { "Log worker $workerId failed to process message, pushing to DLQ" }
                    RedisConfig.syncBlocking().rpush(dlqKey, payload)
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                logger.error(e) { "Log worker $workerId error in BRPOP loop" }
                delay(1000)
            }
        }
    }
}
