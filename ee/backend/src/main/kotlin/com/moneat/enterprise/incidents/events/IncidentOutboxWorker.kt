// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.events

import com.moneat.shared.services.TaskLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

class IncidentOutboxWorker(
    private val outboxService: IncidentOutboxService,
) {
    private val logger = LoggerFactory.getLogger(IncidentOutboxWorker::class.java)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job =
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                logger.info("Native incident outbox worker started")
                while (isActive) {
                    try {
                        TaskLock.tryWithLock(LOCK_NAME, lockAtMostFor = LOCK_DURATION) {
                            drainReadyEvents()
                        }
                    } catch (e: Exception) {
                        logger.error("Native incident outbox poll failed", e)
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        logger.info("Native incident outbox worker stopped")
    }

    private suspend fun drainReadyEvents() {
        var processed: Int
        do {
            processed = outboxService.processBatch(BATCH_SIZE)
        } while (processed == BATCH_SIZE)
    }

    companion object {
        private const val LOCK_NAME = "native-incident-outbox"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val BATCH_SIZE = 100
        private val LOCK_DURATION = 5.minutes
    }
}
