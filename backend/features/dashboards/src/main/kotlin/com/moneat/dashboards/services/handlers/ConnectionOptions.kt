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

/**
 * Parsed, non-secret connection options carried in a data source's extra_config.
 * The Add-data-source dialog writes these (explicit scheme, base path, auth method,
 * TLS mode, per-vendor extras) so connection behavior is no longer guessed from the
 * host string.
 */
data class ConnectionOptions(
    val scheme: String? = null,
    val basePath: String? = null,
    val authMethod: String? = null,
    val headerName: String? = null,
    val tlsMode: String? = null,
    val influxVersion: String? = null,
    val org: String? = null,
    val bucket: String? = null,
    val warehouse: String? = null,
    val role: String? = null,
    val chProtocol: String? = null,
    val schema: String? = null,
    val useRole: Boolean = false,
    val timeoutSeconds: Int? = null,
) {
    companion object {
        private val HTTP_SOURCE_TYPES = setOf("prometheus", "loki", "graphite", "elasticsearch", "influxdb")
        private const val MIN_TIMEOUT_SECONDS = 1
        private const val MAX_TIMEOUT_SECONDS = 300

        fun from(extra: Map<String, String>): ConnectionOptions = ConnectionOptions(
            scheme = extra["scheme"]?.lowercase()?.takeIf { it == "http" || it == "https" },
            basePath = extra["base_path"]?.trim()?.takeIf { it.isNotEmpty() && it != "/" },
            authMethod = extra["auth_method"]?.lowercase(),
            headerName = extra["header_name"]?.takeIf { it.isNotBlank() },
            tlsMode = extra["tls_mode"]?.takeIf { it.isNotBlank() },
            influxVersion = extra["influx_version"]?.takeIf { it.isNotBlank() },
            org = extra["org"]?.takeIf { it.isNotBlank() },
            bucket = extra["bucket"]?.takeIf { it.isNotBlank() },
            warehouse = extra["warehouse"]?.takeIf { it.isNotBlank() },
            role = extra["role"]?.takeIf { it.isNotBlank() },
            chProtocol = extra["ch_protocol"]?.takeIf { it.isNotBlank() },
            schema = extra["schema"]?.takeIf { it.isNotBlank() },
            useRole = extra["use_role"] == "true",
            timeoutSeconds = parseTimeoutSeconds(extra["timeout"]),
        )

        /**
         * Reconstructs the connection host for HTTP-style sources from the explicit
         * scheme + base path in extra_config. Non-HTTP source types, and hosts that
         * already carry a scheme (legacy rows), are returned unchanged.
         */
        fun effectiveHttpHost(sourceType: String, host: String, opts: ConnectionOptions): String {
            if (sourceType.lowercase() !in HTTP_SOURCE_TYPES) return host
            if (host.startsWith("http://") || host.startsWith("https://")) return host
            val withScheme = if (opts.scheme != null) "${opts.scheme}://$host" else host
            return appendBasePath(withScheme, opts.basePath)
        }

        fun effectiveHttpHost(sourceType: String, host: String, extra: Map<String, String>): String =
            effectiveHttpHost(sourceType, host, from(extra))

        private fun appendBasePath(base: String, basePath: String?): String {
            if (basePath.isNullOrEmpty()) return base
            val normalized = if (basePath.startsWith("/")) basePath else "/$basePath"
            return base.trimEnd('/') + normalized.trimEnd('/')
        }

        private fun parseTimeoutSeconds(value: String?): Int? {
            val parsed = value?.toIntOrNull() ?: return null
            if (parsed < MIN_TIMEOUT_SECONDS) return null
            return parsed.coerceAtMost(MAX_TIMEOUT_SECONDS)
        }
    }
}
