// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.services

import com.moneat.enterprise.datadog.models.DatadogLogEntry
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

        assertEquals("debug", batch.logs[0].level)
        assertEquals("info", batch.logs[1].level)
        assertEquals("info", batch.logs[2].level)
        assertEquals("warn", batch.logs[3].level)
        assertEquals("warn", batch.logs[4].level)
        assertEquals("error", batch.logs[5].level)
        assertEquals("fatal", batch.logs[6].level)
        assertEquals("fatal", batch.logs[7].level)
        assertEquals("fatal", batch.logs[8].level)
        assertEquals("info", batch.logs[9].level) // default
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
