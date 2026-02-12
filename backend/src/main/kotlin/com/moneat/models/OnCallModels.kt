package com.moneat.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.time
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.postgresql.util.PGobject
import java.time.LocalTime

// ===== Custom Column Types =====

fun Table.jsonb(name: String): Column<Map<String, kotlinx.serialization.json.JsonElement>?> = 
    registerColumn<Map<String, kotlinx.serialization.json.JsonElement>?>(name, object : ColumnType() {
        override fun sqlType() = "JSONB"
        
        override fun valueFromDB(value: Any): Any {
            if (value is PGobject && value.value == null) {
                return emptyMap<String, kotlinx.serialization.json.JsonElement>()
            }
            return when (value) {
                is PGobject -> Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(value.value ?: "{}")
                is String -> Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(value)
                else -> emptyMap<String, kotlinx.serialization.json.JsonElement>()
            }
        }
        
        override fun notNullValueToDB(value: Any): Any {
            @Suppress("UNCHECKED_CAST")
            val map = value as Map<String, kotlinx.serialization.json.JsonElement>
            return PGobject().apply {
                type = "jsonb"
                this.value = Json.encodeToString(kotlinx.serialization.serializer(), map)
            }
        }
    })

// ===== Priority Management =====

object AlertPriorities : IntIdTable("alert_priorities") {
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val severity = varchar("severity", 20)
    val priorityLevel = varchar("priority_level", 10)
    val isPageable = bool("is_pageable")
    val label = varchar("label", 100)
    val description = text("description").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

@Serializable
data class AlertPriority(
    val id: Int,
    val organizationId: Int,
    val severity: String,
    val priorityLevel: String,
    val isPageable: Boolean,
    val label: String,
    val description: String? = null,
    val createdAt: String,
    val updatedAt: String
)

// ===== Business Hours =====

object BusinessHours : IntIdTable("business_hours") {
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val timezone = varchar("timezone", 100)
    val enabled = bool("enabled")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object BusinessHoursWindows : IntIdTable("business_hours_windows") {
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
    val endTime: LocalTime
)

@Serializable
data class BusinessHoursConfig(
    val id: Int,
    val organizationId: Int,
    val timezone: String,
    val enabled: Boolean,
    val windows: List<BusinessHoursWindow>,
    val createdAt: String,
    val updatedAt: String
)

// ===== Escalation Policies =====

object EscalationPolicies : IntIdTable("escalation_policies") {
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val repeatCount = integer("repeat_count")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object EscalationSteps : IntIdTable("escalation_steps") {
    val escalationPolicyId = integer("escalation_policy_id").references(EscalationPolicies.id, onDelete = ReferenceOption.CASCADE)
    val stepOrder = integer("step_order")
    val timeoutMinutes = integer("timeout_minutes")
    val createdAt = timestamp("created_at")
}

object EscalationStepTargets : IntIdTable("escalation_step_targets") {
    val escalationStepId = integer("escalation_step_id").references(EscalationSteps.id, onDelete = ReferenceOption.CASCADE)
    val targetType = varchar("target_type", 20)
    val targetId = integer("target_id")
    val createdAt = timestamp("created_at")
}

@Serializable
data class EscalationStepTarget(
    val id: Int,
    val targetType: String,
    val targetId: Int,
    val targetName: String? = null
)

@Serializable
data class EscalationStep(
    val id: Int,
    val stepOrder: Int,
    val timeoutMinutes: Int,
    val targets: List<EscalationStepTarget>,
    val createdAt: String
)

@Serializable
data class EscalationPolicy(
    val id: Int,
    val organizationId: Int,
    val name: String,
    val description: String? = null,
    val repeatCount: Int,
    val steps: List<EscalationStep>,
    val createdAt: String,
    val updatedAt: String
)

// ===== On-Call Schedules =====

object OnCallSchedules : IntIdTable("on_call_schedules") {
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val rotationType = varchar("rotation_type", 20)
    val handoffTime = time("handoff_time")
    val timezone = varchar("timezone", 100)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object OnCallParticipants : IntIdTable("on_call_participants") {
    val scheduleId = integer("schedule_id").references(OnCallSchedules.id, onDelete = ReferenceOption.CASCADE)
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    val createdAt = timestamp("created_at")
}

object OnCallOverrides : IntIdTable("on_call_overrides") {
    val scheduleId = integer("schedule_id").references(OnCallSchedules.id, onDelete = ReferenceOption.CASCADE)
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val startAt = timestamp("start_at")
    val endAt = timestamp("end_at")
    val createdBy = integer("created_by").references(Users.id)
    val createdAt = timestamp("created_at")
}

@Serializable
data class OnCallParticipant(
    val id: Int,
    val userId: Int,
    val userName: String,
    val userEmail: String,
    val position: Int
)

@Serializable
data class OnCallOverride(
    val id: Int,
    val scheduleId: Int,
    val userId: Int,
    val userName: String,
    val startAt: String,
    val endAt: String,
    val createdBy: Int,
    val createdAt: String
)

@Serializable
data class OnCallSchedule(
    val id: Int,
    val organizationId: Int,
    val name: String,
    val rotationType: String,
    @Serializable(with = LocalTimeSerializer::class)
    val handoffTime: LocalTime,
    val timezone: String,
    val participants: List<OnCallParticipant>,
    val overrides: List<OnCallOverride>,
    val currentOnCall: OnCallParticipant? = null,
    val createdAt: String,
    val updatedAt: String
)

// ===== Incidents =====

object Incidents : IntIdTable("incidents") {
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val escalationPolicyId = integer("escalation_policy_id").references(EscalationPolicies.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val title = varchar("title", 500)
    val description = text("description").nullable()
    val priorityLevel = varchar("priority_level", 10)
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
}

object IncidentTimeline : IntIdTable("incident_timeline") {
    val incidentId = integer("incident_id").references(Incidents.id, onDelete = ReferenceOption.CASCADE)
    val eventType = varchar("event_type", 30)
    val actorUserId = integer("actor_user_id").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val details = jsonb("details")
    val createdAt = timestamp("created_at")
}

@Serializable
data class Incident(
    val id: Int,
    val organizationId: Int,
    val escalationPolicyId: Int? = null,
    val escalationPolicyName: String? = null,
    val title: String,
    val description: String? = null,
    val priorityLevel: String,
    val status: String,
    val alertSource: String? = null,
    val deduplicationKey: String? = null,
    val currentStep: Int,
    val repeatIteration: Int,
    val triggeredAt: String,
    val acknowledgedAt: String? = null,
    val acknowledgedBy: Int? = null,
    val acknowledgedByName: String? = null,
    val resolvedAt: String? = null,
    val resolvedBy: Int? = null,
    val resolvedByName: String? = null,
    val metadata: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    val nextEscalationAt: String? = null,
    val viewedByCurrentUser: Boolean = false,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class IncidentTimelineEvent(
    val id: Int,
    val incidentId: Int,
    val eventType: String,
    val actorUserId: Int? = null,
    val actorName: String? = null,
    val details: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    val createdAt: String
)

// ===== Device Tokens =====

object UserDeviceTokens : IntIdTable("user_device_tokens") {
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val deviceToken = varchar("device_token", 500).uniqueIndex()
    val platform = varchar("platform", 20)
    val deviceName = varchar("device_name", 255).nullable()
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at")
}

@Serializable
data class UserDeviceToken(
    val id: Int,
    val userId: Int,
    val deviceToken: String,
    val platform: String,
    val deviceName: String? = null,
    val createdAt: String,
    val lastUsedAt: String
)

// ===== Slack User Mappings =====

object SlackUserMappings : IntIdTable("slack_user_mappings") {
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val slackUserId = varchar("slack_user_id", 100)
    val slackTeamId = varchar("slack_team_id", 100)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

@Serializable
data class SlackUserMapping(
    val id: Int,
    val userId: Int,
    val slackUserId: String,
    val slackTeamId: String,
    val createdAt: String,
    val updatedAt: String
)

// ===== LocalTime Serializer =====

object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.STRING)
    
    override fun serialize(encoder: Encoder, value: LocalTime) {
        encoder.encodeString(value.toString())
    }
    
    override fun deserialize(decoder: Decoder): LocalTime {
        return LocalTime.parse(decoder.decodeString())
    }
}
