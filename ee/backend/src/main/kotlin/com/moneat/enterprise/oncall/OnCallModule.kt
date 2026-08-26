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
import com.moneat.enterprise.OnCallIncidentActionRequest
import com.moneat.enterprise.OnCallUserInfo
import com.moneat.enterprise.PriorityInfo
import com.moneat.enterprise.alertroutes.routes.alertRouteRoutes
import com.moneat.enterprise.alertroutes.routes.alertGroupRoutes
import com.moneat.enterprise.alertroutes.services.EnterpriseAlertRouteFanout
import com.moneat.enterprise.alertroutes.services.AlertRouteExecutionService
import com.moneat.enterprise.alertroutes.services.AlertRouteRecoveryWorker
import com.moneat.enterprise.alertroutes.services.AlertRouteSlackActionService
import com.moneat.enterprise.incidents.commands.DeclareIncidentCommand
import com.moneat.enterprise.incidents.commands.AddIncidentActionCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandException
import com.moneat.enterprise.incidents.events.IncidentOutboxService
import com.moneat.enterprise.incidents.events.IncidentOutboxWorker
import com.moneat.enterprise.incidents.updates.IncidentUpdateReminderWorker
import com.moneat.enterprise.incidents.events.IncidentResponseEventConsumer
import com.moneat.enterprise.incidents.events.IncidentAnnouncementEventConsumer
import com.moneat.enterprise.incidents.events.IncidentSlackChannelEventConsumer
import com.moneat.enterprise.incidents.events.WorkflowIncidentEventConsumer
import com.moneat.enterprise.incidents.response.IncidentResponseActivationService
import com.moneat.enterprise.incidents.response.IncidentResponsePager
import com.moneat.enterprise.incidents.response.IncidentResponsePageRequest
import com.moneat.enterprise.incidents.response.IncidentResponsePolicyService
import com.moneat.enterprise.incidents.config.IncidentConfigurationService
import com.moneat.enterprise.incidents.config.IncidentFormDefinition
import com.moneat.enterprise.incidents.config.IncidentFormFieldDefinition
import com.moneat.enterprise.incidents.config.ResolvedIncidentForm
import com.moneat.enterprise.incidents.models.IncidentCustomFieldValueType
import com.moneat.enterprise.incidents.models.IncidentFormStage
import com.moneat.enterprise.incidents.models.IncidentActionSource
import com.moneat.enterprise.incidents.models.NativeIncidentMode
import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import com.moneat.enterprise.incidents.authorization.SlackIncidentAccessRequest
import com.moneat.enterprise.incidents.authorization.SlackIncidentAction
import com.moneat.enterprise.incidents.authorization.SlackIncidentAuthorizationService
import com.moneat.enterprise.incidents.announcements.NativeIncidentAnnouncements
import com.moneat.enterprise.incidents.slack.IncidentSlackChannelState
import com.moneat.enterprise.incidents.slack.NativeIncidentSlackChannels
import com.moneat.enterprise.incidents.slack.SlackIncidentCommandService
import com.moneat.enterprise.oncall.models.OnCallIncidents
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
import com.moneat.notifications.services.SlackIdentityRequest
import com.moneat.notifications.services.SlackIdentityResolver
import com.moneat.shared.models.SlackOutboundDeliveries
import com.moneat.shared.models.SlackUserMappings
import com.moneat.shared.models.Users
import com.moneat.shared.models.Memberships
import com.moneat.shared.services.toUuidOrNull
import io.ktor.server.application.Application
import io.ktor.http.parseQueryString
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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
private const val SLACK_FORM_LABEL_MAX_CHARS = 75
private const val SLACK_FORM_HINT_MAX_CHARS = 200
private const val SLACK_FORM_OPTION_MAX_CHARS = 75
private const val SLACK_INCIDENT_MENU_CALLBACK_ID = "moneat_incident_menu"
private const val SLACK_INCIDENT_ACTION_CALLBACK_ID = "moneat_incident_action"
private const val SLACK_INCIDENT_ACTION_BUTTON_ID = "incident_action_add"
private const val SLACK_ACTION_METADATA_PARTS = 4
private const val SLACK_ACTION_METADATA_CHANNEL_INDEX = 2
private const val SLACK_ACTION_METADATA_MESSAGE_INDEX = 3
private const val SLACK_CONTEXT_REQUIRED = "Slack workspace and user context are required."
private val SLACK_INCIDENT_HELP_COMMANDS = setOf("help", "menu", "commands", "incident help", "incident menu")

