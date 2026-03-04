// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.auth

import com.moneat.enterprise.datadog.services.DatadogService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private const val CACHE_TTL_MS = 300_000L // 5 minutes
private const val MAX_CACHE_SIZE = 10_000

object DatadogAuthMiddleware {
    private data class CachedKey(val organizationId: Int, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CachedKey>()

    private fun evictExpiredEntries(now: Long) {
        cache.entries.removeIf { (_, value) -> now >= value.expiresAt }
    }

    /**
     * Validates DD-API-KEY header and returns the organization ID.
     * Returns null and responds with 403 if validation fails.
     */
    suspend fun authenticate(call: ApplicationCall): Int? {
        val now = System.currentTimeMillis()
        evictExpiredEntries(now)

        val apiKey = call.request.headers["DD-API-KEY"]
            ?: call.request.headers["DD-Api-Key"]
            ?: call.request.headers["dd-api-key"]
            ?: call.request.queryParameters["api_key"]
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
            return cached.organizationId
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

    // For testing
    internal fun clearCache() {
        cache.clear()
    }
}
