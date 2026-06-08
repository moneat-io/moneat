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

package com.moneat.contact.models

import kotlinx.serialization.Serializable

/**
 * Public Enterprise sales-contact submission from the marketing pricing page.
 *
 * [website] is a honeypot field: it is hidden from real users and should be empty.
 * Any submission with a non-blank [website] is treated as a bot and silently discarded.
 */
@Serializable
data class SalesInquiryRequest(
    val name: String,
    val email: String,
    val company: String,
    val message: String,
    val website: String? = null
)
