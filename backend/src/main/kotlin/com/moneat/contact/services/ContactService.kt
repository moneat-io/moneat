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

private const val MAX_NAME_LENGTH = 200
private const val MAX_EMAIL_LENGTH = 320
private const val MAX_COMPANY_LENGTH = 200
private const val MAX_MESSAGE_LENGTH = 5000
private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

class ContactService(
    private val emailService: EmailService
) {
    /**
     * Validates and forwards an Enterprise sales inquiry to the internal inbox.
     *
     * Bot submissions (non-blank honeypot) are silently discarded so automated scrapers
     * receive the same success response as humans. Genuine invalid input throws
     * [IllegalArgumentException], which the route surfaces as a 400.
     */
    fun submitInquiry(request: SalesInquiryRequest) {
        if (!request.website.isNullOrBlank()) {
            // Honeypot tripped — silently drop without emailing.
            return
        }

        val name = request.name.trim()
        val email = request.email.trim()
        val company = request.company.trim()
        val message = request.message.trim()

        require(name.isNotEmpty()) { "Name is required" }
        require(name.length <= MAX_NAME_LENGTH) { "Name is too long" }
        require(email.isNotEmpty()) { "Work email is required" }
        require(email.length <= MAX_EMAIL_LENGTH) { "Work email is too long" }
        require(EMAIL_REGEX.matches(email)) { "A valid work email is required" }
        require(company.isNotEmpty()) { "Company is required" }
        require(company.length <= MAX_COMPANY_LENGTH) { "Company is too long" }
        require(message.isNotEmpty()) { "Message is required" }
        require(message.length <= MAX_MESSAGE_LENGTH) { "Message is too long" }

        emailService.sendEnterpriseSalesInquiry(name, email, company, message)
    }
}
