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

package com.moneat.contact.routes

import com.moneat.contact.models.SalesInquiryRequest
import com.moneat.contact.services.ContactService
import com.moneat.notifications.services.EmailService
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ContactRoutesTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun ApplicationTestBuilder.setupApp(contactService: ContactService) {
        application {
            install(ContentNegotiation) { json() }
            routing { route("/v1") { contactRoutes(contactService) } }
        }
    }

    // ──── Sales inquiry ────

    @Test
    fun `valid inquiry returns ok and forwards to service`() = testApplication {
        val emailService = mockk<EmailService>()
        every { emailService.sendEnterpriseSalesInquiry(any(), any(), any(), any()) } just Runs
        setupApp(ContactService(emailService))

        val response = client.post("/v1/contact/sales") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    SalesInquiryRequest(
                        name = "Ada Lovelace",
                        email = "ada@acme.com",
                        company = "Acme Corp",
                        message = "We need a dedicated SLA."
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        verify(exactly = 1) {
            emailService.sendEnterpriseSalesInquiry(
                "Ada Lovelace",
                "ada@acme.com",
                "Acme Corp",
                "We need a dedicated SLA."
            )
        }
    }

    @Test
    fun `invalid inquiry returns bad request`() = testApplication {
        val emailService = mockk<EmailService>(relaxed = true)
        setupApp(ContactService(emailService))

        val response = client.post("/v1/contact/sales") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    SalesInquiryRequest(
                        name = "",
                        email = "not-an-email",
                        company = "Acme",
                        message = "hi"
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        verify(exactly = 0) { emailService.sendEnterpriseSalesInquiry(any(), any(), any(), any()) }
    }

    @Test
    fun `honeypot submission returns ok without emailing`() = testApplication {
        val emailService = mockk<EmailService>(relaxed = true)
        setupApp(ContactService(emailService))

        val response = client.post("/v1/contact/sales") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    SalesInquiryRequest(
                        name = "Bot",
                        email = "bot@spam.example",
                        company = "Spam",
                        message = "spam",
                        website = "http://spam.example"
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        verify(exactly = 0) { emailService.sendEnterpriseSalesInquiry(any(), any(), any(), any()) }
    }
}
