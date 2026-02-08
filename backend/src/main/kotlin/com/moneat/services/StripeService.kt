package com.moneat.services

import com.moneat.models.*
import com.stripe.Stripe
import com.stripe.exception.SignatureVerificationException
import com.stripe.model.Customer
import com.stripe.model.Event
import com.stripe.model.Invoice
import com.stripe.model.Subscription
import com.stripe.model.billingportal.Session
import com.stripe.model.billing.MeterEvent
import com.stripe.net.Webhook
import com.stripe.param.CustomerCreateParams
import com.stripe.param.billing.MeterEventCreateParams
import com.stripe.param.billingportal.SessionCreateParams as PortalSessionCreateParams
import com.stripe.param.checkout.SessionCreateParams as CheckoutSessionCreateParams
import io.ktor.server.config.ApplicationConfig
import kotlinx.datetime.Instant
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = KotlinLogging.logger {}

class StripeService(
    private val pricingTierService: PricingTierService = PricingTierService()
) {
    private val config = ApplicationConfig("application.conf")
    private val stripeEnabled = config.propertyOrNull("billing.stripeEnabled")?.getString()?.toBooleanStrictOrNull() ?: false
    private val secretKey = config.propertyOrNull("stripe.secretKey")?.getString()
    private val webhookSecret = config.propertyOrNull("stripe.webhookSecret")?.getString()

    init {
        if (!secretKey.isNullOrBlank()) {
            Stripe.apiKey = secretKey
        }
    }

    fun isStripeEnabled(): Boolean = stripeEnabled && !secretKey.isNullOrBlank()

    fun getPublishableKey(): String? = config.propertyOrNull("stripe.publishableKey")?.getString()

    fun createCheckoutSession(
        organizationId: Int,
        tierName: String,
        successUrl: String,
        cancelUrl: String
    ): CheckoutSessionResponse {
        ensureEnabled()

        val tier = pricingTierService.getCurrentTier(tierName)
            ?: throw IllegalArgumentException("Unknown tier: $tierName")
        if (tier.tierName.equals("FREE", ignoreCase = true)) {
            throw IllegalArgumentException("Checkout is only supported for paid tiers")
        }
        val basePriceId = tier.stripeBasePriceId ?: throw IllegalArgumentException("Tier missing Stripe base price ID")
        val overagePriceId = tier.stripeOveragePriceId
        if (tier.paygEnabled && overagePriceId.isNullOrBlank()) {
            throw IllegalArgumentException("Tier missing Stripe overage price ID while PAYG is enabled")
        }

        val customerId = getOrCreateCustomer(organizationId)

        val paramsBuilder = CheckoutSessionCreateParams.builder()
            .setMode(CheckoutSessionCreateParams.Mode.SUBSCRIPTION)
            .setCustomer(customerId)
            .setSuccessUrl(successUrl)
            .setCancelUrl(cancelUrl)
            .setAllowPromotionCodes(true)
            .setSubscriptionData(
                CheckoutSessionCreateParams.SubscriptionData.builder()
                    .setTrialPeriodDays(14L)
                    .putMetadata("organization_id", organizationId.toString())
                    .putMetadata("tier_name", tier.tierName)
                    .build()
            )
            .putMetadata("organization_id", organizationId.toString())
            .putMetadata("tier_name", tier.tierName)
            .addLineItem(
                CheckoutSessionCreateParams.LineItem.builder()
                    .setPrice(basePriceId)
                    .setQuantity(1L)
                    .build()
            )
        if (tier.paygEnabled && !overagePriceId.isNullOrBlank()) {
            paramsBuilder.addLineItem(
                CheckoutSessionCreateParams.LineItem.builder()
                    .setPrice(overagePriceId)
                    .build()
            )
        }
        val params = paramsBuilder.build()

        val session = com.stripe.model.checkout.Session.create(params)
        return CheckoutSessionResponse(
            sessionId = session.id,
            url = session.url ?: ""
        )
    }

    fun createPortalSession(organizationId: Int, returnUrl: String): PortalSessionResponse {
        ensureEnabled()
        val customerId = transaction {
            Subscriptions.select {
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.status inList listOf("active", "trialing", "past_due"))
            }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
                ?.get(Subscriptions.stripe_customer_id)
        } ?: throw IllegalStateException("No Stripe customer linked for this organization")

        val params = PortalSessionCreateParams.builder()
            .setCustomer(customerId)
            .setReturnUrl(returnUrl)
            .build()
        val portalSession = Session.create(params)
        return PortalSessionResponse(url = portalSession.url)
    }

    fun verifyAndParseEvent(payload: String, signature: String?): Event {
        ensureEnabled()
        val secret = webhookSecret ?: throw IllegalStateException("Missing Stripe webhook secret")
        if (signature.isNullOrBlank()) throw SignatureVerificationException("Missing Stripe signature", "")
        return Webhook.constructEvent(payload, signature, secret)
    }

    fun wasEventProcessed(eventId: String): Boolean {
        return transaction {
            StripeWebhookEvents.select { StripeWebhookEvents.event_id eq eventId }.count() > 0
        }
    }

    fun markEventProcessed(event: Event, status: String, errorMessage: String? = null) {
        transaction {
            StripeWebhookEvents.insertIgnore {
                it[event_id] = event.id
                it[event_type] = event.type
                it[processed_at] = kotlinx.datetime.Clock.System.now()
                it[StripeWebhookEvents.status] = status
                it[this.error_message] = errorMessage
                it[created_at] = kotlinx.datetime.Clock.System.now()
            }
        }
    }

    fun syncSubscriptionFromStripe(subscription: Subscription) {
        val organizationId = resolveOrganizationId(subscription.metadata["organization_id"], subscription.customer)
            ?: return

        val currentTier = transaction {
            val subRow = Subscriptions.select {
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.status inList listOf("active", "trialing", "past_due"))
            }.orderBy(Subscriptions.id to SortOrder.DESC).firstOrNull()
            val tierName = subRow?.get(Subscriptions.plan)?.uppercase() ?: "FREE"
            pricingTierService.getCurrentTier(tierName) ?: pricingTierService.getCurrentTier("FREE")
        }

        val overagePriceId = currentTier?.stripeOveragePriceId
        val basePriceId = currentTier?.stripeBasePriceId

        var baseItemId: String? = null
        var overageItemId: String? = null
        for (item in subscription.items.data) {
            val priceId = item.price?.id
            if (priceId != null && priceId == overagePriceId) overageItemId = item.id
            if (priceId != null && priceId == basePriceId) baseItemId = item.id
        }

        transaction {
            val existing = Subscriptions.select {
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.stripe_subscription_id eq subscription.id)
            }.orderBy(Subscriptions.id to SortOrder.DESC).firstOrNull()

            val planName = currentTier?.tierName?.lowercase() ?: "free"
            val startInstant = subscription.startDate?.let { kotlinx.datetime.Instant.fromEpochSeconds(it) }
            val endInstant = subscription.trialEnd?.let { kotlinx.datetime.Instant.fromEpochSeconds(it) }

            if (existing != null) {
                Subscriptions.update({ Subscriptions.id eq existing[Subscriptions.id] }) {
                    it[plan] = planName
                    it[status] = subscription.status
                    it[current_period_start] = startInstant
                    it[current_period_end] = endInstant
                    it[stripe_customer_id] = subscription.customer
                    it[stripe_base_item_id] = baseItemId
                    it[stripe_overage_item_id] = overageItemId
                }
            } else {
                val tierId = currentTier?.id?.takeIf { it > 0 }
                Subscriptions.insert {
                    it[Subscriptions.organization_id] = organizationId
                    it[stripe_subscription_id] = subscription.id
                    it[stripe_customer_id] = subscription.customer
                    it[plan] = planName
                    it[status] = subscription.status
                    it[current_period_start] = startInstant
                    it[current_period_end] = endInstant
                    it[pricing_tier_config_id] = tierId
                    it[payg_budget_cents] = 0
                    it[payg_used_units] = 0
                    it[payg_used_micros] = 0
                    it[pending_meter_units] = 0
                    it[stripe_base_item_id] = baseItemId
                    it[stripe_overage_item_id] = overageItemId
                }
            }
        }
    }

    fun handleCheckoutCompleted(session: com.stripe.model.checkout.Session) {
        val customerId = session.customer ?: return
        val subscriptionId = session.subscription ?: return
        val stripeSubscription = Subscription.retrieve(subscriptionId)
        syncSubscriptionFromStripe(stripeSubscription)

        val organizationId = resolveOrganizationId(session.metadata["organization_id"], customerId) ?: return
        transaction {
            Subscriptions.update({
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.stripe_subscription_id eq subscriptionId)
            }) {
                it[status] = "active"
                it[stripe_customer_id] = customerId
            }
        }
    }

    fun handleInvoicePaid(invoice: Invoice) {
        val organizationId = resolveOrganizationId(invoice.metadata["organization_id"], invoice.customer) ?: return
        transaction {
            val q = Subscriptions.select {
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.status inList listOf("active", "trialing", "past_due"))
            }.orderBy(Subscriptions.id to SortOrder.DESC)
            val row = q.firstOrNull() ?: return@transaction
            val start = invoice.periodStart?.let { kotlinx.datetime.Instant.fromEpochSeconds(it) }
            val end = invoice.periodEnd?.let { kotlinx.datetime.Instant.fromEpochSeconds(it) }

            Subscriptions.update({ Subscriptions.id eq row[Subscriptions.id] }) {
                it[status] = "active"
                it[current_period_start] = start
                it[current_period_end] = end
                it[payg_used_units] = 0
                it[payg_used_micros] = 0
                it[pending_meter_units] = 0
                it[billing_grace_until] = null
            }
        }
    }

    fun handleInvoicePaymentFailed(invoice: Invoice, graceDays: Int = 7) {
        val organizationId = resolveOrganizationId(invoice.metadata["organization_id"], invoice.customer) ?: return
        val graceUntil = addDays(kotlinx.datetime.Clock.System.now(), graceDays)
        transaction {
            Subscriptions.update({
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.status inList listOf("active", "trialing", "past_due"))
            }) {
                it[status] = "past_due"
                it[billing_grace_until] = graceUntil
            }
        }
    }

    fun handleSubscriptionDeleted(subscription: Subscription) {
        val organizationId = resolveOrganizationId(subscription.metadata["organization_id"], subscription.customer) ?: return
        val freeTier = pricingTierService.getCurrentTier("FREE")
        transaction {
            Subscriptions.update({
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.stripe_subscription_id eq subscription.id)
            }) {
                it[status] = "canceled"
            }

            Subscriptions.insert {
                it[Subscriptions.organization_id] = organizationId
                it[plan] = "free"
                it[status] = "active"
                it[current_period_start] = kotlinx.datetime.Clock.System.now()
                it[current_period_end] = addDays(kotlinx.datetime.Clock.System.now(), 30)
                it[pricing_tier_config_id] = freeTier?.id?.takeIf { id -> id > 0 }
                it[payg_budget_cents] = 0
                it[payg_used_units] = 0
                it[payg_used_micros] = 0
                it[pending_meter_units] = 0
            }
        }
    }

    fun flushPendingMeteredUsage(limit: Int = 200): Int {
        if (!isStripeEnabled()) return 0
        val meterEventName = config.propertyOrNull("stripe.meterEventName")?.getString() ?: "moneat_overage_units"
        val rows = transaction {
            Subscriptions.select {
                (Subscriptions.pending_meter_units greater 0L) and
                    (Subscriptions.stripe_customer_id.isNotNull()) and
                    (Subscriptions.status inList listOf("active", "trialing"))
            }
                .orderBy(Subscriptions.id to SortOrder.ASC)
                .limit(limit)
                .toList()
        }

        var flushed = 0
        for (row in rows) {
            val customerId = row[Subscriptions.stripe_customer_id] ?: continue
            val units = row[Subscriptions.pending_meter_units]
            if (units <= 0) continue
            try {
                val params = MeterEventCreateParams.builder()
                    .setEventName(meterEventName)
                    .setIdentifier("sub-${row[Subscriptions.id]}-${System.currentTimeMillis()}")
                    .putPayload("stripe_customer_id", customerId)
                    .putPayload("value", units.toString())
                    .build()
                MeterEvent.create(params)

                transaction {
                    Subscriptions.update({ Subscriptions.id eq row[Subscriptions.id] }) {
                        it[pending_meter_units] = 0
                    }
                }
                flushed++
            } catch (e: Exception) {
                logger.error(e) {
                    "Failed to report metered usage for subscription ${row[Subscriptions.id]} (units=$units)"
                }
            }
        }
        return flushed
    }

    fun applyDunningDowngrade(graceDays: Int = 7): Int {
        val freeTier = pricingTierService.getCurrentTier("FREE")
        val now = kotlinx.datetime.Clock.System.now()
        val downgraded = transaction {
            val pastDueRows = Subscriptions.select {
                (Subscriptions.status eq "past_due") and
                    (Subscriptions.billing_grace_until.isNotNull()) and
                    (Subscriptions.billing_grace_until lessEq now)
            }.toList()

            for (row in pastDueRows) {
                Subscriptions.update({ Subscriptions.id eq row[Subscriptions.id] }) {
                    it[status] = "canceled"
                }
                Subscriptions.insert {
                    it[organization_id] = row[Subscriptions.organization_id]
                    it[plan] = "free"
                    it[status] = "active"
                    it[current_period_start] = now
                    it[current_period_end] = addDays(now, 30)
                    it[pricing_tier_config_id] = freeTier?.id?.takeIf { id -> id > 0 }
                    it[payg_budget_cents] = 0
                    it[payg_used_units] = 0
                    it[payg_used_micros] = 0
                    it[pending_meter_units] = 0
                }
            }
            pastDueRows.size
        }
        if (downgraded > 0) {
            logger.info { "Applied dunning downgrade for $downgraded past_due subscription(s)" }
        }
        return downgraded
    }

    private fun getOrCreateCustomer(organizationId: Int): String {
        val existing = transaction {
            Subscriptions.select {
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.stripe_customer_id.isNotNull())
            }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
                ?.get(Subscriptions.stripe_customer_id)
        }
        if (!existing.isNullOrBlank()) return existing

        val (orgName, ownerEmail) = transaction {
            val orgName = Organizations.select { Organizations.id eq organizationId }
                .firstOrNull()
                ?.get(Organizations.name)
                ?: "Moneat Organization $organizationId"
            val ownerUserId = Memberships.select {
                (Memberships.organization_id eq organizationId) and (Memberships.role eq "owner")
            }
                .orderBy(Memberships.id to SortOrder.ASC)
                .firstOrNull()
                ?.get(Memberships.user_id)
            val fallbackUserId = Memberships.select { Memberships.organization_id eq organizationId }
                .orderBy(Memberships.id to SortOrder.ASC)
                .firstOrNull()
                ?.get(Memberships.user_id)
            val userId = ownerUserId ?: fallbackUserId
            val email = userId?.let { id ->
                Users.select { Users.id eq id }.firstOrNull()?.get(Users.email)
            }
            Pair(orgName, email)
        }

        val paramsBuilder = CustomerCreateParams.builder()
            .setName(orgName)
            .putMetadata("organization_id", organizationId.toString())
        if (!ownerEmail.isNullOrBlank()) {
            paramsBuilder.setEmail(ownerEmail)
        }
        val customer = Customer.create(paramsBuilder.build())

        transaction {
            val sub = Subscriptions.select {
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.status inList listOf("active", "trialing", "past_due"))
            }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()

            if (sub != null) {
                Subscriptions.update({ Subscriptions.id eq sub[Subscriptions.id] }) {
                    it[stripe_customer_id] = customer.id
                }
            }
        }

        return customer.id
    }

    private fun resolveOrganizationId(metadataOrgId: String?, customerId: String?): Int? {
        val byMetadata = metadataOrgId?.toIntOrNull()
        if (byMetadata != null) return byMetadata
        if (customerId.isNullOrBlank()) return null
        return transaction {
            Subscriptions.select { Subscriptions.stripe_customer_id eq customerId }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
                ?.get(Subscriptions.organization_id)
        }
    }

    private fun ensureEnabled() {
        if (!isStripeEnabled()) {
            throw IllegalStateException("Stripe integration is disabled")
        }
    }

    private fun addDays(instant: Instant, days: Int): Instant {
        return Instant.fromEpochSeconds(instant.epochSeconds + (days * 86_400L))
    }
}
