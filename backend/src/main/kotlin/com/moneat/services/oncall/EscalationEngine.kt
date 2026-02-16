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

package com.moneat.services.oncall

import com.moneat.config.RedisClient
import com.moneat.models.*
import com.moneat.services.SlackService
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.and
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class EscalationEngine(
    private val escalationPolicyService: EscalationPolicyService,
    private val onCallScheduleService: OnCallScheduleService,
    private val pushNotificationService: PushNotificationService,
    private val slackService: SlackService,
    private val redisClient: RedisClient
) {
    private val logger = LoggerFactory.getLogger(EscalationEngine::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var timeoutPollingJob: Job? = null
    
    companion object {
        private const val TIMEOUT_KEY_PREFIX = "escalation:timeout:"
        private const val ACTIVE_INCIDENTS_KEY = "escalation:active"
    }
    
    fun start() {
        logger.info("Starting escalation engine timeout polling")
        timeoutPollingJob = scope.launch {
            while (isActive) {
                try {
                    checkTimeouts()
                } catch (e: Exception) {
                    logger.error("Error checking escalation timeouts", e)
                }
                delay(10.seconds)
            }
        }
    }
    
    fun stop() {
        logger.info("Stopping escalation engine")
        timeoutPollingJob?.cancel()
        scope.cancel()
    }
    
    fun triggerEscalation(
        organizationId: Int,
        escalationPolicyId: Int,
        title: String,
        description: String?,
        priorityLevel: String,
        alertSource: String?,
        deduplicationKey: String?,
        metadata: Map<String, JsonElement>? = null
    ): Incident? {
        return transaction {
            val now = Clock.System.now()
            
            // Check for existing open incident with same deduplication key
            if (deduplicationKey != null) {
                val existing = Incidents
                    .selectAll()
                    .where { 
                        (Incidents.organizationId eq organizationId) and
                        (Incidents.deduplicationKey eq deduplicationKey) and
                        (Incidents.status inList listOf("TRIGGERED", "ACKNOWLEDGED"))
                    }
                    .singleOrNull()
                
                if (existing != null) {
                    logger.info("Incident already exists for dedup key: $deduplicationKey")
                    return@transaction null
                }
            }
            
            // Create incident
            val incidentId = Incidents.insertAndGetId {
                it[Incidents.organizationId] = organizationId
                it[Incidents.escalationPolicyId] = escalationPolicyId
                it[Incidents.title] = title
                it[Incidents.description] = description
                it[Incidents.priorityLevel] = priorityLevel
                it[Incidents.status] = "TRIGGERED"
                it[Incidents.alertSource] = alertSource
                it[Incidents.deduplicationKey] = deduplicationKey
                it[currentStep] = 0
                it[repeatIteration] = 0
                it[triggeredAt] = now
                it[Incidents.metadata] = metadata
                it[createdAt] = now
                it[updatedAt] = now
            }.value
            
            // Log timeline event
            logTimelineEvent(incidentId, "TRIGGERED", null, mapOf("priority" to JsonPrimitive(priorityLevel)))
            
            // Start escalation
            processEscalationStep(incidentId, 0, 0)
            
            getIncident(incidentId)
        }
    }
    
    private fun processEscalationStep(incidentId: Int, stepIndex: Int, iteration: Int) {
        transaction {
            val incident = Incidents
                .selectAll()
                .where { Incidents.id eq incidentId }
                .singleOrNull() ?: return@transaction
            
            // Check if incident is still in TRIGGERED status
            if (incident[Incidents.status] != "TRIGGERED") {
                logger.info("Incident $incidentId no longer in TRIGGERED status, stopping escalation")
                removeTimeout(incidentId)
                return@transaction
            }
            
            val policyId = incident[Incidents.escalationPolicyId] ?: return@transaction
            val policy = escalationPolicyService.getPolicy(policyId) ?: return@transaction
            
            // Check if we've exhausted all steps
            if (stepIndex >= policy.steps.size) {
                if (iteration < policy.repeatCount) {
                    // Loop back to step 0, increment iteration
                    logger.info("Repeating escalation for incident $incidentId, iteration ${iteration + 1}")
                    Incidents.update({ Incidents.id eq incidentId }) {
                        it[currentStep] = 0
                        it[repeatIteration] = iteration + 1
                        it[updatedAt] = Clock.System.now()
                    }
                    processEscalationStep(incidentId, 0, iteration + 1)
                } else {
                    logger.warn("Escalation exhausted for incident $incidentId after $iteration iterations")
                    logTimelineEvent(incidentId, "ESCALATED", null, mapOf("result" to JsonPrimitive("exhausted")))
                }
                return@transaction
            }
            
            val step = policy.steps[stepIndex]
            logger.info("Processing escalation step $stepIndex for incident $incidentId")
            
            // Update current step
            Incidents.update({ Incidents.id eq incidentId }) {
                it[currentStep] = stepIndex
                it[updatedAt] = Clock.System.now()
            }
            
            // Notify all targets in this step
            step.targets.forEach { target ->
                when (target.targetType) {
                    "USER" -> {
                        notifyUser(incidentId, target.targetId, incident[Incidents.title], incident[Incidents.priorityLevel])
                    }
                    "ON_CALL_SCHEDULE" -> {
                        val onCall = onCallScheduleService.getCurrentOnCall(target.targetId)
                        if (onCall != null) {
                            notifyUser(incidentId, onCall.userId, incident[Incidents.title], incident[Incidents.priorityLevel])
                        } else {
                            logger.warn("No on-call user found for schedule ${target.targetId}")
                        }
                    }
                }
            }
            
            logTimelineEvent(
                incidentId,
                "ESCALATED",
                null,
                mapOf("step" to JsonPrimitive(stepIndex.toString()), "iteration" to JsonPrimitive(iteration.toString()))
            )
            
            // Schedule timeout
            scheduleTimeout(incidentId, step.timeoutMinutes.toLong(), stepIndex + 1, iteration)
        }
    }
    
    private fun notifyUser(incidentId: Int, userId: Int, title: String, priorityLevel: String) {
        val userName = transaction {
            Users.selectAll().where { Users.id eq userId }.singleOrNull()?.get(Users.name)
        }
        
        scope.launch {
            try {
                // Send push notification
                pushNotificationService.sendIncidentAlert(userId, incidentId, title, priorityLevel)
                
                // Send Slack DM
                slackService.sendOnCallAlert(userId, incidentId, title, priorityLevel)
                
                logTimelineEvent(
                    incidentId,
                    "NOTIFICATION_SENT",
                    userId,
                    mapOf("channel" to JsonPrimitive("push,slack"), "toUserName" to JsonPrimitive(userName ?: "Unknown"))
                )
            } catch (e: Exception) {
                logger.error("Failed to notify user $userId for incident $incidentId", e)
            }
        }
    }
    
    private fun scheduleTimeout(incidentId: Int, timeoutMinutes: Long, nextStep: Int, iteration: Int) {
        val timeoutAt = Clock.System.now().plus(timeoutMinutes.minutes)
        
        val timeoutData = mapOf(
            "incidentId" to incidentId.toString(),
            "nextStep" to nextStep.toString(),
            "iteration" to iteration.toString()
        )
        
        redisClient.zadd(ACTIVE_INCIDENTS_KEY, timeoutAt.epochSeconds.toDouble(), Json.encodeToString(kotlinx.serialization.serializer(), timeoutData))
        logger.debug("Scheduled timeout for incident $incidentId at $timeoutAt")
    }
    
    private fun removeTimeout(incidentId: Int) {
        // Remove from active incidents
        val members = redisClient.zrange(ACTIVE_INCIDENTS_KEY, 0, -1)
        members.forEach { member ->
            val data = try {
                Json.decodeFromString<Map<String, String>>(member)
            } catch (e: Exception) {
                return@forEach
            }
            if (data["incidentId"] == incidentId.toString()) {
                redisClient.zrem(ACTIVE_INCIDENTS_KEY, member)
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
                
                logTimelineEvent(incidentId, "STEP_TIMEOUT", null, mapOf("step" to JsonPrimitive((nextStep - 1).toString())))
                processEscalationStep(incidentId, nextStep, iteration)
                
                // Remove from queue
                redisClient.zrem(ACTIVE_INCIDENTS_KEY, item)
            } catch (e: Exception) {
                logger.error("Error processing timeout for item: $item", e)
                redisClient.zrem(ACTIVE_INCIDENTS_KEY, item)
            }
        }
    }
    
    fun acknowledgeIncident(incidentId: Int, userId: Int): Boolean = transaction {
        val now = Clock.System.now()
        
        val updated = Incidents.update({
            (Incidents.id eq incidentId) and (Incidents.status eq "TRIGGERED")
        }) {
            it[status] = "ACKNOWLEDGED"
            it[acknowledgedAt] = now
            it[acknowledgedBy] = userId
            it[updatedAt] = now
        }
        
        if (updated > 0) {
            removeTimeout(incidentId)
            logTimelineEvent(incidentId, "ACKNOWLEDGED", userId, null)
            logger.info("Incident $incidentId acknowledged by user $userId")
            true
        } else {
            false
        }
    }
    
    fun resolveIncident(incidentId: Int, userId: Int): Boolean = transaction {
        val now = Clock.System.now()
        
        val updated = Incidents.update({
            (Incidents.id eq incidentId) and (Incidents.status inList listOf("TRIGGERED", "ACKNOWLEDGED"))
        }) {
            it[status] = "RESOLVED"
            it[resolvedAt] = now
            it[resolvedBy] = userId
            it[updatedAt] = now
        }
        
        if (updated > 0) {
            removeTimeout(incidentId)
            logTimelineEvent(incidentId, "RESOLVED", userId, null)
            logger.info("Incident $incidentId resolved by user $userId")
            true
        } else {
            false
        }
    }
    
    fun reassignIncident(incidentId: Int, toUserId: Int, byUserId: Int): Boolean = transaction {
        val incident = Incidents
            .selectAll()
            .where { Incidents.id eq incidentId }
            .singleOrNull() ?: return@transaction false
        
        if (incident[Incidents.status] != "TRIGGERED") {
            return@transaction false
        }
        
        // Notify new user
        notifyUser(incidentId, toUserId, incident[Incidents.title], incident[Incidents.priorityLevel])
        
        logTimelineEvent(
            incidentId,
            "REASSIGNED",
            byUserId,
            mapOf("to_user_id" to JsonPrimitive(toUserId.toString()))
        )
        
        logger.info("Incident $incidentId reassigned to user $toUserId by $byUserId")
        true
    }
    
    private fun logTimelineEvent(
        incidentId: Int,
        eventType: String,
        actorUserId: Int?,
        details: Map<String, JsonElement>?
    ) {
        transaction {
            IncidentTimeline.insert {
                it[IncidentTimeline.incidentId] = incidentId
                it[IncidentTimeline.eventType] = eventType
                it[IncidentTimeline.actorUserId] = actorUserId
                it[IncidentTimeline.details] = details
                it[createdAt] = Clock.System.now()
            }
        }
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
            } catch (_: Exception) { }
        }
        return result
    }
    
    fun markUnavailable(incidentId: Int, userId: Int): Boolean = transaction {
        val incident = Incidents
            .selectAll()
            .where { Incidents.id eq incidentId }
            .singleOrNull() ?: return@transaction false
        
        if (incident[Incidents.status] != "TRIGGERED") return@transaction false
        
        val policyId = incident[Incidents.escalationPolicyId] ?: return@transaction false
        val policy = escalationPolicyService.getPolicy(policyId) ?: return@transaction false
        val currentStepIndex = incident[Incidents.currentStep]
        
        // Find the next step's first user target to reassign to
        val nextStepIndex = currentStepIndex + 1
        if (nextStepIndex < policy.steps.size) {
            val nextStep = policy.steps[nextStepIndex]
            val nextTarget = nextStep.targets.firstOrNull()
            if (nextTarget != null) {
                val targetUserId = when (nextTarget.targetType) {
                    "USER" -> nextTarget.targetId
                    "ON_CALL_SCHEDULE" -> onCallScheduleService.getCurrentOnCall(nextTarget.targetId)?.userId
                    else -> null
                }
                if (targetUserId != null) {
                    logTimelineEvent(incidentId, "REASSIGNED", userId, mapOf(
                        "reason" to JsonPrimitive("unavailable"),
                        "to_user_id" to JsonPrimitive(targetUserId.toString())
                    ))
                    // Force advance to next step immediately
                    removeTimeout(incidentId)
                    processEscalationStep(incidentId, nextStepIndex, incident[Incidents.repeatIteration])
                    return@transaction true
                }
            }
        }
        
        false
    }
    
    private fun getIncident(incidentId: Int): Incident? = transaction {
        val row = Incidents
            .leftJoin(EscalationPolicies, { escalationPolicyId }, { id })
            .selectAll()
            .where { Incidents.id eq incidentId }
            .singleOrNull() ?: return@transaction null
        
        Incident(
            id = row[Incidents.id].value,
            organizationId = row[Incidents.organizationId],
            escalationPolicyId = row[Incidents.escalationPolicyId],
            escalationPolicyName = row.getOrNull(EscalationPolicies.name),
            title = row[Incidents.title],
            description = row[Incidents.description],
            priorityLevel = row[Incidents.priorityLevel],
            status = row[Incidents.status],
            alertSource = row[Incidents.alertSource],
            deduplicationKey = row[Incidents.deduplicationKey],
            currentStep = row[Incidents.currentStep],
            repeatIteration = row[Incidents.repeatIteration],
            triggeredAt = row[Incidents.triggeredAt].toString(),
            acknowledgedAt = row[Incidents.acknowledgedAt]?.toString(),
            acknowledgedBy = row[Incidents.acknowledgedBy],
            resolvedAt = row[Incidents.resolvedAt]?.toString(),
            resolvedBy = row[Incidents.resolvedBy],
            metadata = row[Incidents.metadata],
            createdAt = row[Incidents.createdAt].toString(),
            updatedAt = row[Incidents.updatedAt].toString()
        )
    }
}
