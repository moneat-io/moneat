package com.moneat.services

import com.moneat.models.EmailsSent
import com.moneat.models.Memberships
import com.moneat.models.Users
import com.moneat.utils.SentryUtils
import io.ktor.server.config.*
import io.sentry.Sentry
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.datetime.Clock
import mu.KotlinLogging
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.*

private val logger = KotlinLogging.logger {}

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
            val props = Properties().apply {
                put("mail.smtp.host", smtpHost)
                put("mail.smtp.port", smtpPort.toString())
                put("mail.smtp.auth", smtpAuth.toString())
                put("mail.smtp.starttls.enable", smtpStartTls.toString())
                put("mail.smtp.ssl.protocols", "TLSv1.2")
            }
            
            Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(smtpUsername, smtpPassword)
                }
            })
        }
    }
    
    fun sendVerificationEmail(email: String, token: String, userName: String?) {
        val verificationUrl = "$frontendUrl/verify-email?token=$token"
        val displayName = userName ?: email.substringBefore("@")
        
        val subject = "Verify your email address"
        val htmlBody = loadVerificationTemplate(displayName, verificationUrl)
        val textBody = """
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
    
    fun sendPasswordResetEmail(email: String, token: String, userName: String?) {
        val resetUrl = "$frontendUrl/reset-password?token=$token"
        val displayName = userName ?: email.substringBefore("@")
        
        val subject = "Reset your password"
        val htmlBody = loadPasswordResetTemplate(displayName, resetUrl)
        val textBody = """
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
    
    fun sendEmail(to: String, subject: String, htmlBody: String, textBody: String, emailType: String = "other") {
        SentryUtils.breadcrumb("email", "Sending email", mapOf(
            "to" to to,
            "subject" to subject,
            "type" to emailType
        ))
        
        val mailSession = session
        if (mailSession == null) {
            logger.warn { "Email service not configured. Would send to $to: $subject" }
            logger.info { "Email preview:\n$textBody" }
            trackEmailSent(to, emailType, false)
            return
        }
        
        var success = false
        try {
            val message = MimeMessage(mailSession).apply {
                setFrom(InternetAddress(fromEmail))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                setSubject(subject)
                
                // Create multipart message with both HTML and text
                val multipart = MimeMultipart("alternative")
                
                // Add text part
                val textPart = MimeBodyPart().apply {
                    setText(textBody, "UTF-8")
                }
                multipart.addBodyPart(textPart)
                
                // Add HTML part
                val htmlPart = MimeBodyPart().apply {
                    setContent(htmlBody, "text/html; charset=UTF-8")
                }
                multipart.addBodyPart(htmlPart)
                
                setContent(multipart)
            }
            
            Transport.send(message)
            success = true
            logger.info { "Email sent to $to" }
            SentryUtils.breadcrumb("email", "Email sent successfully", mapOf(
                "to" to to,
                "type" to emailType
            ))
        } catch (e: Exception) {
            logger.error("Failed to send email to $to", e)
            Sentry.captureException(e) { scope ->
                scope.setTag("email.operation", "send")
                scope.setExtra("email.to", to)
                scope.setExtra("email.subject", subject)
                scope.setExtra("email.type", emailType)
            }
            throw e
        } finally {
            trackEmailSent(to, emailType, success)
        }
    }
    
    private fun trackEmailSent(recipient: String, emailType: String, success: Boolean) {
        try {
            transaction {
                // Try to find organization for the recipient
                val orgId = Users.select { Users.email eq recipient }
                    .firstOrNull()
                    ?.let { user ->
                        Memberships.select { Memberships.user_id eq user[Users.id] }
                            .firstOrNull()
                            ?.get(Memberships.organization_id)
                    }
                
                EmailsSent.insert {
                    it[EmailsSent.organization_id] = orgId
                    it[EmailsSent.email_type] = emailType
                    it[EmailsSent.recipient] = recipient
                    it[EmailsSent.sent_at] = Clock.System.now()
                    it[EmailsSent.success] = success
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to track email sent to $recipient" }
        }
    }
    
    private fun loadVerificationTemplate(userName: String, verificationUrl: String): String {
        // Try to load the built email template
        val templatePath = "emails/build/templates/email/verify-email.html"
        val templateFile = File(templatePath)
        
        return if (templateFile.exists()) {
            templateFile.readText()
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
    
    private fun loadPasswordResetTemplate(userName: String, resetUrl: String): String {
        // Try to load the built email template
        val templatePath = "emails/build/templates/email/reset-password.html"
        val templateFile = File(templatePath)
        
        return if (templateFile.exists()) {
            templateFile.readText()
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
    
    fun sendErrorAlertEmail(to: String, data: ErrorAlertData) {
        val subject = "[${data.projectName}] ${data.issueLevel.uppercase()}: ${data.issueTitle}"
        val htmlBody = loadErrorAlertTemplate(data)
        val textBody = """
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
    
    fun sendWeeklySummaryEmail(to: String, data: WeeklySummaryData) {
        val subject = "Your Weekly Summary: ${data.totalEvents} events, ${data.newIssues} new issues"
        val htmlBody = loadWeeklySummaryTemplate(data)
        val textBody = """
            Your Weekly Summary (${data.startDate} – ${data.endDate})
            
            KEY STATS:
            - Total Events: ${data.totalEvents} (${if (data.eventsTrend > 0) "+" else ""}${data.eventsTrend}%)
            - New Issues: ${data.newIssues} (${if (data.issuesTrend > 0) "+" else ""}${data.issuesTrend}%)
            - Affected Users: ${data.affectedUsers} (${if (data.usersTrend > 0) "+" else ""}${data.usersTrend}%)
            
            TOP ISSUES:
            ${data.topIssues.take(5).joinToString("\n") { "- ${it.title} (${it.project}): ${it.count} events" }}
            
            Open Dashboard: ${data.dashboardUrl}
            Manage preferences: ${data.settingsUrl}
        """.trimIndent()
        
        sendEmail(to, subject, htmlBody, textBody, "weekly_summary")
    }
    
    private fun loadErrorAlertTemplate(data: ErrorAlertData): String {
        val templatePath = "emails/build/templates/email/error-alert.html"
        val templateFile = File(templatePath)
        val year = java.time.Year.now().value.toString()
        
        return if (templateFile.exists()) {
            templateFile.readText()
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
        val templatePath = "emails/build/templates/email/weekly-summary.html"
        val templateFile = File(templatePath)
        val year = java.time.Year.now().value.toString()
        
        return if (templateFile.exists()) {
            var html = templateFile.readText()
                .replace("{{ startDate }}", data.startDate)
                .replace("{{ endDate }}", data.endDate)
                .replace("{{ totalEvents }}", data.totalEvents)
                .replace("{{ eventsTrend }}", data.eventsTrend.toString())
                .replace("{{ newIssues }}", data.newIssues)
                .replace("{{ issuesTrend }}", data.issuesTrend.toString())
                .replace("{{ affectedUsers }}", data.affectedUsers)
                .replace("{{ usersTrend }}", data.usersTrend.toString())
                .replace("{{ dashboardUrl }}", data.dashboardUrl)
                .replace("{{ settingsUrl }}", data.settingsUrl)
                .replace("{{ unsubscribeUrl }}", data.unsubscribeUrl)
                .replace("{{ year }}", year)
            
            // Replace issue list (simplified - in production would use proper template engine)
            val issuesHtml = data.topIssues.joinToString("\n") { issue ->
                """
                <tr class="border-b border-slate-200">
                  <td class="py-3 pr-2">
                    <p class="m-0 text-sm font-semibold text-slate-900 mb-1">${issue.title}</p>
                    <p class="m-0 text-xs text-slate-600 font-mono">${issue.culprit}</p>
                  </td>
                  <td class="py-3 px-2">
                    <p class="m-0 text-sm text-slate-700">${issue.project}</p>
                  </td>
                  <td class="py-3 pl-2 text-right">
                    <p class="m-0 text-sm font-bold text-slate-900">${issue.count}</p>
                  </td>
                </tr>
                """.trimIndent()
            }
            html = html.replace("<!-- ISSUES_PLACEHOLDER -->", issuesHtml)
            
            val projectsHtml = data.projects.joinToString("\n") { project ->
                """
                <tr>
                  <td class="pb-4">
                    <p class="m-0 text-base font-bold text-slate-900 mb-2">${project.name}</p>
                    <p class="m-0 text-sm text-slate-600">Events: ${project.events} | Issues: ${project.issues} | Crash-Free: ${project.crashFree}%</p>
                  </td>
                </tr>
                """.trimIndent()
            }
            html = html.replace("<!-- PROJECTS_PLACEHOLDER -->", projectsHtml)
            
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
}
