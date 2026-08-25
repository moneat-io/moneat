// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.slack

import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.Organizations
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.Clock
import kotlin.uuid.Uuid

enum class IncidentSlackChannelState(val wire: String) {
    CHANNELLESS("CHANNELLESS"),
    PROVISIONING("PROVISIONING"),
    ACTIVE("ACTIVE"),
    ARCHIVED("ARCHIVED"),
    FAILED("FAILED"),
}

/** Desired and observed Slack channel state for one incident/workspace binding. */
object NativeIncidentSlackChannels : IntIdTable("native_incident_slack_channels") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val teamId = varchar("team_id", 255)
    val channelId = varchar("channel_id", 255).nullable()
    val channelName = varchar("channel_name", 80).nullable()
    val state = varchar("state", 24).default(IncidentSlackChannelState.CHANNELLESS.wire)
    val isPrivate = bool("is_private").default(false)
    val desiredVersion = integer("desired_version").default(1)
    val deliveryResourceId = uuid("delivery_resource_id").nullable()
    val topic = varchar("topic", 2_000).nullable()
    val bookmarks = text("bookmarks").nullable()
    val lastError = text("last_error").nullable()
    val archivedAt = timestamp("archived_at").nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(organizationId, incidentId, teamId)
        index(false, organizationId, incidentId, state)
    }
}
