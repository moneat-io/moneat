package com.moneat.services

import com.moneat.billing.services.AdminBillingService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.PromotionalCreditGrants
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.*

class AdminBillingServiceTest {
    private val service = AdminBillingService()

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_admin_billing;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
                    Subscriptions,
                    PromotionalCreditGrants
                )
            } catch (_: Exception) {
                // Tables already exist, which is fine
            }
            
            PromotionalCreditGrants.deleteAll()
            Subscriptions.deleteAll()
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

    private fun seedUser(email: String = "admin@test.com"): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[Users.name] = "Admin"
                it[Users.password_hash] = "hashed"
                it[Users.email_verified] = true
            } get Users.id
        }

    private fun seedSubscription(
        orgId: Int,
        status: String = "active"
    ): Int =
        transaction {
            Subscriptions.insert {
                it[organization_id] = orgId
                it[Subscriptions.status] = status
                it[plan] = "pro"
            } get Subscriptions.id
        }

    // --- grantPromotionalCredit ---

    @Test
    fun `grantPromotionalCredit adds bonus GB`() {
        val orgId = seedOrg()
        val userId = seedUser()
        seedSubscription(orgId)

        val result = service.grantPromotionalCredit(orgId, userId, bonusGb = 5.0, reason = "test")
        assertEquals(orgId, result.organizationId)
        assertTrue(result.bonusGbBytes > 0)
        assertEquals(5.0, result.bonusGb, 0.01)
    }

    @Test
    fun `grantPromotionalCredit adds bonus units`() {
        val orgId = seedOrg()
        val userId = seedUser()
        seedSubscription(orgId)

        val result = service.grantPromotionalCredit(orgId, userId, bonusUnits = 10000L, reason = "promo")
        assertEquals(10000L, result.bonusUnits)
    }

    @Test
    fun `grantPromotionalCredit is additive`() {
        val orgId = seedOrg()
        val userId = seedUser()
        seedSubscription(orgId)

        service.grantPromotionalCredit(orgId, userId, bonusUnits = 100L, reason = "first")
        val result = service.grantPromotionalCredit(orgId, userId, bonusUnits = 200L, reason = "second")
        assertEquals(300L, result.bonusUnits)
    }

    @Test
    fun `grantPromotionalCredit creates audit trail`() {
        val orgId = seedOrg()
        val userId = seedUser()
        seedSubscription(orgId)

        service.grantPromotionalCredit(orgId, userId, bonusGb = 1.0, reason = "audit test")

        val grants =
            transaction {
                PromotionalCreditGrants
                    .selectAll()
                    .where { PromotionalCreditGrants.organization_id eq orgId }
                    .toList()
            }
        assertEquals(1, grants.size)
        assertEquals("audit test", grants[0][PromotionalCreditGrants.reason])
    }

    @Test
    fun `grantPromotionalCredit fails without subscription`() {
        val orgId = seedOrg()
        val userId = seedUser()

        assertFailsWith<IllegalStateException> {
            service.grantPromotionalCredit(orgId, userId, bonusGb = 1.0, reason = "no sub")
        }
    }

    @Test
    fun `grantPromotionalCredit requires at least one bonus type`() {
        val orgId = seedOrg()
        val userId = seedUser()
        seedSubscription(orgId)

        assertFailsWith<IllegalArgumentException> {
            service.grantPromotionalCredit(orgId, userId, reason = "nothing")
        }
    }

    @Test
    fun `grantPromotionalCredit rejects negative bonusGb`() {
        val orgId = seedOrg()
        val userId = seedUser()
        seedSubscription(orgId)

        assertFailsWith<IllegalArgumentException> {
            service.grantPromotionalCredit(orgId, userId, bonusGb = -1.0, reason = "negative")
        }
    }

    // --- getPromotionalCreditHistory ---
    // Note: getPromotionalCreditHistory and getAllPromotionalCreditGrants use implicit joins
    // that don't work with H2 (multiple FK paths to Users table). Tested in production with PostgreSQL.

    // --- resetPromotionalCredits ---

    @Test
    fun `resetPromotionalCredits zeros out bonuses`() {
        val orgId = seedOrg()
        val userId = seedUser()
        seedSubscription(orgId)

        service.grantPromotionalCredit(orgId, userId, bonusGb = 5.0, bonusUnits = 1000L, reason = "setup")

        val result = service.resetPromotionalCredits(orgId, userId)
        assertTrue(result)

        val sub =
            transaction {
                Subscriptions
                    .selectAll()
                    .where { Subscriptions.organization_id eq orgId }
                    .first()
            }
        assertEquals(0L, sub[Subscriptions.bonus_gb_bytes])
        assertEquals(0L, sub[Subscriptions.bonus_units])
    }

    @Test
    fun `resetPromotionalCredits returns false without subscription`() {
        val orgId = seedOrg()
        val userId = seedUser()

        val result = service.resetPromotionalCredits(orgId, userId)
        assertFalse(result)
    }
}
