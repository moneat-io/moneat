// Moneat - Mobile-First Error Monitoring Platform
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
