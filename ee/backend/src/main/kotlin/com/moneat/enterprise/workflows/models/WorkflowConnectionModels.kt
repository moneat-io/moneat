// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.models

import com.moneat.shared.models.jsonb
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp

// Foreign keys (organization_id, created_by) are declared in the Flyway migration
// (V115__workflow_connections.sql), which is the authoritative schema in production.
// These Exposed mappings keep org/createdBy as plain integers so the tables can be
// created standalone on H2 in unit tests.

/**
 * Vaulted third-party connection. `encrypted_credentials` holds the envelope
 * ciphertext (see ConnectionCredentialCipher); plaintext is never stored. Only
 * `last_four` is retained in the clear for display.
 */
object WorkflowConnections : IntIdTable("workflow_connections") {
    val organizationId = integer("organization_id")
    val type = varchar("type", 64)
    val name = varchar("name", 255)

    // No Exposed-level default: the column default lives in the V115 migration; the
    // service always sets this on insert. (Exposed renders jsonb defaults unquoted,
    // which breaks H2 DDL in tests.)
    val identifierTags = jsonb("identifier_tags")
    val encryptedCredentials = text("encrypted_credentials")
    val keyId = varchar("key_id", 64)
    val lastFour = varchar("last_four", 8).nullable()
    val createdBy = integer("created_by").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, name)
    }
}

/**
 * A named group of connections of the same type. At run time the vault resolves the
 * group to one concrete connection by matching member identifier tags against the
 * run scope (see `selection_rule`).
 */
object WorkflowConnectionGroups : IntIdTable("workflow_connection_groups") {
    val organizationId = integer("organization_id")
    val name = varchar("name", 255)
    val connectionType = varchar("connection_type", 64)
    val memberConnectionIds = jsonb("member_connection_ids")
    val selectionRule = jsonb("selection_rule")
    val createdBy = integer("created_by").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, name)
    }
}

/** Persisted selection rule for a connection group. */
@Serializable
data class ConnectionSelectionRule(
    val strategy: String = "first_match"
)

// ---------------------------------------------------------------------------
// API DTOs — secrets are accepted on write but NEVER returned on read.
// ---------------------------------------------------------------------------

@Serializable
data class CreateConnectionRequest(
    val type: String,
    val name: String,
    @SerialName("identifier_tags") val identifierTags: Map<String, String> = emptyMap(),
    /** Entered once; immediately encrypted and never echoed back. */
    val secret: String
)

@Serializable
data class RotateConnectionRequest(
    val secret: String
)

@Serializable
data class ConnectionResponse(
    val id: Int,
    val type: String,
    val name: String,
    @SerialName("identifier_tags") val identifierTags: Map<String, String>,
    @SerialName("last_four") val lastFour: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreateConnectionGroupRequest(
    val name: String,
    @SerialName("connection_type") val connectionType: String,
    @SerialName("member_connection_ids") val memberConnectionIds: List<Int> = emptyList(),
    @SerialName("selection_strategy") val selectionStrategy: String = "first_match"
)

@Serializable
data class ConnectionGroupResponse(
    val id: Int,
    val name: String,
    @SerialName("connection_type") val connectionType: String,
    @SerialName("member_connection_ids") val memberConnectionIds: List<Int>,
    @SerialName("selection_strategy") val selectionStrategy: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)
