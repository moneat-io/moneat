// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.rbac.services

import com.moneat.enterprise.rbac.models.RbacRoleAssignments
import com.moneat.enterprise.rbac.models.RbacRoles
import com.moneat.enterprise.rbac.models.RoleAssignmentResponse
import com.moneat.enterprise.rbac.models.RoleResponse
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
private const val MAX_ROLE_NAME_LENGTH = 120

/**
 * Postgres-backed RBAC store. Resolves the cross-cutting [com.moneat.authz.PermissionBridge]
 * and manages roles + assignments. Every query is scoped by `organizationId` from the
 * authenticated caller, so roles and assignments never cross tenant boundaries.
 *
 * Resolution is additive: a user with at least one role assignment is governed strictly by
 * the union of those roles' permission keys; a user with no assignment resolves to null so
 * the caller falls back to the coarse org-role gates.
 */
class RbacService {

    private val json = Json { ignoreUnknownKeys = true }

    // --- PermissionBridge resolution (synchronous; the bridge interface is non-suspend) ---

    fun resolvePermissions(organizationId: Int, userId: Int): Set<String>? =
        transaction {
            val roleIds =
                RbacRoleAssignments
                    .selectAll()
                    .where {
                        (RbacRoleAssignments.organizationId eq organizationId) and
                            (RbacRoleAssignments.userId eq userId)
                    }
                    .map { it[RbacRoleAssignments.roleId] }
            if (roleIds.isEmpty()) {
                // No granular assignment — defer to the coarse ADMIN/MEMBER role gates.
                return@transaction null
            }
            RbacRoles
                .selectAll()
                .where {
                    (RbacRoles.organizationId eq organizationId) and
                        (RbacRoles.id inList roleIds)
                }
                .flatMap { json.decodeFromString<List<String>>(it[RbacRoles.permissions]) }
                .toSet()
        }

    fun hasPermission(organizationId: Int, userId: Int, permission: String): Boolean? =
        resolvePermissions(organizationId, userId)?.contains(permission)

    // --- Role CRUD ---

    fun listRoles(organizationId: Int): List<RoleResponse> =
        transaction {
            RbacRoles
                .selectAll()
                .where { RbacRoles.organizationId eq organizationId }
                .map { it.toRoleResponse() }
        }

    fun getRole(organizationId: Int, roleId: Int): RoleResponse? =
        transaction { findRoleRow(organizationId, roleId)?.toRoleResponse() }

    fun createRole(
        organizationId: Int,
        name: String,
        permissions: List<String>,
        createdBy: Int?
    ): RoleResponse {
        val normalizedName = normalizeRoleName(name)
        val normalizedPermissions = normalizePermissions(permissions)
        val now = Clock.System.now()
        val newId =
            try {
                transaction {
                    RbacRoles.insertAndGetId {
                        it[RbacRoles.organizationId] = organizationId
                        it[RbacRoles.name] = normalizedName
                        it[RbacRoles.permissions] = json.encodeToString(normalizedPermissions)
                        it[RbacRoles.createdBy] = createdBy
                        it[RbacRoles.createdAt] = now
                        it[RbacRoles.updatedAt] = now
                    }.value
                }
            } catch (e: ExposedSQLException) {
                throw duplicateNameOr(e, normalizedName)
            }
        return checkNotNull(getRole(organizationId, newId))
    }

    fun updateRole(
        organizationId: Int,
        roleId: Int,
        name: String?,
        permissions: List<String>?
    ): RoleResponse? {
        val normalizedName = name?.let { normalizeRoleName(it) }
        val normalizedPermissions = permissions?.let { normalizePermissions(it) }
        val now = Clock.System.now()
        val updated =
            try {
                transaction {
                    RbacRoles.update({
                        (RbacRoles.id eq roleId) and (RbacRoles.organizationId eq organizationId)
                    }) {
                        if (normalizedName != null) it[RbacRoles.name] = normalizedName
                        if (normalizedPermissions != null) {
                            it[RbacRoles.permissions] = json.encodeToString(normalizedPermissions)
                        }
                        it[RbacRoles.updatedAt] = now
                    }
                }
            } catch (e: ExposedSQLException) {
                throw duplicateNameOr(e, normalizedName ?: name.orEmpty())
            }
        return if (updated > 0) getRole(organizationId, roleId) else null
    }

