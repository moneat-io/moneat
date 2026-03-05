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

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.datadog.models.DdDebuggerDiagnostic
import com.moneat.datadog.models.DdDebuggerInput
import com.moneat.shared.services.UsageTrackingService
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private const val DEBUGGER_QUEUE_KEY = "moneat:dd:debugger:queue"

private val validDebuggerTypes = setOf(
    "log_probe",
    "snapshot",
    "span_decoration",
    "metric_probe"
)
private val validDiagnosticStatuses = setOf(
    "received",
    "installed",
    "emitting",
    "error",
    "blocked"
)

@Serializable
data class QueuedDebuggerBatch(
    @SerialName("organization_id") val organizationId: Int,
    @SerialName("batch_type") val batchType: String,
    val logs: List<QueuedDebuggerLogEntry> = emptyList(),
    val diagnostics: List<QueuedDebuggerDiagEntry> = emptyList(),
)

@Serializable
data class QueuedDebuggerLogEntry(
    val service: String = "",
    val env: String = "",
    val version: String = "",
    @SerialName("debugger_type") val debuggerType: String = "log_probe",
    @SerialName("probe_id") val probeId: String = "",
    @SerialName("probe_location") val probeLocation: String = "",
    val message: String = "",
    val snapshot: String = "",
    val host: String = "",
    @SerialName("timestamp_ms") val timestampMs: Long,
    val tags: Map<String, String> = emptyMap(),
)

@Serializable
data class QueuedDebuggerDiagEntry(
    val service: String = "",
    val env: String = "",
    @SerialName("runtime_id") val runtimeId: String = "",
    @SerialName("probe_id") val probeId: String = "",
    val status: String = "received",
    @SerialName("error_message") val errorMessage: String = "",
    val host: String = "",
    @SerialName("timestamp_ms") val timestampMs: Long,
    val tags: Map<String, String> = emptyMap(),
)

object DebuggerIngestionService {
    private val clickhouseDb by lazy { ClickHouseClient.getDatabase() }
    private val usageTracking = UsageTrackingService.instance

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun mapDebuggerLogs(
        organizationId: Int,
        entries: List<DdDebuggerInput>,
    ): QueuedDebuggerBatch {
        val logs = entries.map { entry ->
            val tags = parseDdTagList(entry.tags)
            QueuedDebuggerLogEntry(
                service = entry.service, env = entry.env,
                version = entry.version,
                debuggerType = normalizeDebuggerType(entry.debuggerType),
                probeId = entry.probeId,
                probeLocation = entry.probeLocation,
                message = entry.message, snapshot = entry.snapshot,
                host = entry.host,
                timestampMs = entry.timestamp ?: System.currentTimeMillis(),
                tags = tags,
            )
        }
        return QueuedDebuggerBatch(organizationId, "logs", logs = logs)
    }

    fun mapDiagnostics(
        organizationId: Int,
        entries: List<DdDebuggerDiagnostic>,
    ): QueuedDebuggerBatch {
        val diagnostics = entries.map { entry ->
            val tags = parseDdTagList(entry.tags)
            QueuedDebuggerDiagEntry(
                service = entry.service, env = entry.env,
                runtimeId = entry.runtimeId, probeId = entry.probeId,
                status = normalizeDiagnosticStatus(entry.status),
                errorMessage = entry.errorMessage,
                host = entry.host,
                timestampMs = entry.timestamp ?: System.currentTimeMillis(),
                tags = tags,
            )
        }
        return QueuedDebuggerBatch(organizationId, "diagnostics", diagnostics = diagnostics)
    }

    fun enqueueDebuggerLogs(
        organizationId: Int,
        entries: List<DdDebuggerInput>,
        queueKey: String = DEBUGGER_QUEUE_KEY,
    ): Int {
        val batch = mapDebuggerLogs(organizationId, entries)
        if (batch.logs.isEmpty()) return 0
        RedisConfig.sync().lpush(queueKey, json.encodeToString(batch))
        return batch.logs.size
    }

