package com.moneat.services

import com.moneat.billing.models.CreateTierVersionRequest
import com.moneat.billing.models.PricingTierConfigs
import com.moneat.billing.services.PricingTierService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.*

class PricingTierServiceTest {
    private val service = PricingTierService()

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_pricing_tier;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, Subscriptions, PricingTierConfigs)
    }

    private fun seedTier(
        tierName: String,
        version: Int = 1,
        monthlyUnitLimit: Long = 10_000,
        retentionDays: Int = 3,
        isCurrent: Boolean = true,
        monthlyPriceCents: Int = 0,
        apmTraceRetentionDays: Int = retentionDays,
        monthlyInfraMetricSeriesHourLimit: Long = 0,
        infraMetricOverageRateCentsPer100kSeriesHours: Int = 0
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
                it[apm_trace_retention_days] = apmTraceRetentionDays
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
                it[monthly_infra_metric_series_hour_limit] = monthlyInfraMetricSeriesHourLimit
                it[infra_metric_overage_rate_cents_per_100k_series_hours] =
                    infraMetricOverageRateCentsPer100kSeriesHours
                it[is_current] = isCurrent
            } get PricingTierConfigs.id
        }

    private fun createRequest(
        retentionDays: Int = 7,
        apmTraceRetentionDays: Int? = null
    ): CreateTierVersionRequest =
        CreateTierVersionRequest(
            monthlyUnitLimit = 50_000,
            monthlyErrorLimit = 50_000,
            monthlyTransactionLimit = 0,
            monthlyReplayLimit = 0,
            monthlyFeedbackLimit = 0,
            monthlyGbLimit = 1_073_741_824,
            retentionDays = retentionDays,
            logRetentionDays = retentionDays,
            apmTraceRetentionDays = apmTraceRetentionDays,
            maxProjects = 5,
            maxSystems = 5,
            monitorIntervalSeconds = 60,
            monthlyPriceCents = 2900,
            yearlyPriceCents = 24_900,
            trialDays = 14,
            paygEnabled = false,
            paygRateMicrosPerUnit = 0
        )

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
    fun `createTierVersion stores explicit APM trace retention`() {
        seedTier("PRO", version = 1, retentionDays = 30)

        val created =
            service.createTierVersion(
                "PRO",
                createRequest(retentionDays = 30, apmTraceRetentionDays = 45)
            )

        assertEquals(45, created.apmTraceRetentionDays)
    }

    @Test
    fun `createTierVersion preserves omitted APM trace retention from current tier`() {
        seedTier("PRO", version = 1, retentionDays = 30, apmTraceRetentionDays = 45)

        val created =
            service.createTierVersion(
                "PRO",
                createRequest(retentionDays = 30)
            )

        assertEquals(45, created.apmTraceRetentionDays)
    }

    @Test
    fun `createTierVersion defaults APM trace retention to retention days without current tier`() {
        val created =
            service.createTierVersion(
                "TEAM",
                createRequest(retentionDays = 21)
            )

        assertEquals(21, created.apmTraceRetentionDays)
    }

    @Test
    fun `createTierVersion rejects invalid APM trace retention`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                service.createTierVersion(
                    "PRO",
                    createRequest(retentionDays = 30, apmTraceRetentionDays = 0)
                )
            }

        assertEquals("APM trace retention days must be between 1 and 90", error.message)
    }

    @Test
    fun `createTierVersion preserves infrastructure metric billing from current tier`() {
        seedTier(
            "PRO",
            version = 1,
            monthlyInfraMetricSeriesHourLimit = 12_345,
            infraMetricOverageRateCentsPer100kSeriesHours = 17
        )

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

        assertEquals(12_345, created.monthlyInfraMetricSeriesHourLimit)
        assertEquals(17, created.infraMetricOverageRateCentsPer100kSeriesHours)
    }

    @Test
    fun `createTierVersion uses default infrastructure metric billing without current tier`() {
        val created =
            service.createTierVersion(
                "TEAM",
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
                    monthlyPriceCents = 7900,
                    yearlyPriceCents = 79200,
                    trialDays = 14,
                    paygEnabled = true,
                    paygRateMicrosPerUnit = 400_000
                )
            )

        assertEquals(250_000_000, created.monthlyInfraMetricSeriesHourLimit)
        assertEquals(10, created.infraMetricOverageRateCentsPer100kSeriesHours)
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
