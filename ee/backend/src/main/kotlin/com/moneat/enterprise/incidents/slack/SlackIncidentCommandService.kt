// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.slack

import com.moneat.enterprise.incidents.authorization.SlackIncidentAccessRequest
import com.moneat.enterprise.incidents.authorization.SlackIncidentAccessStatus
import com.moneat.enterprise.incidents.authorization.SlackIncidentAction
import com.moneat.enterprise.incidents.authorization.SlackIncidentAuthorizationService
import com.moneat.enterprise.incidents.announcements.IncidentAnnouncementNudgeService
import com.moneat.enterprise.incidents.commands.AcceptIncidentCommand
import com.moneat.enterprise.incidents.commands.AddIncidentActionCommand
import com.moneat.enterprise.incidents.commands.AddIncidentFollowUpCommand
import com.moneat.enterprise.incidents.commands.AddIncidentTimelineEventCommand
import com.moneat.enterprise.incidents.commands.CancelIncidentCommand
import com.moneat.enterprise.incidents.commands.DeclineIncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandException
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.commands.LeaveIncidentCommand
import com.moneat.enterprise.incidents.commands.ReopenIncidentCommand
import com.moneat.enterprise.incidents.commands.ResolveIncidentCommand
import com.moneat.enterprise.incidents.commands.SetIncidentParticipationCommand
import com.moneat.enterprise.incidents.commands.UpdateIncidentCommand
import com.moneat.enterprise.incidents.models.IncidentParticipationType
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import com.moneat.enterprise.incidents.followups.IncidentFollowUpPriority
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.notifications.services.SlackIdentityRequest
import com.moneat.notifications.services.SlackIdentityResolution
import com.moneat.notifications.services.SlackIdentityResolver
import com.moneat.notifications.services.SlackInstallationService
import com.moneat.notifications.services.SlackService
import com.moneat.shared.services.toUuidOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val INCIDENT_ACTION_PREFIX = "incident_"
private const val UPDATE_CALLBACK_ID = "moneat_incident_update"
private const val ACTION_CALLBACK_ID = "moneat_incident_action"
private const val TIMELINE_CALLBACK_ID = "moneat_incident_timeline"
private const val FOLLOW_UP_CALLBACK_ID = "moneat_incident_follow_up"
private const val DECISION_CALLBACK_ID = "moneat_incident_decision"
private const val UNAUTHORIZED_RESPONSE = "You are not authorized to respond to this incident in Slack."
private const val MAX_TEXT_LENGTH = 2_000
private const val MAX_FOLLOW_UP_TITLE_LENGTH = 255
private const val MODAL_TITLE_MAX_LENGTH = 24
private const val MODAL_LABEL_MAX_LENGTH = 75
private const val NUDGE_DISMISS_ACTION = "nudge_dismiss"

private data class IncidentContext(
    val id: Int,
    val resourceId: String,
    val title: String,
    val status: NativeIncidentStatus,
    val severity: String?,
    val visibility: NativeIncidentVisibility,
    val version: Int,
)

/**
 * Handles incident-bound Slack interactions. The adapter only translates Slack payloads; all
 * mutations are executed by [IncidentCommandService] so REST, dashboard, and Slack share policy,
 * optimistic concurrency, idempotency, timeline, and outbox behavior.
 */
