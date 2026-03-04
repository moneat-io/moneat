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

import com.moneat.datadog.models.DdDebuggerDiagnostic
import com.moneat.datadog.models.DdDebuggerInput
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DebuggerIngestionServiceTest {

    // ============ MAP DEBUGGER LOGS TESTS ============

    @Test
    fun `mapDebuggerLogs maps entries correctly`() {
        val entries = listOf(
            DdDebuggerInput(
                service = "api-server",
                env = "production",
                version = "1.2.3",
                debuggerType = "log_probe",
                probeId = "probe-abc",
                probeLocation = "com.example.UserService:42",
                message = "userId=123, name=John",
                snapshot = """{"captures":{"lines":{"42":{"locals":{}}}}}""",
                host = "web-01",
                timestamp = 1700000000000L,
                tags = listOf("team:backend", "env:production"),
            )
        )

        val batch = DebuggerIngestionService.mapDebuggerLogs(1, entries)

        assertEquals(1, batch.organizationId)
        assertEquals("logs", batch.batchType)
        assertEquals(1, batch.logs.size)

        val log = batch.logs[0]
        assertEquals("api-server", log.service)
        assertEquals("production", log.env)
        assertEquals("1.2.3", log.version)
        assertEquals("log_probe", log.debuggerType)
        assertEquals("probe-abc", log.probeId)
        assertEquals("com.example.UserService:42", log.probeLocation)
        assertEquals("userId=123, name=John", log.message)
        assertTrue(log.snapshot.contains("captures"))
        assertEquals("web-01", log.host)
        assertEquals(1700000000000L, log.timestampMs)
        assertEquals("backend", log.tags["team"])
    }

    @Test
    fun `mapDebuggerLogs handles empty entries`() {
        val batch = DebuggerIngestionService.mapDebuggerLogs(1, emptyList())
        assertTrue(batch.logs.isEmpty())
    }

    @Test
    fun `mapDebuggerLogs handles multiple entries`() {
        val entries = listOf(
            DdDebuggerInput(probeId = "p1", message = "log1"),
            DdDebuggerInput(probeId = "p2", message = "log2"),
            DdDebuggerInput(probeId = "p3", message = "log3"),
        )

        val batch = DebuggerIngestionService.mapDebuggerLogs(1, entries)
        assertEquals(3, batch.logs.size)
        assertEquals("p1", batch.logs[0].probeId)
        assertEquals("p2", batch.logs[1].probeId)
        assertEquals("p3", batch.logs[2].probeId)
    }

    @Test
    fun `mapDebuggerLogs uses current time when timestamp is null`() {
        val entries = listOf(
            DdDebuggerInput(probeId = "p1", timestamp = null)
        )

        val before = System.currentTimeMillis()
        val batch = DebuggerIngestionService.mapDebuggerLogs(1, entries)
        val after = System.currentTimeMillis()

        assertTrue(batch.logs[0].timestampMs in before..after)
    }

    // ============ MAP DIAGNOSTICS TESTS ============

    @Test
    fun `mapDiagnostics maps entries correctly`() {
        val entries = listOf(
            DdDebuggerDiagnostic(
                service = "api-server",
                env = "staging",
                runtimeId = "runtime-123",
                probeId = "probe-xyz",
                status = "installed",
                errorMessage = "",
                host = "web-02",
                timestamp = 1700000000000L,
                tags = listOf("version:1.0"),
            )
        )

        val batch = DebuggerIngestionService.mapDiagnostics(1, entries)

        assertEquals(1, batch.organizationId)
        assertEquals("diagnostics", batch.batchType)
        assertEquals(1, batch.diagnostics.size)

        val d = batch.diagnostics[0]
        assertEquals("api-server", d.service)
        assertEquals("staging", d.env)
        assertEquals("runtime-123", d.runtimeId)
        assertEquals("probe-xyz", d.probeId)
        assertEquals("installed", d.status)
        assertEquals("", d.errorMessage)
        assertEquals("web-02", d.host)
        assertEquals("1.0", d.tags["version"])
    }

    @Test
    fun `mapDiagnostics handles error status`() {
        val entries = listOf(
            DdDebuggerDiagnostic(
                probeId = "p1",
                status = "error",
                errorMessage = "Cannot instrument class",
            )
        )

        val batch = DebuggerIngestionService.mapDiagnostics(1, entries)
        assertEquals("error", batch.diagnostics[0].status)
        assertEquals("Cannot instrument class", batch.diagnostics[0].errorMessage)
    }

    // ============ ENCODE/DECODE ROUND-TRIP TESTS ============

    @Test
    fun `decodeBatch round-trips debugger logs batch`() {
        val entries = listOf(
            DdDebuggerInput(probeId = "p1", message = "test msg", timestamp = 1700000000000L)
        )

        val batch = DebuggerIngestionService.mapDebuggerLogs(1, entries)
        val json = kotlinx.serialization.json.Json.encodeToString(batch)
        val decoded = DebuggerIngestionService.decodeBatch(json)

        assertEquals("logs", decoded.batchType)
        assertEquals(1, decoded.logs.size)
        assertEquals("p1", decoded.logs[0].probeId)
        assertEquals("test msg", decoded.logs[0].message)
    }

    @Test
    fun `decodeBatch round-trips diagnostics batch`() {
        val entries = listOf(
            DdDebuggerDiagnostic(probeId = "p1", status = "installed", timestamp = 1700000000000L)
        )

        val batch = DebuggerIngestionService.mapDiagnostics(1, entries)
        val json = kotlinx.serialization.json.Json.encodeToString(batch)
        val decoded = DebuggerIngestionService.decodeBatch(json)

        assertEquals("diagnostics", decoded.batchType)
        assertEquals(1, decoded.diagnostics.size)
        assertEquals("installed", decoded.diagnostics[0].status)
    }

    // ============ NORMALIZATION TESTS ============

    @Test
    fun `normalizeDebuggerType accepts valid types`() {
        assertEquals("log_probe", DebuggerIngestionService.normalizeDebuggerType("log_probe"))
        assertEquals("snapshot", DebuggerIngestionService.normalizeDebuggerType("snapshot"))
        assertEquals("span_decoration", DebuggerIngestionService.normalizeDebuggerType("span_decoration"))
        assertEquals("metric_probe", DebuggerIngestionService.normalizeDebuggerType("metric_probe"))
    }

    @Test
    fun `normalizeDebuggerType is case insensitive`() {
        assertEquals("log_probe", DebuggerIngestionService.normalizeDebuggerType("LOG_PROBE"))
        assertEquals("snapshot", DebuggerIngestionService.normalizeDebuggerType("Snapshot"))
    }

    @Test
    fun `normalizeDebuggerType defaults to log_probe for unknown types`() {
        assertEquals("log_probe", DebuggerIngestionService.normalizeDebuggerType("unknown"))
        assertEquals("log_probe", DebuggerIngestionService.normalizeDebuggerType(""))
    }

    @Test
    fun `normalizeDiagnosticStatus accepts valid statuses`() {
        assertEquals("received", DebuggerIngestionService.normalizeDiagnosticStatus("received"))
        assertEquals("installed", DebuggerIngestionService.normalizeDiagnosticStatus("installed"))
        assertEquals("emitting", DebuggerIngestionService.normalizeDiagnosticStatus("emitting"))
        assertEquals("error", DebuggerIngestionService.normalizeDiagnosticStatus("error"))
        assertEquals("blocked", DebuggerIngestionService.normalizeDiagnosticStatus("blocked"))
    }

    @Test
    fun `normalizeDiagnosticStatus is case insensitive`() {
        assertEquals("installed", DebuggerIngestionService.normalizeDiagnosticStatus("INSTALLED"))
        assertEquals("error", DebuggerIngestionService.normalizeDiagnosticStatus("Error"))
    }

    @Test
    fun `normalizeDiagnosticStatus defaults to received for unknown statuses`() {
        assertEquals("received", DebuggerIngestionService.normalizeDiagnosticStatus("unknown"))
        assertEquals("received", DebuggerIngestionService.normalizeDiagnosticStatus(""))
    }

    // ============ TAG PARSING TESTS ============

    @Test
    fun `parseDdTagList parses key-value pairs`() {
        val result = DebuggerIngestionService.parseDdTagList(
            listOf("env:production", "service:api")
        )
        assertEquals("production", result["env"])
        assertEquals("api", result["service"])
    }

    @Test
    fun `parseDdTagList handles tags without values`() {
        val result = DebuggerIngestionService.parseDdTagList(
            listOf("standalone", "env:prod")
        )
        assertEquals("", result["standalone"])
        assertEquals("prod", result["env"])
    }

    @Test
    fun `parseDdTagList handles empty list`() {
        assertTrue(DebuggerIngestionService.parseDdTagList(emptyList()).isEmpty())
    }
}
