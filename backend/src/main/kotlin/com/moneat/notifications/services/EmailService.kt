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

package com.moneat.notifications.services

import kotlinx.serialization.SerializationException
import java.io.IOException

import com.moneat.shared.models.EmailsSent
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Users
import com.moneat.utils.SentryUtils
import io.ktor.server.config.ApplicationConfig
import io.sentry.Sentry
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.Properties
import kotlin.math.roundToInt
import kotlin.time.Clock
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

private const val DANGER_COLOR = "#dc2626"
private const val EMAIL_SUBTLE = "#eef0f4"
private const val EMAIL_TEXT = "#161922"
private const val EMAIL_TEXT_STRONG = "#0e1016"
private const val EMAIL_TEXT_MUTED = "#6b7280"
private const val EMAIL_BORDER = "#d8dce3"
private const val EMAIL_BORDER_MUTED = "#e4e7ec"
private const val EMAIL_ACCENT = "#0ea5e9"
private const val EMAIL_LINK = "#0369a1"
private const val EMAIL_DANGER = "#cf2126"
private const val EMAIL_WARNING = "#e0a100"
private const val EMAIL_SUCCESS = "#0e8c6b"
private const val EMAIL_MONO =
    "'JetBrains Mono',ui-monospace,'SF Mono',Menlo,Consolas,monospace"
private const val EMAIL_SANS =
    "'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif"

/** Escapes HTML special characters for safe inclusion in email templates. */
private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private const val FULL_PROGRESS_PERCENT = 100
private const val MIN_VISIBLE_PROGRESS_PERCENT = 2
private const val TOP_ISSUES_COUNT = 5
private const val SPARKLINE_HEIGHT = 46
private const val EMAIL_STACK_FRAMES_COUNT = 5
private const val SETTINGS_URL_PLACEHOLDER = "{{ settingsUrl }}"
private const val YEAR_PLACEHOLDER = "{{ year }}"

/** Sends transactional and notification email via Jakarta Mail and HTML templates. */
class EmailService {
    private val config = ApplicationConfig("application.conf")
    private val fromEmail = config.property("email.from").getString()
    private val frontendUrl = config.property("email.frontendUrl").getString()
    private val salesInbox = config.propertyOrNull("email.salesInbox")?.getString() ?: "support@moneat.io"

    private val smtpHost = config.propertyOrNull("email.smtp.host")?.getString()
    private val smtpPort = config.propertyOrNull("email.smtp.port")?.getString()?.toIntOrNull() ?: 587
    private val smtpUsername = config.propertyOrNull("email.smtp.username")?.getString()
    private val smtpPassword = config.propertyOrNull("email.smtp.password")?.getString()
    private val smtpAuth = config.propertyOrNull("email.smtp.auth")?.getString()?.toBoolean() ?: true
    private val smtpStartTls = config.propertyOrNull("email.smtp.starttls")?.getString()?.toBoolean() ?: true

