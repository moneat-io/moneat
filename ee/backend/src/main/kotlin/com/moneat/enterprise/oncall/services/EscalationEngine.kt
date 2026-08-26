// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.enterprise.oncall.escalationPolicyResourceIdOrNull
import com.moneat.enterprise.oncall.alertResourceId
import com.moneat.enterprise.oncall.incidentResourceIdOrNull
import com.moneat.enterprise.oncall.organizationResourceId
import com.moneat.enterprise.oncall.userResourceIdOrNull
import com.moneat.enterprise.incidents.timeline.IncidentAlertTimelineBridge
import com.moneat.shared.services.TaskLock
import com.moneat.config.RedisClient
import com.moneat.enterprise.oncall.models.EscalationStepTarget
import com.moneat.enterprise.oncall.models.OnCallAlert
import com.moneat.enterprise.oncall.models.OnCallAlertTimeline
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.notifications.services.SlackService
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.Users
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Singleton holder so webhook routes can access the running engine instance
object EscalationEngineHolder {
    @Volatile
    var instance: EscalationEngine? = null
}

class EscalationEngine(
    private val escalationPolicyService: EscalationPolicyService,
    private val onCallScheduleService: OnCallScheduleService,
    private val pushNotificationService: PushNotificationService,
    private val slackService: SlackService,
    private val redisClient: RedisClient,
    private val escalationPathService: EscalationPathService = EscalationPathService(),
) {
    private val logger = LoggerFactory.getLogger(EscalationEngine::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var timeoutPollingJob: Job? = null
    private val twilioService = TwilioService()
    private val notificationPrefsService = UserNotificationPreferencesService()
    private val incidentAlertTimelineBridge = IncidentAlertTimelineBridge()

    companion object {
        private const val TIMEOUT_KEY_PREFIX = "escalation:timeout:"
        private const val ACTIVE_INCIDENTS_KEY = "escalation:active"
        private const val SMS_FALLBACK_KEY = "escalation:sms_fallback"
        private const val TRIGGERED_STATUS = "TRIGGERED"
        private const val ACKNOWLEDGED_STATUS = "ACKNOWLEDGED"
        private const val RESOLVED_STATUS = "RESOLVED"
        private val OPEN_ALERT_STATUSES = listOf(TRIGGERED_STATUS, ACKNOWLEDGED_STATUS)
    }

    private data class AlertEscalationState(
        val status: String,
        val escalationPolicyId: Int?,
        val currentStep: Int,
        val repeatIteration: Int
    )

    private data class EscalationNotification(
        val incidentId: Int,
        val target: EscalationStepTarget,
        val title: String,
        val priority: String,
        val smsFallbackDelayMinutes: Int,
        val stepIndex: Int,
        val iteration: Int,
    )

    private data class EscalationTimeout(
        val incidentId: Int,
        val timeoutMinutes: Long,
        val nextStep: Int,
        val iteration: Int,
    )

    private data class EscalationStepRequest(
        val incidentId: Int,
        val stepIndex: Int,
        val iteration: Int,
    )

    private data class EscalationStepProcessingResult(
        val notifications: List<EscalationNotification> = emptyList(),
        val timeout: EscalationTimeout? = null,
        val nextStep: EscalationStepRequest? = null,
        val removeTimeout: Boolean = false,
    )

    fun start() {
        logger.info("Starting escalation engine timeout polling")
        EscalationEngineHolder.instance = this
        timeoutPollingJob =
            scope.launch {
                while (isActive) {
                    TaskLock.tryWithLock("oncall-escalation", lockAtMostFor = 5.minutes, lockAtLeastFor = 5.seconds) {
                        checkTimeouts()
                        checkSmsFallbackTimeouts()
                    }
                    delay(10.seconds)
                }
            }
    }

    fun stop() {
        logger.info("Stopping escalation engine")
        EscalationEngineHolder.instance = null
        timeoutPollingJob?.cancel()
        scope.cancel()
    }

    fun triggerEscalation(
        organizationId: Int,
        escalationPolicyId: Int,
        title: String,
        description: String?,
        priority: String,
        alertSource: String?,
        deduplicationKey: String?,
        metadata: Map<String, JsonElement>? = null,
    ): OnCallAlert? {
        val publishedPath = escalationPathService.getPublishedVersion(escalationPolicyId)
        val alertId =
            transaction {
                val now = Clock.System.now()

                // Check for an existing open alert with the same deduplication key.
                if (deduplicationKey != null) {
                    val existing =
                        OnCallAlerts
                            .selectAll()
                            .where {
                                (OnCallAlerts.organizationId eq organizationId) and
                                    (OnCallAlerts.deduplicationKey eq deduplicationKey) and
                                    (OnCallAlerts.status inList OPEN_ALERT_STATUSES)
                            }.singleOrNull()

                    if (existing != null) {
                        logger.info("Alert already exists for dedup key: $deduplicationKey")
                        return@transaction null
                    }
                }

                val alertId =
                    OnCallAlerts
                        .insertAndGetId {
                            it[OnCallAlerts.organizationId] = organizationId
                            it[OnCallAlerts.escalationPolicyId] = escalationPolicyId
                            it[OnCallAlerts.escalationPolicyVersionId] = publishedPath?.internalId
                            it[OnCallAlerts.title] = title
                            it[OnCallAlerts.description] = description
                            it[OnCallAlerts.priority] = priority
                            it[OnCallAlerts.status] = TRIGGERED_STATUS
                            it[OnCallAlerts.alertSource] = alertSource
                            it[OnCallAlerts.deduplicationKey] = deduplicationKey
                            it[currentStep] = 0
                            it[repeatIteration] = 0
                            it[triggeredAt] = now
                            it[OnCallAlerts.metadata] = metadata
                            it[createdAt] = now
                            it[updatedAt] = now
                        }.value

                insertTimelineEvent(alertId, TRIGGERED_STATUS, null, mapOf("priority" to JsonPrimitive(priority)))

                alertId
            } ?: return null

        publishedPath?.let { pathVersion ->
            escalationPathService.createExecution(
                organizationId = organizationId,
                alertId = alertId,
                policyVersionId = pathVersion.internalId,
                startNodeId = pathVersion.path.startNodeId,
            )
        }

        processEscalationStep(alertId, 0, 0)
        return getAlert(alertId)
    }

    fun resolveAlertByDeduplicationKey(
        organizationId: Int,
        alertSource: String,
        deduplicationKey: String,
    ): Boolean =
        transaction {
            val now = Clock.System.now()
            val alertIds =
                OnCallAlerts
                    .selectAll()
                    .where {
                        (OnCallAlerts.organizationId eq organizationId) and
                            (OnCallAlerts.alertSource eq alertSource) and
                            (OnCallAlerts.deduplicationKey eq deduplicationKey) and
                            (OnCallAlerts.status inList OPEN_ALERT_STATUSES)
                    }.map { row -> row[OnCallAlerts.id].value }

            if (alertIds.isEmpty()) {
                return@transaction false
            }

            val updated =
                OnCallAlerts.update({ OnCallAlerts.id inList alertIds }) {
                    it[status] = RESOLVED_STATUS
                    it[resolvedAt] = now
                    it[resolvedBy] = null
                    it[updatedAt] = now
                }

            if (updated <= 0) {
                return@transaction false
            }

            alertIds.forEach { alertId ->
                removeTimeout(alertId)
                removeSmsFallback(alertId)
                insertTimelineEvent(
                    alertId,
                    RESOLVED_STATUS,
                    null,
                    mapOf(
                        "source" to JsonPrimitive(alertSource),
                        "deduplicationKey" to JsonPrimitive(deduplicationKey),
                        "resolvedBy" to JsonPrimitive("system"),
                    ),
                )
            }

            logger.info(
                "Resolved $updated on-call alert(s) for org $organizationId, source $alertSource, " +
                    "deduplication key $deduplicationKey",
            )
            true
        }

    private fun processEscalationStep(
        incidentId: Int,
        stepIndex: Int,
        iteration: Int,
    ) {
        val result =
            transaction {
                val incident =
                    OnCallAlerts
                        .selectAll()
                        .where { OnCallAlerts.id eq incidentId }
                        .singleOrNull() ?: return@transaction EscalationStepProcessingResult()

                // Check if incident is still in TRIGGERED status
                if (incident[OnCallAlerts.status] != "TRIGGERED") {
                    logger.info("Incident $incidentId no longer in TRIGGERED status, stopping escalation")
                    return@transaction EscalationStepProcessingResult(removeTimeout = true)
                }

                val policyId =
                    incident[OnCallAlerts.escalationPolicyId]
                        ?: return@transaction EscalationStepProcessingResult()
                val policy =
                    escalationPolicyService.getPolicy(policyId)
                        ?: return@transaction EscalationStepProcessingResult()

                // Check if we've exhausted all steps
                if (stepIndex >= policy.steps.size) {
                    if (iteration < policy.repeatCount) {
                        // Loop back to step 0, increment iteration
                        logger.info("Repeating escalation for incident $incidentId, iteration ${iteration + 1}")
                        OnCallAlerts.update({ OnCallAlerts.id eq incidentId }) {
                            it[currentStep] = 0
                            it[repeatIteration] = iteration + 1
                            it[updatedAt] = Clock.System.now()
                        }
                        return@transaction EscalationStepProcessingResult(
                            nextStep = EscalationStepRequest(incidentId, 0, iteration + 1),
                        )
                    } else {
                        logger.warn("Escalation exhausted for incident $incidentId after $iteration iterations")
                        insertTimelineEvent(
                            incidentId,
                            "ESCALATED",
                            null,
                            mapOf("result" to JsonPrimitive("exhausted")),
                        )
                    }
                    return@transaction EscalationStepProcessingResult()
                }

                val step = policy.steps[stepIndex]
                logger.info("Processing escalation step $stepIndex for incident $incidentId")

                // Update current step
                OnCallAlerts.update({ OnCallAlerts.id eq incidentId }) {
                    it[currentStep] = stepIndex
                    it[updatedAt] = Clock.System.now()
                }

                // Notify all targets in this step after commit.
                logger.info("Escalation step $stepIndex for incident $incidentId has ${step.targets.size} target(s)")
                val notifications =
                    step.targets.map { target ->
                        EscalationNotification(
                            incidentId = incidentId,
                            target = target,
                            title = incident[OnCallAlerts.title],
                            priority = incident[OnCallAlerts.priority],
                            smsFallbackDelayMinutes = step.smsFallbackDelayMinutes,
                            stepIndex = stepIndex,
                            iteration = iteration,
                        )
                    }

                insertTimelineEvent(
                    incidentId,
                    "ESCALATED",
                    null,
                    mapOf(
                        "step" to JsonPrimitive(stepIndex.toString()),
                        "iteration" to JsonPrimitive(iteration.toString()),
                    ),
                )

                EscalationStepProcessingResult(
                    notifications = notifications,
                    timeout =
                        EscalationTimeout(
                            incidentId = incidentId,
                            timeoutMinutes = step.timeoutMinutes.toLong(),
                            nextStep = stepIndex + 1,
                            iteration = iteration,
                        ),
                )
            }

        if (result.removeTimeout) {
            removeTimeout(incidentId)
        }
        result.nextStep?.let { nextStep ->
            processEscalationStep(nextStep.incidentId, nextStep.stepIndex, nextStep.iteration)
        }
        result.notifications.forEach { notification ->
            notifyEscalationTarget(
                incidentId = notification.incidentId,
                target = notification.target,
                title = notification.title,
                priority = notification.priority,
                smsFallbackDelayMinutes = notification.smsFallbackDelayMinutes,
                stepIndex = notification.stepIndex,
                iteration = notification.iteration,
            )
        }
        result.timeout?.let { timeout ->
            scheduleTimeout(timeout.incidentId, timeout.timeoutMinutes, timeout.nextStep, timeout.iteration)
        }
    }

    private fun notifyEscalationTarget(
        incidentId: Int,
        target: EscalationStepTarget,
        title: String,
        priority: String,
        smsFallbackDelayMinutes: Int,
        stepIndex: Int,
        iteration: Int,
    ) {
        when (target.targetType) {
            "USER" -> {
                logger.info("Notifying user ${target.targetId} for incident $incidentId")
                notifyUser(incidentId, target.targetId, title, priority, smsFallbackDelayMinutes, stepIndex, iteration)
            }

            "ON_CALL_SCHEDULE" -> notifyCurrentOnCall(
                incidentId,
                target,
                title,
                priority,
                smsFallbackDelayMinutes,
                stepIndex,
                iteration,
            )
        }
    }

    private fun notifyCurrentOnCall(
        incidentId: Int,
        target: EscalationStepTarget,
        title: String,
        priority: String,
        smsFallbackDelayMinutes: Int,
        stepIndex: Int,
        iteration: Int,
    ) {
        val onCall = onCallScheduleService.getCurrentOnCall(target.targetId)
        if (onCall == null) {
            logger.warn("No on-call user found for schedule ${target.targetId}")
            return
        }

        logger.info(
            "Notifying on-call user ${onCall.userId} from schedule ${target.targetId} for incident $incidentId",
        )
        notifyUser(incidentId, onCall.userId, title, priority, smsFallbackDelayMinutes, stepIndex, iteration)
    }

    private fun notifyUser(
        incidentId: Int,
        userId: Int,
        title: String,
        priority: String,
        smsFallbackDelayMinutes: Int = 2,
        stepIndex: Int,
        iteration: Int
    ) {
        val (userName, phoneNumber, phoneOptIn) =
            transaction {
                val row = Users.selectAll().where { Users.id eq userId }.singleOrNull()
                Triple(row?.get(Users.name), row?.get(Users.phone_number), row?.get(Users.oncall_phone_opt_in) ?: false)
            }

        scope.launch {
            val prefs = notificationPrefsService.getChannelPreferences(userId, "high_urgency")

            // Push: send if enabled, or as a safety net when the user has disabled all channels
            val shouldSendPush = prefs.isChannelEnabled("push") || prefs.allDisabled()
            if (shouldSendPush) {
                try {
                    pushNotificationService.sendIncidentAlert(userId, incidentId, title, priority)
                } catch (e: Exception) {
                    logger.error("Failed to send push notification for incident $incidentId, user $userId", e)
                }
            }

            // Slack: respect user preference
            if (prefs.isChannelEnabled("slack")) {
                try {
                    val publicAlertId = transaction { alertResourceId(incidentId) }
                    slackService.sendOnCallAlert(userId, publicAlertId, title, priority)
                } catch (e: Exception) {
                    logger.error("Failed to send Slack DM for incident $incidentId, user $userId", e)
                }
            }

            val channelsSent = prefs.enabledChannels().joinToString(",").ifEmpty { "push(forced)" }
            try {
                logTimelineEvent(
                    incidentId,
                    "NOTIFICATION_SENT",
                    userId,
                    mapOf(
                        "channel" to JsonPrimitive(channelsSent),
                        "toUserName" to JsonPrimitive(userName ?: "Unknown"),
                    ),
                )
            } catch (e: Exception) {
                logger.error("Failed to log timeline for incident $incidentId", e)
            }

            // SMS/call fallback: respect preference AND existing phone opt-in consent
            val smsEligible =
                prefs.isChannelEnabled("sms") &&
                    !phoneNumber.isNullOrBlank() &&
                    smsFallbackDelayMinutes > 0 &&
                    twilioService.isEnabled()
            if (smsEligible) {
                try {
                    if (phoneOptIn) {
                        scheduleSmsFallback(
                            incidentId = incidentId,
                            userId = userId,
                            phoneNumber = phoneNumber,
                            title = title,
                            priority = priority,
                            delayMinutes = smsFallbackDelayMinutes.toLong(),
                            stepIndex = stepIndex,
                            iteration = iteration
                        )
                    } else {
                        logTimelineEvent(
                            incidentId,
                            "NOTIFICATION_SKIPPED",
                            userId,
                            mapOf(
                                "channel" to JsonPrimitive("sms,call"),
                                "reason" to JsonPrimitive("consent_missing"),
                                "toUserName" to JsonPrimitive(userName ?: "Unknown"),
                            ),
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Failed to schedule SMS/call fallback for incident $incidentId, user $userId", e)
                }
            }
        }
    }

    private fun scheduleTimeout(
        incidentId: Int,
        timeoutMinutes: Long,
        nextStep: Int,
        iteration: Int,
    ) {
        val timeoutAt = Clock.System.now().plus(timeoutMinutes.minutes)

        val timeoutData =
            mapOf(
                "incidentId" to incidentId.toString(),
                "nextStep" to nextStep.toString(),
                "iteration" to iteration.toString(),
            )

        redisClient.zadd(
            ACTIVE_INCIDENTS_KEY,
            timeoutAt.epochSeconds.toDouble(),
            Json.encodeToString(kotlinx.serialization.serializer(), timeoutData),
        )
        logger.debug("Scheduled timeout for incident $incidentId at $timeoutAt")
    }

    private fun removeTimeout(incidentId: Int) {
        // Remove from active incidents
        val members = redisClient.zrange(ACTIVE_INCIDENTS_KEY, 0, -1)
        members.forEach { member ->
            val data =
                try {
                    Json.decodeFromString<Map<String, String>>(member)
                } catch (e: Exception) {
                    return@forEach
                }
            if (data["incidentId"] == incidentId.toString()) {
                redisClient.zrem(ACTIVE_INCIDENTS_KEY, member)
            }
        }
    }

    private fun scheduleSmsFallback(
        incidentId: Int,
        userId: Int,
        phoneNumber: String,
        title: String,
        priority: String,
        delayMinutes: Long,
        stepIndex: Int,
        iteration: Int
    ) {
        val fireAt = Clock.System.now().plus(delayMinutes.minutes)
        val data =
            mapOf(
                "incidentId" to incidentId.toString(),
                "userId" to userId.toString(),
                "phoneNumber" to phoneNumber,
                "title" to title,
                "priority" to priority,
                "stepIndex" to stepIndex.toString(),
                "iteration" to iteration.toString(),
            )
        val payload = Json.encodeToString(kotlinx.serialization.serializer(), data)
        redisClient.zadd(SMS_FALLBACK_KEY, fireAt.epochSeconds.toDouble(), payload)
        logger.debug("Scheduled SMS/call fallback for incident $incidentId at $fireAt")
    }

    private fun isFallbackStillValid(incidentId: Int, userId: Int, stepIndex: Int, iteration: Int): Boolean {
        val state = transaction {
            OnCallAlerts.selectAll()
                .where { OnCallAlerts.id eq incidentId }
                .singleOrNull()
                ?.let { row ->
                    AlertEscalationState(
                        status = row[OnCallAlerts.status],
                        escalationPolicyId = row[OnCallAlerts.escalationPolicyId],
                        currentStep = row[OnCallAlerts.currentStep],
                        repeatIteration = row[OnCallAlerts.repeatIteration]
                    )
                }
        } ?: return false

        if (state.status != "TRIGGERED") return false
        if (state.currentStep != stepIndex || state.repeatIteration != iteration) return false

        val policyId = state.escalationPolicyId ?: return false
        val policy = escalationPolicyService.getPolicy(policyId) ?: return false
        val step = policy.steps.getOrNull(stepIndex) ?: return false

        return step.targets.any { target ->
            when (target.targetType) {
                "USER" -> target.targetId == userId
                "ON_CALL_SCHEDULE" -> onCallScheduleService.getCurrentOnCall(target.targetId)?.userId == userId
                else -> false
            }
        }
    }

    private fun removeSmsFallback(incidentId: Int) {
        val members = redisClient.zrange(SMS_FALLBACK_KEY, 0, -1)
        members.forEach { member ->
            val data =
                try {
                    Json.decodeFromString<Map<String, String>>(member)
                } catch (e: Exception) {
                    return@forEach
                }
            if (data["incidentId"] == incidentId.toString()) {
                redisClient.zrem(SMS_FALLBACK_KEY, member)
            }
        }
    }

    private fun checkSmsFallbackTimeouts() {
        val now = Clock.System.now()
        val expiredItems = redisClient.zrangebyscore(SMS_FALLBACK_KEY, 0.0, now.epochSeconds.toDouble())

        expiredItems.forEach { item ->
            try {
                val data = Json.decodeFromString<Map<String, String>>(item)
                val incidentId = data["incidentId"]?.toIntOrNull() ?: return@forEach
                val userId = data["userId"]?.toIntOrNull() ?: return@forEach
                val phoneNumber = data["phoneNumber"] ?: return@forEach
                val title = data["title"] ?: return@forEach
                val priority = data["priority"] ?: data["priorityLevel"] ?: return@forEach
                val stepIndex = data["stepIndex"]?.toIntOrNull() ?: return@forEach
                val iteration = data["iteration"]?.toIntOrNull() ?: return@forEach

                if (isFallbackStillValid(incidentId, userId, stepIndex, iteration)) {
                    logger.info("SMS/call fallback firing for incident $incidentId, user $userId")
                    scope.launch {
                        try {
                            twilioService.sendSms(phoneNumber, incidentId, title, priority, userId)
                            twilioService.makeCall(phoneNumber, incidentId, title, priority, userId)
                            logTimelineEvent(
                                incidentId,
                                "NOTIFICATION_SENT",
                                userId,
                                mapOf("channel" to JsonPrimitive("sms,call"), "toPhone" to JsonPrimitive(phoneNumber)),
                            )
                        } catch (e: Exception) {
                            logger.error("Failed to send SMS/call fallback for incident $incidentId", e)
                        }
                    }
                } else {
                    logger.info("Skipping stale SMS/call fallback for incident $incidentId, user $userId")
                }

                redisClient.zrem(SMS_FALLBACK_KEY, item)
            } catch (e: Exception) {
                logger.error("Error processing SMS fallback item: $item", e)
                redisClient.zrem(SMS_FALLBACK_KEY, item)
            }
        }
    }

    private fun checkTimeouts() {
        val now = Clock.System.now()
        val expiredItems = redisClient.zrangebyscore(ACTIVE_INCIDENTS_KEY, 0.0, now.epochSeconds.toDouble())

        expiredItems.forEach { item ->
            try {
                val data = Json.decodeFromString<Map<String, String>>(item)
                val incidentId = data["incidentId"]?.toIntOrNull() ?: return@forEach
                val nextStep = data["nextStep"]?.toIntOrNull() ?: return@forEach
                val iteration = data["iteration"]?.toIntOrNull() ?: return@forEach

                logger.info("Timeout expired for incident $incidentId, advancing to step $nextStep")

                logTimelineEvent(
                    incidentId,
                    "STEP_TIMEOUT",
                    null,
                    mapOf("step" to JsonPrimitive((nextStep - 1).toString())),
                )
                processEscalationStep(incidentId, nextStep, iteration)

                // Only remove from queue on success — transient failures leave item for retry
                redisClient.zrem(ACTIVE_INCIDENTS_KEY, item)
            } catch (e: Exception) {
                logger.error("Error processing timeout for item: $item", e)
                // Do NOT zrem here: keep item in queue so it retries on next poll
            }
        }
    }

    fun acknowledgeAlert(
        incidentId: Int,
        userId: Int,
    ): Boolean {
        val acknowledged = transaction {
            val now = Clock.System.now()

            val updated =
                OnCallAlerts.update({
                    (OnCallAlerts.id eq incidentId) and (OnCallAlerts.status eq "TRIGGERED")
                }) {
                    it[status] = "ACKNOWLEDGED"
                    it[acknowledgedAt] = now
                    it[acknowledgedBy] = userId
                    it[updatedAt] = now
                }

            if (updated > 0) {
                removeTimeout(incidentId)
                removeSmsFallback(incidentId)
                logTimelineEvent(incidentId, "ACKNOWLEDGED", userId, null)
                logger.info("Incident $incidentId acknowledged by user $userId")
                true
            } else {
                false
            }
        }
        if (acknowledged) {
            escalationPathService.appendEventForAlert(
                incidentId,
                EscalationExecutionEvent(
                    type = EscalationPathService.EVENT_ACKNOWLEDGED,
                    actorUserId = userId,
                ),
            )
        }
        return acknowledged
    }

    fun acknowledgeAlertByPhone(incidentId: Int): Boolean {
        val acknowledged = transaction {
            val now = Clock.System.now()
            val updated =
                OnCallAlerts.update({
                    (OnCallAlerts.id eq incidentId) and (OnCallAlerts.status eq "TRIGGERED")
                }) {
                    it[status] = "ACKNOWLEDGED"
                    it[acknowledgedAt] = now
                    it[updatedAt] = now
                }
            if (updated > 0) {
                removeTimeout(incidentId)
                removeSmsFallback(incidentId)
                logTimelineEvent(incidentId, "ACKNOWLEDGED", null, mapOf("channel" to JsonPrimitive("phone")))
                logger.info("Incident $incidentId acknowledged via phone call")
                true
            } else {
                false
            }
        }
        if (acknowledged) {
            escalationPathService.appendEventForAlert(
                incidentId,
                EscalationExecutionEvent(
                    type = EscalationPathService.EVENT_ACKNOWLEDGED,
                    details = mapOf("channel" to JsonPrimitive("phone")),
                ),
            )
        }
        return acknowledged
    }

    fun resolveAlert(
        incidentId: Int,
        userId: Int,
    ): Boolean {
        val resolved = transaction {
            val now = Clock.System.now()

            val updated =
                OnCallAlerts.update({
                    (OnCallAlerts.id eq incidentId) and (OnCallAlerts.status inList listOf("TRIGGERED", "ACKNOWLEDGED"))
                }) {
                    it[status] = "RESOLVED"
                    it[resolvedAt] = now
                    it[resolvedBy] = userId
                    it[updatedAt] = now
                }

            if (updated > 0) {
                removeTimeout(incidentId)
                removeSmsFallback(incidentId)
                logTimelineEvent(incidentId, "RESOLVED", userId, null)
                logger.info("Incident $incidentId resolved by user $userId")
                true
            } else {
                false
            }
        }
        if (resolved) {
            escalationPathService.appendEventForAlert(
                incidentId,
                EscalationExecutionEvent(
                    type = EscalationPathService.EVENT_RECOVERED,
                    actorUserId = userId,
                ),
            )
        }
        return resolved
    }

    fun reassignAlert(
        incidentId: Int,
        toUserId: Int,
        byUserId: Int,
    ): Boolean {
        val reassigned = transaction {
            val incident =
                OnCallAlerts
                    .selectAll()
                    .where { OnCallAlerts.id eq incidentId }
                    .singleOrNull() ?: return@transaction false

            if (incident[OnCallAlerts.status] != "TRIGGERED") {
                return@transaction false
            }

            // Notify new user
            notifyUser(
                incidentId,
                toUserId,
                incident[OnCallAlerts.title],
                incident[OnCallAlerts.priority],
                stepIndex = incident[OnCallAlerts.currentStep],
                iteration = incident[OnCallAlerts.repeatIteration]
            )

            logTimelineEvent(
                incidentId,
                "REASSIGNED",
                byUserId,
                mapOf("to_user_id" to JsonPrimitive(toUserId.toString())),
            )

            logger.info("Incident $incidentId reassigned to user $toUserId by $byUserId")
            true
        }
        if (reassigned) {
            escalationPathService.appendEventForAlert(
                incidentId,
                EscalationExecutionEvent(
                    type = EscalationPathService.EVENT_REASSIGNED,
                    actorUserId = byUserId,
                    details = mapOf("toUserId" to JsonPrimitive(toUserId.toString())),
                ),
            )
        }
        return reassigned
    }

    private fun logTimelineEvent(
        incidentId: Int,
        eventType: String,
        actorUserId: Int?,
        details: Map<String, JsonElement>?,
    ) {
        transaction {
            insertTimelineEvent(incidentId, eventType, actorUserId, details)
        }
    }

    private fun insertTimelineEvent(
        incidentId: Int,
        eventType: String,
        actorUserId: Int?,
        details: Map<String, JsonElement>?,
    ) {
        val timelineId = OnCallAlertTimeline.insertAndGetId {
            it[OnCallAlertTimeline.alertId] = incidentId
            it[OnCallAlertTimeline.eventType] = eventType
            it[OnCallAlertTimeline.actorUserId] = actorUserId
            it[OnCallAlertTimeline.details] = details
            it[createdAt] = Clock.System.now()
        }.value
        incidentAlertTimelineBridge.recordForAlertTimeline(timelineId)
    }

    fun getNextEscalationTimes(): Map<Int, String> {
        val members = redisClient.zrangeWithScores(ACTIVE_INCIDENTS_KEY, 0, -1)
        val result = mutableMapOf<Int, String>()
        members.forEach { (member, score) ->
            try {
                val data = Json.decodeFromString<Map<String, String>>(member)
                val incidentId = data["incidentId"]?.toIntOrNull() ?: return@forEach
                val instant = Instant.fromEpochSeconds(score.toLong())
                result[incidentId] = instant.toString()
            } catch (_: Exception) {
            }
        }
        return result
    }

    fun markUnavailable(
        incidentId: Int,
        userId: Int,
    ): Boolean =
        transaction {
            val incident =
                OnCallAlerts
                    .selectAll()
                    .where { OnCallAlerts.id eq incidentId }
                    .singleOrNull() ?: return@transaction false

            if (incident[OnCallAlerts.status] != "TRIGGERED") return@transaction false

            val policyId = incident[OnCallAlerts.escalationPolicyId] ?: return@transaction false
            val policy = escalationPolicyService.getPolicy(policyId) ?: return@transaction false
            val currentStepIndex = incident[OnCallAlerts.currentStep]

            // Find the next step's first user target to reassign to
            val nextStepIndex = currentStepIndex + 1
            if (nextStepIndex < policy.steps.size) {
                val nextStep = policy.steps[nextStepIndex]
                val nextTarget = nextStep.targets.firstOrNull()
                if (nextTarget != null) {
                    val targetUserId =
                        when (nextTarget.targetType) {
                            "USER" -> nextTarget.targetId
                            "ON_CALL_SCHEDULE" -> onCallScheduleService.getCurrentOnCall(nextTarget.targetId)?.userId
                            else -> null
                        }
                    if (targetUserId != null) {
                        logTimelineEvent(
                            incidentId,
                            "REASSIGNED",
                            userId,
                            mapOf(
                                "reason" to JsonPrimitive("unavailable"),
                                "to_user_id" to JsonPrimitive(targetUserId.toString()),
                            ),
                        )
                        // Force advance to next step immediately
                        removeTimeout(incidentId)
                        processEscalationStep(incidentId, nextStepIndex, incident[OnCallAlerts.repeatIteration])
                        return@transaction true
                    }
                }
            }

            false
        }

    fun acknowledgeIncident(
        incidentId: Int,
        userId: Int,
    ): Boolean = acknowledgeAlert(incidentId, userId)

    fun acknowledgeIncidentByPhone(incidentId: Int): Boolean = acknowledgeAlertByPhone(incidentId)

    fun resolveIncident(
        incidentId: Int,
        userId: Int,
    ): Boolean = resolveAlert(incidentId, userId)

    fun reassignIncident(
        incidentId: Int,
        toUserId: Int,
        byUserId: Int,
    ): Boolean = reassignAlert(incidentId, toUserId, byUserId)

    private fun getAlert(incidentId: Int): OnCallAlert? =
        transaction {
            val row =
                OnCallAlerts
                    .join(EscalationPolicies, JoinType.LEFT, OnCallAlerts.escalationPolicyId, EscalationPolicies.id)
                    .selectAll()
                    .where { OnCallAlerts.id eq incidentId }
                    .singleOrNull() ?: return@transaction null

            OnCallAlert(
                id = row[OnCallAlerts.resourceId].toString(),
                organizationResourceId = organizationResourceId(row[OnCallAlerts.organizationId]),
                declaredIncidentResourceId = incidentResourceIdOrNull(row[OnCallAlerts.declaredIncidentId]),
                escalationPolicyResourceId = escalationPolicyResourceIdOrNull(row[OnCallAlerts.escalationPolicyId]),
                escalationPolicyName = row.getOrNull(EscalationPolicies.name),
                title = row[OnCallAlerts.title],
                description = row[OnCallAlerts.description],
                priority = row[OnCallAlerts.priority],
                status = row[OnCallAlerts.status],
                alertSource = row[OnCallAlerts.alertSource],
                deduplicationKey = row[OnCallAlerts.deduplicationKey],
                currentStep = row[OnCallAlerts.currentStep],
                repeatIteration = row[OnCallAlerts.repeatIteration],
                triggeredAt = row[OnCallAlerts.triggeredAt].toString(),
                acknowledgedAt = row[OnCallAlerts.acknowledgedAt]?.toString(),
                acknowledgedByResourceId = userResourceIdOrNull(row[OnCallAlerts.acknowledgedBy]),
                resolvedAt = row[OnCallAlerts.resolvedAt]?.toString(),
                resolvedByResourceId = userResourceIdOrNull(row[OnCallAlerts.resolvedBy]),
                metadata = row[OnCallAlerts.metadata],
                createdAt = row[OnCallAlerts.createdAt].toString(),
                updatedAt = row[OnCallAlerts.updatedAt].toString(),
                internalId = row[OnCallAlerts.id].value,
                organizationId = row[OnCallAlerts.organizationId],
                declaredIncidentId = row[OnCallAlerts.declaredIncidentId],
                escalationPolicyId = row[OnCallAlerts.escalationPolicyId],
                acknowledgedBy = row[OnCallAlerts.acknowledgedBy],
                resolvedBy = row[OnCallAlerts.resolvedBy],
            )
        }
}
