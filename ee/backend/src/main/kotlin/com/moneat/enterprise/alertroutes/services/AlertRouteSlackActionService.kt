// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.alerts.services.AlertEpisodeService
import com.moneat.enterprise.alertroutes.commands.AlertGroupActor
import com.moneat.enterprise.alertroutes.commands.CreateAlertGroupTriageCommand
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupEscalations
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupMembers
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroups
import com.moneat.enterprise.incidents.authorization.SlackIncidentAccessRequest
import com.moneat.enterprise.incidents.authorization.SlackIncidentAccessStatus
import com.moneat.enterprise.incidents.authorization.SlackIncidentAction
import com.moneat.enterprise.incidents.authorization.SlackIncidentAuthorizationService
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.commands.SetIncidentParticipationCommand
import com.moneat.enterprise.incidents.models.IncidentParticipationType
import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.services.OnCallAlertService
import com.moneat.notifications.services.SlackIdentityRequest
import com.moneat.notifications.services.SlackIdentityResolution
import com.moneat.notifications.services.SlackIdentityResolver
import com.moneat.notifications.services.SlackInstallationService
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.services.toUuidOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

private data class AlertActionContext(
    val groupId: Uuid,
    val groupVersion: Int,
    val episodeId: Int,
    val episodeTitle: String,
    val incidentId: Int?,
    val onCallAlertId: Int?,
)