    fun deleteRole(organizationId: Int, roleId: Int): Boolean =
        transaction {
            RbacRoles.deleteWhere {
                (RbacRoles.id eq roleId) and (RbacRoles.organizationId eq organizationId)
            } > 0
        }

    // --- Assignments ---

    fun listAssignments(organizationId: Int, roleId: Int): List<RoleAssignmentResponse> =
        transaction {
            RbacRoleAssignments
                .selectAll()
                .where {
                    (RbacRoleAssignments.organizationId eq organizationId) and
                        (RbacRoleAssignments.roleId eq roleId)
                }
                .map { it.toAssignmentResponse() }
        }

    /**
     * Assign [roleId] to [userId]. Returns null when the role does not exist in the
     * organization. Idempotent: an existing assignment is returned unchanged.
     */
    fun assignRole(organizationId: Int, roleId: Int, userId: Int): RoleAssignmentResponse? =
        transaction {
            if (findRoleRow(organizationId, roleId) == null) {
                return@transaction null
            }
            try {
                val id =
                    RbacRoleAssignments.insertAndGetId {
                        it[RbacRoleAssignments.organizationId] = organizationId
                        it[RbacRoleAssignments.roleId] = roleId
                        it[RbacRoleAssignments.userId] = userId
                        it[RbacRoleAssignments.createdAt] = Clock.System.now()
                    }.value
                findAssignmentRow(organizationId, id)?.toAssignmentResponse()
            } catch (e: ExposedSQLException) {
                if (e.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                    findAssignmentRow(organizationId, roleId, userId)?.toAssignmentResponse()
                } else {
                    throw e
                }
            }
        }

    fun unassignRole(organizationId: Int, roleId: Int, userId: Int): Boolean =
        transaction {
            RbacRoleAssignments.deleteWhere {
                (RbacRoleAssignments.organizationId eq organizationId) and
                    (RbacRoleAssignments.roleId eq roleId) and
                    (RbacRoleAssignments.userId eq userId)
            } > 0
        }

    // --- Helpers ---

    private fun findRoleRow(organizationId: Int, roleId: Int): ResultRow? =
        RbacRoles
            .selectAll()
            .where { (RbacRoles.id eq roleId) and (RbacRoles.organizationId eq organizationId) }
            .firstOrNull()

    private fun findAssignmentRow(organizationId: Int, assignmentId: Int): ResultRow? =
        RbacRoleAssignments
            .selectAll()
            .where {
                (RbacRoleAssignments.id eq assignmentId) and
                    (RbacRoleAssignments.organizationId eq organizationId)
            }
            .firstOrNull()

    private fun findAssignmentRow(organizationId: Int, roleId: Int, userId: Int): ResultRow? =
        RbacRoleAssignments
            .selectAll()
            .where {
                (RbacRoleAssignments.organizationId eq organizationId) and
                    (RbacRoleAssignments.roleId eq roleId) and
                    (RbacRoleAssignments.userId eq userId)
            }
            .firstOrNull()

    private fun ResultRow.toRoleResponse(): RoleResponse =
        RoleResponse(
            id = this[RbacRoles.id].value,
            name = this[RbacRoles.name],
            permissions = json.decodeFromString(this[RbacRoles.permissions]),
            createdAt = this[RbacRoles.createdAt].toString(),
            updatedAt = this[RbacRoles.updatedAt].toString()
        )

    private fun ResultRow.toAssignmentResponse(): RoleAssignmentResponse =
        RoleAssignmentResponse(
            id = this[RbacRoleAssignments.id].value,
            roleId = this[RbacRoleAssignments.roleId],
            userId = this[RbacRoleAssignments.userId],
            createdAt = this[RbacRoleAssignments.createdAt].toString()
        )

    private fun normalizeRoleName(name: String): String {
        val normalized = name.trim()
        require(normalized.isNotBlank()) { "Role name is required" }
        require(normalized.length <= MAX_ROLE_NAME_LENGTH) { "Role name is too long" }
        return normalized
    }

    private fun normalizePermissions(permissions: List<String>): List<String> =
        permissions.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun duplicateNameOr(e: ExposedSQLException, name: String): Throwable =
        if (e.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
            IllegalArgumentException("A role named '$name' already exists")
        } else {
            e
        }
}
