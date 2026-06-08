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

package com.moneat.billing.routes

import com.moneat.auth.requireCurrentOrg
import com.moneat.billing.models.APM_SPAN_USAGE_DEBUG_DEFAULT_LIMIT
import com.moneat.billing.models.APM_SPAN_USAGE_DEBUG_MAX_LIMIT
import com.moneat.billing.models.APM_SPAN_USAGE_DEBUG_MIN_LIMIT
import com.moneat.billing.models.BillingPlansListResponse
import com.moneat.billing.models.CheckoutSessionRequest
import com.moneat.billing.models.UpdateOnCallSeatsRequest
import com.moneat.billing.models.UpdatePaygBudgetRequest
import com.moneat.billing.models.UpdatePaygBudgetResponse
import com.moneat.billing.services.BillingUsageInsightsService
import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.PricingTierService
import com.moneat.billing.services.StripeService
import com.moneat.billing.services.getUsageInsightsSafely
import com.moneat.config.EnvConfig
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.BooleanResponse
import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.context.GlobalContext
import kotlinx.serialization.SerializationException
import java.io.IOException

private val logger = KotlinLogging.logger {}
private const val FAILED_TO_CREATE_CHECKOUT_SESSION = "Failed to create checkout session"
private const val FAILED_TO_CANCEL_SUBSCRIPTION = "Failed to cancel subscription"
private const val FAILED_TO_UPDATE_ON_CALL_SEATS = "Failed to update on-call seats"
private const val FAILED_TO_UPDATE_SEATS = "Failed to update seats"
private const val PAYG_INCREMENT_CENTS = 500
private const val MAX_ONCALL_SEATS = 200

// Public billing endpoints (no auth required)
fun Route.publicBillingRoutes(
    pricingTierService: PricingTierService = GlobalContext.get().get(),
    stripeService: StripeService = GlobalContext.get().get(),
) {
    route("/billing") {
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
    }
}

// Protected billing endpoints (require auth)
fun Route.billingRoutes(
    pricingTierService: PricingTierService = GlobalContext.get().get(),
    quotaService: BillingQuotaService = GlobalContext.get().get(),
    stripeService: StripeService = GlobalContext.get().get(),
    insightsService: BillingUsageInsightsService = BillingUsageInsightsService(quotaService),
) {
    val usageTrackingService = UsageTrackingService.instance

    route("/billing") {
        get("/usage") {
            val orgId = call.requireCurrentOrg()?.orgId ?: return@get

            val usage = quotaService.getUsageForOrganization(orgId)

            // Compute accurate counts from usage_records
            suspendRunCatching {
                val startDate = LocalDate.parse(usage.periodStart)
                val endDate = LocalDate.parse(usage.periodEnd)
                val computedBytes = usageTrackingService.getTotalBytesForOrg(orgId, startDate, endDate)
                val computedLogs =
                    usageTrackingService.getEventCountForOrg(
                        orgId,
                        startDate,
                        endDate,
                        listOf("log", "logs")
                    )
                val computedApmSpans =
                    usageTrackingService.getEventCountForOrg(
                        orgId,
                        startDate,
                        endDate,
                        listOf("apm_span", "apm", "otlp_trace", "dd_trace", "sentry_trace")
                    )
                val computedCustomMetrics =
                    usageTrackingService.getEventCountForOrg(
                        orgId,
                        startDate,
                        endDate,
                        listOf("custom_metric", "metric")
                    )
                call.respond(
                    usage.copy(
                        usedBytes = maxOf(computedBytes, usage.usedBytes),
                        usedLogs = maxOf(computedLogs, usage.usedLogs),
                        usedApmSpans = maxOf(computedApmSpans, usage.usedApmSpans),
                        usedCustomMetrics = maxOf(computedCustomMetrics, usage.usedCustomMetrics)
                    )
                )
            }.getOrElse { e ->
                logger.warn(e) { "Failed to compute bytes for org $orgId, falling back to original usage response" }
                call.respond(usage)
            }
        }

        get("/usage/insights") {
            if (EnvConfig.SelfHost.enabled) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Usage Insights is available in Moneat Cloud")
                )
                return@get
            }
            val orgId = call.requireCurrentOrg()?.orgId ?: return@get

            val insights = insightsService.getUsageInsightsSafely(orgId)
            if (insights == null) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Usage insights are temporarily unavailable")
                )
                return@get
            }

            call.respond(insights)
        }

        get("/usage/apm-spans") {
            val orgId = call.requireCurrentOrg()?.orgId ?: return@get
            val limit = call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.coerceIn(APM_SPAN_USAGE_DEBUG_MIN_LIMIT, APM_SPAN_USAGE_DEBUG_MAX_LIMIT)
                ?: APM_SPAN_USAGE_DEBUG_DEFAULT_LIMIT
            val usage = quotaService.getUsageForOrganization(orgId)
            val debug = quotaService.getApmSpanUsageDebug(
                organizationId = orgId,
                periodStart = LocalDate.parse(usage.periodStart),
                periodEnd = LocalDate.parse(usage.periodEnd),
                limit = limit
            )
            call.respond(debug)
        }

        post("/checkout") {
            val orgId = call.requireCurrentOrg()?.orgId ?: return@post
            handleBillingCheckout(call, stripeService, orgId)
        }

        get("/invoices") {
            val orgId = call.requireCurrentOrg()?.orgId ?: return@get

            suspendRunCatching {
                call.respond(stripeService.listInvoices(orgId))
            }.getOrElse { e ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to load invoices")))
            }
        }

        get("/payment-method") {
            val orgId = call.requireCurrentOrg()?.orgId ?: return@get

            suspendRunCatching {
                call.respond(stripeService.getPaymentMethod(orgId))
            }.getOrElse { e ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to load payment method")))
            }
        }

        post("/setup-intent") {
            val orgId = call.requireCurrentOrg()?.orgId ?: return@post

            suspendRunCatching {
                call.respond(stripeService.createSetupIntent(orgId))
            }.getOrElse { e ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to create setup intent")))
            }
        }

        post("/setup-intent/confirm") {
            val orgId = call.requireCurrentOrg()?.orgId ?: return@post

            suspendRunCatching {
                val request = call.receive<Map<String, String>>()
                val setupIntentId = request["setupIntentId"] ?: throw IllegalArgumentException("setupIntentId required")
                stripeService.confirmSetupIntentAndUpdatePaymentMethod(orgId, setupIntentId)
                call.respond(HttpStatusCode.OK, BooleanResponse(true))
            }.getOrElse { e ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to confirm setup intent")))
            }
        }

        post("/cancel") {
            val orgId = call.requireCurrentOrg()?.orgId ?: return@post
            handleBillingCancel(call, stripeService, orgId)
        }

        put("/payg-budget") {
            val orgId = call.requireCurrentOrg()?.orgId ?: return@put
            handleBillingPaygBudget(call, pricingTierService, orgId)
        }

        put("/oncall-seats") {
            val orgId = call.requireCurrentOrg()?.orgId ?: return@put
            handleBillingOnCallSeats(call, stripeService, orgId)
        }
    }
}

