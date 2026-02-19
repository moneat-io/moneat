package com.moneat.services

import com.moneat.models.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.*
import kotlin.time.Clock

class AccountDeletionServiceTest {
    private val service = AccountDeletionService()

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_account_deletion;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(
                    Organizations,
                    Users,
                    Memberships,
                    Subscriptions,
                    OrgInvitations,
                    RefreshTokens,
                    Projects
                )
            }
            dbInitialized = true
        }

        transaction {
            OrgInvitations.deleteAll()
            RefreshTokens.deleteAll()
            Subscriptions.deleteAll()
            Memberships.deleteAll()
            Projects.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
        }
    }

    private fun seedUser(email: String = "test@example.com", name: String = "Test User"): Int = transaction {
        Users.insert {
            it[Users.email] = email
            it[Users.name] = name
            it[Users.password_hash] = "hashed"
            it[Users.email_verified] = true
        } get Users.id
    }

    private fun seedOrg(name: String = "Test Org"): Int = transaction {
        Organizations.insert {
            it[Organizations.name] = name
            it[slug] = name.lowercase().replace(" ", "-")
        } get Organizations.id
    }

    private fun seedMembership(userId: Int, orgId: Int, role: String = "owner") = transaction {
        Memberships.insert {
            it[user_id] = userId
            it[organization_id] = orgId
            it[Memberships.role] = role
        }
    }

    private fun seedSubscription(orgId: Int, status: String = "active") = transaction {
        Subscriptions.insert {
            it[organization_id] = orgId
            it[Subscriptions.status] = status
            it[plan] = "pro"
        }
    }

    // --- validateUserDeletion ---

    @Test
    fun `validateUserDeletion allows deletion when user has no orgs`() {
        val userId = seedUser()
        val result = service.validateUserDeletion(userId)
        assertTrue(result.canDelete)
        assertNull(result.errorMessage)
        assertTrue(result.organizationsAsLastOwner.isEmpty())
    }

    @Test
    fun `validateUserDeletion allows deletion when user is member not owner`() {
        val userId = seedUser()
        val orgId = seedOrg()
        val otherUser = seedUser("other@example.com", "Other")
        seedMembership(otherUser, orgId, "owner")
        seedMembership(userId, orgId, "member")

        val result = service.validateUserDeletion(userId)
        assertTrue(result.canDelete)
    }

    @Test
    fun `validateUserDeletion blocks when user is sole owner`() {
        val userId = seedUser()
        val orgId = seedOrg("My Company")
        seedMembership(userId, orgId, "owner")

        val result = service.validateUserDeletion(userId)
        assertFalse(result.canDelete)
        assertNotNull(result.errorMessage)
        assertTrue(result.organizationsAsLastOwner.contains("My Company"))
    }

    @Test
    fun `validateUserDeletion allows when org has multiple owners`() {
        val userId = seedUser()
        val otherUser = seedUser("other@test.com", "Other")
        val orgId = seedOrg()
        seedMembership(userId, orgId, "owner")
        seedMembership(otherUser, orgId, "owner")

        val result = service.validateUserDeletion(userId)
        assertTrue(result.canDelete)
    }

    @Test
    fun `validateUserDeletion reports all orgs where user is sole owner`() {
        val userId = seedUser()
        val org1 = seedOrg("Company A")
        val org2 = seedOrg("Company B")
        seedMembership(userId, org1, "owner")
        seedMembership(userId, org2, "owner")

        val result = service.validateUserDeletion(userId)
        assertFalse(result.canDelete)
        assertEquals(2, result.organizationsAsLastOwner.size)
        assertTrue(result.organizationsAsLastOwner.containsAll(listOf("Company A", "Company B")))
    }

    @Test
    fun `validateUserDeletion ignores deleted orgs`() {
        val userId = seedUser()
        val orgId = seedOrg("Deleted Org")
        seedMembership(userId, orgId, "owner")
        // Soft delete the org
        transaction {
            Organizations.update({ Organizations.id eq orgId }) {
                it[deletedAt] = Clock.System.now()
            }
        }

        val result = service.validateUserDeletion(userId)
        assertTrue(result.canDelete)
    }

    // --- validateOrganizationDeletion ---

    @Test
    fun `validateOrganizationDeletion allows when owner and no subscription`() {
        val userId = seedUser()
        val orgId = seedOrg()
        seedMembership(userId, orgId, "owner")

        val result = service.validateOrganizationDeletion(orgId, userId)
        assertTrue(result.canDelete)
    }

    @Test
    fun `validateOrganizationDeletion blocks non-owner`() {
        val userId = seedUser()
        val orgId = seedOrg()
        seedMembership(userId, orgId, "member")

        val result = service.validateOrganizationDeletion(orgId, userId)
        assertFalse(result.canDelete)
        assertTrue(result.errorMessage!!.contains("owner"))
    }

    @Test
    fun `validateOrganizationDeletion blocks non-member`() {
        val userId = seedUser()
        val orgId = seedOrg()

        val result = service.validateOrganizationDeletion(orgId, userId)
        assertFalse(result.canDelete)
    }

    @Test
    fun `validateOrganizationDeletion blocks with active subscription`() {
        val userId = seedUser()
        val orgId = seedOrg()
        seedMembership(userId, orgId, "owner")
        seedSubscription(orgId, "active")

        val result = service.validateOrganizationDeletion(orgId, userId)
        assertFalse(result.canDelete)
        assertTrue(result.errorMessage!!.contains("subscription"))
    }

    @Test
    fun `validateOrganizationDeletion blocks with trialing subscription`() {
        val userId = seedUser()
        val orgId = seedOrg()
        seedMembership(userId, orgId, "owner")
        seedSubscription(orgId, "trialing")

        val result = service.validateOrganizationDeletion(orgId, userId)
        assertFalse(result.canDelete)
    }

    @Test
    fun `validateOrganizationDeletion allows with canceled subscription`() {
        val userId = seedUser()
        val orgId = seedOrg()
        seedMembership(userId, orgId, "owner")
        seedSubscription(orgId, "canceled")

        val result = service.validateOrganizationDeletion(orgId, userId)
        assertTrue(result.canDelete)
    }
}
