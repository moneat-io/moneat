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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonitoringTest {
    @Test
    fun `skips health and metrics endpoints`() {
        assertTrue(shouldSkipTracing("/health", "GET"))
        assertTrue(shouldSkipTracing("/health/ready", "GET"))
        assertTrue(shouldSkipTracing("/metrics", "GET"))
    }

    @Test
    fun `skips OTLP and log ingestion endpoints`() {
        assertTrue(shouldSkipTracing("/v1/traces", "POST"))
        assertTrue(shouldSkipTracing("/v1/traces/otlp", "POST"))
        assertTrue(shouldSkipTracing("/v1/metrics", "POST"))
        assertTrue(shouldSkipTracing("/v1/metrics/otlp", "POST"))
        assertTrue(shouldSkipTracing("/v1/logs", "POST"))
        assertTrue(shouldSkipTracing("/v1/logs/otlp", "POST"))
        assertTrue(shouldSkipTracing("/v1/logs/ingest", "POST"))
        assertTrue(shouldSkipTracing("/v1/pulse", "POST"))
    }

    @Test
    fun `skips Datadog intake endpoints`() {
        assertTrue(shouldSkipTracing("/dd/api/v1/series", "POST"))
        assertTrue(shouldSkipTracing("/dd/v0.4/traces", "PUT"))
        assertTrue(shouldSkipTracing("/dd/api/v2/logs", "POST"))
        assertTrue(shouldSkipTracing("/api/v1/container", "POST"))
        assertTrue(shouldSkipTracing("/api/v2/databasequery", "POST"))
        assertTrue(shouldSkipTracing("/api/v2/contimage", "POST"))
    }

    @Test
    fun `skips Sentry-compatible ingestion endpoints`() {
        assertTrue(shouldSkipTracing("/api/123/envelope/", "POST"))
        assertTrue(shouldSkipTracing("/api/123/store", "POST"))
        assertTrue(shouldSkipTracing("/api/123/logs", "POST"))
        assertTrue(shouldSkipTracing("/api/123/security", "POST"))
    }

    @Test
    fun `does not skip dashboard reads`() {
        assertFalse(shouldSkipTracing("/v1/traces", "GET"))
        assertFalse(shouldSkipTracing("/v1/services/map", "GET"))
        assertFalse(shouldSkipTracing("/v1/apm-errors", "GET"))
        assertFalse(shouldSkipTracing("/v1/projects/1/stats", "GET"))
    }
}
