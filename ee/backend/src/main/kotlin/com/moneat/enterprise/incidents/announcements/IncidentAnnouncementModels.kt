// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.announcements

import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.models.requiredJsonb
import com.moneat.shared.models.Organizations
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Conditions are evaluated against the canonical incident snapshot, never against an alert route. */
@Serializable
data class IncidentAnnouncementRuleConditions(
    val incidentTypes: Set<String> = emptySet(),
    val severities: Set<String> = emptySet(),
    val services: Set<String> = emptySet(),
    val teams: Set<String> = emptySet(),
    val fields: Map<String, Set<String>> = emptyMap(),
    val visibilities: Set<String> = emptySet(),
    /** Optional incident actions rendered on matching overview cards. */
    val quickActions: List<IncidentAnnouncementQuickAction> = emptyList(),
    /** Optional links rendered alongside the canonical incident homepage. */
    val links: List<IncidentAnnouncementLink> = emptyList(),
    /** Controls which response-work nudges are included in card updates. */
    val nudges: IncidentAnnouncementNudgePolicy = IncidentAnnouncementNudgePolicy(),
)

@Serializable
data class IncidentAnnouncementQuickAction(
    val label: String,
    val actionId: String,
    val value: String? = null,
)

@Serializable
data class IncidentAnnouncementLink(
    val label: String,
    val url: String,
)

@Serializable
data class IncidentAnnouncementNudgePolicy(
    val enabled: Boolean = true,
    val missingLead: Boolean = true,
    val missingSummary: Boolean = true,
    val missingUpdate: Boolean = true,
    val missingStatusPage: Boolean = true,
    val missingTriageDecision: Boolean = true,
    val missingEscalation: Boolean = true,
    val missingClosure: Boolean = true,
)

data class IncidentAnnouncementContext(
    val incidentType: String?,
    val severity: String?,
    val service: String?,
    val team: String?,
    val fields: Map<String, String>,
    val visibility: String,
    val mode: String,
    val status: String,
)

fun IncidentAnnouncementRuleConditions.matches(context: IncidentAnnouncementContext): Boolean =
    (incidentTypes.isEmpty() || incidentTypes.contains(context.incidentType)) &&
        (severities.isEmpty() || severities.contains(context.severity)) &&
        (services.isEmpty() || services.contains(context.service)) &&
        (teams.isEmpty() || teams.contains(context.team)) &&
        (visibilities.isEmpty() || visibilities.contains(context.visibility)) &&
        fields.all { (key, values) -> values.isEmpty() || values.contains(context.fields[key]) }

object NativeIncidentAnnouncementRules : IntIdTable("native_incident_announcement_rules") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 160)
    val version = integer("version")
    val enabled = bool("enabled").default(true)
    val teamId = varchar("team_id", 255).nullable()
    val channelId = varchar("channel_id", 255).nullable()
    val announceTriage = bool("announce_triage").default(false)
    val allowPrivate = bool("allow_private").default(false)
    val allowTest = bool("allow_test").default(false)
    val conditions = requiredJsonb("conditions")
    val createdBy = integer("created_by").nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(organizationId, name, version)
    }
}

enum class IncidentAnnouncementState(val wire: String) {
    PENDING("PENDING"),
    ACTIVE("ACTIVE"),
    FAILED("FAILED"),
    ARCHIVED("ARCHIVED"),
}

/** One stable card per incident/rule/destination; desiredVersion makes retries converge. */
object NativeIncidentAnnouncements : IntIdTable("native_incident_announcements") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val ruleKey = varchar("rule_key", 160)
    val ruleVersion = integer("rule_version")
    val teamId = varchar("team_id", 255)
    val channelId = varchar("channel_id", 255)
    val desiredVersion = integer("desired_version")
    val eventType = varchar("event_type", 80)
    val state = varchar("state", 24).default(IncidentAnnouncementState.PENDING.wire)
    val deliveryResourceId = uuid("delivery_resource_id").nullable()
    val providerMessageTs = varchar("provider_message_ts", 64).nullable()
    val threadMessageTs = varchar("thread_message_ts", 64).nullable()
    val cardPayload = text("card_payload")
    val lastError = text("last_error").nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(organizationId, incidentId, ruleKey, teamId, channelId)
        index(false, organizationId, incidentId, desiredVersion)
    }
}
