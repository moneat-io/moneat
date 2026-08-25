// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall

import com.moneat.config.RedisClient
import com.moneat.alerts.services.AlertRouteFanout
import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.IncidentInfo
import com.moneat.enterprise.OnCallBridge
import com.moneat.enterprise.OnCallIncidentDeclaration
import com.moneat.enterprise.OnCallUserInfo
import com.moneat.enterprise.PriorityInfo
import com.moneat.enterprise.alertroutes.routes.alertRouteRoutes
import com.moneat.enterprise.alertroutes.routes.alertGroupRoutes
import com.moneat.enterprise.alertroutes.services.EnterpriseAlertRouteFanout
import com.moneat.enterprise.alertroutes.services.AlertRouteExecutionService
import com.moneat.enterprise.alertroutes.services.AlertRouteRecoveryWorker
import com.moneat.enterprise.incidents.commands.DeclareIncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.events.IncidentOutboxService
import com.moneat.enterprise.incidents.events.IncidentOutboxWorker
import com.moneat.enterprise.incidents.events.IncidentResponseEventConsumer
import com.moneat.enterprise.incidents.events.IncidentSlackChannelEventConsumer
import com.moneat.enterprise.incidents.events.WorkflowIncidentEventConsumer
import com.moneat.enterprise.incidents.response.IncidentResponseActivationService
import com.moneat.enterprise.incidents.response.IncidentResponsePager
import com.moneat.enterprise.incidents.response.IncidentResponsePageRequest
import com.moneat.enterprise.incidents.response.IncidentResponsePolicyService
import com.moneat.enterprise.oncall.routes.deviceRoutes
import com.moneat.enterprise.oncall.routes.escalationRoutes
import com.moneat.enterprise.oncall.routes.incidentRoutes
import com.moneat.enterprise.oncall.routes.notificationPreferencesRoutes
import com.moneat.enterprise.oncall.routes.onCallRoutes
import com.moneat.enterprise.oncall.routes.priorityRoutes
import com.moneat.enterprise.oncall.routes.twilioWebhookRoutes
import com.moneat.enterprise.oncall.mcp.GetOnCallAlertTool
import com.moneat.enterprise.oncall.mcp.ListOnCallAlertsTool
import com.moneat.enterprise.oncall.mcp.ListSchedulesTool
import com.moneat.enterprise.oncall.services.BusinessHoursService
import com.moneat.enterprise.oncall.services.EscalationEngine
import com.moneat.enterprise.oncall.services.EscalationPolicyService
import com.moneat.enterprise.oncall.services.EscalationPathService
import com.moneat.enterprise.oncall.services.OnCallHandoffService
import com.moneat.enterprise.oncall.services.OnCallAlertService
import com.moneat.enterprise.oncall.services.OnCallIncidentService
import com.moneat.enterprise.oncall.services.OnCallScheduleService
import com.moneat.enterprise.oncall.services.PriorityService
import com.moneat.enterprise.oncall.services.PushNotificationService
import com.moneat.enterprise.oncall.services.ShiftChangeNotifier
import com.moneat.enterprise.oncall.services.SlackUserGroupSyncService
import com.moneat.mcp.McpToolContributor
import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.notifications.services.SlackService
import com.moneat.notifications.services.SlackInstallationService
import com.moneat.shared.models.SlackUserMappings
import io.ktor.server.application.Application
import io.ktor.http.parseQueryString
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}
private const val SLACK_TITLE_PREFILL_MAX_CHARS = 150

/**
 * Enterprise module for on-call management, escalation, and incident response.
 * Also implements [OnCallBridge] so that core code can optionally call
 * enterprise escalation features.
 */
