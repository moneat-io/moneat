// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.announcements

import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Durable per-destination state for activity-aware incident response nudges. */
object NativeIncidentAnnouncementNudges : IntIdTable("native_incident_announcement_nudges") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val ruleKey = varchar("rule_key", 160)
    val teamId = varchar("team_id", 255)
    val channelId = varchar("channel_id", 255)
    val nudgeKey = varchar("nudge_key", 64)
    val dismissedBy = integer("dismissed_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val dismissedAt = timestamp("dismissed_at").nullable()
    val lastShownAt = timestamp("last_shown_at").nullable()
    val lastShownVersion = integer("last_shown_version").nullable()
    val lastActivityAt = timestamp("last_activity_at").nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(organizationId, incidentId, ruleKey, teamId, channelId, nudgeKey)
        index(false, organizationId, incidentId, updatedAt)
    }
}
