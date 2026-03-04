// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.config.RedisClient
import com.moneat.shared.models.OnCallSchedules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

class OnCallHandoffService(
    private val onCallScheduleService: OnCallScheduleService,
    private val pushNotificationService: PushNotificationService,
    private val redisClient: RedisClient,
) {
    private val logger = LoggerFactory.getLogger(OnCallHandoffService::class.java)
    private var job: Job? = null

    companion object {
        private const val CHECK_INTERVAL_MS = 60_000L // 60 seconds
        private const val REDIS_KEY_PREFIX = "oncall:current:"
    }

    fun start() {
        if (job?.isActive == true) {
            logger.warn("OnCallHandoffService is already running")
            return
        }

        job =
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                logger.info("OnCallHandoffService started")

                while (isActive) {
                    try {
                        checkAllSchedules()
                    } catch (e: Exception) {
                        logger.error("Error in handoff check loop", e)
                    }

                    delay(CHECK_INTERVAL_MS)
                }

                logger.info("OnCallHandoffService stopped")
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        logger.info("OnCallHandoffService stopped")
    }

    private suspend fun checkAllSchedules() {
        val scheduleIds = getAllScheduleIds()

        scheduleIds.forEach { (scheduleId, scheduleName) ->
            try {
                checkSchedule(scheduleId, scheduleName)
            } catch (e: Exception) {
                logger.error("Error checking handoff for schedule $scheduleId", e)
            }
        }
    }

    private suspend fun checkSchedule(
        scheduleId: Int,
        scheduleName: String,
    ) {
        val currentOnCall = onCallScheduleService.getCurrentOnCall(scheduleId)
        val currentUserId = currentOnCall?.userId ?: -1

        val cacheKey = "$REDIS_KEY_PREFIX$scheduleId"
        val lastUserIdStr = redisClient.get(cacheKey)
        val lastUserId = lastUserIdStr?.toIntOrNull()

        // If state changed
        if (currentUserId != (lastUserId ?: -2)) { // -2 as uninitialized sentinel
            // Update cache
            redisClient.set(cacheKey, currentUserId.toString())

            // Send notification if there is a new user (and it's not just initialization/startup)
            if (currentUserId != -1 && lastUserId != null) {
                logger.info("On-call handoff detected for schedule $scheduleName: User $lastUserId -> $currentUserId")
                pushNotificationService.sendOnCallAssignmentAlert(currentUserId, scheduleName)
            } else if (currentUserId != -1 && lastUserId == null) {
                logger.info("Initializing on-call state for schedule $scheduleName: User $currentUserId")
            } else if (currentUserId == -1 && lastUserId != null) {
                logger.info("On-call ended for schedule $scheduleName (now empty)")
            }
        }
    }

    private fun getAllScheduleIds(): List<Pair<Int, String>> =
        transaction {
            OnCallSchedules
                .selectAll()
                .map { it[OnCallSchedules.id].value to it[OnCallSchedules.name] }
        }
}
