// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.notifications.services.EmailService
import com.moneat.shared.models.OnCallParticipants
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.ShiftChangeNotificationsSent
import com.moneat.shared.models.Users
import com.moneat.shared.services.TaskLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class ShiftChangeNotifier(
    private val onCallScheduleService: OnCallScheduleService,
    private val pushNotificationService: PushNotificationService,
) {
    private val logger = LoggerFactory.getLogger(ShiftChangeNotifier::class.java)
    private var job: Job? = null
    private val prefsService = UserNotificationPreferencesService()
    private val emailService = EmailService()

    companion object {
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val NOTIFY_BEFORE_MINUTES = 15L
    }

    fun start() {
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            logger.info("ShiftChangeNotifier started")
            while (isActive) {
                TaskLock.tryWithLock("oncall-shift-change", lockAtMostFor = 5.minutes) {
                    checkAllSchedules()
                }
                delay(CHECK_INTERVAL_MS)
            }
            logger.info("ShiftChangeNotifier stopped")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        logger.info("ShiftChangeNotifier stopped")
    }

    private suspend fun checkAllSchedules() {
        val scheduleIds = transaction {
            OnCallSchedules.selectAll().map { it[OnCallSchedules.id].value to it[OnCallSchedules.name] }
        }

        scheduleIds.forEach { (scheduleId, scheduleName) ->
            try {
                checkScheduleForUpcomingHandoff(scheduleId, scheduleName)
            } catch (e: Exception) {
                logger.error("Error checking shift change for schedule $scheduleId", e)
            }
        }
    }

    private suspend fun checkScheduleForUpcomingHandoff(scheduleId: Int, scheduleName: String) {
        val now = Clock.System.now()
        val lookAheadInstant = now.plus(NOTIFY_BEFORE_MINUTES.minutes)

        val currentOnCall = onCallScheduleService.getCurrentOnCall(scheduleId)
        val upcomingOnCall = onCallScheduleService.getOnCallAt(scheduleId, lookAheadInstant)

        // Only notify if a transition is about to happen
        if (upcomingOnCall == null || currentOnCall?.userId == upcomingOnCall.userId) return

        val shiftStartAt = computeShiftStart(scheduleId, lookAheadInstant) ?: return
        val upcomingUserId = upcomingOnCall.userId

        val prefs = prefsService.getChannelPreferences(upcomingUserId, "shift_change")

        if (prefs.isChannelEnabled("push")) {
            sendIfNotSent(upcomingUserId, scheduleId, shiftStartAt, "push") {
                pushNotificationService.sendPush(
                    userId = upcomingUserId,
                    title = "Your on-call shift starts in $NOTIFY_BEFORE_MINUTES minutes",
                    body = "You are the primary on-call for $scheduleName",
                    data = mapOf("type" to "shift_change", "scheduleId" to scheduleId.toString()),
                )
            }
        }

        if (prefs.isChannelEnabled("email")) {
            sendIfNotSent(upcomingUserId, scheduleId, shiftStartAt, "email") {
                val userEmail = transaction {
                    Users.selectAll().where { Users.id eq upcomingUserId }.singleOrNull()?.get(Users.email)
                }
                if (userEmail != null) {
                    emailService.sendEmail(
                        to = userEmail,
                        subject = "Your on-call shift starts in $NOTIFY_BEFORE_MINUTES minutes — $scheduleName",
                        htmlBody = "<p>Hi,</p><p>Your on-call shift for <strong>$scheduleName</strong> starts in $NOTIFY_BEFORE_MINUTES minutes.</p>",
                        textBody = "Your on-call shift for $scheduleName starts in $NOTIFY_BEFORE_MINUTES minutes.",
                        emailType = "shift_change",
                    )
                }
            }
        }
    }

    /** Record dedup entry before sending; skip if already recorded for this (user, schedule, shiftStart, channel). */
    private suspend fun sendIfNotSent(
        userId: Int,
        scheduleId: Int,
        shiftStartAt: kotlin.time.Instant,
        channel: String,
        send: suspend () -> Unit,
    ) {
        val alreadySent = transaction {
            ShiftChangeNotificationsSent
                .selectAll()
                .where {
                    (ShiftChangeNotificationsSent.userId eq userId) and
                        (ShiftChangeNotificationsSent.scheduleId eq scheduleId) and
                        (ShiftChangeNotificationsSent.shiftStartAt eq shiftStartAt) and
                        (ShiftChangeNotificationsSent.channel eq channel)
                }
                .singleOrNull() != null
        }

        if (alreadySent) return

        try {
            send()
            transaction {
                ShiftChangeNotificationsSent.insertIgnore {
                    it[ShiftChangeNotificationsSent.userId] = userId
                    it[ShiftChangeNotificationsSent.scheduleId] = scheduleId
                    it[ShiftChangeNotificationsSent.shiftStartAt] = shiftStartAt
                    it[ShiftChangeNotificationsSent.channel] = channel
                    it[ShiftChangeNotificationsSent.sentAt] = Clock.System.now()
                }
            }
            logger.info("Sent shift_change $channel notification to user $userId for schedule $scheduleId")
        } catch (e: Exception) {
            logger.error("Failed to send shift_change $channel notification to user $userId", e)
        }
    }

    /** Compute the exact rotation-period start instant for whoever is on-call at [atInstant]. */
    private fun computeShiftStart(scheduleId: Int, atInstant: kotlin.time.Instant): kotlin.time.Instant? {
        return transaction {
            val scheduleRow = OnCallSchedules.selectAll()
                .where { OnCallSchedules.id eq scheduleId }
                .singleOrNull() ?: return@transaction null

            val participants = OnCallParticipants.selectAll()
                .where { OnCallParticipants.scheduleId eq scheduleId }
                .orderBy(OnCallParticipants.position to SortOrder.ASC)
                .toList()

            if (participants.isEmpty()) return@transaction null

            val rotationType = scheduleRow[OnCallSchedules.rotationType]
            val rotationDays = when (rotationType) {
                "DAILY" -> 1L
                else -> 7L // WEEKLY, CUSTOM
            }

            val zoneId = ZoneId.of(scheduleRow[OnCallSchedules.timezone])
            val handoffLocalTime = scheduleRow[OnCallSchedules.handoffTime]
            val zonedAt = java.time.Instant.ofEpochMilli(atInstant.toEpochMilliseconds()).atZone(zoneId)

            // Determine the rotation date (adjusted back if before handoff time)
            val rotationDate = if (zonedAt.toLocalTime().isBefore(handoffLocalTime)) {
                zonedAt.toLocalDate().minusDays(1)
            } else {
                zonedAt.toLocalDate()
            }

            val daysSinceEpoch = ChronoUnit.DAYS.between(LocalDate.EPOCH, rotationDate)
            val rotationPeriodStartDay = (daysSinceEpoch / rotationDays) * rotationDays

            val shiftStartLocal = LocalDate.EPOCH.plusDays(rotationPeriodStartDay).atTime(handoffLocalTime)
            val javaInstant = shiftStartLocal.atZone(zoneId).toInstant()

            kotlin.time.Instant.fromEpochMilliseconds(javaInstant.toEpochMilli())
        }
    }
}
