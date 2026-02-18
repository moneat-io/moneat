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

import com.moneat.config.EnvConfig
import com.moneat.services.oncall.EscalationEngineHolder
import com.moneat.services.oncall.TwilioService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import mu.KotlinLogging

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

            val twiml = if (digits == "1" && incidentId != null) {
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
    }
}
