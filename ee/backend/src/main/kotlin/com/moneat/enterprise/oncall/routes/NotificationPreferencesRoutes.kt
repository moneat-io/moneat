// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.routes

import com.moneat.enterprise.oncall.services.PushNotificationService
import com.moneat.enterprise.oncall.services.TwilioService
import com.moneat.enterprise.oncall.services.UserNotificationPreferencesService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.shared.models.AlertNotificationPreferences
import com.moneat.shared.models.SlackUserMappings
import com.moneat.shared.models.Users
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

// ===== Request / Response models =====

@Serializable
data class ChannelAvailability(
    val enabled: Boolean,
    val available: Boolean,
)

@Serializable
data class NotificationRuleCategory(
    val description: String,
    val channels: Map<String, ChannelAvailability>,
)

@Serializable
data class NotificationRulesResponse(
    val rules: Map<String, NotificationRuleCategory>,
)

@Serializable
data class UpdateNotificationRulesRequest(
    val channels: Map<String, Boolean>,
)

@Serializable
data class ContactMethodEntry(
    val type: String,
    val label: String,
    val value: String,
    val configured: Boolean,
    val healthy: Boolean,
)

@Serializable
data class ContactMethodsResponse(
    val methods: List<ContactMethodEntry>,
)

// ===== Routes =====

