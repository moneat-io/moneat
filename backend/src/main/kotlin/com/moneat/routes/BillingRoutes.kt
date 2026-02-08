package com.moneat.routes

import com.moneat.models.*
import com.moneat.services.BillingQuotaService
import com.moneat.services.PricingTierService
import com.moneat.services.StripeService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun Route.billingRoutes() {
    val pricingTierService = PricingTierService()
    val quotaService = BillingQuotaService(pricingTierService)
    val stripeService = StripeService(pricingTierService)

    route("/billing") {
        get("/plans") {
            val plans = pricingTierService.getCurrentPlans()
            call.respond(
                mapOf(
                    "plans" to plans,
                    "stripeEnabled" to stripeService.isStripeEnabled(),
                    "publishableKey" to stripeService.getPublishableKey()
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
            call.respond(usage)
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

        post("/portal") {
            val principal = call.principal<JWTPrincipal>() ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                return@post
            }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId = pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization access"))
                return@post
            }

            val request = call.receive<PortalSessionRequest>()
            try {
                val response = stripeService.createPortalSession(orgId, request.returnUrl)
                call.respond(response)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Failed to create portal session")))
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
                val sub = Subscriptions.select {
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
