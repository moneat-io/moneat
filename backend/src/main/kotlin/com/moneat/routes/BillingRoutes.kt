package com.moneat.routes

import com.moneat.models.BillingPlansListResponse
import com.moneat.models.CheckoutSessionRequest
import com.moneat.models.Subscriptions
import com.moneat.models.UpdatePaygBudgetRequest
import com.moneat.models.UpdatePaygBudgetResponse
import com.moneat.services.BillingQuotaService
import com.moneat.services.PricingTierService
import com.moneat.services.StripeService
import com.moneat.services.UsageTrackingService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

private val logger = KotlinLogging.logger {}

fun Route.billingRoutes() {
    val pricingTierService = PricingTierService()
    val quotaService = BillingQuotaService(pricingTierService)
    val stripeService = StripeService(pricingTierService)
    val usageTrackingService = UsageTrackingService.instance

    route("/billing") {
        // Public endpoint for pricing plans (no auth required)
        get("/plans") {
            val plans = pricingTierService.getCurrentPlans()
            call.respond(
                BillingPlansListResponse(
                    plans = plans,
                    stripeEnabled = stripeService.isStripeEnabled(),
                    publishableKey = stripeService.getPublishableKey()
                )
            )
        }

        get("/usage") {
            val principal = call.principal<JWTPrincipal>() ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                return@get
            }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId = pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization access"))
                return@get
            }

            val usage = quotaService.getUsageForOrganization(orgId)
            
            // Compute accurate usedBytes from usage_records
            try {
                val startDate = LocalDate.parse(usage.periodStart)
                val endDate = LocalDate.parse(usage.periodEnd)
                val computedBytes = usageTrackingService.getTotalBytesForOrg(orgId, startDate, endDate)
                call.respond(usage.copy(usedBytes = computedBytes))
            } catch (e: Exception) {
                logger.warn(e) { "Failed to compute bytes for org $orgId, falling back to original usage response" }
                call.respond(usage)
            }
        }

        post("/checkout") {
            val principal = call.principal<JWTPrincipal>() ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                return@post
            }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId = pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization access"))
                return@post
            }

            val request = call.receive<CheckoutSessionRequest>()
            try {
                val response = stripeService.createCheckoutSession(
                    organizationId = orgId,
                    tierName = request.tierName,
                    billingInterval = request.billingInterval,
                    successUrl = request.successUrl,
                    cancelUrl = request.cancelUrl
                )
                call.respond(response)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid checkout request")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to create checkout session")))
            }
        }

        get("/invoices") {
            val principal = call.principal<JWTPrincipal>() ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                return@get
            }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId = pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization access"))
                return@get
            }

            try {
                call.respond(stripeService.listInvoices(orgId))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Failed to load invoices")))
            }
        }

        get("/payment-method") {
            val principal = call.principal<JWTPrincipal>() ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                return@get
            }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId = pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization access"))
                return@get
            }

            try {
                call.respond(stripeService.getPaymentMethod(orgId))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Failed to load payment method")))
            }
        }

        post("/setup-intent") {
            val principal = call.principal<JWTPrincipal>() ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                return@post
            }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId = pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization access"))
                return@post
            }

            try {
                call.respond(stripeService.createSetupIntent(orgId))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Failed to create setup intent")))
            }
        }

        post("/setup-intent/confirm") {
            val principal = call.principal<JWTPrincipal>() ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                return@post
            }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId = pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization access"))
                return@post
            }

            try {
                val request = call.receive<Map<String, String>>()
                val setupIntentId = request["setupIntentId"] ?: throw IllegalArgumentException("setupIntentId required")
                stripeService.confirmSetupIntentAndUpdatePaymentMethod(orgId, setupIntentId)
                call.respond(HttpStatusCode.OK, mapOf("success" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Failed to confirm setup intent")))
            }
        }

        post("/cancel") {
            val principal = call.principal<JWTPrincipal>() ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                return@post
            }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId = pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization access"))
                return@post
            }

            try {
                call.respond(stripeService.cancelSubscription(orgId))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "No cancelable subscription found")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to cancel subscription")))
            }
        }

        put("/payg-budget") {
            val principal = call.principal<JWTPrincipal>() ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                return@put
            }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId = pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization access"))
                return@put
            }
            val request = call.receive<UpdatePaygBudgetRequest>()
            if (request.paygBudgetCents < 0 || request.paygBudgetCents % 500 != 0) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "PAYG budget must be in $5 increments"))
                return@put
            }

            val tierContext = pricingTierService.getEffectiveTierForOrganization(orgId)
            if (!tierContext.tier.paygEnabled || tierContext.tier.tierName.equals("FREE", ignoreCase = true)) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "PAYG budget is only available on paid tiers"))
                return@put
            }

            val updated = transaction {
                val sub = Subscriptions.selectAll().where {
                    (Subscriptions.organization_id eq orgId) and
                        (Subscriptions.status inList listOf("active", "trialing", "past_due"))
                }
                    .orderBy(Subscriptions.id to SortOrder.DESC)
                    .firstOrNull()
                    ?: return@transaction false
                Subscriptions.update({ Subscriptions.id eq sub[Subscriptions.id] }) {
                    it[payg_budget_cents] = request.paygBudgetCents
                }
                true
            }
            if (!updated) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No active subscription found"))
                return@put
            }
            call.respond(UpdatePaygBudgetResponse(paygBudgetCents = request.paygBudgetCents))
        }
    }
}
