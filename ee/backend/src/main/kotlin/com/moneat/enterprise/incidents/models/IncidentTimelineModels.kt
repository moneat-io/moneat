// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.models

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.models.requiredJsonb
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.regexp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

@Serializable
enum class IncidentSourceType(val wire: String) {
    ON_CALL_ALERT("ON_CALL_ALERT"),
    ALERT_EPISODE("ALERT_EPISODE"),
    SLACK_MESSAGE("SLACK_MESSAGE"),
    SOURCE_MESSAGE("SOURCE_MESSAGE"),
    URL("URL"),
}

@Serializable
enum class IncidentTimelineProvenance(val wire: String) {
    INTERNAL("INTERNAL"),
    REST("REST"),
    SLACK("SLACK"),
    INTEGRATION("INTEGRATION"),
    IMPORT("IMPORT"),
    WORKFLOW("WORKFLOW"),
}

@Serializable
enum class IncidentTimelineVisibility(val wire: String) {
    ORGANIZATION("ORGANIZATION"),
    PARTICIPANTS("PARTICIPANTS"),
    PRIVATE("PRIVATE"),
    PUBLIC("PUBLIC"),
}

@Serializable
data class IncidentSourceLink(
    val id: String,
    val sourceType: IncidentSourceType,
    val sourceKey: String,
    val label: String? = null,
    val sourceUrl: String? = null,
    val metadata: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    val createdAt: String,
)

object NativeIncidentSourceLinks : IntIdTable("native_incident_source_links") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val sourceType = varchar("source_type", 32)
    val sourceKey = varchar("source_key", 500)
    val onCallAlertId =
        integer("on_call_alert_id").references(OnCallAlerts.id, onDelete = ReferenceOption.CASCADE).nullable()
    val alertEpisodeId =
        integer("alert_episode_id").references(AlertEpisodes.id, onDelete = ReferenceOption.CASCADE).nullable()
    val label = varchar("label", 500).nullable()
    val sourceUrl = text("source_url").nullable()
    val metadata = requiredJsonb("metadata").clientDefault { emptyMap() }
    val linkedBy = integer("linked_by").references(Users.id)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(incidentId, sourceType, sourceKey)
        check("chk_native_incident_source_type") {
            sourceType regexp INCIDENT_SOURCE_TYPE_PATTERN
        }
        check("chk_native_incident_source_pointer") {
            ((sourceType eq IncidentSourceType.ON_CALL_ALERT.wire) and
                onCallAlertId.isNotNull() and alertEpisodeId.isNull()) or
                ((sourceType eq IncidentSourceType.ALERT_EPISODE.wire) and
                    alertEpisodeId.isNotNull() and onCallAlertId.isNull()) or
                ((sourceType neq IncidentSourceType.ON_CALL_ALERT.wire) and
                    (sourceType neq IncidentSourceType.ALERT_EPISODE.wire) and
                    onCallAlertId.isNull() and alertEpisodeId.isNull())
        }
    }

    private const val INCIDENT_SOURCE_TYPE_PATTERN =
        "^(ON_CALL_ALERT|ALERT_EPISODE|SLACK_MESSAGE|SOURCE_MESSAGE|URL)$"
}

object NativeIncidentTimelineRevisions : IntIdTable("native_incident_timeline_revisions") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val timelineEventId =
        integer("timeline_event_id").references(OnCallIncidentTimeline.id, onDelete = ReferenceOption.CASCADE)
    val revisionNumber = integer("revision_number")
    val action = varchar("action", 24)
    val previousSnapshot = requiredJsonb("previous_snapshot")
    val nextSnapshot = requiredJsonb("next_snapshot")
    val reason = text("reason").nullable()
    val editedBy = integer("edited_by").references(Users.id)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(timelineEventId, revisionNumber)
    }
}
