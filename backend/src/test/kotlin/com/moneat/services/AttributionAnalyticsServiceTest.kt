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

import com.moneat.billing.models.PricingTierConfigs
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.services.AttributionAnalyticsService
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttributionAnalyticsServiceTest {
    private val service = AttributionAnalyticsService()

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_attribution;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        transaction {
            SchemaUtils.drop(PricingTierConfigs, Subscriptions, Organizations)
            SchemaUtils.create(Organizations, Subscriptions, PricingTierConfigs)
        }
    }

    private fun seedOrg(
        name: String,
        slug: String? = null,
        utmSource: String? = null,
        utmMedium: String? = null,
        utmCampaign: String? = null
    ): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[Organizations.slug] = slug ?: name.lowercase().replace(" ", "-")
                it[utm_source] = utmSource
                it[utm_medium] = utmMedium
                it[utm_campaign] = utmCampaign
            } get Organizations.id
        }

    private fun seedPricingTier(priceCents: Int = 1900, tierName: String = "PRO", yearlyPriceCents: Int = 0): Int =
        transaction {
            PricingTierConfigs.insert {
                it[tier_name] = tierName
                it[version] = 1
                it[monthly_unit_limit] = 50000
                it[monthly_error_limit] = 50000
                it[retention_days] = 30
                it[log_retention_days] = 30
                it[max_systems] = 10
                it[monitor_interval_seconds] = 30
                it[monthly_price_cents] = priceCents
                it[yearly_price_cents] = yearlyPriceCents
                it[is_current] = true
            } get PricingTierConfigs.id
        }

    private fun seedActiveSubscription(orgId: Int, tierId: Int, plan: String = "PRO"): Int =
        transaction {
            Subscriptions.insert {
                it[organization_id] = orgId
                it[Subscriptions.plan] = plan
                it[status] = "active"
                it[pricing_tier_config_id] = tierId
            } get Subscriptions.id
        }

    // ==================== getAttributionMetrics ====================

    @Test
    fun `getAttributionMetrics returns empty metrics when no orgs`() {
        val result = service.getAttributionMetrics("campaign")
        assertTrue(result.metrics.isEmpty())
        assertEquals(0, result.summary.totalSignups)
        assertEquals(0, result.summary.totalPaidOrganizations)
        assertEquals(0.0, result.summary.overallConversionRate)
    }

    @Test
    fun `getAttributionMetrics groups by campaign with UTM data`() {
        seedOrg("Org A", utmSource = "google", utmMedium = "cpc", utmCampaign = "spring-promo")
        seedOrg("Org B", utmSource = "google", utmMedium = "cpc", utmCampaign = "spring-promo")
        seedOrg("Org C", utmSource = "twitter", utmMedium = "social", utmCampaign = "summer-deal")

        val result = service.getAttributionMetrics("campaign")
        assertEquals(2, result.metrics.size)
        assertEquals(3, result.summary.totalSignups)
    }

    @Test
    fun `getAttributionMetrics groups by source`() {
        seedOrg("Org A", utmSource = "google")
        seedOrg("Org B", utmSource = "google")
        seedOrg("Org C", utmSource = "twitter")

        val result = service.getAttributionMetrics("source")
        assertEquals(2, result.metrics.size)

        val googleMetric = result.metrics.first { it.source == "google" }
        assertEquals(2, googleMetric.signups)
    }

    @Test
    fun `getAttributionMetrics groups by medium`() {
        seedOrg("Org A", utmMedium = "email")
        seedOrg("Org B", utmMedium = "email")
        seedOrg("Org C", utmMedium = "cpc")

        val result = service.getAttributionMetrics("medium")
        assertEquals(2, result.metrics.size)
    }

    @Test
    fun `getAttributionMetrics counts paid organizations`() {
        val tierId = seedPricingTier()
        val orgA = seedOrg("Org A", utmSource = "google")
        val orgB = seedOrg("Org B", utmSource = "google")
        seedOrg("Org C", utmSource = "google")

        seedActiveSubscription(orgA, tierId)
        seedActiveSubscription(orgB, tierId)

        val result = service.getAttributionMetrics("source")
        assertEquals(1, result.metrics.size)
        val metric = result.metrics.first()
        assertEquals(3, metric.signups)
        assertEquals(2, metric.paidOrganizations)
    }

    @Test
    fun `getAttributionMetrics calculates conversion rate`() {
        val tierId = seedPricingTier()
        val orgA = seedOrg("Org A", utmCampaign = "launch")
        seedOrg("Org B", utmCampaign = "launch")
        seedOrg("Org C", utmCampaign = "launch")
        seedOrg("Org D", utmCampaign = "launch")

        seedActiveSubscription(orgA, tierId) // 1 out of 4 paid = 25%

        val result = service.getAttributionMetrics("campaign")
        val metric = result.metrics.first()
        assertEquals(4, metric.signups)
        assertEquals(1, metric.paidOrganizations)
        assertEquals(25.0, metric.conversionRate)
    }

    @Test
    fun `getAttributionMetrics calculates summary totals`() {
        val tierId = seedPricingTier()
        val orgA = seedOrg("Org A", utmCampaign = "a")
        val orgB = seedOrg("Org B", utmCampaign = "b")
        seedOrg("Org C", utmCampaign = "c")

        seedActiveSubscription(orgA, tierId)
        seedActiveSubscription(orgB, tierId)

        val result = service.getAttributionMetrics("campaign")
        assertEquals(3, result.summary.totalSignups)
        assertEquals(2, result.summary.totalPaidOrganizations)
        // 2/3 = 66.67%
        assertTrue(result.summary.overallConversionRate > 66.0)
    }

    @Test
    fun `getAttributionMetrics handles all groupBy option`() {
        seedOrg("Org X", utmSource = "fb", utmMedium = "social", utmCampaign = "q1")

        val result = service.getAttributionMetrics("all")
        assertEquals(1, result.metrics.size)
        assertEquals("fb", result.metrics.first().source)
    }

    @Test
    fun `getAttributionMetrics calculates monthly MRR from subscription price`() {
        val tierId = seedPricingTier(priceCents = 4900) // $49
        val orgA = seedOrg("Org A", utmCampaign = "mrr-test")
        seedActiveSubscription(orgA, tierId)

        val result = service.getAttributionMetrics("campaign")
        val metric = result.metrics.first()
        // $49.00 (4900 cents / 100)
        assertEquals("49.00", metric.totalMrr)
    }

    @Test
    fun `getAttributionMetrics excludes FREE subscriptions from paid count`() {
        val freeTierId = seedPricingTier(priceCents = 0, tierName = "FREE_TIER")
        val orgA = seedOrg("Org A", utmCampaign = "free-test")
        seedActiveSubscription(orgA, freeTierId, plan = "FREE")

        val result = service.getAttributionMetrics("campaign")
        val metric = result.metrics.first()
        assertEquals(0, metric.paidOrganizations)
    }

    @Test
    fun `getAttributionMetrics sorts metrics by signups descending`() {
        seedOrg("Solo Org", utmCampaign = "solo")
        val orgA = seedOrg("Busy Org A", utmCampaign = "busy")
        val orgB = seedOrg("Busy Org B", utmCampaign = "busy")
        val orgC = seedOrg("Busy Org C", utmCampaign = "busy")

        val result = service.getAttributionMetrics("campaign")
        assertEquals(3, result.metrics[0].signups)
        assertEquals(1, result.metrics[1].signups)
    }

    @Test
    fun `getAttributionMetrics handles orgs with null UTM params`() {
        seedOrg("No UTM Org") // all UTM fields null

        val result = service.getAttributionMetrics("campaign")
        assertEquals(1, result.metrics.size)
        assertNull(result.metrics.first().source)
        assertNull(result.metrics.first().campaign)
    }

    @Test
    fun `getAttributionMetrics handles yearly subscription billing interval`() {
        val tierId = seedPricingTier(priceCents = 1000, yearlyPriceCents = 12000) // $120/year
        val orgA = seedOrg("Yearly Org", utmCampaign = "yearly")

        // Insert a yearly subscription
        transaction {
            Subscriptions.insert {
                it[organization_id] = orgA
                it[plan] = "PRO"
                it[status] = "active"
                it[pricing_tier_config_id] = tierId
                it[billing_interval] = "yearly"
            }
        }

        val result = service.getAttributionMetrics("campaign")
        val metric = result.metrics.first()
        // $120 / 12 months = $10.00 per month
        assertEquals("10.00", metric.totalMrr)
    }
}
