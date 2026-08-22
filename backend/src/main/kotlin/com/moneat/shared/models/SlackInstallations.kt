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
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.Clock
import kotlin.uuid.Uuid

object SlackInstallations : Table("slack_installations") {
    val id = integer("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(
        Organizations.id,
        onDelete = ReferenceOption.CASCADE,
    )
    val legacyIntegrationId = integer("legacy_integration_id").references(
        OrganizationIntegrations.id,
        onDelete = ReferenceOption.SET_NULL,
    ).nullable()
    val teamId = varchar("team_id", 100).nullable()
    val teamName = varchar("team_name", 255).nullable()
    val enterpriseId = varchar("enterprise_id", 100).nullable()
    val enterpriseName = varchar("enterprise_name", 255).nullable()
    val isEnterpriseInstall = bool("is_enterprise_install").default(false)
    val appId = varchar("app_id", 100).nullable()
    val enabledCapabilities = text("enabled_capabilities").default("")
    val defaultChannelId = varchar("default_channel_id", 255).nullable()
    val defaultChannelName = varchar("default_channel_name", 255).nullable()
    val isDefault = bool("is_default").default(false)
    val enabled = bool("enabled").default(true)
    val healthStatus = varchar("health_status", 40).default("REAUTHORIZATION_REQUIRED")
    val healthDetail = varchar("health_detail", 500).nullable()
    val lastVerifiedAt = timestamp("last_verified_at").nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(organizationId, resourceId)
    }
}

object SlackWorkspaceBindings : Table("slack_workspace_bindings") {
    val id = integer("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(
        Organizations.id,
        onDelete = ReferenceOption.CASCADE,
    )
    val slackInstallationId = integer("slack_installation_id").references(
        SlackInstallations.id,
        onDelete = ReferenceOption.CASCADE,
    )
    val teamId = varchar("team_id", 100)
    val teamName = varchar("team_name", 255).nullable()
    val enterpriseId = varchar("enterprise_id", 100).nullable()
    val isPrimary = bool("is_primary").default(false)
    val enabled = bool("enabled").default(true)
    val healthStatus = varchar("health_status", 40).default("REAUTHORIZATION_REQUIRED")
    val healthDetail = varchar("health_detail", 500).nullable()
    val lastVerifiedAt = timestamp("last_verified_at").nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(organizationId, teamId)
    }
}

object SlackInstallationGrants : Table("slack_installation_grants") {
    val id = integer("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(
        Organizations.id,
        onDelete = ReferenceOption.CASCADE,
    )
    val slackInstallationId = integer("slack_installation_id").references(
        SlackInstallations.id,
        onDelete = ReferenceOption.CASCADE,
    )
    val grantType = varchar("grant_type", 20)
    val slackUserId = varchar("slack_user_id", 100).nullable()
    val tokenType = varchar("token_type", 40).nullable()
    val accessTokenCiphertext = text("access_token_ciphertext")
    val accessTokenKeyId = varchar("access_token_key_id", 100)
    val refreshTokenCiphertext = text("refresh_token_ciphertext").nullable()
    val refreshTokenKeyId = varchar("refresh_token_key_id", 100).nullable()
    val grantedScopes = text("granted_scopes").default("")
    val expiresAt = timestamp("expires_at").nullable()
    val rotatedAt = timestamp("rotated_at").nullable()
    val revokedAt = timestamp("revoked_at").nullable()
    val healthStatus = varchar("health_status", 40).default("REAUTHORIZATION_REQUIRED")
    val healthDetail = varchar("health_detail", 500).nullable()
    val lastVerifiedAt = timestamp("last_verified_at").nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(organizationId, resourceId)
    }
}
