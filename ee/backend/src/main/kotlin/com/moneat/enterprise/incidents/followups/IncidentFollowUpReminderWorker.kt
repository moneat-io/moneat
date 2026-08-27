// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.followups

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

class IncidentFollowUpReminderWorker(
    private val reminderService: IncidentFollowUpReminderService = IncidentFollowUpReminderService(),
) {
    private val logger = LoggerFactory.getLogger(IncidentFollowUpReminderWorker::class.java)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            logger.info("Native incident follow-up reminder worker started")
            while (isActive) {
                try {
                    TaskLock.tryWithLock(LOCK_NAME, lockAtMostFor = LOCK_DURATION) {
                        reminderService.processDue()
                    }
                } catch (error: Exception) {
                    logger.error("Native incident follow-up reminder poll failed", error)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        logger.info("Native incident follow-up reminder worker stopped")
    }

    companion object {
        private const val LOCK_NAME = "native-incident-follow-up-reminders"
        private const val POLL_INTERVAL_MS = 60_000L
        private val LOCK_DURATION = 5.minutes
    }
}