class OnCallModule :
    EnterpriseModule,
    McpToolContributor,
    OnCallBridge {
    private val priorityService = PriorityService()
    private val businessHoursService = BusinessHoursService()
    private val escalationPolicyService = EscalationPolicyService()
    private val escalationPathService = EscalationPathService()
    private val onCallScheduleService = OnCallScheduleService()
    private val onCallIncidentService = OnCallIncidentService()
    private val pushNotificationService by lazy { PushNotificationService() }
    private val slackInstallationService by lazy { SlackInstallationService() }
    private val slackService by lazy { SlackService(installationService = slackInstallationService) }
    private val redisClient by lazy { RedisClient() }
    private val escalationEngineDelegate =
        lazy {
            EscalationEngine(
                escalationPolicyService = escalationPolicyService,
                onCallScheduleService = onCallScheduleService,
                pushNotificationService = pushNotificationService,
                slackService = slackService,
                redisClient = redisClient,
                escalationPathService = escalationPathService,
            )
        }
    private val escalationEngine by escalationEngineDelegate
    private val incidentResponsePolicyService = IncidentResponsePolicyService()
    private val incidentResponseActivationService by lazy {
        IncidentResponseActivationService(
            policyService = incidentResponsePolicyService,
            pager = IncidentResponsePager { request: IncidentResponsePageRequest ->
                escalationEngine.triggerEscalation(
                    organizationId = request.organizationId,
                    escalationPolicyId = request.escalationPolicyId,
                    title = request.title,
                    description = "Incident response activation",
                    priority = request.severity,
                    alertSource = "incident-response",
                    deduplicationKey = request.deduplicationKey,
                    metadata = request.metadata,
                )?.internalId
            },
        )
    }
    private val onCallAlertService by lazy {
        OnCallAlertService(
            escalationEngine = escalationEngine,
            responseActivationService = incidentResponseActivationService,
        )
    }
    private val slackUserGroupSyncServiceDelegate =
        lazy {
            SlackUserGroupSyncService(
                onCallScheduleService = onCallScheduleService,
                slackService = slackService,
                redisClient = redisClient,
                slackInstallationService = slackInstallationService,
            )
        }
    private val slackUserGroupSyncService by slackUserGroupSyncServiceDelegate
    private val onCallHandoffServiceDelegate =
        lazy {
            OnCallHandoffService(
                onCallScheduleService = onCallScheduleService,
                pushNotificationService = pushNotificationService,
                redisClient = redisClient,
            )
        }
    private val onCallHandoffService by onCallHandoffServiceDelegate
    private val shiftChangeNotifierDelegate =
        lazy {
            ShiftChangeNotifier(
                onCallScheduleService = onCallScheduleService,
                pushNotificationService = pushNotificationService,
            )
        }
    private val shiftChangeNotifier by shiftChangeNotifierDelegate
    private val incidentOutboxWorkerDelegate =
        lazy {
            IncidentOutboxWorker(
                IncidentOutboxService(
                    consumers = listOf(
                        WorkflowIncidentEventConsumer(),
                        IncidentResponseEventConsumer(incidentResponseActivationService),
                        IncidentSlackChannelEventConsumer(),
                    ),
                ),
            )
        }
    private val incidentOutboxWorker by incidentOutboxWorkerDelegate
    private val alertRouteExecutionService = AlertRouteExecutionService()
    private val alertRouteFanout = EnterpriseAlertRouteFanout(alertRouteExecutionService)
    private val alertRouteRecoveryWorker = AlertRouteRecoveryWorker(alertRouteExecutionService)

    override val name: String = "On-Call"
    override val licenseFeature: String = "oncall"

    override fun koinModules(): List<Module> =
        listOf(
            module {
                single<AlertRouteFanout> { alertRouteFanout }
            },
        )

    override fun registerRoutes(route: Route) {
        route.apply {
            onCallRoutes(
                { slackUserGroupSyncService },
                { pushNotificationService },
            )
            escalationRoutes()
            priorityRoutes()
            deviceRoutes()
            incidentRoutes(
                alertServiceProvider = { onCallAlertService },
                incidentResponseActivationService = incidentResponseActivationService,
            )
            alertRouteRoutes()
            alertGroupRoutes()
            twilioWebhookRoutes()
            notificationPreferencesRoutes { pushNotificationService }
        }
    }

    override fun contributeTools(registry: McpToolRegistry) {
        registry.register(ListOnCallAlertsTool { onCallAlertService })
        registry.register(GetOnCallAlertTool { onCallAlertService })
        registry.register(ListSchedulesTool())
    }

    override fun startBackgroundJobs(application: Application) {
        logger.info { "Starting on-call enterprise background jobs" }
        alertRouteRecoveryWorker.start()
        escalationEngine.start()
        slackUserGroupSyncService.start()
        onCallHandoffService.start()
        shiftChangeNotifier.start()
        incidentOutboxWorker.start()
    }

    override fun stopBackgroundJobs() {
        logger.info { "Stopping on-call enterprise background jobs" }
        alertRouteRecoveryWorker.stop()
        if (escalationEngineDelegate.isInitialized()) escalationEngine.stop()
        if (slackUserGroupSyncServiceDelegate.isInitialized()) slackUserGroupSyncService.stop()
        if (onCallHandoffServiceDelegate.isInitialized()) onCallHandoffService.stop()
        if (shiftChangeNotifierDelegate.isInitialized()) shiftChangeNotifier.stop()
        if (incidentOutboxWorkerDelegate.isInitialized()) incidentOutboxWorker.stop()
    }

    // OnCallBridge implementation

    override fun resolvePriority(
        organizationId: Int,
        priority: String,
    ): PriorityInfo? {
        val resolved = priorityService.resolvePriority(organizationId, priority) ?: return null
        return PriorityInfo(priority = resolved.priority, label = resolved.label)
    }

    override fun shouldEscalate(
        organizationId: Int,
        priority: String,
    ): Boolean = businessHoursService.shouldEscalate(organizationId, priority)

    override fun resolveEscalationPolicyId(
        organizationId: Int,
        escalationPolicyResourceId: String,
    ): Int? = escalationPolicyService.resolveEscalationPolicyId(organizationId, escalationPolicyResourceId)

    override fun resolveAlertId(
        organizationId: Int,
        alertResourceId: String,
    ): Int? = alertIdForResource(organizationId, alertResourceId)

    override fun getCurrentOnCall(
        organizationId: Int,
        scheduleId: Int,
    ): OnCallUserInfo? {
        val participant =
            onCallScheduleService.getCurrentOnCallForOrganization(scheduleId, organizationId) ?: return null
        return OnCallUserInfo(userId = participant.userResourceId, userName = participant.userName)
    }

    override suspend fun triggerEscalation(
        organizationId: Int,
        escalationPolicyId: Int,
        title: String,
        description: String?,
        priority: String,
        alertSource: String,
        deduplicationKey: String?,
        metadata: String?,
    ): String? {
        val metadataMap: Map<String, JsonElement>? =
            metadata?.let {
                try {
                    Json.decodeFromString<Map<String, JsonElement>>(it)
                } catch (_: Exception) {
                    null
                }
            }
        val alert =
            escalationEngine.triggerEscalation(
                organizationId = organizationId,
                escalationPolicyId = escalationPolicyId,
                title = title,
                description = description,
                priority = priority,
                alertSource = alertSource,
                deduplicationKey = deduplicationKey,
                metadata = metadataMap,
            )
        return alert?.id
    }

    override suspend fun resolveEscalation(
        organizationId: Int,
        alertSource: String,
        deduplicationKey: String,
    ): Boolean =
        escalationEngine.resolveAlertByDeduplicationKey(
            organizationId = organizationId,
            alertSource = alertSource,
            deduplicationKey = deduplicationKey,
        )

    override suspend fun declareIncident(declaration: OnCallIncidentDeclaration): String =
        onCallIncidentService
            .declareIncident(
                DeclareIncidentCommand(
                    commandKey = declaration.commandKey,
                    actor = IncidentCommandActor(declaration.organizationId, declaration.userId, declaration.origin),
                    title = declaration.title,
                    description = declaration.description,
                    severity = declaration.severity,
                    onCallAlertId = declaration.alertId,
                ),
            ).id

    override suspend fun handleSlackInbound(
        requestType: String,
        payload: String,
        deliveryId: String?,
    ): String? {
        val form = parseQueryString(payload)
        val root = form["payload"]?.let { Json.parseToJsonElement(it).jsonObject }
        val value: (String) -> String? = { key ->
            root?.get(key)?.jsonPrimitive?.contentOrNull ?: form[key]
        }
        val type = root?.get("type")?.jsonPrimitive?.contentOrNull
        if (requestType == "interactions" && type == "view_submission") {
            return submitSlackIncident(root, value, deliveryId)
        }
        if (requestType == "events" || requestType == "mentions") return null

        return openSlackIncident(root, value)
    }

    private suspend fun openSlackIncident(
        root: JsonObject?,
        value: (String) -> String?,
    ): String? {
        val teamId = value("team_id") ?: root?.get("team")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        val slackUserId = value("user_id") ?: root?.get("user")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        val triggerId = value("trigger_id") ?: root?.get("trigger_id")?.jsonPrimitive?.contentOrNull
        if (teamId.isNullOrBlank() || slackUserId.isNullOrBlank()) {
            return slackEphemeral("Slack workspace and user context are required.")
        }
        val organizationId = slackInstallationService.organizationIdForTeam(teamId)
            ?: return slackEphemeral("This Slack workspace is not connected to a Moneat organization.")
        val installationId = slackInstallationService.internalInstallationIdForTeam(organizationId, teamId)
            ?: return slackEphemeral("This Slack workspace installation is disabled.")
        val linkedUser = transaction {
            SlackUserMappings
                .selectAll()
                .where {
                    (SlackUserMappings.slackInstallationId eq installationId) and
                        (SlackUserMappings.slackUserId eq slackUserId) and
                        (SlackUserMappings.slackTeamId eq teamId)
                }
                .firstOrNull()
        } != null
        if (!linkedUser) return slackEphemeral("Link your Slack identity in Moneat before declaring an incident.")

        val contextText = value("text")
            ?: root?.get("message")?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        if (triggerId.isNullOrBlank()) return slackEphemeral("Slack did not provide a modal trigger.")
        val opened = slackService.openModal(
            organizationId = organizationId,
            installationId = installationId,
            triggerId = triggerId,
            view = slackIncidentDeclarationView(contextText),
        )
        return if (opened) "{}" else slackEphemeral("Moneat could not open the incident declaration form.")
    }

    private suspend fun submitSlackIncident(
        root: JsonObject?,
        value: (String) -> String?,
        deliveryId: String?,
    ): String {
        val teamId = root?.get("team")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?: value("team_id")
        val slackUserId = root?.get("user")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?: value("user_id")
        val organizationId = teamId?.let(slackInstallationService::organizationIdForTeam)
        val installationId = if (organizationId != null) {
            slackInstallationService.internalInstallationIdForTeam(organizationId, requireNotNull(teamId))
        } else {
            null
        }
        val userId = if (organizationId != null && installationId != null && !slackUserId.isNullOrBlank()) {
            transaction {
                SlackUserMappings
                    .selectAll()
                    .where {
                        (SlackUserMappings.slackInstallationId eq installationId) and
                            (SlackUserMappings.slackUserId eq slackUserId) and
                            (SlackUserMappings.slackTeamId eq teamId)
                    }
                    .firstOrNull()
                    ?.get(SlackUserMappings.userId)
            }
        } else {
            null
        }
        if (organizationId == null || userId == null) {
            return slackEphemeral("Link your Slack identity in Moneat before declaring an incident.")
        }
        val state = root?.get("view")?.jsonObject?.get("state")?.jsonObject?.get("values")?.jsonObject
        val title = slackViewValue(state, "title")?.trim().orEmpty()
        val description = slackViewValue(state, "description")?.trim()?.takeIf(String::isNotEmpty)
        val severity = slackViewValue(state, "severity")?.trim()?.takeIf(String::isNotEmpty) ?: "SEV-3"
        if (title.isBlank()) return slackViewErrors(mapOf("title" to "Incident title is required."))
        val resourceId = declareIncident(
            OnCallIncidentDeclaration(
                organizationId = organizationId,
                userId = userId,
                alertId = null,
                title = title,
                description = description,
                severity = severity,
                commandKey = "slack:${deliveryId ?: Uuid.random()}",
                origin = "SLACK",
            ),
        )
        logger.info { "Declared incident $resourceId from Slack for organization $organizationId" }
        return buildJsonObject { put("response_action", "clear") }.toString()
    }

    override fun getIncident(
        incidentId: Int,
        userId: Int,
    ): IncidentInfo? {
        val incident = onCallIncidentService.getIncident(incidentId) ?: return null
        val organizationId = onCallIncidentService.getIncidentOrganizationId(incidentId) ?: return null
        return IncidentInfo(
            id = incidentId,
            organizationId = organizationId,
            title = incident.title,
            status = incident.status,
        )
    }

    override fun acknowledgeIncident(
        incidentId: Int,
        userId: Int,
    ): Boolean {
        val incident = onCallIncidentService.getIncident(incidentId) ?: return false
        return incident.alerts.fold(false) { acknowledged, alert ->
            if (alert.status == "TRIGGERED") {
                onCallAlertService.acknowledge(alert.internalId, userId) || acknowledged
            } else {
                acknowledged
            }
        }
    }

    override fun getAlert(
        alertId: Int,
        userId: Int,
    ): IncidentInfo? {
        val alert = onCallAlertService.getAlert(alertId, userId) ?: return null
        return IncidentInfo(
            id = alert.internalId,
            organizationId = alert.organizationId,
            title = alert.title,
            status = alert.status,
        )
    }

    override fun acknowledgeAlert(
        alertId: Int,
        userId: Int,
    ): Boolean = onCallAlertService.acknowledge(alertId, userId)
}

