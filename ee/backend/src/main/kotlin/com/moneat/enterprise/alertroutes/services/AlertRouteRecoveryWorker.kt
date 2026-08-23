// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.shared.services.TaskLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import kotlin.time.Duration.Companion.minutes

private val recoveryLogger = KotlinLogging.logger {}

/** Replays due recovery after grace periods and process restarts. */
class AlertRouteRecoveryWorker(private val executionService: AlertRouteExecutionService) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (isActive) {
                try {
                    TaskLock.tryWithLock(LOCK_NAME, lockAtMostFor = LOCK_DURATION) {
                        executionService.recoverDue()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    recoveryLogger.error(error) { "Alert-route recovery poll failed" }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val LOCK_NAME = "alert-route-recovery"
        const val POLL_INTERVAL_MS = 30_000L
        val LOCK_DURATION = 5.minutes
    }
}
