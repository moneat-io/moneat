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

package com.moneat.featureflags.models

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.shared.models.jsonb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

object FeatureFlagEnvironments : Table("feature_flag_environments") {
    val id = integer("id").autoIncrement()
    val organizationId = integer("organization_id").references(Organizations.id)
    val key = varchar("key", 64)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val version = integer("version").default(1)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object FeatureFlags : Table("feature_flags") {
    val id = integer("id").autoIncrement()
    val organizationId = integer("organization_id").references(Organizations.id)
    val key = varchar("key", 255)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val valueType = varchar("value_type", 32)
    val clientVisible = bool("client_visible").default(false)
    val tags = jsonb("tags").default("[]")
    val createdBy = integer("created_by").references(Users.id).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val archivedAt = timestamp("archived_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object FeatureFlagVariants : Table("feature_flag_variants") {
    val id = integer("id").autoIncrement()
    val flagId = integer("flag_id").references(FeatureFlags.id)
    val key = varchar("key", 255)
    val name = varchar("name", 255)
    val valueJson = jsonb("value_json")
    val sortOrder = integer("sort_order").default(0)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object FeatureFlagEnvironmentConfigs : Table("feature_flag_environment_configs") {
    val id = integer("id").autoIncrement()
    val flagId = integer("flag_id").references(FeatureFlags.id)
    val environmentId = integer("environment_id").references(FeatureFlagEnvironments.id)
    val enabled = bool("enabled").default(false)
    val defaultVariantKey = varchar("default_variant_key", 255).nullable()
    val offVariantKey = varchar("off_variant_key", 255).nullable()
    val rulesJson = jsonb("rules_json").default("{\"rules\":[]}")
    val version = integer("version").default(1)
    val updatedBy = integer("updated_by").references(Users.id).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object FeatureFlagSegments : Table("feature_flag_segments") {
    val id = integer("id").autoIncrement()
    val organizationId = integer("organization_id").references(Organizations.id)
    val key = varchar("key", 255)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val conditionsJson = jsonb("conditions_json").default("{\"all\":[]}")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val archivedAt = timestamp("archived_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object FeatureFlagSdkKeys : Table("feature_flag_sdk_keys") {
    val id = integer("id").autoIncrement()
    val organizationId = integer("organization_id").references(Organizations.id)
    val environmentId = integer("environment_id").references(FeatureFlagEnvironments.id)
    val name = varchar("name", 255)
    val keyType = varchar("key_type", 16)
    val keyHash = varchar("key_hash", 255)
    val keyPrefix = varchar("key_prefix", 16)
    val createdBy = integer("created_by").references(Users.id).nullable()
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    val revokedAt = timestamp("revoked_at").nullable()
    val isActive = bool("is_active").default(true)
    override val primaryKey = PrimaryKey(id)
}

object FeatureFlagAuditEvents : Table("feature_flag_audit_events") {
    val id = integer("id").autoIncrement()
    val organizationId = integer("organization_id").references(Organizations.id)
    val environmentId = integer("environment_id").references(FeatureFlagEnvironments.id).nullable()
    val flagId = integer("flag_id").references(FeatureFlags.id).nullable()
    val actorUserId = integer("actor_user_id").references(Users.id).nullable()
    val eventType = varchar("event_type", 64)
    val beforeJson = jsonb("before_json").nullable()
    val afterJson = jsonb("after_json").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
