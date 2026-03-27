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

import kotlinx.serialization.SerializationException
import java.io.IOException

import com.moneat.events.models.BulkInviteFailure
import com.moneat.events.models.BulkInviteResult
import com.moneat.events.models.InvitationDetailsResponse
import com.moneat.events.models.InvitationResponse
import com.moneat.notifications.services.EmailService
import com.moneat.org.repositories.OrgInvitationRepository
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class OrgInvitationService(
    private val membershipService: OrgMembershipService,
    private val emailService: EmailService,
    private val invitationRepository: OrgInvitationRepository,
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
    ): InvitationResponse {
        val normalizedEmail = email.lowercase().trim()

        membershipService.requireRole(orgId, invitedByUserId, OrgRole.ADMIN)

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
        invitationRepository.expireStaleInvitations(orgId, normalizedEmail, now)

        val existingUser = invitationRepository.findUserByEmail(normalizedEmail)
        if (existingUser != null && membershipService.isMember(orgId, existingUser.id)) {
            throw BadRequestException("User is already a member of this organization")
        }

        if (invitationRepository.existsPendingInvitation(orgId, normalizedEmail, now)) {
            throw BadRequestException("An invitation is already pending for this email")
        }

        val token = generateToken()
        val expiresAt = Clock.System.now().plus(7.days).toEpochMilliseconds()
        val createdAt = Clock.System.now()

        val invitationId = invitationRepository.createInvitation(
            orgId = orgId,
            email = normalizedEmail,
            role = role,
            invitedBy = invitedByUserId,
            token = token,
            expiresAt = expiresAt,
            createdAt = createdAt,
        )

        val inviterAndOrg = invitationRepository.findInviterAndOrg(invitedByUserId, orgId)
        if (inviterAndOrg != null) {
            emailService.sendInvitationEmail(
                toEmail = normalizedEmail,
                inviterName = inviterAndOrg.inviterName ?: inviterAndOrg.inviterEmail,
                orgName = inviterAndOrg.orgName,
                role = role,
                token = token
            )
        }

        logger.info("User $invitedByUserId invited $normalizedEmail to org $orgId as $role")

        return InvitationResponse(
            id = invitationId,
            email = normalizedEmail,
            role = role,
            status = "pending",
            invitedBy = inviterAndOrg?.inviterName ?: "",
            invitedByEmail = inviterAndOrg?.inviterEmail ?: "",
            createdAt = createdAt.toString(),
            expiresAt = kotlin.time.Instant.fromEpochMilliseconds(expiresAt).toString()
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
            } catch (e: SerializationException) {
                logger.warn("Failed to invite $email: ${e.message}")
                failed.add(BulkInviteFailure(email, e.message ?: "Unknown error"))
            } catch (e: IOException) {
                logger.warn("Failed to invite $email: ${e.message}")
                failed.add(BulkInviteFailure(email, e.message ?: "Unknown error"))
            } catch (e: IllegalStateException) {
                logger.warn("Failed to invite $email: ${e.message}")
                failed.add(BulkInviteFailure(email, e.message ?: "Unknown error"))
            } catch (e: IllegalArgumentException) {
                logger.warn("Failed to invite $email: ${e.message}")
                failed.add(BulkInviteFailure(email, e.message ?: "Unknown error"))
            }
        }

        return BulkInviteResult(success, failed)
    }

    fun getPendingInvitations(orgId: Int): List<InvitationResponse> {
        val now = Clock.System.now().toEpochMilliseconds()
        invitationRepository.expireAllStaleForOrg(orgId, now)
        return invitationRepository.findPendingInvitations(orgId, now).map { row ->
            InvitationResponse(
                id = row.id,
                email = row.email,
                role = row.role,
                status = row.status,
                invitedBy = row.inviterName,
                invitedByEmail = row.inviterEmail,
                createdAt = row.createdAt.toString(),
                expiresAt = kotlin.time.Instant.fromEpochMilliseconds(row.expiresAt).toString()
            )
        }
    }

    fun getInvitationDetails(token: String): InvitationDetailsResponse {
        val details = invitationRepository.findInvitationDetails(token)
            ?: throw NotFoundException("Invitation not found")
        val isExpired = Clock.System.now().toEpochMilliseconds() > details.expiresAt
        return InvitationDetailsResponse(
            orgName = details.orgName,
            role = details.role,
            invitedBy = details.inviterDisplay,
            expiresAt = kotlin.time.Instant.fromEpochMilliseconds(details.expiresAt).toString(),
            valid = details.status == "pending" && !isExpired
        )
    }

    fun acceptInvitation(
        token: String,
        userId: Int
    ): Boolean {
        val invite = invitationRepository.findByToken(token)
            ?: throw NotFoundException("Invitation not found")

        if (invite.status != "pending") {
            throw BadRequestException("Invitation is no longer valid (status: ${invite.status})")
        }

        if (Clock.System.now().toEpochMilliseconds() > invite.expiresAt) {
            invitationRepository.updateStatus(invite.id, "expired")
            throw BadRequestException("Invitation has expired")
        }

        val user = invitationRepository.findUserById(userId)
            ?: throw NotFoundException("User not found")

        if (user.email != invite.email) {
            throw BadRequestException("This invitation was sent to a different email address")
        }

        if (membershipService.isMember(invite.orgId, userId)) {
            throw BadRequestException("You are already a member of this organization")
        }

        membershipService.addMember(invite.orgId, userId, invite.role)
        invitationRepository.updateStatus(invite.id, "accepted")

        logger.info("User $userId accepted invitation ${invite.id} to org ${invite.orgId}")
        return true
    }

    fun revokeInvitation(
        invitationId: Int,
        requestingUserId: Int
    ): Boolean {
        val invite = invitationRepository.findById(invitationId)
            ?: throw NotFoundException("Invitation not found")

        membershipService.requireRole(invite.orgId, requestingUserId, OrgRole.ADMIN)

        val updated = invitationRepository.updateStatus(invitationId, "revoked")
        logger.info("User $requestingUserId revoked invitation $invitationId")
        return updated > 0
    }

    fun resendInvitation(
        invitationId: Int,
        requestingUserId: Int
    ): Boolean {
        val invite = invitationRepository.findById(invitationId)
            ?: throw NotFoundException("Invitation not found")

        membershipService.requireRole(invite.orgId, requestingUserId, OrgRole.ADMIN)

        if (invite.status != "pending") {
            throw BadRequestException("Can only resend pending invitations")
        }

        val newToken = generateToken()
        val newExpiresAt = Clock.System.now().plus(7.days).toEpochMilliseconds()
        invitationRepository.updateTokenAndExpiry(invitationId, newToken, newExpiresAt)

        val inviterAndOrg = invitationRepository.findInviterAndOrgForResend(invitationId)
        if (inviterAndOrg != null) {
            emailService.sendInvitationEmail(
                toEmail = invite.email,
                inviterName = inviterAndOrg.inviterName ?: inviterAndOrg.inviterEmail,
                orgName = inviterAndOrg.orgName,
                role = invite.role,
                token = newToken
            )
        }

        logger.info("User $requestingUserId resent invitation $invitationId")
        return true
    }

    fun cleanupExpiredInvitations(): Int {
        val now = Clock.System.now().toEpochMilliseconds()
        val updated = invitationRepository.cleanupExpiredInvitations(now)
        if (updated > 0) logger.info("Marked $updated expired invitations")
        return updated
    }

    fun purgeOldInvitations(olderThanDays: Int = 90): Int {
        val cutoff = Clock.System.now().minus(olderThanDays.days)
        val deleted = invitationRepository.purgeOldInvitations(cutoff)
        if (deleted > 0) {
            logger.info(
                "Purged $deleted old invitations " +
                    "(status in expired/revoked/accepted, older than $olderThanDays days)"
            )
        }
        return deleted
    }
}
