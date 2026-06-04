package com.moneat.services

import com.moneat.auth.services.AccountDeletionService
import com.moneat.config.ClickHouseClient
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrgInvitations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.RefreshTokens
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.UsageRecords
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import com.moneat.testsupport.withClickHouseMockServer
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlin.time.Clock

class AccountDeletionServiceTest {
    private val service = AccountDeletionService(
        stripeService = mockk(relaxed = true),
        emailService = mockk(relaxed = true),
    )

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_account_deletion;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            Projects,
            RefreshTokens,
            Subscriptions,
            UsageRecords,
            OrgInvitations
        )
    }

    @AfterTest
    fun closeClickHouse() {
        ClickHouseClient.close()
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

    private fun seedOrg(name: String = "Test Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
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

    private fun seedSubscription(
        orgId: Int,
        status: String = "active"
    ) = transaction {
        Subscriptions.insert {
            it[organization_id] = orgId
            it[Subscriptions.status] = status
            it[plan] = "pro"
        }
    }

    private fun seedProject(
        orgId: Int,
        name: String = "delete-service"
    ): Long =
        transaction {
            Projects.insert {
                it[organization_id] = orgId
                it[Projects.name] = name
                it[slug] = name
            } get Projects.id
        }

    // ──── validateUserDeletion ────

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

    // ──── validateOrganizationDeletion ────

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

    @Test
    fun `deleteOrganization deletes ClickHouse service data by compatible id column`() = runBlocking {
        val userId = seedUser()
        val orgId = seedOrg("Delete Service Org")
        val projectId = seedProject(orgId)
        seedMembership(userId, orgId, "owner")
        val queries = CopyOnWriteArrayList<String>()

        withClickHouseMockServer(
            { exchange ->
                queries += exchange.requestBodyText()
                exchange.respond(200, "")
            }
        ) {
            assertTrue(service.deleteOrganization(orgId, userId))
        }

        assertTrue(
            queries.any { it == "ALTER TABLE events DELETE WHERE service_id IN ($projectId)" },
            "events should delete by service_id"
        )
        assertTrue(
            queries.any { it == "ALTER TABLE llm_generations DELETE WHERE service_id IN ($projectId)" },
            "llm_generations should delete by service_id"
        )
        assertTrue(
            queries.any { it == "ALTER TABLE llm_generations_hourly_mv DELETE WHERE project_id IN ($projectId)" },
            "llm_generations_hourly_mv should keep deprecated project_id"
        )
    }
}
