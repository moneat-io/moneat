package com.moneat.models

import org.jetbrains.exposed.sql.Table

object Users : Table("users") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", 255)
    val password_hash = varchar("password_hash", 255)
    val name = varchar("name", 255).nullable()
    val email_verified = bool("email_verified").default(false)
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
    val last_used_at = long("last_used_at").nullable()
    val expires_at = long("expires_at").nullable()
    val created_at = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Releases : Table("releases") {
    val id = integer("id").autoIncrement()
    val project_id = long("project_id").references(Projects.id)
    val version = varchar("version", 255)
    val ref = varchar("ref", 255).nullable()
    val created_at = long("created_at")
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
