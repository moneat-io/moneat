// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.announcements

import com.moneat.enterprise.incidents.events.NativeIncidentDomainEvent
import com.moneat.enterprise.incidents.followups.NativeIncidentFollowUps
import com.moneat.enterprise.incidents.models.NativeIncidentRoleAssignments
import com.moneat.enterprise.incidents.models.NativeIncidentRoleDefinitions
import com.moneat.enterprise.incidents.response.NativeIncidentResponseActivations
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.IncidentUpdateRequestStatus
import com.moneat.enterprise.incidents.models.NativeIncidentUpdateRequests
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.config.EnvConfig
import com.moneat.notifications.services.SlackOutboundDeliveryService
import com.moneat.notifications.services.SlackOutboundEnqueueRequest
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.SlackOutboundOperation
import com.moneat.shared.models.Users
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

class IncidentAnnouncementService(
    private val outboundDeliveryService: SlackOutboundDeliveryService = SlackOutboundDeliveryService(),
    private val enqueue: ((SlackOutboundEnqueueRequest) -> String)? = null,
    private val clock: Clock = Clock.System,
    private val nudgeStateService: IncidentAnnouncementNudgeService = IncidentAnnouncementNudgeService(clock),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun listRules(organizationId: Int): List<IncidentAnnouncementRuleDefinition> = transaction {
        NativeIncidentAnnouncementRules
            .selectAll()
            .where { NativeIncidentAnnouncementRules.organizationId eq organizationId }
            .sortedWith(
                compareBy(
                    { it[NativeIncidentAnnouncementRules.name] },
                    { it[NativeIncidentAnnouncementRules.version] },
                ),
            )
            .map(::ruleDefinition)
    }

    fun createRule(
        organizationId: Int,
        actorUserId: Int,
        request: CreateIncidentAnnouncementRule,
    ): IncidentAnnouncementRuleDefinition = transaction {
        require(request.name.isNotBlank() && request.name.length <= MAX_RULE_NAME_LENGTH) {
            "Announcement rule name is required and must be at most $MAX_RULE_NAME_LENGTH characters"
        }
        require(request.channelId.isNullOrBlank() || !request.teamId.isNullOrBlank()) {
            "A Slack team is required when a channel is specified"
        }
        validatePresentation(request.conditions)
        val version = NativeIncidentAnnouncementRules
            .selectAll()
            .where { NativeIncidentAnnouncementRules.organizationId eq organizationId }
            .filter { it[NativeIncidentAnnouncementRules.name] == request.name.trim() }
            .maxOfOrNull { it[NativeIncidentAnnouncementRules.version] }
            ?.plus(1) ?: 1
        val id = NativeIncidentAnnouncementRules.insertIgnore {
            it[resourceId] = Uuid.random()
            it[NativeIncidentAnnouncementRules.organizationId] = organizationId
            it[name] = request.name.trim()
            it[NativeIncidentAnnouncementRules.version] = version
            it[enabled] = request.enabled
            it[teamId] = request.teamId?.trim()?.takeIf(String::isNotEmpty)
            it[channelId] = request.channelId?.trim()?.takeIf(String::isNotEmpty)
            it[announceTriage] = request.announceTriage
            it[allowPrivate] = request.allowPrivate
            it[allowTest] = request.allowTest
            it[conditions] = mapOf(
                "rules" to json.encodeToJsonElement(
                    IncidentAnnouncementRuleConditions.serializer(),
                    request.conditions,
                ),
            )
            it[createdBy] = actorUserId
            it[createdAt] = clock.now()
            it[updatedAt] = clock.now()
        }.insertedCount
        require(id == 1) { "Announcement rule could not be created" }
        NativeIncidentAnnouncementRules
            .selectAll()
            .where {
                (NativeIncidentAnnouncementRules.organizationId eq organizationId) and
                    (NativeIncidentAnnouncementRules.name eq request.name.trim()) and
                    (NativeIncidentAnnouncementRules.version eq version)
            }
            .single()
            .let(::ruleDefinition)
    }

    suspend fun consume(event: NativeIncidentDomainEvent, deliveryKey: String) {
        require(deliveryKey.isNotBlank()) { "Incident announcement delivery key is required" }
        val snapshot = snapshot(event) ?: return
        if (!shouldAnnounce(event, snapshot)) return
        destinations(event.organizationId).forEach { destination ->
            if (event.eventType == "INCIDENT_DECLARE" &&
                snapshot.status == NativeIncidentStatus.TRIAGE.wire &&
                !destination.announceTriage
            ) {
                return@forEach
            }
            if (!destination.conditions.matches(snapshot.context) || !destination.allowed(snapshot)) return@forEach
            announce(event, snapshot, destination)
        }
    }

    private fun announce(
        event: NativeIncidentDomainEvent,
        snapshot: IncidentSnapshot,
        destination: AnnouncementDestination,
    ) {
        val existing = transaction {
            NativeIncidentAnnouncements
                .selectAll()
                .where {
                    (NativeIncidentAnnouncements.organizationId eq event.organizationId) and
                        (NativeIncidentAnnouncements.incidentId eq event.incidentId) and
                        (NativeIncidentAnnouncements.ruleKey eq destination.ruleKey) and
                        (NativeIncidentAnnouncements.teamId eq destination.teamId) and
                        (NativeIncidentAnnouncements.channelId eq destination.channelId)
                }
                .singleOrNull()
        }
        if (existing != null && existing[NativeIncidentAnnouncements.desiredVersion] >= event.aggregateVersion) return
        val previousTs = existing?.get(NativeIncidentAnnouncements.providerMessageTs)
            ?: existing?.get(NativeIncidentAnnouncements.deliveryResourceId)?.toString()?.let { id ->
                outboundDeliveryService.find(id)?.providerMessageTs
            }
        val isUpdate = previousTs != null
        val visibleNudgeKeys = nudgeStateService.visibleKeys(
            scope = IncidentAnnouncementNudgeService.Scope(
                organizationId = event.organizationId,
                incidentId = event.incidentId,
                ruleKey = destination.ruleKey,
                teamId = destination.teamId,
                channelId = destination.channelId,
            ),
            applicableKeys = applicableNudgeKeys(snapshot, destination.conditions.nudges),
        )
        val cardPayload = cardPayload(snapshot, destination, previousTs, visibleNudgeKeys)
        val idempotencyKey =
            "incident:${snapshot.resourceId}:announcement:${destination.ruleKey}:${destination.teamId}" +
                ":${destination.channelId}:card"
        val request = SlackOutboundEnqueueRequest(
            organizationId = event.organizationId,
            teamId = destination.teamId,
            channelId = destination.channelId,
            operation = if (isUpdate) SlackOutboundOperation.MESSAGE_UPDATE else SlackOutboundOperation.MESSAGE,
            idempotencyKey = idempotencyKey,
            payload = cardPayload,
            desiredVersion = event.aggregateVersion,
        )
        try {
            val deliveryId = enqueue(request)
            persistAnnouncement(event, destination, cardPayload, previousTs, deliveryId)
            nudgeStateService.recordShown(
                scope = IncidentAnnouncementNudgeService.Scope(
                    organizationId = event.organizationId,
                    incidentId = event.incidentId,
                    ruleKey = destination.ruleKey,
                    teamId = destination.teamId,
                    channelId = destination.channelId,
                ),
                keys = visibleNudgeKeys,
                version = event.aggregateVersion,
            )
            if (previousTs != null && isMaterialUpdate(event.eventType)) {
                enqueueThread(event, snapshot, destination, previousTs)
            }
        } catch (error: Exception) {
            recordFailure(event, destination, error.message ?: "Slack announcement enqueue failed")
        }
    }

    private fun persistAnnouncement(
        event: NativeIncidentDomainEvent,
        destination: AnnouncementDestination,
        cardPayload: String,
        previousTs: String?,
        deliveryId: String,
    ) {
        transaction {
            NativeIncidentAnnouncements.insertIgnore {
                it[resourceId] = Uuid.random()
                it[organizationId] = event.organizationId
                it[incidentId] = event.incidentId
                it[ruleKey] = destination.ruleKey
                it[ruleVersion] = destination.ruleVersion
                it[NativeIncidentAnnouncements.teamId] = destination.teamId
                it[NativeIncidentAnnouncements.channelId] = destination.channelId
                it[desiredVersion] = event.aggregateVersion
                it[eventType] = event.eventType
                it[state] = IncidentAnnouncementState.PENDING.wire
                it[deliveryResourceId] = Uuid.parse(deliveryId)
                it[providerMessageTs] = previousTs
                it[NativeIncidentAnnouncements.cardPayload] = cardPayload
                it[createdAt] = clock.now()
                it[updatedAt] = clock.now()
            }
            NativeIncidentAnnouncements.update({ announcementPredicate(event, destination) }) {
                it[ruleVersion] = destination.ruleVersion
                it[desiredVersion] = event.aggregateVersion
                it[eventType] = event.eventType
                it[state] = announcementState(event.eventType)
                it[deliveryResourceId] = Uuid.parse(deliveryId)
                it[providerMessageTs] = previousTs
                it[NativeIncidentAnnouncements.cardPayload] = cardPayload
                it[lastError] = null
                it[updatedAt] = clock.now()
            }
        }
    }

    private fun recordFailure(event: NativeIncidentDomainEvent, destination: AnnouncementDestination, message: String) {
        transaction {
            NativeIncidentAnnouncements.update({ announcementPredicate(event, destination) }) {
                it[state] = IncidentAnnouncementState.FAILED.wire
                it[lastError] = message.take(MAX_ERROR_LENGTH)
                it[updatedAt] = clock.now()
            }
        }
    }

    private fun announcementPredicate(
        event: NativeIncidentDomainEvent,
        destination: AnnouncementDestination,
    ) =
        (NativeIncidentAnnouncements.organizationId eq event.organizationId) and
            (NativeIncidentAnnouncements.incidentId eq event.incidentId) and
            (NativeIncidentAnnouncements.ruleKey eq destination.ruleKey) and
            (NativeIncidentAnnouncements.teamId eq destination.teamId) and
            (NativeIncidentAnnouncements.channelId eq destination.channelId)

    private fun announcementState(eventType: String): String =
        if (eventType in TERMINAL_EVENT_TYPES) {
            IncidentAnnouncementState.ARCHIVED.wire
        } else {
            IncidentAnnouncementState.PENDING.wire
        }

    private fun enqueueThread(
        event: NativeIncidentDomainEvent,
        snapshot: IncidentSnapshot,
        destination: AnnouncementDestination,
        threadTs: String,
    ) {
        val payload = json.encodeToString(
            buildJsonObject {
                put("channel", destination.channelId)
                put("thread_ts", threadTs)
                put("text", "Incident update: ${snapshot.title}")
                put("blocks", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "section")
                        put("text", buildJsonObject {
                            put("type", "mrkdwn")
                            put("text", "*${event.eventType.replace('_', ' ')}* · version ${event.aggregateVersion}")
                        })
                    })
                })
            },
        )
        val threadKey =
            "incident:${snapshot.resourceId}:announcement:${destination.ruleKey}:thread:" +
                event.aggregateVersion
        enqueue(
            SlackOutboundEnqueueRequest(
                organizationId = event.organizationId,
                teamId = destination.teamId,
                channelId = destination.channelId,
                operation = SlackOutboundOperation.MESSAGE,
                idempotencyKey = threadKey,
                payload = payload,
                desiredVersion = event.aggregateVersion,
            ),
        )
    }

    private fun snapshot(event: NativeIncidentDomainEvent): IncidentSnapshot? = transaction {
        OnCallIncidents.selectAll().where {
            (OnCallIncidents.organizationId eq event.organizationId) and
                (OnCallIncidents.id eq event.incidentId)
        }.singleOrNull()?.let { row ->
            val fields = row[OnCallIncidents.declarationSnapshot].mapValues { (_, value) -> value.asText() }
            val roleAssignments = NativeIncidentRoleAssignments.selectAll().where {
                (NativeIncidentRoleAssignments.organizationId eq event.organizationId) and
                    (NativeIncidentRoleAssignments.incidentId eq event.incidentId) and
                    NativeIncidentRoleAssignments.endedAt.isNull()
            }
            val roleIds = roleAssignments.map { it[NativeIncidentRoleAssignments.roleDefinitionId] }.toSet()
            val userIds = roleAssignments.map { it[NativeIncidentRoleAssignments.assigneeUserId] }.toSet()
            val roleNames: Map<Int, String> = NativeIncidentRoleDefinitions.selectAll().where {
                NativeIncidentRoleDefinitions.id inList roleIds
            }.associate { it[NativeIncidentRoleDefinitions.id].value to it[NativeIncidentRoleDefinitions.name] }
            val userNames: Map<Int, String> = Users.selectAll().where { Users.id inList userIds }
                .associate { it[Users.id] to (it[Users.name] ?: "Responder") }
            val roles = roleAssignments.map { assignment ->
                val roleId: Int = assignment[NativeIncidentRoleAssignments.roleDefinitionId]
                val userId: Int = assignment[NativeIncidentRoleAssignments.assigneeUserId]
                val role = roleNames[roleId] ?: "Responder"
                val user = userNames[userId] ?: "Responder"
                "$role: $user"
            }
            val followUpCount = NativeIncidentFollowUps.selectAll().where {
                (NativeIncidentFollowUps.organizationId eq event.organizationId) and
                    (NativeIncidentFollowUps.incidentId eq event.incidentId)
            }.count().toInt()
            val activation = NativeIncidentResponseActivations.selectAll().where {
                (NativeIncidentResponseActivations.organizationId eq event.organizationId) and
                    (NativeIncidentResponseActivations.incidentId eq event.incidentId)
            }.maxByOrNull { it[NativeIncidentResponseActivations.createdAt] }
            val openUpdateRequest = NativeIncidentUpdateRequests.selectAll().where {
                (NativeIncidentUpdateRequests.organizationId eq event.organizationId) and
                    (NativeIncidentUpdateRequests.incidentId eq event.incidentId) and
                    (NativeIncidentUpdateRequests.status eq IncidentUpdateRequestStatus.OPEN.wire)
            }.maxByOrNull { it[NativeIncidentUpdateRequests.createdAt] }
            IncidentSnapshot(
                resourceId = row[OnCallIncidents.resourceId].toString(),
                title = row[OnCallIncidents.title],
                summary = row[OnCallIncidents.summary] ?: row[OnCallIncidents.description],
                incidentType = row[OnCallIncidents.incidentType],
                severity = row[OnCallIncidents.severity],
                status = row[OnCallIncidents.status],
                mode = row[OnCallIncidents.mode],
                visibility = row[OnCallIncidents.visibility],
                reporter = row[OnCallIncidents.declaredBy].toString(),
                fields = fields + mapOf(
                    "customer_impact" to (row[OnCallIncidents.customerImpact] ?: ""),
                    "next_update_at" to (row[OnCallIncidents.nextUpdateAt]?.toString() ?: ""),
                    "update_reminder_paused" to row[OnCallIncidents.updateReminderPaused].toString(),
                    "update_requested" to (openUpdateRequest?.get(NativeIncidentUpdateRequests.message) ?: ""),
                ),
                roles = roles,
                escalation = activation?.let {
                    "${it[NativeIncidentResponseActivations.acknowledgedCount]}/" +
                        "${it[NativeIncidentResponseActivations.desiredCount]} acknowledged · " +
                        it[NativeIncidentResponseActivations.status]
                },
                followUpCount = followUpCount,
            )
        }
    }

    private fun destinations(organizationId: Int): List<AnnouncementDestination> = transaction {
        val configured = NativeIncidentAnnouncementRules.selectAll().where {
            (NativeIncidentAnnouncementRules.organizationId eq organizationId) and
                (NativeIncidentAnnouncementRules.enabled eq true)
        }.map { row ->
            val conditions = json.decodeFromJsonElement(
                IncidentAnnouncementRuleConditions.serializer(),
                row[NativeIncidentAnnouncementRules.conditions]["rules"] as? JsonObject
                    ?: JsonObject(emptyMap()),
            )
            val channel = row[NativeIncidentAnnouncementRules.channelId]
            val teams = OrganizationIntegrations.selectAll().where {
                (OrganizationIntegrations.organization_id eq organizationId) and
                    (OrganizationIntegrations.integration_type eq "slack") and
                    (OrganizationIntegrations.enabled eq true) and
                    OrganizationIntegrations.team_id.isNotNull()
            }.filter { integration ->
                row[NativeIncidentAnnouncementRules.teamId] == null ||
                    integration[OrganizationIntegrations.team_id] == row[NativeIncidentAnnouncementRules.teamId]
            }
            val integrations = if (channel == null) teams else listOf(null)
            integrations.mapNotNull { integration ->
                val resolvedChannel = channel ?: integration?.get(OrganizationIntegrations.channel_id)
                val team =
                    row[NativeIncidentAnnouncementRules.teamId] ?: integration?.get(OrganizationIntegrations.team_id)
                destinationOrNull(row, conditions, team, resolvedChannel)
            }
        }.flatten()
        return@transaction configured.ifEmpty {
            OrganizationIntegrations.selectAll().where {
                (OrganizationIntegrations.organization_id eq organizationId) and
                    (OrganizationIntegrations.integration_type eq "slack") and
                    (OrganizationIntegrations.enabled eq true) and
                    OrganizationIntegrations.team_id.isNotNull() and
                    OrganizationIntegrations.channel_id.isNotNull()
            }.mapNotNull { row ->
                val team = row[OrganizationIntegrations.team_id]
                val channel = row[OrganizationIntegrations.channel_id]
                if (team.isNullOrBlank() || channel.isNullOrBlank()) null else defaultDestination(team, channel)
            }
        }
    }

    private fun shouldAnnounce(event: NativeIncidentDomainEvent, snapshot: IncidentSnapshot): Boolean {
        if (event.eventType == "INCIDENT_ROLE_INSTRUCTIONS") return false
        if (snapshot.mode == "TEST" || snapshot.visibility == "PRIVATE") {
            return destinations(event.organizationId).any { it.allowPrivate || it.allowTest }
        }
        return true
    }

    private fun cardPayload(
        snapshot: IncidentSnapshot,
        destination: AnnouncementDestination,
        messageTs: String?,
        visibleNudgeKeys: Set<String>,
    ): String =
        json.encodeToString(
            buildJsonObject {
                put("channel", destination.channelId)
                messageTs?.let { put("ts", it) }
                put("text", "Incident: ${snapshot.title}")
                putJsonObject("metadata") {
                    put("event_type", "moneat_incident")
                    putJsonObject("event_payload") {
                        put("incident_id", snapshot.resourceId)
                    }
                }
                put("blocks", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "header")
                        put("text", buildJsonObject {
                            put("type", "plain_text")
                            put("text", "🔥 ${snapshot.title.take(MAX_TITLE_LENGTH)}")
                            put("emoji", true)
                        })
                    })
                    add(buildJsonObject {
                        put("type", "section")
                        put("fields", buildJsonArray {
                            add(field("Status", snapshot.status))
                            add(field("Severity", snapshot.severity ?: "Unclassified"))
                            add(field("Type", snapshot.incidentType ?: "Unclassified"))
                            add(field("Reporter", snapshot.reporter))
                            add(field("Channel", destination.channelId))
                            add(field("Escalation", snapshot.escalation ?: "Not activated"))
                            add(field("Roles", snapshot.roles.ifEmpty { listOf("Unassigned") }.joinToString("; ")))
                            snapshot.fields["customer_impact"]?.takeIf(String::isNotBlank)?.let {
                                add(field("Customer impact", it))
                            }
                            snapshot.fields["next_update_at"]?.takeIf(String::isNotBlank)?.let {
                                add(field("Next update", it))
                            }
                            snapshot.fields["update_requested"]?.takeIf(String::isNotBlank)?.let {
                                add(field("Update requested", it))
                            }
                            snapshot.fields.entries.take(MAX_CUSTOM_FIELDS).forEach { (key, value) ->
                                if (key in RESERVED_INCIDENT_FIELDS) return@forEach
                                add(field(key.replace('_', ' ').replaceFirstChar(Char::uppercase), value))
                            }
                        })
                    })
                    snapshot.summary?.let { summary ->
                        add(buildJsonObject {
                            put("type", "section")
                            put("text", buildJsonObject {
                                put("type", "mrkdwn")
                                put("text", summary.take(MAX_SUMMARY_LENGTH))
                            })
                        })
                    }
                    followUpBlocks(snapshot).forEach(::add)
                    nudgeBlocks(snapshot, destination.conditions.nudges, visibleNudgeKeys).forEach(::add)
                    add(buildJsonObject {
                        put("type", "context")
                        put("elements", buildJsonArray {
                            contextLink(homepage(snapshot.resourceId), "Open incident homepage")?.let(::add)
                            destination.conditions.links.take(MAX_LINKS).forEach { link ->
                                contextLink(link.url, link.label)?.let(::add)
                            }
                            contextLink(snapshot.fields["call_url"], "Join incident call")?.let(::add)
                            contextLink(snapshot.fields["status_page_url"], "View status page")?.let(::add)
                        })
                    })
                    add(buildJsonObject {
                        put("type", "actions")
                        put("elements", buildJsonArray {
                            add(action("Accept", "incident_accept:${snapshot.resourceId}"))
                            add(action("Merge", "incident_merge:${snapshot.resourceId}"))
                            add(action("Decline", "incident_decline:${snapshot.resourceId}"))
                            destination.conditions.quickActions.take(MAX_QUICK_ACTIONS).forEach { configured ->
                                add(
                                    action(
                                        configured.label,
                                        configured.actionId,
                                        configured.value ?: snapshot.resourceId,
                                    ),
                                )
                            }
                        })
                    })
                })
            },
        )

    private fun field(label: String, value: String): JsonObject = buildJsonObject {
        put("type", "mrkdwn")
        put("text", "*$label:*\n${value.take(MAX_FIELD_LENGTH)}")
    }

    private fun contextLink(url: String?, label: String): JsonObject? {
        if (url.isNullOrBlank() || !isHttpUrl(url) || url.length > MAX_CONTEXT_LINK_LENGTH) return null
        return buildJsonObject {
            put("type", "mrkdwn")
            put("text", "<$url|${label.take(MAX_LINK_LABEL_LENGTH)}>")
        }
    }

    private fun action(
        label: String,
        actionId: String,
        value: String = actionId.substringAfter(':'),
    ): JsonObject = buildJsonObject {
        put("type", "button")
        put("text", buildJsonObject { put("type", "plain_text"); put("text", label) })
        put("action_id", actionId)
        put("value", value)
    }

    private fun nudgeBlocks(
        snapshot: IncidentSnapshot,
        policy: IncidentAnnouncementNudgePolicy,
        visibleNudgeKeys: Set<String>,
    ): List<JsonObject> {
        if (!policy.enabled || snapshot.status in TERMINAL_STATUSES) return emptyList()
        val nudges = nudgeMessages(snapshot, policy).filter { it.key in visibleNudgeKeys }.take(MAX_NUDGES)
        if (nudges.isEmpty()) return emptyList()
        return buildList {
            add(buildJsonObject {
                put("type", "section")
                put("text", buildJsonObject {
                    put("type", "mrkdwn")
                    put("text", "*Response nudges*\n" + nudges.joinToString("\n") { "• ${it.text}" })
                })
            })
            nudges.forEach { nudge ->
                add(buildJsonObject {
                    put("type", "actions")
                    putJsonArray("elements") {
                        add(action("Dismiss ${nudge.text}", "incident_nudge_dismiss:${snapshot.resourceId}", nudge.key))
                        if (nudge.key == NUDGE_TRIAGE_DECISION) {
                            add(action("Accept", "incident_accept:${snapshot.resourceId}"))
                            add(action("Merge", "incident_merge:${snapshot.resourceId}"))
                            add(action("Decline", "incident_decline:${snapshot.resourceId}"))
                        }
                    }
                })
            }
        }
    }

    private fun followUpBlocks(snapshot: IncidentSnapshot): List<JsonObject> {
        if (snapshot.followUpCount == 0) return emptyList()
        return listOf(buildJsonObject {
            put("type", "section")
            put("text", buildJsonObject {
                put("type", "mrkdwn")
                put(
                    "text",
                    "*Follow-up work:* ${snapshot.followUpCount} item(s) · " +
                        "manage details in the incident workspace",
                )
            })
        })
    }

    private fun applicableNudgeKeys(
        snapshot: IncidentSnapshot,
        policy: IncidentAnnouncementNudgePolicy,
    ): Set<String> = nudgeMessages(snapshot, policy).map { it.key }.toSet()

    private fun nudgeMessages(
        snapshot: IncidentSnapshot,
        policy: IncidentAnnouncementNudgePolicy,
    ): List<IncidentNudge> = listOfNotNull(
        IncidentNudge(NUDGE_MISSING_LEAD, "Assign an incident lead").takeIf {
            policy.missingLead && snapshot.roles.none { it.startsWith("Incident Commander:") }
        },
        IncidentNudge(NUDGE_MISSING_SUMMARY, "Add an incident summary").takeIf {
            policy.missingSummary && snapshot.summary.isNullOrBlank()
        },
        IncidentNudge(NUDGE_MISSING_UPDATE, "Set the next update time").takeIf {
            policy.missingUpdate && snapshot.fields["next_update_at"].isNullOrBlank()
        },
        IncidentNudge(NUDGE_MISSING_STATUS_PAGE, "Publish a status page update").takeIf {
            policy.missingStatusPage && snapshot.fields["status_page_url"].isNullOrBlank()
        },
        IncidentNudge(NUDGE_TRIAGE_DECISION, "Make the triage decision").takeIf {
            policy.missingTriageDecision && snapshot.status == NativeIncidentStatus.TRIAGE.wire
        },
        IncidentNudge(NUDGE_MISSING_ESCALATION, "Activate the response escalation").takeIf {
            policy.missingEscalation && snapshot.escalation == null
        },
        IncidentNudge(NUDGE_MISSING_CLOSURE, "Keep the closure checklist current").takeIf { policy.missingClosure },
    )

    private fun JsonElement.asText(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun homepage(resourceId: String): String =
        "${EnvConfig.get("FRONTEND_URL", "https://moneat.io")}/on-call/incidents/$resourceId"

    private fun isMaterialUpdate(eventType: String): Boolean =
        eventType in setOf(
            "INCIDENT_ACCEPT",
            "INCIDENT_UPDATE",
            "INCIDENT_REQUEST_UPDATE",
            "INCIDENT_UPDATE_REMINDER",
            "INCIDENT_PAUSE_UPDATE_REMINDERS",
            "INCIDENT_TRANSITION",
            "INCIDENT_RESOLVE",
            "INCIDENT_CLOSE",
            "INCIDENT_CANCEL",
            "INCIDENT_MERGE",
            "INCIDENT_ADD_FOLLOW_UP",
            "INCIDENT_UPDATE_FOLLOW_UP",
            "INCIDENT_ACCEPT_FOLLOW_UP",
            "INCIDENT_COMPLETE_FOLLOW_UP",
            "INCIDENT_CANCEL_FOLLOW_UP",
        )

    private data class IncidentSnapshot(
        val resourceId: String,
        val title: String,
        val summary: String?,
        val incidentType: String?,
        val severity: String?,
        val status: String,
        val mode: String,
        val visibility: String,
        val reporter: String,
        val fields: Map<String, String>,
        val roles: List<String>,
        val escalation: String?,
        val followUpCount: Int,
    ) {
        val context: IncidentAnnouncementContext
            get() = IncidentAnnouncementContext(
                incidentType = incidentType,
                severity = severity,
                service = fields["service"],
                team = fields["team"],
                fields = fields,
                visibility = visibility,
                mode = mode,
                status = status,
            )
    }

    private data class AnnouncementDestination(
        val ruleKey: String,
        val ruleVersion: Int,
        val teamId: String,
        val channelId: String,
        val conditions: IncidentAnnouncementRuleConditions,
        val announceTriage: Boolean,
        val allowPrivate: Boolean,
        val allowTest: Boolean,
    ) {
        fun allowed(snapshot: IncidentSnapshot): Boolean =
            (snapshot.mode != "TEST" || allowTest) && (snapshot.visibility != "PRIVATE" || allowPrivate) &&
                (snapshot.status != NativeIncidentStatus.TRIAGE.wire || announceTriage)
    }

    private fun enqueue(request: SlackOutboundEnqueueRequest): String =
        enqueue?.invoke(request) ?: outboundDeliveryService.enqueueAndWake(request)

    private fun validatePresentation(conditions: IncidentAnnouncementRuleConditions) {
        require(conditions.quickActions.size <= MAX_QUICK_ACTIONS) {
            "At most $MAX_QUICK_ACTIONS incident quick actions may be configured"
        }
        require(conditions.links.size <= MAX_LINKS) {
            "At most $MAX_LINKS incident links may be configured"
        }
        conditions.quickActions.forEach { action ->
            require(action.label.isNotBlank() && action.label.length <= MAX_ACTION_LABEL_LENGTH) {
                "Incident quick action labels must be non-empty and at most $MAX_ACTION_LABEL_LENGTH characters"
            }
            require(action.actionId.matches(ACTION_ID_PATTERN)) {
                "Incident quick action IDs must use lowercase letters, numbers, underscores, colons, or hyphens"
            }
            require(action.value == null || action.value.length <= MAX_ACTION_VALUE_LENGTH) {
                "Incident quick action values must be at most $MAX_ACTION_VALUE_LENGTH characters"
            }
        }
        val actionIds = conditions.quickActions.map { it.actionId }
        require(actionIds.size == actionIds.toSet().size) {
            "Incident quick action IDs must be unique"
        }
        require(actionIds.none { actionId -> RESERVED_ACTION_PREFIXES.any(actionId::startsWith) }) {
            "Incident quick action IDs may not use reserved incident action prefixes"
        }
        conditions.links.forEach { link ->
            require(link.label.isNotBlank() && link.label.length <= MAX_LINK_LABEL_LENGTH) {
                "Incident link labels must be non-empty and at most $MAX_LINK_LABEL_LENGTH characters"
            }
            require(isHttpUrl(link.url) && link.url.length <= MAX_CONTEXT_LINK_LENGTH) {
                "Incident links must use http or https URLs"
            }
        }
    }

    private data class IncidentNudge(val key: String, val text: String)

    private fun isHttpUrl(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

    companion object {
        private const val MAX_RULE_NAME_LENGTH = 160
        private const val MAX_ERROR_LENGTH = 1_000
        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_SUMMARY_LENGTH = 1_500
        private const val MAX_FIELD_LENGTH = 200
        private const val MAX_CUSTOM_FIELDS = 3
        private val RESERVED_INCIDENT_FIELDS = setOf(
            "customer_impact",
            "next_update_at",
            "update_reminder_paused",
            "update_requested",
        )
        private const val MAX_QUICK_ACTIONS = 5
        private const val MAX_LINKS = 5
        private const val MAX_LINK_LABEL_LENGTH = 80
        private const val MAX_CONTEXT_LINK_LENGTH = 2_000
        private const val MAX_ACTION_LABEL_LENGTH = 75
        private const val MAX_ACTION_VALUE_LENGTH = 2_000
        private const val MAX_NUDGES = 7
        private const val NUDGE_MISSING_LEAD = "missing_lead"
        private const val NUDGE_MISSING_SUMMARY = "missing_summary"
        private const val NUDGE_MISSING_UPDATE = "missing_update"
        private const val NUDGE_MISSING_STATUS_PAGE = "missing_status_page"
        private const val NUDGE_TRIAGE_DECISION = "triage_decision"
        private const val NUDGE_MISSING_ESCALATION = "missing_escalation"
        private const val NUDGE_MISSING_CLOSURE = "missing_closure"
        private val ACTION_ID_PATTERN = Regex("^[a-z][a-z0-9_:-]{1,63}$")
        private val RESERVED_ACTION_PREFIXES = setOf("incident_accept:", "incident_merge:", "incident_decline:")
        private val TERMINAL_STATUSES = setOf("RESOLVED", "CLOSED", "CANCELLED", "DECLINED", "MERGED")
        private val TERMINAL_EVENT_TYPES =
            setOf("INCIDENT_RESOLVE", "INCIDENT_CLOSE", "INCIDENT_CANCEL", "INCIDENT_DECLINE", "INCIDENT_MERGE")
    }

    private fun destinationOrNull(
        row: org.jetbrains.exposed.v1.core.ResultRow,
        conditions: IncidentAnnouncementRuleConditions,
        team: String?,
        channel: String?,
    ): AnnouncementDestination? {
        if (channel.isNullOrBlank() || team.isNullOrBlank()) return null
        return AnnouncementDestination(
            ruleKey = row[NativeIncidentAnnouncementRules.resourceId].toString(),
            ruleVersion = row[NativeIncidentAnnouncementRules.version],
            teamId = team,
            channelId = channel,
            conditions = conditions,
            announceTriage = row[NativeIncidentAnnouncementRules.announceTriage],
            allowPrivate = row[NativeIncidentAnnouncementRules.allowPrivate],
            allowTest = row[NativeIncidentAnnouncementRules.allowTest],
        )
    }

    private fun defaultDestination(team: String, channel: String) = AnnouncementDestination(
        ruleKey = "default:$team:$channel",
        ruleVersion = 1,
        teamId = team,
        channelId = channel,
        conditions = IncidentAnnouncementRuleConditions(),
        announceTriage = false,
        allowPrivate = false,
        allowTest = false,
    )

    private fun ruleDefinition(row: org.jetbrains.exposed.v1.core.ResultRow): IncidentAnnouncementRuleDefinition {
        val conditions = json.decodeFromJsonElement(
            IncidentAnnouncementRuleConditions.serializer(),
            row[NativeIncidentAnnouncementRules.conditions]["rules"] ?: JsonObject(emptyMap()),
        )
        return IncidentAnnouncementRuleDefinition(
            id = row[NativeIncidentAnnouncementRules.resourceId].toString(),
            name = row[NativeIncidentAnnouncementRules.name],
            version = row[NativeIncidentAnnouncementRules.version],
            enabled = row[NativeIncidentAnnouncementRules.enabled],
            teamId = row[NativeIncidentAnnouncementRules.teamId],
            channelId = row[NativeIncidentAnnouncementRules.channelId],
            announceTriage = row[NativeIncidentAnnouncementRules.announceTriage],
            allowPrivate = row[NativeIncidentAnnouncementRules.allowPrivate],
            allowTest = row[NativeIncidentAnnouncementRules.allowTest],
            conditions = conditions,
        )
    }
}

@Serializable
data class CreateIncidentAnnouncementRuleRequest(
    val name: String,
    val teamId: String? = null,
    val channelId: String? = null,
    val enabled: Boolean = true,
    val announceTriage: Boolean = false,
    val allowPrivate: Boolean = false,
    val allowTest: Boolean = false,
    val conditions: IncidentAnnouncementRuleConditions = IncidentAnnouncementRuleConditions(),
)

data class CreateIncidentAnnouncementRule(
    val name: String,
    val teamId: String?,
    val channelId: String?,
    val enabled: Boolean,
    val announceTriage: Boolean,
    val allowPrivate: Boolean,
    val allowTest: Boolean,
    val conditions: IncidentAnnouncementRuleConditions,
)

@Serializable
data class IncidentAnnouncementRuleDefinition(
    val id: String,
    val name: String,
    val version: Int,
    val enabled: Boolean,
    val teamId: String?,
    val channelId: String?,
    val announceTriage: Boolean,
    val allowPrivate: Boolean,
    val allowTest: Boolean,
    val conditions: IncidentAnnouncementRuleConditions,
)
