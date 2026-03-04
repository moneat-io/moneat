// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall

import com.moneat.config.RedisClient
import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.IncidentInfo
import com.moneat.enterprise.OnCallBridge
import com.moneat.enterprise.PriorityInfo
import com.moneat.enterprise.oncall.routes.deviceRoutes
import com.moneat.enterprise.oncall.routes.escalationRoutes
import com.moneat.enterprise.oncall.routes.incidentRoutes
import com.moneat.enterprise.oncall.routes.notificationPreferencesRoutes
import com.moneat.enterprise.oncall.routes.onCallRoutes
import com.moneat.enterprise.oncall.routes.priorityRoutes
import com.moneat.enterprise.oncall.routes.twilioWebhookRoutes
import com.moneat.enterprise.oncall.services.BusinessHoursService
import com.moneat.enterprise.oncall.services.EscalationEngine
import com.moneat.enterprise.oncall.services.EscalationPolicyService
import com.moneat.enterprise.oncall.services.IncidentManagementService
import com.moneat.enterprise.oncall.services.OnCallHandoffService
import com.moneat.enterprise.oncall.services.OnCallScheduleService
import com.moneat.enterprise.oncall.services.PriorityService
import com.moneat.enterprise.oncall.services.PushNotificationService
import com.moneat.enterprise.oncall.services.ShiftChangeNotifier
import com.moneat.enterprise.oncall.services.SlackUserGroupSyncService
import com.moneat.notifications.services.SlackService
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Enterprise module for on-call management, escalation, and incident response.
 * Also implements [OnCallBridge] so that core code can optionally call
 * enterprise escalation features.
 */
class OnCallModule :
    EnterpriseModule,
    OnCallBridge {
    private lateinit var escalationEngine: EscalationEngine
    private lateinit var incidentManagementService: IncidentManagementService
    private lateinit var pushNotificationService: PushNotificationService
    private var slackUserGroupSyncService: SlackUserGroupSyncService? = null
    private var onCallHandoffService: OnCallHandoffService? = null
    private var shiftChangeNotifier: ShiftChangeNotifier? = null
    private val priorityService = PriorityService()
    private val businessHoursService = BusinessHoursService()

    override val name: String = "On-Call"
    override val licenseFeature: String = "oncall"

    override fun registerRoutes(route: Route) {
        route.apply {
            onCallRoutes(
                { slackUserGroupSyncService },
                { pushNotificationService },
            )
            escalationRoutes()
            priorityRoutes()
            deviceRoutes()
            incidentRoutes({ incidentManagementService })
            twilioWebhookRoutes()
            notificationPreferencesRoutes { pushNotificationService }
        }
    }

    override fun startBackgroundJobs(application: Application) {
        val escalationPolicyService = EscalationPolicyService()
        val onCallScheduleService = OnCallScheduleService()
        pushNotificationService = PushNotificationService()
        val slackService = SlackService()
        val redisClient = RedisClient()

        escalationEngine =
            EscalationEngine(
                escalationPolicyService = escalationPolicyService,
                onCallScheduleService = onCallScheduleService,
                pushNotificationService = pushNotificationService,
                slackService = slackService,
                redisClient = redisClient,
            )

        incidentManagementService =
            IncidentManagementService(
                escalationEngine = escalationEngine,
            )

        slackUserGroupSyncService =
            SlackUserGroupSyncService(
                onCallScheduleService = onCallScheduleService,
                slackService = slackService,
                redisClient = redisClient,
            )

        onCallHandoffService =
            OnCallHandoffService(
                onCallScheduleService = onCallScheduleService,
                pushNotificationService = pushNotificationService,
                redisClient = redisClient,
            )

        shiftChangeNotifier =
            ShiftChangeNotifier(
                onCallScheduleService = onCallScheduleService,
                pushNotificationService = pushNotificationService,
            )

        logger.info { "Starting on-call enterprise background jobs" }
        escalationEngine.start()
        slackUserGroupSyncService?.start()
        onCallHandoffService?.start()
        shiftChangeNotifier?.start()
    }

    override fun stopBackgroundJobs() {
        logger.info { "Stopping on-call enterprise background jobs" }
        escalationEngine.stop()
        slackUserGroupSyncService?.stop()
        onCallHandoffService?.stop()
        shiftChangeNotifier?.stop()
    }

    // OnCallBridge implementation

    override fun resolvePriority(
        organizationId: Int,
        severity: String,
    ): PriorityInfo? {
        val priority = priorityService.resolvePriority(organizationId, severity) ?: return null
        return PriorityInfo(priorityLevel = priority.priorityLevel, label = priority.label)
    }

    override fun shouldEscalate(
        organizationId: Int,
        priorityLevel: String,
    ): Boolean = businessHoursService.shouldEscalate(organizationId, priorityLevel)

    override suspend fun triggerEscalation(
        organizationId: Int,
        escalationPolicyId: Int,
        title: String,
        description: String?,
        priorityLevel: String,
        alertSource: String,
        deduplicationKey: String?,
        metadata: String?,
    ): Int? {
        val metadataMap: Map<String, JsonElement>? =
            metadata?.let {
                try {
                    Json.decodeFromString<Map<String, JsonElement>>(it)
                } catch (_: Exception) {
                    null
                }
            }
        val incident =
            escalationEngine.triggerEscalation(
                organizationId = organizationId,
                escalationPolicyId = escalationPolicyId,
                title = title,
                description = description,
                priorityLevel = priorityLevel,
                alertSource = alertSource,
                deduplicationKey = deduplicationKey,
                metadata = metadataMap,
            )
        return incident?.id
    }

    override fun getIncident(
        incidentId: Int,
        userId: Int,
    ): IncidentInfo? {
        val incident = incidentManagementService.getIncident(incidentId, userId) ?: return null
        return IncidentInfo(
            id = incident.id,
            organizationId = incident.organizationId,
            title = incident.title,
            status = incident.status,
        )
    }

    override fun acknowledgeIncident(
        incidentId: Int,
        userId: Int,
    ): Boolean = incidentManagementService.acknowledge(incidentId, userId)
}
