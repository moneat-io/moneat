// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.models

import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.NativeIncidentTypes
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.postgresql.util.PGobject
import java.time.LocalTime
import kotlin.uuid.Uuid

// ===== Custom Column Types =====

private class JsonbColumnType : ColumnType<Map<String, kotlinx.serialization.json.JsonElement>>() {
    private fun isH2(): Boolean =
        TransactionManager
            .currentOrNull()
            ?.db
            ?.url
            ?.contains("h2", ignoreCase = true) == true

    override fun sqlType(): String = if (isH2()) "TEXT" else "JSONB"

    override fun valueFromDB(value: Any): Map<String, kotlinx.serialization.json.JsonElement> {
        if (value is PGobject && value.value == null) {
            return emptyMap()
        }
        return when (value) {
            is PGobject -> Json.decodeFromString(value.value ?: "{}")
            is String -> Json.decodeFromString(value)
            else -> emptyMap()
        }
    }

    override fun notNullValueToDB(value: Map<String, kotlinx.serialization.json.JsonElement>): Any {
        val encoded = Json.encodeToString(kotlinx.serialization.serializer(), value)
        if (isH2()) return encoded
        return PGobject().apply {
            type = "jsonb"
            this.value = encoded
        }
    }
}

fun Table.jsonb(name: String): Column<Map<String, kotlinx.serialization.json.JsonElement>?> =
    registerColumn<Map<String, kotlinx.serialization.json.JsonElement>>(name, JsonbColumnType()).nullable()

fun Table.requiredJsonb(name: String): Column<Map<String, kotlinx.serialization.json.JsonElement>> =
    registerColumn(name, JsonbColumnType())

// ===== Priority Management =====

object AlertPriorities : IntIdTable("alert_priorities") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val priority = varchar("priority", 10)
    val isPageable = bool("is_pageable")
    val label = varchar("label", 100)
    val description = text("description").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

@Serializable
data class AlertPriority(
    val id: String,
    @SerialName("organizationId") val organizationResourceId: String,
    val priority: String,
    val isPageable: Boolean,
    val label: String,
    val description: String? = null,
    val createdAt: String,
    val updatedAt: String,
    @Transient val internalId: Int = 0,
    @Transient val organizationId: Int = 0,
)

// ===== Business Hours =====

object BusinessHours : IntIdTable("business_hours") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId =
        integer("organization_id")
            .references(Organizations.id, onDelete = ReferenceOption.CASCADE)
            .uniqueIndex()
    val timezone = varchar("timezone", 100)
    val enabled = bool("enabled")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object BusinessHoursWindows : IntIdTable("business_hours_windows") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val businessHoursId = integer("business_hours_id").references(BusinessHours.id, onDelete = ReferenceOption.CASCADE)
    val dayOfWeek = integer("day_of_week")
    val startTime = time("start_time")
    val endTime = time("end_time")
    val createdAt = timestamp("created_at")
}

@Serializable
data class BusinessHoursWindow(
    val dayOfWeek: Int,
    @Serializable(with = LocalTimeSerializer::class)
    val startTime: LocalTime,
    @Serializable(with = LocalTimeSerializer::class)
    val endTime: LocalTime,
)

@Serializable
data class BusinessHoursConfig(
    val id: String,
    @SerialName("organizationId") val organizationResourceId: String,
    val timezone: String,
    val enabled: Boolean,
    val windows: List<BusinessHoursWindow>,
    val createdAt: String,
    val updatedAt: String,
    @Transient val internalId: Int = 0,
    @Transient val organizationId: Int = 0,
)

// ===== Escalation Policies =====

// EscalationPolicies table is defined in core (OnCallSharedModels.kt)
// to allow core code to query escalation policies without enterprise dependency.

object EscalationSteps : IntIdTable("escalation_steps") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val escalationPolicyId =
        integer("escalation_policy_id")
            .references(EscalationPolicies.id, onDelete = ReferenceOption.CASCADE)
    val stepOrder = integer("step_order")
    val timeoutMinutes = integer("timeout_minutes")
    val smsFallbackDelayMinutes = integer("sms_call_fallback_delay_minutes").default(2)
    val createdAt = timestamp("created_at")
}

object EscalationStepTargets : IntIdTable("escalation_step_targets") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val escalationStepId =
        integer("escalation_step_id")
            .references(EscalationSteps.id, onDelete = ReferenceOption.CASCADE)
    val targetType = varchar("target_type", 20)
    val targetId = integer("target_id")
    val createdAt = timestamp("created_at")
}

