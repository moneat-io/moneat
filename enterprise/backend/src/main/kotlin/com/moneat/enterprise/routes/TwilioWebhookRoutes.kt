// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.routes

import com.moneat.config.EnvConfig
import com.moneat.enterprise.services.oncall.EscalationEngineHolder
import com.moneat.enterprise.services.oncall.TwilioService
import com.moneat.models.OnCallPhoneConsentEvents
import com.moneat.models.Users
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

fun Route.twilioWebhookRoutes() {
    val twilioService = TwilioService.instance

    route("/v1/webhooks/twilio") {
        // SMS delivery status callback
        post("/sms-status") {
            val params = call.receiveParameters()
            val signature = call.request.headers["X-Twilio-Signature"] ?: ""
            val url = "${EnvConfig.get("BACKEND_URL", "https://api.moneat.io")}/v1/webhooks/twilio/sms-status"
            val paramMap = params.entries().associate { it.key to (it.value.firstOrNull() ?: "") }
            if (!twilioService.validateSignature(signature, url, paramMap)) {
                logger.warn { "Invalid Twilio signature on /sms-status" }
                call.respondText("Forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                return@post
            }
            val messageSid = params["MessageSid"]
            val messageStatus = params["MessageStatus"]
            logger.debug { "Twilio SMS status: SID=$messageSid, status=$messageStatus" }
            if (messageSid != null && messageStatus != null) {
                twilioService.updateNotificationStatus(messageSid, messageStatus)
            }
            call.respondText("", ContentType.Text.Plain, HttpStatusCode.NoContent)
        }

        // Voice call status callback
        post("/call-status") {
            val params = call.receiveParameters()
            val signature = call.request.headers["X-Twilio-Signature"] ?: ""
            val url = "${EnvConfig.get("BACKEND_URL", "https://api.moneat.io")}/v1/webhooks/twilio/call-status"
            val paramMap = params.entries().associate { it.key to (it.value.firstOrNull() ?: "") }
            if (!twilioService.validateSignature(signature, url, paramMap)) {
                logger.warn { "Invalid Twilio signature on /call-status" }
                call.respondText("Forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                return@post
            }
            val callSid = params["CallSid"]
            val callStatus = params["CallStatus"]
            logger.debug { "Twilio call status: SID=$callSid, status=$callStatus" }
            if (callSid != null && callStatus != null) {
                twilioService.updateNotificationStatus(callSid, callStatus)
            }
            call.respondText("", ContentType.Text.Plain, HttpStatusCode.NoContent)
        }

        // DTMF gather: user pressed a digit during the voice call
        post("/gather") {
            val params = call.receiveParameters()
            val incidentId = call.request.queryParameters["incidentId"]?.toIntOrNull()
            val signature = call.request.headers["X-Twilio-Signature"] ?: ""
            val baseUrl = "${EnvConfig.get("BACKEND_URL", "https://api.moneat.io")}/v1/webhooks/twilio/gather"
            val fullUrl = if (incidentId != null) "$baseUrl?incidentId=$incidentId" else baseUrl
            val paramMap = params.entries().associate { it.key to (it.value.firstOrNull() ?: "") }
            if (!twilioService.validateSignature(signature, fullUrl, paramMap)) {
                logger.warn { "Invalid Twilio signature on /gather" }
                call.respondText("Forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                return@post
            }
            val digits = params["Digits"]

            val twiml =
                if (digits == "1" && incidentId != null) {
                    // Acknowledge the incident - use system user 0 as "phone acknowledge"
                    val escalationEngine = EscalationEngineHolder.instance
                    if (escalationEngine != null) {
                        val acknowledged = escalationEngine.acknowledgeIncidentByPhone(incidentId)
                        if (acknowledged) {
                            logger.info { "Incident $incidentId acknowledged via phone call" }
                            """<Response><Say voice="alice">Incident acknowledged. Thank you. Goodbye.</Say></Response>"""
                        } else {
                            """<Response><Say voice="alice">This incident has already been acknowledged or resolved. Goodbye.</Say></Response>"""
                        }
                    } else {
                        logger.error { "EscalationEngine not available; cannot acknowledge incident $incidentId via phone" }
                        """<Response><Say voice="alice">Acknowledgement is temporarily unavailable. Please try the app. Goodbye.</Say></Response>"""
                    }
                } else {
                    """<Response><Say voice="alice">Invalid input. Goodbye.</Say></Response>"""
                }

            call.respondText(twiml, ContentType.Text.Xml, HttpStatusCode.OK)
        }

        // Inbound SMS: handle opt-out/help/start keywords
        post("/inbound-sms") {
            val params = call.receiveParameters()
            val signature = call.request.headers["X-Twilio-Signature"] ?: ""
            val url = "${EnvConfig.get("BACKEND_URL", "https://api.moneat.io")}/v1/webhooks/twilio/inbound-sms"
            val paramMap = params.entries().associate { it.key to (it.value.firstOrNull() ?: "") }
            if (!twilioService.validateSignature(signature, url, paramMap)) {
                logger.warn { "Invalid Twilio signature on /inbound-sms" }
                call.respondText("Forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                return@post
            }

            val from = params["From"] ?: ""
            val body = params["Body"]?.trim()?.uppercase() ?: ""

            val stopKeywords = setOf("STOP", "UNSUBSCRIBE", "CANCEL", "END", "QUIT")
            val twiml =
                when {
                    stopKeywords.contains(body) -> {
                        // Opt out ALL accounts associated with this phone number and record audit events.
                        // Phone numbers are not unique in the DB, so we use a bulk update rather than
                        // singleOrNull() to avoid a crash if duplicates exist.
                        transaction {
                            val now = Clock.System.now()
                            val users = Users.selectAll().where { Users.phone_number eq from }.toList()
                            users.forEach { user ->
                                val userId = user[Users.id]
                                Users.update({ Users.id eq userId }) {
                                    it[Users.oncall_phone_opt_in] = false
                                    it[Users.oncall_phone_opted_out_at] = now
                                }
                                OnCallPhoneConsentEvents.insert {
                                    it[OnCallPhoneConsentEvents.user_id] = userId
                                    it[OnCallPhoneConsentEvents.phone_number] = from
                                    it[OnCallPhoneConsentEvents.event_type] = "OPT_OUT"
                                    it[OnCallPhoneConsentEvents.created_at] = now
                                }
                                logger.info { "User $userId opted out via SMS STOP keyword" }
                            }
                        }
                        """<Response><Message>You have been unsubscribed from Moneat on-call alerts. You will not receive further SMS messages. To re-enable, update your notification settings in the Moneat app.</Message></Response>"""
                    }

                    body == "HELP" -> {
                        """<Response><Message>Moneat on-call alerts: Reply STOP to unsubscribe. To manage settings, visit the Moneat app. For support, contact support@moneat.io</Message></Response>"""
                    }

                    body == "START" || body == "YES" -> {
                        """<Response><Message>To re-enable on-call SMS alerts, please open the Moneat app and update your notification settings. This ensures your consent is properly recorded.</Message></Response>"""
                    }

                    else -> {
                        """<Response></Response>"""
                    }
                }

            call.respondText(twiml, ContentType.Text.Xml, HttpStatusCode.OK)
        }
    }
}