class SlackIncidentCommandService(
    private val installationService: SlackInstallationService,
    private val slackService: SlackService,
    private val commandService: IncidentCommandService = IncidentCommandService(),
    private val nudgeService: IncidentAnnouncementNudgeService = IncidentAnnouncementNudgeService(),
) {
    private val identityResolver = SlackIdentityResolver()
    private val authorizationService = SlackIncidentAuthorizationService()

    fun handlesAction(actionId: String?): Boolean =
        actionId?.startsWith(INCIDENT_ACTION_PREFIX) == true && actionId != "incident_menu_declare"

    suspend fun handleBlockAction(root: JsonObject, deliveryId: String?): String? {
        val action = root["actions"]?.jsonArray?.firstOrNull()?.jsonObject ?: return response("No action was selected.")
        val actionId = action["action_id"]?.jsonPrimitive?.contentOrNull
        if (!handlesAction(actionId)) return null
        val identity = resolveIdentity(root)
        if (!identity.isMapped) return response(identity.message)
        val actionName = actionId.orEmpty().substringBefore(':').removePrefix(INCIDENT_ACTION_PREFIX)
        val resourceId = actionId.orEmpty().substringAfter(':', "").ifBlank {
            action["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        val context = context(identity, resourceId)
            ?: return staleResponse(resourceId)
        if (!authorize(identity, context, SlackIncidentAction.RESPOND)) {
            return response(UNAUTHORIZED_RESPONSE)
        }
        return handleAction(actionName, root, context, identity, deliveryId)
    }

    private suspend fun handleAction(
        actionName: String,
        root: JsonObject,
        context: IncidentContext,
        identity: SlackIdentityResolution,
        deliveryId: String?,
    ): String = if (actionName == NUDGE_DISMISS_ACTION) {
        dismissNudge(root, context, identity)
    } else {
        handleStandardAction(actionName, root, context, identity, deliveryId)
    }

    private suspend fun handleStandardAction(
        actionName: String,
        root: JsonObject,
        context: IncidentContext,
        identity: SlackIdentityResolution,
        deliveryId: String?,
    ): String = when (actionName) {
            "overview", "refresh" -> currentStateResponse(context)
            "accept" -> execute(context, "accept") {
                AcceptIncidentCommand(
                    commandKey = commandKey(deliveryId, "accept"),
                    actor = actor(identity),
                    incidentId = context.id,
                    expectedVersion = context.version,
                    severity = context.severity ?: "SEV-3",
                )
            }
            "decline" -> execute(context, "decline") {
                DeclineIncidentCommand(
                    commandKey = commandKey(deliveryId, "decline"),
                    actor = actor(identity),
                    incidentId = context.id,
                    expectedVersion = context.version,
                )
            }
            "resolve" -> execute(context, "resolve") {
                ResolveIncidentCommand(
                    commandKey = commandKey(deliveryId, "resolve"),
                    actor = actor(identity),
                    incidentId = context.id,
                    expectedVersion = context.version,
                )
            }
            "cancel" -> execute(context, "cancel") {
                CancelIncidentCommand(
                    commandKey = commandKey(deliveryId, "cancel"),
                    actor = actor(identity),
                    incidentId = context.id,
                    expectedVersion = context.version,
                )
            }
            "reopen" -> execute(context, "reopen") {
                ReopenIncidentCommand(
                    commandKey = commandKey(deliveryId, "reopen"),
                    actor = actor(identity),
                    incidentId = context.id,
                    expectedVersion = context.version,
                )
            }
            "join", "observe" -> execute(context, actionName) {
                SetIncidentParticipationCommand(
                    commandKey = commandKey(deliveryId, actionName),
                    actor = actor(identity),
                    incidentId = context.id,
                    userId = requireNotNull(identity.userId),
                    participationType = if (actionName == "join") {
                        IncidentParticipationType.PARTICIPANT
                    } else {
                        IncidentParticipationType.OBSERVER
                    },
                    expectedVersion = context.version,
                )
            }
            "leave" -> execute(context, "leave") {
                LeaveIncidentCommand(
                    commandKey = commandKey(deliveryId, "leave"),
                    actor = actor(identity),
                    incidentId = context.id,
                    userId = requireNotNull(identity.userId),
                    expectedVersion = context.version,
                )
            }
            "update", "action", "timeline", "follow_up", "decision" -> openModal(
                root = root,
                context = context,
                callbackId = callbackId(actionName),
                title = actionName.replace('_', ' ').replaceFirstChar(Char::uppercase),
            )
        "merge" -> response("Choose the incident to merge from the incident homepage.")
        else -> response("This incident action is not available.")
    }

    suspend fun handleShortcut(root: JsonObject, incidentResourceId: String?): String? {
        val callbackId = root["callback_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val actionName = submissionAction(callbackId) ?: return null
        val identity = resolveIdentity(root)
        if (!identity.isMapped) return response(identity.message)
        val resourceId = incidentResourceId ?: shortcutIncidentResourceId(root)
        val context = resourceId?.let { context(identity, it) }
            ?: return staleResponse(resourceId)
        if (!authorize(identity, context, SlackIncidentAction.RESPOND)) {
            return response(UNAUTHORIZED_RESPONSE)
        }
        return openModal(
            root = root,
            context = context,
            callbackId = callbackId,
            title = actionName.replace('_', ' ').replaceFirstChar(Char::uppercase),
        )
    }

    fun handleReaction(root: JsonObject, incidentResourceId: String?, deliveryId: String?): String? {
        val event = root["event"]?.jsonObject ?: return null
        if (event["type"]?.jsonPrimitive?.contentOrNull != "reaction_added") return null
        val reaction = event["reaction"]?.jsonPrimitive?.contentOrNull ?: return null
        val identity = resolveIdentity(root, event)
        if (!identity.isMapped) return null
        val resourceId = incidentResourceId ?: return null
        val context = context(identity, resourceId) ?: return null
        if (!authorize(identity, context, SlackIncidentAction.RESPOND)) return null
        val action = reactionAction(reaction) ?: return null
        val key = commandKey(deliveryId, "reaction-$reaction")
        try {
            when (action) {
                "action" -> commandService.execute(
                    AddIncidentActionCommand(
                        commandKey = key,
                        actor = actor(identity),
                        incidentId = context.id,
                        title = "Follow up from :$reaction: reaction",
                        expectedVersion = context.version,
                    ),
                )
                "update" -> commandService.execute(
                    UpdateIncidentCommand(
                        commandKey = key,
                        actor = actor(identity),
                        incidentId = context.id,
                        message = "Update requested from :$reaction: reaction",
                        expectedVersion = context.version,
                    ),
                )
                "timeline" -> commandService.execute(
                    AddIncidentTimelineEventCommand(
                        commandKey = key,
                        actor = actor(identity),
                        incidentId = context.id,
                        eventType = "SLACK_REACTION",
                        details = mapOf("reaction" to JsonPrimitive(reaction)),
                        expectedVersion = context.version,
                    ),
                )
            }
        } catch (_: IncidentCommandException) {
            // Slack events have no response channel. The durable delivery record provides retry
            // semantics; a later reaction or menu interaction shows the current state.
        }
        return null
    }

    fun handleSubmission(root: JsonObject, deliveryId: String?): String? {
        val view = root["view"]?.jsonObject ?: return null
        val callbackId = view["callback_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val actionName = when (callbackId) {
            UPDATE_CALLBACK_ID -> "update"
            ACTION_CALLBACK_ID -> "action"
            TIMELINE_CALLBACK_ID -> "timeline"
            FOLLOW_UP_CALLBACK_ID -> "follow_up"
            DECISION_CALLBACK_ID -> "decision"
            else -> return null
        }
        val identity = resolveIdentity(root)
        if (!identity.isMapped) return response(identity.message)
        val resourceId = view["private_metadata"]?.jsonPrimitive?.contentOrNull
        val context = resourceId?.let { context(identity, it) }
            ?: return staleResponse(resourceId)
        if (!authorize(identity, context, SlackIncidentAction.RESPOND)) {
            return response(UNAUTHORIZED_RESPONSE)
        }
        val values = view["state"]?.jsonObject?.get("values")?.jsonObject
        val text = values?.let { value(it, "text") }?.trim().orEmpty()
        return submitForm(actionName, identity, context, text, deliveryId)
    }

    private fun submitForm(
        actionName: String,
        identity: SlackIdentityResolution,
        context: IncidentContext,
        text: String,
        deliveryId: String?,
    ): String {
        if (text.isBlank()) return submissionErrors()
        if (actionName == "follow_up" && text.length > MAX_FOLLOW_UP_TITLE_LENGTH) {
            return submissionErrors("Follow-up title must be 255 characters or fewer.")
        }
        return try {
            commandService.execute(submissionCommand(actionName, identity, context, text, deliveryId))
            buildJsonObject { put("response_action", "clear") }.toString()
        } catch (error: IncidentCommandException) {
            staleResponse(context, error.message)
        }
    }

    private fun dismissNudge(
        root: JsonObject,
        context: IncidentContext,
        identity: SlackIdentityResolution,
    ): String {
        val nudgeKey = root["actions"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("value")?.jsonPrimitive?.contentOrNull
        if (nudgeKey.isNullOrBlank()) return response("That nudge is no longer active.")
        val dismissed = nudgeService.dismiss(
            organizationId = requireNotNull(identity.organizationId),
            incidentId = context.id,
            nudgeKey = nudgeKey,
            userId = requireNotNull(identity.userId),
        )
        return response(if (dismissed) "Nudge dismissed." else "That nudge is no longer active.")
    }

    private fun submissionCommand(
        actionName: String,
        identity: SlackIdentityResolution,
        context: IncidentContext,
        text: String,
        deliveryId: String?,
    ): com.moneat.enterprise.incidents.commands.IncidentCommand {
        val actor = actor(identity)
        val value = text.take(MAX_TEXT_LENGTH)
        return when (actionName) {
            "update" -> UpdateIncidentCommand(
                commandKey = commandKey(deliveryId, "update"),
                actor = actor,
                incidentId = context.id,
                message = value,
                expectedVersion = context.version,
            )
            "action" -> AddIncidentActionCommand(
                commandKey = commandKey(deliveryId, "action"),
                actor = actor,
                incidentId = context.id,
                title = value,
                expectedVersion = context.version,
            )
            "timeline" -> AddIncidentTimelineEventCommand(
                commandKey = commandKey(deliveryId, "timeline"),
                actor = actor,
                incidentId = context.id,
                eventType = "SLACK_NOTE",
                details = mapOf("note" to JsonPrimitive(value)),
                expectedVersion = context.version,
            )
            "follow_up" -> AddIncidentFollowUpCommand(
                commandKey = commandKey(deliveryId, actionName),
                actor = actor,
                incidentId = context.id,
                title = value,
                description = value,
                ownerUserId = requireNotNull(identity.userId),
                priority = IncidentFollowUpPriority.P2,
                source = com.moneat.enterprise.incidents.models.IncidentActionSource.SLACK,
                expectedVersion = context.version,
            )
            "decision" -> AddIncidentTimelineEventCommand(
                commandKey = commandKey(deliveryId, actionName),
                actor = actor,
                incidentId = context.id,
                eventType = "DECISION_RECORDED",
                details = mapOf("text" to JsonPrimitive(value)),
                expectedVersion = context.version,
            )
            else -> error("Unsupported Slack incident form")
        }
    }

    private fun submissionAction(callbackId: String): String? = when (callbackId) {
        UPDATE_CALLBACK_ID -> "update"
        ACTION_CALLBACK_ID -> "action"
        TIMELINE_CALLBACK_ID -> "timeline"
        FOLLOW_UP_CALLBACK_ID -> "follow_up"
        DECISION_CALLBACK_ID -> "decision"
        else -> null
    }

    private fun submissionErrors(message: String = "Add a value before submitting."): String = buildJsonObject {
        put("response_action", "errors")
        putJsonObject("errors") { put("text", message) }
    }.toString()

    private fun execute(
        context: IncidentContext,
        action: String,
        command: () -> com.moneat.enterprise.incidents.commands.IncidentCommand,
    ): String {
        return try {
            commandService.execute(command())
            response("Incident ${action.replace('_', ' ')} recorded.")
        } catch (error: IncidentCommandException) {
            staleResponse(context, error.message)
        }
    }

    private suspend fun openModal(
        root: JsonObject,
        context: IncidentContext,
        callbackId: String,
        title: String,
    ): String {
        val triggerId = root["trigger_id"]?.jsonPrimitive?.contentOrNull
            ?: return response("Slack did not provide a modal trigger. Open the incident homepage to continue.")
        val teamId = root["team"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?: root["team_id"]?.jsonPrimitive?.contentOrNull
            ?: return response("Slack workspace context is required.")
        val organizationId = installationService.organizationIdForTeam(teamId)
            ?: return response("This Slack workspace is not connected to Moneat.")
        val installationId = installationService.internalInstallationIdForTeam(organizationId, teamId)
            ?: return response("This Slack workspace installation is disabled.")
        val opened = slackService.openModal(
            organizationId = organizationId,
            installationId = installationId,
            triggerId = triggerId,
            view = buildJsonObject {
                put("type", "modal")
                put("callback_id", callbackId)
                put("private_metadata", context.resourceId)
                putJsonObject("title") { put("type", "plain_text"); put("text", title.take(MODAL_TITLE_MAX_LENGTH)) }
                putJsonObject("submit") { put("type", "plain_text"); put("text", "Submit") }
                putJsonArray("blocks") {
                    add(buildJsonObject {
                        put("type", "input")
                        put("block_id", "text")
                        putJsonObject("label") {
                            put("type", "plain_text")
                            put("text", title.take(MODAL_LABEL_MAX_LENGTH))
                        }
                        putJsonObject("element") {
                            put("type", "plain_text_input")
                            put("action_id", "value")
                            put("multiline", true)
                            if (callbackId == FOLLOW_UP_CALLBACK_ID) {
                                put("max_length", MAX_FOLLOW_UP_TITLE_LENGTH)
                            }
                        }
                    })
                }
            },
        )
        return if (opened) "{}" else response("Moneat could not open the incident form.")
    }

    private fun resolveIdentity(root: JsonObject, event: JsonObject? = null): SlackIdentityResolution {
        val teamId = root["team"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?: root["team_id"]?.jsonPrimitive?.contentOrNull
            ?: event?.get("team")?.jsonPrimitive?.contentOrNull
        val userId = root["user"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?: root["user_id"]?.jsonPrimitive?.contentOrNull
            ?: event?.get("user")?.jsonPrimitive?.contentOrNull
        val organizationId = teamId?.let(installationService::organizationIdForTeam)
        return identityResolver.resolve(
            SlackIdentityRequest(teamId = teamId, userId = userId, organizationId = organizationId),
        )
    }

    private fun shortcutIncidentResourceId(root: JsonObject): String? {
        val privateMetadata = (root["private_metadata"] as? JsonPrimitive)?.contentOrNull
        if (privateMetadata != null) return privateMetadata
        val message = root["message"] as? JsonObject ?: return null
        val metadata = message["metadata"] as? JsonObject ?: return null
        val eventPayload = metadata["event_payload"] as? JsonObject ?: return null
        return (eventPayload["incident_id"] as? JsonPrimitive)?.contentOrNull
    }

    private fun context(identity: SlackIdentityResolution, resourceId: String): IncidentContext? {
        val parsed = resourceId.toUuidOrNull() ?: return null
        val organizationId = identity.organizationId ?: return null
        return transaction {
            OnCallIncidents.selectAll().where {
                (OnCallIncidents.organizationId eq organizationId) and
                    (OnCallIncidents.resourceId eq parsed)
            }.singleOrNull()?.let { row ->
                val status = NativeIncidentStatus.fromWire(row[OnCallIncidents.status]) ?: return@let null
                val visibility = NativeIncidentVisibility.entries.firstOrNull {
                    it.wire == row[OnCallIncidents.visibility]
                } ?: return@let null
                IncidentContext(
                    id = row[OnCallIncidents.id].value,
                    resourceId = row[OnCallIncidents.resourceId].toString(),
                    title = row[OnCallIncidents.title],
                    status = status,
                    severity = row[OnCallIncidents.severity],
                    visibility = visibility,
                    version = row[OnCallIncidents.version],
                )
            }
        }
    }

    private fun authorize(
        identity: SlackIdentityResolution,
        context: IncidentContext,
        action: SlackIncidentAction,
    ): Boolean = authorizationService.authorize(
        SlackIncidentAccessRequest(
            identity = identity,
            organizationId = requireNotNull(identity.organizationId),
            incidentId = context.id,
            visibility = context.visibility,
            action = action,
        ),
    ).status == SlackIncidentAccessStatus.ALLOWED

    private fun actor(identity: SlackIdentityResolution): IncidentCommandActor = IncidentCommandActor(
        organizationId = requireNotNull(identity.organizationId),
        userId = requireNotNull(identity.userId),
        origin = "SLACK",
    )

    private fun commandKey(deliveryId: String?, action: String): String =
        "slack:${deliveryId ?: Uuid.random()}:$action"

    private fun callbackId(actionName: String): String = when (actionName) {
        "update" -> UPDATE_CALLBACK_ID
        "action" -> ACTION_CALLBACK_ID
        "timeline" -> TIMELINE_CALLBACK_ID
        "follow_up" -> FOLLOW_UP_CALLBACK_ID
        else -> DECISION_CALLBACK_ID
    }

    private fun reactionAction(reaction: String): String? = when (reaction) {
        "white_check_mark", "heavy_check_mark" -> "action"
        "eyes", "warning" -> "update"
        "memo", "pushpin", "round_pushpin" -> "timeline"
        else -> null
    }

    private fun value(values: JsonObject, blockId: String): String? {
        val block = values[blockId]?.jsonObject ?: return null
        val action = block.values.firstOrNull()?.jsonObject ?: return null
        return action["value"]?.jsonPrimitive?.contentOrNull
    }

    private fun currentStateResponse(context: IncidentContext): String = buildJsonObject {
        put("response_type", "ephemeral")
        put("text", "Incident ${context.status.wire.lowercase()}: ${context.title}")
        putJsonArray("blocks") {
            addJsonObject {
                put("type", "section")
                putJsonObject("text") {
                    put("type", "mrkdwn")
                    put(
                        "text",
                        "*${context.title}*\nStatus: `${context.status.wire}` · " +
                            "Severity: `${context.severity ?: "Unclassified"}`",
                    )
                }
            }
            addJsonObject {
                put("type", "actions")
                putJsonArray("elements") {
                    add(button("incident_refresh:${context.resourceId}", "Refresh"))
                    if (!context.status.terminal) add(button("incident_update:${context.resourceId}", "Add update"))
                }
            }
        }
    }.toString()

    private fun staleResponse(resourceId: String?, reason: String? = null): String = buildJsonObject {
        put("response_type", "ephemeral")
        put("text", reason ?: "That incident action is no longer current.")
        putJsonArray("blocks") {
            addJsonObject {
                put("type", "section")
                putJsonObject("text") {
                    put("type", "mrkdwn")
                    put(
                        "text",
                        "The incident changed or is no longer available. " +
                            "Refresh the incident menu to see its current state.",
                    )
                }
            }
            if (!resourceId.isNullOrBlank()) {
                addJsonObject {
                    put("type", "actions")
                    putJsonArray("elements") { add(button("incident_overview:$resourceId", "Refresh incident")) }
                }
            }
        }
    }.toString()

    private fun staleResponse(context: IncidentContext, reason: String?): String = buildJsonObject {
        put("response_type", "ephemeral")
        put("text", reason ?: "That incident action is no longer current.")
        putJsonArray("blocks") {
            addJsonObject {
                put("type", "section")
                putJsonObject("text") {
                    put("type", "mrkdwn")
                    put(
                        "text",
                        "*${context.title}* is currently `${context.status.wire}` " +
                            "(${context.severity ?: "Unclassified"}). Refresh before trying that action again.",
                    )
                }
            }
            addJsonObject {
                put("type", "actions")
                putJsonArray("elements") {
                    add(button("incident_overview:${context.resourceId}", "Refresh incident"))
                    if (!context.status.terminal) add(button("incident_update:${context.resourceId}", "Add update"))
                }
            }
        }
    }.toString()

    private fun button(actionId: String, label: String): JsonObject = buildJsonObject {
        put("type", "button")
        put("action_id", actionId)
        put("value", actionId.substringAfter(':'))
        putJsonObject("text") { put("type", "plain_text"); put("text", label) }
    }

    private fun response(message: String): String = buildJsonObject {
        put("response_type", "ephemeral")
        put("text", message)
    }.toString()
}
