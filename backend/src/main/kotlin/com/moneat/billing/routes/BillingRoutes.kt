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

import kotlinx.serialization.SerializationException
import java.io.IOException

import com.moneat.billing.models.BillingPlansListResponse
import com.moneat.billing.models.CheckoutSessionRequest
import com.moneat.billing.models.UpdateOnCallSeatsRequest
import com.moneat.billing.models.UpdatePaygBudgetRequest
import com.moneat.billing.models.UpdatePaygBudgetResponse
import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.PricingTierService
import com.moneat.billing.services.StripeService
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.BooleanResponse
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
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

private val logger = KotlinLogging.logger {}

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
) {
    val usageTrackingService = UsageTrackingService.instance

    route("/billing") {
        get("/usage") {
            val principal =
                call.principal<JWTPrincipal>() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
                    return@get
                }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId =
                pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

            val usage = quotaService.getUsageForOrganization(orgId)

            // Compute accurate counts from usage_records
            try {
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
                        listOf("apm_span", "apm")
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
            } catch (e: SerializationException) {
                logger.warn(e) { "Failed to compute bytes for org $orgId, falling back to original usage response" }
                call.respond(usage)
            } catch (e: IOException) {
                logger.warn(e) { "Failed to compute bytes for org $orgId, falling back to original usage response" }
                call.respond(usage)
            } catch (e: IllegalStateException) {
                logger.warn(e) { "Failed to compute bytes for org $orgId, falling back to original usage response" }
                call.respond(usage)
            } catch (e: IllegalArgumentException) {
                logger.warn(e) { "Failed to compute bytes for org $orgId, falling back to original usage response" }
                call.respond(usage)
            }
        }

        post("/checkout") {
            val principal =
                call.principal<JWTPrincipal>() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
                    return@post
                }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId =
                pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@post
                }

            val request = call.receive<CheckoutSessionRequest>()
            try {
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
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Invalid checkout request")))
            } catch (e: SerializationException) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse((e.message ?: "Failed to create checkout session"))
                )
            } catch (e: IOException) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse((e.message ?: "Failed to create checkout session"))
                )
            } catch (e: IllegalStateException) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse((e.message ?: "Failed to create checkout session"))
                )
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse((e.message ?: "Failed to create checkout session"))
                )
            }
        }

        get("/invoices") {
            val principal =
                call.principal<JWTPrincipal>() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
                    return@get
                }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId =
                pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

            try {
                call.respond(stripeService.listInvoices(orgId))
            } catch (e: SerializationException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to load invoices")))
            } catch (e: IOException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to load invoices")))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to load invoices")))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to load invoices")))
            }
        }

        get("/payment-method") {
            val principal =
                call.principal<JWTPrincipal>() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
                    return@get
                }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId =
                pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@get
                }

            try {
                call.respond(stripeService.getPaymentMethod(orgId))
            } catch (e: SerializationException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to load payment method")))
            } catch (e: IOException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to load payment method")))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to load payment method")))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to load payment method")))
            }
        }

        post("/setup-intent") {
            val principal =
                call.principal<JWTPrincipal>() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
                    return@post
                }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId =
                pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@post
                }

            try {
                call.respond(stripeService.createSetupIntent(orgId))
            } catch (e: SerializationException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to create setup intent")))
            } catch (e: IOException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to create setup intent")))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to create setup intent")))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to create setup intent")))
            }
        }

        post("/setup-intent/confirm") {
            val principal =
                call.principal<JWTPrincipal>() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
                    return@post
                }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId =
                pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@post
                }

            try {
                val request = call.receive<Map<String, String>>()
                val setupIntentId = request["setupIntentId"] ?: throw IllegalArgumentException("setupIntentId required")
                stripeService.confirmSetupIntentAndUpdatePaymentMethod(orgId, setupIntentId)
                call.respond(HttpStatusCode.OK, BooleanResponse(true))
            } catch (e: SerializationException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to confirm setup intent")))
            } catch (e: IOException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to confirm setup intent")))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to confirm setup intent")))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Failed to confirm setup intent")))
            }
        }

        post("/cancel") {
            val principal =
                call.principal<JWTPrincipal>() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
                    return@post
                }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId =
                pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@post
                }

            try {
                call.respond(stripeService.cancelSubscription(orgId))
            } catch (e: IllegalStateException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse((e.message ?: "No cancelable subscription found"))
                )
            } catch (e: SerializationException) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse((e.message ?: "Failed to cancel subscription"))
                )
            } catch (e: IOException) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse((e.message ?: "Failed to cancel subscription"))
                )
            } catch (e: IllegalStateException) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse((e.message ?: "Failed to cancel subscription"))
                )
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse((e.message ?: "Failed to cancel subscription"))
                )
            }
        }

        put("/payg-budget") {
            val principal =
                call.principal<JWTPrincipal>() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
                    return@put
                }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId =
                pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@put
                }
            val request = call.receive<UpdatePaygBudgetRequest>()
            if (request.paygBudgetCents < 0 || request.paygBudgetCents % 500 != 0) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("PAYG budget must be in $5 increments"))
                return@put
            }

            val tierContext = pricingTierService.getEffectiveTierForOrganization(orgId)
            if (!tierContext.tier.paygEnabled || tierContext.tier.tierName.equals("FREE", ignoreCase = true)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("PAYG budget is only available on paid tiers"))
                return@put
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
                return@put
            }
            call.respond(UpdatePaygBudgetResponse(paygBudgetCents = request.paygBudgetCents))
        }

        put("/oncall-seats") {
            val principal =
                call.principal<JWTPrincipal>() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
                    return@put
                }
            val userId = principal.payload.getClaim("userId").asInt()
            val orgId =
                pricingTierService.getPrimaryOrganizationIdForUser(userId) ?: run {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
                    return@put
                }

            val request = call.receive<UpdateOnCallSeatsRequest>()
            if (request.seats < 0) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Seats must be non-negative"))
                return@put
            }
            if (request.seats > 200) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Maximum 200 on-call seats allowed"))
                return@put
            }

            // Check if seats >= currently used
            val usedSeats =
                try {
                    val clazz = Class.forName("com.moneat.enterprise.services.oncall.OnCallScheduleService")
                    val instance = clazz.getDeclaredConstructor().newInstance()
                    val method = clazz.getMethod("getOnCallUsedSeats", Int::class.java)
                    method.invoke(instance, orgId) as? Int ?: 0
                } catch (_: SerializationException) {
                    0
                } catch (_: IOException) {
                    0
                } catch (_: IllegalStateException) {
                    0
                } catch (_: IllegalArgumentException) {
                    0
                }
            if (request.seats < usedSeats) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Cannot reduce seats below currently assigned users ($usedSeats)")
                )
                return@put
            }

            try {
                val response = stripeService.updateOnCallSeats(orgId, request.seats)
                call.respond(response)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Invalid request")))
            } catch (e: SerializationException) {
                logger.error(e) { "Failed to update on-call seats" }
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to update seats"))
            } catch (e: IOException) {
                logger.error(e) { "Failed to update on-call seats" }
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to update seats"))
            } catch (e: IllegalStateException) {
                logger.error(e) { "Failed to update on-call seats" }
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to update seats"))
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "Failed to update on-call seats" }
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to update seats"))
            }
        }
    }
}
