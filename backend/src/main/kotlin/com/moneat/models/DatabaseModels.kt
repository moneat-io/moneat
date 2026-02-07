package com.moneat.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

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
    override val primaryKey = PrimaryKey(id)
}

object Organizations : Table("organizations") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255)
    val slug = varchar("slug", 255)
    val company_size = varchar("company_size", 50).nullable()
    override val primaryKey = PrimaryKey(id)
}

object Memberships : Table("memberships") {
    val id = integer("id").autoIncrement()
    val user_id = integer("user_id").references(Users.id)
    val organization_id = integer("organization_id").references(Organizations.id)
    val role = varchar("role", 50)
    override val primaryKey = PrimaryKey(id)
}

object Projects : Table("projects") {
    val id = long("id").autoIncrement()
    val organization_id = integer("organization_id")
    val name = varchar("name", 255)
    val slug = varchar("slug", 255)
    val platform = varchar("platform", 50).nullable()
    override val primaryKey = PrimaryKey(id)
}

object ProjectKeys : Table("project_keys") {
    val id = integer("id").autoIncrement()
    val project_id = long("project_id")
    val public_key = varchar("public_key", 255)
    val secret_key = varchar("secret_key", 255).nullable()
    val is_active = bool("is_active")
    override val primaryKey = PrimaryKey(id)
}

object AuthTokens : Table("auth_tokens") {
    val id = integer("id").autoIncrement()
    val user_id = integer("user_id").references(Users.id)
    val token_hash = varchar("token_hash", 64)
    val name = varchar("name", 255)
    val scopes = text("scopes")
    val last_used_at = timestamp("last_used_at").nullable()
    val expires_at = timestamp("expires_at").nullable()
    val created_at = timestamp("created_at")
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

object UsageRecords : Table("usage_records") {
    val id = integer("id").autoIncrement()
    val organization_id = integer("organization_id").references(Organizations.id)
    val project_id = integer("project_id").nullable()  // FK to projects(id) in DB
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
    val current_period_start = timestamp("current_period_start").nullable()
    val current_period_end = timestamp("current_period_end").nullable()
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

object EmailsSent : Table("emails_sent") {
    val id = integer("id").autoIncrement()
    val organization_id = integer("organization_id").references(Organizations.id).nullable()
    val email_type = varchar("email_type", 50)
    val recipient = varchar("recipient", 255)
    val sent_at = timestamp("sent_at")
    val success = bool("success").default(true)
    override val primaryKey = PrimaryKey(id)
}
