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

import com.moneat.billing.models.CreateTierVersionRequest
import com.moneat.billing.models.PricingTierConfigs
import com.moneat.billing.services.PricingTierService
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.assertNotNull
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PricingTierServiceFeatureFlagsTest {
    companion object {
        private var db: Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        // Initialize DB connection and schema once per test class
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_pricing;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }

        // Clean up any existing test data from previous tests
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        transaction {
            try {
                SchemaUtils.create(PricingTierConfigs)
            } catch (_: Exception) {
                // Tables already exist, which is fine
            }

            PricingTierConfigs.deleteAll()
        }
    }

    @Test
    fun `createTierVersion copies omitted optional fields from current tier`() {
        transaction {
            PricingTierConfigs.insert {
                it[tier_name] = "PRO"
                it[version] = 1
                it[monthly_unit_limit] = 500_000
                it[monthly_error_limit] = 500_000
                it[monthly_transaction_limit] = 0
                it[monthly_replay_limit] = 0
                it[monthly_feedback_limit] = 0
                it[monthly_gb_limit] = 53_687_091_200
                it[retention_days] = 30
                it[log_retention_days] = 30
                it[status_pages_enabled] = true
                it[status_page_custom_domain_enabled] = true
                it[session_replay_enabled] = true
                it[slack_enabled] = true
                it[incident_io_enabled] = true
                it[saml_enabled] = false
                it[oidc_enabled] = false
                it[priority_support_enabled] = false
                it[sla_enabled] = false
                it[custom_retention_enabled] = false
                it[max_projects] = null
                it[max_systems] = 10
                it[monitor_interval_seconds] = 30
                it[monthly_price_cents] = 2900
                it[yearly_price_cents] = 28_800
                it[trial_days] = 14
                it[payg_enabled] = true
                it[payg_rate_micros_per_unit] = 400_000
                it[overage_rate_cents_per_gb] = 40
                it[is_current] = true
            }
        }

        val service = PricingTierService()
        val created =
            service.createTierVersion(
                "PRO",
                CreateTierVersionRequest(
                    monthlyUnitLimit = 500_000,
                    monthlyErrorLimit = 500_000,
                    monthlyTransactionLimit = 0,
                    monthlyReplayLimit = 0,
                    monthlyFeedbackLimit = 0,
                    retentionDays = 30,
                    maxProjects = null,
                    maxSystems = 10,
                    monitorIntervalSeconds = 30,
                    monthlyPriceCents = 2900,
                    paygEnabled = true,
                    paygRateMicrosPerUnit = 400_000
                )
            )

        assertEquals(2, created.version)
        assertEquals(53_687_091_200, created.monthlyGbLimit)
        assertEquals(30, created.logRetentionDays)
        assertEquals(28_800, created.yearlyPriceCents)
        assertEquals(14, created.trialDays)
        assertEquals(40, created.overageRateCentsPerGb)
        assertTrue(created.statusPagesEnabled)
        assertTrue(created.statusPageCustomDomainEnabled)
        assertTrue(created.sessionReplayEnabled)
        assertTrue(created.slackEnabled)
        assertTrue(created.incidentIoEnabled)
    }

    @Test
    fun `getCurrentPlans returns retention and feature flag matrix`() {
        val tiers =
            listOf(
                SeedTier("FREE", 0, 3, 3, saml = false, oidc = false, priority = false, sla = false, customRetention = false),
                SeedTier("PRO", 2900, 30, 30, saml = false, oidc = false, priority = false, sla = false, customRetention = false),
                SeedTier("TEAM", 7900, 30, 30, saml = true, oidc = true, priority = false, sla = false, customRetention = false),
                SeedTier("BUSINESS", 19900, 90, 90, saml = true, oidc = true, priority = true, sla = true, customRetention = true)
            )

        transaction {
            tiers.forEachIndexed { idx, tier ->
                PricingTierConfigs.insert {
                    it[tier_name] = tier.name
                    it[version] = 1
                    it[monthly_unit_limit] = 500_000L + idx
                    it[monthly_error_limit] = 500_000L + idx
                    it[monthly_transaction_limit] = 0
                    it[monthly_replay_limit] = 0
                    it[monthly_feedback_limit] = 0
                    it[monthly_gb_limit] = 1_073_741_824L
                    it[retention_days] = tier.retentionDays
                    it[log_retention_days] = tier.logRetentionDays
                    it[status_pages_enabled] = true
                    it[status_page_custom_domain_enabled] = true
                    it[session_replay_enabled] = true
                    it[slack_enabled] = true
                    it[incident_io_enabled] = true
                    it[saml_enabled] = tier.saml
                    it[oidc_enabled] = tier.oidc
                    it[priority_support_enabled] = tier.priority
                    it[sla_enabled] = tier.sla
                    it[custom_retention_enabled] = tier.customRetention
                    it[max_projects] = 3
                    it[max_systems] = 3
                    it[monitor_interval_seconds] = 60
                    it[monthly_price_cents] = tier.monthlyPriceCents
                    it[yearly_price_cents] = 0
                    it[trial_days] = if (tier.name == "FREE") 0 else 14
                    it[payg_enabled] = tier.name != "FREE"
                    it[payg_rate_micros_per_unit] = 400_000
                    it[overage_rate_cents_per_gb] = 40
                    it[is_current] = true
                }
            }
        }

        val plans = PricingTierService().getCurrentPlans()
        assertEquals(4, plans.size)

        val byTier = plans.associateBy { it.tier.tierName }
        assertEquals(3, byTier["FREE"]?.tier?.retentionDays)
        assertEquals(0, byTier["FREE"]?.trialDays)
        assertEquals(30, byTier["PRO"]?.tier?.retentionDays)
        assertEquals(14, byTier["PRO"]?.trialDays)
        assertEquals(30, byTier["TEAM"]?.tier?.retentionDays)
        assertEquals(90, byTier["BUSINESS"]?.tier?.retentionDays)

        val team = byTier["TEAM"]?.tier
        assertNotNull(team)
        assertTrue(team.samlEnabled)
        assertTrue(team.oidcEnabled)

        val business = byTier["BUSINESS"]?.tier
        assertNotNull(business)
        assertTrue(business.prioritySupportEnabled)
        assertTrue(business.slaEnabled)
        assertTrue(business.customRetentionEnabled)

        val currentVersions =
            transaction {
                PricingTierConfigs
                    .selectAll()
                    .where { PricingTierConfigs.tier_name eq "PRO" }
                    .orderBy(PricingTierConfigs.version to SortOrder.DESC)
                    .toList()
            }
        assertTrue(currentVersions.isNotEmpty())
    }

    private data class SeedTier(
        val name: String,
        val monthlyPriceCents: Int,
        val retentionDays: Int,
        val logRetentionDays: Int,
        val saml: Boolean,
        val oidc: Boolean,
        val priority: Boolean,
        val sla: Boolean,
        val customRetention: Boolean
    )
}
