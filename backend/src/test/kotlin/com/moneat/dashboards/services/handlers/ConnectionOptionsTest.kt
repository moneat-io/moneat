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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionOptionsTest {

    @Test
    fun `parses structured options from extra_config`() {
        val opts = ConnectionOptions.from(
            mapOf(
                "scheme" to "https",
                "base_path" to "/prometheus",
                "auth_method" to "basic",
                "header_name" to "X-Scope-OrgID",
                "tls_mode" to "verify-full",
                "influx_version" to "2",
                "org" to "acme",
                "bucket" to "metrics",
                "timeout" to "45",
            )
        )
        assertEquals("https", opts.scheme)
        assertEquals("/prometheus", opts.basePath)
        assertEquals("basic", opts.authMethod)
        assertEquals("X-Scope-OrgID", opts.headerName)
        assertEquals("verify-full", opts.tlsMode)
        assertEquals("2", opts.influxVersion)
        assertEquals("acme", opts.org)
        assertEquals("metrics", opts.bucket)
        assertEquals(45, opts.timeoutSeconds)
    }

    @Test
    fun `parses timeout only when it is a positive bounded integer`() {
        assertEquals(30, ConnectionOptions.from(mapOf("timeout" to "30")).timeoutSeconds)
        assertEquals(300, ConnectionOptions.from(mapOf("timeout" to "5000")).timeoutSeconds)
        assertNull(ConnectionOptions.from(mapOf("timeout" to "0")).timeoutSeconds)
        assertNull(ConnectionOptions.from(mapOf("timeout" to "abc")).timeoutSeconds)
    }

    @Test
    fun `ignores invalid scheme and bare-slash base path`() {
        val opts = ConnectionOptions.from(mapOf("scheme" to "ftp", "base_path" to "/"))
        assertNull(opts.scheme)
        assertNull(opts.basePath)
    }

    @Test
    fun `effectiveHttpHost applies scheme and base path for http sources`() {
        val host = ConnectionOptions.effectiveHttpHost(
            "prometheus",
            "prom.example.com",
            mapOf("scheme" to "https", "base_path" to "/prometheus"),
        )
        assertEquals("https://prom.example.com/prometheus", host)
    }

    @Test
    fun `effectiveHttpHost applies base path without a scheme`() {
        val host = ConnectionOptions.effectiveHttpHost(
            "loki",
            "loki.internal",
            mapOf("base_path" to "/loki"),
        )
        assertEquals("loki.internal/loki", host)
    }

    @Test
    fun `effectiveHttpHost leaves non-http sources untouched`() {
        val host = ConnectionOptions.effectiveHttpHost(
            "postgresql",
            "db.example.com",
            mapOf("scheme" to "https"),
        )
        assertEquals("db.example.com", host)
    }

    @Test
    fun `effectiveHttpHost does not double-apply a scheme already on the host`() {
        val host = ConnectionOptions.effectiveHttpHost(
            "prometheus",
            "https://prom.example.com",
            mapOf("scheme" to "http"),
        )
        assertEquals("https://prom.example.com", host)
    }

    @Test
    fun `parses schema warehouse role and use_role`() {
        val opts = ConnectionOptions.from(
            mapOf("schema" to "reporting", "warehouse" to "WH", "role" to "R", "use_role" to "true"),
        )
        assertEquals("reporting", opts.schema)
        assertEquals("WH", opts.warehouse)
        assertEquals("R", opts.role)
        assertTrue(opts.useRole)
    }

    @Test
    fun `use_role is false when absent or not literally true`() {
        assertFalse(ConnectionOptions.from(emptyMap()).useRole)
        assertFalse(ConnectionOptions.from(mapOf("use_role" to "false")).useRole)
        assertFalse(ConnectionOptions.from(mapOf("use_role" to "1")).useRole)
    }

    @Test
    fun `effectiveHttpHost overload accepts pre-parsed options`() {
        val host = ConnectionOptions.effectiveHttpHost(
            "prometheus",
            "prom",
            ConnectionOptions(scheme = "https", basePath = "/p"),
        )
        assertEquals("https://prom/p", host)
    }
}
