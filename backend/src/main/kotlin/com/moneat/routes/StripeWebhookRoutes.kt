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

package com.moneat.routes

import com.moneat.services.StripeService
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun Route.stripeWebhookRoutes() {
    val stripeService = StripeService()

    post("/api/webhooks/stripe") {
        logger.info { "Received webhook request at /api/webhooks/stripe" }

        if (!stripeService.isStripeEnabled()) {
            logger.warn { "Stripe webhook rejected - Stripe is disabled" }
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Stripe disabled"))
            return@post
        }

        val payload = call.receiveText()
        val signature = call.request.headers["Stripe-Signature"]
        logger.info { "Webhook payload received, signature present: ${!signature.isNullOrBlank()}" }

        val event =
            try {
                stripeService.verifyAndParseEvent(payload, signature)
            } catch (e: Exception) {
                logger.debug { "Stripe webhook signature verification failed: ${e.message}" }
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid webhook signature"))
                return@post
            }

        logger.info { "Webhook event verified: type=${event.type}, id=${event.id}" }

        if (stripeService.wasEventProcessed(event.id)) {
            call.respond(HttpStatusCode.OK, mapOf("received" to true, "duplicate" to true))
            return@post
        }

        try {
            when (event.type) {
                "checkout.session.completed" -> {
                    logger.info { "Processing checkout.session.completed event ${event.id}" }
                    val sessionOpt = event.dataObjectDeserializer.`object`
                    val session =
                        if (sessionOpt.isPresent) {
                            sessionOpt.get() as com.stripe.model.checkout.Session
                        } else {
                            // Fallback: retrieve from Stripe API using ID from raw data
                            val sessionId =
                                event.dataObjectDeserializer.rawJson.let {
                                    Json.parseToJsonElement(it)
                                        .jsonObject["id"]
                                        ?.jsonPrimitive
                                        ?.content
                                        ?: error("Missing 'id' in Stripe webhook raw JSON")
                                }
                            com.stripe.model.checkout.Session
                                .retrieve(sessionId)
                        }
                    logger.info { "Calling handleCheckoutCompleted for session ${session.id}" }
                    stripeService.handleCheckoutCompleted(session)
                    logger.info { "Finished handleCheckoutCompleted" }
                }

                "customer.subscription.created",
                "customer.subscription.updated" -> {
                    logger.info { "Processing ${event.type} event ${event.id}" }
                    val subscriptionOpt = event.dataObjectDeserializer.`object`
                    val subscription =
                        if (subscriptionOpt.isPresent) {
                            subscriptionOpt.get() as com.stripe.model.Subscription
                        } else {
                            // Fallback: retrieve from Stripe API using ID from raw data
                            val subscriptionId =
                                event.dataObjectDeserializer.rawJson.let {
                                    Json.parseToJsonElement(it)
                                        .jsonObject["id"]
                                        ?.jsonPrimitive
                                        ?.content
                                        ?: error("Missing 'id' in Stripe webhook raw JSON")
                                }
                            com.stripe.model.Subscription
                                .retrieve(subscriptionId)
                        }
                    logger.info { "Calling syncSubscriptionFromStripe for subscription ${subscription.id}" }
                    stripeService.syncSubscriptionFromStripe(subscription)
                    logger.info { "Finished syncSubscriptionFromStripe" }
                }

                "customer.subscription.deleted" -> {
                    val subscription =
                        event.dataObjectDeserializer.`object`.orElse(null)
                            as? com.stripe.model.Subscription
                    if (subscription != null) {
                        stripeService.handleSubscriptionDeleted(subscription)
                    }
                }

                "invoice.paid" -> {
                    val invoice =
                        event.dataObjectDeserializer.`object`.orElse(null)
                            as? com.stripe.model.Invoice
                    if (invoice != null) {
                        stripeService.handleInvoicePaid(invoice)
                    }
                }

                "invoice.payment_failed" -> {
                    val invoice =
                        event.dataObjectDeserializer.`object`.orElse(null)
                            as? com.stripe.model.Invoice
                    if (invoice != null) {
                        stripeService.handleInvoicePaymentFailed(invoice)
                    }
                }

                "setup_intent.succeeded" -> {
                    logger.info { "Received setup_intent.succeeded webhook" }
                    val setupIntent =
                        event.dataObjectDeserializer.`object`.orElse(null)
                            as? com.stripe.model.SetupIntent
                    if (setupIntent != null) {
                        logger.info { "Processing setup intent: customer=${setupIntent.customer}, paymentMethod=${setupIntent.paymentMethod}" }
                        stripeService.handleSetupIntentSucceeded(setupIntent)
                    } else {
                        logger.warn { "Could not deserialize SetupIntent from webhook event" }
                    }
                }

                else -> {
                    logger.debug { "Unhandled Stripe event type: ${event.type}" }
                }
            }

            stripeService.markEventProcessed(event, "processed")
            call.respond(HttpStatusCode.OK, mapOf("received" to true))
        } catch (e: Exception) {
            logger.error(e) { "Failed handling Stripe webhook ${event.id} (${event.type})" }
            stripeService.markEventProcessed(event, "failed", e.message)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Webhook handling failed"))
        }
    }
}
