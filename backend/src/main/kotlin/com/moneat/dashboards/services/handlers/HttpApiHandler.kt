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

package com.moneat.dashboards.services.handlers

import com.moneat.dashboards.services.DataSourceCredentials
import com.moneat.utils.UrlValidator
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json

abstract class HttpApiHandler : DataSourceHandler {

    companion object {
        internal const val DEFAULT_HTTP_PORT = 80
        internal const val DEFAULT_HTTPS_PORT = 443
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
        private const val MILLIS_PER_SECOND = 1_000L

        /**
         * Resolves the HTTP auth header(s) for a request from the chosen auth_method
         * (basic / bearer / custom header / none), falling back to the vendor's
         * historical scheme only when no auth_method is stored. Pure and testable.
         */
        internal fun resolveHttpAuthHeaders(
            credentials: DataSourceCredentials,
            default: HttpAuthDefault,
        ): List<Pair<String, String>> {
            val options = credentials.options
            return when (options.authMethod) {
                "basic" -> basicAuthHeader(credentials.username, credentials.password)
                "bearer" -> bearerHeader(credentials.apiKey)
                "header" -> {
                    val name = options.headerName
                    val value = credentials.headerValue
                    if (!name.isNullOrBlank() && !value.isNullOrBlank()) {
                        listOf(name to value)
                    } else {
                        emptyList()
                    }
                }
                "none" -> emptyList()
                else -> defaultAuthHeaders(default, credentials.apiKey, credentials.username, credentials.password)
            }
        }

        private fun defaultAuthHeaders(
            default: HttpAuthDefault,
            apiKey: String?,
            username: String?,
            password: String?,
        ): List<Pair<String, String>> = when (default) {
            HttpAuthDefault.BEARER -> bearerHeader(apiKey)
            HttpAuthDefault.TOKEN -> apiKey?.takeIf { it.isNotBlank() }
                ?.let { listOf(HttpHeaders.Authorization to "Token $it") } ?: emptyList()
            HttpAuthDefault.ORG_ID -> apiKey?.takeIf { it.isNotBlank() }
                ?.let { listOf("X-Scope-OrgID" to it) } ?: emptyList()
            HttpAuthDefault.ELASTICSEARCH -> when {
                !apiKey.isNullOrBlank() -> listOf(HttpHeaders.Authorization to "ApiKey $apiKey")
                !username.isNullOrBlank() && !password.isNullOrBlank() -> basicAuthHeader(username, password)
                else -> emptyList()
            }
            HttpAuthDefault.NONE -> emptyList()
        }

        private fun bearerHeader(apiKey: String?): List<Pair<String, String>> =
            apiKey?.takeIf { it.isNotBlank() }
                ?.let { listOf(HttpHeaders.Authorization to "Bearer $it") } ?: emptyList()

        private fun basicAuthHeader(username: String?, password: String?): List<Pair<String, String>> {
            if (username.isNullOrBlank()) return emptyList()
            val encoded = java.util.Base64.getEncoder()
                .encodeToString("$username:${password ?: ""}".toByteArray())
            return listOf(HttpHeaders.Authorization to "Basic $encoded")
        }
    }

    protected val json = Json { ignoreUnknownKeys = true }
    protected open val defaultPort: Int = DEFAULT_HTTP_PORT
    protected val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 30_000
            endpoint { connectTimeout = 10_000 }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MS
        }
    }

    /** Builds the URL string without performing SSRF validation. */
    internal fun buildUrlString(host: String, port: Int?): String {
        val scheme = when {
            host.startsWith("https://") -> "https://"
            host.startsWith("http://") -> "http://"
            else -> "http://"
        }
        val withoutScheme = host.removePrefix("https://").removePrefix("http://")
        // Preserve a reverse-proxy base path (e.g. "/prometheus") so the API path
        // appended by handlers lands under it.
        val slashIdx = withoutScheme.indexOf('/')
        val basePath = if (slashIdx >= 0) withoutScheme.substring(slashIdx).trimEnd('/') else ""
        val cleanHost = (if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme).trimEnd('/')
        return "$scheme${buildAuthority(cleanHost, scheme, port)}$basePath"
    }

    /** Wraps bare IPv6 literals and appends the port unless it is implicit or already present. */
    private fun buildAuthority(cleanHost: String, scheme: String, port: Int?): String {
        // Normalize bare IPv6 literals (e.g. "2001:db8::1") by wrapping in brackets
        val normalizedHost = if (!cleanHost.startsWith("[") && cleanHost.count { it == ':' } > 1) {
            "[$cleanHost]"
        } else {
            cleanHost
        }
        val hostHasPort = if (normalizedHost.startsWith("[")) {
            normalizedHost.contains("]:")
        } else {
            normalizedHost.contains(":")
        }
        if (port == null || hostHasPort) return normalizedHost
        val isDefaultPort =
            (scheme == "http://" && port == DEFAULT_HTTP_PORT) ||
                (scheme == "https://" && port == DEFAULT_HTTPS_PORT)
        return if (isDefaultPort) normalizedHost else "$normalizedHost:$port"
    }

    /** Builds the URL and validates it against SSRF-blocked addresses. */
    internal fun buildUrl(host: String, port: Int?): String {
        val url = buildUrlString(host, port)
        UrlValidator.validateExternalUrl(url)
        return url
    }

    /** Per-vendor fallback auth applied to legacy rows created before explicit auth_method. */
    enum class HttpAuthDefault { NONE, BEARER, TOKEN, ORG_ID, ELASTICSEARCH }

    /** The vendor's historical auth scheme, applied when a row has no explicit auth_method. */
    protected open val httpAuthDefault: HttpAuthDefault = HttpAuthDefault.NONE

    /**
     * Applies the auth scheme the user chose in the connect dialog (auth_method in
     * extra_config) to an outgoing request: none / basic / bearer / custom header.
     * All inputs (secrets + parsed options) ride in [credentials].
     */
    protected fun HttpRequestBuilder.applyHttpAuth(credentials: DataSourceCredentials) {
        resolveHttpAuthHeaders(credentials, httpAuthDefault)
            .forEach { (name, value) -> header(name, value) }
        credentials.options.timeoutSeconds?.let { timeoutSeconds ->
            val timeoutMs = timeoutSeconds * MILLIS_PER_SECOND
            timeout {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }
    }
}
