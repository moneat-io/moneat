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
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.javatime.time
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Exposed table objects for on-call/enterprise database tables.
 * These table definitions live in core so that core code (e.g., billing,
 * integrations) can query on-call tables without depending on the
 * enterprise module. The enterprise module uses these same objects.
 *
 * The actual DDL migrations are in core (db/migration/) as well.
 */

object EscalationPolicies : IntIdTable("escalation_policies") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val repeatCount = integer("repeat_count")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object EscalationPolicyAlertSources : IntIdTable("escalation_policy_alert_sources") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val alertSource = varchar("alert_source", 50)
    val escalationPolicyId = integer(
        "escalation_policy_id"
    ).references(EscalationPolicies.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
}

object SlackUserMappings : IntIdTable("slack_user_mappings") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val slackUserId = varchar("slack_user_id", 100)
    val slackTeamId = varchar("slack_team_id", 100)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object SsoConfigurations : Table("sso_configurations") {
    val id = integer("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
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
    val emailDomainVerified = bool("email_domain_verified").default(false)
    val emailDomainVerificationToken = varchar("email_domain_verification_token", 128).nullable()
    val emailDomainVerifiedAt = timestamp("email_domain_verified_at").nullable()
    val emailDomainVerifiedBy = integer("email_domain_verified_by").references(Users.id).nullable()
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
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val rotationType = varchar("rotation_type", 20)
    val handoffTime = time("handoff_time")
    val timezone = varchar("timezone", 100)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object OnCallParticipants : IntIdTable("on_call_participants") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val scheduleId = integer("schedule_id").references(OnCallSchedules.id, onDelete = ReferenceOption.CASCADE)
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    val createdAt = timestamp("created_at")
}

object OnCallScheduleLayers : IntIdTable("on_call_schedule_layers") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val scheduleId = integer("schedule_id").references(OnCallSchedules.id, onDelete = ReferenceOption.CASCADE)
    val layerOrder = integer("layer_order")
    val name = varchar("name", 255)
    val rotationType = varchar("rotation_type", 20)
    val handoffTime = time("handoff_time")
    val timezone = varchar("timezone", 100)
    val enabled = bool("enabled").default(true)
    val explicitGap = bool("explicit_gap").default(false)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object OnCallScheduleLayerParticipants : IntIdTable("on_call_schedule_layer_participants") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val layerId = integer("layer_id").references(OnCallScheduleLayers.id, onDelete = ReferenceOption.CASCADE)
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    val createdAt = timestamp("created_at")
}

object OnCallIncidents : IntIdTable("on_call_incidents") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
}

object UserNotificationChannelPreferences : IntIdTable("user_notification_channel_preferences") {
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val category = varchar("category", 32)
    val channel = varchar("channel", 32)
    val enabled = bool("enabled").default(true)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }

    init {
        uniqueIndex(userId, organizationId, category, channel)
    }
}

object ShiftChangeNotificationsSent : IntIdTable("shift_change_notifications_sent") {
    val userId = integer("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val scheduleId = integer("schedule_id").references(OnCallSchedules.id, onDelete = ReferenceOption.CASCADE)
    val shiftStartAt = timestamp("shift_start_at")
    val channel = varchar("channel", 32)
    val sentAt = timestamp("sent_at").clientDefault { Clock.System.now() }

    init {
        uniqueIndex(userId, scheduleId, shiftStartAt, channel)
    }
}
