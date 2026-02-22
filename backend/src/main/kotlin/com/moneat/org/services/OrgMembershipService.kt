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

package com.moneat.org.services

import com.moneat.events.models.OrgMemberResponse
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Users
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

enum class OrgRole(val level: Int) {
    MEMBER(0),
    ADMIN(1),
    OWNER(2);

    companion object {
        fun fromString(role: String): OrgRole {
            return valueOf(role.uppercase())
        }
    }
}

class OrgMembershipService {
    private val logger = LoggerFactory.getLogger(OrgMembershipService::class.java)

    fun getMembers(orgId: Int): List<OrgMemberResponse> =
        transaction {
            (Memberships innerJoin Users)
                .selectAll()
                .where { Memberships.organization_id eq orgId }
                .map { row ->
                    OrgMemberResponse(
                        userId = row[Users.id],
                        email = row[Users.email],
                        name = row[Users.name],
                        role = row[Memberships.role],
                        joinedAt = null // We don't have created_at on memberships currently
                    )
                }
        }

    fun getMemberRole(
        orgId: Int,
        userId: Int
    ): String? =
        transaction {
            Memberships
                .selectAll()
                .where { (Memberships.organization_id eq orgId) and (Memberships.user_id eq userId) }
                .singleOrNull()
                ?.get(Memberships.role)
        }

    fun requireRole(
        orgId: Int,
        userId: Int,
        minRole: OrgRole
    ) {
        val userRole =
            getMemberRole(orgId, userId)
                ?: throw IllegalStateException("Not a member of this organization")

        if (OrgRole.fromString(userRole).level < minRole.level) {
            throw IllegalStateException(
                "Insufficient permissions (require ${minRole.name}, have ${userRole.uppercase()})"
            )
        }
    }

    fun updateMemberRole(
        orgId: Int,
        targetUserId: Int,
        newRole: String,
        requestingUserId: Int
    ): Boolean =
        transaction {
            // Validate requesting user has permission
            requireRole(orgId, requestingUserId, OrgRole.ADMIN)

            // Validate new role is valid
            val validRoles = listOf("owner", "admin", "member")
            if (newRole !in validRoles) {
                throw BadRequestException("Invalid role: $newRole. Must be one of: ${validRoles.joinToString()}")
            }

            // Get target user's current role
            val targetRole =
                getMemberRole(orgId, targetUserId)
                    ?: throw NotFoundException("User is not a member of this organization")

            // Admins cannot modify owners or assign owner role
            val requestingRole = getMemberRole(orgId, requestingUserId)!!
            if (OrgRole.fromString(requestingRole) == OrgRole.ADMIN) {
                if (OrgRole.fromString(targetRole) == OrgRole.OWNER) {
                    throw IllegalStateException("Admins cannot modify owners")
                }
                if (newRole == "owner") {
                    throw IllegalStateException("Only owners can assign owner role")
                }
            }

            // Prevent changing last owner
            if (targetRole == "owner" && newRole != "owner") {
                val ownerCount =
                    Memberships
                        .selectAll()
                        .where { (Memberships.organization_id eq orgId) and (Memberships.role eq "owner") }
                        .count()

                if (ownerCount <= 1) {
                    throw BadRequestException("Cannot change role of the last owner. Transfer ownership first.")
                }
            }

            val updated =
                Memberships.update(
                    { (Memberships.organization_id eq orgId) and (Memberships.user_id eq targetUserId) }
                ) {
                    it[role] = newRole
                }

            logger.info("User $requestingUserId updated role of user $targetUserId to $newRole in org $orgId")
            updated > 0
        }

    fun removeMember(
        orgId: Int,
        targetUserId: Int,
        requestingUserId: Int
    ): Boolean =
        transaction {
            // Validate requesting user has permission
            requireRole(orgId, requestingUserId, OrgRole.ADMIN)

            // Get target user's current role
            val targetRole =
                getMemberRole(orgId, targetUserId)
                    ?: throw NotFoundException("User is not a member of this organization")

            // Cannot remove owners
            if (targetRole == "owner") {
                throw BadRequestException("Cannot remove owners. Transfer ownership first.")
            }

            // Admins can only remove members, not other admins
            val requestingRole = getMemberRole(orgId, requestingUserId)!!
            if (OrgRole.fromString(requestingRole) == OrgRole.ADMIN &&
                OrgRole.fromString(targetRole) == OrgRole.ADMIN &&
                requestingUserId != targetUserId
            ) {
                throw IllegalStateException("Admins cannot remove other admins")
            }

            val deleted =
                Memberships.deleteWhere {
                    (organization_id eq orgId) and (user_id eq targetUserId)
                }

            logger.info("User $requestingUserId removed user $targetUserId from org $orgId")
            deleted > 0
        }

    fun isMember(
        orgId: Int,
        userId: Int
    ): Boolean =
        transaction {
            Memberships
                .selectAll()
                .where { (Memberships.organization_id eq orgId) and (Memberships.user_id eq userId) }
                .count() > 0
        }
}
