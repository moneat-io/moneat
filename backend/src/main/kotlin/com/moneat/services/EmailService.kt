package com.moneat.services

import aws.sdk.kotlin.services.ses.SesClient
import aws.sdk.kotlin.services.ses.model.Body
import aws.sdk.kotlin.services.ses.model.Content
import aws.sdk.kotlin.services.ses.model.Destination
import aws.sdk.kotlin.services.ses.model.Message
import aws.sdk.kotlin.services.ses.model.SendEmailRequest
import io.ktor.server.config.*
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

class EmailService {
    private val config = ApplicationConfig("application.conf")
    private val fromEmail = config.property("email.from").getString()
    private val frontendUrl = config.property("email.frontendUrl").getString()
    private val awsRegion = config.propertyOrNull("email.aws.region")?.getString() ?: "us-east-1"
    
    private val sesClient: SesClient? by lazy {
        try {
            // AWS SDK will automatically use credentials from:
            // 1. Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
            // 2. AWS credentials file (~/.aws/credentials)
            // 3. IAM role (if running on EC2/ECS)
            runBlocking {
                SesClient {
                    region = awsRegion
                }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to initialize AWS SES client: ${e.message}" }
            null
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
    
    private fun sendEmail(to: String, subject: String, htmlBody: String, textBody: String) {
        val client = sesClient
        if (client == null) {
            logger.warn { "Email service not configured. Would send to $to: $subject" }
            logger.info { "Email preview:\n$textBody" }
            return
        }
        
        try {
            runBlocking {
                client.sendEmail(SendEmailRequest {
                    source = fromEmail
                    destination = Destination {
                        toAddresses = listOf(to)
                    }
                    message = Message {
                        this.subject = Content { data = subject }
                        body = Body {
                            html = Content { data = htmlBody }
                            text = Content { data = textBody }
                        }
                    }
                })
            }
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
}
