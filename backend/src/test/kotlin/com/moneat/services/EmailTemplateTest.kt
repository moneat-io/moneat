package com.moneat.services

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmailTemplateTest {
    
    @Test
    fun `verify email templates are bundled in classpath`() {
        val templates = listOf(
            "verify-email.html",
            "reset-password.html",
            "org-invitation.html",
            "error-alert.html",
            "weekly-summary.html",
            "system-alert-v1.html",
            "system-recovered.html"
        )
        
        templates.forEach { templateName ->
            val resource = this::class.java.classLoader.getResourceAsStream("email-templates/$templateName")
            assertNotNull(resource, "Template $templateName should be in classpath")
            
            val content = resource.bufferedReader().use { it.readText() }
            assertTrue(content.isNotEmpty(), "Template $templateName should not be empty")
            assertTrue(content.contains("{{"), "Template $templateName should contain placeholders")
        }
    }
}
