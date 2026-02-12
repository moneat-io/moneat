package com.moneat.services

import com.moneat.models.*
import com.moneat.utils.SentryUtils
import com.stripe.Stripe
import com.stripe.exception.SignatureVerificationException
import com.stripe.model.*
import com.stripe.model.billing.MeterEvent
import com.stripe.net.Webhook
import com.stripe.param.*
import com.stripe.param.billing.MeterEventCreateParams
import io.ktor.server.config.*
import io.sentry.Sentry
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import com.stripe.param.checkout.SessionCreateParams as CheckoutSessionCreateParams

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
        billingInterval: String = "monthly",
        successUrl: String,
        cancelUrl: String
    ): CheckoutSessionResponse {
        ensureEnabled()
        
        SentryUtils.breadcrumb("stripe", "Creating checkout session", mapOf(
            "organization_id" to organizationId,
            "tier_name" to tierName,
            "billing_interval" to billingInterval
        ))

        val tier = pricingTierService.getCurrentTier(tierName)
            ?: throw IllegalArgumentException("Unknown tier: $tierName")
        if (tier.tierName.equals("FREE", ignoreCase = true)) {
            throw IllegalArgumentException("Checkout is only supported for paid tiers")
        }
        
        val isYearly = billingInterval.equals("yearly", ignoreCase = true)
        val basePriceId = if (isYearly) {
            tier.stripeYearlyBasePriceId ?: tier.stripeBasePriceId
        } else {
            tier.stripeBasePriceId
        } ?: throw IllegalArgumentException("Tier missing Stripe base price ID for $billingInterval")
        
        val overagePriceId = if (isYearly) {
            tier.stripeYearlyOveragePriceId ?: tier.stripeOveragePriceId
        } else {
            tier.stripeOveragePriceId
        }
        
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
                    .putMetadata("billing_interval", billingInterval)
                    .build()
            )
            .putMetadata("organization_id", organizationId.toString())
            .putMetadata("tier_name", tier.tierName)
            .putMetadata("billing_interval", billingInterval)
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

        return try {
            val session = com.stripe.model.checkout.Session.create(params)
            SentryUtils.breadcrumb("stripe", "Checkout session created", mapOf(
                "session_id" to session.id,
                "customer_id" to customerId
            ))
            CheckoutSessionResponse(
                sessionId = session.id,
                url = session.url ?: ""
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to create Stripe checkout session" }
            Sentry.captureException(e) { scope ->
                scope.setTag("stripe.operation", "create_checkout_session")
                scope.setExtra("organization_id", organizationId.toString())
                scope.setExtra("tier_name", tierName)
                scope.setExtra("billing_interval", billingInterval)
            }
            throw e
        }
    }

    fun listInvoices(organizationId: Int, limit: Long = 20): List<InvoiceResponse> {
        ensureEnabled()
        val customerId = findCustomerId(organizationId) ?: return emptyList()
        val invoices = Invoice.list(
            InvoiceListParams.builder()
                .setCustomer(customerId)
                .setLimit(limit.coerceIn(1, 100))
                .build()
        )
        return invoices.data.map { invoice ->
            InvoiceResponse(
                id = invoice.id,
                date = epochSecondsToIso(invoice.created) ?: "",
                amountCents = (invoice.total ?: invoice.amountPaid ?: invoice.amountDue ?: 0L).toInt(),
                status = invoice.status ?: "unknown",
                pdfUrl = invoice.invoicePdf
            )
        }
    }

    fun getPaymentMethod(organizationId: Int): PaymentMethodResponse {
        ensureEnabled()
        val customerId = findCustomerId(organizationId) ?: return PaymentMethodResponse(
            brand = null,
            last4 = null,
            expMonth = null,
            expYear = null
        )

        val customer = Customer.retrieve(
            customerId,
            CustomerRetrieveParams.builder()
                .addExpand("invoice_settings.default_payment_method")
                .build(),
            null
        )
        val paymentMethod = customer.invoiceSettings?.defaultPaymentMethodObject
            ?: customer.invoiceSettings?.defaultPaymentMethod?.takeIf { it.isNotBlank() }?.let { PaymentMethod.retrieve(it) }
        val card = paymentMethod?.card
        return PaymentMethodResponse(
            brand = card?.brand,
            last4 = card?.last4,
            expMonth = card?.expMonth?.toInt(),
            expYear = card?.expYear?.toInt()
        )
    }

    fun createSetupIntent(organizationId: Int): SetupIntentResponse {
        ensureEnabled()
        val customerId = getOrCreateCustomer(organizationId)
        val intent = SetupIntent.create(
            SetupIntentCreateParams.builder()
                .setCustomer(customerId)
                .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION)
                .putMetadata("organization_id", organizationId.toString())
                .build()
        )
        val clientSecret = intent.clientSecret ?: throw IllegalStateException("Stripe setup intent missing client secret")
        return SetupIntentResponse(clientSecret = clientSecret)
    }

    fun confirmSetupIntentAndUpdatePaymentMethod(organizationId: Int, setupIntentId: String) {
        ensureEnabled()
        
        logger.info { "Confirming setup intent and updating payment method: orgId=$organizationId, setupIntentId=$setupIntentId" }
        
        // Retrieve the setup intent to get the payment method and customer
        val setupIntent = SetupIntent.retrieve(setupIntentId)
        val customerId = setupIntent.customer ?: throw IllegalStateException("Setup intent has no customer")
        val paymentMethodId = setupIntent.paymentMethod ?: throw IllegalStateException("Setup intent has no payment method")
        
        // Verify this customer belongs to the organization
        val expectedCustomerId = getOrCreateCustomer(organizationId)
        if (customerId != expectedCustomerId) {
            throw IllegalStateException("Setup intent customer mismatch")
        }
        
        // Update customer's default payment method
        Customer.retrieve(customerId).update(
            CustomerUpdateParams.builder()
                .setInvoiceSettings(
                    CustomerUpdateParams.InvoiceSettings.builder()
                        .setDefaultPaymentMethod(paymentMethodId)
                        .build()
                )
                .build()
        )
        
        logger.info { "Successfully updated default payment method for customer $customerId to $paymentMethodId" }
    }

    fun cancelSubscription(organizationId: Int): CancelSubscriptionResponse {
        ensureEnabled()
        val localSubscription = transaction {
            Subscriptions.selectAll().where {
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.status inList listOf("active", "trialing", "past_due"))
            }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
        } ?: throw IllegalStateException("No active subscription found")

        val stripeSubscriptionId = localSubscription[Subscriptions.stripe_subscription_id]
            ?: throw IllegalStateException("No Stripe subscription linked for this organization")

        val canceled = Subscription.retrieve(stripeSubscriptionId).update(
            SubscriptionUpdateParams.builder()
                .setCancelAtPeriodEnd(true)
                .build()
        )

        val currentPeriodEnd = canceled.cancelAt?.let { Instant.fromEpochSeconds(it) }
        transaction {
            Subscriptions.update({ Subscriptions.id eq localSubscription[Subscriptions.id] }) {
                it[status] = canceled.status ?: localSubscription[Subscriptions.status]
                it[current_period_end] = currentPeriodEnd
            }
        }

        return CancelSubscriptionResponse(
            status = canceled.status ?: localSubscription[Subscriptions.status],
            cancelAtPeriodEnd = canceled.cancelAtPeriodEnd == true,
            currentPeriodEnd = epochSecondsToIso(canceled.cancelAt)
        )
    }

    fun verifyAndParseEvent(payload: String, signature: String?): Event {
        ensureEnabled()
        val secret = webhookSecret ?: throw IllegalStateException("Missing Stripe webhook secret")
        if (signature.isNullOrBlank()) throw SignatureVerificationException("Missing Stripe signature", "")
        logger.debug { "Verifying Stripe webhook signature" }
        try {
            return Webhook.constructEvent(payload, signature, secret)
        } catch (e: SignatureVerificationException) {
            logger.error { "Webhook signature verification failed: ${e.message}" }
            throw e
        }
    }

    fun wasEventProcessed(eventId: String): Boolean {
        return transaction {
            StripeWebhookEvents.selectAll().where { StripeWebhookEvents.event_id eq eventId }.count() > 0
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
        val metadataOrgId = subscription.metadata?.get("organization_id")
        logger.info { "syncSubscriptionFromStripe: subscription=${subscription.id}, customer=${subscription.customer}, metadata_org_id='$metadataOrgId'" }
        
        val organizationId = resolveOrganizationId(metadataOrgId, subscription.customer)
        if (organizationId == null) {
            logger.error { "CRITICAL: Could not resolve organization ID for subscription ${subscription.id}. metadata_org_id='$metadataOrgId', customer=${subscription.customer}, full_metadata=${subscription.metadata}" }
            return
        }
        logger.info { "Resolved organization ID $organizationId for subscription ${subscription.id}" }

        val fallbackTier = transaction {
            val subRow = Subscriptions.selectAll().where {
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.status inList listOf("active", "trialing", "past_due"))
            }.orderBy(Subscriptions.id to SortOrder.DESC).firstOrNull()
            val tierName = subRow?.get(Subscriptions.plan)?.uppercase() ?: "FREE"
            pricingTierService.getCurrentTier(tierName) ?: pricingTierService.getCurrentTier("FREE")
        }

        val resolvedTier = resolveTierForSubscription(subscription, fallbackTier)
        val basePriceIds = setOfNotBlank(
            resolvedTier?.stripeBasePriceId,
            resolvedTier?.stripeYearlyBasePriceId
        )
        val overagePriceIds = setOfNotBlank(
            resolvedTier?.stripeOveragePriceId,
            resolvedTier?.stripeYearlyOveragePriceId
        )

        var baseItemId: String? = null
        var overageItemId: String? = null
        for (item in subscription.items.data) {
            val priceId = item.price?.id
            if (priceId != null && priceId in overagePriceIds) overageItemId = item.id
            if (priceId != null && priceId in basePriceIds) baseItemId = item.id
        }

        transaction {
            val existing = Subscriptions.selectAll().where {
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.stripe_subscription_id eq subscription.id)
            }.orderBy(Subscriptions.id to SortOrder.DESC).firstOrNull()

            val planName = resolvedTier?.tierName?.lowercase()
                ?: fallbackTier?.tierName?.lowercase()
                ?: "free"
            val tierId = resolvedTier?.id?.takeIf { it > 0 }
            val startInstant = subscription.startDate?.let { kotlinx.datetime.Instant.fromEpochSeconds(it) }
            val endInstant = subscription.trialEnd?.let { kotlinx.datetime.Instant.fromEpochSeconds(it) }

            if (existing != null) {
                logger.info { "Updating existing subscription row ${existing[Subscriptions.id]} for org $organizationId" }
                Subscriptions.update({ Subscriptions.id eq existing[Subscriptions.id] }) {
                    it[plan] = planName
                    it[status] = subscription.status
                    it[current_period_start] = startInstant
                    it[current_period_end] = endInstant
                    it[stripe_customer_id] = subscription.customer
                    it[pricing_tier_config_id] = tierId
                    it[stripe_base_item_id] = baseItemId
                    it[stripe_overage_item_id] = overageItemId
                }
            } else {
                logger.info { "Creating new subscription row for org $organizationId, plan=$planName, status=${subscription.status}" }
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

    private fun resolveTierForSubscription(
        subscription: Subscription,
        fallbackTier: PricingTierConfigResponse?
    ): PricingTierConfigResponse? {
        val metadataTierName = subscription.metadata["tier_name"]?.trim()?.takeIf { it.isNotBlank() }
        if (metadataTierName != null) {
            val metadataTier = pricingTierService.getCurrentTier(metadataTierName)
            if (metadataTier != null) return metadataTier
            logger.warn {
                "Stripe subscription ${subscription.id} has unknown tier_name metadata: $metadataTierName"
            }
        }

        val subscriptionPriceIds = subscription.items.data.mapNotNull { it.price?.id }.toSet()
        if (subscriptionPriceIds.isNotEmpty()) {
            val matchedTier = pricingTierService.getCurrentPlans()
                .map { it.tier }
                .firstOrNull { tier ->
                    val tierPriceIds = setOfNotBlank(
                        tier.stripeBasePriceId,
                        tier.stripeYearlyBasePriceId,
                        tier.stripeOveragePriceId,
                        tier.stripeYearlyOveragePriceId
                    )
                    tierPriceIds.any { it in subscriptionPriceIds }
                }
            if (matchedTier != null) return matchedTier
        }

        return fallbackTier ?: pricingTierService.getCurrentTier("FREE")
    }

    private fun setOfNotBlank(vararg values: String?): Set<String> {
        return values.filterNotNull().map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    fun handleCheckoutCompleted(session: com.stripe.model.checkout.Session) {
        logger.info { "handleCheckoutCompleted: session=${session.id}, customer=${session.customer}, subscription=${session.subscription}" }
        val customerId = session.customer
        val subscriptionId = session.subscription
        if (customerId == null || subscriptionId == null) {
            logger.error { "CRITICAL: Checkout session missing customer or subscription: session=${session.id}" }
            return
        }
        logger.info { "Retrieving Stripe subscription $subscriptionId" }
        val stripeSubscription = Subscription.retrieve(subscriptionId)
        syncSubscriptionFromStripe(stripeSubscription)

        val metadataOrgId = session.metadata?.get("organization_id")
        val organizationId = resolveOrganizationId(metadataOrgId, customerId)
        if (organizationId == null) {
            logger.error { "CRITICAL: Could not resolve organization ID from checkout session ${session.id}. metadata_org_id='$metadataOrgId', customer=$customerId" }
            return
        }
        logger.info { "Setting subscription $subscriptionId to active for org $organizationId" }
        transaction {
            val updateCount = Subscriptions.update({
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.stripe_subscription_id eq subscriptionId)
            }) {
                it[status] = "active"
                it[stripe_customer_id] = customerId
            }
            logger.info { "Updated $updateCount subscription rows to active" }
        }
    }

    fun handleInvoicePaid(invoice: Invoice) {
        val organizationId = resolveOrganizationId(invoice.metadata["organization_id"], invoice.customer) ?: return
        transaction {
            val q = Subscriptions.selectAll().where {
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

    fun handleSetupIntentSucceeded(setupIntent: SetupIntent) {
        val customerId = setupIntent.customer ?: return
        val paymentMethodId = setupIntent.paymentMethod ?: return
        
        SentryUtils.breadcrumb("stripe", "Setup intent succeeded", mapOf(
            "customer_id" to customerId,
            "payment_method_id" to paymentMethodId
        ))
        
        try {
            // Update customer's default payment method
            Customer.retrieve(customerId).update(
                CustomerUpdateParams.builder()
                    .setInvoiceSettings(
                        CustomerUpdateParams.InvoiceSettings.builder()
                            .setDefaultPaymentMethod(paymentMethodId)
                            .build()
                    )
                    .build()
            )
            
            logger.info { "Updated default payment method for customer $customerId" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update default payment method for customer $customerId" }
            Sentry.captureException(e) { scope ->
                scope.setTag("stripe.operation", "update_default_payment_method")
                scope.setExtra("customer_id", customerId)
                scope.setExtra("payment_method_id", paymentMethodId)
            }
            throw e
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
            Subscriptions.selectAll().where {
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

    fun applyDunningDowngrade(@Suppress("UNUSED_PARAMETER") graceDays: Int = 7): Int {
        val freeTier = pricingTierService.getCurrentTier("FREE")
        val now = kotlinx.datetime.Clock.System.now()
        val downgraded = transaction {
            val pastDueRows = Subscriptions.selectAll().where {
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
            Subscriptions.selectAll().where {
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.stripe_customer_id.isNotNull())
            }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
                ?.get(Subscriptions.stripe_customer_id)
        }
        if (!existing.isNullOrBlank()) return existing

        val (orgName, ownerEmail) = transaction {
            val orgName = Organizations.selectAll().where { Organizations.id eq organizationId }
                .firstOrNull()
                ?.get(Organizations.name)
                ?: "Moneat Organization $organizationId"
            val ownerUserId = Memberships.selectAll().where {
                (Memberships.organization_id eq organizationId) and (Memberships.role eq "owner")
            }
                .orderBy(Memberships.id to SortOrder.ASC)
                .firstOrNull()
                ?.get(Memberships.user_id)
            val fallbackUserId = Memberships.selectAll().where { Memberships.organization_id eq organizationId }
                .orderBy(Memberships.id to SortOrder.ASC)
                .firstOrNull()
                ?.get(Memberships.user_id)
            val userId = ownerUserId ?: fallbackUserId
            val email = userId?.let { id ->
                Users.selectAll().where { Users.id eq id }.firstOrNull()?.get(Users.email)
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
            val sub = Subscriptions.selectAll().where {
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
        logger.debug { "Resolving org ID: metadataOrgId=$metadataOrgId, customerId=$customerId" }
        val byMetadata = metadataOrgId?.toIntOrNull()
        logger.debug { "Parsed metadata org ID: $byMetadata" }
        if (byMetadata != null) {
            logger.debug { "Returning org ID from metadata: $byMetadata" }
            return byMetadata
        }
        if (customerId.isNullOrBlank()) {
            logger.debug { "Customer ID is blank, returning null" }
            return null
        }
        val orgId = transaction {
            Subscriptions.selectAll().where { Subscriptions.stripe_customer_id eq customerId }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
                ?.get(Subscriptions.organization_id)
        }
        logger.debug { "Found org ID from customer lookup: $orgId" }
        return orgId
    }

    private fun ensureEnabled() {
        if (!isStripeEnabled()) {
            throw IllegalStateException("Stripe integration is disabled")
        }
    }

    private fun findCustomerId(organizationId: Int): String? {
        return transaction {
            Subscriptions.selectAll().where {
                (Subscriptions.organization_id eq organizationId) and
                    (Subscriptions.stripe_customer_id.isNotNull())
            }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
                ?.get(Subscriptions.stripe_customer_id)
        }
    }

    private fun addDays(instant: Instant, days: Int): Instant {
        return Instant.fromEpochSeconds(instant.epochSeconds + (days * 86_400L))
    }

    private fun epochSecondsToIso(epochSeconds: Long?): String? {
        if (epochSeconds == null) return null
        return Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(kotlinx.datetime.TimeZone.UTC).date.toString()
    }
}