    private val session: Session? by lazy {
        if (smtpHost.isNullOrBlank() || smtpUsername.isNullOrBlank() || smtpPassword.isNullOrBlank()) {
            logger.warn { "SMTP configuration incomplete. Email sending will be disabled." }
            null
        } else {
            val props =
                Properties().apply {
                    put("mail.smtp.host", smtpHost)
                    put("mail.smtp.port", smtpPort.toString())
                    put("mail.smtp.auth", smtpAuth.toString())
                    put("mail.smtp.starttls.enable", smtpStartTls.toString())
                    put("mail.smtp.ssl.protocols", "TLSv1.2")
                }

            Session.getInstance(
                props,
                object : Authenticator() {
                    /** Credentials used for authenticated SMTP submission. */
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(smtpUsername, smtpPassword)
                    }
                }
            )
        }
    }

    fun sendVerificationEmail(
        email: String,
        token: String,
        userName: String?
    ) {
        val verificationUrl = "$frontendUrl/verify-email?token=$token"
        val displayName = userName ?: email.substringBefore("@")

        val subject = "Verify your email address"
        val htmlBody = loadVerificationTemplate(displayName, verificationUrl)
        val textBody =
            """
            Hi $displayName,
            
            Thanks for signing up for Moneat! Please verify your email address by clicking the link below:
            
            $verificationUrl
            
            This link will expire in 24 hours.
            
            If you didn't create an account, you can safely ignore this email.
            
            Best regards,
            The Moneat Team
            """.trimIndent()

        sendEmail(email, subject, htmlBody, textBody, "verification")
    }

    fun sendPasswordResetEmail(
        email: String,
        token: String,
        userName: String?
    ) {
        val resetUrl = "$frontendUrl/reset-password?token=$token"
        val displayName = userName ?: email.substringBefore("@")

        val subject = "Reset your password"
        val htmlBody = loadPasswordResetTemplate(displayName, resetUrl)
        val textBody =
            """
            Hi $displayName,
            
            We received a request to reset your password. Click the link below to reset it:
            
            $resetUrl
            
            This link will expire in 1 hour.
            
            If you didn't request a password reset, you can safely ignore this email.
            
            Best regards,
            The Moneat Team
            """.trimIndent()

        sendEmail(email, subject, htmlBody, textBody, "password_reset")
    }

    fun sendInvitationEmail(
        toEmail: String,
        data: InvitationEmailData
    ) {
        val inviteUrl = "$frontendUrl/accept-invite?token=${data.token}"

        val subject = "You've been invited to join ${data.orgName} on Moneat"
        val htmlBody =
            loadInvitationTemplate(
                data.inviterName,
                data.orgName,
                data.role,
                inviteUrl,
                data.inviterEmail
            )
        val textBody =
            """
            You've been invited to join ${data.orgName} on Moneat
            
            ${data.inviterName} has invited you to join their team as a ${data.role.lowercase()}.
            
            Click the link below to accept the invitation:
            
            $inviteUrl
            
            This invitation will expire in 7 days.
            
            If you don't have an account yet, you'll be able to create one when you accept the invitation.
            
            Best regards,
            The Moneat Team
            """.trimIndent()

        sendEmail(toEmail, subject, htmlBody, textBody, "org_invitation")
    }

    /**
     * Sends a multipart alternative (plain text + HTML) message when SMTP is configured;
     * otherwise logs a preview and records a failed send for metrics.
     */
    fun sendEmail(
        to: String,
        subject: String,
        htmlBody: String,
        textBody: String,
        emailType: String = "other"
    ) {
        sendEmail(to, subject, htmlBody, textBody, emailType, replyTo = null)
    }

    /**
     * Sends a multipart alternative (plain text + HTML) message when SMTP is configured;
     * otherwise logs a preview and records a failed send for metrics. When [replyTo] is set
     * the message carries a Reply-To header so recipients can respond to that address directly.
     */
    fun sendEmail(
        to: String,
        subject: String,
        htmlBody: String,
        textBody: String,
        emailType: String,
        replyTo: String?
    ) {
        SentryUtils.breadcrumb(
            "email",
            "Sending email",
            mapOf(
                "to" to to,
                "subject" to subject,
                "type" to emailType
            )
        )

        val mailSession = session
        if (mailSession == null) {
            logger.warn { "Email service not configured. Would send to $to: $subject" }
            logger.info { "Email preview:\n$textBody" }
            trackEmailSent(to, emailType, false)
            return
        }

        var success = false
        try {
            val textPart =
                MimeBodyPart().apply {
                    setText(textBody, "UTF-8")
                }
            val htmlPart =
                MimeBodyPart().apply {
                    setContent(htmlBody, "text/html; charset=UTF-8")
                }
            val message =
                MimeMessage(mailSession).apply {
                    setFrom(InternetAddress(fromEmail, "Moneat"))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                    if (!replyTo.isNullOrBlank()) {
                        this.replyTo = InternetAddress.parse(replyTo)
                    }
                    setSubject(subject)

                    val multipart = MimeMultipart("alternative")
                    multipart.addBodyPart(textPart)
                    multipart.addBodyPart(htmlPart)
                    setContent(multipart)
                }

            Transport.send(message)
            success = true
            logger.info { "Email sent to $to" }
            SentryUtils.breadcrumb(
                "email",
                "Email sent successfully",
                mapOf(
                    "to" to to,
                    "type" to emailType
                )
            )
        } catch (e: SerializationException) {
            logger.error("Failed to send email to $to", e)
            Sentry.captureException(e) { scope ->
                scope.setTag(EMAIL_OPERATION_TAG, "send")
                scope.setExtra(EMAIL_TO_TAG, to)
                scope.setExtra(EMAIL_SUBJECT_TAG, subject)
                scope.setExtra(EMAIL_TYPE_TAG, emailType)
            }
            throw e
        } catch (e: IOException) {
            logger.error("Failed to send email to $to", e)
            Sentry.captureException(e) { scope ->
                scope.setTag(EMAIL_OPERATION_TAG, "send")
                scope.setExtra(EMAIL_TO_TAG, to)
                scope.setExtra(EMAIL_SUBJECT_TAG, subject)
                scope.setExtra(EMAIL_TYPE_TAG, emailType)
            }
            throw e
        } catch (e: IllegalStateException) {
            logger.error("Failed to send email to $to", e)
            Sentry.captureException(e) { scope ->
                scope.setTag(EMAIL_OPERATION_TAG, "send")
                scope.setExtra(EMAIL_TO_TAG, to)
                scope.setExtra(EMAIL_SUBJECT_TAG, subject)
                scope.setExtra(EMAIL_TYPE_TAG, emailType)
            }
            throw e
        } catch (e: IllegalArgumentException) {
            logger.error("Failed to send email to $to", e)
            Sentry.captureException(e) { scope ->
                scope.setTag(EMAIL_OPERATION_TAG, "send")
                scope.setExtra(EMAIL_TO_TAG, to)
                scope.setExtra(EMAIL_SUBJECT_TAG, subject)
                scope.setExtra(EMAIL_TYPE_TAG, emailType)
            }
            throw e
        } finally {
            trackEmailSent(to, emailType, success)
        }
    }

    /** Records send outcome in [EmailsSent] for the recipient's organization when resolvable. */
    private fun trackEmailSent(
        recipient: String,
        emailType: String,
        success: Boolean
    ) {
        suspendRunCatching {
            val normalizedEmail = recipient.lowercase().trim()
            transaction {
                // Try to find organization for the recipient
                val orgId =
                    Users
                        .selectAll()
                        .where { Users.email eq normalizedEmail }
                        .firstOrNull()
                        ?.let { user ->
                            Memberships
                                .selectAll()
                                .where { Memberships.user_id eq user[Users.id] }
                                .firstOrNull()
                                ?.get(Memberships.organization_id)
                        }

                EmailsSent.insert {
                    it[EmailsSent.organization_id] = orgId
                    it[EmailsSent.email_type] = emailType
                    it[EmailsSent.recipient] = normalizedEmail
                    it[EmailsSent.sent_at] = Clock.System.now()
                    it[EmailsSent.success] = success
                }
            }
        }.getOrElse { e ->
            logger.warn(e) { "Failed to track email sent to $recipient" }
        }
    }

    private fun loadTemplate(templateName: String): String? =
        this::class.java.classLoader
            .getResourceAsStream("email-templates/$templateName")
            ?.bufferedReader()
            ?.use { it.readText() }

    private fun commonEmailTokens(
        settingsUrl: String = "$frontendUrl/settings/notifications",
        unsubscribeUrl: String = "$frontendUrl/settings/notifications"
    ): Map<String, String> =
        mapOf(
            "settingsUrl" to settingsUrl,
            "unsubscribeUrl" to unsubscribeUrl,
            "year" to java.time.Year.now().value.toString()
        )

    private fun String.replaceTokens(replacements: Map<String, String>): String =
        replacements.entries.fold(this) { html, (token, value) ->
            html.replace(Regex("""\{\{\s*${Regex.escape(token)}\s*}}"""), value)
        }

    private fun String.replaceSentinels(replacements: Map<String, String>): String =
        replacements.entries.fold(this) { html, (token, value) ->
            html.replace(token, value)
        }

    private fun invitationInviterDetails(
        inviterName: String,
        inviterEmail: String?
    ): String {
        val safeName = inviterName.escapeHtml()
        val safeEmail =
            inviterEmail
                ?.takeIf { it.isNotBlank() && it != inviterName }
                ?.escapeHtml()
        return if (safeEmail == null) safeName else "$safeName &middot; $safeEmail"
    }

    private fun loadVerificationTemplate(
        userName: String,
        verificationUrl: String
    ): String {
        val template = loadTemplate("verify-email.html")

        return if (template != null) {
            template.replaceTokens(
                commonEmailTokens() + mapOf(
                    "userName" to userName.escapeHtml(),
                    "verificationUrl" to verificationUrl.escapeHtml()
                )
            )
        } else {
            // Fallback to inline HTML if template doesn't exist
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
            </head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                <div style="background-color: #f8f9fa; padding: 30px; border-radius: 8px;">
                    <h1 style="color: #2563eb; margin-bottom: 20px;">Verify Your Email</h1>
                    <p>Hi $userName,</p>
                    <p>Thanks for signing up for Moneat! Please verify your email address by clicking the button below:</p>
                    <div style="margin: 30px 0;">
                        <a href="$verificationUrl" style="display: inline-block; background-color: #2563eb; color: white; padding: 12px 30px; text-decoration: none; border-radius: 6px; font-weight: 500;">Verify Email</a>
                    </div>
                    <p style="color: #666; font-size: 14px;">This link will expire in 24 hours.</p>
                    <p style="color: #666; font-size: 14px;">If you didn't create an account, you can safely ignore this email.</p>
                    <hr style="border: none; border-top: 1px solid #ddd; margin: 30px 0;">
                    <p style="color: #999; font-size: 12px;">Moneat - Mobile-First Error Monitoring</p>
                </div>
            </body>
            </html>
            """.trimIndent()
        }
    }

    private fun loadPasswordResetTemplate(
        userName: String,
        resetUrl: String
    ): String {
        val template = loadTemplate("reset-password.html")

        return if (template != null) {
            template.replaceTokens(
                commonEmailTokens() + mapOf(
                    "userName" to userName.escapeHtml(),
                    "resetUrl" to resetUrl.escapeHtml()
                )
            )
        } else {
            // Fallback to inline HTML if template doesn't exist
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
            </head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                <div style="background-color: #f8f9fa; padding: 30px; border-radius: 8px;">
                    <h1 style="color: #2563eb; margin-bottom: 20px;">Reset Your Password</h1>
                    <p>Hi $userName,</p>
                    <p>We received a request to reset your password. Click the button below to reset it:</p>
                    <div style="margin: 30px 0;">
                        <a href="$resetUrl" style="display: inline-block; background-color: #2563eb; color: white; padding: 12px 30px; text-decoration: none; border-radius: 6px; font-weight: 500;">Reset Password</a>
                    </div>
                    <p style="color: #666; font-size: 14px;">This link will expire in 1 hour.</p>
                    <p style="color: #666; font-size: 14px;">If you didn't request a password reset, you can safely ignore this email.</p>
                    <hr style="border: none; border-top: 1px solid #ddd; margin: 30px 0;">
                    <p style="color: #999; font-size: 12px;">Moneat - Mobile-First Error Monitoring</p>
                </div>
            </body>
            </html>
            """.trimIndent()
        }
    }

    private fun loadInvitationTemplate(
        inviterName: String,
        orgName: String,
        role: String,
        inviteUrl: String,
        inviterEmail: String?
    ): String {
        val template = loadTemplate("org-invitation.html")

        return if (template != null) {
            template.replaceTokens(
                commonEmailTokens() + mapOf(
                    "inviterName" to inviterName.escapeHtml(),
                    "inviterDetails" to invitationInviterDetails(inviterName, inviterEmail),
                    "orgName" to orgName.escapeHtml(),
                    "role" to role.replaceFirstChar { it.uppercase() }.escapeHtml(),
                    "inviteUrl" to inviteUrl.escapeHtml()
                )
            )
        } else {
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
            </head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                <div style="background-color: #f8f9fa; padding: 30px; border-radius: 8px;">
                    <h1 style="color: #2563eb; margin-bottom: 20px;">You've been invited to join $orgName</h1>
                    <p>Hi there,</p>
                    <p>$inviterName has invited you to join <strong>$orgName</strong> on Moneat as a <strong>${role.replaceFirstChar { it.uppercase() }}</strong>.</p>
                    <div style="margin: 30px 0;">
                        <a href="$inviteUrl" style="display: inline-block; background-color: #2563eb; color: white; padding: 12px 30px; text-decoration: none; border-radius: 6px; font-weight: 500;">Accept Invitation</a>
                    </div>
                    <p style="color: #666; font-size: 14px;">This invitation will expire in 7 days.</p>
                    <p style="color: #666; font-size: 14px;">If you don't have an account yet, you'll be able to create one when you accept the invitation.</p>
                    <hr style="border: none; border-top: 1px solid #ddd; margin: 30px 0;">
                    <p style="color: #999; font-size: 12px;">Moneat - Mobile-First Error Monitoring</p>
                </div>
            </body>
            </html>
            """.trimIndent()
        }
    }

    data class ErrorAlertData(
        val issueTitle: String,
        val issueLevel: String,
        val issueCulprit: String,
        val issueMessage: String,
        val issueCount: String,
        val issueUrl: String,
        val projectName: String,
        val environment: String,
        val timestamp: String,
        val stackTrace: String,
        val settingsUrl: String,
        val unsubscribeUrl: String,
        val errorRate: String = "—",
        val errorRateDelta: String = "—",
        val p95Latency: String = "—",
        val p95LatencyDelta: String = "—",
        val throughput: String = "—",
        val throughputDelta: String = "—",
        val usersAffected: String = "—",
        val firstSeen: String = timestamp,
        val lastSeen: String = timestamp,
        val eventSeries: List<Int> = emptyList(),
        val spikeIndex: Int? = null,
        val issueFunction: String = issueCulprit.substringBefore(" ").ifBlank { issueCulprit },
        val issueLocation: String = issueCulprit.substringAfter(" ", issueCulprit),
        val release: String = "—",
        val deploySummary: String = "No deploy context available",
        val contextTags: List<ContextTag> = emptyList(),
        val codeOwner: String = "—",
        val stackFrames: List<StackFrame> = emptyList()
    )

    data class ContextTag(
        val key: String,
        val value: String
    )

    data class StackFrame(
        val text: String,
        val inApp: Boolean = false,
        val heading: Boolean = false
    )

    data class InvitationEmailData(
        val inviterName: String,
        val inviterEmail: String?,
        val orgName: String,
        val role: String,
        val token: String
    )

    data class HostProcessRow(
        val name: String,
        val cpu: String,
        val memory: String
    )

    data class HostAlertData(
        val hostName: String,
        val lastSeenText: String,
        val hostUrl: String,
        val settingsUrl: String,
        val unsubscribeUrl: String,
        val severity: String = "Critical",
        val metricName: String = "CPU",
        val condition: String = "CPU above threshold",
        val currentValue: String = "—",
        val baselineSummary: String = "baseline unavailable",
        val sustainedDuration: String = "—",
        val threshold: String = "90%",
        val environment: String = "production",
        val triggeredAt: String = lastSeenText,
        val cpuLabel: String = "CPU",
        val cpuPercent: String = "0",
        val memoryLabel: String = "Memory",
        val memoryPercent: String = "0",
        val diskLabel: String = "Disk",
        val diskPercent: String = "0",
        val load1m: String = "—",
        val vcpu: String = "—",
        val privateIp: String = "—",
        val region: String = "—",
        val instanceType: String = "—",
        val memoryTotal: String = "—",
        val os: String = "—",
        val agentVersion: String = "—",
        val uptime: String = "—",
        val processes: List<HostProcessRow> = emptyList(),
        val historySeries: List<Int> = emptyList()
    )

    data class MonitorAlertData(
        val monitorName: String,
        val status: String,
        val message: String,
        val monitorUrl: String,
        val settingsUrl: String,
        val unsubscribeUrl: String,
        val metricName: String = "Monitor status",
        val currentValue: String = "down",
        val baselineSummary: String = "baseline unavailable",
        val threshold: String = "threshold",
        val condition: String = "condition unavailable",
        val cadence: String = "configured interval",
        val triggeredAt: String = Clock.System.now().toString(),
        val scope: String = "all monitored targets",
        val dashboardName: String = monitorName,
        val widgetName: String = monitorName,
        val environment: String = "production",
        val projectName: String = monitorName,
        val breachSummary: String = "Samples in breach are highlighted.",
        val historySeries: List<Int> = emptyList(),
        val breachIndex: Int? = null
    )

    data class ResolvedAlertData(
        val targetName: String,
        val metricName: String,
        val alertUrl: String,
        val settingsUrl: String,
        val unsubscribeUrl: String,
        val duration: String = "—",
        val peakValue: String = "—",
        val currentValue: String = "normal",
        val monitorName: String = metricName,
        val triggeredAt: String = "—",
        val recoveredAt: String = Clock.System.now().toString(),
        val acknowledgedBy: String = "—",
        val environment: String = "production",
        val resolutionSummary: String =
            "The metric returned to normal levels and the alert closed automatically."
    )

    data class WeeklySummaryData(
        val startDate: String,
        val endDate: String,
        val totalEvents: String,
        val eventsTrend: Int?,
        val newIssues: String,
        val issuesTrend: Int?,
        val affectedUsers: String,
        val usersTrend: Int?,
        val topIssues: List<TopIssue>,
        val projects: List<ProjectSummary>,
        val dashboardUrl: String,
        val settingsUrl: String,
        val unsubscribeUrl: String,
        val organizationName: String = "your organization"
    )

    data class TopIssue(
        val title: String,
        val culprit: String,
        val project: String,
        val count: String
    )

    data class ProjectSummary(
        val name: String,
        val events: String,
        val issues: String,
        val crashFree: String
    )

    data class BillingInsightRow(
        val label: String,
        val used: String,
        val limit: String,
        val percent: String,
        val status: String
    )

    data class BillingInsightEmailData(
        val organizationName: String,
        val plan: String,
        val periodStart: String,
        val periodEnd: String,
        val headline: String,
        val summary: String,
        val dashboardUrl: String,
        val settingsUrl: String,
        val rows: List<BillingInsightRow>,
        val totalOverage: String,
        val topDriver: String = "—",
        val topDriverUsage: String = "—",
        val unsubscribeUrl: String = settingsUrl
    )

    fun sendErrorAlertEmail(
        to: String,
        data: ErrorAlertData
    ) {
        val subject = "[${data.projectName}] ${data.issueLevel.uppercase()}: ${data.issueTitle}"
        val htmlBody = loadErrorAlertTemplate(data)
        val textBody =
            """
            New ${data.issueLevel.uppercase()} in ${data.projectName}
            
            ${data.issueTitle}
            
            Error: ${data.issueMessage}
            Location: ${data.issueCulprit}
            Environment: ${data.environment}
            First Seen: ${data.timestamp}
            Occurrences: ${data.issueCount}
            
            View full details: ${data.issueUrl}
            
            Manage notification preferences: ${data.settingsUrl}
            """.trimIndent()

        sendEmail(to, subject, htmlBody, textBody, "error_alert")
    }

    fun sendWeeklySummaryEmail(
        to: String,
        data: WeeklySummaryData
    ) {
        val subject = "Your Weekly Summary: ${data.totalEvents} events, ${data.newIssues} new issues"
        val htmlBody = loadWeeklySummaryTemplate(data)
        val topIssuesList = data.topIssues.take(TOP_ISSUES_COUNT)
            .joinToString("\n") { "- ${it.title} (${it.project}): ${it.count} events" }
        val eventsTrendText = formatTrendText(data.eventsTrend)
        val issuesTrendText = formatTrendText(data.issuesTrend)
        val usersTrendText = formatTrendText(data.usersTrend)
        val textBody =
            """
            Your Weekly Summary (${data.startDate} – ${data.endDate})
            
            KEY STATS:
            - Total Events: ${data.totalEvents} ($eventsTrendText)
            - New Issues: ${data.newIssues} ($issuesTrendText)
            - Affected Users: ${data.affectedUsers} ($usersTrendText)
            
            TOP ISSUES:
            $topIssuesList
            
            Open Dashboard: ${data.dashboardUrl}
            Manage preferences: ${data.settingsUrl}
            """.trimIndent()

        sendEmail(to, subject, htmlBody, textBody, "weekly_summary")
    }

    fun sendBillingThresholdAlertEmail(
        to: String,
        subject: String,
        data: BillingInsightEmailData
    ) {
        val htmlBody = loadBillingInsightTemplate("billing-threshold-alert.html", data)
        val rows = data.rows.joinToString("\n") { "- ${it.label}: ${it.used} / ${it.limit} (${it.percent})" }
        val textBody =
            """
            ${data.headline}

            ${data.summary}

            Plan: ${data.plan}
            Billing period: ${data.periodStart} to ${data.periodEnd}
            Estimated overage: ${data.totalOverage}

            $rows

            Open Usage Insights: ${data.dashboardUrl}
            Billing settings: ${data.settingsUrl}
            """.trimIndent()

        sendEmail(to, subject, htmlBody, textBody, "billing_threshold_alert")
    }

    fun sendBillingInsightsEmail(
        to: String,
        data: BillingInsightEmailData
    ) {
        val subject = "[${data.organizationName}] Usage Insights digest"
        val htmlBody = loadBillingInsightTemplate("billing-insights.html", data)
        val rows = data.rows.joinToString("\n") { "- ${it.label}: ${it.used} / ${it.limit} (${it.percent})" }
        val textBody =
            """
            ${data.headline}

            ${data.summary}

            Plan: ${data.plan}
            Billing period: ${data.periodStart} to ${data.periodEnd}
            Estimated overage: ${data.totalOverage}

            $rows

            Open Usage Insights: ${data.dashboardUrl}
            Billing settings: ${data.settingsUrl}
            """.trimIndent()

        sendEmail(to, subject, htmlBody, textBody, "billing_insights_digest")
    }

    fun sendHostDownEmail(
        to: String,
        hostName: String,
        lastSeenText: String,
        hostUrl: String
    ) {
        sendHostAlertEmail(
            to = to,
            data =
            HostAlertData(
                hostName = hostName,
                lastSeenText = lastSeenText,
                hostUrl = hostUrl,
                settingsUrl = "$frontendUrl/settings/notifications",
                unsubscribeUrl = "$frontendUrl/settings/notifications",
                currentValue = "not reporting",
                baselineSummary = "last seen $lastSeenText",
                sustainedDuration = lastSeenText,
                condition = "agent heartbeat missing",
                triggeredAt = lastSeenText
            )
        )
    }

    fun sendHostAlertEmail(
        to: String,
        data: HostAlertData
    ) {
        val subject = "Host alert: ${data.hostName}"
        val htmlBody = loadHostAlertTemplate(data)
        val textBody =
            """
            Host alert: ${data.hostName}
            
            Metric: ${data.metricName}
            Current value: ${data.currentValue}
            Threshold: ${data.threshold}
            Status: ${data.lastSeenText}
            
            The monitoring agent or host metric is in an alert state.
            
            View: ${data.hostUrl}
            
            ---
            Moneat Server Monitoring
            """.trimIndent()

        sendEmail(to, subject, htmlBody, textBody, "host_down")
    }

    fun sendUptimeAlertEmail(
        to: String,
        monitorName: String,
        status: String,
        message: String,
        monitorUrl: String
    ) {
        val isDown = status.lowercase() == "down"
        if (!isDown) {
            sendResolvedAlertEmail(
                to = to,
                emailType = "uptime_alert",
                data =
                ResolvedAlertData(
                    targetName = monitorName,
                    metricName = "Uptime monitor",
                    alertUrl = monitorUrl,
                    settingsUrl = "$frontendUrl/settings/notifications",
                    unsubscribeUrl = "$frontendUrl/settings/notifications",
                    monitorName = monitorName,
                    currentValue = status.uppercase(),
                    resolutionSummary = message.ifBlank {
                        "The monitor is back up and the alert closed automatically."
                    }
                )
            )
            return
        }

        sendMonitorAlertEmail(
            to = to,
            data =
            MonitorAlertData(
                monitorName = monitorName,
                status = status,
                message = message,
                monitorUrl = monitorUrl,
                settingsUrl = "$frontendUrl/settings/notifications",
                unsubscribeUrl = "$frontendUrl/settings/notifications",
                metricName = "Uptime status",
                currentValue = status.uppercase(),
                threshold = "up",
                condition = "status != up",
                dashboardName = monitorName,
                widgetName = monitorName,
                breachSummary = message.ifBlank { "The latest check failed." }
            )
        )
    }

    fun sendMonitorAlertEmail(
        to: String,
        data: MonitorAlertData
    ) {
        val subject = "Monitor alert: ${data.monitorName}"
        val htmlBody = loadMonitorAlertTemplate(data)
        val textBody =
            """
            Monitor alert: ${data.monitorName}
            
            Metric: ${data.metricName}
            Current value: ${data.currentValue}
            Condition: ${data.condition}
            ${if (data.message.isNotBlank()) "Message: ${data.message}" else ""}
            
            View: ${data.monitorUrl}
            """.trimIndent()

        sendEmail(to, subject, htmlBody, textBody, "uptime_alert")
    }

    fun sendHostUpEmail(
        to: String,
        hostName: String,
        hostUrl: String
    ) {
        sendResolvedAlertEmail(
            to = to,
            emailType = "host_up",
            data =
            ResolvedAlertData(
                targetName = hostName,
                metricName = "Host heartbeat",
                alertUrl = hostUrl,
                settingsUrl = "$frontendUrl/settings/notifications",
                unsubscribeUrl = "$frontendUrl/settings/notifications",
                monitorName = "Host heartbeat",
                currentValue = "reporting",
                resolutionSummary = "The host is reporting metrics again."
            )
        )
    }

    fun sendResolvedAlertEmail(
        to: String,
        emailType: String,
        data: ResolvedAlertData
    ) {
        val subject = "Resolved: ${data.targetName}"
        val htmlBody = loadResolvedTemplate(data)
        val textBody =
            """
            Resolved: ${data.targetName}
            
            Metric: ${data.metricName}
            Current value: ${data.currentValue}
            
            ${data.resolutionSummary}
            
            View: ${data.alertUrl}
            
            ---
            Moneat
            """.trimIndent()

        sendEmail(to, subject, htmlBody, textBody, emailType)
    }

    private fun loadErrorAlertTemplate(data: ErrorAlertData): String {
        val template = loadTemplate("error-alert.html")

        return if (template != null) {
            template
                .replaceTokens(
                    commonEmailTokens(data.settingsUrl, data.unsubscribeUrl) + mapOf(
                        "issueTitle" to data.issueTitle.escapeHtml(),
                        "issueLevel" to data.issueLevel.escapeHtml(),
                        "issueCulprit" to data.issueCulprit.escapeHtml(),
                        "issueMessage" to data.issueMessage.escapeHtml(),
                        "issueCount" to data.issueCount.escapeHtml(),
                        "issueUrl" to data.issueUrl.escapeHtml(),
                        "projectName" to data.projectName.escapeHtml(),
                        "environment" to data.environment.escapeHtml(),
                        "timestamp" to data.timestamp.escapeHtml(),
                        "stackTrace" to data.stackTrace.escapeHtml(),
                        "errorRate" to data.errorRate.escapeHtml(),
                        "errorRateDelta" to data.errorRateDelta.escapeHtml(),
                        "p95Latency" to data.p95Latency.escapeHtml(),
                        "p95LatencyDelta" to data.p95LatencyDelta.escapeHtml(),
                        "throughput" to data.throughput.escapeHtml(),
                        "throughputDelta" to data.throughputDelta.escapeHtml(),
                        "usersAffected" to data.usersAffected.escapeHtml(),
                        "firstSeen" to data.firstSeen.escapeHtml(),
                        "lastSeen" to data.lastSeen.escapeHtml(),
                        "issueFunction" to data.issueFunction.escapeHtml(),
                        "issueLocation" to data.issueLocation.escapeHtml(),
                        "release" to data.release.escapeHtml(),
                        "deploySummary" to data.deploySummary.escapeHtml(),
                        "codeOwner" to data.codeOwner.escapeHtml()
                    )
                )
                .replaceSentinels(
                    mapOf(
                        "ISSUE_SPARKLINE_PLACEHOLDER" to sparklineHtml(data.eventSeries, data.spikeIndex),
                        "STACK_FRAMES_PLACEHOLDER" to stackFramesHtml(data),
                        "ISSUE_CONTEXT_TAGS_PLACEHOLDER" to contextTagsHtml(data.contextTags)
                    )
                )
        } else {
            // Fallback HTML
            """
            <!DOCTYPE html>
            <html>
            <body style="font-family: sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
                <h2>New ${data.issueLevel.uppercase()}: ${data.issueTitle}</h2>
                <p><strong>Project:</strong> ${data.projectName}</p>
                <p><strong>Environment:</strong> ${data.environment}</p>
                <p><strong>Error:</strong> ${data.issueMessage}</p>
                <p><strong>Location:</strong> ${data.issueCulprit}</p>
                <p><a href="${data.issueUrl}">View Full Details</a></p>
            </body>
            </html>
            """.trimIndent()
        }
    }

    private fun loadHostAlertTemplate(data: HostAlertData): String {
        val template = loadTemplate("host-alert-v1.html")
        return if (template != null) {
            template
                .replaceTokens(
                    commonEmailTokens(data.settingsUrl, data.unsubscribeUrl) + mapOf(
                        "hostName" to data.hostName.escapeHtml(),
                        "severity" to data.severity.escapeHtml(),
                        "metricName" to data.metricName.escapeHtml(),
                        "currentValue" to data.currentValue.escapeHtml(),
                        "threshold" to data.threshold.escapeHtml(),
                        "sustainedDuration" to data.sustainedDuration.escapeHtml(),
                        "vcpu" to data.vcpu.escapeHtml(),
                        "region" to data.region.escapeHtml(),
                        "environment" to data.environment.escapeHtml(),
                        "triggeredAt" to data.triggeredAt.escapeHtml(),
                        "condition" to data.condition.escapeHtml(),
                        "baselineSummary" to data.baselineSummary.escapeHtml(),
                        "cpuLabel" to data.cpuLabel.escapeHtml(),
                        "cpuPercent" to data.cpuPercent.escapeHtml(),
                        "memoryLabel" to data.memoryLabel.escapeHtml(),
                        "memoryPercent" to data.memoryPercent.escapeHtml(),
                        "diskLabel" to data.diskLabel.escapeHtml(),
                        "diskPercent" to data.diskPercent.escapeHtml(),
                        "load1m" to data.load1m.escapeHtml(),
                        "privateIp" to data.privateIp.escapeHtml(),
                        "instanceType" to data.instanceType.escapeHtml(),
                        "memoryTotal" to data.memoryTotal.escapeHtml(),
                        "os" to data.os.escapeHtml(),
                        "agentVersion" to data.agentVersion.escapeHtml(),
                        "uptime" to data.uptime.escapeHtml(),
                        "hostUrl" to data.hostUrl.escapeHtml()
                    )
                )
                .replaceSentinels(
                    mapOf(
                        "HOST_PROCESSES_PLACEHOLDER" to hostProcessesHtml(data.processes),
                        "HOST_HISTORY_PLACEHOLDER" to sparklineHtml(data.historySeries, null)
                    )
                )
        } else {
            """
            <!DOCTYPE html>
            <html><body>
            <h2>Host alert: ${data.hostName.escapeHtml()}</h2>
            <p>${data.metricName.escapeHtml()}: ${data.currentValue.escapeHtml()}</p>
            <p><a href="${data.hostUrl.escapeHtml()}">View host</a></p>
            </body></html>
            """.trimIndent()
        }
    }

    private fun loadMonitorAlertTemplate(data: MonitorAlertData): String {
        val template = loadTemplate("dashboard-alert-v1.html")
        return if (template != null) {
            template
                .replaceTokens(
                    commonEmailTokens(data.settingsUrl, data.unsubscribeUrl) + mapOf(
                        "dashboardName" to data.dashboardName.escapeHtml(),
                        "metricName" to data.metricName.escapeHtml(),
                        "currentValue" to data.currentValue.escapeHtml(),
                        "threshold" to data.threshold.escapeHtml(),
                        "condition" to data.condition.escapeHtml(),
                        "environment" to data.environment.escapeHtml(),
                        "triggeredAt" to data.triggeredAt.escapeHtml(),
                        "baselineSummary" to data.baselineSummary.escapeHtml(),
                        "widgetName" to data.widgetName.escapeHtml(),
                        "cadence" to data.cadence.escapeHtml(),
                        "scope" to data.scope.escapeHtml(),
                        "projectName" to data.projectName.escapeHtml(),
                        "breachSummary" to data.breachSummary.escapeHtml(),
                        "monitorUrl" to data.monitorUrl.escapeHtml()
                    )
                )
                .replaceSentinels(
                    mapOf(
                        "MONITOR_HISTORY_PLACEHOLDER" to sparklineHtml(data.historySeries, data.breachIndex)
                    )
                )
        } else {
            """
            <!DOCTYPE html>
            <html><body>
            <h2>Monitor alert: ${data.monitorName.escapeHtml()}</h2>
            <p>${data.message.escapeHtml()}</p>
            <p><a href="${data.monitorUrl.escapeHtml()}">View monitor</a></p>
            </body></html>
            """.trimIndent()
        }
    }

    private fun loadResolvedTemplate(data: ResolvedAlertData): String {
        val template = loadTemplate("host-recovered.html")
        return if (template != null) {
            template.replaceTokens(
                commonEmailTokens(data.settingsUrl, data.unsubscribeUrl) + mapOf(
                    "targetName" to data.targetName.escapeHtml(),
                    "metricName" to data.metricName.escapeHtml(),
                    "duration" to data.duration.escapeHtml(),
                    "currentValue" to data.currentValue.escapeHtml(),
                    "peakValue" to data.peakValue.escapeHtml(),
                    "monitorName" to data.monitorName.escapeHtml(),
                    "triggeredAt" to data.triggeredAt.escapeHtml(),
                    "recoveredAt" to data.recoveredAt.escapeHtml(),
                    "acknowledgedBy" to data.acknowledgedBy.escapeHtml(),
                    "environment" to data.environment.escapeHtml(),
                    "resolutionSummary" to data.resolutionSummary.escapeHtml(),
                    "alertUrl" to data.alertUrl.escapeHtml()
                )
            )
        } else {
            """
            <!DOCTYPE html>
            <html><body>
            <h2>Resolved: ${data.targetName.escapeHtml()}</h2>
            <p>${data.resolutionSummary.escapeHtml()}</p>
            <p><a href="${data.alertUrl.escapeHtml()}">View alert</a></p>
            </body></html>
            """.trimIndent()
        }
    }

    private fun loadWeeklySummaryTemplate(data: WeeklySummaryData): String {
        val template = loadTemplate("weekly-summary.html")

        return if (template != null) {
            var html =
                template.replaceTokens(
                    commonEmailTokens(data.settingsUrl, data.unsubscribeUrl) + mapOf(
                        "startDate" to data.startDate.escapeHtml(),
                        "endDate" to data.endDate.escapeHtml(),
                        "totalEvents" to data.totalEvents.escapeHtml(),
                        "newIssues" to data.newIssues.escapeHtml(),
                        "affectedUsers" to data.affectedUsers.escapeHtml(),
                        "dashboardUrl" to data.dashboardUrl.escapeHtml(),
                        "organizationName" to data.organizationName.escapeHtml()
                    )
                )

            html = html.replace("EVENTS_TREND_PLACEHOLDER", trendBadgeHtml(data.eventsTrend, positiveIsGood = true))
            html = html.replace("ISSUES_TREND_PLACEHOLDER", trendBadgeHtml(data.issuesTrend, positiveIsGood = false))
            html = html.replace("USERS_TREND_PLACEHOLDER", trendBadgeHtml(data.usersTrend, positiveIsGood = false))

            html = html.replace("ISSUES_PLACEHOLDER", weeklyIssuesHtml(data.topIssues))
            html = html.replace("PROJECTS_PLACEHOLDER", weeklyProjectsHtml(data.projects))

            html
        } else {
            // Fallback HTML
            """
            <!DOCTYPE html>
            <html>
            <body style="font-family: sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
                <h2>Your Weekly Summary</h2>
                <p>${data.startDate} – ${data.endDate}</p>
                <p>Total Events: ${data.totalEvents}</p>
                <p>New Issues: ${data.newIssues}</p>
                <p><a href="${data.dashboardUrl}">Open Dashboard</a></p>
            </body>
            </html>
            """.trimIndent()
        }
    }

    private fun loadBillingInsightTemplate(
        templateName: String,
        data: BillingInsightEmailData
    ): String {
        val templateResource = this::class.java.classLoader.getResourceAsStream("email-templates/$templateName")
        val year = java.time.Year.now().value.toString()
        return if (templateResource != null) {
            var html =
                templateResource
                    .bufferedReader()
                    .use { it.readText() }
                    .replace("{{ organizationName }}", data.organizationName.escapeHtml())
                    .replace("{{ plan }}", data.plan.escapeHtml())
                    .replace("{{ periodStart }}", data.periodStart.escapeHtml())
                    .replace("{{ periodEnd }}", data.periodEnd.escapeHtml())
                    .replace("{{ headline }}", data.headline.escapeHtml())
                    .replace("{{ summary }}", data.summary.escapeHtml())
                    .replace("{{ dashboardUrl }}", data.dashboardUrl.escapeHtml())
                    .replace(SETTINGS_URL_PLACEHOLDER, data.settingsUrl.escapeHtml())
                    .replace("{{ unsubscribeUrl }}", data.unsubscribeUrl.escapeHtml())
                    .replace("{{ totalOverage }}", data.totalOverage.escapeHtml())
                    .replace("{{ topDriver }}", data.topDriver.escapeHtml())
                    .replace("{{ topDriverUsage }}", data.topDriverUsage.escapeHtml())
                    .replace(YEAR_PLACEHOLDER, year)

            html = html.replace("BILLING_ROWS_PLACEHOLDER", billingInsightRowsHtml(data.rows))
            html
        } else {
            """
            <!DOCTYPE html>
            <html>
            <body style="font-family: sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
                <h2>${data.headline.escapeHtml()}</h2>
                <p>${data.summary.escapeHtml()}</p>
                <p><strong>Plan:</strong> ${data.plan.escapeHtml()}</p>
                <p><strong>Period:</strong> ${data.periodStart.escapeHtml()} to ${data.periodEnd.escapeHtml()}</p>
                ${billingInsightRowsHtml(data.rows)}
                <p><a href="${data.dashboardUrl.escapeHtml()}">Open Usage Insights</a></p>
            </body>
            </html>
            """.trimIndent()
        }
    }

    private fun billingInsightRowsHtml(rows: List<BillingInsightRow>): String {
        if (rows.isEmpty()) {
            return """
            <p style="margin:0;font:400 13px/1.5 $EMAIL_SANS;color:$EMAIL_TEXT_MUTED;">
              No billable usage yet.
            </p>
            """.trimIndent()
        }
        return rows.joinToString("\n") { row ->
            val progressPercent = billingProgressPercent(row.percent)
            val remainingPercent = FULL_PROGRESS_PERCENT - progressPercent
            val progressColor = billingProgressColor(row.status)
            """
            <div style="border-top:1px solid $EMAIL_BORDER_MUTED;padding-top:13px;margin-top:13px;">
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>
                <td style="font:600 13px/1.4 $EMAIL_SANS;color:$EMAIL_TEXT_STRONG;">
                  ${row.label.escapeHtml()}
                </td>
                <td align="right" style="font:500 12px/1.4 $EMAIL_MONO;color:$EMAIL_TEXT;">
                  ${row.used.escapeHtml()} / ${row.limit.escapeHtml()}
                  <span style="color:$progressColor;font-weight:600;">${row.percent.escapeHtml()}</span>
                </td>
              </tr></table>
              <div style="height:8px;"></div>
              <table
                role="presentation"
                width="100%"
                cellpadding="0"
                cellspacing="0"
                aria-label="${row.percent.escapeHtml()} used"
                style="border-radius:999px;overflow:hidden;background:$EMAIL_SUBTLE;"
              >
                <tr>
                  <td width="$progressPercent%" style="height:7px;background:$progressColor;line-height:7px;font-size:0;">&nbsp;</td>
                  <td width="$remainingPercent%" style="height:7px;background:$EMAIL_SUBTLE;line-height:7px;font-size:0;">&nbsp;</td>
                </tr>
              </table>
              <div style="margin-top:7px;font:500 11px/1.3 $EMAIL_SANS;color:$EMAIL_TEXT_MUTED;">
                ${row.status.escapeHtml()}
              </div>
            </div>
            """.trimIndent()
        }
    }

    private fun billingProgressPercent(percent: String): Int {
        if (percent == "Unlimited") return FULL_PROGRESS_PERCENT
        val numericPercent = percent.removeSuffix("%").toDoubleOrNull() ?: return 0
        if (numericPercent <= 0.0) return 0
        return numericPercent.roundToInt().coerceIn(MIN_VISIBLE_PROGRESS_PERCENT, FULL_PROGRESS_PERCENT)
    }

    private fun billingProgressColor(status: String): String {
        return when (status) {
            "Over limit" -> EMAIL_DANGER
            "Critical" -> EMAIL_WARNING
            "Approaching" -> EMAIL_LINK
            else -> EMAIL_ACCENT
        }
    }

    private fun formatTrendText(trend: Int?): String {
        if (trend == null) return "\u2014"
        return "${if (trend > 0) "+" else ""}$trend%"
    }

    private fun trendBadgeHtml(trend: Int?, positiveIsGood: Boolean): String {
        if (trend == null) {
            return "&mdash;"
        }
        return when {
            trend > 0 && positiveIsGood ->
                "&uarr; $trend%"
            trend > 0 ->
                "&uarr; $trend%"
            trend < 0 && positiveIsGood ->
                "&darr; ${-trend}%"
            trend < 0 ->
                "&darr; ${-trend}%"
            else ->
                "&rarr; 0%"
        }
    }

    private fun weeklyIssuesHtml(issues: List<TopIssue>): String {
        if (issues.isEmpty()) {
            return """
            <p style="margin:0;font:400 13px/1.5 $EMAIL_SANS;color:$EMAIL_TEXT_MUTED;">
              No new issues this week.
            </p>
            """.trimIndent()
        }
        val rows =
            issues.joinToString("\n") { issue ->
                """
                <tr>
                  <td style="padding:11px 0;border-top:1px solid $EMAIL_BORDER_MUTED;width:14px;vertical-align:top;">
                    <div style="padding-top:4px;">
                      <span style="display:inline-block;width:8px;height:8px;border-radius:999px;background:$EMAIL_DANGER;vertical-align:middle;"></span>
                    </div>
                  </td>
                  <td style="padding:11px 0 11px 10px;border-top:1px solid $EMAIL_BORDER_MUTED;">
                    <div style="font:600 13px/1.4 $EMAIL_SANS;color:$EMAIL_TEXT_STRONG;">
                      ${issue.title.escapeHtml()}
                    </div>
                    <div style="margin-top:3px;font:500 11px/1 $EMAIL_MONO;color:$EMAIL_TEXT_MUTED;">
                      ${issue.project.escapeHtml()} · ${issue.culprit.escapeHtml()}
                    </div>
                  </td>
                  <td align="right" style="padding:11px 0;border-top:1px solid $EMAIL_BORDER_MUTED;vertical-align:top;">
                    <span style="font:600 13px/1.4 $EMAIL_MONO;color:$EMAIL_TEXT;">${issue.count.escapeHtml()}</span>
                    <div style="font:500 10px/1.3 $EMAIL_SANS;color:$EMAIL_TEXT_MUTED;text-transform:uppercase;letter-spacing:0.05em;">
                      events
                    </div>
                  </td>
                </tr>
                """.trimIndent()
            }
        return """
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
        $rows
        </table>
        """.trimIndent()
    }

    private fun weeklyProjectsHtml(projects: List<ProjectSummary>): String {
        if (projects.isEmpty()) {
            return """
            <p style="margin:0;font:400 13px/1.5 $EMAIL_SANS;color:$EMAIL_TEXT_MUTED;">
              No service activity in this period.
            </p>
            """.trimIndent()
        }
        val rows =
            projects.joinToString("\n") { project ->
                """
                <tr>
                  <td style="padding:10px 0;border-top:1px solid $EMAIL_BORDER_MUTED;font:500 12px/1.4 $EMAIL_MONO;color:$EMAIL_TEXT;white-space:nowrap;">
                    ${project.name.escapeHtml()}
                  </td>
                  <td style="padding:10px 12px;border-top:1px solid $EMAIL_BORDER_MUTED;font:500 12px/1.4 $EMAIL_MONO;color:$EMAIL_TEXT_MUTED;">
                    ${project.issues.escapeHtml()} issues
                  </td>
                  <td align="right" style="padding:10px 0;border-top:1px solid $EMAIL_BORDER_MUTED;font:500 12px/1.4 $EMAIL_MONO;color:$EMAIL_TEXT_MUTED;">
                    ${project.events.escapeHtml()} · ${project.crashFree.escapeHtml()} crash-free
                  </td>
                </tr>
                """.trimIndent()
            }
        return """
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
          <tr>
            <td style="padding:0 0 9px;font:600 10px/1 $EMAIL_SANS;color:$EMAIL_TEXT_MUTED;letter-spacing:0.05em;text-transform:uppercase;">
              Service
            </td>
            <td style="padding:0 12px 9px;font:600 10px/1 $EMAIL_SANS;color:$EMAIL_TEXT_MUTED;letter-spacing:0.05em;text-transform:uppercase;">
              Issues
            </td>
            <td align="right" style="padding:0 0 9px;font:600 10px/1 $EMAIL_SANS;color:$EMAIL_TEXT_MUTED;letter-spacing:0.05em;text-transform:uppercase;">
              Events
            </td>
          </tr>
          $rows
        </table>
        """.trimIndent()
    }

    private fun hostProcessesHtml(processes: List<HostProcessRow>): String {
        val rows = processes.ifEmpty {
            listOf(HostProcessRow("No process data", "—", "—"))
        }.joinToString("\n") { process ->
            """
            <tr>
              <td style="padding:9px 0;border-top:1px solid $EMAIL_BORDER_MUTED;font:500 12px/1.4 $EMAIL_MONO;color:$EMAIL_TEXT;">
                ${process.name.escapeHtml()}
              </td>
              <td align="right" style="padding:9px 0;border-top:1px solid $EMAIL_BORDER_MUTED;font:600 12px/1.4 $EMAIL_MONO;color:$EMAIL_TEXT;">
                ${process.cpu.escapeHtml()}
              </td>
              <td align="right" style="padding:9px 0;border-top:1px solid $EMAIL_BORDER_MUTED;font:500 12px/1.4 $EMAIL_MONO;color:$EMAIL_TEXT_MUTED;">
                ${process.memory.escapeHtml()}
              </td>
            </tr>
            """.trimIndent()
        }
        return """
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
          <tr>
            <td style="padding:0 0 8px;font:600 10px/1 $EMAIL_SANS;color:$EMAIL_TEXT_MUTED;letter-spacing:0.05em;text-transform:uppercase;">
              Process
            </td>
            <td align="right" style="padding:0 0 8px;font:600 10px/1 $EMAIL_SANS;color:$EMAIL_TEXT_MUTED;letter-spacing:0.05em;text-transform:uppercase;">
              CPU
            </td>
            <td align="right" style="padding:0 0 8px;font:600 10px/1 $EMAIL_SANS;color:$EMAIL_TEXT_MUTED;letter-spacing:0.05em;text-transform:uppercase;">
              Mem
            </td>
          </tr>
          $rows
        </table>
        """.trimIndent()
    }

    private fun sparklineHtml(
        values: List<Int>,
        spikeIndex: Int?
    ): String {
        val normalized = values.ifEmpty { listOf(1, 1, 1, 1, 1, 1, 1, 1) }
        val max = normalized.maxOrNull()?.coerceAtLeast(1) ?: 1
        val bars =
            normalized.mapIndexed { index, value ->
                val height = ((value.toDouble() / max) * SPARKLINE_HEIGHT).roundToInt().coerceAtLeast(2)
                val color = if (spikeIndex != null && index >= spikeIndex) EMAIL_DANGER else EMAIL_ACCENT
                """
                <td style="vertical-align:bottom;padding:0 1px;">
                  <div style="width:9px;height:${height}px;background:$color;border-radius:2px 2px 0 0;"></div>
                </td>
                """.trimIndent()
            }.joinToString("")
        return """
        <table role="presentation" cellpadding="0" cellspacing="0" style="height:${SPARKLINE_HEIGHT}px;">
          <tr style="vertical-align:bottom;">$bars</tr>
        </table>
        """.trimIndent()
    }

    private fun contextTagsHtml(tags: List<ContextTag>): String {
        val effectiveTags = tags.ifEmpty { listOf(ContextTag("context", "unavailable")) }
        return effectiveTags.joinToString("") { tag ->
            """
            <span style="display:inline-block;margin:0 6px 7px 0;padding:3px 8px;border-radius:6px;background:$EMAIL_SUBTLE;border:1px solid $EMAIL_BORDER;font:500 11px/1.5 $EMAIL_MONO;color:$EMAIL_TEXT;white-space:nowrap;">
              <span style="color:$EMAIL_TEXT_MUTED;">${tag.key.escapeHtml()}</span> ${tag.value.escapeHtml()}
            </span>
            """.trimIndent()
        }
    }

    private fun stackFramesHtml(data: ErrorAlertData): String {
        val frames = data.stackFrames.ifEmpty {
            data.stackTrace
                .lines()
                .filter { it.isNotBlank() }
                .take(EMAIL_STACK_FRAMES_COUNT)
                .mapIndexed { index, frame -> StackFrame(frame.trim(), inApp = index == 0, heading = index == 0) }
                .ifEmpty { listOf(StackFrame("No stack trace available", heading = true)) }
        }
        val frameRows =
            frames.joinToString("") { frame ->
                when {
                    frame.heading ->
                        """<div style="font:500 12px/1.7 $EMAIL_MONO;color:#f3b3b5;">${frame.text.escapeHtml()}</div>"""
                    frame.inApp ->
                        """
                        <div style="font:500 12px/1.7 $EMAIL_MONO;color:#d7e1ec;background:rgba(56,189,248,0.12);border-left:2px solid $EMAIL_ACCENT;padding:1px 0 1px 10px;margin:2px 0 2px -2px;">
                          ${frame.text.escapeHtml()} <span style="color:$EMAIL_ACCENT;font-size:10px;">in&nbsp;app</span>
                        </div>
                        """.trimIndent()
                    else ->
                        """
                        <div style="font:500 12px/1.7 $EMAIL_MONO;color:#8a99a9;padding-left:10px;">
                          ${frame.text.escapeHtml()}
                        </div>
                        """.trimIndent()
                }
            }
        return """
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-radius:8px;overflow:hidden;background:#0f1620;border:1px solid #21303f;">
          <tr>
            <td style="padding:9px 14px;background:#16202c;border-bottom:1px solid #21303f;">
              <span style="font:500 11px/1 $EMAIL_MONO;color:#8a99a9;letter-spacing:0.04em;">
                ${data.issueLocation.escapeHtml()} · stack trace
              </span>
            </td>
          </tr>
          <tr><td style="padding:13px 14px;">$frameRows</td></tr>
        </table>
        """.trimIndent()
    }

    /**
     * Notifies the internal sales inbox of an Enterprise inquiry submitted from the public
     * pricing page. The message Reply-To is the prospect's address so the team can respond directly.
     */
    fun sendEnterpriseSalesInquiry(
        name: String,
        email: String,
        company: String,
        message: String
    ) {
        val safeName = name.escapeHtml()
        val safeEmail = email.escapeHtml()
        val safeCompany = company.escapeHtml()
        val safeMessage = message.escapeHtml()
        val subject = "Enterprise sales inquiry: $company"
        val htmlBody =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
            </head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                <div style="background-color: #f8f9fa; padding: 30px; border-radius: 8px;">
                    <h1 style="color: #1a1a1a; margin-bottom: 20px;">New Enterprise sales inquiry</h1>
                    <p><strong>Name:</strong> $safeName</p>
                    <p><strong>Work email:</strong> <a href="mailto:$safeEmail">$safeEmail</a></p>
                    <p><strong>Company:</strong> $safeCompany</p>
                    <p style="margin-top: 20px;"><strong>Message:</strong></p>
                    <p style="white-space: pre-wrap; background-color: #ffffff; border: 1px solid #e5e5e5; border-radius: 6px; padding: 16px;">$safeMessage</p>
                    <hr style="border: none; border-top: 1px solid #ddd; margin: 30px 0;">
                    <p style="color: #999; font-size: 12px;">Reply directly to this email to reach $safeName.</p>
                </div>
            </body>
            </html>
            """.trimIndent()

        val textBody =
            """
            New Enterprise sales inquiry

            Name: $name
            Work email: $email
            Company: $company

            Message:
            $message

            ---
            Reply directly to this email to reach the prospect.
            """.trimIndent()

        sendEmail(salesInbox, subject, htmlBody, textBody, "enterprise_sales_inquiry", replyTo = email)
    }

    fun sendAccountDeletionConfirmation(email: String) {
        val subject = "Your Moneat account has been deactivated"
        val htmlBody =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
            </head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                <div style="background-color: #f8f9fa; padding: 30px; border-radius: 8px;">
                    <h1 style="color: #1a1a1a; margin-bottom: 20px;">Account Deactivated</h1>
                    <p>Your Moneat account has been successfully deactivated.</p>
                    <p>Your profile and membership associations have been removed from active use. Your account record is retained in a deactivated state for potential recovery within 30 days. After that period, remaining records may be purged per our retention policy.</p>
                    <p>If you deleted your account by mistake or have any questions, please contact us at <a href="mailto:support@moneat.io">support@moneat.io</a> within 30 days for potential account recovery.</p>
                    <hr style="border: none; border-top: 1px solid #ddd; margin: 30px 0;">
                    <p style="color: #999; font-size: 12px;">Thank you for using Moneat.</p>
                </div>
            </body>
            </html>
            """.trimIndent()

        val textBody =
            """
            Account Deactivated
            
            Your Moneat account has been successfully deactivated.
            
            Your profile and membership associations have been removed from active use. Your account record is retained in a deactivated state for potential recovery within 30 days. After that period, remaining records may be purged per our retention policy.
            
            If you deleted your account by mistake or have any questions, please contact us at support@moneat.io within 30 days for potential account recovery.
            
            Thank you for using Moneat.
            """.trimIndent()

        sendEmail(email, subject, htmlBody, textBody, "account_deletion")
    }

    fun sendOrganizationDeletionNotification(
        email: String,
        organizationName: String
    ) {
        val subject = "Organization deleted: $organizationName"
        val htmlBody =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
            </head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                <div style="background-color: #fef2f2; border-left: 4px solid $DANGER_COLOR; padding: 30px; border-radius: 8px;">
                    <h1 style="color: $DANGER_COLOR; margin-bottom: 20px;">Organization Deleted</h1>
                    <p>The organization <strong>$organizationName</strong> has been deleted by its owner.</p>
                    <p>Deletion of all projects, events, LLM data, analytics, and associated data has been initiated. Data removal from storage may complete within a short period.</p>
                    <p>Your Moneat account is still active. You can <a href="$frontendUrl/organizations/new" style="color: #2563eb;">create a new organization</a> or join another organization if you have pending invitations.</p>
                    <hr style="border: none; border-top: 1px solid #ddd; margin: 30px 0;">
                    <p style="color: #999; font-size: 12px;">Moneat</p>
                </div>
            </body>
            </html>
            """.trimIndent()

        val textBody =
            """
            Organization Deleted
            
            The organization $organizationName has been deleted by its owner.
            
            Deletion of all projects, events, LLM data, analytics, and associated data has been initiated. Data removal from storage may complete within a short period.
            
            Your Moneat account is still active. You can create a new organization or join another organization if you have pending invitations.
            
            Visit: $frontendUrl/organizations/new
            
            ---
            Moneat
            """.trimIndent()

        sendEmail(email, subject, htmlBody, textBody, "organization_deletion")
    }

    companion object {
        private const val EMAIL_OPERATION_TAG = "email.operation"
        private const val EMAIL_TO_TAG = "email.to"
        private const val EMAIL_SUBJECT_TAG = "email.subject"
        private const val EMAIL_TYPE_TAG = "email.type"
    }
}
