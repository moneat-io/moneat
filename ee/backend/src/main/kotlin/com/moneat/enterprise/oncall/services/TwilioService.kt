// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.config.EnvConfig
import com.moneat.enterprise.oncall.models.TwilioNotificationsSent
import com.moneat.shared.models.Users
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock

class TwilioService {
    private val logger = LoggerFactory.getLogger(TwilioService::class.java)

    private val accountSid = EnvConfig.get("TWILIO_ACCOUNT_SID", "")
    private val authToken = EnvConfig.get("TWILIO_AUTH_TOKEN", "")
    private val fromNumber = EnvConfig.get("TWILIO_FROM_NUMBER", "")
    private val backendUrl = EnvConfig.get("BACKEND_URL", "https://api.moneat.io")
    private val frontendUrl = EnvConfig.get("FRONTEND_URL")!!

    private val httpClient = HttpClient(CIO)

    /**
     * Validates the X-Twilio-Signature header for an incoming webhook request.
     * See: https://www.twilio.com/docs/usage/webhooks/webhooks-security
     *
     * @param signature the X-Twilio-Signature header value
     * @param url the full URL of the request (including query string)
     * @param params the POST body parameters, sorted by key
     */
    fun validateSignature(
        signature: String,
        url: String,
        params: Map<String, String>,
    ): Boolean {
        if (authToken.isEmpty()) return false
        val sortedParams = params.entries.sortedBy { it.key }.joinToString("") { it.key + it.value }
        val data = url + sortedParams
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(authToken.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val expected = Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
        return expected == signature
    }

    companion object {
        @JvmStatic
        val instance: TwilioService by lazy { TwilioService() }
    }

    fun isEnabled(): Boolean = accountSid.isNotEmpty() && authToken.isNotEmpty() && fromNumber.isNotEmpty()

    private fun basicAuthHeader(): String {
        val credentials = "$accountSid:$authToken"
        return "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray())}"
    }

    private val messagesUrl get() =
        "https://api.twilio.com/2010-04-01/Accounts/$accountSid/Messages.json"

    private val callsUrl get() =
        "https://api.twilio.com/2010-04-01/Accounts/$accountSid/Calls.json"

    suspend fun sendSms(
        toNumber: String,
        incidentId: Int,
        incidentTitle: String,
        priority: String,
        userId: Int,
    ) {
        if (!isEnabled()) {
            logger.warn("Twilio not configured, skipping SMS to $toNumber")
            return
        }

        // Race-condition guard: re-check consent AND that the stored number still matches.
        // This prevents sends to an old number if the user changed their number while opted in.
        val stillConsented =
            transaction {
                val row = Users.selectAll().where { Users.id eq userId }.singleOrNull()
                val optedIn = row?.get(Users.oncall_phone_opt_in) ?: false
                val storedPhone = row?.get(Users.phone_number)
                optedIn && storedPhone == toNumber
            }
        if (!stillConsented) {
            logger.warn("User $userId consent check failed (opted out or phone mismatch) before SMS, skipping")
            return
        }

        val acknowledgeUrl = "$frontendUrl/on-call/alerts/$incidentId"
        val body = "[$priority] $incidentTitle - Acknowledge alert: $acknowledgeUrl"
        val statusCallback = "$backendUrl/v1/webhooks/twilio/sms-status"

        try {
            val response =
                httpClient.post(messagesUrl) {
                    header(HttpHeaders.Authorization, basicAuthHeader())
                    setBody(
                        FormDataContent(
                            Parameters.build {
                                append("To", toNumber)
                                append("From", fromNumber)
                                append("Body", body)
                                append("StatusCallback", statusCallback)
                            },
                        ),
                    )
                }

            val responseText = response.bodyAsText()
            val twilioSid = extractSid(responseText, "sid")

            recordNotification(userId, incidentId, "sms", twilioSid, "queued", toNumber)

            if (response.status.value in 200..299) {
                logger.info("SMS sent to $toNumber for incident $incidentId, SID=$twilioSid")
            } else {
                logger.error("SMS failed for incident $incidentId: ${response.status} - $responseText")
            }
        } catch (e: Exception) {
            logger.error("Failed to send SMS to $toNumber for incident $incidentId", e)
        }
    }

    suspend fun makeCall(
        toNumber: String,
        incidentId: Int,
        incidentTitle: String,
        priority: String,
        userId: Int,
    ) {
        if (!isEnabled()) {
            logger.warn("Twilio not configured, skipping call to $toNumber")
            return
        }

        // Race-condition guard: re-check consent AND that the stored number still matches.
        // This prevents calls to an old number if the user changed their number while opted in.
        val stillConsented =
            transaction {
                val row = Users.selectAll().where { Users.id eq userId }.singleOrNull()
                val optedIn = row?.get(Users.oncall_phone_opt_in) ?: false
                val storedPhone = row?.get(Users.phone_number)
                optedIn && storedPhone == toNumber
            }
        if (!stillConsented) {
            logger.warn("User $userId consent check failed (opted out or phone mismatch) before call, skipping")
            return
        }

        val gatherUrl = "$backendUrl/v1/webhooks/twilio/gather?incidentId=$incidentId"
        val statusCallback = "$backendUrl/v1/webhooks/twilio/call-status"

        val safeTitle = incidentTitle.escapeXml()
        val safePriority = priority.escapeXml()
        val twiml = """<Response>
  <Say voice="alice">Moneat on-call alert. Priority $safePriority. $safeTitle.</Say>
  <Gather numDigits="1" action="$gatherUrl" method="POST">
    <Say voice="alice">Press 1 to acknowledge this alert.</Say>
  </Gather>
  <Say voice="alice">No input received. Goodbye.</Say>
</Response>"""

        try {
            val response =
                httpClient.post(callsUrl) {
                    header(HttpHeaders.Authorization, basicAuthHeader())
                    setBody(
                        FormDataContent(
                            Parameters.build {
                                append("To", toNumber)
                                append("From", fromNumber)
                                append("Twiml", twiml)
                                append("StatusCallback", statusCallback)
                            },
                        ),
                    )
                }

            val responseText = response.bodyAsText()
            val twilioSid = extractSid(responseText, "sid")

            recordNotification(userId, incidentId, "call", twilioSid, "queued", toNumber)

            if (response.status.value in 200..299) {
                logger.info("Call initiated to $toNumber for incident $incidentId, SID=$twilioSid")
            } else {
                logger.error("Call failed for incident $incidentId: ${response.status} - $responseText")
            }
        } catch (e: Exception) {
            logger.error("Failed to make call to $toNumber for incident $incidentId", e)
        }
    }

    suspend fun sendTestSms(toNumber: String) {
        if (!isEnabled()) {
            throw IllegalStateException("Twilio is not configured")
        }
        val body = "[TEST] Moneat on-call SMS test. If you received this, SMS is working correctly."
        val response =
            httpClient.post(messagesUrl) {
                header(HttpHeaders.Authorization, basicAuthHeader())
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("To", toNumber)
                            append("From", fromNumber)
                            append("Body", body)
                        },
                    ),
                )
            }
        if (response.status.value !in 200..299) {
            val text = response.bodyAsText()
            logger.error("Test SMS failed: ${response.status} - $text")
            throw Exception("Twilio SMS failed: ${response.status}")
        }
        logger.info("Test SMS sent to $toNumber")
    }

    fun sendTestSmsBlocking(toNumber: String) = runBlocking {
        sendTestSms(toNumber)
    }

    suspend fun makeTestCall(toNumber: String) {
        if (!isEnabled()) {
            throw IllegalStateException("Twilio is not configured")
        }
        val twiml =
            """<Response><Say voice="alice">This is a test call from Moneat on-call alerting. """ +
                """Your phone call integration is working correctly.</Say></Response>"""
        val response =
            httpClient.post(callsUrl) {
                header(HttpHeaders.Authorization, basicAuthHeader())
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("To", toNumber)
                            append("From", fromNumber)
                            append("Twiml", twiml)
                        },
                    ),
                )
            }
        if (response.status.value !in 200..299) {
            val text = response.bodyAsText()
            logger.error("Test call failed: ${response.status} - $text")
            throw Exception("Twilio call failed: ${response.status}")
        }
        logger.info("Test call initiated to $toNumber")
    }

    fun makeTestCallBlocking(toNumber: String) = runBlocking {
        makeTestCall(toNumber)
    }

    fun updateNotificationStatus(
        twilioSid: String,
        status: String,
    ) {
        transaction {
            TwilioNotificationsSent.update({ TwilioNotificationsSent.twilioSid eq twilioSid }) {
                it[TwilioNotificationsSent.status] = status
            }
        }
    }

    fun getFromNumber(): String = fromNumber

    private fun recordNotification(
        userId: Int,
        incidentId: Int,
        channel: String,
        twilioSid: String?,
        status: String,
        phoneNumber: String,
    ) {
        transaction {
            TwilioNotificationsSent.insert {
                it[TwilioNotificationsSent.userId] = userId
                it[TwilioNotificationsSent.alertId] = incidentId
                it[TwilioNotificationsSent.channel] = channel
                it[TwilioNotificationsSent.twilioSid] = twilioSid
                it[TwilioNotificationsSent.status] = status
                it[TwilioNotificationsSent.phoneNumber] = phoneNumber
                it[createdAt] = Clock.System.now()
            }
        }
    }

    private fun extractSid(
        responseText: String,
        key: String,
    ): String? =
        try {
            Json
                .parseToJsonElement(responseText)
                .jsonObject[key]
                ?.jsonPrimitive
                ?.content
        } catch (_: Exception) {
            null
        }
}

private fun String.escapeXml(): String =
    this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
