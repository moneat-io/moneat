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

package com.moneat.datadog.auth

import com.moneat.datadog.services.DatadogService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private const val CACHE_TTL_MS = 300_000L // 5 minutes
private const val MAX_CACHE_SIZE = 10_000
private val API_KEY_HEADER_NAMES = listOf(
    "DD-API-KEY",
    "DD-Api-Key",
    "dd-api-key",
    "X-Datadog-API-Key",
    "X-Datadog-Api-Key",
    "Api-Key",
    "api-key",
)

object DatadogAuthMiddleware {
    private data class CachedKey(val organizationId: Int, val expiresAt: Long)
    private data class CachedContext(val context: DatadogAuthContext, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CachedKey>()
    private val contextCache = ConcurrentHashMap<String, CachedContext>()

    private fun evictExpiredEntries(now: Long) {
        cache.entries.removeIf { (_, value) -> now >= value.expiresAt }
        contextCache.entries.removeIf { (_, value) -> now >= value.expiresAt }
    }

    /**
     * Validates DD-API-KEY header and returns the organization ID.
     * Returns null and responds with 403 if validation fails.
     */
    suspend fun authenticate(call: ApplicationCall): Int? {
        val now = System.currentTimeMillis()
        evictExpiredEntries(now)

        val apiKey = extractApiKey(call)
        if (apiKey.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.Forbidden,
                mapOf("errors" to listOf("API key is missing or empty"))
            )
            return null
        }

        // Check cache first
        val cached = cache[apiKey]
        if (cached != null) {
            if (now < cached.expiresAt) {
                return cached.organizationId
            }
            cache.remove(apiKey, cached)
        }

        val organizationId = DatadogService.validateApiKey(apiKey)
        if (organizationId == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                mapOf("errors" to listOf("API key is not valid"))
            )
            return null
        }

        // Cache the result
        if (cache.size < MAX_CACHE_SIZE) {
            cache[apiKey] = CachedKey(
                organizationId,
                now + CACHE_TTL_MS
            )
        } else {
            logger.warn { "Datadog API key cache reached max size; skipping cache insert" }
        }

        return organizationId
    }

    suspend fun authenticateContext(call: ApplicationCall): DatadogAuthContext? {
        val now = System.currentTimeMillis()
        evictExpiredEntries(now)

        val apiKey = extractApiKey(call)
        if (apiKey.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.Forbidden,
                mapOf("errors" to listOf("API key is missing or empty"))
            )
            return null
        }

        val cached = contextCache[apiKey]
        if (cached != null) {
            if (now < cached.expiresAt) {
                return cached.context
            }
            contextCache.remove(apiKey, cached)
        }

        val validation = DatadogService.validateApiKeyContext(apiKey)
        if (validation == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                mapOf("errors" to listOf("API key is not valid"))
            )
            return null
        }

        val context = DatadogAuthContext(
            organizationId = validation.organizationId,
            projectId = validation.projectId,
        )
        if (contextCache.size < MAX_CACHE_SIZE) {
            contextCache[apiKey] = CachedContext(context, now + CACHE_TTL_MS)
        } else {
            logger.warn { "Datadog API key context cache reached max size; skipping cache insert" }
        }

        return context
    }

    /**
     * Resolves an org ID from a DD-API-KEY without performing HTTP responses.
     * Uses the same cache as [authenticate] to avoid redundant DB lookups.
     */
    fun resolveOrgId(apiKey: String): Int? {
        val now = System.currentTimeMillis()
        evictExpiredEntries(now)
        val cached = cache[apiKey]
        if (cached != null) {
            if (now < cached.expiresAt) {
                return cached.organizationId
            }
            cache.remove(apiKey, cached)
        }
        val organizationId = DatadogService.validateApiKey(apiKey) ?: return null
        if (cache.size < MAX_CACHE_SIZE) {
            cache[apiKey] = CachedKey(organizationId, now + CACHE_TTL_MS)
        }
        return organizationId
    }

    // For testing
    internal fun clearCache() {
        cache.clear()
        contextCache.clear()
    }

    fun extractApiKey(call: ApplicationCall): String? =
        API_KEY_HEADER_NAMES.firstNotNullOfOrNull { call.request.headers[it] }
            ?: call.request.queryParameters["api_key"]
            ?: call.request.queryParameters["api-key"]
}