@Serializable
data class EscalationStepTarget(
    val id: String,
    val targetType: String,
    @SerialName("targetId") val targetResourceId: String,
    val targetName: String? = null,
    @Transient val internalId: Int = 0,
    @Transient val targetId: Int = 0,
)

@Serializable
data class EscalationStep(
    val id: String,
    val stepOrder: Int,
    val timeoutMinutes: Int,
    val smsFallbackDelayMinutes: Int = 2,
    val targets: List<EscalationStepTarget>,
    val createdAt: String,
    @Transient val internalId: Int = 0,
)

@Serializable
data class EscalationPolicy(
    val id: String,
    @SerialName("organizationId") val organizationResourceId: String,
    val name: String,
    val description: String? = null,
    val repeatCount: Int,
    val steps: List<EscalationStep>,
    val createdAt: String,
    val updatedAt: String,
    @Transient val internalId: Int = 0,
    @Transient val organizationId: Int = 0,
)

// ===== On-Call Schedules =====

// OnCallSchedules and OnCallParticipants are defined in core (OnCallSharedModels.kt)

object OnCallOverrides : IntIdTable("on_call_overrides") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val scheduleId = integer("schedule_id").references(OnCallSchedules.id, onDelete = ReferenceOption.CASCADE)
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val startAt = timestamp("start_at")
    val endAt = timestamp("end_at")
    val createdBy = integer("created_by").references(Users.id)
    val createdAt = timestamp("created_at")
}

object OnCallScheduleUsergroups : IntIdTable("on_call_schedule_usergroups") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val scheduleId =
        integer("schedule_id")
            .references(OnCallSchedules.id, onDelete = ReferenceOption.CASCADE)
            .uniqueIndex()
    val slackUsergroupId = varchar("slack_usergroup_id", 100)
    val slackUsergroupHandle = varchar("slack_usergroup_handle", 100)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

@Serializable
data class OnCallParticipant(
    val id: String,
    @SerialName("userId") val userResourceId: String,
    val userName: String,
    val userEmail: String,
    val position: Int,
    @Transient val internalId: Int = 0,
    @Transient val userId: Int = 0,
)

@Serializable
data class OnCallOverride(
    val id: String,
    @SerialName("scheduleId") val scheduleResourceId: String,
    @SerialName("userId") val userResourceId: String,
    val userName: String,
    val startAt: String,
    val endAt: String,
    @SerialName("createdBy") val createdByResourceId: String,
    val createdAt: String,
    @Transient val internalId: Int = 0,
    @Transient val scheduleId: Int = 0,
    @Transient val userId: Int = 0,
    @Transient val createdBy: Int = 0,
)

@Serializable
data class OnCallSchedule(
    val id: String,
    @SerialName("organizationId") val organizationResourceId: String,
    val name: String,
    val rotationType: String,
    @Serializable(with = LocalTimeSerializer::class)
    val handoffTime: LocalTime,
    val timezone: String,
    val participants: List<OnCallParticipant>,
    val overrides: List<OnCallOverride>,
    val currentOnCall: OnCallParticipant? = null,
    val slackUsergroupId: String? = null,
    val slackUsergroupHandle: String? = null,
    val createdAt: String,
    val updatedAt: String,
    @Transient val internalId: Int = 0,
    @Transient val organizationId: Int = 0,
)

// ===== On-Call Incidents (User Declared) =====

