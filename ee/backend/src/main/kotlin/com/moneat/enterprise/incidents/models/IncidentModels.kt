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
    ;

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
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex("uq_native_incident_commands_org_command_key", organizationId, commandKey)
        uniqueIndex(organizationId, resourceId)
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
