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

package com.moneat.contact.services

import com.moneat.contact.models.SalesInquiryRequest
import com.moneat.notifications.services.EmailService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContactServiceTest {

    private fun validRequest() =
        SalesInquiryRequest(
            name = "Ada Lovelace",
            email = "ada@acme.com",
            company = "Acme Corp",
            message = "We need 5TB ingestion and an SLA.",
            website = null
        )

    @Test
    fun `submitInquiry sends trimmed inquiry to email service`() {
        val emailService = mockk<EmailService>()
        val nameSlot = slot<String>()
        val emailSlot = slot<String>()
        val companySlot = slot<String>()
        val messageSlot = slot<String>()
        every {
            emailService.sendEnterpriseSalesInquiry(
                capture(nameSlot), capture(emailSlot), capture(companySlot), capture(messageSlot)
            )
        } just Runs

        val service = ContactService(emailService)
        service.submitInquiry(
            validRequest().copy(
                name = "  Ada Lovelace  ",
                email = "  ada@acme.com ",
                company = " Acme Corp ",
                message = "  We need 5TB ingestion and an SLA.  "
            )
        )

        assertEquals("Ada Lovelace", nameSlot.captured)
        assertEquals("ada@acme.com", emailSlot.captured)
        assertEquals("Acme Corp", companySlot.captured)
        assertEquals("We need 5TB ingestion and an SLA.", messageSlot.captured)
    }

    @Test
    fun `submitInquiry drops bot submissions with filled honeypot without emailing`() {
        val emailService = mockk<EmailService>(relaxed = true)
        val service = ContactService(emailService)

        service.submitInquiry(validRequest().copy(website = "http://spam.example"))

        verify(exactly = 0) { emailService.sendEnterpriseSalesInquiry(any(), any(), any(), any()) }
    }

    @Test
    fun `submitInquiry rejects blank name`() {
        val service = ContactService(mockk(relaxed = true))
        assertFailsWith<IllegalArgumentException> {
            service.submitInquiry(validRequest().copy(name = "   "))
        }
    }

    @Test
    fun `submitInquiry rejects blank company`() {
        val service = ContactService(mockk(relaxed = true))
        assertFailsWith<IllegalArgumentException> {
            service.submitInquiry(validRequest().copy(company = ""))
        }
    }

    @Test
    fun `submitInquiry rejects blank message`() {
        val service = ContactService(mockk(relaxed = true))
        assertFailsWith<IllegalArgumentException> {
            service.submitInquiry(validRequest().copy(message = " "))
        }
    }

    @Test
    fun `submitInquiry rejects malformed email`() {
        val service = ContactService(mockk(relaxed = true))
        assertFailsWith<IllegalArgumentException> {
            service.submitInquiry(validRequest().copy(email = "not-an-email"))
        }
    }

    @Test
    fun `submitInquiry rejects overlong message`() {
        val service = ContactService(mockk(relaxed = true))
        assertFailsWith<IllegalArgumentException> {
            service.submitInquiry(validRequest().copy(message = "x".repeat(5001)))
        }
    }
}