private data class SlackIncidentSubmitter(
    val organizationId: Int,
    val userId: Int,
)

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
    private val incidentConfigurationService by lazy { IncidentConfigurationService() }
    private val slackIdentityResolver = SlackIdentityResolver()
    private val slackIncidentAuthorization = SlackIncidentAuthorizationService()
    private val pushNotificationService by lazy { PushNotificationService() }
    private val slackInstallationService by lazy { SlackInstallationService() }
    private val slackService by lazy { SlackService(installationService = slackInstallationService) }
    private val slackIncidentCommandService by lazy {
        SlackIncidentCommandService(
            installationService = slackInstallationService,
            slackService = slackService,
        )
    }
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
    private val alertRouteSlackActionService by lazy {
        AlertRouteSlackActionService(
            slackInstallationService = slackInstallationService,
            onCallAlertServiceProvider = { onCallAlertService },
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
                        IncidentAnnouncementEventConsumer(),
                    ),
                ),
            )
        }
    private val incidentOutboxWorker by incidentOutboxWorkerDelegate
    private val incidentUpdateReminderWorker = IncidentUpdateReminderWorker()
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
        incidentUpdateReminderWorker.start()
    }

    override fun stopBackgroundJobs() {
        logger.info { "Stopping on-call enterprise background jobs" }
        alertRouteRecoveryWorker.stop()
        if (escalationEngineDelegate.isInitialized()) escalationEngine.stop()
        if (slackUserGroupSyncServiceDelegate.isInitialized()) slackUserGroupSyncService.stop()
        if (onCallHandoffServiceDelegate.isInitialized()) onCallHandoffService.stop()
        if (shiftChangeNotifierDelegate.isInitialized()) shiftChangeNotifier.stop()
        if (incidentOutboxWorkerDelegate.isInitialized()) incidentOutboxWorker.stop()
        incidentUpdateReminderWorker.stop()
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
                    mode = parseIncidentMode(declaration.mode),
                    visibility = parseIncidentVisibility(declaration.visibility),
                    formDefinitionId = declaration.formDefinitionId,
                    formDefinitionSnapshot = declaration.formDefinitionSnapshot,
                    formValues = declaration.formValues,
                    onCallAlertId = declaration.alertId,
                ),
            ).id

    override suspend fun createIncidentAction(request: OnCallIncidentActionRequest): String? {
        val incidentId = onCallIncidentService.getIncidentIdByResourceId(
            request.organizationId,
            request.incidentResourceId,
        ) ?: return null
        val assigneeUserId = request.assigneeUserResourceId?.let { resourceId ->
            val parsed = resourceId.toUuidOrNull() ?: return null
            transaction {
                Users
                    .innerJoin(Memberships)
                    .selectAll()
                    .where {
                        (Users.resource_id eq parsed) and
                            (Memberships.organization_id eq request.organizationId)
                    }
                    .singleOrNull()
                    ?.get(Users.id)
            } ?: return null
        }
        val source = IncidentActionSource.entries
            .firstOrNull { it.wire == request.source }
            ?: return null
        return onCallIncidentService.executeIncidentCommand(
            AddIncidentActionCommand(
                commandKey = request.commandKey,
                actor = IncidentCommandActor(request.organizationId, request.userId, source.wire),
                incidentId = incidentId,
                title = request.description,
                description = request.description,
                assigneeUserId = assigneeUserId,
                source = source,
                slackChannelId = request.slackChannelId,
                slackMessageTs = request.slackMessageTs,
            ),
        ).actionResourceId
    }

    override suspend fun handleSlackInbound(
        requestType: String,
        payload: String,
        deliveryId: String?,
    ): String? {
        val form = parseQueryString(payload)
        val root = form["payload"]?.let { Json.parseToJsonElement(it).jsonObject }
            ?: runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
        val value: (String) -> String? = { key ->
            root?.get(key)?.jsonPrimitive?.contentOrNull ?: form[key]
        }
        return when {
            requestType == "interactions" -> handleSlackInteraction(root, value, payload, deliveryId)
            requestType == "events" -> handleSlackEvent(root, value, deliveryId)
            requestType == "mentions" -> null
            requestType == "shortcuts" -> handleSlackShortcut(root, value)
            else -> slackCommandResponse(requestType, root, value) ?: openSlackIncident(root, value)
        }
    }

    private suspend fun slackCommandResponse(
        requestType: String,
        root: JsonObject?,
        value: (String) -> String?,
    ): String? {
        if (requestType != "commands" && requestType != "shortcuts") return null
        incidentResourceIdForSlackChannel(root, value)?.let { return slackIncidentCommandMenu(it) }
        if (requestType == "commands" && value("text")?.trim()?.lowercase() in SLACK_INCIDENT_HELP_COMMANDS) {
            return slackIncidentCommandMenu()
        }
        return null
    }

    private fun handleSlackEvent(
        root: JsonObject?,
        value: (String) -> String?,
        deliveryId: String?,
    ): String? {
        if (root == null) return null
        slackIncidentCommandService.handleReaction(
            root = root,
            incidentResourceId = incidentResourceIdForSlackChannel(root, value),
            deliveryId = deliveryId,
        )
        return null
    }

    private fun incidentResourceIdForSlackChannel(
        root: JsonObject?,
        value: (String) -> String?,
    ): String? {
        val event = root?.get("event")?.jsonObject
        val channelId = slackChannelId(root, event, value)
        if (channelId.isNullOrBlank()) return null
        val submitter = resolveSlackSubmitter(root, value, slackInstallationService) ?: return null
        val teamId = root?.get("team")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?: value("team_id")
            ?: event?.get("team")?.jsonPrimitive?.contentOrNull
            ?: return null
        return transaction {
            val channel = NativeIncidentSlackChannels
                .selectAll()
                .where {
                    (NativeIncidentSlackChannels.organizationId eq submitter.organizationId) and
                        (NativeIncidentSlackChannels.teamId eq teamId) and
                        (NativeIncidentSlackChannels.channelId eq channelId) and
                        (NativeIncidentSlackChannels.state eq IncidentSlackChannelState.ACTIVE.wire)
                }
                .firstOrNull()
            val messageTs = event?.get("item")?.jsonObject?.get("ts")?.jsonPrimitive?.contentOrNull
            val announcementIncidentId = if (channel == null) {
                findAnnouncementIncidentId(submitter.organizationId, teamId, channelId, messageTs)
            } else {
                null
            }
            val incidentId = channel?.get(NativeIncidentSlackChannels.incidentId)
                ?: announcementIncidentId
                ?: return@transaction null
            OnCallIncidents
                .selectAll()
                .where {
                    (OnCallIncidents.id eq incidentId) and
                        (OnCallIncidents.organizationId eq submitter.organizationId)
                }
                .firstOrNull()
                ?.get(OnCallIncidents.resourceId)
                ?.toString()
        }
    }

    private fun slackChannelId(
        root: JsonObject?,
        event: JsonObject?,
        value: (String) -> String?,
    ): String? {
        value("channel_id")?.let { return it }
        val rootChannel = root?.get("channel") as? JsonObject
        (rootChannel?.get("id") as? JsonPrimitive)?.contentOrNull?.let { return it }
        (event?.get("channel") as? JsonPrimitive)?.contentOrNull?.let { return it }
        val item = event?.get("item") as? JsonObject
        return (item?.get("channel") as? JsonPrimitive)?.contentOrNull
    }

    private fun findAnnouncementIncidentId(
        organizationId: Int,
        teamId: String,
        channelId: String,
        messageTs: String?,
    ): Int? {
        if (messageTs.isNullOrBlank()) return null
        val deliveryResourceId = SlackOutboundDeliveries
            .selectAll()
            .where {
                (SlackOutboundDeliveries.organizationId eq organizationId) and
                    (SlackOutboundDeliveries.teamId eq teamId) and
                    (SlackOutboundDeliveries.channelId eq channelId) and
                    (SlackOutboundDeliveries.providerMessageTs eq messageTs)
            }
            .firstOrNull()
            ?.get(SlackOutboundDeliveries.resourceId)
        return NativeIncidentAnnouncements
            .selectAll()
            .where {
                (NativeIncidentAnnouncements.organizationId eq organizationId) and
                    (NativeIncidentAnnouncements.teamId eq teamId) and
                    (NativeIncidentAnnouncements.channelId eq channelId)
            }
            .firstOrNull { row ->
                row[NativeIncidentAnnouncements.providerMessageTs] == messageTs ||
                    (deliveryResourceId != null &&
                        row[NativeIncidentAnnouncements.deliveryResourceId] == deliveryResourceId)
            }
            ?.get(NativeIncidentAnnouncements.incidentId)
    }

    private suspend fun handleSlackInteraction(
        root: JsonObject?,
        value: (String) -> String?,
        payload: String,
        deliveryId: String?,
    ): String? {
        if (root == null) return slackEphemeral(SLACK_CONTEXT_REQUIRED)
        return when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "block_actions" -> {
                val action = root["actions"]?.jsonArray?.firstOrNull()?.jsonObject
            val actionId = action?.get("action_id")?.jsonPrimitive?.contentOrNull
            val actionValue = action?.get("value")?.jsonPrimitive?.contentOrNull
            when (actionId) {
                "incident_menu_declare" -> openSlackIncident(root, value)
                SLACK_INCIDENT_ACTION_BUTTON_ID ->
                    openSlackIncidentAction(root, value, actionValue, IncidentActionSource.MODAL)
                else -> slackIncidentCommandService.handleBlockAction(root, deliveryId)
                    ?: alertRouteSlackActionService.handle(payload, deliveryId)
            }
            }
            "view_submission" -> {
                val callbackId = root["view"]?.jsonObject?.get("callback_id")?.jsonPrimitive?.contentOrNull
                when {
                    callbackId == SLACK_INCIDENT_ACTION_CALLBACK_ID ->
                        submitSlackIncidentAction(root, value, deliveryId)
                    else -> slackIncidentCommandService.handleSubmission(root, deliveryId)
                        ?: submitSlackIncident(root, value, deliveryId)
                }
            }
            else -> openSlackIncident(root, value)
        }
    }

    private suspend fun handleSlackShortcut(
        root: JsonObject?,
        value: (String) -> String?,
    ): String? {
        if (root == null) return slackEphemeral(SLACK_CONTEXT_REQUIRED)
        return when (value("callback_id")) {
            SLACK_INCIDENT_MENU_CALLBACK_ID -> slackIncidentCommandMenu()
            SLACK_INCIDENT_ACTION_CALLBACK_ID ->
                openSlackIncidentAction(root, value, null, IncidentActionSource.MESSAGE_SHORTCUT)
            else -> slackIncidentCommandService.handleShortcut(root, incidentResourceIdForSlackChannel(root, value))
                ?: openSlackIncident(root, value)
        }
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
            view = slackIncidentDeclarationView(
                contextText,
                incidentConfigurationService.listForms(organizationId, IncidentFormStage.DECLARATION)
                    .firstOrNull { it.incidentTypeId == null },
            ),
        )
        return if (opened) "{}" else slackEphemeral("Moneat could not open the incident declaration form.")
    }

    private suspend fun openSlackIncidentAction(
        root: JsonObject?,
        value: (String) -> String?,
        requestedIncidentResourceId: String?,
        source: IncidentActionSource,
    ): String {
        val submitter = resolveSlackSubmitter(root, value, slackInstallationService)
            ?: return slackEphemeral("Link your Slack identity in Moneat before adding incident actions.")
        val triggerId = value("trigger_id") ?: root?.get("trigger_id")?.jsonPrimitive?.contentOrNull
        if (triggerId.isNullOrBlank()) return slackEphemeral("Slack did not provide a modal trigger.")
        val channelId = root?.get("channel")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?: root?.get("message")?.jsonObject?.get("channel")?.jsonPrimitive?.contentOrNull
            ?: value("channel_id")
        val incidentResourceId = requestedIncidentResourceId
            ?: channelId?.let { incidentResourceIdForSlackChannel(submitter.organizationId, it) }
        if (incidentResourceId.isNullOrBlank()) {
            return slackEphemeral("Open this shortcut from an incident channel or provide an incident reference.")
        }
        val incidentId = onCallIncidentService.getIncidentIdByResourceId(
            submitter.organizationId,
            incidentResourceId,
        ) ?: return slackEphemeral("Open this shortcut from an incident channel or provide an incident reference.")
        authorizeSlackIncidentAction(root, value, submitter.organizationId, incidentId)?.let { message ->
            return slackEphemeral(message)
        }
        val messageTs = root?.get("message")?.jsonObject?.get("ts")?.jsonPrimitive?.contentOrNull
        val metadata = listOf(source.wire, incidentResourceId, channelId.orEmpty(), messageTs.orEmpty())
            .joinToString("|")
        val installationId = slackInstallationService.internalInstallationIdForTeam(
            submitter.organizationId,
            requireNotNull(
                root?.get("team")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull ?: value("team_id"),
            ),
        ) ?: return slackEphemeral("This Slack workspace installation is disabled.")
        val opened = slackService.openModal(
            organizationId = submitter.organizationId,
            installationId = installationId,
            triggerId = triggerId,
            view = slackIncidentActionView(metadata),
        )
        return if (opened) "{}" else slackEphemeral("Moneat could not open the incident action form.")
    }

    private suspend fun submitSlackIncidentAction(
        root: JsonObject?,
        value: (String) -> String?,
        deliveryId: String?,
    ): String {
        val submitter = resolveSlackSubmitter(root, value, slackInstallationService)
            ?: return slackEphemeral("Link your Slack identity in Moneat before adding incident actions.")
        val state = root?.get("view")?.jsonObject?.get("state")?.jsonObject?.get("values")?.jsonObject
        val description = slackViewValue(state, "description")?.trim().orEmpty()
        if (description.isBlank()) return slackViewErrors(mapOf("description" to "Action description is required."))
        val metadata = root?.get("view")?.jsonObject?.get("private_metadata")?.jsonPrimitive?.contentOrNull
            ?: return slackEphemeral("The incident action context has expired.")
        val metadataParts = metadata.split("|", limit = SLACK_ACTION_METADATA_PARTS)
        val source = IncidentActionSource.entries.firstOrNull { it.wire == metadataParts.firstOrNull() }
            ?: return slackEphemeral("The incident action context is invalid.")
        val incidentResourceId = metadataParts.getOrNull(1)
            ?: return slackEphemeral("The incident action context is invalid.")
        val incidentId = onCallIncidentService.getIncidentIdByResourceId(submitter.organizationId, incidentResourceId)
            ?: return slackEphemeral("This incident is no longer available.")
        authorizeSlackIncidentAction(root, value, submitter.organizationId, incidentId)?.let { message ->
            return slackEphemeral(message)
        }
        val result = try {
            onCallIncidentService.executeIncidentCommand(
                AddIncidentActionCommand(
                    commandKey = "slack-incident-action:${deliveryId ?: Uuid.random()}",
                    actor = IncidentCommandActor(submitter.organizationId, submitter.userId, "SLACK"),
                    incidentId = incidentId,
                    title = description,
                    description = description,
                    source = source,
                    slackChannelId = metadataParts.getOrNull(SLACK_ACTION_METADATA_CHANNEL_INDEX)
                        ?.takeIf(String::isNotBlank),
                    slackMessageTs = metadataParts.getOrNull(SLACK_ACTION_METADATA_MESSAGE_INDEX)
                        ?.takeIf(String::isNotBlank),
                ),
            )
        } catch (error: IncidentCommandException) {
            return slackViewErrors(mapOf("description" to (error.message ?: "This action is not allowed.")))
        } catch (error: IllegalArgumentException) {
            return slackViewErrors(mapOf("description" to (error.message ?: "Check this action.")))
        }
        logger.info {
            "Added incident action ${result.actionResourceId} from Slack for " +
                "organization ${submitter.organizationId}"
        }
        return buildJsonObject { put("response_action", "clear") }.toString()
    }

    private fun incidentResourceIdForSlackChannel(organizationId: Int, channelId: String): String? = transaction {
        NativeIncidentSlackChannels
            .selectAll()
            .where {
                (NativeIncidentSlackChannels.organizationId eq organizationId) and
                    (NativeIncidentSlackChannels.channelId eq channelId)
            }
            .firstOrNull()
            ?.get(NativeIncidentSlackChannels.incidentId)
            ?.let { incidentId ->
                OnCallIncidents
                    .selectAll()
                    .where { OnCallIncidents.id eq incidentId }
                    .firstOrNull()
                    ?.get(OnCallIncidents.resourceId)
                    ?.toString()
            }
    }

    private suspend fun submitSlackIncident(
        root: JsonObject?,
        value: (String) -> String?,
        deliveryId: String?,
    ): String {
        val submitter = resolveSlackSubmitter(root, value, slackInstallationService)
        if (submitter == null) {
            return slackEphemeral("Link your Slack identity in Moneat before declaring an incident.")
        }
        val organizationId = submitter.organizationId
        val userId = submitter.userId
        val state = root?.get("view")?.jsonObject?.get("state")?.jsonObject?.get("values")?.jsonObject
        val title = slackViewValue(state, "title")?.trim().orEmpty()
        val description = slackViewValue(state, "description")?.trim()?.takeIf(String::isNotEmpty)
        val severity = slackViewValue(state, "severity")?.trim()?.takeIf(String::isNotEmpty) ?: "SEV-3"
        if (title.isBlank()) return slackViewErrors(mapOf("title" to "Incident title is required."))
        val mode = parseSlackIncidentMode(slackViewValue(state, "mode"))
            ?: return slackViewErrors(mapOf("mode" to "Choose a supported incident mode."))
        val visibility = parseSlackIncidentVisibility(slackViewValue(state, "visibility"))
            ?: return slackViewErrors(mapOf("visibility" to "Choose a supported incident visibility."))
        val resolvedForm = try {
            resolveSlackDeclarationForm(organizationId, state, incidentConfigurationService)
        } catch (error: IllegalArgumentException) {
            val missingField = missingSlackFormField(organizationId, state, incidentConfigurationService) ?: "title"
            return slackViewErrors(mapOf(missingField to (error.message ?: "Check this value.")))
        }
        val resourceId = declareIncident(
            OnCallIncidentDeclaration(
                organizationId = organizationId,
                userId = userId,
                alertId = null,
                title = title,
                description = description,
                severity = severity,
                mode = mode.wire,
                visibility = visibility.wire,
                commandKey = "slack:${deliveryId ?: Uuid.random()}",
                origin = "SLACK",
                formDefinitionId = resolvedForm?.formDefinitionId,
                formDefinitionSnapshot = resolvedForm?.definitionSnapshot ?: emptyMap(),
                formValues = resolvedForm?.values ?: emptyMap(),
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

    private fun authorizeSlackIncidentAction(
        root: JsonObject?,
        value: (String) -> String?,
        organizationId: Int,
        incidentId: Int,
    ): String? {
        val incident = onCallIncidentService.getIncident(incidentId)
            ?: return "This incident is no longer available."
        val visibility = NativeIncidentVisibility.entries.firstOrNull { it.wire == incident.visibility }
            ?: return "This incident has an invalid visibility."
        val identity = slackIdentityResolver.resolve(
            SlackIdentityRequest(
                teamId = root?.get("team")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: value("team_id"),
                userId = root?.get("user")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: value("user_id"),
                organizationId = organizationId,
            ),
        )
        val decision = slackIncidentAuthorization.authorize(
            SlackIncidentAccessRequest(
                identity = identity,
                organizationId = organizationId,
                incidentId = incidentId,
                visibility = visibility,
                action = SlackIncidentAction.RESPOND,
            ),
        )
        return decision.message.takeUnless { decision.allowed }
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

internal fun slackIncidentCommandMenu(incidentResourceId: String? = null): String =
    buildJsonObject {
        put("response_type", "ephemeral")
        put("text", if (incidentResourceId == null) "Incident response commands" else "Incident menu")
        putJsonArray("blocks") {
            addJsonObject {
                put("type", "header")
                putJsonObject("text") {
                    put("type", "plain_text")
                    put("text", "Incident response")
                }
            }
            addJsonObject {
                put("type", "section")
                put(
                    "text",
                    if (incidentResourceId == null) {
                        "*Available actions*\n" +
                            "Overview · update · status · severity · summary · rename · roles · fields\n" +
                            "Actions · follow-ups · timeline · decision · handover · call · status page\n" +
                            "Escalation · resolve · cancel · reopen · workflow"
                    } else {
                        "*Incident menu*\n" +
                            "Incident `$incidentResourceId`\n" +
                            "Overview · update · status · severity · summary · rename · roles · fields\n" +
                            "Actions · follow-ups · timeline · decision · handover · call · status page\n" +
                            "Escalation · resolve · cancel · reopen · workflow"
                    },
                )
            }
            addJsonObject {
                put("type", "actions")
                putJsonArray("elements") {
                    addJsonObject {
                        put("type", "button")
                        put("action_id", "incident_menu_declare")
                        putJsonObject("text") {
                            put("type", "plain_text")
                            put("text", "Declare incident")
                        }
                        put("style", "primary")
                    }
                }
            }
            incidentResourceId?.let { resourceId ->
                listOf(
                    listOf("Overview" to "overview", "Update" to "update", "Actions" to "action"),
                    listOf("Timeline" to "timeline", "Join" to "join", "Observe" to "observe", "Leave" to "leave"),
                    listOf("Accept" to "accept", "Decline" to "decline", "Resolve" to "resolve", "Cancel" to "cancel"),
                    listOf("Reopen" to "reopen", "Refresh" to "refresh"),
                ).forEach { group ->
                    addJsonObject {
                        put("type", "actions")
                        putJsonArray("elements") {
                            group.forEach { (label, action) ->
                                addJsonObject {
                                    put("type", "button")
                                    put("action_id", "incident_$action:$resourceId")
                                    put("value", resourceId)
                                    putJsonObject("text") {
                                        put("type", "plain_text")
                                        put("text", label)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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

private fun parseIncidentMode(value: String): NativeIncidentMode =
    NativeIncidentMode.entries.firstOrNull { it.wire.equals(value.trim(), ignoreCase = true) }
        ?: throw IllegalArgumentException("Unsupported incident mode")

private fun parseIncidentVisibility(value: String): NativeIncidentVisibility =
    NativeIncidentVisibility.entries.firstOrNull { it.wire.equals(value.trim(), ignoreCase = true) }
        ?: throw IllegalArgumentException("Unsupported incident visibility")

private fun parseSlackIncidentMode(value: String?): NativeIncidentMode? =
    if (value.isNullOrBlank()) {
        NativeIncidentMode.LIVE
    } else {
        NativeIncidentMode.entries.firstOrNull { it.wire.equals(value.trim(), ignoreCase = true) }
    }

private fun parseSlackIncidentVisibility(value: String?): NativeIncidentVisibility? =
    if (value.isNullOrBlank()) {
        NativeIncidentVisibility.ORGANIZATION
    } else {
        NativeIncidentVisibility.entries.firstOrNull { it.wire.equals(value.trim(), ignoreCase = true) }
    }

private fun resolveSlackSubmitter(
    root: JsonObject?,
    value: (String) -> String?,
    installationService: SlackInstallationService,
): SlackIncidentSubmitter? {
    val teamId = root?.get("team")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        ?: value("team_id")
    val slackUserId = root?.get("user")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        ?: value("user_id")
    val organizationId = teamId?.let(installationService::organizationIdForTeam) ?: return null
    val installationId = installationService.internalInstallationIdForTeam(organizationId, requireNotNull(teamId))
        ?: return null
    if (slackUserId.isNullOrBlank()) return null
    val userId = transaction {
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
    return userId?.let { SlackIncidentSubmitter(organizationId, it) }
}

internal fun slackIncidentDeclarationView(
    prefill: String?,
    declarationForm: IncidentFormDefinition? = null,
): JsonObject =
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
            addJsonObject {
                put("type", "input")
                put("block_id", "mode")
                putJsonObject("label") {
                    put("type", "plain_text")
                    put("text", "Incident mode")
                }
                putJsonObject("hint") {
                    put("type", "plain_text")
                    put(
                        "text",
                        "Live incidents can page responders. Test and retrospective incidents stay channelless " +
                            "unless your response policy enables those actions.",
                    )
                }
                putJsonObject("element") {
                    put("type", "static_select")
                    put("action_id", "value")
                    putJsonObject("initial_option") {
                        putJsonObject("text") {
                            put("type", "plain_text")
                            put("text", "Live")
                        }
                        put("value", NativeIncidentMode.LIVE.wire)
                    }
                    putJsonArray("options") {
                        listOf(
                            NativeIncidentMode.LIVE to "Live",
                            NativeIncidentMode.RETROSPECTIVE to "Retrospective",
                            NativeIncidentMode.TEST to "Test",
                        ).forEach { (mode, label) ->
                            addJsonObject {
                                putJsonObject("text") {
                                    put("type", "plain_text")
                                    put("text", label)
                                }
                                put("value", mode.wire)
                            }
                        }
                    }
                }
            }
            addJsonObject {
                put("type", "input")
                put("block_id", "visibility")
                putJsonObject("label") {
                    put("type", "plain_text")
                    put("text", "Visibility")
                }
                putJsonObject("element") {
                    put("type", "static_select")
                    put("action_id", "value")
                    putJsonObject("initial_option") {
                        putJsonObject("text") {
                            put("type", "plain_text")
                            put("text", "Organization")
                        }
                        put("value", NativeIncidentVisibility.ORGANIZATION.wire)
                    }
                    putJsonArray("options") {
                        listOf(
                            NativeIncidentVisibility.ORGANIZATION to "Organization",
                            NativeIncidentVisibility.PRIVATE to "Private",
                            NativeIncidentVisibility.PUBLIC to "Public",
                        ).forEach { (visibility, label) ->
                            addJsonObject {
                                putJsonObject("text") {
                                    put("type", "plain_text")
                                    put("text", label)
                                }
                                put("value", visibility.wire)
                            }
                        }
                    }
                }
            }
            declarationForm?.fields
                ?.filter(IncidentFormFieldDefinition::visible)
                ?.sortedBy(IncidentFormFieldDefinition::position)
                ?.forEach { add(slackFormFieldBlock(it)) }
        }
    }

internal fun slackIncidentActionView(privateMetadata: String): JsonObject =
    buildJsonObject {
        put("type", "modal")
        put("callback_id", SLACK_INCIDENT_ACTION_CALLBACK_ID)
        put("private_metadata", privateMetadata)
        putJsonObject("title") {
            put("type", "plain_text")
            put("text", "Add incident action")
        }
        putJsonObject("submit") {
            put("type", "plain_text")
            put("text", "Add action")
        }
        putJsonArray("blocks") {
            addJsonObject {
                put("type", "input")
                put("block_id", "description")
                putJsonObject("label") {
                    put("type", "plain_text")
                    put("text", "Action description")
                }
                putJsonObject("element") {
                    put("type", "plain_text_input")
                    put("action_id", "value")
                    put("multiline", true)
                }
            }
        }
    }

private fun slackFormFieldBlock(formField: IncidentFormFieldDefinition): JsonObject =
    buildJsonObject {
        put("type", "input")
        put("block_id", "field_${formField.field.key}")
        putJsonObject("label") {
            put("type", "plain_text")
            put("text", formField.field.name.take(SLACK_FORM_LABEL_MAX_CHARS))
        }
        formField.helpText?.takeIf(String::isNotBlank)?.let { hint ->
            putJsonObject("hint") {
                put("type", "plain_text")
                put("text", hint.take(SLACK_FORM_HINT_MAX_CHARS))
            }
        }
        put("optional", !formField.required)
        putJsonObject("element") {
            put("action_id", "value")
            when (formField.field.valueType) {
                IncidentCustomFieldValueType.SELECT -> {
                    put("type", "static_select")
                    putJsonArray("options") {
                        formField.field.options.forEach { option -> add(slackOption(option.value, option.label)) }
                    }
                    formField.defaultValue?.let { default ->
                        formField.field.options.firstOrNull { it.value == default.jsonPrimitive.contentOrNull }
                            ?.let { option -> put("initial_option", slackOption(option.value, option.label)) }
                    }
                }
                IncidentCustomFieldValueType.MULTI_SELECT -> {
                    put("type", "multi_static_select")
                    putJsonArray("options") {
                        formField.field.options.forEach { option -> add(slackOption(option.value, option.label)) }
                    }
                }
                IncidentCustomFieldValueType.NUMBER -> put("type", "number_input")
                else -> {
                    put("type", "plain_text_input")
                    if (formField.field.valueType == IncidentCustomFieldValueType.LINK) put("url_only", true)
                    formField.defaultValue?.jsonPrimitive?.contentOrNull?.let { put("initial_value", it) }
                }
            }
        }
    }

private fun slackOption(value: String, label: String): JsonObject =
    buildJsonObject {
        putJsonObject("text") {
            put("type", "plain_text")
            put("text", label.take(SLACK_FORM_LABEL_MAX_CHARS))
        }
        put("value", value.take(SLACK_FORM_OPTION_MAX_CHARS))
    }

private fun resolveSlackDeclarationForm(
    organizationId: Int,
    state: JsonObject?,
    configurationService: IncidentConfigurationService,
): ResolvedIncidentForm? {
    val declarationForm = genericSlackDeclarationForm(organizationId, configurationService) ?: return null
    return configurationService.resolveForm(
        organizationId = organizationId,
        incidentTypeResourceId = null,
        stage = IncidentFormStage.DECLARATION,
        submittedValues = slackFormValues(state, declarationForm),
    )
}

private fun missingSlackFormField(
    organizationId: Int,
    state: JsonObject?,
    configurationService: IncidentConfigurationService,
): String? {
    val declarationForm = genericSlackDeclarationForm(organizationId, configurationService) ?: return null
    val submittedValues = slackFormValues(state, declarationForm)
    return declarationForm.fields
        .firstOrNull { it.required && !submittedValues.containsKey(it.field.key) }
        ?.let { "field_${it.field.key}" }
}

private fun genericSlackDeclarationForm(
    organizationId: Int,
    configurationService: IncidentConfigurationService,
): IncidentFormDefinition? =
    configurationService.listForms(organizationId, IncidentFormStage.DECLARATION)
        .firstOrNull { it.incidentTypeId == null }

private fun slackFormValues(
    values: JsonObject?,
    form: IncidentFormDefinition,
): Map<String, kotlinx.serialization.json.JsonElement> =
    form.fields.filter(IncidentFormFieldDefinition::visible).mapNotNull { field ->
        slackViewJsonValue(values, "field_${field.field.key}", field.field.valueType)
            ?.let { field.field.key to it }
    }.toMap()

private fun slackViewJsonValue(
    values: JsonObject?,
    blockId: String,
    valueType: IncidentCustomFieldValueType,
): kotlinx.serialization.json.JsonElement? {
    val block = values?.get(blockId)?.jsonObject ?: return null
    val action = block.values.firstOrNull()?.jsonObject ?: return null
    return when (valueType) {
        IncidentCustomFieldValueType.SELECT ->
            action["selected_option"]?.jsonObject?.get("value")?.jsonPrimitive
        IncidentCustomFieldValueType.MULTI_SELECT ->
            action["selected_options"]?.jsonArray?.mapNotNull { it.jsonObject["value"]?.jsonPrimitive }
                ?.let(::JsonArray)
        else -> action["value"]?.jsonPrimitive?.takeIf { it.contentOrNull?.isNotBlank() == true }
    }
}
