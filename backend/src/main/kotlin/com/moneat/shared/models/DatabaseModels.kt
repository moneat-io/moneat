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

import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

class TextArrayColumnType : ColumnType<List<String>>() {
    private fun isH2(): Boolean =
        TransactionManager
            .currentOrNull()
            ?.db
            ?.url
            ?.contains("h2", ignoreCase = true) == true

    override fun sqlType(): String = if (isH2()) "VARCHAR ARRAY" else "TEXT[]"

    override fun nonNullValueToString(value: List<String>): String {
        return if (isH2()) {
            "ARRAY[${value.joinToString(",") { "'$it'" }}]"
        } else {
            "ARRAY[${value.joinToString(",") { "'$it'" }}]::TEXT[]"
        }
    }

    override fun valueFromDB(value: Any): List<String> {
        return when (value) {
            is java.sql.Array -> (value.array as Array<*>).filterIsInstance<String>()
            is Array<*> -> value.filterIsInstance<String>()
            is List<*> -> value.filterIsInstance<String>()
            else -> throw IllegalArgumentException("Unexpected value type: ${value::class}")
        }
    }

    override fun notNullValueToDB(value: List<String>): Any {
        return value.toTypedArray()
    }

    override fun setParameter(
        stmt: org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi,
        index: Int,
        value: Any?
    ) {
        if (value == null) {
            stmt.setNull(index, this)
        } else {
            val preparedStatement = stmt as org.jetbrains.exposed.v1.jdbc.statements.jdbc.JdbcPreparedStatementImpl

            @Suppress("UNCHECKED_CAST")
            val array = value as Array<*>
            val connection = preparedStatement.statement.connection
            val sqlArray = connection.createArrayOf(if (isH2()) "VARCHAR" else "text", array)
            preparedStatement.statement.setArray(index, sqlArray)
        }
    }
}

object Users : Table("users") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", 255)
    val password_hash = varchar("password_hash", 255)
    val name = varchar("name", 255).nullable()
    val email_verified = bool("email_verified").default(false)
    val is_admin = bool("is_admin").default(false)
    val email_verification_token = varchar("email_verification_token", 255).nullable()
    val email_verification_expires_at = long("email_verification_expires_at").nullable()
    val password_reset_token = varchar("password_reset_token", 255).nullable()
    val password_reset_expires_at = long("password_reset_expires_at").nullable()
    val onboarding_completed = bool("onboarding_completed").default(false)
    val oauth_provider = varchar("oauth_provider", 20).nullable()
    val oauth_provider_id = varchar("oauth_provider_id", 512).nullable()
    val phone_number = varchar("phone_number", 20).nullable()
    val oncall_phone_opt_in = bool("oncall_phone_opt_in").default(false)
    val oncall_phone_consented_at = timestamp("oncall_phone_consented_at").nullable()
    val oncall_phone_consent_version = varchar("oncall_phone_consent_version", 50).nullable()
    val oncall_phone_consent_ip = varchar("oncall_phone_consent_ip", 45).nullable()
    val oncall_phone_consent_user_agent = text("oncall_phone_consent_user_agent").nullable()
    val oncall_phone_opted_out_at = timestamp("oncall_phone_opted_out_at").nullable()
    val timezone = varchar("timezone", 64).nullable()
    val deletedAt = timestamp("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object OnCallPhoneConsentEvents : Table("oncall_phone_consent_events") {
    val id = integer("id").autoIncrement()
    val user_id = integer("user_id").references(Users.id)
    val phone_number = varchar("phone_number", 20)
    val event_type = varchar("event_type", 20) // OPT_IN, OPT_OUT, PHONE_REMOVED
    val consent_version = varchar("consent_version", 50).nullable()
    val ip_address = varchar("ip_address", 45).nullable()
    val user_agent = text("user_agent").nullable()
    val created_at = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object UserLegalAcceptances : Table("user_legal_acceptances") {
    val id = integer("id").autoIncrement()
    val user_id = integer("user_id").references(Users.id)
    val document_type = varchar("document_type", 20)
    val document_version = varchar("document_version", 32)
    val accepted_at = timestamp("accepted_at")
    val ip_address = varchar("ip_address", 64).nullable()
    val user_agent = text("user_agent").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Organizations : Table("organizations") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255)
    val slug = varchar("slug", 255)
    val company_size = varchar("company_size", 50).nullable()
    val referral_source = varchar("referral_source", 100).nullable()
    val utm_source = varchar("utm_source", 255).nullable()
    val utm_medium = varchar("utm_medium", 255).nullable()
    val utm_campaign = varchar("utm_campaign", 255).nullable()
    val utm_content = varchar("utm_content", 255).nullable()
    val utm_term = varchar("utm_term", 255).nullable()
    val deletedAt = timestamp("deleted_at").nullable()
    val deletedBy = integer("deleted_by").references(Users.id).nullable()
    override val primaryKey = PrimaryKey(id)
}

