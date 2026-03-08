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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import kotlinx.serialization.json.Json

abstract class HttpApiHandler : DataSourceHandler {

    companion object {
        internal const val DEFAULT_HTTP_PORT = 80
        internal const val DEFAULT_HTTPS_PORT = 443
    }

    protected val json = Json { ignoreUnknownKeys = true }
    protected open val defaultPort: Int = DEFAULT_HTTP_PORT
    protected val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 30_000
            endpoint { connectTimeout = 10_000 }
        }
    }

    internal fun buildUrl(host: String, port: Int?): String {
        val scheme = when {
            host.startsWith("https://") -> "https://"
            host.startsWith("http://") -> "http://"
            else -> "http://"
        }
        val cleanHost = host.removePrefix("https://").removePrefix("http://").trimEnd('/')
        // Normalize bare IPv6 literals (e.g. "2001:db8::1") by wrapping in brackets
        val normalizedHost = if (
            !cleanHost.startsWith("[") && cleanHost.count { it == ':' } > 1
        ) {
            "[$cleanHost]"
        } else {
            cleanHost
        }
        val hostHasPort = if (normalizedHost.startsWith("[")) {
            normalizedHost.contains("]:")
        } else {
            normalizedHost.contains(":")
        }
        if (port == null || hostHasPort) return "$scheme$normalizedHost"
        val isDefaultPort =
            (scheme == "http://" && port == DEFAULT_HTTP_PORT) ||
                (scheme == "https://" && port == DEFAULT_HTTPS_PORT)
        return if (isDefaultPort) {
            "$scheme$normalizedHost"
        } else {
            "$scheme$normalizedHost:$port"
        }
    }

    protected fun withAuth(headers: MutableMap<String, String>, credentials: DataSourceCredentials) {
        credentials.apiKey?.let { headers["Authorization"] = "Bearer $it" }
    }
}
