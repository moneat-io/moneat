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
    override val primaryKey = PrimaryKey(id)
}

object Organizations : Table("organizations") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255)
    val slug = varchar("slug", 255)
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
