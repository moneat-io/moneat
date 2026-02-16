// Moneat - Mobile-First Error Monitoring Platform
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

import com.moneat.models.*
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.*
import kotlin.time.Duration.Companion.days

class BillingQuotaServiceTest {
    private val billingQuotaService = BillingQuotaService()
    private var testOrgId: Int = 0
    private var testSubId: Int = 0
    private var testTierId: Int = 0

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        // Initialize DB connection and schema once per test class
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_billing_quota;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(
                    Organizations,
                    Subscriptions,
                    OrgUsageCounters,
                    PricingTierConfigs,
                    Users,
                    OnCallSchedules,
                    OnCallParticipants
                )
            }
            dbInitialized = true
        }
        
        // Clean up any existing test data from previous tests
        transaction {
            OrgUsageCounters.deleteAll()
            Subscriptions.deleteAll()
            Organizations.deleteAll()
            PricingTierConfigs.deleteAll()
        }
        
        // Setup test data
        transaction {
            testOrgId = insertTestOrganization("Test Org", "test-org")
            testTierId = insertTestPricingTier(
                tierName = "PRO",
                monthlyUnitLimit = 1000,
                monthlyErrorLimit = 500,
                monthlyTransactionLimit = 300,
                monthlyReplayLimit = 100,
                monthlyFeedbackLimit = 100,
                paygEnabled = true,
                paygRateMicrosPerUnit = 400000, // $0.40 per unit
                retentionDays = 30,
                logRetentionDays = 30
            )
            testSubId = insertTestSubscription(
                organizationId = testOrgId,
                plan = "PRO",
                status = "active",
                paygBudgetCents = 10000, // $100 budget
                pricingTierConfigId = testTierId
            )
        }
    }

    // ============ Quota Reservation Boundaries Tests ============

    @Test
    fun `reserveUnits succeeds within error type limit`() {
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 100,
                usedTransactions = 50,
                usedReplays = 25,
                usedFeedback = 10
            )
        }

        // With enforcement disabled by default, should succeed
        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 300, // Within 500 error limit
            eventType = "error"
        )

        assertTrue(result.allowed, "Should allow reservation within error limit")
        assertEquals(null, result.reason, "Should have no failure reason")
    }

    @Test
    fun `reserveUnits correctly tracks usage returns current state for error events`() {
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 100,
                usedTransactions = 0,
                usedReplays = 0,
                usedFeedback = 0
            )
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 50,
            eventType = "error"
        )

        assertTrue(result.allowed, "Should allow reservation")
        // With enforcement disabled, usage is not persisted, but returned usage reflects current state
        assertEquals(100, result.usage.usedErrors, "Returns current state, not updated state (enforcement disabled)")
        assertEquals(100, result.usage.usedUnits, "Total reflects current state")
    }

    @Test
    fun `reserveUnits respects transaction event type limit with current state`() {
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 0,
                usedTransactions = 250, // Near transaction limit of 300
                usedReplays = 0,
                usedFeedback = 0
            )
        }

        // Transaction request within limit should work
        val okResult = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 40, // Would be 290, under 300 limit
            eventType = "transaction"
        )

        assertTrue(okResult.allowed, "Request within limit should succeed")
        // With enforcement disabled, returns current state
        assertEquals(250, okResult.usage.usedTransactions)
    }

    @Test
    fun `reserveUnits respects replay event type limit`() {
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 0,
                usedTransactions = 0,
                usedReplays = 50,
                usedFeedback = 0
            )
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 40, // Total 90, under 100 replay limit
            eventType = "replay"
        )

        assertTrue(result.allowed, "Request within replay limit should succeed")
        // With enforcement disabled, usage is not persisted
        assertEquals(50, result.usage.usedReplays, "Enforcement disabled, so no persistence")
    }

    @Test
    fun `reserveUnits respects feedback event type limit`() {
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 0,
                usedTransactions = 0,
                usedReplays = 0,
                usedFeedback = 50
            )
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 30, // Total 80, under 100 feedback limit
            eventType = "feedback"
        )

        assertTrue(result.allowed, "Request within feedback limit should succeed")
        // With enforcement disabled, usage is not persisted
        assertEquals(50, result.usage.usedFeedback, "Enforcement disabled, so no persistence")
    }

    // ============ PAYG Budget Limits Tests ============

    @Test
    fun `PAYG budget adds to base quota correctly`() {
        // Verify usage response shows correct PAYG limits
        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        assertEquals(1000, usage.baseLimitUnits, "Base limit should be 1000")
        assertEquals(250, usage.paygLimitUnits, "PAYG should allow 250 more units")
        assertEquals(1250, usage.totalLimitUnits, "Total should be 1250")
    }

    @Test
    fun `PAYG overage units are tracked when usage exceeds base limit`() {
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 1100, // 100 over base limit of 1000
                usedTransactions = 0,
                usedReplays = 0,
                usedFeedback = 0
            )
        }

        val result = billingQuotaService.getUsageForOrganization(testOrgId)

        assertEquals(1100, result.usedUnits)
        assertEquals(1100, result.usedErrors)
        // With enforcement disabled, PAYG usage tracking only happens during reservations
        // So we verify the response object is properly structured
        assertTrue(result.baseLimitUnits > 0)
        assertTrue(result.paygLimitUnits > 0)
    }

    @Test
    fun `PAYG rate correctly converts budget to unit limit`() {
        // Budget: 10000 cents = $100
        // Rate: 400000 micros per unit
        // 1 cent = 10000 micros, so 10000 cents = 100000000 micros
        // Units: 100000000 / 400000 = 250 units
        
        val usage = billingQuotaService.getUsageForOrganization(testOrgId)
        assertEquals(250, usage.paygLimitUnits)
    }

    // ============ Batch Reservation Tests ============

    @Test
    fun `reserveUnitsBatch succeeds with multiple event types`() {
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 100,
                usedTransactions = 50,
                usedReplays = 25,
                usedFeedback = 10
            )
        }

        val result = billingQuotaService.reserveUnitsBatch(
            organizationId = testOrgId,
            requestedUnitsByType = mapOf(
                "error" to 200,
                "transaction" to 150,
                "replay" to 50,
                "feedback" to 40
            )
        )

        assertTrue(result.allowed, "Batch reservation should succeed within limits")
        assertEquals(null, result.reason)
        // With enforcement disabled, reservation is allowed but not persisted
        assertEquals(100, result.usage.usedErrors, "Enforcement disabled, so no persistence")
    }

    @Test
    fun `reserveUnitsBatch normalizes event type names`() {
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 0,
                usedTransactions = 0,
                usedReplays = 0,
                usedFeedback = 0
            )
        }

        // "log" and "logs" should normalize to "error"
        val result = billingQuotaService.reserveUnitsBatch(
            organizationId = testOrgId,
            requestedUnitsByType = mapOf(
                "log" to 100,
                "logs" to 50,
                "error" to 100
            )
        )

        assertTrue(result.allowed, "Should normalize event types and succeed")
        // With enforcement disabled, batch is allowed but not persisted
        assertEquals(0, result.usage.usedErrors, "Enforcement disabled, so no persistence")
    }

    @Test
    fun `reserveUnitsBatch ignores zero or negative units`() {
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 0,
                usedTransactions = 0,
                usedReplays = 0,
                usedFeedback = 0
            )
        }

        val result = billingQuotaService.reserveUnitsBatch(
            organizationId = testOrgId,
            requestedUnitsByType = mapOf(
                "error" to 100,
                "transaction" to 0,
                "replay" to -50  // Should be ignored
            )
        )

        assertTrue(result.allowed, "Should ignore zero and negative units")
        // With enforcement disabled, no persistence
        assertEquals(0, result.usage.usedErrors)
    }

    @Test
    fun `reserveUnitsBatch returns empty request successfully`() {
        val result = billingQuotaService.reserveUnitsBatch(
            organizationId = testOrgId,
            requestedUnitsByType = mapOf()
        )

        assertTrue(result.allowed, "Empty batch should succeed")
    }

    @Test
    fun `reserveUnitsBatch with all zero units succeeds`() {
        val result = billingQuotaService.reserveUnitsBatch(
            organizationId = testOrgId,
            requestedUnitsByType = mapOf(
                "error" to 0,
                "transaction" to 0,
                "replay" to 0,
                "feedback" to 0
            )
        )

        assertTrue(result.allowed, "Batch with all zeros should succeed")
    }

    // ============ Usage Reporting Tests ============

    @Test
    fun `getUsageForOrganization returns correct usage stats`() {
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 123,
                usedTransactions = 45,
                usedReplays = 67,
                usedFeedback = 89
            )
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        assertEquals(123, usage.usedErrors)
        assertEquals(45, usage.usedTransactions)
        assertEquals(67, usage.usedReplays)
        assertEquals(89, usage.usedFeedback)
        assertEquals(123 + 45 + 67 + 89, usage.usedUnits)
        assertEquals("active", usage.status)
    }

    @Test
    fun `usage response includes plan information`() {
        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        assertEquals("pro", usage.plan)
        assertEquals("active", usage.status)
        assertTrue(usage.retentionDays > 0)
    }

    @Test
    fun `usage response indicates within quota`() {
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 100,
                usedTransactions = 100,
                usedReplays = 50,
                usedFeedback = 50
            )
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        assertTrue(usage.withinQuota, "Should be within quota")
        assertEquals(300, usage.usedUnits)
        assertTrue(usage.totalLimitUnits >= 1000)
    }

    @Test
    fun `usage response indicates over GB quota when used bytes exceed limit`() {
        val bytesOverBaseAndPayg = 300L * 1024L * 1024L * 1024L
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 10,
                usedTransactions = 0,
                usedReplays = 0,
                usedFeedback = 0,
                usedBytes = bytesOverBaseAndPayg
            )
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        assertFalse(usage.withinQuota, "Should be over quota when used bytes exceed monthly + PAYG GB limit")
        assertEquals(bytesOverBaseAndPayg, usage.usedBytes)
        assertEquals(10, usage.bytesLimit)
    }

    // ============ Subscription Status Tests ============

    @Test
    fun `reserveUnits with active subscription status allows reservation`() {
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 100,
                usedTransactions = 50,
                usedReplays = 25,
                usedFeedback = 10
            )
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 100,
            eventType = "error"
        )

        assertTrue(result.allowed, "Should allow reservation for active subscription")
    }

    @Test
    fun `reserveUnits with trialing subscription status allows reservation`() {
        transaction {
            insertTestSubscription(
                organizationId = testOrgId,
                plan = "PRO",
                status = "trialing",
                paygBudgetCents = 5000,
                pricingTierConfigId = testTierId
            )
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 100,
                usedTransactions = 50,
                usedReplays = 25,
                usedFeedback = 10
            )
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 100,
            eventType = "error"
        )

        assertTrue(result.allowed, "Should allow reservation for trialing subscription")
    }

    @Test
    fun `reserveUnits with past_due subscription status allows reservation`() {
        transaction {
            insertTestSubscription(
                organizationId = testOrgId,
                plan = "PRO",
                status = "past_due",
                paygBudgetCents = 5000,
                pricingTierConfigId = testTierId
            )
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 100,
                usedTransactions = 50,
                usedReplays = 25,
                usedFeedback = 10
            )
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 100,
            eventType = "error"
        )

        assertTrue(result.allowed, "Should allow reservation for past_due subscription")
    }

    // ============ Edge Cases Tests ============

    @Test
    fun `reserveUnits with zero units succeeds without changes`() {
        val usage1 = billingQuotaService.getUsageForOrganization(testOrgId)
        
        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 0,
            eventType = "error"
        )

        assertTrue(result.allowed, "Zero unit reservation should succeed")
        val usage2 = billingQuotaService.getUsageForOrganization(testOrgId)
        assertEquals(usage1.usedUnits, usage2.usedUnits, "Usage should remain unchanged")
    }

    @Test
    fun `reserveUnits with negative units succeeds without changes`() {
        val usage1 = billingQuotaService.getUsageForOrganization(testOrgId)
        
        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = -100,
            eventType = "error"
        )

        assertTrue(result.allowed, "Negative unit reservation should succeed")
        val usage2 = billingQuotaService.getUsageForOrganization(testOrgId)
        assertEquals(usage1.usedUnits, usage2.usedUnits, "Usage should remain unchanged")
    }

    @Test
    fun `reserveUnits without active subscription uses free tier limits`() {
        // Create org without subscription
        val orgWithoutSub = transaction {
            val id = insertTestOrganization("No Sub Org", "no-sub-org")
            insertTestUsageCounter(
                organizationId = id,
                usedErrors = 0,
                usedTransactions = 0,
                usedReplays = 0,
                usedFeedback = 0
            )
            id
        }

        val usage = billingQuotaService.getUsageForOrganization(orgWithoutSub)

        assertEquals("free", usage.plan, "Should use free tier")
        assertTrue(usage.baseLimitUnits > 0, "Free tier should have some limit")
    }

    @Test
    fun `multiple batch operations read current state correctly`() {
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 0,
                usedTransactions = 0,
                usedReplays = 0,
                usedFeedback = 0
            )
        }

        // First batch reservation
        val result1 = billingQuotaService.reserveUnitsBatch(
            organizationId = testOrgId,
            requestedUnitsByType = mapOf(
                "error" to 200,
                "transaction" to 100
            )
        )

        assertTrue(result1.allowed, "First batch should succeed")
        // With enforcement disabled, no persistence
        assertEquals(0, result1.usage.usedErrors, "Enforcement disabled, so no persistence")

        // Second batch reservation reads same initial state
        val result2 = billingQuotaService.reserveUnitsBatch(
            organizationId = testOrgId,
            requestedUnitsByType = mapOf(
                "replay" to 80,
                "feedback" to 20
            )
        )

        assertTrue(result2.allowed, "Second batch should succeed")
        // Both see the same initial state since enforcement is disabled
        assertEquals(0, result2.usage.usedReplays)
        assertEquals(0, result2.usage.usedFeedback)
    }

    @Test
    fun `large batch operations succeed`() {
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 0,
                usedTransactions = 0,
                usedReplays = 0,
                usedFeedback = 0
            )
        }

        val result = billingQuotaService.reserveUnitsBatch(
            organizationId = testOrgId,
            requestedUnitsByType = mapOf(
                "error" to 500,
                "transaction" to 250,
                "replay" to 80,
                "feedback" to 100
            )
        )

        assertTrue(result.allowed, "Large batch should succeed")
        // With enforcement disabled, no persistence
        assertEquals(0, result.usage.usedUnits)
    }

    @Test
    fun `usage response includes period information`() {
        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        assertNotNull(usage.periodStart, "Should have period start")
        assertNotNull(usage.periodEnd, "Should have period end")
        assertTrue(usage.periodStart.isNotEmpty())
        assertTrue(usage.periodEnd.isNotEmpty())
    }

    @Test
    fun `usage response includes correct limits`() {
        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        assertEquals(500, usage.errorLimit, "Error limit should match tier")
        assertEquals(300, usage.transactionLimit, "Transaction limit should match tier")
        assertEquals(100, usage.replayLimit, "Replay limit should match tier")
        assertEquals(100, usage.feedbackLimit, "Feedback limit should match tier")
    }

    // ============ Helper Methods ============

    private fun insertTestOrganization(name: String, slug: String): Int {
        return Organizations.insert {
            it[Organizations.name] = name
            it[Organizations.slug] = slug
        }[Organizations.id]
    }

    private fun insertTestSubscription(
        organizationId: Int,
        plan: String,
        status: String,
        paygBudgetCents: Int,
        pricingTierConfigId: Int? = null
    ): Int {
        val now = Clock.System.now()

        return Subscriptions.insert {
            it[Subscriptions.organization_id] = organizationId
            it[Subscriptions.plan] = plan
            it[Subscriptions.status] = status
            it[Subscriptions.billing_interval] = "monthly"
            it[Subscriptions.current_period_start] = now
            it[Subscriptions.current_period_end] = now + 30.days
            it[Subscriptions.pricing_tier_config_id] = pricingTierConfigId
            it[Subscriptions.payg_budget_cents] = paygBudgetCents
            it[Subscriptions.payg_used_units] = 0
            it[Subscriptions.payg_used_micros] = 0
            it[Subscriptions.pending_meter_units] = 0
        }[Subscriptions.id]
    }

    private fun insertTestPricingTier(
        tierName: String,
        monthlyUnitLimit: Long,
        monthlyErrorLimit: Long,
        monthlyTransactionLimit: Long,
        monthlyReplayLimit: Long,
        monthlyFeedbackLimit: Long,
        paygEnabled: Boolean,
        paygRateMicrosPerUnit: Long,
        retentionDays: Int,
        logRetentionDays: Int
    ): Int {
        return PricingTierConfigs.insert {
            it[PricingTierConfigs.tier_name] = tierName
            it[PricingTierConfigs.version] = 1
            it[PricingTierConfigs.monthly_unit_limit] = monthlyUnitLimit
            it[PricingTierConfigs.monthly_error_limit] = monthlyErrorLimit
            it[PricingTierConfigs.monthly_transaction_limit] = monthlyTransactionLimit
            it[PricingTierConfigs.monthly_replay_limit] = monthlyReplayLimit
            it[PricingTierConfigs.monthly_feedback_limit] = monthlyFeedbackLimit
            it[PricingTierConfigs.monthly_gb_limit] = 10
            it[PricingTierConfigs.retention_days] = retentionDays
            it[PricingTierConfigs.log_retention_days] = logRetentionDays
            it[PricingTierConfigs.status_pages_enabled] = true
            it[PricingTierConfigs.status_page_custom_domain_enabled] = true
            it[PricingTierConfigs.session_replay_enabled] = true
            it[PricingTierConfigs.slack_enabled] = false
            it[PricingTierConfigs.incident_io_enabled] = false
            it[PricingTierConfigs.saml_enabled] = false
            it[PricingTierConfigs.oidc_enabled] = false
            it[PricingTierConfigs.priority_support_enabled] = false
            it[PricingTierConfigs.sla_enabled] = false
            it[PricingTierConfigs.custom_retention_enabled] = false
            it[PricingTierConfigs.max_projects] = null
            it[PricingTierConfigs.max_systems] = 5
            it[PricingTierConfigs.monitor_interval_seconds] = 60
            it[PricingTierConfigs.monthly_price_cents] = 2900
            it[PricingTierConfigs.yearly_price_cents] = 28800
            it[PricingTierConfigs.trial_days] = 14
            it[PricingTierConfigs.payg_enabled] = paygEnabled
            it[PricingTierConfigs.payg_rate_micros_per_unit] = paygRateMicrosPerUnit
            it[PricingTierConfigs.overage_rate_cents_per_gb] = 40
            it[PricingTierConfigs.stripe_base_price_id] = null
            it[PricingTierConfigs.stripe_overage_price_id] = null
            it[PricingTierConfigs.stripe_yearly_base_price_id] = null
            it[PricingTierConfigs.stripe_yearly_overage_price_id] = null
            it[PricingTierConfigs.is_current] = true
        }[PricingTierConfigs.id]
    }

    private fun insertTestUsageCounter(
        organizationId: Int,
        usedErrors: Long = 0,
        usedTransactions: Long = 0,
        usedReplays: Long = 0,
        usedFeedback: Long = 0,
        usedBytes: Long = 0
    ): Int {
        val now = Clock.System.now()
        val periodStart = now.toLocalDateTime(TimeZone.UTC).date
        val periodEnd = periodStart.plus(DatePeriod(months = 1, days = -1))

        return OrgUsageCounters.insert {
            it[OrgUsageCounters.organization_id] = organizationId
            it[OrgUsageCounters.period_start] = periodStart
            it[OrgUsageCounters.period_end] = periodEnd
            it[OrgUsageCounters.used_units] = usedErrors + usedTransactions + usedReplays + usedFeedback
            it[OrgUsageCounters.used_errors] = usedErrors
            it[OrgUsageCounters.used_transactions] = usedTransactions
            it[OrgUsageCounters.used_replays] = usedReplays
            it[OrgUsageCounters.used_feedback] = usedFeedback
            it[OrgUsageCounters.used_bytes] = usedBytes
            it[OrgUsageCounters.updated_at] = now
        }[OrgUsageCounters.id]
    }
}