private fun slackEphemeral(message: String): String =
    buildJsonObject {
        put("response_type", "ephemeral")
        put("text", message)
    }.toString()

internal fun slackViewErrors(errors: Map<String, String>): String =
    buildJsonObject {
        put("response_action", "errors")
        putJsonObject("errors") {
            errors.forEach { (blockId, message) -> put(blockId, message) }
        }
    }.toString()

internal fun slackViewValue(values: JsonObject?, blockId: String): String? {
    val block = values?.get(blockId)?.jsonObject ?: return null
    val action = block.values.firstOrNull()?.jsonObject ?: return null
    return action["value"]?.jsonPrimitive?.contentOrNull
        ?: action["selected_option"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
}

internal fun slackIncidentDeclarationView(prefill: String?): JsonObject =
    buildJsonObject {
        put("type", "modal")
        put("callback_id", "moneat_incident_declaration")
        putJsonObject("title") {
            put("type", "plain_text")
            put("text", "Declare incident")
        }
        putJsonObject("submit") {
            put("type", "plain_text")
            put("text", "Declare")
        }
        putJsonArray("blocks") {
            addJsonObject {
                put("type", "input")
                put("block_id", "title")
                putJsonObject("label") {
                    put("type", "plain_text")
                    put("text", "Title")
                }
                putJsonObject("element") {
                    put("type", "plain_text_input")
                    put("action_id", "value")
                    prefill?.trim()?.takeIf(String::isNotEmpty)
                        ?.let { put("initial_value", it.take(SLACK_TITLE_PREFILL_MAX_CHARS)) }
                }
            }
            addJsonObject {
                put("type", "input")
                put("block_id", "description")
                putJsonObject("label") {
                    put("type", "plain_text")
                    put("text", "Description")
                }
                putJsonObject("element") {
                    put("type", "plain_text_input")
                    put("action_id", "value")
                    put("multiline", true)
                    put("optional", true)
                }
            }
            addJsonObject {
                put("type", "input")
                put("block_id", "severity")
                putJsonObject("label") {
                    put("type", "plain_text")
                    put("text", "Severity")
                }
                putJsonObject("element") {
                    put("type", "static_select")
                    put("action_id", "value")
                    putJsonObject("initial_option") {
                        put("text", buildJsonObject {
                            put("type", "plain_text")
                            put("text", "SEV-3")
                        })
                        put("value", "SEV-3")
                    }
                    putJsonArray("options") {
                        listOf("SEV-1", "SEV-2", "SEV-3", "SEV-4").forEach { severity ->
                            addJsonObject {
                                putJsonObject("text") {
                                    put("type", "plain_text")
                                    put("text", severity)
                                }
                                put("value", severity)
                            }
                        }
                    }
                }
            }
        }
    }