object Memberships : Table("memberships") {
    val id = integer("id").autoIncrement()
    val user_id = integer("user_id").references(Users.id)
    val organization_id = integer("organization_id").references(Organizations.id)
    val role = varchar("role", 50)
    val sidebar_hidden_items =
        registerColumn<List<String>>(
            "sidebar_hidden_items",
            TextArrayColumnType()
        ).default(emptyList())
    override val primaryKey = PrimaryKey(id)
}

object SidebarPreferenceEvents : Table("sidebar_preference_events") {
    val id = integer("id").autoIncrement()
    val membership_id = integer("membership_id").references(Memberships.id)
    val user_id = integer("user_id").references(Users.id)
    val organization_id = integer("organization_id").references(Organizations.id)
    val hidden_items = registerColumn<List<String>>("hidden_items", TextArrayColumnType())
    val event_source = varchar("source", 32)
    val created_at = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Projects : Table("projects") {
    val id = long("id").autoIncrement()
    val organization_id = integer("organization_id")
    val name = varchar("name", 255)
    val slug = varchar("slug", 255)
    val framework = varchar("framework", 50).nullable()
    override val primaryKey = PrimaryKey(id)
}

object ProjectKeys : Table("project_keys") {
    val id = integer("id").autoIncrement()
    val project_id = long("project_id")
    val public_key = varchar("public_key", 255)
    val secret_key = varchar("secret_key", 255).nullable()
    val platform_target = varchar("platform_target", 50).nullable()
    val is_active = bool("is_active")
    override val primaryKey = PrimaryKey(id)
}

object LogApiKeys : Table("log_api_keys") {
    val id = integer("id").autoIncrement()
    val organization_id = integer("organization_id").references(Organizations.id)
    val name = varchar("name", 255)
    val key_hash = varchar("key_hash", 255)
    val key_prefix = varchar("key_prefix", 12)
    val created_by = integer("created_by").references(Users.id).nullable()
    val created_at = timestamp("created_at")
    val last_used_at = timestamp("last_used_at").nullable()
    val is_active = bool("is_active").default(true)
    override val primaryKey = PrimaryKey(id)
}

object AuthTokens : Table("auth_tokens") {
    val id = integer("id").autoIncrement()
    val user_id = integer("user_id").references(Users.id)
    val token_hash = varchar("token_hash", 64)
    val name = varchar("name", 255)
    val scopes = registerColumn<List<String>>("scopes", TextArrayColumnType())
    val last_used_at = timestamp("last_used_at").nullable()
    val expires_at = timestamp("expires_at").nullable()
    val created_at = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object RefreshTokens : Table("refresh_tokens") {
    val id = integer("id").autoIncrement()
    val user_id = integer("user_id").references(Users.id)
    val token_hash = varchar("token_hash", 255)
    val expires_at = long("expires_at")
    val created_at = long("created_at")
    val last_used_at = long("last_used_at").nullable()
    val revoked = bool("revoked").default(false)
    override val primaryKey = PrimaryKey(id)
}

object Releases : Table("releases") {
    val id = integer("id").autoIncrement()
    val project_id = long("project_id").references(Projects.id)
    val version = varchar("version", 255)
    val ref = varchar("ref", 255).nullable()
    val created_at = long("created_at")
    val first_seen = long("first_seen").nullable()
    val last_seen = long("last_seen").nullable()
    val event_count = long("event_count").default(0)
    val is_auto_detected = bool("is_auto_detected").default(false)
    override val primaryKey = PrimaryKey(id)
}

object ReleaseFiles : Table("release_files") {
    val id = integer("id").autoIncrement()
    val release_id = integer("release_id").references(Releases.id)
    val name = varchar("name", 500)
    val file_path = varchar("file_path", 1000).nullable()
    val storage_path = varchar("storage_path", 1000).nullable()
    val file_type = varchar("file_type", 50).nullable()
    val created_at = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object FileBlobs : Table("file_blobs") {
    val id = integer("id").autoIncrement()
    val checksum = varchar("checksum", 40)
    val size = long("size").default(0)
    val storage_path = varchar("storage_path", 500)
    val created_at = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ArtifactBundles : Table("artifact_bundles") {
    val id = integer("id").autoIncrement()
    val organization_id = integer("organization_id").references(Organizations.id)
    val checksum = varchar("checksum", 40)
    val state = varchar("state", 20).default("created")
    val detail = text("detail").nullable()
    val version = varchar("version", 255).nullable()
    val dist = varchar("dist", 255).nullable()
    val storage_path = varchar("storage_path", 500).nullable()
    val created_at = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object UsageRecords : Table("usage_records") {
    val id = integer("id").autoIncrement()
    val organization_id = integer("organization_id").references(Organizations.id)
    val project_id = integer("project_id").nullable() // FK to projects(id) in DB
    val event_type = varchar("event_type", 50).default("error")
    val event_count = integer("event_count").default(0)
    val bytes_ingested = long("bytes_ingested").default(0)
    val recordDate = date("date")
    override val primaryKey = PrimaryKey(id)
}

object Subscriptions : Table("subscriptions") {
    val id = integer("id").autoIncrement()
    val organization_id = integer("organization_id").references(Organizations.id)
    val stripe_subscription_id = varchar("stripe_subscription_id", 255).nullable()
    val stripe_customer_id = varchar("stripe_customer_id", 255).nullable()
    val plan = varchar("plan", 50)
    val status = varchar("status", 50)
    val billing_interval = varchar("billing_interval", 20).default("monthly")
    val current_period_start = timestamp("current_period_start").nullable()
    val current_period_end = timestamp("current_period_end").nullable()
    val pricing_tier_config_id = integer("pricing_tier_config_id").nullable()
    val payg_budget_cents = integer("payg_budget_cents").default(0)
    val payg_used_units = long("payg_used_units").default(0)
    val payg_used_micros = long("payg_used_micros").default(0)
    val pending_meter_units = long("pending_meter_units").default(0)
    val pending_meter_batch_id = varchar("pending_meter_batch_id", 255).nullable()
    val pending_meter_batch_units = long("pending_meter_batch_units").default(0)
    val stripe_base_item_id = varchar("stripe_base_item_id", 255).nullable()
    val stripe_overage_item_id = varchar("stripe_overage_item_id", 255).nullable()
    val stripe_oncall_item_id = varchar("stripe_oncall_item_id", 255).nullable()
    val oncall_seats = integer("oncall_seats").default(0)
    val billing_grace_until = timestamp("billing_grace_until").nullable()
    val bonus_gb_bytes = long("bonus_gb_bytes").default(0)
    val bonus_units = long("bonus_units").default(0)
    val bonus_granted_at = timestamp("bonus_granted_at").nullable()
    val bonus_granted_by = integer("bonus_granted_by").references(Users.id).nullable()
    val bonus_reason = varchar("bonus_reason", 500).nullable()
    override val primaryKey = PrimaryKey(id)
}

object NotificationPreferences : Table("notification_preferences") {
    val id = integer("id").autoIncrement()
    val user_id = integer("user_id").references(Users.id)
    val project_id = long("project_id").references(Projects.id).nullable()
    val issue_alerts = bool("issue_alerts").default(true)
    val error_alerts = bool("error_alerts").default(true)
    val weekly_summary = bool("weekly_summary").default(true)
    val alert_frequency_minutes = integer("alert_frequency_minutes").default(30)
    val created_at = timestamp("created_at").nullable()
    val updated_at = timestamp("updated_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object AlertNotificationPreferences : Table("alert_notification_preferences") {
    val id = integer("id").autoIncrement()
    val user_id = integer("user_id").references(Users.id)
    val organization_id = integer("organization_id").references(Organizations.id)
    val alert_source = varchar("alert_source", 50)
    val email_enabled = bool("email_enabled").default(true)
    val slack_enabled = bool("slack_enabled").default(true)
    val discord_enabled = bool("discord_enabled").default(true)
    val created_at = timestamp("created_at")
    val updated_at = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object EmailsSent : Table("emails_sent") {
    val id = integer("id").autoIncrement()
    val organization_id = integer("organization_id").references(Organizations.id).nullable()
    val email_type = varchar("email_type", 50)
    val recipient = varchar("recipient", 255)
    val sent_at = timestamp("sent_at")
    val success = bool("success").default(true)
    override val primaryKey = PrimaryKey(id)
}

object PromotionalCreditGrants : Table("promotional_credit_grants") {
    val id = integer("id").autoIncrement()
    val organization_id = integer("organization_id").references(Organizations.id)
    val subscription_id = integer("subscription_id").references(Subscriptions.id).nullable()
    val granted_by = integer("granted_by").references(Users.id)
    val bonus_gb_bytes = long("bonus_gb_bytes").default(0)
    val bonus_units = long("bonus_units").default(0)
    val reason = varchar("reason", 500).nullable()
    val granted_at = timestamp("granted_at")
    override val primaryKey = PrimaryKey(id)
}

object Systems : Table("systems") {
    val id = javaUUID("id")
    val organization_id = integer("organization_id").references(Organizations.id)
    val name = varchar("name", 255)
    val host = varchar("host", 255).nullable()
    val agent_key_hash = varchar("agent_key_hash", 255)
    val status = varchar("status", 20)
    val last_seen_at = timestamp("last_seen_at").nullable()
    val agent_version = varchar("agent_version", 20).nullable()
    val os = varchar("os", 100).nullable()
    val arch = varchar("arch", 20).nullable()
    val created_at = timestamp("created_at")
    val updated_at = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object SystemAlerts : Table("system_alerts") {
    val id = integer("id").autoIncrement()
    val system_id = javaUUID("system_id").references(Systems.id)
    val organization_id = integer("organization_id").references(Organizations.id)
    val metric = varchar("metric", 50)
    val condition = varchar("condition", 20)
    val threshold = double("threshold")
    val duration_seconds = integer("duration_seconds")
    val enabled = bool("enabled")
    val last_triggered_at = timestamp("last_triggered_at").nullable()
    val incident_severity = varchar("incident_severity", 20).nullable()
    val created_at = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object OrganizationAlertTemplates : Table("organization_alert_templates") {
    val id = integer("id").autoIncrement()
    val organization_id = integer("organization_id").references(Organizations.id)
    val metric = varchar("metric", 50)
    val condition = varchar("condition", 20)
    val threshold = double("threshold")
    val duration_seconds = integer("duration_seconds").default(0)
    val enabled = bool("enabled").default(false)
    val incident_severity = varchar("incident_severity", 20).nullable()
    val created_at = timestamp("created_at")
    val updated_at = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object SystemAlertSettings : Table("system_alert_settings") {
    val system_id = javaUUID("system_id").references(Systems.id)
    val organization_id = integer("organization_id").references(Organizations.id)
    val scope = varchar("scope", 20).default("system")
    val updated_at = timestamp("updated_at")
    override val primaryKey = PrimaryKey(system_id)
}

object SystemAlertTemplateStates : Table("system_alert_template_states") {
    val template_alert_id = integer("template_alert_id").references(OrganizationAlertTemplates.id)
    val system_id = javaUUID("system_id").references(Systems.id)
    val last_triggered_at = timestamp("last_triggered_at").nullable()
    override val primaryKey = PrimaryKey(template_alert_id, system_id)
}

object AlertSilencePeriods : Table("alert_silence_periods") {
    val id = integer("id").autoIncrement()
    val organization_id = integer("organization_id").references(Organizations.id)
    val reason = varchar("reason", 255).nullable()
    val starts_at = timestamp("starts_at")
    val ends_at = timestamp("ends_at")
    val created_by = integer("created_by").references(Users.id)
    val created_at = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object OrganizationIntegrations : Table("organization_integrations") {
    val id = integer("id").autoIncrement()
    val organization_id = integer("organization_id").references(Organizations.id)
    val integration_type = varchar("integration_type", 50)

    // OAuth data (for Slack App)
    val access_token = text("access_token").nullable()
    val bot_user_id = varchar("bot_user_id", 255).nullable()
    val team_id = varchar("team_id", 255).nullable()
    val team_name = varchar("team_name", 255).nullable()

    // Channel configuration
    val channel_id = varchar("channel_id", 255).nullable()
    val channel_name = varchar("channel_name", 255).nullable()

    // Legacy webhook support (deprecated)
    val webhook_url = text("webhook_url").nullable()

    val enabled = bool("enabled").default(true)
    val created_at = timestamp("created_at")
    val updated_at = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object OrgInvitations : Table("org_invitations") {
    val id = integer("id").autoIncrement()
    val organization_id = integer("organization_id").references(Organizations.id)
    val email = varchar("email", 255)
    val role = varchar("role", 50).default("member")
    val invited_by = integer("invited_by").references(Users.id)
    val token = varchar("token", 255)
    val status = varchar("status", 20).default("pending")
    val expires_at = long("expires_at")
    val created_at = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
