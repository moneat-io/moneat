package com.moneat.services

import com.moneat.models.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.*

class PricingTierServiceTest {
    private val service = PricingTierService()

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_pricing_tier;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(PricingTierConfigs, Users, Organizations, Memberships, Subscriptions)
            }
            dbInitialized = true
        }

        transaction {
            Subscriptions.deleteAll()
            Memberships.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
            PricingTierConfigs.deleteAll()
        }
    }

    private fun seedTier(
        tierName: String,
        version: Int = 1,
        monthlyUnitLimit: Long = 10_000,
        retentionDays: Int = 3,
        isCurrent: Boolean = true,
        monthlyPriceCents: Int = 0
    ): Int =
        transaction {
            PricingTierConfigs.insert {
                it[tier_name] = tierName
                it[PricingTierConfigs.version] = version
                it[monthly_unit_limit] = monthlyUnitLimit
                it[monthly_error_limit] = monthlyUnitLimit
                it[monthly_transaction_limit] = 0
                it[monthly_replay_limit] = 0
                it[monthly_feedback_limit] = 0
                it[monthly_gb_limit] = 1_073_741_824
                it[retention_days] = retentionDays
                it[log_retention_days] = retentionDays
                it[status_pages_enabled] = true
                it[status_page_custom_domain_enabled] = false
                it[session_replay_enabled] = false
                it[slack_enabled] = false
                it[incident_io_enabled] = false
                it[saml_enabled] = false
                it[oidc_enabled] = false
                it[priority_support_enabled] = false
                it[sla_enabled] = false
                it[custom_retention_enabled] = false
                it[max_projects] = 3
                it[max_systems] = 3
                it[monitor_interval_seconds] = 60
                it[PricingTierConfigs.monthly_price_cents] = monthlyPriceCents
                it[yearly_price_cents] = 0
                it[trial_days] = 0
                it[payg_enabled] = false
                it[payg_rate_micros_per_unit] = 0
                it[overage_rate_cents_per_gb] = 0
                it[is_current] = isCurrent
            } get PricingTierConfigs.id
        }

    @Test
    fun `getCurrentPlans returns only current tiers`() {
        seedTier("FREE", version = 1, isCurrent = true)
        seedTier("FREE", version = 2, isCurrent = false)
        seedTier("PRO", version = 1, isCurrent = true, monthlyPriceCents = 2900)

        val plans = service.getCurrentPlans()
        assertEquals(2, plans.size)
        assertTrue(plans.any { it.tier.tierName == "FREE" })
        assertTrue(plans.any { it.tier.tierName == "PRO" })
    }

    @Test
    fun `getCurrentTier returns correct tier`() {
        seedTier("FREE", version = 1, isCurrent = true)

        val tier = service.getCurrentTier("FREE")
        assertNotNull(tier)
        assertEquals("FREE", tier.tierName)
        assertEquals(1, tier.version)
    }

    @Test
    fun `getCurrentTier returns null for non-existent tier`() {
        val tier = service.getCurrentTier("NONEXISTENT")
        assertNull(tier)
    }

    @Test
    fun `getTierVersions returns all versions for tier`() {
        seedTier("PRO", version = 1, isCurrent = false)
        seedTier("PRO", version = 2, isCurrent = true)

        val versions = service.getTierVersions("PRO")
        assertEquals(2, versions.size)
    }

    @Test
    fun `getTierById returns correct tier by id`() {
        val id = seedTier("FREE")

        val tier = service.getTierById(id)
        assertNotNull(tier)
        assertEquals("FREE", tier.tierName)
    }

    @Test
    fun `getTierById returns null for non-existent id`() {
        val tier = service.getTierById(99999)
        assertNull(tier)
    }

    @Test
    fun `createTierVersion increments version`() {
        seedTier("PRO", version = 1)

        val created =
            service.createTierVersion(
                "PRO",
                CreateTierVersionRequest(
                    monthlyUnitLimit = 50_000,
                    monthlyErrorLimit = 50_000,
                    monthlyTransactionLimit = 0,
                    monthlyReplayLimit = 0,
                    monthlyFeedbackLimit = 0,
                    monthlyGbLimit = 1_073_741_824,
                    retentionDays = 7,
                    logRetentionDays = 7,
                    maxProjects = 5,
                    maxSystems = 5,
                    monitorIntervalSeconds = 60,
                    monthlyPriceCents = 2900,
                    yearlyPriceCents = 24900,
                    trialDays = 14,
                    paygEnabled = false,
                    paygRateMicrosPerUnit = 0
                )
            )

        assertEquals(2, created.version)
        assertEquals("PRO", created.tierName)
        assertEquals(50_000, created.monthlyUnitLimit)
    }

    @Test
    fun `getEffectiveTierForOrganization returns free tier for org without subscription`() {
        seedTier("FREE", version = 1)
        val orgId =
            transaction {
                Organizations.insert {
                    it[name] = "Test Org"
                    it[slug] = "test-org"
                } get Organizations.id
            }

        val context = service.getEffectiveTierForOrganization(orgId)
        assertNotNull(context)
        assertEquals("FREE", context.tier.tierName)
    }
}