object OnCallIncidents : IntIdTable("on_call_incidents") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 255)
    val description = text("description").nullable()

    /** Triage incidents stay unclassified until acceptance, so severity is optional there. */
    val severity = varchar("severity", 10).nullable()
    val status = varchar("status", 20).default("ACTIVE")
    val mode = varchar("mode", 24).default("LIVE")
    val visibility = varchar("visibility", 24).default("ORGANIZATION")
    val incidentType = varchar("incident_type", 100).nullable()
    val incidentTypeDefinitionId = integer("incident_type_definition_id").references(NativeIncidentTypes.id).nullable()
    val declarationSnapshot = requiredJsonb("declaration_snapshot").clientDefault { emptyMap() }
    val summary = text("summary").nullable()
    val version = integer("version").default(1)
    val declaredBy = integer("declared_by").references(Users.id)
    val declaredAt = timestamp("declared_at")
    val triagedAt = timestamp("triaged_at").nullable()
    val acceptedAt = timestamp("accepted_at").nullable()
    val resolvedBy = integer("resolved_by").references(Users.id).nullable()
    val resolvedAt = timestamp("resolved_at").nullable()
    val postIncidentAt = timestamp("post_incident_at").nullable()
    val closedAt = timestamp("closed_at").nullable()
    val cancelledAt = timestamp("cancelled_at").nullable()
    val declinedAt = timestamp("declined_at").nullable()
    val mergedAt = timestamp("merged_at").nullable()
    val mergedIntoIncidentId =
        integer("merged_into_incident_id").references(id, onDelete = ReferenceOption.RESTRICT).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        // Severity is only required once the incident has been accepted out of triage.
        check("chk_native_incident_triage_severity") {
            severity.isNotNull() or acceptedAt.isNull()
        }
        check("chk_native_incident_merged_state") {
            ((status eq NativeIncidentStatus.MERGED.wire) and
                mergedAt.isNotNull() and mergedIntoIncidentId.isNotNull()) or
                ((status neq NativeIncidentStatus.MERGED.wire) and
                    mergedAt.isNull() and mergedIntoIncidentId.isNull())
        }
    }
}

@Serializable
data class OnCallIncident(
    val id: String,
    @SerialName("organizationId") val organizationResourceId: String,
    val title: String,
    val description: String? = null,
    val severity: String? = null,
    val status: String,
    val mode: String = "LIVE",
    val visibility: String = "ORGANIZATION",
    val incidentType: String? = null,
    val summary: String? = null,
    val version: Int = 1,
    @SerialName("declaredBy") val declaredByResourceId: String,
    val declaredByName: String? = null,
    val declaredAt: String,
    val triagedAt: String? = null,
    val acceptedAt: String? = null,
    @SerialName("resolvedBy") val resolvedByResourceId: String? = null,
    val resolvedByName: String? = null,
    val resolvedAt: String? = null,
    val postIncidentAt: String? = null,
    val closedAt: String? = null,
    val cancelledAt: String? = null,
    val declinedAt: String? = null,
    val mergedAt: String? = null,
    @SerialName("mergedIntoIncidentId") val mergedIntoIncidentResourceId: String? = null,
    val alertCount: Int = 0,
    val alerts: List<OnCallAlert> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

object OnCallIncidentTimeline : IntIdTable("on_call_incident_timeline") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val eventKey = varchar("event_key", 200)
    val eventType = varchar("event_type", 80)
    val actorUserId = integer("actor_user_id").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val details = requiredJsonb("details").clientDefault { emptyMap() }
    val sourceType = varchar("source_type", 48).nullable()
    val sourceReference = varchar("source_reference", 500).nullable()
    val sourceUrl = text("source_url").nullable()
    val provenance = varchar("provenance", 32).default("INTERNAL")
    val visibility = varchar("visibility", 24).default("ORGANIZATION")
    val originalOccurredAt = timestamp("original_occurred_at")
    val observedAt = timestamp("observed_at")
    val displayOrder = long("display_order")
    val annotation = text("annotation").nullable()
    val editedAt = timestamp("edited_at").nullable()
    val editedBy = integer("edited_by").references(Users.id).nullable()
    val deletedAt = timestamp("deleted_at").nullable()
    val deletedBy = integer("deleted_by").references(Users.id).nullable()
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(incidentId, eventKey)
    }
}

// ===== On-Call Alerts =====

object OnCallAlerts : IntIdTable("on_call_alerts") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val declaredIncidentId =
        integer("declared_incident_id")
            .references(OnCallIncidents.id, onDelete = ReferenceOption.SET_NULL)
            .nullable()
    val escalationPolicyId =
        integer(
            "escalation_policy_id",
        ).references(EscalationPolicies.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val title = varchar("title", 500)
    val description = text("description").nullable()
    val priority = varchar("priority", 10)
    val status = varchar("status", 20)
    val alertSource = varchar("alert_source", 100).nullable()
    val deduplicationKey = varchar("deduplication_key", 255).nullable()
    val currentStep = integer("current_step")
    val repeatIteration = integer("repeat_iteration")
    val triggeredAt = timestamp("triggered_at")
    val acknowledgedAt = timestamp("acknowledged_at").nullable()
    val acknowledgedBy = integer("acknowledged_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val resolvedAt = timestamp("resolved_at").nullable()
    val resolvedBy = integer("resolved_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val metadata = jsonb("metadata")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(resourceId)
    }
}

object OnCallAlertTimeline : IntIdTable("on_call_alert_timeline") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val alertId = integer("alert_id").references(OnCallAlerts.id, onDelete = ReferenceOption.CASCADE)
    val eventType = varchar("event_type", 30)
    val actorUserId = integer("actor_user_id").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val details = jsonb("details")
    val createdAt = timestamp("created_at")
}

