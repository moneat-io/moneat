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

package com.moneat.models

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.Clock

/**
 * Exposed table objects for on-call/enterprise database tables.
 * These table definitions live in core so that core code (e.g., billing,
 * integrations) can query on-call tables without depending on the
 * enterprise module. The enterprise module uses these same objects.
 *
 * The actual DDL migrations are in core (db/migration/) as well.
 */

object EscalationPolicies : IntIdTable("escalation_policies") {
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val repeatCount = integer("repeat_count")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object SlackUserMappings : IntIdTable("slack_user_mappings") {
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val slackUserId = varchar("slack_user_id", 100)
    val slackTeamId = varchar("slack_team_id", 100)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object SsoConfigurations : Table("sso_configurations") {
    val id = integer("id").autoIncrement()
    val organizationId = integer("organization_id").references(Organizations.id)
    val providerType = varchar("provider_type", 10)
    val isEnabled = bool("is_enabled").default(false)
    val idpEntityId = varchar("idp_entity_id", 512).nullable()
    val idpSsoUrl = varchar("idp_sso_url", 1024).nullable()
    val idpCertificate = text("idp_certificate").nullable()
    val spEntityId = varchar("sp_entity_id", 512).nullable()
    val oidcIssuerUrl = varchar("oidc_issuer_url", 1024).nullable()
    val oidcClientId = varchar("oidc_client_id", 256).nullable()
    val oidcClientSecret = varchar("oidc_client_secret", 512).nullable()
    val emailDomain = varchar("email_domain", 256).nullable()
    val requireSso = bool("require_sso").default(false)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(id)
}

object UserSsoLinks : Table("user_sso_links") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id)
    val ssoConfigurationId = integer("sso_configuration_id").references(SsoConfigurations.id)
    val externalId = varchar("external_id", 512)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(id)
}

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
