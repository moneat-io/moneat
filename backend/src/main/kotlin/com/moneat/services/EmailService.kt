package com.moneat.services

import io.ktor.server.config.*
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import mu.KotlinLogging
import java.io.File
import java.util.Properties

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
        
        sendEmail(email, subject, htmlBody, textBody)
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
        
        sendEmail(email, subject, htmlBody, textBody)
    }
    
    private fun sendEmail(to: String, subject: String, htmlBody: String, textBody: String) {
        val mailSession = session
        if (mailSession == null) {
            logger.warn { "Email service not configured. Would send to $to: $subject" }
            logger.info { "Email preview:\n$textBody" }
            return
        }
        
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
            logger.info { "Verification email sent to $to" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to send email to $to" }
            throw e
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
}
