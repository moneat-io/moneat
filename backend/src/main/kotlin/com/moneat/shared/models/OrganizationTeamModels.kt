// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.moneat.shared.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.Clock
import kotlin.uuid.Uuid

object OrganizationTeams : IntIdTable("organization_teams") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 200)
    val slug = varchar("slug", 120)
    val description = text("description").nullable()
    val slackChannel = varchar("slack_channel", 200).nullable()
    val repository = varchar("repository", 300).nullable()
    val onCallScheduleId =
        integer("on_call_schedule_id").references(OnCallSchedules.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val escalationPolicyId =
        integer("escalation_policy_id").references(EscalationPolicies.id, onDelete = ReferenceOption.SET_NULL)
            .nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(organizationId, slug)
    }
}

object OrganizationTeamMembers : IntIdTable("organization_team_members") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val teamId = integer("team_id").references(OrganizationTeams.id, onDelete = ReferenceOption.CASCADE)
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }

    init {
        uniqueIndex(teamId, userId)
    }
}
