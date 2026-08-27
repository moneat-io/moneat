// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.alerts.services.AlertFanoutContext
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.slack.IncidentSlackChannelState
import com.moneat.enterprise.incidents.slack.NativeIncidentSlackChannels
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.notifications.services.SlackOutboundDeliveryService
import com.moneat.notifications.services.SlackOutboundEnqueueRequest
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.SlackOutboundDeliveries
import com.moneat.shared.models.SlackOutboundOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

private data class SlackCardDestination(
    val teamId: String?,
    val channelId: String,
)

/** Publishes alert state to configured Slack channels without evaluating routes or paging. */
class AlertRouteSlackCardService(
    private val outboundDeliveryService: SlackOutboundDeliveryService = SlackOutboundDeliveryService(),
    private val enqueue: ((SlackOutboundEnqueueRequest) -> String)? = null,
) {
    private val logger = LoggerFactory.getLogger(AlertRouteSlackCardService::class.java)
    private val json = Json { encodeDefaults = true }

    fun publish(context: AlertFanoutContext, outcome: com.moneat.alerts.services.AlertRouteExecutionOutcome) {
        if (outcome.state != com.moneat.alerts.services.AlertRouteExecutionState.MATCHED) return
        val episode = context.episode ?: return
        val groupId = outcome.groupId ?: return
        val destinations = destinations(context.event.organizationId, outcome.incidentId)
        destinations.forEach { destination ->
            if (context.deliverySilenced) return@forEach
            val version = cardVersion(context)
            val idempotencyKey =
                "alert-card:${episode.resourceId}:$groupId:${destination.teamId}:${destination.channelId}"
            val providerMessageTs = providerMessageTs(context.event.organizationId, idempotencyKey)
            val request = SlackOutboundEnqueueRequest(
                organizationId = context.event.organizationId,
                teamId = destination.teamId,
                channelId = destination.channelId,
                operation = if (providerMessageTs == null) {
                    SlackOutboundOperation.MESSAGE
                } else {
                    SlackOutboundOperation.MESSAGE_UPDATE
                },
                idempotencyKey = idempotencyKey,
                payload = cardPayload(context, outcome, destination.channelId, providerMessageTs),
                desiredVersion = version,
            )
            try {
                if (enqueue != null) {
                    enqueue.invoke(request)
                } else {
                    outboundDeliveryService.enqueueAndWake(request)
                }
            } catch (error: Exception) {
                logger.warn("Unable to enqueue alert Slack card for group {}", groupId, error)
            }
        }
    }

    internal fun cardPayload(
        context: AlertFanoutContext,
        outcome: com.moneat.alerts.services.AlertRouteExecutionOutcome,
        channelId: String,
        providerMessageTs: String? = null,
    ): String {
        val event = context.event
        val episode = requireNotNull(context.episode)
        val incident = outcome.incidentId?.let { incidentSnapshot(event.organizationId, it) }
        val incidentState = incident?.second ?: "Not declared"
        val owner = event.metadata["team"]?.toString()?.trim('"')
            ?: event.metadata["service_name"]?.toString()?.trim('"')
            ?: "Unassigned"
        val fallback = "${event.priority.wire} alert: ${event.title}"
        return json.encodeToString(
            buildJsonObject {
                put("channel", channelId)
                providerMessageTs?.let { put("ts", it) }
                put("text", fallback)
                putJsonArray("blocks") {
                    addJsonObject {
                        put("type", "header")
                        putJsonObject("text") {
                            put("type", "plain_text")
                            put("text", "${priorityEmoji(event.priority.wire)} ${event.title.take(MAX_TITLE_LENGTH)}")
                            put("emoji", true)
                        }
                    }
                    addJsonObject {
                        put("type", "section")
                        putJsonObject("text") {
                            put("type", "mrkdwn")
                            put(
                                "text",
                                "*Source:* ${event.source.name}\n*Severity:* ${event.priority.wire}" +
                                    "\n*Owner:* $owner\n*Alert state:* ${event.status.name}" +
                                    "\n*Incident state:* $incidentState",
                            )
                        }
                    }
                    addJsonObject {
                        put("type", "section")
                        putJsonObject("text") {
                            put("type", "mrkdwn")
                            put(
                                "text",
                                "*Deduplication:* `${event.deduplicationKey.take(MAX_FIELD_LENGTH)}`\n" +
                                    "*Episode:* `${episode.episodeKey.take(MAX_FIELD_LENGTH)}`",
                            )
                        }
                    }
                    addJsonObject {
                        put("type", "context")
                        putJsonArray("elements") {
                            addJsonObject {
                                put("type", "mrkdwn")
                                put(
                                    "text",
                                    "Alert group `${outcome.groupId}` · route `${outcome.matchedRouteId}` " +
                                        "(revision ${outcome.matchedRouteRevision ?: 0})",
                                )
                            }
                        }
                    }
                    addJsonObject {
                        put("type", "actions")
                        putJsonArray("elements") {
                            add(
                                action(
                                    "declare_incident",
                                    "Declare incident",
                                    "primary",
                                    episode.resourceId.toString(),
                                ),
                            )
                            add(action("join_incident", "Join incident", null, outcome.incidentId))
                            add(action("acknowledge_alert", "Acknowledge", null, episode.resourceId.toString()))
                            add(action("snooze_alert", "Snooze", null, episode.resourceId.toString()))
                            add(action("unavailable_alert", "Unavailable", "danger", episode.resourceId.toString()))
                            add(action("resolve_alert", "Resolve", null, episode.resourceId.toString()))
                            add(action("confirm_grouping", "Confirm grouping", null, outcome.groupId))
                            add(action("unrelated_alert", "Unrelated", "danger", outcome.groupId))
                            add(action("merge_alert_group", "Merge", null, outcome.groupId))
                        }
                    }
                    addJsonObject {
                        put("type", "actions")
                        putJsonArray("elements") {
                            add(linkButton("Open source", event.moneatUrl))
                            add(linkButton("View details", event.moneatUrl))
                        }
                    }
                }
            },
        )
    }

    private fun providerMessageTs(organizationId: Int, idempotencyKey: String): String? = transaction {
        SlackOutboundDeliveries.selectAll().where {
            (SlackOutboundDeliveries.organizationId eq organizationId) and
                (SlackOutboundDeliveries.idempotencyKey eq idempotencyKey)
        }.singleOrNull()?.get(SlackOutboundDeliveries.providerMessageTs)
    }

    private fun cardVersion(context: AlertFanoutContext): Int {
        val sequence = context.episodeDecision?.notificationSequence ?: context.episode?.episodeSeq ?: 0
        val terminalOffset = if (context.event.status == com.moneat.alerts.models.AlertStatus.RESOLVED) 1 else 0
        return (sequence * 2 + terminalOffset).coerceAtLeast(1)
    }

    private fun destinations(
        organizationId: Int,
        incidentResourceId: String?,
    ): List<SlackCardDestination> = transaction {
        val incident = incidentResourceId?.let { resourceId ->
            OnCallIncidents.selectAll().where {
                (OnCallIncidents.organizationId eq organizationId) and
                    (OnCallIncidents.resourceId eq Uuid.parse(resourceId))
            }.singleOrNull()
        }
        if (incident != null && incident[OnCallIncidents.visibility] == "PRIVATE") {
            return@transaction NativeIncidentSlackChannels.selectAll().where {
                (NativeIncidentSlackChannels.organizationId eq organizationId) and
                    (NativeIncidentSlackChannels.incidentId eq incident[OnCallIncidents.id].value) and
                    (NativeIncidentSlackChannels.state eq IncidentSlackChannelState.ACTIVE.wire) and
                    (NativeIncidentSlackChannels.isPrivate eq true) and
                    NativeIncidentSlackChannels.channelId.isNotNull()
            }.mapNotNull { row ->
                row[NativeIncidentSlackChannels.channelId]?.let { channelId ->
                    SlackCardDestination(row[NativeIncidentSlackChannels.teamId], channelId)
                }
            }
        }
        OrganizationIntegrations.selectAll().where {
            (OrganizationIntegrations.organization_id eq organizationId) and
                (OrganizationIntegrations.integration_type eq "slack") and
                (OrganizationIntegrations.enabled eq true) and
                OrganizationIntegrations.channel_id.isNotNull()
        }.mapNotNull { row ->
            row[OrganizationIntegrations.channel_id]?.let { channelId ->
                SlackCardDestination(row[OrganizationIntegrations.team_id], channelId)
            }
        }
    }

    private fun incidentSnapshot(organizationId: Int, resourceId: String): Pair<String, String>? = transaction {
        OnCallIncidents.selectAll().where {
            (OnCallIncidents.organizationId eq organizationId) and
                (OnCallIncidents.resourceId eq Uuid.parse(resourceId))
        }.singleOrNull()?.let { row ->
            row[OnCallIncidents.title] to
                (NativeIncidentStatus.fromWire(row[OnCallIncidents.status])?.wire ?: "UNKNOWN")
        }
    }

    private fun action(
        actionId: String,
        label: String,
        style: String?,
        value: String?,
    ): JsonObject = buildJsonObject {
            put("type", "button")
            put("action_id", actionId)
            put("text", buildJsonObject {
                put("type", "plain_text")
                put("text", label)
            })
            style?.let { put("style", it) }
            value?.let { put("value", it) }
    }

    private fun linkButton(label: String, url: String): JsonObject = buildJsonObject {
            put("type", "button")
            put("text", buildJsonObject {
                put("type", "plain_text")
                put("text", label)
            })
            put("url", url)
    }

    private fun priorityEmoji(priority: String): String = when (priority) {
        "P0" -> "🚨"
        "P1" -> "🔴"
        "P2" -> "🟠"
        else -> "⚠️"
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 120
        const val MAX_FIELD_LENGTH = 180
    }
}
