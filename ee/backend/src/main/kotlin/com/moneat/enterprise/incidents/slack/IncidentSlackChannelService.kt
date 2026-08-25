// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.slack

import com.moneat.enterprise.incidents.events.NativeIncidentDomainEvent
import com.moneat.enterprise.incidents.models.NativeIncidentMode
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.notifications.services.SlackOutboundDeliveryService
import com.moneat.notifications.services.SlackOutboundEnqueueRequest
import com.moneat.shared.models.OrganizationIntegrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.Locale
import kotlin.time.Clock

/** Maintains incident channel desired state while keeping channel failures outside incident mutations. */
class IncidentSlackChannelService(
    private val outboundDeliveryService: SlackOutboundDeliveryService = SlackOutboundDeliveryService(),
    private val enqueue: ((SlackOutboundEnqueueRequest) -> String)? = null,
    private val clock: Clock = Clock.System,
) {
    private val logger = LoggerFactory.getLogger(IncidentSlackChannelService::class.java)
    private val json = Json { encodeDefaults = true }

    fun provision(event: NativeIncidentDomainEvent) {
        val incident = incident(event) ?: return
        if (!shouldHaveChannel(event.eventType, incident.mode, incident.status)) return
        val integrations = integrations(event.organizationId)
        if (integrations.isEmpty()) {
            recordFailure(event, UNCONFIGURED_TEAM, "No enabled Slack workspace is configured")
            return
        }
        integrations.forEach { integration ->
            provisionWorkspace(event, incident, integration[OrganizationIntegrations.team_id]!!)
        }
    }

    fun archive(event: NativeIncidentDomainEvent) {
        val incident = incident(event) ?: return
        val channels = transaction {
            NativeIncidentSlackChannels
                .selectAll()
                .where {
                    (NativeIncidentSlackChannels.organizationId eq event.organizationId) and
                        (NativeIncidentSlackChannels.incidentId eq incident.id)
                }
                .toList()
        }
        channels.forEach { row ->
            val channelId = row[NativeIncidentSlackChannels.channelId] ?: return@forEach
            val request = SlackOutboundEnqueueRequest(
                organizationId = event.organizationId,
                teamId = row[NativeIncidentSlackChannels.teamId],
                channelId = channelId,
                operation = com.moneat.shared.models.SlackOutboundOperation.ARCHIVE,
                idempotencyKey = "incident:${incident.resourceId}:channel:${row[NativeIncidentSlackChannels.teamId]}" +
                    ":archive:${event.aggregateVersion}",
                payload = json.encodeToString(buildJsonObject { put("channel", channelId) }),
                desiredVersion = event.aggregateVersion,
            )
            try {
                enqueue(request)
                transaction {
                    NativeIncidentSlackChannels.update({
                        NativeIncidentSlackChannels.id eq row[NativeIncidentSlackChannels.id]
                    }) {
                        it[state] = IncidentSlackChannelState.ARCHIVED.wire
                        it[archivedAt] = clock.now()
                        it[lastError] = null
                        it[updatedAt] = clock.now()
                    }
                }
            } catch (error: Exception) {
                recordFailure(event, row[NativeIncidentSlackChannels.teamId], error.message ?: "Channel archive failed")
            }
        }
    }

    private fun provisionWorkspace(
        event: NativeIncidentDomainEvent,
        incident: IncidentSnapshot,
        teamId: String,
    ) {
        val name = channelName(incident.title, incident.resourceId)
        val topic = "Incident ${incident.title} · ${homepage(incident.resourceId)}"
        val isPrivate = incident.visibility == "PRIVATE"
        val payload = json.encodeToString(
            buildJsonObject {
                put("name", name)
                put("is_private", isPrivate)
            },
        )
        val row = transaction {
            NativeIncidentSlackChannels.insertIgnore {
                it[resourceId] = kotlin.uuid.Uuid.random()
                it[organizationId] = event.organizationId
                it[incidentId] = incident.id
                it[NativeIncidentSlackChannels.teamId] = teamId
                it[NativeIncidentSlackChannels.channelName] = name
                it[state] = IncidentSlackChannelState.PROVISIONING.wire
                it[NativeIncidentSlackChannels.isPrivate] = isPrivate
                it[desiredVersion] = event.aggregateVersion
                it[NativeIncidentSlackChannels.topic] = topic
                it[bookmarks] = homepage(incident.resourceId)
                it[createdAt] = clock.now()
                it[updatedAt] = clock.now()
            }
            NativeIncidentSlackChannels.selectAll().where {
                (NativeIncidentSlackChannels.organizationId eq event.organizationId) and
                    (NativeIncidentSlackChannels.incidentId eq incident.id) and
                    (NativeIncidentSlackChannels.teamId eq teamId)
            }.single()
        }
        val state = row[NativeIncidentSlackChannels.state]
        if (state == IncidentSlackChannelState.ACTIVE.wire ||
            (state == IncidentSlackChannelState.PROVISIONING.wire &&
                row[NativeIncidentSlackChannels.deliveryResourceId] != null)
        ) {
            return
        }
        val request = SlackOutboundEnqueueRequest(
            organizationId = event.organizationId,
            teamId = teamId,
            channelId = null,
            operation = com.moneat.shared.models.SlackOutboundOperation.CHANNEL_CREATE,
            idempotencyKey = "incident:${incident.resourceId}:channel:$teamId:create",
            payload = payload,
            desiredVersion = event.aggregateVersion,
        )
        try {
            val deliveryId = enqueue(request)
            transaction {
                NativeIncidentSlackChannels.update({
                    NativeIncidentSlackChannels.id eq row[NativeIncidentSlackChannels.id]
                }) {
                    it[NativeIncidentSlackChannels.state] = IncidentSlackChannelState.PROVISIONING.wire
                    it[NativeIncidentSlackChannels.deliveryResourceId] = kotlin.uuid.Uuid.parse(deliveryId)
                    it[NativeIncidentSlackChannels.lastError] = null
                    it[NativeIncidentSlackChannels.updatedAt] = clock.now()
                }
            }
        } catch (error: Exception) {
            recordFailure(event, teamId, error.message ?: "Channel provisioning failed")
            logger.warn("Slack incident channel provisioning failed for incident {}", incident.resourceId, error)
        }
    }

    private fun incident(event: NativeIncidentDomainEvent): IncidentSnapshot? = transaction {
        OnCallIncidents.selectAll().where {
            (OnCallIncidents.organizationId eq event.organizationId) and
                (OnCallIncidents.resourceId eq incidentResourceId(event))
        }.singleOrNull()?.let { row ->
            IncidentSnapshot(
                id = row[OnCallIncidents.id].value,
                resourceId = row[OnCallIncidents.resourceId].toString(),
                title = row[OnCallIncidents.title],
                mode = row[OnCallIncidents.mode],
                status = row[OnCallIncidents.status],
                visibility = row[OnCallIncidents.visibility],
            )
        }
    }

    private fun integrations(organizationId: Int) = transaction {
        OrganizationIntegrations.selectAll().where {
            (OrganizationIntegrations.organization_id eq organizationId) and
                (OrganizationIntegrations.integration_type eq "slack") and
                (OrganizationIntegrations.enabled eq true) and
                OrganizationIntegrations.team_id.isNotNull()
        }.toList()
    }

    private fun recordFailure(event: NativeIncidentDomainEvent, teamId: String, message: String) {
        transaction {
            NativeIncidentSlackChannels.insertIgnore {
                it[resourceId] = kotlin.uuid.Uuid.random()
                it[organizationId] = event.organizationId
                it[incidentId] = OnCallIncidents.selectAll().where {
                    (OnCallIncidents.organizationId eq event.organizationId) and
                        (OnCallIncidents.resourceId eq incidentResourceId(event))
                }.single()[OnCallIncidents.id].value
                it[NativeIncidentSlackChannels.teamId] = teamId
                it[state] = IncidentSlackChannelState.FAILED.wire
                it[lastError] = message.take(MAX_ERROR_LENGTH)
                it[createdAt] = clock.now()
                it[updatedAt] = clock.now()
            }
            NativeIncidentSlackChannels.update({
                (NativeIncidentSlackChannels.organizationId eq event.organizationId) and
                    (NativeIncidentSlackChannels.incidentId eq OnCallIncidents.selectAll().where {
                        (OnCallIncidents.organizationId eq event.organizationId) and
                            (OnCallIncidents.resourceId eq incidentResourceId(event))
                    }.single()[OnCallIncidents.id].value) and
                    (NativeIncidentSlackChannels.teamId eq teamId)
            }) {
                it[state] = IncidentSlackChannelState.FAILED.wire
                it[lastError] = message.take(MAX_ERROR_LENGTH)
                it[updatedAt] = clock.now()
            }
        }
    }

    private fun shouldHaveChannel(eventType: String, mode: String, status: String): Boolean =
        mode == NativeIncidentMode.LIVE.wire &&
            ((eventType == "INCIDENT_DECLARE" &&
                status in setOf(NativeIncidentStatus.TRIAGE.wire, NativeIncidentStatus.ACTIVE.wire)) ||
                eventType == "INCIDENT_ACCEPT" || eventType == "INCIDENT_REOPEN")

    private fun channelName(title: String, resourceId: String): String {
        val slug = title.lowercase(Locale.US).replace(NON_ALPHANUMERIC, "-").trim('-').take(MAX_SLUG_LENGTH)
        return "inc-${slug.ifBlank { "incident" }}-${resourceId.take(RESOURCE_ID_SUFFIX_LENGTH)}"
    }

    private fun homepage(resourceId: String): String =
        "${com.moneat.config.EnvConfig.get("FRONTEND_URL", "https://moneat.io")}/on-call/incidents/$resourceId"

    private fun incidentResourceId(event: NativeIncidentDomainEvent): kotlin.uuid.Uuid =
        kotlin.uuid.Uuid.parse(event.payload[INCIDENT_ID]?.toString()?.trim('"') ?: "")

    private data class IncidentSnapshot(
        val id: Int,
        val resourceId: String,
        val title: String,
        val mode: String,
        val status: String,
        val visibility: String,
    )

    companion object {
        private const val INCIDENT_ID = "incidentId"
        private const val UNCONFIGURED_TEAM = "unconfigured"
        private const val MAX_ERROR_LENGTH = 1_000
        private const val MAX_SLUG_LENGTH = 56
        private const val RESOURCE_ID_SUFFIX_LENGTH = 8
        private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    }

    private fun enqueue(request: SlackOutboundEnqueueRequest): String =
        enqueue?.invoke(request) ?: outboundDeliveryService.enqueueAndWake(request)
}
