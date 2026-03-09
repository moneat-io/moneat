package com.moneat.services

import com.moneat.notifications.services.EmailService
import com.moneat.org.repositories.OrgInvitationRepository
import com.moneat.org.repositories.models.InvitationWithInviterRow
import com.moneat.org.repositories.models.OrgInvitationRow
import com.moneat.org.repositories.models.OrgInvitationUserRow
import com.moneat.org.services.OrgInvitationService
import com.moneat.org.services.OrgMembershipService
import io.ktor.server.plugins.BadRequestException
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class OrgInvitationServiceTest {
    private val membershipService = mockk<OrgMembershipService>()
    private val emailService = mockk<EmailService>(relaxed = true)
    private val invitationRepository = mockk<OrgInvitationRepository>()
    private val service = OrgInvitationService(membershipService, emailService, invitationRepository)

    private val orgId = 1
    private val adminId = 10
    private val futureMs = Clock.System.now().plus(7.days).toEpochMilliseconds()
    private val pastMs = Clock.System.now().minus(1.days).toEpochMilliseconds()

    private fun mockAdminRole() {
        every { membershipService.requireRole(orgId, adminId, any()) } returns Unit
        every { membershipService.getMemberRole(orgId, adminId) } returns "admin"
    }

    private fun stubInviteCreation(email: String, invitationId: Int = 1) {
        every { invitationRepository.expireStaleInvitations(orgId, email, any()) } returns 0
        every { invitationRepository.findUserByEmail(email) } returns null
        every { invitationRepository.existsPendingInvitation(orgId, email, any()) } returns false
        every {
            invitationRepository.createInvitation(orgId, email, any(), adminId, any(), any(), any())
        } returns invitationId
        every { invitationRepository.findInviterAndOrg(adminId, orgId) } returns null
    }

    // --- inviteMember ---

    @Test
    fun `inviteMember creates pending invitation`() {
        mockAdminRole()
        stubInviteCreation("newuser@test.com")

        val result = service.inviteMember(orgId, "newuser@test.com", "member", adminId)
        assertEquals("newuser@test.com", result.email)
        assertEquals("member", result.role)
        assertEquals("pending", result.status)
    }

    @Test
    fun `inviteMember rejects invalid role`() {
        mockAdminRole()
        assertFailsWith<BadRequestException> {
            service.inviteMember(orgId, "user@test.com", "superadmin", adminId)
        }
    }

    @Test
    fun `inviteMember rejects non-owner inviting as owner`() {
        every { membershipService.requireRole(orgId, adminId, any()) } returns Unit
        every { membershipService.getMemberRole(orgId, adminId) } returns "admin"
        assertFailsWith<IllegalStateException> {
            service.inviteMember(orgId, "user@test.com", "owner", adminId)
        }
    }

    @Test
    fun `inviteMember rejects when user is already a member`() {
        mockAdminRole()
        val existingUserId = 99
        every { invitationRepository.expireStaleInvitations(orgId, "existing@test.com", any()) } returns 0
        every { invitationRepository.findUserByEmail("existing@test.com") } returns
            OrgInvitationUserRow(existingUserId, "existing@test.com", "Existing")
        every { membershipService.isMember(orgId, existingUserId) } returns true

        assertFailsWith<BadRequestException> {
            service.inviteMember(orgId, "existing@test.com", "member", adminId)
        }
    }

    @Test
    fun `inviteMember rejects duplicate pending invitation`() {
        mockAdminRole()
        every { invitationRepository.expireStaleInvitations(orgId, "dup@test.com", any()) } returns 0
        every { invitationRepository.findUserByEmail("dup@test.com") } returns null
        every { invitationRepository.existsPendingInvitation(orgId, "dup@test.com", any()) } returns true

        assertFailsWith<BadRequestException> {
            service.inviteMember(orgId, "dup@test.com", "member", adminId)
        }
    }

    @Test
    fun `inviteMember allows owner to invite as owner`() {
        every { membershipService.requireRole(orgId, adminId, any()) } returns Unit
        every { membershipService.getMemberRole(orgId, adminId) } returns "owner"
        stubInviteCreation("newowner@test.com", 2)

        val result = service.inviteMember(orgId, "newowner@test.com", "owner", adminId)
        assertEquals("owner", result.role)
    }

    // --- acceptInvitation ---

    private fun makeInvite(
        status: String = "pending",
        expiresAt: Long = futureMs,
        email: String = "newuser@test.com",
    ) = OrgInvitationRow(
        id = 1, orgId = orgId, email = email, role = "member",
        invitedBy = adminId, token = "tok", status = status,
        expiresAt = expiresAt, createdAt = Clock.System.now(),
    )

    @Test
    fun `acceptInvitation creates membership`() {
        val invite = makeInvite()
        val userId = 20
        every { invitationRepository.findByToken("tok") } returns invite
        every { invitationRepository.findUserById(userId) } returns
            OrgInvitationUserRow(userId, "newuser@test.com", "New User")
        every { membershipService.isMember(orgId, userId) } returns false
        justRun { membershipService.addMember(orgId, userId, "member") }
        every { invitationRepository.updateStatus(1, "accepted") } returns 1

        val result = service.acceptInvitation("tok", userId)
        assertTrue(result)
        verify { membershipService.addMember(orgId, userId, "member") }
        verify { invitationRepository.updateStatus(1, "accepted") }
    }

    @Test
    fun `acceptInvitation rejects wrong email`() {
        val invite = makeInvite(email = "correct@test.com")
        val wrongUserId = 21
        every { invitationRepository.findByToken("tok") } returns invite
        every { invitationRepository.findUserById(wrongUserId) } returns
            OrgInvitationUserRow(wrongUserId, "wrong@test.com", "Wrong")

        assertFailsWith<BadRequestException> { service.acceptInvitation("tok", wrongUserId) }
    }

    @Test
    fun `acceptInvitation rejects expired invitation`() {
        val invite = makeInvite(expiresAt = pastMs)
        every { invitationRepository.findByToken("tok") } returns invite
        every { invitationRepository.updateStatus(1, "expired") } returns 1

        assertFailsWith<BadRequestException> { service.acceptInvitation("tok", 20) }
        verify { invitationRepository.updateStatus(1, "expired") }
    }

    @Test
    fun `acceptInvitation rejects already accepted invitation`() {
        val invite = makeInvite(status = "accepted")
        every { invitationRepository.findByToken("tok") } returns invite

        assertFailsWith<BadRequestException> { service.acceptInvitation("tok", 20) }
    }

    // --- revokeInvitation ---

    @Test
    fun `revokeInvitation marks as revoked`() {
        every { invitationRepository.findById(1) } returns makeInvite()
        every { membershipService.requireRole(orgId, adminId, any()) } returns Unit
        every { invitationRepository.updateStatus(1, "revoked") } returns 1

        val result = service.revokeInvitation(1, adminId)
        assertTrue(result)
        verify { invitationRepository.updateStatus(1, "revoked") }
    }

    @Test
    fun `revokeInvitation requires admin permission`() {
        val memberId = 30
        every { invitationRepository.findById(1) } returns makeInvite()
        every { membershipService.requireRole(orgId, memberId, any()) } throws
            IllegalStateException("Insufficient permissions")

        assertFailsWith<Exception> { service.revokeInvitation(1, memberId) }
    }

    // --- cleanupExpiredInvitations ---

    @Test
    fun `cleanupExpiredInvitations marks expired invitations`() {
        every { invitationRepository.cleanupExpiredInvitations(any()) } returns 2
        assertEquals(2, service.cleanupExpiredInvitations())
    }

    @Test
    fun `cleanupExpiredInvitations does not affect valid invitations`() {
        every { invitationRepository.cleanupExpiredInvitations(any()) } returns 0
        assertEquals(0, service.cleanupExpiredInvitations())
    }

    @Test
    fun `cleanupExpiredInvitations returns zero when no expired`() {
        every { invitationRepository.cleanupExpiredInvitations(any()) } returns 0
        assertEquals(0, service.cleanupExpiredInvitations())
    }

    // --- getPendingInvitations ---

    @Test
    fun `getPendingInvitations returns valid pending invitations`() {
        every { invitationRepository.expireAllStaleForOrg(orgId, any()) } returns 0
        every { invitationRepository.findPendingInvitations(orgId, any()) } returns listOf(
            InvitationWithInviterRow(
                1, "user1@test.com", "member", "pending", futureMs, Clock.System.now(), "Admin", "admin@test.com"
            ),
            InvitationWithInviterRow(
                2, "user2@test.com", "member", "pending", futureMs, Clock.System.now(), "Admin", "admin@test.com"
            ),
        )

        val pending = service.getPendingInvitations(orgId)
        assertEquals(2, pending.size)
    }
}
