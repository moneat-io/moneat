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

import com.moneat.billing.services.StripeService
import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.koin.core.context.GlobalContext

private val logger = KotlinLogging.logger {}

private const val MISSING_STRIPE_WEBHOOK_RAW_JSON_ID = "Missing 'id' in Stripe webhook raw JSON"

fun Route.stripeWebhookRoutes(
    stripeService: StripeService = GlobalContext.get().get(),
) {
    post("/api/webhooks/stripe") { handleStripeWebhook(stripeService) }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleStripeWebhook(stripeService: StripeService) {
    logger.info { "Received webhook request at /api/webhooks/stripe" }

    if (!stripeService.isStripeEnabled()) {
        logger.warn { "Stripe webhook rejected - Stripe is disabled" }
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Stripe disabled"))
        return
    }

    val payload = call.receiveText()
    val signature = call.request.headers["Stripe-Signature"]
    logger.info { "Webhook payload received, signature present: ${!signature.isNullOrBlank()}" }

    val event =
        suspendRunCatching {
            stripeService.verifyAndParseEvent(payload, signature)
        }.getOrElse { e ->
            logger.debug { "Stripe webhook signature verification failed: ${e.message}" }
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid webhook signature"))
            return
        }

    logger.info { "Webhook event verified: type=${event.type}, id=${event.id}" }

    if (stripeService.wasEventProcessed(event.id)) {
        call.respond(HttpStatusCode.OK, mapOf("received" to true, "duplicate" to true))
        return
    }

    suspendRunCatching {
        dispatchStripeEvent(stripeService, event)
        stripeService.markEventProcessed(event, "processed")
        call.respond(HttpStatusCode.OK, mapOf("received" to true))
    }.getOrElse { e ->
        logger.error(e) { "Failed handling Stripe webhook ${event.id} (${event.type})" }
        stripeService.markEventProcessed(event, "failed", e.message)
        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Webhook handling failed"))
    }
}

private fun dispatchStripeEvent(stripeService: StripeService, event: com.stripe.model.Event) {
    when (event.type) {
        "checkout.session.completed" -> {
            logger.info { "Processing checkout.session.completed event ${event.id}" }
            val session = resolveCheckoutSession(event)
            logger.info { "Calling handleCheckoutCompleted for session ${session.id}" }
            stripeService.handleCheckoutCompleted(session)
            logger.info { "Finished handleCheckoutCompleted" }
        }
        "customer.subscription.created", "customer.subscription.updated" -> {
            logger.info { "Processing ${event.type} event ${event.id}" }
            val subscription = resolveSubscription(event)
            logger.info { "Calling syncSubscriptionFromStripe for subscription ${subscription.id}" }
            stripeService.syncSubscriptionFromStripe(subscription)
            logger.info { "Finished syncSubscriptionFromStripe" }
        }
        "customer.subscription.deleted" -> {
            val subscription =
                event.dataObjectDeserializer.`object`.orElse(null) as? com.stripe.model.Subscription
            if (subscription != null) stripeService.handleSubscriptionDeleted(subscription)
        }
        "invoice.paid" -> {
            logger.info { "Processing invoice.paid event ${event.id}" }
            val invoice = resolveInvoice(event)
            val subId = invoice.parent?.subscriptionDetails?.getSubscription()
            logger.info {
                "Calling handleInvoicePaid for invoice ${invoice.id}, " +
                    "sub=$subId, customer=${invoice.customer}"
            }
            stripeService.handleInvoicePaid(invoice)
            logger.info { "Finished handleInvoicePaid" }
        }
        "invoice.payment_failed" -> {
            logger.info { "Processing invoice.payment_failed event ${event.id}" }
            val invoice = resolveInvoice(event)
            stripeService.handleInvoicePaymentFailed(invoice)
        }
        "setup_intent.succeeded" -> {
            logger.info { "Received setup_intent.succeeded webhook" }
            val setupIntent =
                event.dataObjectDeserializer.`object`.orElse(null) as? com.stripe.model.SetupIntent
            if (setupIntent != null) {
                logger.info {
                    "Processing setup intent: customer=${setupIntent.customer}, " +
                        "paymentMethod=${setupIntent.paymentMethod}"
                }
                stripeService.handleSetupIntentSucceeded(setupIntent)
            } else {
                logger.warn { "Could not deserialize SetupIntent from webhook event" }
            }
        }
        else -> logger.debug { "Unhandled Stripe event type: ${event.type}" }
    }
}

private fun resolveCheckoutSession(event: com.stripe.model.Event): com.stripe.model.checkout.Session {
    val sessionOpt = event.dataObjectDeserializer.`object`
    return if (sessionOpt.isPresent) {
        sessionOpt.get() as com.stripe.model.checkout.Session
    } else {
        val sessionId =
            Json.parseToJsonElement(event.dataObjectDeserializer.rawJson)
                .jsonObject["id"]?.jsonPrimitive?.content
                ?: error(MISSING_STRIPE_WEBHOOK_RAW_JSON_ID)
        com.stripe.model.checkout.Session.retrieve(sessionId)
    }
}

private fun resolveSubscription(event: com.stripe.model.Event): com.stripe.model.Subscription {
    val subscriptionOpt = event.dataObjectDeserializer.`object`
    return if (subscriptionOpt.isPresent) {
        subscriptionOpt.get() as com.stripe.model.Subscription
    } else {
        val subscriptionId =
            Json.parseToJsonElement(event.dataObjectDeserializer.rawJson)
                .jsonObject["id"]?.jsonPrimitive?.content
                ?: error(MISSING_STRIPE_WEBHOOK_RAW_JSON_ID)
        com.stripe.model.Subscription.retrieve(subscriptionId)
    }
}

private fun resolveInvoice(event: com.stripe.model.Event): com.stripe.model.Invoice {
    val invoiceOpt = event.dataObjectDeserializer.`object`
    return if (invoiceOpt.isPresent) {
        invoiceOpt.get() as com.stripe.model.Invoice
    } else {
        val invoiceId =
            Json.parseToJsonElement(event.dataObjectDeserializer.rawJson)
                .jsonObject["id"]?.jsonPrimitive?.content
                ?: error(MISSING_STRIPE_WEBHOOK_RAW_JSON_ID)
        logger.warn { "Invoice deserialization failed for event ${event.id}, fetching invoice $invoiceId from API" }
        com.stripe.model.Invoice.retrieve(invoiceId)
    }
}