/** Executes Slack alert-card actions through the canonical alert and incident commands. */
class AlertRouteSlackActionService(
    private val slackInstallationService: SlackInstallationService,
    onCallAlertServiceProvider: () -> OnCallAlertService,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val identityResolver = SlackIdentityResolver()
    private val incidentAuthorization = SlackIncidentAuthorizationService()
    private val incidentCommands = IncidentCommandService()
    private val slackTimelineRecorder = SlackIncidentTimelineRecorder()
    private val alertGroupCommands = AlertGroupCommandService()
    private val alertEpisodeService = AlertEpisodeService()
    private val onCallAlertService by lazy(onCallAlertServiceProvider)

    fun handle(payload: String, deliveryId: String?): String? {
        val root = payloadRoot(payload) ?: return null
        if (root["type"]?.jsonPrimitive?.contentOrNull != "block_actions") return null
        val action = root["actions"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return response("No action was selected.")
        val actionId = action["action_id"]?.jsonPrimitive?.contentOrNull
            ?: return response("This action is unavailable.")
        val value = action["value"]?.jsonPrimitive?.contentOrNull
        val teamId = root["team"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        val slackUserId = root["user"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        val organizationId = teamId?.let(::organizationIdForTeam)
        val identity = identityResolver.resolve(
            SlackIdentityRequest(teamId = teamId, userId = slackUserId, organizationId = organizationId),
        )
        val resolvedOrganizationId = identity.organizationId
        val resolvedUserId = identity.userId
        if (!identity.isMapped || resolvedOrganizationId == null || resolvedUserId == null) {
            return response(identity.message)
        }
        val actionContext = actionContext(actionId, value, resolvedOrganizationId)
        return dispatchAction(actionId, identity, actionContext, value, deliveryId)
    }

    private fun dispatchAction(
        actionId: String,
        identity: SlackIdentityResolution,
        context: AlertActionContext?,
        value: String?,
        deliveryId: String?,
    ): String {
        val organizationId = requireNotNull(identity.organizationId)
        val userId = requireNotNull(identity.userId)
        return when (actionId) {
            "declare_incident" -> declare(organizationId, userId, context, deliveryId)
            "join_incident" -> join(organizationId, userId, identity, value, deliveryId)
            "acknowledge_alert" -> acknowledge(identity, context, deliveryId)
            "silence_alert" -> silence(identity, context, deliveryId)
            "resolve_alert" -> resolve(identity, context, deliveryId)
            "confirm_grouping" -> response("This alert is already grouped by the matched route.")
            "unrelated_alert", "merge_alert_group" ->
                response("Use the alert group view to review this grouping decision.")
            else -> response("This alert action is not supported.")
        }
    }

    private fun actionContext(actionId: String, value: String?, organizationId: Int): AlertActionContext? =
        when (actionId) {
            "declare_incident", "acknowledge_alert", "silence_alert", "resolve_alert" ->
                value?.let { findByEpisode(organizationId, it) }
            else -> null
        }

    private fun organizationIdForTeam(teamId: String): Int? = transaction {
        OrganizationIntegrations.selectAll().where {
            (OrganizationIntegrations.integration_type eq "slack") and
                (OrganizationIntegrations.team_id eq teamId) and
                (OrganizationIntegrations.enabled eq true)
        }.firstOrNull()?.get(OrganizationIntegrations.organization_id)
    }

    private fun declare(
        organizationId: Int,
        userId: Int,
        context: AlertActionContext?,
        deliveryId: String?,
    ): String {
        if (context == null) return response("The alert group is no longer available.")
        if (context.incidentId != null) return response("This alert group is already linked to an incident.")
        val result = alertGroupCommands.execute(
            CreateAlertGroupTriageCommand(
                commandKey = "slack-alert-declare:${deliveryId ?: Uuid.random()}",
                actor = AlertGroupActor(organizationId, userId, "SLACK"),
                groupId = context.groupId,
                expectedVersion = context.groupVersion,
                title = context.episodeTitle,
            ),
        )
        return response("Incident ${result.group.incidentId ?: "created"} opened in triage.")
    }

    private fun join(
        organizationId: Int,
        userId: Int,
        identity: SlackIdentityResolution,
        value: String?,
        deliveryId: String?,
    ): String {
        val resourceId = value?.toUuidOrNull() ?: return response("This incident is no longer available.")
        val incident = transaction {
            OnCallIncidents.selectAll().where {
                (OnCallIncidents.organizationId eq organizationId) and
                    (OnCallIncidents.resourceId eq resourceId)
            }.singleOrNull()
        } ?: return response("This incident is no longer available.")
        val incidentId = incident[OnCallIncidents.id].value
        val visibility = visibility(incident[OnCallIncidents.visibility])
            ?: return response("This incident has an invalid visibility.")
        if (visibility == NativeIncidentVisibility.PRIVATE) {
            val decision = incidentAuthorization.authorize(
                SlackIncidentAccessRequest(
                    identity,
                    organizationId,
                    incidentId,
                    visibility,
                    SlackIncidentAction.RESPOND,
                ),
            )
            if (decision.status != SlackIncidentAccessStatus.ALLOWED) {
                return response("You are not authorized to join this private incident.")
            }
        }
        incidentCommands.execute(
            SetIncidentParticipationCommand(
                commandKey = "slack-alert-join:${deliveryId ?: Uuid.random()}",
                actor = IncidentCommandActor(organizationId, userId, "SLACK"),
                incidentId = incidentId,
                userId = userId,
                participationType = IncidentParticipationType.PARTICIPANT,
            ),
        )
        return response("You joined the incident.")
    }

    private fun acknowledge(
        identity: SlackIdentityResolution,
        context: AlertActionContext?,
        deliveryId: String?,
    ): String {
        if (context == null || context.onCallAlertId == null) {
            return response("No page is attached to this alert group.")
        }
        if (!canRespondToIncident(identity, context)) {
            return response("You are not authorized to acknowledge this alert.")
        }
        val acknowledged = onCallAlertService.acknowledge(context.onCallAlertId, requireNotNull(identity.userId))
        if (acknowledged) recordSlackAction(identity, context, "SLACK_ALERT_ACKNOWLEDGED", deliveryId)
        return response("Alert acknowledged.")
    }

    private fun silence(
        identity: SlackIdentityResolution,
        context: AlertActionContext?,
        deliveryId: String?,
    ): String {
        if (context == null) return response("The alert episode is no longer available.")
        if (!canRespondToIncident(identity, context)) return response("You are not authorized to silence this alert.")
        val suppressed = alertEpisodeService.suppressEpisode(
            requireNotNull(identity.organizationId),
            context.episodeId,
            identity.userId,
            "Silenced from Slack",
        )
        if (suppressed != null) recordSlackAction(identity, context, "SLACK_ALERT_SILENCED", deliveryId)
        return response("Alert silenced.")
    }

    private fun resolve(
        identity: SlackIdentityResolution,
        context: AlertActionContext?,
        deliveryId: String?,
    ): String {
        if (context == null || context.onCallAlertId == null) {
            return response("No page is attached to this alert group.")
        }
        if (!canRespondToIncident(identity, context)) {
            return response("You are not authorized to resolve this alert.")
        }
        val resolved = onCallAlertService.resolve(context.onCallAlertId, requireNotNull(identity.userId))
        if (resolved) recordSlackAction(identity, context, "SLACK_ALERT_RESOLVED", deliveryId)
        return response("Alert resolved.")
    }

    private fun recordSlackAction(
        identity: SlackIdentityResolution,
        context: AlertActionContext,
        eventType: String,
        deliveryId: String?,
    ) {
        val incidentId = context.incidentId ?: return
        val organizationId = identity.organizationId ?: return
        val actorUserId = identity.userId ?: return
        val observedAt = Clock.System.now()
        slackTimelineRecorder.record(
            SlackIncidentTimelineRecord(
                organizationId = organizationId,
                incidentId = incidentId,
                actorUserId = actorUserId,
                alertEpisodeId = context.episodeId,
                onCallAlertId = context.onCallAlertId,
                eventType = eventType,
                deliveryId = deliveryId,
                occurredAt = observedAt,
            ),
        )
    }

    private fun canRespondToIncident(identity: SlackIdentityResolution, context: AlertActionContext): Boolean {
        val organizationId = requireNotNull(identity.organizationId)
        val incidentId = context.incidentId ?: return true
        val incident = transaction {
            OnCallIncidents.selectAll().where {
                OnCallIncidents.id eq EntityID(incidentId, OnCallIncidents)
            }.singleOrNull()
        } ?: return false
        val visibility = visibility(incident[OnCallIncidents.visibility]) ?: return false
        if (visibility != NativeIncidentVisibility.PRIVATE) return true
        return incidentAuthorization.authorize(
            SlackIncidentAccessRequest(
                identity = identity,
                organizationId = organizationId,
                incidentId = incidentId,
                visibility = visibility,
                action = SlackIncidentAction.RESPOND,
            ),
        ).allowed
    }

    private fun findByEpisode(organizationId: Int, resourceId: String): AlertActionContext? {
        val episodeResourceId = resourceId.toUuidOrNull() ?: return null
        return transaction {
            val episode = AlertEpisodes.selectAll().where {
                (AlertEpisodes.organizationId eq organizationId) and
                    (AlertEpisodes.resourceId eq episodeResourceId)
            }.singleOrNull() ?: return@transaction null
            val episodeId = episode[AlertEpisodes.id].value
            val member = EnterpriseAlertGroupMembers.selectAll().where {
                (EnterpriseAlertGroupMembers.organizationId eq organizationId) and
                    (EnterpriseAlertGroupMembers.alertEpisodeId eq episodeId)
            }.singleOrNull() ?: return@transaction null
            val group = EnterpriseAlertGroups.selectAll().where {
                (EnterpriseAlertGroups.organizationId eq organizationId) and
                    (EnterpriseAlertGroups.id eq member[EnterpriseAlertGroupMembers.groupId])
            }.singleOrNull() ?: return@transaction null
            val escalation = EnterpriseAlertGroupEscalations.selectAll().where {
                (EnterpriseAlertGroupEscalations.organizationId eq organizationId) and
                    (EnterpriseAlertGroupEscalations.groupId eq group[EnterpriseAlertGroups.id].value)
            }.firstOrNull()
            AlertActionContext(
                groupId = group[EnterpriseAlertGroups.resourceId],
                groupVersion = group[EnterpriseAlertGroups.version],
                episodeId = episodeId,
                episodeTitle = episode[AlertEpisodes.title].orEmpty(),
                incidentId = group[EnterpriseAlertGroups.incidentId],
                onCallAlertId = escalation?.get(EnterpriseAlertGroupEscalations.onCallAlertId),
            )
        }
    }

    private fun payloadRoot(payload: String): JsonObject? {
        val root = payload.trimStart()
        if (root.startsWith("{")) return runCatching { json.parseToJsonElement(root).jsonObject }.getOrNull()
        val encoded = io.ktor.http.parseQueryString(payload)["payload"] ?: return null
        return runCatching { json.parseToJsonElement(encoded).jsonObject }.getOrNull()
    }

    private fun visibility(value: String): NativeIncidentVisibility? =
        NativeIncidentVisibility.entries.firstOrNull { it.wire == value.uppercase() }

    private fun response(message: String): String = buildJsonObject {
        put("response_type", "ephemeral")
        put("text", message)
    }.toString()
}
