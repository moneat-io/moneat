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
import java.util.*
import kotlin.time.Clock
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private const val BADGE_STYLE = "margin:0;font-size:0.75rem;font-weight:600;" +
    "display:inline-block;border-radius:4px;padding:1px 8px;"
private const val BADGE_POSITIVE =
    "background-color:#f0fdf4;border:1px solid #bbf7d0;color:#16a34a;"
private const val BADGE_NEGATIVE =
    "background-color:#fef2f2;border:1px solid #fecaca;color:#dc2626;"
private const val BADGE_NEUTRAL = "font-weight:500;background-color:#f5f5f5;" +
    "border:1px solid #e5e5e5;color:#737373;"
private const val TOP_ISSUES_COUNT = 5

class EmailService {
    private val config = ApplicationConfig("application.conf")
    private val fromEmail = config.property("email.from").getString()
    private val frontendUrl = config.property("email.frontendUrl").getString()

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
        inviterName: String,
        orgName: String,
        role: String,
        token: String
    ) {
        val inviteUrl = "$frontendUrl/accept-invite?token=$token"

        val subject = "You've been invited to join $orgName on Moneat"
        val htmlBody = loadInvitationTemplate(inviterName, orgName, role, inviteUrl)
        val textBody =
            """
            You've been invited to join $orgName on Moneat
            
            $inviterName has invited you to join their team as a ${role.lowercase()}.
            
            Click the link below to accept the invitation:
            
            $inviteUrl
            
            This invitation will expire in 7 days.
            
            If you don't have an account yet, you'll be able to create one when you accept the invitation.
            
            Best regards,
            The Moneat Team
            """.trimIndent()

        sendEmail(toEmail, subject, htmlBody, textBody, "org_invitation")
    }

    fun sendEmail(
        to: String,
        subject: String,
        htmlBody: String,
        textBody: String,
        emailType: String = "other"
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

    private fun loadVerificationTemplate(
        userName: String,
        verificationUrl: String
    ): String {
        // Try to load the built email template from classpath
        val templateResource = this::class.java.classLoader.getResourceAsStream("email-templates/verify-email.html")

        return if (templateResource != null) {
            templateResource
                .bufferedReader()
                .use { it.readText() }
                .replace("{{ userName }}", userName)
                .replace("{{ verificationUrl }}", verificationUrl)
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
        // Try to load the built email template from classpath
        val templateResource = this::class.java.classLoader.getResourceAsStream("email-templates/reset-password.html")

        return if (templateResource != null) {
            templateResource
                .bufferedReader()
                .use { it.readText() }
                .replace("{{ userName }}", userName)
                .replace("{{ resetUrl }}", resetUrl)
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
        inviteUrl: String
    ): String {
        val templateResource = this::class.java.classLoader.getResourceAsStream("email-templates/org-invitation.html")

        return if (templateResource != null) {
            templateResource
                .bufferedReader()
                .use { it.readText() }
                .replace("{{ inviterName }}", inviterName)
                .replace("{{ orgName }}", orgName)
                .replace("{{ role }}", role)
                .replace("{{ inviteUrl }}", inviteUrl)
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
        val unsubscribeUrl: String
    )

    data class WeeklySummaryData(
        val startDate: String,
        val endDate: String,
        val totalEvents: String,
        val eventsTrend: Int,
        val newIssues: String,
        val issuesTrend: Int,
        val affectedUsers: String,
        val usersTrend: Int,
        val topIssues: List<TopIssue>,
        val projects: List<ProjectSummary>,
        val dashboardUrl: String,
        val settingsUrl: String,
        val unsubscribeUrl: String
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
        val textBody =
            """
            Your Weekly Summary (${data.startDate} – ${data.endDate})
            
            KEY STATS:
            - Total Events: ${data.totalEvents} (${if (data.eventsTrend > 0) "+" else ""}${data.eventsTrend}%)
            - New Issues: ${data.newIssues} (${if (data.issuesTrend > 0) "+" else ""}${data.issuesTrend}%)
            - Affected Users: ${data.affectedUsers} (${if (data.usersTrend > 0) "+" else ""}${data.usersTrend}%)
            
            TOP ISSUES:
            $topIssuesList
            
            Open Dashboard: ${data.dashboardUrl}
            Manage preferences: ${data.settingsUrl}
            """.trimIndent()

        sendEmail(to, subject, htmlBody, textBody, "weekly_summary")
    }

    fun sendHostDownEmail(
        to: String,
        hostName: String,
        lastSeenText: String,
        hostUrl: String
    ) {
        val safeHostName = hostName.escapeHtml()
        val safeLastSeenText = lastSeenText.escapeHtml()
        val safeHostUrl = hostUrl.escapeHtml()
        val subject = "🔴 Host Down: $hostName"
        val htmlBody =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
            </head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                <div style="background-color: #fef2f2; border-left: 4px solid #dc2626; padding: 30px; border-radius: 8px;">
                    <h1 style="color: #dc2626; margin-bottom: 20px;">🔴 Host Down</h1>
                    <p><strong>Host:</strong> $safeHostName</p>
                    <p><strong>Status:</strong> $safeLastSeenText</p>
                    <p>The monitoring agent has stopped reporting metrics. Please check if the host is online and the agent is running.</p>
                    <div style="margin: 30px 0;">
                        <a href="$safeHostUrl" style="display: inline-block; background-color: #dc2626; color: white; padding: 12px 30px; text-decoration: none; border-radius: 6px; font-weight: 500;">View Host</a>
                    </div>
                    <hr style="border: none; border-top: 1px solid #ddd; margin: 30px 0;">
                    <p style="color: #999; font-size: 12px;">Moneat Server Monitoring</p>
                </div>
            </body>
            </html>
            """.trimIndent()

        val textBody =
            """
            🔴 Host Down
            
            Host: $hostName
            Status: $lastSeenText
            
            The monitoring agent has stopped reporting metrics. Please check if the host is online and the agent is running.
            
            View host: $hostUrl
            
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
        val emoji = if (isDown) "🔴" else "✅"
        val subject = "$emoji Uptime Monitor ${if (isDown) "Down" else "Up"}: $monitorName"
        val bgColor = if (isDown) "#fef2f2" else "#f0fdf4"
        val borderColor = if (isDown) "#dc2626" else "#16a34a"
        val headingColor = if (isDown) "#dc2626" else "#16a34a"
        val buttonColor = if (isDown) "#dc2626" else "#16a34a"

        val htmlBody =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
            </head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                <div style="background-color: $bgColor; border-left: 4px solid $borderColor; padding: 30px; border-radius: 8px;">
                    <h1 style="color: $headingColor; margin-bottom: 20px;">$emoji Uptime Monitor ${if (isDown) "Down" else "Recovered"}</h1>
                    <p><strong>Monitor:</strong> $monitorName</p>
                    <p><strong>Status:</strong> ${status.uppercase()}</p>
                    ${if (message.isNotBlank()) "<p><strong>Message:</strong> $message</p>" else ""}
                    <div style="margin: 30px 0;">
                        <a href="$monitorUrl" style="display: inline-block; background-color: $buttonColor; color: white; padding: 12px 30px; text-decoration: none; border-radius: 6px; font-weight: 500;">View Monitor</a>
                    </div>
                    <hr style="border: none; border-top: 1px solid #ddd; margin: 30px 0;">
                    <p style="color: #999; font-size: 12px;">Moneat Uptime Monitoring</p>
                </div>
            </body>
            </html>
            """.trimIndent()
        val textBody =
            """
            Uptime Monitor ${if (isDown) "Down" else "Recovered"}: $monitorName
            
            Status: ${status.uppercase()}
            ${if (message.isNotBlank()) "Message: $message" else ""}
            
            View monitor: $monitorUrl
            """.trimIndent()

        sendEmail(to, subject, htmlBody, textBody, "uptime_alert")
    }

    fun sendHostUpEmail(
        to: String,
        hostName: String,
        hostUrl: String
    ) {
        val safeHostName = hostName.escapeHtml()
        val safeHostUrl = hostUrl.escapeHtml()
        val subject = "✅ Host Recovered: $hostName"
        val htmlBody =
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
            </head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                <div style="background-color: #f0fdf4; border-left: 4px solid #16a34a; padding: 30px; border-radius: 8px;">
                    <h1 style="color: #16a34a; margin-bottom: 20px;">✅ Host Recovered</h1>
                    <p><strong>Host:</strong> $safeHostName</p>
                    <p>The host is now reporting metrics again.</p>
                    <div style="margin: 30px 0;">
                        <a href="$safeHostUrl" style="display: inline-block; background-color: #16a34a; color: white; padding: 12px 30px; text-decoration: none; border-radius: 6px; font-weight: 500;">View Host</a>
                    </div>
                    <hr style="border: none; border-top: 1px solid #ddd; margin: 30px 0;">
                    <p style="color: #999; font-size: 12px;">Moneat Server Monitoring</p>
                </div>
            </body>
            </html>
            """.trimIndent()

        val textBody =
            """
            ✅ Host Recovered
            
            Host: $hostName
            
            The host is now reporting metrics again.
            
            View host: $hostUrl
            
            ---
            Moneat Server Monitoring
            """.trimIndent()

        sendEmail(to, subject, htmlBody, textBody, "host_up")
    }

    private fun loadErrorAlertTemplate(data: ErrorAlertData): String {
        val templateResource = this::class.java.classLoader.getResourceAsStream("email-templates/error-alert.html")
        val year =
            java.time.Year
                .now()
                .value
                .toString()

        return if (templateResource != null) {
            templateResource
                .bufferedReader()
                .use { it.readText() }
                .replace("{{ issueTitle }}", data.issueTitle)
                .replace("{{ issueLevel }}", data.issueLevel)
                .replace("{{ issueCulprit }}", data.issueCulprit)
                .replace("{{ issueMessage }}", data.issueMessage)
                .replace("{{ issueCount }}", data.issueCount)
                .replace("{{ issueUrl }}", data.issueUrl)
                .replace("{{ projectName }}", data.projectName)
                .replace("{{ environment }}", data.environment)
                .replace("{{ timestamp }}", data.timestamp)
                .replace("{{ stackTrace }}", data.stackTrace)
                .replace("{{ settingsUrl }}", data.settingsUrl)
                .replace("{{ unsubscribeUrl }}", data.unsubscribeUrl)
                .replace("{{ year }}", year)
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

    private fun loadWeeklySummaryTemplate(data: WeeklySummaryData): String {
        val templateResource = this::class.java.classLoader.getResourceAsStream("email-templates/weekly-summary.html")
        val year =
            java.time.Year
                .now()
                .value
                .toString()

        return if (templateResource != null) {
            var html =
                templateResource
                    .bufferedReader()
                    .use { it.readText() }
                    .replace("{{ startDate }}", data.startDate)
                    .replace("{{ endDate }}", data.endDate)
                    .replace("{{ totalEvents }}", data.totalEvents)
                    .replace("{{ newIssues }}", data.newIssues)
                    .replace("{{ affectedUsers }}", data.affectedUsers)
                    .replace("{{ dashboardUrl }}", data.dashboardUrl)
                    .replace("{{ settingsUrl }}", data.settingsUrl)
                    .replace("{{ unsubscribeUrl }}", data.unsubscribeUrl)
                    .replace("{{ year }}", year)

            html = html.replace("EVENTS_TREND_PLACEHOLDER", trendBadgeHtml(data.eventsTrend, positiveIsGood = true))
            html = html.replace("ISSUES_TREND_PLACEHOLDER", trendBadgeHtml(data.issuesTrend, positiveIsGood = false))
            html = html.replace("USERS_TREND_PLACEHOLDER", trendBadgeHtml(data.usersTrend, positiveIsGood = false))

            val issuesHtml =
                data.topIssues.joinToString("\n") { issue ->
                    """
                <tr>
                  <td style="padding:0.75rem 1rem;border-bottom:1px solid #f5f5f5;">
                    <table width="100%" cellpadding="0" cellspacing="0" role="presentation">
                      <tr>
                        <td style="vertical-align:top;">
                          <p style="margin:0;font-size:0.875rem;font-weight:500;color:#0a0a0a;">${issue.title.escapeHtml()}</p>
                          <table cellpadding="0" cellspacing="0" role="presentation" style="margin-top:0.25rem;">
                            <tr>
                              <td style="padding-right:0.5rem;">
                                <p style="margin:0;font-size:0.75rem;color:#737373;font-family:ui-monospace,monospace;">${issue.culprit.escapeHtml()}</p>
                              </td>
                              <td>
                                <p style="margin:0;font-size:0.75rem;font-weight:500;display:inline-block;background-color:#f5f5f5;border:1px solid #e5e5e5;border-radius:4px;padding:0 6px;color:#525252;">${issue.project.escapeHtml()}</p>
                              </td>
                            </tr>
                          </table>
                        </td>
                        <td style="text-align:right;vertical-align:middle;white-space:nowrap;">
                          <p style="margin:0;font-size:0.875rem;font-weight:700;color:#0a0a0a;">${issue.count.escapeHtml()}</p>
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
                    """.trimIndent()
                }
            html = html.replace("ISSUES_PLACEHOLDER", issuesHtml)

            val projectsHtml =
                data.projects.joinToString("\n") { project ->
                    """
                <table width="100%" cellpadding="0" cellspacing="0" role="presentation" style="border-radius:8px;border:1px solid #e5e5e5;overflow:hidden;margin-bottom:12px;">
                  <tr>
                    <td style="height:4px;background:linear-gradient(90deg,#3b82f6 0%,#8b5cf6 100%);line-height:1px;font-size:1px;">&nbsp;</td>
                  </tr>
                  <tr>
                    <td style="padding:1rem;">
                      <p style="margin:0;margin-bottom:0.75rem;font-size:0.875rem;font-weight:600;color:#0a0a0a;">${project.name.escapeHtml()}</p>
                      <table width="100%" cellpadding="0" cellspacing="0" role="presentation">
                        <tr>
                          <td style="width:33%;">
                            <p style="margin:0;margin-bottom:0.25rem;font-size:0.75rem;font-weight:500;color:#737373;">Events</p>
                            <p style="margin:0;font-size:1.125rem;font-weight:700;color:#0a0a0a;">${project.events.escapeHtml()}</p>
                          </td>
                          <td style="width:33%;">
                            <p style="margin:0;margin-bottom:0.25rem;font-size:0.75rem;font-weight:500;color:#737373;">Issues</p>
                            <p style="margin:0;font-size:1.125rem;font-weight:700;color:#0a0a0a;">${project.issues.escapeHtml()}</p>
                          </td>
                          <td style="width:33%;">
                            <p style="margin:0;margin-bottom:0.25rem;font-size:0.75rem;font-weight:500;color:#737373;">Crash-Free</p>
                            <p style="margin:0;font-size:1.125rem;font-weight:700;color:#16a34a;">${project.crashFree.escapeHtml()}</p>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                </table>
                    """.trimIndent()
                }
            html = html.replace("PROJECTS_PLACEHOLDER", projectsHtml)

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

    private fun trendBadgeHtml(trend: Int, positiveIsGood: Boolean): String {
        return when {
            trend > 0 && positiveIsGood ->
                """<p style="$BADGE_STYLE $BADGE_POSITIVE">&uarr; $trend%</p>"""
            trend > 0 ->
                """<p style="$BADGE_STYLE $BADGE_NEGATIVE">&uarr; $trend%</p>"""
            trend < 0 && positiveIsGood ->
                """<p style="$BADGE_STYLE $BADGE_NEGATIVE">&darr; ${-trend}%</p>"""
            trend < 0 ->
                """<p style="$BADGE_STYLE $BADGE_POSITIVE">&darr; ${-trend}%</p>"""
            else ->
                """<p style="$BADGE_STYLE $BADGE_NEUTRAL">&rarr; 0%</p>"""
        }
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
                <div style="background-color: #fef2f2; border-left: 4px solid #dc2626; padding: 30px; border-radius: 8px;">
                    <h1 style="color: #dc2626; margin-bottom: 20px;">Organization Deleted</h1>
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
