// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.models

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.models.requiredJsonb
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

@Serializable
enum class NativeIncidentStatus(val wire: String) {
    TRIAGE("TRIAGE"),
    ACTIVE("ACTIVE"),
    RESOLVED("RESOLVED"),
    POST_INCIDENT("POST_INCIDENT"),
    CLOSED("CLOSED"),
    CANCELLED("CANCELLED"),
    DECLINED("DECLINED"),
    MERGED("MERGED"),
    ;

    /** MERGED is the only outcome that permanently retires an incident aggregate. */
    val terminal: Boolean get() = this == MERGED

    companion object {
        fun fromWire(value: String): NativeIncidentStatus? =
            when (value.uppercase()) {
                "OPEN" -> ACTIVE
                else -> entries.firstOrNull { it.wire == value.uppercase() }
            }
    }
}

@Serializable
enum class NativeIncidentMode(val wire: String) {
    LIVE("LIVE"),
    RETROSPECTIVE("RETROSPECTIVE"),
    TEST("TEST"),
}

@Serializable
enum class NativeIncidentVisibility(val wire: String) {
    ORGANIZATION("ORGANIZATION"),
    PRIVATE("PRIVATE"),
    PUBLIC("PUBLIC"),
}

@Serializable
enum class IncidentStateOwner(val wire: String) {
    INCIDENT("INCIDENT"),
    ALERT("ALERT"),
    ALERT_EPISODE("ALERT_EPISODE"),
}

/**
 * Typed link between a native incident and a source-neutral alert episode.
 * On-call alerts retain their existing link table for compatibility.
 */
object NativeIncidentAlertEpisodeLinks : IntIdTable("native_incident_alert_episode_links") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val alertEpisodeId = integer("alert_episode_id").references(AlertEpisodes.id, onDelete = ReferenceOption.CASCADE)
    val statusOwner = varchar("status_owner", 24)
    val severityOwner = varchar("severity_owner", 24)
    val resolutionOwner = varchar("resolution_owner", 24)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(incidentId, alertEpisodeId)
        uniqueIndex(organizationId, resourceId)
    }
}

object NativeIncidentCommands : IntIdTable("native_incident_commands") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId =
        integer("incident_id")
            .references(OnCallIncidents.id, onDelete = ReferenceOption.SET_NULL)
            .nullable()
    val actorUserId = integer("actor_user_id").references(Users.id)
    val commandKey = varchar("command_key", 160)
    val commandType = varchar("command_type", 48)
    val requestFingerprint = varchar("request_fingerprint", 64)
    val expectedVersion = integer("expected_version").nullable()
    val resultVersion = integer("result_version").nullable()
    val actionResourceId = uuid("action_resource_id").nullable()
    val followUpResourceId = uuid("follow_up_resource_id").nullable()
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex("uq_native_incident_commands_org_command_key", organizationId, commandKey)
        uniqueIndex(organizationId, resourceId)
    }
}

enum class IncidentUpdateRequestStatus(val wire: String) {
    OPEN("OPEN"),
    FULFILLED("FULFILLED"),
    PAUSED("PAUSED"),
    CANCELLED("CANCELLED"),
}

object NativeIncidentUpdateRequests : IntIdTable("native_incident_update_requests") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val requestedBy = integer("requested_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val message = text("message").nullable()
    val dueAt = timestamp("due_at")
    val status = varchar("status", 24)
    val escalationLevel = integer("escalation_level").default(0)
    val lastRemindedAt = timestamp("last_reminded_at").nullable()
    val fulfilledAt = timestamp("fulfilled_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, resourceId)
        index(false, organizationId, status, dueAt)
        index(false, incidentId, status)
    }
}

enum class IncidentActionState(val wire: String) {
    OPEN("OPEN"),
    CLAIMED("CLAIMED"),
    COMPLETED("COMPLETED"),
    CANCELLED("CANCELLED"),
    FOLLOW_UP("FOLLOW_UP"),
}

