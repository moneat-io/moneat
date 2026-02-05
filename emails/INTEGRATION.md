# Email Templates Integration Guide

## Overview

Email templates are built with Maizzle and output production-ready HTML with `{{ variable }}` placeholders for backend substitution.

## Building Templates

```bash
cd emails
npm run build:production
```

This creates minified, CSS-inlined HTML in `emails/build/templates/email/`.

## Backend Integration Example

### 1. Copy Built Templates to Backend Resources

After building, copy the templates to your backend:

```bash
cp emails/build/templates/email/*.html backend/src/main/resources/email-templates/
```

Or add a build step to your backend Gradle:

```kotlin
// backend/build.gradle.kts
tasks.register<Exec>("buildEmails") {
    workingDir = file("../emails")
    commandLine("npm", "run", "build:production")
}

tasks.named("processResources") {
    dependsOn("buildEmails")
    doLast {
        copy {
            from("../emails/build/templates/email")
            into("$buildDir/resources/main/email-templates")
        }
    }
}
```

### 2. Create Email Service (Kotlin Example)

```kotlin
package com.moneat.services

import java.io.File

class EmailService {
    private val templates = mutableMapOf<String, String>()
    
    init {
        // Load templates from resources
        loadTemplate("error-alert")
    }
    
    private fun loadTemplate(name: String) {
        val resource = this::class.java.classLoader
            .getResource("email-templates/$name.html")
        templates[name] = resource?.readText() ?: throw Exception("Template not found: $name")
    }
    
    fun renderErrorAlert(data: ErrorAlertData): String {
        var html = templates["error-alert"] ?: throw Exception("Template not found")
        
        // Replace all placeholders
        html = html.replace("{{ issueTitle }}", escapeHtml(data.issueTitle))
        html = html.replace("{{ issueLevel }}", data.issueLevel)
        html = html.replace("{{ issueCulprit }}", escapeHtml(data.issueCulprit))
        html = html.replace("{{ issueMessage }}", escapeHtml(data.issueMessage))
        html = html.replace("{{ issueCount }}", data.issueCount.toString())
        html = html.replace("{{ issueUrl }}", data.issueUrl)
        html = html.replace("{{ projectName }}", escapeHtml(data.projectName))
        html = html.replace("{{ environment }}", data.environment)
        html = html.replace("{{ timestamp }}", data.timestamp)
        html = html.replace("{{ stackTrace }}", escapeHtml(data.stackTrace))
        html = html.replace("{{ settingsUrl }}", data.settingsUrl)
        
        return html
    }
    
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}

data class ErrorAlertData(
    val issueTitle: String,
    val issueLevel: String,
    val issueCulprit: String,
    val issueMessage: String,
    val issueCount: Int,
    val issueUrl: String,
    val projectName: String,
    val environment: String,
    val timestamp: String,
    val stackTrace: String,
    val settingsUrl: String
)
```

### 3. Send Email

```kotlin
import jakarta.mail.*
import jakarta.mail.internet.*

fun sendErrorAlert(to: String, issue: Issue) {
    val emailService = EmailService()
    
    val data = ErrorAlertData(
        issueTitle = issue.title,
        issueLevel = issue.level,
        issueCulprit = issue.culprit,
        issueMessage = issue.message,
        issueCount = issue.eventCount,
        issueUrl = "https://moneat.example.com/issues/${issue.id}",
        projectName = issue.projectName,
        environment = issue.environment ?: "production",
        timestamp = issue.firstSeen.toString(),
        stackTrace = issue.stackTrace ?: "",
        settingsUrl = "https://moneat.example.com/settings/notifications"
    )
    
    val htmlBody = emailService.renderErrorAlert(data)
    
    // Send using your email provider (AWS SES, SendGrid, etc.)
    val message = MimeMessage(session).apply {
        setFrom(InternetAddress("alerts@moneat.example.com"))
        setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
        subject = "[Moneat] New Error: ${issue.title}"
        setContent(htmlBody, "text/html; charset=utf-8")
    }
    
    Transport.send(message)
}
```

## Alternative: Template Engine

For more complex templating, consider using a template engine like:

- **Freemarker** - Java template engine
- **Thymeleaf** - Spring-compatible
- **Mustache** - Logic-less templates

### Example with Mustache

```kotlin
dependencies {
    implementation("com.github.spullara.mustache.java:compiler:0.9.11")
}
```

```kotlin
import com.github.mustachejava.DefaultMustacheFactory
import java.io.StringWriter

class EmailService {
    private val mustacheFactory = DefaultMustacheFactory()
    
    fun renderErrorAlert(data: ErrorAlertData): String {
        val template = this::class.java.classLoader
            .getResourceAsStream("email-templates/error-alert.html")
        
        val mustache = mustacheFactory.compile(template.reader(), "error-alert")
        val writer = StringWriter()
        mustache.execute(writer, data).flush()
        
        return writer.toString()
    }
}
```

## Available Templates

### error-alert.html

Notification for new errors detected in a project.

**Required Variables:**
- `issueTitle` - Error title
- `issueLevel` - error|warning|info
- `issueCulprit` - Code location
- `issueMessage` - Full error message
- `issueCount` - Number of occurrences
- `issueUrl` - Link to issue dashboard
- `projectName` - Project name
- `environment` - production|staging|etc
- `timestamp` - ISO 8601 timestamp
- `stackTrace` - Stack trace text
- `settingsUrl` - Link to notification settings

## Testing Locally

Use the dev server to preview templates:

```bash
cd emails
npm run dev
```

Visit `http://localhost:3000` to see templates with sample data.

## Email Provider Setup

### AWS SES Example

```kotlin
import aws.sdk.kotlin.services.ses.SesClient
import aws.sdk.kotlin.services.ses.model.*

suspend fun sendEmail(to: String, subject: String, html: String) {
    SesClient { region = "us-east-1" }.use { ses ->
        ses.sendEmail {
            source = "alerts@moneat.example.com"
            destination {
                toAddresses = listOf(to)
            }
            message {
                this.subject { data = subject }
                body {
                    this.html { data = html }
                }
            }
        }
    }
}
```

### SendGrid Example

```kotlin
dependencies {
    implementation("com.sendgrid:sendgrid-java:4.9.3")
}
```

```kotlin
import com.sendgrid.*
import com.sendgrid.helpers.mail.Mail
import com.sendgrid.helpers.mail.objects.*

fun sendEmail(to: String, subject: String, html: String) {
    val from = Email("alerts@moneat.example.com")
    val toEmail = Email(to)
    val content = Content("text/html", html)
    
    val mail = Mail(from, subject, toEmail, content)
    val sg = SendGrid(System.getenv("SENDGRID_API_KEY"))
    
    val request = Request().apply {
        method = Method.POST
        endpoint = "mail/send"
        body = mail.build()
    }
    
    sg.api(request)
}
```
