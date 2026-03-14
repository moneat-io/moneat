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
import com.moneat.billing.models.StripeWebhookEvents
import com.moneat.billing.repositories.SubscriptionRepositoryImpl
import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.StripeService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OnCallParticipants
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.shared.repositories.OrganizationRepositoryImpl
import com.moneat.testsupport.TestDatabaseHelper
import com.stripe.model.Invoice
import com.stripe.model.Subscription
import com.stripe.model.SubscriptionItem
import com.stripe.model.SubscriptionItemCollection
import com.stripe.param.billing.MeterEventCreateParams
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.and
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class BillingServicesExtendedTest {
    private val stripeService = StripeService(
        subscriptionRepository = SubscriptionRepositoryImpl(),
        organizationRepository = OrganizationRepositoryImpl(),
        allowMeteringWhenStripeDisabled = true
    )
    private val billingQuotaService = BillingQuotaService()

    private var testOrgId: Int = 0
    private var secondOrgId: Int = 0
    private var freeTierId: Int = 0
    private var proTierId: Int = 0
    private var testSubId: Int = 0

    private val mockCustomerId = "cus_ext_test_123"
    private val mockSubscriptionId = "sub_ext_test_456"

    companion object {
        private var db: Database? = null
        private const val BYTES_PER_GB = 1_073_741_824L
        private const val MSG_SHOULD_EMIT_ONE_METER = "Should emit one meter event"
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:billing_ext_test;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            Subscriptions,
            StripeWebhookEvents,
            PricingTierConfigs,
            OrgUsageCounters,
            OnCallSchedules,
            OnCallParticipants
        )

        transaction {
            testOrgId = insertTestOrganization("Test Org", "test-org")
            secondOrgId = insertTestOrganization("Second Org", "second-org")
            freeTierId = insertTestPricingTier(
                tierName = "FREE",
                monthlyUnitLimit = 100,
                monthlyErrorLimit = 100,
                paygEnabled = false,
                paygRateMicrosPerUnit = 0,
                overageRateCentsPerGb = 0,
                monthlyGbLimit = BYTES_PER_GB
            )
            proTierId = insertTestPricingTier(
                tierName = "PRO",
                monthlyUnitLimit = 1000,
                monthlyErrorLimit = 500,
                paygEnabled = true,
                paygRateMicrosPerUnit = 400000,
                overageRateCentsPerGb = 40,
                monthlyGbLimit = 10L * BYTES_PER_GB
            )
            testSubId = insertTestSubscription(
                SubscriptionParams(
                    organizationId = testOrgId,
                    plan = "PRO",
                    status = "active",
                    paygBudgetCents = 10000,
                    pricingTierConfigId = proTierId,
                    stripeCustomerId = mockCustomerId,
                    stripeSubscriptionId = mockSubscriptionId
                )
            )
        }
    }

    // ==================== StripeService: handleSubscriptionDeleted edge cases ====================

    @Test
    fun `handleSubscriptionDeleted with unresolvable org silently returns`() {
        val subscription = mockSubscription(
            subscriptionId = "sub_orphan",
            customerId = "cus_nonexistent",
            status = "canceled",
            organizationId = 0,
            withOrgMetadata = false
        )
        // Should not throw; returns early when org cannot be resolved
        stripeService.handleSubscriptionDeleted(subscription)

        // Verify no new free subscription was created for testOrgId
        val subs = transaction {
            Subscriptions.selectAll()
                .where { Subscriptions.organization_id eq testOrgId }
                .toList()
        }
        assertEquals(1, subs.size, "Original subscription should be unchanged")
    }

    @Test
    fun `handleSubscriptionDeleted creates free replacement with zeroed PAYG fields`() {
        val subscription = mockSubscription(
            subscriptionId = mockSubscriptionId,
            customerId = mockCustomerId,
            status = "canceled",
            organizationId = testOrgId
        )
        stripeService.handleSubscriptionDeleted(subscription)

        val subs = transaction {
            Subscriptions.selectAll()
                .where { Subscriptions.organization_id eq testOrgId }
                .toList()
        }
        val canceled = subs.find { it[Subscriptions.status] == "canceled" }
        val free = subs.find { it[Subscriptions.status] == "active" && it[Subscriptions.plan] == "free" }

        assertTrue(canceled != null, "Original subscription should be canceled")
        assertTrue(free != null, "Free replacement subscription should exist")
        assertEquals(0, free[Subscriptions.payg_budget_cents])
        assertEquals(0L, free[Subscriptions.payg_used_units])
        assertEquals(0L, free[Subscriptions.payg_used_micros])
        assertEquals(0L, free[Subscriptions.pending_meter_units])
        assertEquals(0L, free[Subscriptions.pending_meter_batch_units])
    }

    // ==================== StripeService: handleInvoicePaid edge cases ====================

    @Test
    fun `handleInvoicePaid with unresolvable org silently returns`() {
        val invoice = mockInvoice(
            customerId = "cus_nonexistent",
            organizationId = 0,
            withOrgMetadata = false,
            billingReason = "subscription_cycle"
        )
        // Should not throw
        stripeService.handleInvoicePaid(invoice)
    }

    @Test
    fun `handleInvoicePaid resolves org via customer ID when metadata absent`() {
        transaction {
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[status] = "past_due"
            }
        }
        val invoice = mockInvoice(
            customerId = mockCustomerId,
            organizationId = 0,
            withOrgMetadata = false,
            billingReason = "subscription_cycle"
        )
        // Should resolve org via customer ID and update subscription
        stripeService.handleInvoicePaid(invoice)

        val sub = transaction {
            Subscriptions.selectAll()
                .where { Subscriptions.id eq testSubId }
                .first()
        }
        assertEquals("active", sub[Subscriptions.status])
    }

    // ==================== StripeService: handleInvoicePaymentFailed edge cases ====================

    @Test
    fun `handleInvoicePaymentFailed with unresolvable org silently returns`() {
        val invoice = mockInvoice(
            customerId = "cus_nonexistent",
            organizationId = 0,
            withOrgMetadata = false,
            billingReason = "subscription_cycle"
        )
        // Should not throw
        stripeService.handleInvoicePaymentFailed(invoice)
    }

    @Test
    fun `handleInvoicePaymentFailed sets billing_grace_until`() {
        val invoice = mockInvoice(
            customerId = mockCustomerId,
            organizationId = testOrgId,
            billingReason = "subscription_cycle"
        )

        stripeService.handleInvoicePaymentFailed(invoice, graceDays = 3)

        val sub = transaction {
            Subscriptions.selectAll()
                .where { Subscriptions.id eq testSubId }
                .first()
        }
        assertEquals("past_due", sub[Subscriptions.status])
        val graceUntil = sub[Subscriptions.billing_grace_until]
        assertTrue(graceUntil != null, "billing_grace_until should be set")
        val expectedMinGrace = Clock.System.now() + 2.days
        assertTrue(
            graceUntil > expectedMinGrace,
            "Grace period should be at least 2 days from now"
        )
    }

    // ==================== StripeService: applyDunningDowngrade edge cases ====================

    @Test
    fun `applyDunningDowngrade returns zero when no past_due subscriptions exist`() {
        val result = stripeService.applyDunningDowngrade()
        assertEquals(0, result, "No past_due subscriptions to downgrade")
    }

    @Test
    fun `applyDunningDowngrade ignores past_due without expired grace period`() {
        transaction {
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[status] = "past_due"
                it[billing_grace_until] = Clock.System.now() + 7.days
            }
        }

        val result = stripeService.applyDunningDowngrade()
        assertEquals(0, result, "Should not downgrade when grace period hasn't expired")

        val sub = transaction {
            Subscriptions.selectAll().where { Subscriptions.id eq testSubId }.first()
        }
        assertEquals("past_due", sub[Subscriptions.status])
    }

    @Test
    fun `applyDunningDowngrade processes multiple expired past_due subscriptions`() {
        val expiredGrace = Clock.System.now() - 1.days
        transaction {
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[status] = "past_due"
                it[billing_grace_until] = expiredGrace
            }
            insertTestSubscription(
                SubscriptionParams(
                    organizationId = secondOrgId,
                    plan = "PRO",
                    status = "past_due",
                    paygBudgetCents = 5000,
                    pricingTierConfigId = proTierId,
                    stripeCustomerId = "cus_second",
                    billingGraceUntil = expiredGrace
                )
            )
        }

        val result = stripeService.applyDunningDowngrade()
        assertEquals(2, result, "Both expired subscriptions should be downgraded")

        val org1Subs = transaction {
            Subscriptions.selectAll()
                .where { Subscriptions.organization_id eq testOrgId }
                .toList()
        }
        assertTrue(
            org1Subs.any { it[Subscriptions.status] == "canceled" },
            "Original should be canceled"
        )
        assertTrue(
            org1Subs.any { it[Subscriptions.plan] == "free" && it[Subscriptions.status] == "active" },
            "Free replacement should be created"
        )
    }

    // ==================== StripeService: flushPendingMeteredUsage edge cases ====================

    @Test
    fun `flushPendingMeteredUsage drains pending_overage_bytes into meter units`() {
        val captured = mutableListOf<MeterEventCreateParams>()
        val meteringService = StripeService(
            subscriptionRepository = SubscriptionRepositoryImpl(),
            organizationRepository = OrganizationRepositoryImpl(),
            meterEventSender = { captured.add(it) },
            allowMeteringWhenStripeDisabled = true
        )

        val unitSize = BYTES_PER_GB / 100
        transaction {
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[pending_meter_units] = 0
                it[pending_overage_bytes] = unitSize * 5 + (unitSize / 2)
                it[pending_meter_batch_id] = null
                it[pending_meter_batch_units] = 0
            }
        }

        val flushed = meteringService.flushPendingMeteredUsage(limit = 10)
        assertEquals(1, flushed, "Should flush one subscription")

        assertEquals(1, captured.size, MSG_SHOULD_EMIT_ONE_METER)
        val event = captured.first()
        assertEquals("moneat_overage_units", event.eventName)
        assertEquals(mockCustomerId, event.payload["stripe_customer_id"])
        assertEquals("5", event.payload["value"])

        val sub = transaction {
            Subscriptions.selectAll().where { Subscriptions.id eq testSubId }.first()
        }
        assertEquals(0L, sub[Subscriptions.pending_meter_units], "Pending units should be cleared")
        val remainingBytes = sub[Subscriptions.pending_overage_bytes]
        assertEquals(
            unitSize / 2,
            remainingBytes,
            "Sub-unit bytes should remain for next flush"
        )
    }

    @Test
    fun `flushPendingMeteredUsage skips subscription with no stripe_customer_id`() {
        val captured = mutableListOf<MeterEventCreateParams>()
        val meteringService = StripeService(
            subscriptionRepository = SubscriptionRepositoryImpl(),
            organizationRepository = OrganizationRepositoryImpl(),
            meterEventSender = { captured.add(it) },
            allowMeteringWhenStripeDisabled = true
        )

        transaction {
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[stripe_customer_id] = null
                it[pending_meter_units] = 100
            }
        }

        val flushed = meteringService.flushPendingMeteredUsage(limit = 10)
        assertEquals(0, flushed, "Should skip subscription without customer ID")
        assertTrue(captured.isEmpty(), "No meter events should be sent")
    }

    @Test
    fun `flushPendingMeteredUsage flushes custom metric overage`() {
        val captured = mutableListOf<MeterEventCreateParams>()
        val meteringService = StripeService(
            subscriptionRepository = SubscriptionRepositoryImpl(),
            organizationRepository = OrganizationRepositoryImpl(),
            meterEventSender = { captured.add(it) },
            allowMeteringWhenStripeDisabled = true
        )

        transaction {
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[pending_meter_units] = 0
                it[pending_overage_bytes] = 0
                it[pending_custom_metric_overage_units] = 500
                it[pending_custom_metric_batch_id] = null
                it[pending_custom_metric_batch_units] = 0
            }
        }

        val flushed = meteringService.flushPendingMeteredUsage(limit = 10)
        assertEquals(1, flushed, "Should flush custom metric overage")

        assertEquals(1, captured.size, MSG_SHOULD_EMIT_ONE_METER)
        val event = captured.first()
        assertEquals("moneat_custom_metric_overage_units", event.eventName)
        assertEquals(mockCustomerId, event.payload["stripe_customer_id"])
        assertEquals("500", event.payload["value"])

        val sub = transaction {
            Subscriptions.selectAll().where { Subscriptions.id eq testSubId }.first()
        }
        assertEquals(
            0L,
            sub[Subscriptions.pending_custom_metric_overage_units],
            "Custom metric pending should be cleared"
        )
    }

    @Test
    fun `flushPendingMeteredUsage flushes APM span overage`() {
        val captured = mutableListOf<MeterEventCreateParams>()
        val meteringService = StripeService(
            subscriptionRepository = SubscriptionRepositoryImpl(),
            organizationRepository = OrganizationRepositoryImpl(),
            meterEventSender = { captured.add(it) },
            allowMeteringWhenStripeDisabled = true
        )

        transaction {
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[pending_meter_units] = 0
                it[pending_overage_bytes] = 0
                it[pending_apm_span_overage_units] = 1000
                it[pending_apm_span_batch_id] = null
                it[pending_apm_span_batch_units] = 0
            }
        }

        val flushed = meteringService.flushPendingMeteredUsage(limit = 10)
        assertEquals(1, flushed, "Should flush APM span overage")

        assertEquals(1, captured.size, MSG_SHOULD_EMIT_ONE_METER)
        val event = captured.first()
        assertEquals("moneat_apm_span_overage_units", event.eventName)
        assertEquals(mockCustomerId, event.payload["stripe_customer_id"])
        assertEquals("1000", event.payload["value"])

        val sub = transaction {
            Subscriptions.selectAll().where { Subscriptions.id eq testSubId }.first()
        }
        assertEquals(
            0L,
            sub[Subscriptions.pending_apm_span_overage_units],
            "APM span pending should be cleared"
        )
    }

    @Test
    fun `flushPendingMeteredUsage excludes canceled subscription status`() {
        val captured = mutableListOf<MeterEventCreateParams>()
        val meteringService = StripeService(
            subscriptionRepository = SubscriptionRepositoryImpl(),
            organizationRepository = OrganizationRepositoryImpl(),
            meterEventSender = { captured.add(it) },
            allowMeteringWhenStripeDisabled = true
        )

        transaction {
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[status] = "canceled"
                it[pending_meter_units] = 100
            }
        }

        val flushed = meteringService.flushPendingMeteredUsage(limit = 10)
        assertEquals(0, flushed, "Canceled subscriptions should not be flushed")
    }

    // ==================== StripeService: syncSubscriptionFromStripe edge cases ====================

    @Test
    fun `syncSubscriptionFromStripe resolves billing interval as yearly from price`() {
        val subscription = Subscription()
        subscription.id = "sub_yearly_test"
        subscription.customer = mockCustomerId
        subscription.status = "active"
        subscription.startDate = System.currentTimeMillis() / 1000
        subscription.metadata = mapOf(
            "organization_id" to testOrgId.toString(),
            "tier_name" to "PRO"
        )

        val price = com.stripe.model.Price()
        price.id = "price_yearly_base"
        val recurring = com.stripe.model.Price.Recurring()
        recurring.interval = "year"
        price.recurring = recurring

        val item = SubscriptionItem()
        item.id = "si_yearly_base"
        item.price = price
        item.currentPeriodStart = System.currentTimeMillis() / 1000
        item.currentPeriodEnd = (System.currentTimeMillis() / 1000) + 31536000L

        val itemsCollection = SubscriptionItemCollection()
        itemsCollection.setData(listOf(item))
        subscription.setItems(itemsCollection)

        // Set the base price ID on the tier so it resolves
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[stripe_yearly_base_price_id] = "price_yearly_base"
            }
        }

        stripeService.syncSubscriptionFromStripe(subscription)

        val sub = transaction {
            Subscriptions.selectAll().where {
                (Subscriptions.organization_id eq testOrgId) and
                    (Subscriptions.stripe_subscription_id eq "sub_yearly_test")
            }.firstOrNull()
        }
        assertTrue(sub != null, "Subscription should be synced")
        assertEquals("yearly", sub[Subscriptions.billing_interval])
    }

    @Test
    fun `syncSubscriptionFromStripe with unresolvable org does not create subscription`() {
        val subscription = mockSubscription(
            subscriptionId = "sub_no_org",
            customerId = "cus_nobody",
            status = "active",
            organizationId = 0,
            withOrgMetadata = false
        )
        stripeService.syncSubscriptionFromStripe(subscription)

        val count = transaction {
            Subscriptions.selectAll().where {
                Subscriptions.stripe_subscription_id eq "sub_no_org"
            }.count()
        }
        assertEquals(0L, count, "No subscription should be created for unresolvable org")
    }

    // ==================== BillingQuotaService: refundUnits ====================

    @Test
    fun `refundUnits decrements error usage counters correctly`() {
        transaction {
            insertTestUsageCounter(organizationId = testOrgId, usedErrors = 50, usedBytes = 5000)
        }

        billingQuotaService.refundUnits(
            organizationId = testOrgId,
            units = 10,
            eventType = "error",
            requestedBytes = 1000
        )

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)
        assertEquals(40L, usage.usedErrors, "Errors should be decremented by refund")
        assertEquals(4000L, usage.usedBytes, "Bytes should be decremented by refund")
    }

    @Test
    fun `refundUnits with zero units and zero bytes is a no-op`() {
        transaction {
            insertTestUsageCounter(organizationId = testOrgId, usedErrors = 50, usedBytes = 5000)
        }

        billingQuotaService.refundUnits(
            organizationId = testOrgId,
            units = 0,
            eventType = "error",
            requestedBytes = 0
        )

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)
        assertEquals(50L, usage.usedErrors)
        assertEquals(5000L, usage.usedBytes)
    }

    @Test
    fun `refundUnits clamps to zero and never goes negative`() {
        transaction {
            insertTestUsageCounter(organizationId = testOrgId, usedErrors = 5, usedBytes = 100)
        }

        billingQuotaService.refundUnits(
            organizationId = testOrgId,
            units = 100,
            eventType = "error",
            requestedBytes = 500
        )

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)
        assertEquals(0L, usage.usedErrors, "Errors should clamp to zero")
        assertEquals(0L, usage.usedBytes, "Bytes should clamp to zero")
    }

    @Test
    fun `refundUnits decrements custom metric counters and pending overage`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[monthly_custom_metric_limit] = 100
                it[custom_metric_overage_rate_cents_per_100k] = 50
            }
            val counterId = insertTestUsageCounter(organizationId = testOrgId)
            OrgUsageCounters.update({ OrgUsageCounters.id eq counterId }) {
                it[used_custom_metrics] = 200
            }
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[pending_custom_metric_overage_units] = 100
            }
        }

        billingQuotaService.refundUnits(
            organizationId = testOrgId,
            units = 50,
            eventType = "custom_metric"
        )

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)
        assertEquals(150L, usage.usedCustomMetrics)

        val sub = transaction {
            Subscriptions.selectAll().where { Subscriptions.id eq testSubId }.first()
        }
        assertEquals(
            50L,
            sub[Subscriptions.pending_custom_metric_overage_units],
            "Custom metric pending overage should be reduced"
        )
    }

    @Test
    fun `refundUnits decrements APM span counters and pending overage`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[monthly_apm_span_limit] = 1000
                it[apm_span_overage_rate_cents_per_1m] = 30
            }
            val counterId = insertTestUsageCounter(organizationId = testOrgId)
            OrgUsageCounters.update({ OrgUsageCounters.id eq counterId }) {
                it[used_apm_spans] = 1500
            }
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[pending_apm_span_overage_units] = 500
            }
        }

        billingQuotaService.refundUnits(
            organizationId = testOrgId,
            units = 200,
            eventType = "apm_span"
        )

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)
        assertEquals(1300L, usage.usedApmSpans)

        val sub = transaction {
            Subscriptions.selectAll().where { Subscriptions.id eq testSubId }.first()
        }
        assertEquals(
            300L,
            sub[Subscriptions.pending_apm_span_overage_units],
            "APM span pending overage should be reduced"
        )
    }

    @Test
    fun `refundUnits decrements replay counters`() {
        transaction {
            val counterId = insertTestUsageCounter(
                organizationId = testOrgId,
                usedReplays = 30,
                usedBytes = 3000
            )
            OrgUsageCounters.update({ OrgUsageCounters.id eq counterId }) {
                it[used_replay_bytes] = 2000
            }
        }

        billingQuotaService.refundUnits(
            organizationId = testOrgId,
            units = 5,
            eventType = "replay",
            requestedBytes = 500
        )

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)
        assertEquals(25L, usage.usedReplays)
        assertEquals(1500L, usage.usedReplayBytes)
    }

    @Test
    fun `refundUnits for byte-based overage reduces pending_overage_bytes`() {
        val gbLimit = 2L * BYTES_PER_GB
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[monthly_gb_limit] = gbLimit
                it[overage_rate_cents_per_gb] = 40
            }
            val usedBytes = gbLimit + 1000L
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 10,
                usedBytes = usedBytes
            )
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[pending_overage_bytes] = 1000L
            }
        }

        billingQuotaService.refundUnits(
            organizationId = testOrgId,
            units = 2,
            eventType = "error",
            requestedBytes = 500
        )

        val sub = transaction {
            Subscriptions.selectAll().where { Subscriptions.id eq testSubId }.first()
        }
        assertEquals(
            500L,
            sub[Subscriptions.pending_overage_bytes],
            "Pending overage bytes should be reduced by refund"
        )
    }

    // ==================== BillingQuotaService: getUsageForOrganization ====================

    @Test
    fun `getUsageForOrganization without any subscription uses free tier defaults`() {
        val noSubOrgId = transaction {
            insertTestOrganization("NoSub Org", "nosub-org")
        }

        val usage = billingQuotaService.getUsageForOrganization(noSubOrgId)
        assertEquals("free", usage.plan)
        assertEquals("active", usage.status)
        assertEquals(0L, usage.usedErrors)
        assertTrue(usage.withinQuota, "New org with no usage should be within quota")
    }

    @Test
    fun `getUsageForOrganization returns correct plan and status`() {
        val usage = billingQuotaService.getUsageForOrganization(testOrgId)
        assertEquals("pro", usage.plan)
        assertEquals("active", usage.status)
        assertEquals(testOrgId, usage.organizationId)
    }

    // ==================== BillingQuotaService: reserveUnits ingestion overage tracking ===========

    @Test
    fun `reserveUnits accumulates pending_overage_bytes when exceeding GB limit`() {
        val gbLimit = 1L * BYTES_PER_GB
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[monthly_gb_limit] = gbLimit
                it[overage_rate_cents_per_gb] = 40
                it[payg_enabled] = true
            }
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[payg_budget_cents] = 10000
                it[pending_overage_bytes] = 0
            }
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 0,
                usedBytes = gbLimit - 100
            )
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 1,
            eventType = "error",
            requestedBytes = 500
        )

        assertTrue(result.allowed, "Should be allowed within PAYG budget")

        val sub = transaction {
            Subscriptions.selectAll().where { Subscriptions.id eq testSubId }.first()
        }
        assertEquals(
            400L,
            sub[Subscriptions.pending_overage_bytes],
            "Only the 400 bytes over the limit should be pending"
        )
    }

    @Test
    fun `reserveUnits tracks custom metric overage in pending column`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[monthly_custom_metric_limit] = 100
                it[custom_metric_overage_rate_cents_per_100k] = 50
            }
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[pending_custom_metric_overage_units] = 0
            }
            val counterId = insertTestUsageCounter(organizationId = testOrgId)
            OrgUsageCounters.update({ OrgUsageCounters.id eq counterId }) {
                it[used_custom_metrics] = 90
            }
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 20,
            eventType = "custom_metric",
            requestedBytes = 100
        )
        assertTrue(result.allowed, "Should allow with overage billing enabled")

        val sub = transaction {
            Subscriptions.selectAll().where { Subscriptions.id eq testSubId }.first()
        }
        assertEquals(
            10L,
            sub[Subscriptions.pending_custom_metric_overage_units],
            "10 units over the 100 limit should be pending"
        )
    }

    @Test
    fun `reserveUnits tracks APM span overage in pending column`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[monthly_apm_span_limit] = 500
                it[apm_span_overage_rate_cents_per_1m] = 30
            }
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[pending_apm_span_overage_units] = 0
            }
            val counterId = insertTestUsageCounter(organizationId = testOrgId)
            OrgUsageCounters.update({ OrgUsageCounters.id eq counterId }) {
                it[used_apm_spans] = 480
            }
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 50,
            eventType = "apm_span",
            requestedBytes = 100
        )
        assertTrue(result.allowed, "Should allow with overage billing enabled")

        val sub = transaction {
            Subscriptions.selectAll().where { Subscriptions.id eq testSubId }.first()
        }
        assertEquals(
            30L,
            sub[Subscriptions.pending_apm_span_overage_units],
            "30 units over the 500 limit should be pending"
        )
    }

    @Test
    fun `reserveUnits rejects custom_metric at hard limit without overage billing`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[monthly_custom_metric_limit] = 100
                it[custom_metric_overage_rate_cents_per_100k] = 0
                it[monthly_gb_limit] = 100L * BYTES_PER_GB
            }
            val counterId = insertTestUsageCounter(organizationId = testOrgId)
            OrgUsageCounters.update({ OrgUsageCounters.id eq counterId }) {
                it[used_custom_metrics] = 100
            }
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 1,
            eventType = "custom_metric"
        )
        assertFalse(result.allowed, "Should be rejected at hard limit")
        assertEquals("event_type_quota_exceeded", result.reason)
        assertEquals("custom_metric", result.eventType)
    }

    @Test
    fun `reserveUnits rejects apm_span at hard limit without overage billing`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[monthly_apm_span_limit] = 200
                it[apm_span_overage_rate_cents_per_1m] = 0
                it[monthly_gb_limit] = 100L * BYTES_PER_GB
            }
            val counterId = insertTestUsageCounter(organizationId = testOrgId)
            OrgUsageCounters.update({ OrgUsageCounters.id eq counterId }) {
                it[used_apm_spans] = 200
            }
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 1,
            eventType = "apm_span"
        )
        assertFalse(result.allowed, "Should be rejected at hard limit")
        assertEquals("event_type_quota_exceeded", result.reason)
        assertEquals("apm_span", result.eventType)
    }

    // ==================== BillingQuotaService: normalizeEventType edge cases =====================

    @Test
    fun `reserveUnits normalizes logs to log event type`() {
        transaction {
            insertTestUsageCounter(organizationId = testOrgId)
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 5,
            eventType = "logs"
        )
        assertTrue(result.allowed)
        assertEquals(5L, result.usage.usedLogs, "logs should be normalized to log counter")
    }

    @Test
    fun `reserveUnits normalizes apm to apm_span event type`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[monthly_apm_span_limit] = 10000
            }
            insertTestUsageCounter(organizationId = testOrgId)
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 3,
            eventType = "apm"
        )
        assertTrue(result.allowed)
        assertEquals(3L, result.usage.usedApmSpans, "apm should be normalized to apm_span")
    }

    @Test
    fun `reserveUnits normalizes metric to custom_metric event type`() {
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[monthly_custom_metric_limit] = 10000
            }
            insertTestUsageCounter(organizationId = testOrgId)
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 7,
            eventType = "metric"
        )
        assertTrue(result.allowed)
        assertEquals(7L, result.usage.usedCustomMetrics, "metric normalized to custom_metric")
    }

    @Test
    fun `reserveUnits normalizes dd_profile to profile event type`() {
        transaction {
            insertTestUsageCounter(organizationId = testOrgId)
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 2,
            eventType = "dd_profile",
            requestedBytes = 500
        )
        assertTrue(result.allowed)
        assertEquals(500L, result.usage.usedProfilerBytes, "dd_profile bytes go to profiler")
    }

    @Test
    fun `reserveUnits normalizes unknown event type to error`() {
        transaction {
            insertTestUsageCounter(organizationId = testOrgId)
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 1,
            eventType = "unknown_type"
        )
        assertTrue(result.allowed)
        assertEquals(1L, result.usage.usedErrors, "Unknown type should normalize to error")
    }

    // ==================== BillingQuotaService: GB quota boundary ====================

    @Test
    fun `reserveUnits rejects when GB eligible bytes exceed limit without PAYG`() {
        val gbLimit = 1L * BYTES_PER_GB
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[monthly_gb_limit] = gbLimit
                it[overage_rate_cents_per_gb] = 0
                it[payg_enabled] = false
            }
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[payg_budget_cents] = 0
            }
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedErrors = 0,
                usedBytes = gbLimit
            )
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 1,
            eventType = "error",
            requestedBytes = 1
        )
        assertFalse(result.allowed, "Should reject when at GB limit without PAYG")
        assertEquals("gb_quota_exceeded", result.reason)
    }

    @Test
    fun `reserveUnits excludes apm_span bytes from GB eligible calculation`() {
        val gbLimit = 1L * BYTES_PER_GB
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[monthly_gb_limit] = gbLimit
                it[monthly_apm_span_limit] = Long.MAX_VALUE
                it[overage_rate_cents_per_gb] = 0
                it[payg_enabled] = false
            }
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[payg_budget_cents] = 0
            }
            val counterId = insertTestUsageCounter(
                organizationId = testOrgId,
                usedBytes = gbLimit - 100
            )
            OrgUsageCounters.update({ OrgUsageCounters.id eq counterId }) {
                it[used_apm_span_bytes] = gbLimit - 200
            }
        }

        // GB eligible = usedBytes - apmSpanBytes = (gbLimit-100) - (gbLimit-200) = 100
        // After adding 50 bytes of error: 150, still under gbLimit
        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 1,
            eventType = "error",
            requestedBytes = 50
        )
        assertTrue(result.allowed, "APM span bytes should be excluded from GB gate")
    }

    @Test
    fun `reserveUnits allows when bonus GB extends effective limit`() {
        val gbLimit = 1L * BYTES_PER_GB
        val bonusGb = 1L * BYTES_PER_GB
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[monthly_gb_limit] = gbLimit
                it[overage_rate_cents_per_gb] = 0
                it[payg_enabled] = false
            }
            Subscriptions.update({ Subscriptions.id eq testSubId }) {
                it[payg_budget_cents] = 0
                it[bonus_gb_bytes] = bonusGb
            }
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedBytes = gbLimit + 100
            )
        }

        val result = billingQuotaService.reserveUnits(
            organizationId = testOrgId,
            requestedUnits = 1,
            eventType = "error",
            requestedBytes = 100
        )
        assertTrue(result.allowed, "Bonus GB should extend effective limit")
    }

    // ==================== BillingQuotaService: overage cost estimates ====================

    @Test
    fun `getUsageForOrganization computes ingestion overage cost estimate`() {
        val gbLimit = 1L * BYTES_PER_GB
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[monthly_gb_limit] = gbLimit
                it[overage_rate_cents_per_gb] = 40
            }
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedBytes = gbLimit + BYTES_PER_GB
            )
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)
        assertEquals(40, usage.ingestionOverageCentsEstimate, "1 GB overage at 40c/GB = 40c")
    }

    @Test
    fun `getUsageForOrganization computes zero overage when within limit`() {
        val gbLimit = 10L * BYTES_PER_GB
        transaction {
            PricingTierConfigs.update({ PricingTierConfigs.id eq proTierId }) {
                it[monthly_gb_limit] = gbLimit
                it[overage_rate_cents_per_gb] = 40
            }
            insertTestUsageCounter(
                organizationId = testOrgId,
                usedBytes = BYTES_PER_GB
            )
        }

        val usage = billingQuotaService.getUsageForOrganization(testOrgId)
        assertEquals(0, usage.ingestionOverageCentsEstimate, "No overage within limit")
    }

    // ==================== Helper methods ====================

    private fun insertTestOrganization(name: String, slug: String): Int {
        return Organizations.insert {
            it[Organizations.name] = name
            it[Organizations.slug] = slug
        }[Organizations.id]
    }

    private data class SubscriptionParams(
        val organizationId: Int,
        val plan: String,
        val status: String,
        val paygBudgetCents: Int,
        val pricingTierConfigId: Int? = null,
        val stripeCustomerId: String? = null,
        val stripeSubscriptionId: String? = null,
        val billingGraceUntil: Instant? = null
    )

    private fun insertTestSubscription(p: SubscriptionParams): Int {
        val now = Clock.System.now()
        return Subscriptions.insert {
            it[Subscriptions.organization_id] = p.organizationId
            it[Subscriptions.plan] = p.plan
            it[Subscriptions.status] = p.status
            it[Subscriptions.billing_interval] = "monthly"
            it[Subscriptions.current_period_start] = now
            it[Subscriptions.current_period_end] = now + 30.days
            it[Subscriptions.pricing_tier_config_id] = p.pricingTierConfigId
            it[Subscriptions.payg_budget_cents] = p.paygBudgetCents
            it[Subscriptions.payg_used_units] = 0
            it[Subscriptions.payg_used_micros] = 0
            it[Subscriptions.pending_meter_units] = 0
            it[Subscriptions.pending_meter_batch_id] = null
            it[Subscriptions.pending_meter_batch_units] = 0
            it[Subscriptions.stripe_customer_id] = p.stripeCustomerId
            it[Subscriptions.stripe_subscription_id] = p.stripeSubscriptionId
            it[Subscriptions.billing_grace_until] = p.billingGraceUntil
        }[Subscriptions.id]
    }

    private fun insertTestPricingTier(
        tierName: String,
        monthlyUnitLimit: Long,
        monthlyErrorLimit: Long,
        paygEnabled: Boolean,
        paygRateMicrosPerUnit: Long,
        overageRateCentsPerGb: Int,
        monthlyGbLimit: Long = 10L * BYTES_PER_GB
    ): Int {
        return PricingTierConfigs.insert {
            it[PricingTierConfigs.tier_name] = tierName
            it[PricingTierConfigs.version] = 1
            it[PricingTierConfigs.monthly_unit_limit] = monthlyUnitLimit
            it[PricingTierConfigs.monthly_error_limit] = monthlyErrorLimit
            it[PricingTierConfigs.monthly_transaction_limit] = 300
            it[PricingTierConfigs.monthly_replay_limit] = 100
            it[PricingTierConfigs.monthly_feedback_limit] = 100
            it[PricingTierConfigs.monthly_gb_limit] = monthlyGbLimit
            it[PricingTierConfigs.retention_days] = 30
            it[PricingTierConfigs.log_retention_days] = 30
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
            it[PricingTierConfigs.overage_rate_cents_per_gb] = overageRateCentsPerGb
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
            it[OrgUsageCounters.used_units] =
                usedErrors + usedTransactions + usedReplays + usedFeedback
            it[OrgUsageCounters.used_errors] = usedErrors
            it[OrgUsageCounters.used_transactions] = usedTransactions
            it[OrgUsageCounters.used_replays] = usedReplays
            it[OrgUsageCounters.used_feedback] = usedFeedback
            it[OrgUsageCounters.used_bytes] = usedBytes
            it[OrgUsageCounters.updated_at] = now
        }[OrgUsageCounters.id]
    }

    private fun mockSubscription(
        subscriptionId: String,
        customerId: String,
        status: String,
        organizationId: Int,
        withOrgMetadata: Boolean = true
    ): Subscription {
        val subscription = Subscription()
        subscription.id = subscriptionId
        subscription.customer = customerId
        subscription.status = status
        subscription.startDate = System.currentTimeMillis() / 1000

        val itemsCollection = SubscriptionItemCollection()
        itemsCollection.setData(emptyList())
        subscription.setItems(itemsCollection)

        val metadata = mutableMapOf<String, String>()
        if (withOrgMetadata) {
            metadata["organization_id"] = organizationId.toString()
        }
        subscription.metadata = metadata
        return subscription
    }

    private fun mockInvoice(
        customerId: String,
        organizationId: Int,
        withOrgMetadata: Boolean = true,
        billingReason: String = "subscription_cycle"
    ): Invoice {
        val invoice = Invoice()
        invoice.id = "in_test_${System.nanoTime()}"
        invoice.customer = customerId
        invoice.status = "paid"
        invoice.periodStart = System.currentTimeMillis() / 1000
        invoice.periodEnd = (System.currentTimeMillis() / 1000) + 2592000
        invoice.billingReason = billingReason

        if (withOrgMetadata) {
            invoice.metadata = mapOf("organization_id" to organizationId.toString())
        } else {
            invoice.metadata = emptyMap()
        }
        return invoice
    }
}
