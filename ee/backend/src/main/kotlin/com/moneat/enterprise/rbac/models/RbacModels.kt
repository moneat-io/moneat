// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.rbac.models

import com.moneat.shared.models.jsonb
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp

// Foreign keys (organization_id, role_id, user_id, created_by) are declared in the Flyway
// migration (V117__rbac_roles.sql), which is the authoritative schema in production. These
// Exposed mappings keep them as plain integers so the tables can be created standalone on
// H2 in unit tests.

/**
 * A named, org-scoped role holding a set of namespaced `"<resource>:<action>"` permission
 * keys. The vocabulary is domain-owned (e.g. `com.moneat.workflows.WorkflowPermissions`);
 * this storage is deliberately generic so one role can carry keys from any domain.
 */
object RbacRoles : IntIdTable("rbac_roles") {
    val organizationId = integer("organization_id")
    val name = varchar("name", 120)

    // No Exposed-level default: the column default lives in the V117 migration; the service
    // always sets this on insert. (Exposed renders jsonb defaults unquoted, breaking H2 DDL.)
    val permissions = jsonb("permissions")
    val createdBy = integer("created_by").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, name)
    }
}

/** Assignment of a role to a user within an organization. */
object RbacRoleAssignments : IntIdTable("rbac_role_assignments") {
    val organizationId = integer("organization_id")
    val roleId = integer("role_id")
    val userId = integer("user_id")
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(organizationId, roleId, userId)
    }
}

// ---------------------------------------------------------------------------
// API DTOs.
// ---------------------------------------------------------------------------

@Serializable
data class CreateRoleRequest(
    val name: String,
    val permissions: List<String> = emptyList()
)

@Serializable
data class UpdateRoleRequest(
    val name: String? = null,
    val permissions: List<String>? = null
)

@Serializable
data class RoleResponse(
    val id: Int,
    val name: String,
    val permissions: List<String>,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class AssignRoleRequest(
    @SerialName("user_id") val userId: Int
)

@Serializable
data class RoleAssignmentResponse(
    val id: Int,
    @SerialName("role_id") val roleId: Int,
    @SerialName("user_id") val userId: Int,
    @SerialName("created_at") val createdAt: String
)
