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

package com.moneat.datadog.services

import com.moneat.datadog.models.DatadogLogEntry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DatadogLogServiceTest {

    @Test
    fun `parseDdTags parses key-value pairs`() {
        val result = DatadogLogService.parseDdTags("env:prod,service:web,version:1.2.3")
        assertEquals("prod", result["env"])
        assertEquals("web", result["service"])
        assertEquals("1.2.3", result["version"])
    }

    @Test
    fun `parseDdTags handles empty string`() {
        val result = DatadogLogService.parseDdTags("")
        assertEquals(0, result.size)
    }

    @Test
    fun `parseDdTags handles tags without values`() {
        val result = DatadogLogService.parseDdTags("standalone,env:prod")
        assertEquals("", result["standalone"])
        assertEquals("prod", result["env"])
    }

    @Test
    fun `parseDdTags handles colons in values`() {
        val result = DatadogLogService.parseDdTags("url:http://example.com:8080")
        assertEquals("http://example.com:8080", result["url"])
    }

    @Test
    fun `parseDdTags trims whitespace`() {
        val result = DatadogLogService.parseDdTags(" env:prod , service:web ")
        assertEquals("prod", result["env"])
        assertEquals("web", result["service"])
    }

    @Test
    fun `mapDdLogs maps status to level correctly`() {
        val entries = listOf(
            DatadogLogEntry(message = "trace msg", status = "trace"),
            DatadogLogEntry(message = "debug msg", status = "debug"),
            DatadogLogEntry(message = "info msg", status = "info"),
            DatadogLogEntry(message = "notice msg", status = "notice"),
            DatadogLogEntry(message = "warn msg", status = "warn"),
            DatadogLogEntry(message = "warning msg", status = "warning"),
            DatadogLogEntry(message = "error msg", status = "error"),
            DatadogLogEntry(message = "critical msg", status = "critical"),
            DatadogLogEntry(message = "alert msg", status = "alert"),
            DatadogLogEntry(message = "emerg msg", status = "emerg"),
            DatadogLogEntry(message = "unknown msg", status = "custom_level")
        )

        val batch = DatadogLogService.mapDdLogs(1L, entries)

        assertEquals("trace", batch.logs[0].level)
        assertEquals("debug", batch.logs[1].level)
        assertEquals("info", batch.logs[2].level)
        assertEquals("info", batch.logs[3].level)
        assertEquals("warn", batch.logs[4].level)
        assertEquals("warn", batch.logs[5].level)
        assertEquals("error", batch.logs[6].level)
        assertEquals("fatal", batch.logs[7].level)
        assertEquals("fatal", batch.logs[8].level)
        assertEquals("fatal", batch.logs[9].level)
        assertEquals("info", batch.logs[10].level) // default
    }

    @Test
    fun `mapDdLogs derives level and tags from Datadog Agent log lines`() {
        val rawMessage =
            "2026-06-03 02:05:42 UTC | TRACE | ERROR | " +
                "(pkg/trace/log/throttled.go:46 in log) | Received unexpected status code 403"
        val entries = listOf(
            DatadogLogEntry(
                message = rawMessage,
                status = "info",
                service = "moneat-stage",
                ddsource = "agent"
            )
        )

        val batch = DatadogLogService.mapDdLogs(1L, entries)
        val log = batch.logs.single()

        assertEquals("error", log.level)
        assertEquals("Received unexpected status code 403", log.message)
        assertEquals(rawMessage, log.body)
        assertEquals("trace", log.tags["category"])
        assertEquals("trace", log.tags["datadog.agent.component"])
        assertEquals("datadog_agent", log.tags["log.format"])
        assertEquals("pkg/trace/log/throttled.go:46 in log", log.tags["datadog.agent.caller"])
        assertEquals("pkg/trace/log/throttled.go", log.tags["code.filepath"])
        assertEquals("log", log.tags["code.function"])
        assertEquals("agent", log.tags["ddsource"])
    }

    @Test
    fun `mapDdLogs keeps more severe envelope level for Agent log lines`() {
        val rawMessage =
            "2026-06-03 02:05:34 UTC | CORE | WARN | " +
                "(pkg/process/runner/submitter.go:332 in logQueueSize) | Delivery queues: process[size=42]"
        val entries = listOf(
            DatadogLogEntry(
                message = rawMessage,
                status = "error"
            )
        )

        val batch = DatadogLogService.mapDdLogs(1L, entries)
        val log = batch.logs.single()

        assertEquals("error", log.level)
        assertEquals("Delivery queues: process[size=42]", log.message)
        assertEquals("core", log.tags["category"])
        assertEquals("logQueueSize", log.tags["code.function"])
    }

    @Test
    fun `mapDdLogs sets source to datadog`() {
        val entries = listOf(
            DatadogLogEntry(message = "test", status = "info")
        )

        val batch = DatadogLogService.mapDdLogs(1L, entries)

        assertEquals("datadog", batch.logs[0].source)
        assertEquals("datadog", batch.source)
    }

    @Test
    fun `mapDdLogs extracts ddsource into tags`() {
        val entries = listOf(
            DatadogLogEntry(
                message = "test",
                status = "info",
                ddsource = "nginx"
            )
        )

        val batch = DatadogLogService.mapDdLogs(1L, entries)

        assertEquals("nginx", batch.logs[0].tags["ddsource"])
    }

    @Test
    fun `mapDdLogs maps hostname and service`() {
        val entries = listOf(
            DatadogLogEntry(
                message = "test",
                hostname = "web-01",
                service = "api-server",
                status = "info"
            )
        )

        val batch = DatadogLogService.mapDdLogs(1L, entries)

        assertEquals("web-01", batch.logs[0].host)
        assertEquals("api-server", batch.logs[0].service)
    }

    @Test
    fun `mapDdLogs extracts env from ddtags`() {
        val entries = listOf(
            DatadogLogEntry(
                message = "test",
                status = "info",
                ddtags = "env:production,version:2.0"
            )
        )

        val batch = DatadogLogService.mapDdLogs(1L, entries)

        assertEquals("production", batch.logs[0].environment)
        assertEquals("2.0", batch.logs[0].tags["version"])
        // env should be removed from tags since it became environment
        assertEquals(null, batch.logs[0].tags["env"])
    }

    @Test
    fun `mapDdLogs uses provided timestamp`() {
        val ts = 1700000000000L
        val entries = listOf(
            DatadogLogEntry(
                message = "test",
                status = "info",
                timestamp = ts
            )
        )

        val batch = DatadogLogService.mapDdLogs(1L, entries)

        assertEquals(ts, batch.logs[0].timestampMs)
    }

    @Test
    fun `mapDdLogs uses current time when no timestamp`() {
        val entries = listOf(
            DatadogLogEntry(message = "test", status = "info")
        )

        val before = System.currentTimeMillis()
        val batch = DatadogLogService.mapDdLogs(1L, entries)
        val after = System.currentTimeMillis()

        val ts = batch.logs[0].timestampMs
        assert(ts in before..after) {
            "Timestamp $ts should be between $before and $after"
        }
    }

    @Test
    fun `mapDdLogs sets organization id`() {
        val entries = listOf(
            DatadogLogEntry(message = "test", status = "info")
        )

        val batch = DatadogLogService.mapDdLogs(42L, entries)

        assertEquals(42L, batch.organizationId)
    }

    @Test
    fun `mapDdLogs handles empty entries list`() {
        val batch = DatadogLogService.mapDdLogs(1L, emptyList())
        assertEquals(0, batch.logs.size)
    }
}