fun Route.notificationPreferencesRoutes(
    getPushService: () -> PushNotificationService,
) {
    val prefsService = UserNotificationPreferencesService()
    val pushService by lazy { getPushService() }
    val emailService = EmailService()
    val slackService = SlackService()
    val twilioService = TwilioService.instance

    // ── Contact methods ────────────────────────────────────────────────────────

    route("/v1/user/contact-methods") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()

                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                val methods = buildContactMethods(userId, pushService)
                call.respond(ContactMethodsResponse(methods))
            }

            post("/{type}/test") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val type = call.parameters["type"]

                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                when (type) {
                    "push" -> {
                        pushService.sendPush(
                            userId = userId,
                            title = "Test notification",
                            body = "Your push notifications are working.",
                            data = mapOf("type" to "test"),
                        )
                        call.respond(MessageResponse("Test push notification sent"))
                    }

                    "email" -> {
                        val userEmail = transaction {
                            Users.selectAll().where { Users.id eq userId }.singleOrNull()?.get(Users.email)
                        }
                        if (userEmail == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                            return@post
                        }
                        emailService.sendEmail(
                            to = userEmail,
                            subject = "Moneat — test notification",
                            htmlBody = "<p>Your email notifications are working.</p>",
                            textBody = "Your email notifications are working.",
                            emailType = "test",
                        )
                        call.respond(MessageResponse("Test email sent to $userEmail"))
                    }

                    "sms" -> {
                        if (!twilioService.isEnabled()) {
                            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("SMS not configured"))
                            return@post
                        }
                        val (phone, optedIn) = transaction {
                            val row = Users.selectAll().where { Users.id eq userId }.singleOrNull()
                            (row?.get(Users.phone_number)) to (row?.get(Users.oncall_phone_opt_in) ?: false)
                        }
                        if (phone.isNullOrBlank() || !optedIn) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("Phone not configured or consent not given"),
                            )
                            return@post
                        }
                        // Send a minimal test SMS (reuses existing sendSms which validates consent again)
                        twilioService.sendSms(phone, 0, "Test Notification", "TEST", userId)
                        call.respond(MessageResponse("Test SMS sent"))
                    }

                    "slack" -> {
                        val slackUserId = transaction {
                            SlackUserMappings
                                .selectAll()
                                .where { SlackUserMappings.userId eq userId }
                                .singleOrNull()
                                ?.get(SlackUserMappings.slackUserId)
                        }
                        if (slackUserId == null) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Slack account not linked"))
                            return@post
                        }
                        slackService.sendOnCallAlert(userId, 0, "Test Notification", "TEST")
                        call.respond(MessageResponse("Test Slack DM sent"))
                    }

                    else -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown contact method type: $type"))
                }
            }
        }
    }

    // ── Notification rules ─────────────────────────────────────────────────────

    route("/v1/user/notification-rules") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()

                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                val (hasPhone, phoneOptIn, hasSlack) = transaction {
                    val userRow = Users.selectAll().where { Users.id eq userId }.singleOrNull()
                    val slackLinked = SlackUserMappings.selectAll()
                        .where { SlackUserMappings.userId eq userId }
                        .singleOrNull() != null
                    Triple(
                        !userRow?.get(Users.phone_number).isNullOrBlank(),
                        userRow?.get(Users.oncall_phone_opt_in) ?: false,
                        slackLinked,
                    )
                }
                val hasDevices = pushService.getUserDevices(userId).isNotEmpty()

                val highUrgency = prefsService.getChannelPreferences(userId, "high_urgency")
                val lowUrgency = prefsService.getChannelPreferences(userId, "low_urgency")
                val shiftChange = prefsService.getChannelPreferences(userId, "shift_change")

                val rules = mapOf(
                    "high_urgency" to NotificationRuleCategory(
                        description = "On-call incidents requiring immediate response",
                        channels = mapOf(
                            "push" to ChannelAvailability(highUrgency.isChannelEnabled("push"), hasDevices),
                            "slack" to ChannelAvailability(highUrgency.isChannelEnabled("slack"), hasSlack),
                            "sms" to ChannelAvailability(highUrgency.isChannelEnabled("sms"), hasPhone && phoneOptIn),
                            "phone_call" to ChannelAvailability(
                                highUrgency.isChannelEnabled("phone_call"),
                                hasPhone && phoneOptIn,
                            ),
                        ),
                    ),
                    "low_urgency" to NotificationRuleCategory(
                        description = "System and dashboard alerts",
                        channels = mapOf(
                            "email" to ChannelAvailability(lowUrgency.isChannelEnabled("email"), true),
                            "slack" to ChannelAvailability(lowUrgency.isChannelEnabled("slack"), hasSlack),
                            "discord" to ChannelAvailability(lowUrgency.isChannelEnabled("discord"), false),
                        ),
                    ),
                    "shift_change" to NotificationRuleCategory(
                        description = "Notifications before your on-call shift starts",
                        channels = mapOf(
                            "push" to ChannelAvailability(shiftChange.isChannelEnabled("push"), hasDevices),
                            "email" to ChannelAvailability(shiftChange.isChannelEnabled("email"), true),
                        ),
                    ),
                )

                call.respond(NotificationRulesResponse(rules))
            }

            put("/{category}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val category = call.parameters["category"]

                if (userId == null || organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@put
                }

                val validCategories = setOf("high_urgency", "low_urgency", "shift_change")
                if (category == null || category !in validCategories) {
                    val msg = "Invalid category. Use: ${validCategories.joinToString(", ")}"
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(msg))
                    return@put
                }

                val request = try {
                    call.receive<UpdateNotificationRulesRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    return@put
                }

                // Guard: for high_urgency, prevent disabling all channels
                if (category == "high_urgency" && request.channels.values.none { it }) {
                    val err =
                        "At least one high urgency channel must remain enabled. " +
                            "Push notifications cannot be fully disabled."
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(err))
                    return@put
                }

                prefsService.updateChannelPreferences(userId, organizationId, category, request.channels)

                // For low_urgency: bridge to AlertNotificationPreferences for backward compatibility
                if (category == "low_urgency") {
                    bridgeLowUrgencyToAlertPreferences(userId, request.channels)
                }

                call.respond(MessageResponse("Notification rules updated"))
            }
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun buildContactMethods(userId: Int, pushService: PushNotificationService): List<ContactMethodEntry> {
    val methods = mutableListOf<ContactMethodEntry>()

    // Push devices
    val devices = pushService.getUserDevices(userId)
    if (devices.isEmpty()) {
        methods.add(
            ContactMethodEntry(
                type = "push",
                label = "Push notifications",
                value = "No devices registered",
                configured = false,
                healthy = false,
            ),
        )
    } else {
        devices.forEach { device ->
            methods.add(
                ContactMethodEntry(
                    type = "push",
                    label = device.deviceName ?: device.platform,
                    value = device.platform,
                    configured = true,
                    healthy = true,
                ),
            )
        }
    }

    // Email + phone from Users table, Slack from mappings
    transaction {
        val userRow = Users.selectAll().where { Users.id eq userId }.singleOrNull()
        val email = userRow?.get(Users.email)
        val phone = userRow?.get(Users.phone_number)
        val phoneOptIn = userRow?.get(Users.oncall_phone_opt_in) ?: false

        // Email
        if (email != null) {
            methods.add(
                ContactMethodEntry(
                    type = "email",
                    label = "Email",
                    value = maskEmail(email),
                    configured = true,
                    healthy = true,
                ),
            )
        }

        // Phone / SMS
        if (!phone.isNullOrBlank()) {
            methods.add(
                ContactMethodEntry(
                    type = "sms",
                    label = "SMS & Phone call",
                    value = maskPhone(phone),
                    configured = true,
                    healthy = phoneOptIn,
                ),
            )
        } else {
            methods.add(
                ContactMethodEntry(
                    type = "sms",
                    label = "SMS & Phone call",
                    value = "No phone number",
                    configured = false,
                    healthy = false,
                ),
            )
        }

        // Slack
        val slackMapping = SlackUserMappings
            .selectAll()
            .where { SlackUserMappings.userId eq userId }
            .singleOrNull()
        if (slackMapping != null) {
            methods.add(
                ContactMethodEntry(
                    type = "slack",
                    label = "Slack",
                    value = slackMapping[SlackUserMappings.slackUserId],
                    configured = true,
                    healthy = true,
                ),
            )
        } else {
            methods.add(
                ContactMethodEntry(
                    type = "slack",
                    label = "Slack",
                    value = "Not linked",
                    configured = false,
                    healthy = false,
                ),
            )
        }
    }

    return methods
}

private fun bridgeLowUrgencyToAlertPreferences(userId: Int, channels: Map<String, Boolean>) {
    val emailEnabled = channels["email"] ?: true
    val slackEnabled = channels["slack"] ?: true
    val discordEnabled = channels["discord"] ?: false

    transaction {
        AlertNotificationPreferences.update(
            { AlertNotificationPreferences.user_id eq userId },
        ) {
            it[AlertNotificationPreferences.email_enabled] = emailEnabled
            it[AlertNotificationPreferences.slack_enabled] = slackEnabled
            it[AlertNotificationPreferences.discord_enabled] = discordEnabled
        }
    }
}

private fun maskEmail(email: String): String {
    val parts = email.split("@")
    if (parts.size != 2) return email
    val local = parts[0]
    val masked = if (local.length <= 2) local else "${local.first()}***${local.last()}"
    return "$masked@${parts[1]}"
}

private fun maskPhone(phone: String): String {
    if (phone.length <= 4) return phone
    return "*".repeat(phone.length - 4) + phone.takeLast(4)
}