    fun enqueueDiagnostics(
        organizationId: Int,
        entries: List<DdDebuggerDiagnostic>,
        queueKey: String = DEBUGGER_QUEUE_KEY,
    ): Int {
        val batch = mapDiagnostics(organizationId, entries)
        if (batch.diagnostics.isEmpty()) return 0
        RedisConfig.sync().lpush(queueKey, json.encodeToString(batch))
        return batch.diagnostics.size
    }

    suspend fun insertBatch(batch: QueuedDebuggerBatch) {
        when (batch.batchType) {
            "logs" -> insertDebuggerLogs(batch)
            "diagnostics" -> insertDiagnostics(batch)
        }
    }

    private suspend fun insertDebuggerLogs(batch: QueuedDebuggerBatch) {
        if (batch.logs.isEmpty()) return
        val rows = batch.logs.joinToString(",\n") { log ->
            """(
                ${batch.organizationId},
                '${escapeSql(log.service)}', '${escapeSql(log.env)}',
                '${escapeSql(log.version)}',
                '${escapeSql(log.debuggerType)}',
                '${escapeSql(log.probeId)}',
                '${escapeSql(log.probeLocation)}',
                '${escapeSql(log.message)}',
                '${escapeSql(log.snapshot)}',
                '${escapeSql(log.host)}',
                fromUnixTimestamp64Milli(${log.timestampMs}),
                ${mapToSqlMap(log.tags)}
            )"""
        }
        val insert = """
            INSERT INTO `$clickhouseDb`.debugger_logs (
                organization_id, service, env, version, debugger_type,
                probe_id, probe_location, message, snapshot, host,
                timestamp, tags
            ) VALUES $rows
        """.trimIndent()
        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to insert DD debugger logs")
        }
        val totalBytes = batch.logs.sumOf { it.message.length + it.snapshot.length }
        usageTracking.recordOrgUsage(batch.organizationId, "dd_debugger", totalBytes)
    }

    private suspend fun insertDiagnostics(batch: QueuedDebuggerBatch) {
        if (batch.diagnostics.isEmpty()) return
        val rows = batch.diagnostics.joinToString(",\n") { d ->
            """(
                ${batch.organizationId},
                '${escapeSql(d.service)}', '${escapeSql(d.env)}',
                '${escapeSql(d.runtimeId)}', '${escapeSql(d.probeId)}',
                '${escapeSql(d.status)}',
                '${escapeSql(d.errorMessage)}',
                '${escapeSql(d.host)}',
                fromUnixTimestamp64Milli(${d.timestampMs}),
                ${mapToSqlMap(d.tags)}
            )"""
        }
        val insert = """
            INSERT INTO `$clickhouseDb`.debugger_diagnostics (
                organization_id, service, env, runtime_id, probe_id,
                status, error_message, host, timestamp, tags
            ) VALUES $rows
        """.trimIndent()
        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to insert DD debugger diagnostics")
        }
    }

    fun decodeBatch(encoded: String): QueuedDebuggerBatch = json.decodeFromString(encoded)

    internal fun normalizeDebuggerType(type: String): String {
        val lower = type.lowercase()
        return if (lower in validDebuggerTypes) lower else "log_probe"
    }

    internal fun normalizeDiagnosticStatus(status: String): String {
        val lower = status.lowercase()
        return if (lower in validDiagnosticStatuses) lower else "received"
    }

    internal fun parseDdTagList(tags: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        tags.forEach { tag ->
            val colonIdx = tag.indexOf(':')
            if (colonIdx > 0) {
                result[tag.substring(0, colonIdx)] = tag.substring(colonIdx + 1)
            } else if (tag.isNotEmpty()) {
                result[tag] = ""
            }
        }
        return result
    }

    private fun escapeSql(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    private fun mapToSqlMap(map: Map<String, String>): String {
        if (map.isEmpty()) return "map()"
        val entries = map.entries.joinToString(", ") { (k, v) ->
            "'${escapeSql(k)}', '${escapeSql(v)}'"
        }
        return "map($entries)"
    }
}