enum class IncidentActionSource(val wire: String) {
    COMMAND("COMMAND"),
    MODAL("MODAL"),
    REACTION("REACTION"),
    MESSAGE_SHORTCUT("MESSAGE_SHORTCUT"),
    DASHBOARD("DASHBOARD"),
    API("API"),
    WORKFLOW("WORKFLOW"),
    AI_PROPOSAL("AI_PROPOSAL"),
    SLACK("SLACK"),
}

object NativeIncidentActions : IntIdTable("native_incident_actions") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val description = text("description")
    val assigneeUserId = integer("assignee_user_id")
        .references(Users.id, onDelete = ReferenceOption.SET_NULL)
        .nullable()
    val state = varchar("state", 24)
    val actionSource = varchar("source", 32)
    val slackChannelId = varchar("slack_channel_id", 128).nullable()
    val slackMessageTs = varchar("slack_message_ts", 64).nullable()
    val createdBy = integer("created_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val claimedAt = timestamp("claimed_at").nullable()
    val completedAt = timestamp("completed_at").nullable()
    val cancelledAt = timestamp("cancelled_at").nullable()
    val convertedToFollowUpAt = timestamp("converted_to_follow_up_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, resourceId)
        index(false, incidentId, state)
    }
}

object NativeIncidentActionEvents : IntIdTable("native_incident_action_events") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val actionId = integer("action_id").references(NativeIncidentActions.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val actorUserId = integer("actor_user_id").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val eventType = varchar("event_type", 48)
    val fromState = varchar("from_state", 24).nullable()
    val toState = varchar("to_state", 24).nullable()
    val details = requiredJsonb("details").clientDefault { emptyMap() }
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(organizationId, resourceId)
        index(false, actionId, createdAt)
        index(false, incidentId, createdAt)
    }
}

enum class IncidentOutboxStatus(val wire: String) {
    PENDING("PENDING"),
    PROCESSING("PROCESSING"),
    PUBLISHED("PUBLISHED"),
    DEAD_LETTER("DEAD_LETTER"),
}

object NativeIncidentOutboxEvents : IntIdTable("native_incident_outbox_events") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val eventType = varchar("event_type", 80)
    val aggregateVersion = integer("aggregate_version")
    val idempotencyKey = varchar("idempotency_key", 200)
    val payload = requiredJsonb("payload")
    val status = varchar("status", 24)
    val attemptCount = integer("attempt_count")
    val availableAt = timestamp("available_at")
    val leasedAt = timestamp("leased_at").nullable()
    val leaseOwner = varchar("lease_owner", 120).nullable()
    val lastError = text("last_error").nullable()
    val publishedAt = timestamp("published_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(organizationId, idempotencyKey)
        index(false, status, availableAt, id)
        index(false, incidentId, aggregateVersion, id)
    }
}

enum class IncidentDeliveryStatus(val wire: String) {
    PENDING("PENDING"),
    PROCESSING("PROCESSING"),
    DELIVERED("DELIVERED"),
    DEAD_LETTER("DEAD_LETTER"),
}

object NativeIncidentOutboxDeliveries : IntIdTable("native_incident_outbox_deliveries") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val eventId = integer("event_id").references(NativeIncidentOutboxEvents.id, onDelete = ReferenceOption.CASCADE)
    val consumerName = varchar("consumer_name", 120)
    val deliveryKey = varchar("delivery_key", 384)
    val status = varchar("status", 24)
    val attemptCount = integer("attempt_count")
    val availableAt = timestamp("available_at")
    val leasedAt = timestamp("leased_at").nullable()
    val leaseOwner = varchar("lease_owner", 120).nullable()
    val lastError = text("last_error").nullable()
    val deliveredAt = timestamp("delivered_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(eventId, consumerName)
        uniqueIndex(deliveryKey)
        index(false, status, availableAt, id)
    }
}
