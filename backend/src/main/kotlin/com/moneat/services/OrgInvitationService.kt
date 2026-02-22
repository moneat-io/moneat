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

package com.moneat.services

import com.moneat.models.*
import io.ktor.server.plugins.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class OrgInvitationService(
    private val membershipService: OrgMembershipService,
    private val emailService: EmailService
) {
    private val logger = LoggerFactory.getLogger(OrgInvitationService::class.java)
    private val random = SecureRandom()

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun inviteMember(
        orgId: Int,
        email: String,
        role: String,
        invitedByUserId: Int
    ): InvitationResponse =
        transaction {
            val normalizedEmail = email.lowercase().trim()

            // Validate permission
            membershipService.requireRole(orgId, invitedByUserId, OrgRole.ADMIN)

            // Validate role
            val validRoles = listOf("owner", "admin", "member")
            if (role !in validRoles) {
                throw BadRequestException("Invalid role: $role")
            }

            val requestingRole =
                membershipService.getMemberRole(orgId, invitedByUserId)
                    ?: throw IllegalStateException("Not a member of this organization")
            if (role == "owner" && OrgRole.fromString(requestingRole) != OrgRole.OWNER) {
                throw IllegalStateException("Only owners can invite members as owner")
            }

            val now = Clock.System.now().toEpochMilliseconds()
            // Expire stale pending invitations so they don't block re-invites.
            OrgInvitations.update({
                (OrgInvitations.organization_id eq orgId) and
                    (OrgInvitations.email eq normalizedEmail) and
                    (OrgInvitations.status eq "pending") and
                    (OrgInvitations.expires_at lessEq now)
            }) {
                it[OrgInvitations.status] = "expired"
            }

            // Check if user is already a member
            val existingUser = Users.selectAll().where { Users.email eq normalizedEmail }.singleOrNull()
            if (existingUser != null) {
                val userId = existingUser[Users.id]
                if (membershipService.isMember(orgId, userId)) {
                    throw BadRequestException("User is already a member of this organization")
                }
            }

            // Check if there's already a pending invitation
            val existingInvite =
                OrgInvitations
                    .selectAll()
                    .where {
                        (OrgInvitations.organization_id eq orgId) and
                            (OrgInvitations.email eq normalizedEmail) and
                            (OrgInvitations.status eq "pending") and
                            (OrgInvitations.expires_at greater now)
                    }.singleOrNull()

            if (existingInvite != null) {
                throw BadRequestException("An invitation is already pending for this email")
            }

            // Create invitation
            val token = generateToken()
            val expiresAt =
                Clock.System
                    .now()
                    .plus(7.days)
                    .toEpochMilliseconds()

            val invitationId =
                OrgInvitations.insert {
                    it[organization_id] = orgId
                    it[OrgInvitations.email] = normalizedEmail
                    it[OrgInvitations.role] = role
                    it[invited_by] = invitedByUserId
                    it[OrgInvitations.token] = token
                    it[OrgInvitations.status] = "pending"
                    it[OrgInvitations.expires_at] = expiresAt
                    it[created_at] = Clock.System.now()
                } get OrgInvitations.id

            // Get inviter info and org name for email
            val inviter = Users.selectAll().where { Users.id eq invitedByUserId }.single()
            val org = Organizations.selectAll().where { Organizations.id eq orgId }.single()

            // Send invitation email
            emailService.sendInvitationEmail(
                toEmail = normalizedEmail,
                inviterName = inviter[Users.name] ?: inviter[Users.email],
                orgName = org[Organizations.name],
                role = role,
                token = token
            )

            logger.info("User $invitedByUserId invited $normalizedEmail to org $orgId as $role")

            InvitationResponse(
                id = invitationId,
                email = normalizedEmail,
                role = role,
                status = "pending",
                invitedBy = inviter[Users.name] ?: "",
                invitedByEmail = inviter[Users.email],
                createdAt = Clock.System.now().toString(),
                expiresAt =
                kotlin.time.Instant
                    .fromEpochMilliseconds(expiresAt)
                    .toString()
            )
        }

    fun bulkInvite(
        orgId: Int,
        emails: List<String>,
        role: String,
        invitedByUserId: Int
    ): BulkInviteResult {
        val success = mutableListOf<String>()
        val failed = mutableListOf<BulkInviteFailure>()

        for (email in emails) {
            try {
                inviteMember(orgId, email.trim(), role, invitedByUserId)
                success.add(email)
            } catch (e: Exception) {
                logger.warn("Failed to invite $email: ${e.message}")
                failed.add(BulkInviteFailure(email, e.message ?: "Unknown error"))
            }
        }

        return BulkInviteResult(success, failed)
    }

    fun getPendingInvitations(orgId: Int): List<InvitationResponse> =
        transaction {
            val now = Clock.System.now().toEpochMilliseconds()
            OrgInvitations.update({
                (OrgInvitations.organization_id eq orgId) and
                    (OrgInvitations.status eq "pending") and
                    (OrgInvitations.expires_at lessEq now)
            }) {
                it[OrgInvitations.status] = "expired"
            }

            (OrgInvitations innerJoin Users)
                .selectAll()
                .where {
                    (OrgInvitations.organization_id eq orgId) and
                        (OrgInvitations.status eq "pending") and
                        (OrgInvitations.expires_at greater now)
                }.map { row ->
                    InvitationResponse(
                        id = row[OrgInvitations.id],
                        email = row[OrgInvitations.email],
                        role = row[OrgInvitations.role],
                        status = row[OrgInvitations.status],
                        invitedBy = row[Users.name] ?: "",
                        invitedByEmail = row[Users.email],
                        createdAt = row[OrgInvitations.created_at].toString(),
                        expiresAt =
                        kotlin.time.Instant
                            .fromEpochMilliseconds(row[OrgInvitations.expires_at])
                            .toString()
                    )
                }
        }

    fun getInvitationDetails(token: String): InvitationDetailsResponse =
        transaction {
            val invite =
                (OrgInvitations innerJoin Organizations innerJoin Users)
                    .selectAll()
                    .where { OrgInvitations.token eq token }
                    .singleOrNull()
                    ?: throw NotFoundException("Invitation not found")

            val expiresAt = invite[OrgInvitations.expires_at]
            val isExpired = Clock.System.now().toEpochMilliseconds() > expiresAt
            val status = invite[OrgInvitations.status]

            InvitationDetailsResponse(
                orgName = invite[Organizations.name],
                role = invite[OrgInvitations.role],
                invitedBy = invite[Users.name] ?: invite[Users.email],
                expiresAt =
                kotlin.time.Instant
                    .fromEpochMilliseconds(expiresAt)
                    .toString(),
                valid = status == "pending" && !isExpired
            )
        }

    fun acceptInvitation(
        token: String,
        userId: Int
    ): Boolean =
        transaction {
            val invite =
                OrgInvitations
                    .selectAll()
                    .where { OrgInvitations.token eq token }
                    .singleOrNull()
                    ?: throw NotFoundException("Invitation not found")

            val inviteId = invite[OrgInvitations.id]
            val orgId = invite[OrgInvitations.organization_id]
            val email = invite[OrgInvitations.email]
            val role = invite[OrgInvitations.role]
            val status = invite[OrgInvitations.status]
            val expiresAt = invite[OrgInvitations.expires_at]

            // Validate invitation
            if (status != "pending") {
                throw BadRequestException("Invitation is no longer valid (status: $status)")
            }

            if (Clock.System.now().toEpochMilliseconds() > expiresAt) {
                // Mark as expired
                OrgInvitations.update({ OrgInvitations.id eq inviteId }) {
                    it[OrgInvitations.status] = "expired"
                }
                throw BadRequestException("Invitation has expired")
            }

            // Verify user email matches invitation
            val user =
                Users.selectAll().where { Users.id eq userId }.singleOrNull()
                    ?: throw NotFoundException("User not found")

            if (user[Users.email] != email) {
                throw BadRequestException("This invitation was sent to a different email address")
            }

            // Check if already a member
            if (membershipService.isMember(orgId, userId)) {
                throw BadRequestException("You are already a member of this organization")
            }

            // Create membership
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[Memberships.role] = role
            }

            // Mark invitation as accepted
            OrgInvitations.update({ OrgInvitations.id eq inviteId }) {
                it[OrgInvitations.status] = "accepted"
            }

            logger.info("User $userId accepted invitation $inviteId to org $orgId")
            true
        }

    fun revokeInvitation(
        invitationId: Int,
        requestingUserId: Int
    ): Boolean =
        transaction {
            val invite =
                OrgInvitations
                    .selectAll()
                    .where { OrgInvitations.id eq invitationId }
                    .singleOrNull()
                    ?: throw NotFoundException("Invitation not found")

            val orgId = invite[OrgInvitations.organization_id]

            // Validate permission
            membershipService.requireRole(orgId, requestingUserId, OrgRole.ADMIN)

            val updated =
                OrgInvitations.update({ OrgInvitations.id eq invitationId }) {
                    it[status] = "revoked"
                }

            logger.info("User $requestingUserId revoked invitation $invitationId")
            updated > 0
        }

    fun resendInvitation(
        invitationId: Int,
        requestingUserId: Int
    ): Boolean =
        transaction {
            val invite =
                (OrgInvitations innerJoin Organizations innerJoin Users)
                    .selectAll()
                    .where { OrgInvitations.id eq invitationId }
                    .singleOrNull()
                    ?: throw NotFoundException("Invitation not found")

            val orgId = invite[OrgInvitations.organization_id]

            // Validate permission
            membershipService.requireRole(orgId, requestingUserId, OrgRole.ADMIN)

            if (invite[OrgInvitations.status] != "pending") {
                throw BadRequestException("Can only resend pending invitations")
            }

            // Generate new token and extend expiry
            val newToken = generateToken()
            val newExpiresAt =
                Clock.System
                    .now()
                    .plus(7.days)
                    .toEpochMilliseconds()

            OrgInvitations.update({ OrgInvitations.id eq invitationId }) {
                it[token] = newToken
                it[expires_at] = newExpiresAt
            }

            // Resend email
            val inviter = Users.selectAll().where { Users.id eq requestingUserId }.single()
            emailService.sendInvitationEmail(
                toEmail = invite[OrgInvitations.email],
                inviterName = inviter[Users.name] ?: inviter[Users.email],
                orgName = invite[Organizations.name],
                role = invite[OrgInvitations.role],
                token = newToken
            )

            logger.info("User $requestingUserId resent invitation $invitationId")
            true
        }

    fun cleanupExpiredInvitations(): Int =
        transaction {
            val now = Clock.System.now().toEpochMilliseconds()
            val updated =
                OrgInvitations.update(
                    { (OrgInvitations.status eq "pending") and (OrgInvitations.expires_at less now) }
                ) {
                    it[status] = "expired"
                }

            if (updated > 0) {
                logger.info("Marked $updated expired invitations")
            }
            updated
        }

    /**
     * Delete old expired/revoked/accepted invitations to prevent unbounded storage growth.
     * Keeps records for 90 days after creation for audit purposes, then purges.
     */
    fun purgeOldInvitations(olderThanDays: Int = 90): Int =
        transaction {
            val cutoff = Clock.System.now().minus(olderThanDays.days)
            val deleted =
                OrgInvitations.deleteWhere {
                    (OrgInvitations.status inList listOf("expired", "revoked", "accepted")) and
                        (OrgInvitations.created_at less cutoff)
                }

            if (deleted > 0) {
                logger.info(
                    "Purged $deleted old invitations " +
                        "(status in expired/revoked/accepted, older than $olderThanDays days)"
                )
            }
            deleted
        }
}
