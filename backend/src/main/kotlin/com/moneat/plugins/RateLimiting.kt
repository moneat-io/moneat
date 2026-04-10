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

package com.moneat.plugins

import com.moneat.config.EnvConfig
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.events.routes.extractPublicKey
import com.moneat.events.services.EventService
import com.moneat.otlp.OtlpAuth
import com.moneat.otlp.services.OtlpApiKeyService
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.request.ApplicationRequest
import org.koin.core.context.GlobalContext
import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds
import com.moneat.utils.suspendRunCatching

/**
 * Trusted upstream proxies consulted before accepting forwarded-IP headers.
 *
 * Configure via the TRUSTED_PROXIES environment variable as a comma-separated list
 * of IPv4/IPv6 addresses or CIDR ranges (e.g. "103.21.244.0/22,10.0.0.0/8").
 *
 * Only requests whose direct connection IP (origin.remoteHost) matches an entry
 * are allowed to set CF-Connecting-IP or X-Forwarded-For as the rate-limit key.
 * Direct connections from unknown IPs are always bucketed by their own address.
 */
private object TrustedProxies {
    private val entries: List<String> by lazy {
        EnvConfig.get("TRUSTED_PROXIES", "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun contains(ip: String): Boolean =
        entries.isNotEmpty() && entries.any { matchesEntry(ip, it) }

    private fun matchesEntry(ip: String, entry: String): Boolean {
        if (!entry.contains('/')) return ip == entry
        return suspendRunCatching {
            val (networkStr, prefixStr) = entry.split('/', limit = 2)
            val prefixLen = prefixStr.toInt()
            val addr = InetAddress.getByName(ip).address
            val network = InetAddress.getByName(networkStr).address
            if (addr.size != network.size) return false
            val fullBytes = prefixLen / BITS_PER_BYTE
            val remainBits = prefixLen % BITS_PER_BYTE
            for (i in 0 until fullBytes) {
                if (addr[i] != network[i]) return false
            }
            if (remainBits > 0) {
                val mask = (BYTE_MASK shl (BITS_PER_BYTE - remainBits)).toByte()
                val addrBits = addr[fullBytes].toInt() and mask.toInt()
                val networkBits = network[fullBytes].toInt() and mask.toInt()
                if (addrBits != networkBits) return false
            }
            true
        }.getOrElse { _ ->
            false
        }
    }
}

/**
 * Returns the real client IP for rate-limiting purposes.
 *
 * CF-Connecting-IP and X-Forwarded-For are only trusted when the direct connection
 * peer (origin.remoteHost) is listed in TRUSTED_PROXIES; otherwise the peer address
 * itself is used so spoofed headers cannot bypass rate limits.
 */
private fun ApplicationRequest.clientIp(): String {
    val remoteHost = origin.remoteHost
    return if (TrustedProxies.contains(remoteHost)) {
        headers["CF-Connecting-IP"]?.trim()?.takeIf { it.isNotBlank() }
            ?: headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: remoteHost
    } else {
        remoteHost
    }
}

private const val INGEST_RATE_LIMIT = 100
private const val INGEST_REFILL_SECONDS = 1
private const val TELEMETRY_RATE_LIMIT = 10
private const val TELEMETRY_REFILL_SECONDS = 60
private const val BITS_PER_BYTE = 8
private const val BYTE_MASK = 0xFF

fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(RateLimitName("ingestion")) {
            requestKey { call ->
                val projectId = call.parameters["projectId"] ?: "unknown"
                val auth = call.request.headers.get("X-Sentry-Auth")
                val sentryKey = call.request.queryParameters["sentry_key"]
                val key = extractPublicKey(auth, sentryKey) ?: "anon"
                "$projectId:$key"
            }
            rateLimiter(limit = 100, refillPeriod = 1.seconds)
        }
        register(RateLimitName("api")) {
            requestKey { call ->
                val principal =
                    call.principal<JWTPrincipal>()
                        ?: call.principal<AuthTokenPrincipal>()
                when (principal) {
                    is JWTPrincipal -> {
                        principal.payload
                            .getClaim("userId")
                            .asInt()
                            .toString()
                    }

                    is AuthTokenPrincipal -> {
                        principal.userId.toString()
                    }

                    else -> {
                        "anon"
                    }
                }
            }
            rateLimiter(limit = 30, refillPeriod = 1.seconds)
        }
        register(RateLimitName("datadog-ingestion")) {
            requestKey { call ->
                val apiKey = call.request.headers["DD-API-KEY"]
                    ?: call.request.headers["DD-Api-Key"]
                    ?: call.request.headers["dd-api-key"]
                    ?: call.request.queryParameters["api_key"]
                if (apiKey != null) {
                    DatadogAuthMiddleware.resolveOrgId(apiKey)
                        ?.let { "org:$it" }
                        ?: call.request.clientIp()
                } else {
                    call.request.clientIp()
                }
            }
            rateLimiter(limit = INGEST_RATE_LIMIT, refillPeriod = INGEST_REFILL_SECONDS.seconds)
        }
        fun registerOtlpApiKeyIngestBucket(
            name: String,
            allowLegacyDsn: Boolean,
        ) {
            val otlpApiKeyService = GlobalContext.get().get<OtlpApiKeyService>()
            val eventService = if (allowLegacyDsn) {
                GlobalContext.get().get<EventService>()
            } else {
                null
            }
            register(RateLimitName(name)) {
                requestKey { call ->
                    val organizationId = if (allowLegacyDsn && eventService != null) {
                        OtlpAuth.resolveOtlpIngestOrganizationId(call, otlpApiKeyService, eventService)
                    } else {
                        OtlpAuth.extractOrgId(call, otlpApiKeyService)
                    }
                    organizationId
                        ?.let { "org:$it" }
                        ?: call.request.clientIp()
                }
                rateLimiter(limit = INGEST_RATE_LIMIT, refillPeriod = INGEST_REFILL_SECONDS.seconds)
            }
        }
        // Logs keep DSN fallback for legacy ingest clients.
        registerOtlpApiKeyIngestBucket("log-ingestion", allowLegacyDsn = true)
        // Traces and metrics are OTLP-key only.
        registerOtlpApiKeyIngestBucket("otlp-ingestion", allowLegacyDsn = false)
        register(RateLimitName("telemetry")) {
            requestKey { call -> call.request.clientIp() }
            rateLimiter(limit = TELEMETRY_RATE_LIMIT, refillPeriod = TELEMETRY_REFILL_SECONDS.seconds)
        }
    }
}
