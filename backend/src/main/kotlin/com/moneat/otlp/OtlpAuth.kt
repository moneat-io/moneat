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

import com.moneat.events.routes.extractPublicKey
import com.moneat.events.routes.extractPublicKeyFromDsn
import com.moneat.events.services.EventService
import com.moneat.otlp.services.OtlpApiKeyService
import com.moneat.shared.services.ProjectIdResolver
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * Shared OTLP authentication used by log, trace, and metric ingestion routes.
 * Extracts the org ID from an OTLP API key in the Authorization: Bearer header.
 */
object OtlpAuth {
    private const val BEARER_PREFIX = "Bearer "

    private val projectIdFromDsnRegex =
        "https?://[^@]+@[^/]+/([0-9]+|[0-9a-f-]{36})".toRegex(RegexOption.IGNORE_CASE)

    fun extractBearerToken(authorizationHeader: String?): String? {
        if (authorizationHeader == null) return null
        if (!authorizationHeader.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        return authorizationHeader.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotBlank() }
    }

    fun extractOrgId(
        call: ApplicationCall,
        otlpApiKeyService: OtlpApiKeyService
    ): Int? {
        val key = extractBearerToken(call.request.header(HttpHeaders.Authorization)) ?: return null
        return otlpApiKeyService.validateKey(key)
    }

    /**
     * Resolves the organization for OTLP log/trace-style ingest: Bearer OTLP API key, else legacy DSN / project key.
     */
    fun resolveOtlpIngestOrganizationId(
        call: ApplicationCall,
        otlpApiKeyService: OtlpApiKeyService,
        eventService: EventService,
        projectIdResolver: ProjectIdResolver,
    ): Int? =
        extractOrgId(call, otlpApiKeyService)
            ?: extractOrgIdFromLegacyDsn(call, eventService, projectIdResolver)

    private fun extractOrgIdFromLegacyDsn(
        call: ApplicationCall,
        eventService: EventService,
        projectIdResolver: ProjectIdResolver,
    ): Int? {
        val dsnLikeHeader =
            call.request.header("x-moneat-dsn")
                ?: call.request.header(HttpHeaders.Authorization)
        val projectId =
            extractProjectIdFromDsn(dsnLikeHeader, projectIdResolver)
                ?: call.request.queryParameters["projectId"]?.let(projectIdResolver::resolve)
                ?: return null
        val publicKey =
            extractPublicKey(
                call.request.header("X-Sentry-Auth"),
                call.request.queryParameters["sentry_key"]
            )
                ?: extractPublicKeyFromDsn(dsnLikeHeader)
                ?: return null
        val verification = eventService.verifyProjectKey(projectId, publicKey)
        if (!verification.isValid) return null
        return eventService.getOrganizationIdForProject(projectId)
    }

    private fun extractProjectIdFromDsn(dsnLike: String?, projectIdResolver: ProjectIdResolver): Long? {
        if (dsnLike.isNullOrBlank()) return null
        val cleaned = dsnLike.removePrefix("DSN ").trim()
        return projectIdFromDsnRegex
            .find(cleaned)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(projectIdResolver::resolve)
    }
}