private suspend fun handleBillingCheckout(
    call: ApplicationCall,
    stripeService: StripeService,
    orgId: Int,
) {
    val request = call.receive<CheckoutSessionRequest>()
    suspendRunCatching {
        val response =
            stripeService.createCheckoutSession(
                organizationId = orgId,
                tierName = request.tierName,
                billingInterval = request.billingInterval,
                successUrl = request.successUrl,
                cancelUrl = request.cancelUrl,
                oncallSeats = request.oncallSeats
            )
        call.respond(response)
    }.onFailure { e ->
        when (e) {
            is IllegalArgumentException -> call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(e.message ?: "Invalid checkout request")
            )
            else -> call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(e.message ?: FAILED_TO_CREATE_CHECKOUT_SESSION)
            )
        }
    }
}

private suspend fun handleBillingCancel(
    call: ApplicationCall,
    stripeService: StripeService,
    orgId: Int,
) {
    suspendRunCatching {
        call.respond(stripeService.cancelSubscription(orgId))
    }.onFailure { e ->
        when (e) {
            is IllegalStateException -> call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(e.message ?: "No cancelable subscription found")
            )
            else -> call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(e.message ?: FAILED_TO_CANCEL_SUBSCRIPTION)
            )
        }
    }
}

private suspend fun handleBillingPaygBudget(
    call: ApplicationCall,
    pricingTierService: PricingTierService,
    orgId: Int,
) {
    val request = call.receive<UpdatePaygBudgetRequest>()
    if (request.paygBudgetCents < 0 || request.paygBudgetCents % PAYG_INCREMENT_CENTS != 0) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("PAYG budget must be in $5 increments"))
        return
    }

    val tierContext = pricingTierService.getEffectiveTierForOrganization(orgId)
    if (!tierContext.tier.paygEnabled || tierContext.tier.tierName.equals("FREE", ignoreCase = true)) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("PAYG budget is only available on paid tiers"))
        return
    }

    val updated =
        transaction {
            val sub =
                Subscriptions
                    .selectAll()
                    .where {
                        (Subscriptions.organization_id eq orgId) and
                            (Subscriptions.status inList listOf("active", "trialing", "past_due"))
                    }.orderBy(Subscriptions.id to SortOrder.DESC)
                    .firstOrNull()
                    ?: return@transaction false
            Subscriptions.update({ Subscriptions.id eq sub[Subscriptions.id] }) {
                it[payg_budget_cents] = request.paygBudgetCents
            }
            true
        }
    if (!updated) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("No active subscription found"))
        return
    }
    call.respond(UpdatePaygBudgetResponse(paygBudgetCents = request.paygBudgetCents))
}

private suspend fun handleBillingOnCallSeats(
    call: ApplicationCall,
    stripeService: StripeService,
    orgId: Int,
) {
    val request = call.receive<UpdateOnCallSeatsRequest>()
    if (request.seats < 0) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Seats must be non-negative"))
        return
    }
    if (request.seats > MAX_ONCALL_SEATS) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Maximum 200 on-call seats allowed"))
        return
    }

    // Check if seats >= currently used
    val usedSeats =
        suspendRunCatching {
            val clazz = Class.forName("com.moneat.enterprise.services.oncall.OnCallScheduleService")
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("getOnCallUsedSeats", Int::class.java)
            method.invoke(instance, orgId) as? Int ?: 0
        }.getOrElse { _ ->
            0
        }
    if (request.seats < usedSeats) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("Cannot reduce seats below currently assigned users ($usedSeats)")
        )
        return
    }

    try {
        val response = stripeService.updateOnCallSeats(orgId, request.seats)
        call.respond(response)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Invalid request")))
    } catch (e: SerializationException) {
        logger.error(e) { FAILED_TO_UPDATE_ON_CALL_SEATS }
        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(FAILED_TO_UPDATE_SEATS))
    } catch (e: IOException) {
        logger.error(e) { FAILED_TO_UPDATE_ON_CALL_SEATS }
        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(FAILED_TO_UPDATE_SEATS))
    } catch (e: IllegalStateException) {
        logger.error(e) { FAILED_TO_UPDATE_ON_CALL_SEATS }
        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(FAILED_TO_UPDATE_SEATS))
    }
}
