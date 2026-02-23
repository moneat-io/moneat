package com.moneat.services

import com.moneat.notifications.services.EmailService
import com.moneat.org.services.OrgInvitationService
import com.moneat.org.services.OrgMembershipService
import com.moneat.shared.models.EmailsSent
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.OrgInvitations
import com.moneat.shared.models.SsoConfigurations
import com.moneat.shared.models.Users
import io.ktor.server.plugins.BadRequestException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class OrgInvitationServiceTest {
    private val membershipService = OrgMembershipService()
    private val emailService = EmailService()
    private val service = OrgInvitationService(membershipService, emailService)

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_org_invitation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        transaction {
            try {
                SchemaUtils.create(
                    Organizations,
                    Users,
                    Memberships,
                    OrgInvitations,
                    SsoConfigurations,
                    EmailsSent,
                )
            } catch (_: Exception) {
                // Tables already exist, which is fine
            }
            
            OrgInvitations.deleteAll()
            EmailsSent.deleteAll()
            Memberships.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
        }
    }

    private fun seedOrg(name: String = "Test Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedUser(
        email: String = "test@example.com",
        name: String = "Test User"
    ): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[Users.name] = name
                it[Users.password_hash] = "hashed"
                it[Users.email_verified] = true
            } get Users.id
        }

    private fun seedMembership(
        userId: Int,
        orgId: Int,
        role: String = "owner"
    ) = transaction {
        Memberships.insert {
            it[user_id] = userId
            it[organization_id] = orgId
            it[Memberships.role] = role
        }
    }

    private fun seedInvitation(
        orgId: Int,
        email: String,
        invitedBy: Int,
        token: String = "test-token-${System.nanoTime()}",
        status: String = "pending",
        expiresAt: Long =
            Clock.System
                .now()
                .plus(7.days)
                .toEpochMilliseconds()
    ): Int =
        transaction {
            OrgInvitations.insert {
                it[organization_id] = orgId
                it[OrgInvitations.email] = email
                it[role] = "member"
                it[invited_by] = invitedBy
                it[OrgInvitations.token] = token
                it[OrgInvitations.status] = status
                it[OrgInvitations.expires_at] = expiresAt
                it[created_at] = Clock.System.now()
            } get OrgInvitations.id
        }

    // --- inviteMember ---

    @Test
    fun `inviteMember creates pending invitation`() {
        val orgId = seedOrg()
        val adminId = seedUser("admin@test.com", "Admin")
        seedMembership(adminId, orgId, "admin")

        val result = service.inviteMember(orgId, "newuser@test.com", "member", adminId)
        assertEquals("newuser@test.com", result.email)
        assertEquals("member", result.role)
        assertEquals("pending", result.status)
    }

    @Test
    fun `inviteMember rejects invalid role`() {
        val orgId = seedOrg()
        val adminId = seedUser("admin@test.com", "Admin")
        seedMembership(adminId, orgId, "admin")

        assertFailsWith<BadRequestException> {
            service.inviteMember(orgId, "user@test.com", "superadmin", adminId)
        }
    }

    @Test
    fun `inviteMember rejects non-owner inviting as owner`() {
        val orgId = seedOrg()
        val adminId = seedUser("admin@test.com", "Admin")
        seedMembership(adminId, orgId, "admin")

        assertFailsWith<IllegalStateException> {
            service.inviteMember(orgId, "user@test.com", "owner", adminId)
        }
    }

    @Test
    fun `inviteMember rejects when user is already a member`() {
        val orgId = seedOrg()
        val adminId = seedUser("admin@test.com", "Admin")
        val existingUser = seedUser("existing@test.com", "Existing")
        seedMembership(adminId, orgId, "admin")
        seedMembership(existingUser, orgId, "member")

        assertFailsWith<BadRequestException> {
            service.inviteMember(orgId, "existing@test.com", "member", adminId)
        }
    }

    @Test
    fun `inviteMember rejects duplicate pending invitation`() {
        val orgId = seedOrg()
        val adminId = seedUser("admin@test.com", "Admin")
        seedMembership(adminId, orgId, "admin")

        service.inviteMember(orgId, "newuser@test.com", "member", adminId)
        assertFailsWith<BadRequestException> {
            service.inviteMember(orgId, "newuser@test.com", "member", adminId)
        }
    }

    @Test
    fun `inviteMember allows owner to invite as owner`() {
        val orgId = seedOrg()
        val ownerId = seedUser("owner@test.com", "Owner")
        seedMembership(ownerId, orgId, "owner")

        val result = service.inviteMember(orgId, "newowner@test.com", "owner", ownerId)
        assertEquals("owner", result.role)
    }

    // --- acceptInvitation ---

    @Test
    fun `acceptInvitation creates membership`() {
        val orgId = seedOrg()
        val adminId = seedUser("admin@test.com", "Admin")
        seedMembership(adminId, orgId, "admin")
        val newUserId = seedUser("newuser@test.com", "New User")

        val token = "accept-test-token"
        seedInvitation(orgId, "newuser@test.com", adminId, token)

        val result = service.acceptInvitation(token, newUserId)
        assertTrue(result)
        assertTrue(membershipService.isMember(orgId, newUserId))
    }

    @Test
    fun `acceptInvitation rejects wrong email`() {
        val orgId = seedOrg()
        val adminId = seedUser("admin@test.com", "Admin")
        seedMembership(adminId, orgId, "admin")
        val wrongUser = seedUser("wrong@test.com", "Wrong")

        val token = "wrong-email-token"
        seedInvitation(orgId, "correct@test.com", adminId, token)

        assertFailsWith<BadRequestException> {
            service.acceptInvitation(token, wrongUser)
        }
    }

    @Test
    fun `acceptInvitation rejects expired invitation`() {
        val orgId = seedOrg()
        val adminId = seedUser("admin@test.com", "Admin")
        seedMembership(adminId, orgId, "admin")
        val userId = seedUser("user@test.com", "User")

        val token = "expired-token"
        val pastExpiry =
            Clock.System
                .now()
                .minus(1.days)
                .toEpochMilliseconds()
        seedInvitation(orgId, "user@test.com", adminId, token, expiresAt = pastExpiry)

        assertFailsWith<BadRequestException> {
            service.acceptInvitation(token, userId)
        }
    }

    @Test
    fun `acceptInvitation rejects already accepted invitation`() {
        val orgId = seedOrg()
        val adminId = seedUser("admin@test.com", "Admin")
        seedMembership(adminId, orgId, "admin")
        val userId = seedUser("user@test.com", "User")

        val token = "accepted-token"
        seedInvitation(orgId, "user@test.com", adminId, token, status = "accepted")

        assertFailsWith<BadRequestException> {
            service.acceptInvitation(token, userId)
        }
    }

    // --- revokeInvitation ---

    @Test
    fun `revokeInvitation marks as revoked`() {
        val orgId = seedOrg()
        val adminId = seedUser("admin@test.com", "Admin")
        seedMembership(adminId, orgId, "admin")

        val invId = seedInvitation(orgId, "user@test.com", adminId)

        val result = service.revokeInvitation(invId, adminId)
        assertTrue(result)

        val status =
            transaction {
                OrgInvitations
                    .selectAll()
                    .where { OrgInvitations.id eq invId }
                    .single()[OrgInvitations.status]
            }
        assertEquals("revoked", status)
    }

    @Test
    fun `revokeInvitation requires admin permission`() {
        val orgId = seedOrg()
        val adminId = seedUser("admin@test.com", "Admin")
        val memberId = seedUser("member@test.com", "Member")
        seedMembership(adminId, orgId, "admin")
        seedMembership(memberId, orgId, "member")

        val invId = seedInvitation(orgId, "user@test.com", adminId)

        assertFailsWith<Exception> {
            service.revokeInvitation(invId, memberId)
        }
    }

    // --- cleanupExpiredInvitations ---

    @Test
    fun `cleanupExpiredInvitations marks expired invitations`() {
        val orgId = seedOrg()
        val adminId = seedUser("admin@test.com", "Admin")
        seedMembership(adminId, orgId, "admin")

        val pastExpiry =
            Clock.System
                .now()
                .minus(1.days)
                .toEpochMilliseconds()
        seedInvitation(orgId, "expired1@test.com", adminId, expiresAt = pastExpiry)
        seedInvitation(orgId, "expired2@test.com", adminId, expiresAt = pastExpiry)

        val count = service.cleanupExpiredInvitations()
        assertEquals(2, count)
    }

    @Test
    fun `cleanupExpiredInvitations does not affect valid invitations`() {
        val orgId = seedOrg()
        val adminId = seedUser("admin@test.com", "Admin")
        seedMembership(adminId, orgId, "admin")

        val futureExpiry =
            Clock.System
                .now()
                .plus(7.days)
                .toEpochMilliseconds()
        seedInvitation(orgId, "valid@test.com", adminId, expiresAt = futureExpiry)

        val count = service.cleanupExpiredInvitations()
        assertEquals(0, count)
    }

    @Test
    fun `cleanupExpiredInvitations returns zero when no expired`() {
        val count = service.cleanupExpiredInvitations()
        assertEquals(0, count)
    }

    // --- getPendingInvitations ---

    @Test
    fun `getPendingInvitations returns valid pending invitations`() {
        val orgId = seedOrg()
        val adminId = seedUser("admin@test.com", "Admin")
        seedMembership(adminId, orgId, "admin")

        seedInvitation(orgId, "user1@test.com", adminId)
        seedInvitation(orgId, "user2@test.com", adminId)
        // Revoked should not appear
        seedInvitation(orgId, "revoked@test.com", adminId, status = "revoked")

        val pending = service.getPendingInvitations(orgId)
        assertEquals(2, pending.size)
    }
}
