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

package com.moneat.otlp

import com.moneat.otlp.services.OtlpApiKeyService
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * Shared OTLP authentication used by log, trace, and metric ingestion routes.
 * Extracts the org ID from an OTLP API key in the Authorization: Bearer header.
 */
object OtlpAuth {
    private const val BEARER_PREFIX = "Bearer "

    fun extractOrgId(
        call: ApplicationCall,
        otlpApiKeyService: OtlpApiKeyService
    ): Int? {
        val authHeader = call.request.header(HttpHeaders.Authorization)
        val key =
            authHeader
                ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
                ?.substring(BEARER_PREFIX.length)
                ?.trim()
                ?: return null
        return otlpApiKeyService.validateKey(key)
    }
}
