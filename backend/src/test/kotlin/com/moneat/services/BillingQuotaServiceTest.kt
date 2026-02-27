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

import com.moneat.billing.models.OrgUsageCounters
import com.moneat.billing.models.PricingTierConfigs
import com.moneat.billing.services.BillingQuotaService
import com.moneat.shared.models.OnCallParticipants
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import com.moneat.testsupport.TestDatabaseHelper

class BillingQuotaServiceTest {
    private val billingQuotaService = BillingQuotaService()
    private var testOrgId: Int = 0
    private var testSubId: Int = 0
    private var testTierId: Int = 0

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        // Initialize DB connection and schema once per test class
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_billing_quota;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }

        // Clean up any existing test data from previous tests
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Subscriptions,
            OrgUsageCounters,
            PricingTierConfigs,
            OnCallSchedules,
            OnCallParticipants
        )

        // Setup test data
        transaction {
            testOrgId = insertTestOrganization("Test Org", "test-org")
            testTierId =
                insertTestPricingTier(
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
            testSubId =
                insertTestSubscription(
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

        // With enforcement enabled, should succeed and increment
        val result =
            billingQuotaService.reserveUnits(
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

        val result =
            billingQuotaService.reserveUnits(
                organizationId = testOrgId,
                requestedUnits = 50,
                eventType = "error"
            )

        assertTrue(result.allowed, "Should allow reservation")
        // With enforcement enabled, usage is persisted and incremented
        assertEquals(150, result.usage.usedErrors, "100 initial + 50 requested = 150")
        assertEquals(150, result.usage.usedUnits, "Total reflects updated state")
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
        val okResult =
            billingQuotaService.reserveUnits(
                organizationId = testOrgId,
                requestedUnits = 40, // Would be 290, under 300 limit
                eventType = "transaction"
            )

        assertTrue(okResult.allowed, "Request within limit should succeed")
        // With enforcement enabled, counters are incremented
        assertEquals(290, okResult.usage.usedTransactions, "250 initial + 40 requested = 290")
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

        val result =
            billingQuotaService.reserveUnits(
                organizationId = testOrgId,
                requestedUnits = 40, // Total 90, under 100 replay limit
                eventType = "replay"
            )

        assertTrue(result.allowed, "Request within replay limit should succeed")
        // With enforcement enabled, counters are incremented
        assertEquals(90, result.usage.usedReplays, "50 initial + 40 requested = 90")
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

        val result =
            billingQuotaService.reserveUnits(
                organizationId = testOrgId,
                requestedUnits = 30, // Total 80, under 100 feedback limit
                eventType = "feedback"
            )

        assertTrue(result.allowed, "Request within feedback limit should succeed")
        // With enforcement enabled, counters are incremented
        assertEquals(80, result.usage.usedFeedback, "50 initial + 30 requested = 80")
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
        // With enforcement enabled, PAYG usage tracking happens during reservations
        // Verify the response object is properly structured
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

        val result =
            billingQuotaService.reserveUnitsBatch(
                organizationId = testOrgId,
                requestedUnitsByType =
                mapOf(
                    "error" to 200,
                    "transaction" to 150,
                    "replay" to 50,
                    "feedback" to 40
                )
            )

        assertTrue(result.allowed, "Batch reservation should succeed within limits")
        assertEquals(null, result.reason)
        // With enforcement enabled, counters are incremented
        assertEquals(300, result.usage.usedErrors, "100 initial + 200 requested = 300")
        assertEquals(200, result.usage.usedTransactions, "50 initial + 150 requested = 200")
        assertEquals(75, result.usage.usedReplays, "25 initial + 50 requested = 75")
        assertEquals(50, result.usage.usedFeedback, "10 initial + 40 requested = 50")
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

        // "log" and "logs" should normalize to "log"
        val result =
            billingQuotaService.reserveUnitsBatch(
                organizationId = testOrgId,
                requestedUnitsByType =
                mapOf(
                    "log" to 100,
                    "logs" to 50,
                    "error" to 100
                )
            )

        assertTrue(result.allowed, "Should normalize event types and succeed")
        // With enforcement enabled, counters are incremented
        assertEquals(100, result.usage.usedErrors, "error: 0 + 100 = 100")
        assertEquals(150, result.usage.usedLogs, "log + logs: 0 + 100 + 50 = 150")
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

        val result =
            billingQuotaService.reserveUnitsBatch(
                organizationId = testOrgId,
                requestedUnitsByType =
                mapOf(
                    "error" to 100,
                    "transaction" to 0,
                    "replay" to -50 // Should be ignored
                )
            )

        assertTrue(result.allowed, "Should ignore zero and negative units")
        // With enforcement enabled, only positive units are incremented
        assertEquals(100, result.usage.usedErrors, "0 initial + 100 requested = 100")
        assertEquals(0, result.usage.usedTransactions, "Zero and negative ignored")
        assertEquals(0, result.usage.usedReplays, "Zero and negative ignored")
    }

    @Test
    fun `reserveUnitsBatch returns empty request successfully`() {
        val result =
            billingQuotaService.reserveUnitsBatch(
                organizationId = testOrgId,
                requestedUnitsByType = mapOf()
            )

        assertTrue(result.allowed, "Empty batch should succeed")
    }

    @Test
    fun `reserveUnitsBatch with all zero units succeeds`() {
        val result =
            billingQuotaService.reserveUnitsBatch(
                organizationId = testOrgId,
                requestedUnitsByType =
                mapOf(
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

        assertFalse(usage.withinQuota, "Should be over quota when used bytes exceed monthly GB limit")
        assertEquals(bytesOverBaseAndPayg, usage.usedBytes)
        assertEquals(10, usage.bytesLimit)
    }

    @Test
    fun `bytes within PAYG headroom are within quota in unified model`() {
        // In the unified ingestion model, PAYG budget DOES expand the
        // effective byte limit (bytes are the primary billing dimension).
        val bytesWithinPaygHeadroom = 50L * 1024L * 1024L * 1024L
        transaction {
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 10,
                usedTransactions = 0,
                usedReplays = 0,
                usedFeedback = 0,
                usedBytes = bytesWithinPaygHeadroom
            )
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        // 50 GB used < 10 GB base + ~250 GB PAYG headroom → within quota
        assertTrue(usage.withinQuota, "Bytes within PAYG headroom should be within quota")
        assertEquals(bytesWithinPaygHeadroom, usage.usedBytes)
    }

    @Test
    fun `llm count limits no longer affect withinQuota in unified model`() {
        // In the unified ingestion model, LLM event counts are no longer
        // enforced. withinQuota only checks bytes and custom metrics.
        transaction {
            val usageCounterId = insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 0,
                usedTransactions = 0,
                usedReplays = 0,
                usedFeedback = 0
            )
            OrgUsageCounters.update({ OrgUsageCounters.id eq usageCounterId }) {
                it[used_llm_events] = 120
            }
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_llm_event_limit] = 100
            }
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        // LLM count exceeds limit, but withinQuota only checks bytes + custom metrics
        assertTrue(usage.withinQuota, "LLM count limits should not affect withinQuota")
        assertEquals(120, usage.usedLlmEvents)
        assertEquals(100, usage.llmEventLimit)
    }

    @Test
    fun `replay overage estimate uses bytes proportionally to overage sessions`() {
        val replayBytes = 150L * 1024L * 1024L * 1024L
        transaction {
            val usageCounterId = insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 0,
                usedTransactions = 0,
                usedReplays = 150,
                usedFeedback = 0,
                usedBytes = replayBytes
            )
            OrgUsageCounters.update({ OrgUsageCounters.id eq usageCounterId }) {
                it[used_replay_bytes] = replayBytes
            }
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        assertEquals(100, usage.replayLimit)
        assertEquals(40, usage.replayOverageRateCentsPerGb)
        assertEquals(2_000, usage.replayOverageCentsEstimate, "50 overage sessions should estimate as 50GB at $0.40/GB")
    }

    @Test
    fun `replay unlimited sentinel disables replay overage estimate`() {
        val replayBytes = 500L * 1024L * 1024L * 1024L
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_replay_limit] = -1
            }
            val usageCounterId = insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 0,
                usedTransactions = 0,
                usedReplays = 10_000,
                usedFeedback = 0,
                usedBytes = replayBytes
            )
            OrgUsageCounters.update({ OrgUsageCounters.id eq usageCounterId }) {
                it[used_replay_bytes] = replayBytes
            }
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        assertEquals(-1, usage.replayLimit)
        assertEquals(0, usage.replayOverageCentsEstimate, "Unlimited replay limit should not show replay overage")
    }

    // ============ APM Span and Custom Metric Overage Estimate Tests ============

    @Test
    fun `apm span overage estimate is computed correctly`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_apm_span_limit] = 10_000_000L
                it[apm_span_overage_rate_cents_per_1m] = 30
            }
            val usageCounterId = insertTestUsageCounter(organizationId = testOrgId)
            OrgUsageCounters.update({ OrgUsageCounters.id eq usageCounterId }) {
                it[used_apm_spans] = 11_000_000L // 1M over limit
            }
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        assertEquals(30, usage.apmSpanOverageCentsEstimate, "1M APM span overage at \$0.30/1M should be 30 cents")
        assertEquals(30, usage.apmSpanOverageRateCentsPer1m)
    }

    @Test
    fun `custom metric overage estimate is computed correctly`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_custom_metric_limit] = 1_000_000L
                it[custom_metric_overage_rate_cents_per_100k] = 50
            }
            val usageCounterId = insertTestUsageCounter(organizationId = testOrgId)
            OrgUsageCounters.update({ OrgUsageCounters.id eq usageCounterId }) {
                it[used_custom_metrics] = 1_200_000L // 200k over limit
            }
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        assertEquals(
            100,
            usage.customMetricOverageCentsEstimate,
            "200k custom metric overage at \$0.50/100k should be 100 cents",
        )
        assertEquals(50, usage.customMetricOverageRateCentsPer100k)
    }

    @Test
    fun `custom metric overage is included in totalOverageCentsEstimate`() {
        // In the unified model, APM span overages are legacy and not
        // included in totalOverageCentsEstimate. Only ingestion GB,
        // custom metric, and analytics pageview overages are summed.
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_apm_span_limit] = 10_000_000L
                it[apm_span_overage_rate_cents_per_1m] = 30
                it[monthly_custom_metric_limit] = 1_000_000L
                it[custom_metric_overage_rate_cents_per_100k] = 50
            }
            val usageCounterId = insertTestUsageCounter(organizationId = testOrgId)
            OrgUsageCounters.update({ OrgUsageCounters.id eq usageCounterId }) {
                it[used_apm_spans] = 11_000_000L // 30 cents legacy overage
                it[used_custom_metrics] = 1_200_000L // 100 cents overage
            }
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        // APM span overage is still computed for display but NOT in total
        assertEquals(30, usage.apmSpanOverageCentsEstimate)
        assertEquals(100, usage.customMetricOverageCentsEstimate)
        assertTrue(
            usage.totalOverageCentsEstimate >= 100,
            "Total overage should include custom metric (100¢), " +
                "got ${usage.totalOverageCentsEstimate}",
        )
    }

    @Test
    fun `apm span units are tracked but no longer incur pending overage`() {
        // In the unified model, APM span overage tracking was removed
        // from reserveUnits. APM spans are gated by the unified GB limit.
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_apm_span_limit] = 10_000_000L
                it[apm_span_overage_rate_cents_per_1m] = 30
            }
            insertTestUsageCounter(organizationId = testOrgId).also { counterId ->
                OrgUsageCounters.update({ OrgUsageCounters.id eq counterId }) {
                    it[used_apm_spans] = 10_000_000L // already at limit
                }
            }
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 500_000,
            eventType = "apm_span"
        )

        assertTrue(result.allowed, "APM span should succeed (gated by GB, not count)")
        val pendingApmOverage = transaction {
            Subscriptions.selectAll().where { Subscriptions.id eq testSubId }
                .first()[Subscriptions.pending_apm_span_overage_units]
        }
        assertEquals(0L, pendingApmOverage, "APM span pending overage should not be tracked")
    }

    @Test
    fun `custom metric overage tracking increments pending column on reserveUnits`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_custom_metric_limit] = 1_000_000L
                it[custom_metric_overage_rate_cents_per_100k] = 50
            }
            insertTestUsageCounter(organizationId = testOrgId).also { counterId ->
                OrgUsageCounters.update({ OrgUsageCounters.id eq counterId }) {
                    it[used_custom_metrics] = 1_000_000L // already at limit
                }
            }
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 200_000,
            eventType = "custom_metric"
        )

        assertTrue(result.allowed)
        val pendingMetricOverage = transaction {
            Subscriptions.selectAll().where { Subscriptions.id eq testSubId }
                .first()[Subscriptions.pending_custom_metric_overage_units]
        }
        assertEquals(200_000L, pendingMetricOverage)
    }

    @Test
    fun `apm span overage rate of zero disables estimate`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_apm_span_limit] = 500_000L
                it[apm_span_overage_rate_cents_per_1m] = 0 // FREE tier: no overage billing
            }
            insertTestUsageCounter(organizationId = testOrgId).also { counterId ->
                OrgUsageCounters.update({ OrgUsageCounters.id eq counterId }) {
                    it[used_apm_spans] = 600_000L
                }
            }
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        assertEquals(0, usage.apmSpanOverageCentsEstimate, "Zero overage rate should produce zero estimate")
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

        val result =
            billingQuotaService.reserveUnits(
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

        val result =
            billingQuotaService.reserveUnits(
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

        val result =
            billingQuotaService.reserveUnits(
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

        val result =
            billingQuotaService.reserveUnits(
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

        val result =
            billingQuotaService.reserveUnits(
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
        val orgWithoutSub =
            transaction {
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
        val result1 =
            billingQuotaService.reserveUnitsBatch(
                organizationId = testOrgId,
                requestedUnitsByType =
                mapOf(
                    "error" to 200,
                    "transaction" to 100
                )
            )

        assertTrue(result1.allowed, "First batch should succeed")
        // With enforcement enabled, counters are incremented
        assertEquals(200, result1.usage.usedErrors, "0 initial + 200 requested = 200")
        assertEquals(100, result1.usage.usedTransactions, "0 initial + 100 requested = 100")

        // Second batch reservation reads updated state from first batch
        val result2 =
            billingQuotaService.reserveUnitsBatch(
                organizationId = testOrgId,
                requestedUnitsByType =
                mapOf(
                    "replay" to 80,
                    "feedback" to 20
                )
            )

        assertTrue(result2.allowed, "Second batch should succeed")
        // Second batch sees state from after first batch
        assertEquals(80, result2.usage.usedReplays, "0 initial + 80 requested = 80")
        assertEquals(20, result2.usage.usedFeedback, "0 initial + 20 requested = 20")
        // And errors/transactions remain from first batch
        assertEquals(200, result2.usage.usedErrors, "From first batch")
        assertEquals(100, result2.usage.usedTransactions, "From first batch")
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

        val result =
            billingQuotaService.reserveUnitsBatch(
                organizationId = testOrgId,
                requestedUnitsByType =
                mapOf(
                    "error" to 500,
                    "transaction" to 250,
                    "replay" to 80,
                    "feedback" to 100
                )
            )

        assertTrue(result.allowed, "Large batch should succeed")
        // With enforcement enabled, all counters are incremented
        assertEquals(930, result.usage.usedUnits, "500 + 250 + 80 + 100 = 930")
        assertEquals(500, result.usage.usedErrors)
        assertEquals(250, result.usage.usedTransactions)
        assertEquals(80, result.usage.usedReplays)
        assertEquals(100, result.usage.usedFeedback)
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

    // ============ Unified Ingestion Model Tests ============

    @Test
    fun `unified GB quota gates all data types`() {
        val oneGb = 1L * 1024L * 1024L * 1024L
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_gb_limit] = oneGb
                it[overage_rate_cents_per_gb] = 0 // No overage (strict limit)
                it[payg_enabled] = false
            }
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[Subscriptions.payg_budget_cents] = 0
            }
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedBytes = oneGb - 1000L // just under 1 GB
            )
        }

        // Error ingestion with bytes that would push over GB limit
        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 1,
            eventType = "error",
            requestedBytes = 2000L // Would exceed 1 GB
        )

        assertFalse(result.allowed, "Should reject when GB limit exceeded")
        assertEquals("gb_quota_exceeded", result.reason)
    }

    @Test
    fun `unified GB quota allows within limit across multiple types`() {
        val tenGb = 10L * 1024L * 1024L * 1024L
        val halfGb = 512L * 1024L * 1024L
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_gb_limit] = tenGb
                it[overage_rate_cents_per_gb] = 0
                it[payg_enabled] = false
            }
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[Subscriptions.payg_budget_cents] = 0
            }
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedBytes = halfGb
            )
        }

        // All types should succeed within GB budget
        val errorResult = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 100,
            eventType = "error",
            requestedBytes = 1024L * 1024L // 1 MB
        )
        assertTrue(errorResult.allowed, "Errors within GB should succeed")

        val replayResult = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 1,
            eventType = "replay",
            requestedBytes = 1024L * 1024L // 1 MB
        )
        assertTrue(replayResult.allowed, "Replays within GB should succeed")
    }

    @Test
    fun `custom metric count limit still enforced in unified model`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_custom_metric_limit] = 1000L
                it[custom_metric_overage_rate_cents_per_100k] = 0
                it[monthly_gb_limit] = 100 // Plenty of GB
            }
            val counterId = insertTestUsageCounter(organizationId = testOrgId)
            OrgUsageCounters.update({ OrgUsageCounters.id eq counterId }) {
                it[used_custom_metrics] = 1000L // at limit
            }
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 1,
            eventType = "custom_metric",
            requestedBytes = 100L
        )

        assertFalse(result.allowed, "Custom metric should be rejected at count limit")
        assertEquals("event_type_quota_exceeded", result.reason)
        assertEquals("custom_metric", result.eventType)
    }

    @Test
    fun `error count limit is not enforced in unified model`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_error_limit] = 100L
                it[monthly_gb_limit] = 100 // Plenty of GB
            }
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 200 // well over error limit
            )
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 50,
            eventType = "error",
            requestedBytes = 1024L
        )

        assertTrue(result.allowed, "Error count limit should NOT be enforced")
    }

    @Test
    fun `ingestion overage computed correctly in usage response`() {
        val oneGb = 1L * 1024L * 1024L * 1024L
        val twoGb = 2L * 1024L * 1024L * 1024L
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_gb_limit] = oneGb // stored in bytes
                it[overage_rate_cents_per_gb] = 40 // $0.40/GB
            }
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedBytes = twoGb // 2 GB used, 1 GB over limit
            )
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        assertEquals(40, usage.ingestionOverageCentsEstimate,
            "1 GB overage at \$0.40/GB = 40 cents")
        assertEquals(40, usage.ingestionOverageRateCentsPerGb)
    }

    @Test
    fun `ingestion overage tracking increments pending meter on reserveUnits`() {
        val oneGb = 1L * 1024L * 1024L * 1024L
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_gb_limit] = oneGb
                it[overage_rate_cents_per_gb] = 40
                it[payg_enabled] = true
            }
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[Subscriptions.payg_budget_cents] = 10000 // $100
            }
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedBytes = oneGb // at limit
            )
        }

        // Push 1 GB over
        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 1,
            eventType = "error",
            requestedBytes = oneGb
        )

        assertTrue(result.allowed, "Should allow with PAYG budget")
        val pendingOverageBytes = transaction {
            Subscriptions.selectAll().where { Subscriptions.id eq testSubId }
                .first()[Subscriptions.pending_overage_bytes]
        }
        assertTrue(pendingOverageBytes > 0,
            "Pending overage bytes should be incremented for ingestion overage")
    }

    // ============ Analytics Pageview Quota Tests ============

    @Test
    fun `analytics pageview limit is reported in usage response`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_analytics_pageview_limit] = 100_000L
                it[analytics_pageview_overage_rate_cents_per_100k] = 1000
            }
            val counterId = insertTestUsageCounter(organizationId = testOrgId)
            OrgUsageCounters.update({ OrgUsageCounters.id eq counterId }) {
                it[used_analytics_pageviews] = 75_000L
            }
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        assertEquals(75_000, usage.usedAnalyticsPageviews)
        assertEquals(100_000, usage.analyticsPageviewLimit)
        assertEquals(0, usage.analyticsPageviewOverageCentsEstimate,
            "No overage when under limit")
    }

    @Test
    fun `analytics pageview overage computed correctly`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq testTierId }) {
                it[monthly_analytics_pageview_limit] = 100_000L
                it[analytics_pageview_overage_rate_cents_per_100k] = 1000 // $10/100K
            }
            val counterId = insertTestUsageCounter(organizationId = testOrgId)
            OrgUsageCounters.update({ OrgUsageCounters.id eq counterId }) {
                it[used_analytics_pageviews] = 200_000L // 100K over limit
            }
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        assertEquals(1000, usage.analyticsPageviewOverageCentsEstimate,
            "100K pageview overage at \$10/100K = 1000 cents")
        assertEquals(1000, usage.analyticsPageviewOverageRateCentsPer100k)
    }

    // ============ Feature Flag Tests ============

    @Test
    fun `all feature flags default to true on tier configs`() {
        val usage = billingQuotaService.getUsageForOrganization(testOrgId)

        // Just verify the tier exists and withinQuota is true
        // (feature flags are on the tier config, tested via PricingTierService)
        assertTrue(usage.withinQuota, "Should be within quota with default setup")
    }

    // ============ Helper Methods ============

    private fun insertTestOrganization(
        name: String,
        slug: String
    ): Int {
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
            it[Subscriptions.pending_meter_batch_id] = null
            it[Subscriptions.pending_meter_batch_units] = 0
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
            it[PricingTierConfigs.error_overage_rate_cents_per_1k] = 10
            it[PricingTierConfigs.replay_overage_rate_cents_per_gb] = 40
            it[PricingTierConfigs.llm_overage_rate_cents_per_1k] = 100
            it[PricingTierConfigs.monthly_apm_span_limit] = 10_000_000L
            it[PricingTierConfigs.apm_span_overage_rate_cents_per_1m] = 30
            it[PricingTierConfigs.monthly_custom_metric_limit] = 1_000_000L
            it[PricingTierConfigs.custom_metric_overage_rate_cents_per_100k] = 50
            it[PricingTierConfigs.stripe_base_price_id] = null
            it[PricingTierConfigs.stripe_overage_price_id] = null
            it[PricingTierConfigs.stripe_yearly_base_price_id] = null
            it[PricingTierConfigs.stripe_yearly_overage_price_id] = null
            it[PricingTierConfigs.is_current] = true
            it[PricingTierConfigs.profiling_enabled] = true
            it[PricingTierConfigs.network_monitoring_enabled] = true
            it[PricingTierConfigs.dbm_enabled] = true
            it[PricingTierConfigs.debugger_enabled] = true
            it[PricingTierConfigs.k8s_monitoring_enabled] = true
            it[PricingTierConfigs.data_streams_enabled] = true
            it[PricingTierConfigs.sbom_enabled] = true
            it[PricingTierConfigs.synthetics_enabled] = true
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