@Serializable
data class OnCallAlert(
    val id: String,
    @SerialName("organizationId") val organizationResourceId: String,
    @SerialName("declaredIncidentId") val declaredIncidentResourceId: String? = null,
    @SerialName("escalationPolicyId") val escalationPolicyResourceId: String? = null,
    val escalationPolicyName: String? = null,
    val title: String,
    val description: String? = null,
    val priority: String,
    val status: String,
    val alertSource: String? = null,
    val deduplicationKey: String? = null,
    val currentStep: Int,
    val repeatIteration: Int,
    val triggeredAt: String,
    val acknowledgedAt: String? = null,
    @SerialName("acknowledgedBy") val acknowledgedByResourceId: String? = null,
    val acknowledgedByName: String? = null,
    val resolvedAt: String? = null,
    @SerialName("resolvedBy") val resolvedByResourceId: String? = null,
    val resolvedByName: String? = null,
    val metadata: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    val nextEscalationAt: String? = null,
    val viewedByCurrentUser: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
    @Transient val internalId: Int = 0,
    @Transient val organizationId: Int = 0,
    @Transient val declaredIncidentId: Int? = null,
    @Transient val escalationPolicyId: Int? = null,
    @Transient val acknowledgedBy: Int? = null,
    @Transient val resolvedBy: Int? = null,
)

object OnCallIncidentAlerts : Table("on_call_incident_alerts") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val alertId = integer("alert_id").references(OnCallAlerts.id, onDelete = ReferenceOption.CASCADE)
    val statusOwner = varchar("status_owner", 24).default("INCIDENT")
    val severityOwner = varchar("severity_owner", 24).default("INCIDENT")
    val resolutionOwner = varchar("resolution_owner", 24).default("INCIDENT")
    override val primaryKey = PrimaryKey(incidentId, alertId)

    init {
        uniqueIndex(alertId)
    }
}

@Serializable
data class OnCallTimelineEvent(
    val id: String,
    @SerialName("targetId") val targetResourceId: String,
    val eventType: String,
    @SerialName("actorUserId") val actorUserResourceId: String? = null,
    val actorName: String? = null,
    val details: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    val createdAt: String,
    // Fields for merged on-call incident timeline
    val source: String? = null, // "incident" or "alert"
    @SerialName("alertId") val alertResourceId: String? = null,
    val alertTitle: String? = null,
    @Transient val internalId: Int = 0,
    @Transient val targetId: Int = 0,
    @Transient val actorUserId: Int? = null,
    @Transient val alertId: Int? = null,
)

// ===== Device Tokens =====

object UserDeviceTokens : IntIdTable("user_device_tokens") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val deviceToken = varchar("device_token", 500).uniqueIndex()
    val platform = varchar("platform", 20)
    val deviceName = varchar("device_name", 255).nullable()
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at")
}

@Serializable
data class UserDeviceToken(
    val id: String,
    @SerialName("userId") val userResourceId: String,
    val deviceToken: String,
    val platform: String,
    val deviceName: String? = null,
    val createdAt: String,
    val lastUsedAt: String,
    @Transient val internalId: Int = 0,
    @Transient val userId: Int = 0,
)

// ===== Slack User Mappings =====

// SlackUserMappings table is defined in core (OnCallSharedModels.kt)
// to allow core code to query Slack user mappings without enterprise dependency.

@Serializable
data class SlackUserMapping(
    val id: String,
    @SerialName("userId") val userResourceId: String,
    val slackUserId: String,
    val slackTeamId: String,
    val createdAt: String,
    val updatedAt: String,
    @Transient val internalId: Int = 0,
    @Transient val userId: Int = 0,
)

// ===== Twilio Notifications =====

object TwilioNotificationsSent : IntIdTable("twilio_notifications_sent") {
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val alertId = integer("alert_id").references(OnCallAlerts.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val channel = varchar("channel", 10) // 'sms' or 'call'
    val twilioSid = varchar("twilio_sid", 64).nullable()
    val status = varchar("status", 20)
    val phoneNumber = varchar("phone_number", 20)
    val createdAt = timestamp("created_at")
}

// ===== LocalTime Serializer =====

object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: LocalTime,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString())
}
