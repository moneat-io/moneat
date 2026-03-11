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
import com.moneat.org.repositories.OrgMembershipRepository
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
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

class OrgMembershipService(
    private val membershipRepository: OrgMembershipRepository
) {
    private val logger = LoggerFactory.getLogger(OrgMembershipService::class.java)

    fun getMembers(orgId: Int): List<OrgMemberResponse> =
        membershipRepository.getMembers(orgId).map { row ->
            OrgMemberResponse(
                userId = row.userId,
                email = row.email,
                name = row.name,
                role = row.role,
                joinedAt = null
            )
        }

    fun getMemberRole(
        orgId: Int,
        userId: Int
    ): String? =
        membershipRepository.getMemberRole(orgId, userId)

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
    ): Boolean {
        requireRole(orgId, requestingUserId, OrgRole.ADMIN)

        val validRoles = listOf("owner", "admin", "member")
        if (newRole !in validRoles) {
            throw BadRequestException("Invalid role: $newRole. Must be one of: ${validRoles.joinToString()}")
        }

        val targetRole =
            getMemberRole(orgId, targetUserId)
                ?: throw NotFoundException("User is not a member of this organization")

        val requestingRole = getMemberRole(orgId, requestingUserId)!!
        if (OrgRole.fromString(requestingRole) == OrgRole.ADMIN) {
            if (OrgRole.fromString(targetRole) == OrgRole.OWNER) {
                throw IllegalStateException("Admins cannot modify owners")
            }
            if (newRole == "owner") {
                throw IllegalStateException("Only owners can assign owner role")
            }
        }

        if (targetRole == "owner" && newRole != "owner") {
            val ownerCount = membershipRepository.getOwnerCount(orgId)
            if (ownerCount <= 1) {
                throw BadRequestException("Cannot change role of the last owner. Transfer ownership first.")
            }
        }

        val updated = membershipRepository.updateMemberRole(orgId, targetUserId, newRole)
        logger.info("User $requestingUserId updated role of user $targetUserId to $newRole in org $orgId")
        return updated > 0
    }

    fun removeMember(
        orgId: Int,
        targetUserId: Int,
        requestingUserId: Int
    ): Boolean {
        requireRole(orgId, requestingUserId, OrgRole.ADMIN)

        val targetRole =
            getMemberRole(orgId, targetUserId)
                ?: throw NotFoundException("User is not a member of this organization")

        if (targetRole == "owner") {
            throw BadRequestException("Cannot remove owners. Transfer ownership first.")
        }

        val requestingRole = getMemberRole(orgId, requestingUserId)!!
        if (OrgRole.fromString(requestingRole) == OrgRole.ADMIN &&
            OrgRole.fromString(targetRole) == OrgRole.ADMIN &&
            requestingUserId != targetUserId
        ) {
            throw IllegalStateException("Admins cannot remove other admins")
        }

        val deleted = membershipRepository.removeMember(orgId, targetUserId)
        logger.info("User $requestingUserId removed user $targetUserId from org $orgId")
        return deleted > 0
    }

    fun isMember(
        orgId: Int,
        userId: Int
    ): Boolean =
        membershipRepository.isMember(orgId, userId)

    fun addMember(
        orgId: Int,
        userId: Int,
        role: String
    ) = membershipRepository.addMember(orgId, userId, role)
}
