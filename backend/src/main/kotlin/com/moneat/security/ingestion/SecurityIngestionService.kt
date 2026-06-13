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

package com.moneat.security.ingestion

import com.moneat.config.ClickHouseClient
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.security.posture.ComplianceFindingInput
import com.moneat.security.posture.PostureRegressionAnalyzer
import com.moneat.security.signals.RuntimeSecurityEventInput
import com.moneat.security.signals.SignalDerivation
import com.moneat.security.signals.SignalOutcome
import com.moneat.security.signals.SignalWriter
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private const val SECURITY_QUEUE_KEY = "moneat:dd:security:queue"

@Serializable
data class QueuedSecurityBatch(
    @SerialName("organization_id") val organizationId: Int,
    @SerialName("batch_type") val batchType: String,
    val events: List<QueuedSecurityEventEntry> = emptyList(),
    val dumps: List<QueuedActivityDumpEntry> = emptyList(),
    val findings: List<QueuedComplianceEntry> = emptyList(),
)

@Serializable
data class QueuedSecurityEventEntry(
    @SerialName("rule_id") val ruleId: String = "",
    @SerialName("rule_name") val ruleName: String = "",
    @SerialName("rule_category") val ruleCategory: String = "",
    val severity: String = "info",
    @SerialName("agent_rule_version") val agentRuleVersion: String = "",
    @SerialName("event_type") val eventType: String = "",
    @SerialName("process_name") val processName: String = "",
    @SerialName("file_path") val filePath: String = "",
    val host: String = "",
    val env: String = "",
    val tags: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Serializable
data class QueuedActivityDumpEntry(
    @SerialName("activity_type") val activityType: String = "",
    @SerialName("process_name") val processName: String = "",
    val host: String = "",
    @SerialName("duration_ns") val durationNs: Long = 0,
    @SerialName("dump_data") val dumpData: String = "",
    val tags: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Serializable
data class QueuedComplianceEntry(
    val framework: String = "",
    @SerialName("rule_id") val ruleId: String = "",
    @SerialName("rule_name") val ruleName: String = "",
    val status: String = "passed",
    @SerialName("resource_type") val resourceType: String = "",
    @SerialName("resource_id") val resourceId: String = "",
    @SerialName("resource_name") val resourceName: String = "",
    val tags: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms") val timestampMs: Long,
)

object SecurityIngestionService {
    private val clickhouseDb by lazy { ClickHouseClient.getDatabase() }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Inserts the batch into ClickHouse, then derives security signals from it via [SignalWriter]
     * (open-signal dedup). Returns the resulting signal outcomes so the caller can drive the
     * `security.signal` workflow trigger off signal lifecycle rather than per raw event. Both the
     * ingestion worker and the demo reseed call this, so both populate the triage surface.
     */
    suspend fun insertBatch(batch: QueuedSecurityBatch): List<SignalOutcome> {
        when (batch.batchType) {
            "events" -> insertSecurityEvents(batch)
            "dumps" -> insertActivityDumps(batch)
            "findings" -> insertComplianceFindings(batch)
        }
        return deriveSignals(batch)
    }

    fun enqueueBatch(batch: QueuedSecurityBatch) {
        IngestionQueueClient.enqueue(IngestionPipeline.DD_SECURITY, SECURITY_QUEUE_KEY, json.encodeToString(batch))
    }

    /**
     * Derives signals best-effort: the ClickHouse insert is already durable by this point, so a
     * problem persisting signals (e.g. a transient Postgres issue) is logged and degrades to "no
     * signals for this batch" rather than failing the ingest or crashing the worker. Each spec is
     * upserted independently so one bad row cannot drop the rest.
     */
    private fun deriveSignals(batch: QueuedSecurityBatch): List<SignalOutcome> {
        val specs = runCatching {
            if (batch.batchType == "findings") {
                PostureRegressionAnalyzer.analyze(
                    batch.organizationId,
                    batch.findings.map { it.toPostureFindingInput() },
                )
            } else if (batch.batchType == "events") {
                val events = batch.events.map { it.toRuntimeSecurityEventInput() }
                SignalDerivation.fromRuntimeEvents(events)
            } else {
                emptyList()
            }
        }.getOrElse { e ->
            logger.warn { "Security signal analysis failed for ${batch.batchType}: ${e.message}" }
            emptyList()
        }
        return specs.mapNotNull { spec ->
            runCatching { SignalWriter.upsert(batch.organizationId, spec) }
                .onFailure { logger.warn { "Signal derivation failed for rule ${spec.ruleId}: ${it.message}" } }
                .getOrNull()
        }
    }

    @Suppress("LongMethod")
    private suspend fun insertSecurityEvents(batch: QueuedSecurityBatch) {
        if (batch.events.isEmpty()) return
        val rows = batch.events.joinToString(",\n") { e ->
            val sev = when (e.severity) {
                "low" -> "low"
                "medium" -> "medium"
                "high" -> "high"
                "critical" -> "critical"
                else -> "info"
            }
            """(
                toUInt64(${batch.organizationId}),
                '${escapeSql(e.ruleId)}', '${escapeSql(e.ruleName)}',
                '${escapeSql(e.ruleCategory)}', '$sev',
                '${escapeSql(e.agentRuleVersion)}',
                '${escapeSql(e.eventType)}',
                '${escapeSql(e.processName)}',
                '${escapeSql(e.filePath)}',
                '${escapeSql(e.host)}', '${escapeSql(e.env)}',
                ${mapToSqlMap(e.tags)},
                fromUnixTimestamp64Milli(${e.timestampMs})
            )"""
        }
        executeInsert(
            """INSERT INTO `$clickhouseDb`.security_events (
                organization_id, rule_id, rule_name, rule_category,
                severity, agent_rule_version, event_type,
                process_name, file_path, host, env, tags, timestamp
            ) VALUES $rows""",
            "security_events"
        )
    }

    private suspend fun insertActivityDumps(batch: QueuedSecurityBatch) {
        if (batch.dumps.isEmpty()) return
        val rows = batch.dumps.joinToString(",\n") { d ->
            """(
                toUInt64(${batch.organizationId}),
                '${escapeSql(d.activityType)}',
                '${escapeSql(d.processName)}',
                '${escapeSql(d.host)}',
                ${d.durationNs},
                '${escapeSql(d.dumpData)}',
                ${mapToSqlMap(d.tags)},
                fromUnixTimestamp64Milli(${d.timestampMs})
            )"""
        }
        executeInsert(
            """INSERT INTO `$clickhouseDb`.security_dumps (
                organization_id, activity_type, process_name,
                host, duration_ns, dump_data, tags, timestamp
            ) VALUES $rows""",
            "security_dumps"
        )
    }

    private suspend fun insertComplianceFindings(batch: QueuedSecurityBatch) {
        if (batch.findings.isEmpty()) return
        val rows = batch.findings.joinToString(",\n") { f ->
            val status = when (f.status) {
                "failed" -> "failed"
                "skipped" -> "skipped"
                "error" -> "error"
                else -> "passed"
            }
            """(
                toUInt64(${batch.organizationId}),
                '${escapeSql(f.framework)}',
                '${escapeSql(f.ruleId)}', '${escapeSql(f.ruleName)}',
                '$status',
                '${escapeSql(f.resourceType)}',
                '${escapeSql(f.resourceId)}',
                '${escapeSql(f.resourceName)}',
                ${mapToSqlMap(f.tags)},
                fromUnixTimestamp64Milli(${f.timestampMs})
            )"""
        }
        executeInsert(
            """INSERT INTO `$clickhouseDb`.compliance_findings (
                organization_id, framework, rule_id, rule_name,
                status, resource_type, resource_id, resource_name,
                tags, evaluated_at
            ) VALUES $rows""",
            "compliance_findings"
        )
    }

    fun decodeBatch(encoded: String): QueuedSecurityBatch =
        json.decodeFromString(encoded)

    private suspend fun executeInsert(sql: String, label: String) {
        val response = ClickHouseClient.execute(sql)
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to insert DD $label")
        }
    }

    private fun mapToSqlMap(map: Map<String, String>): String {
        if (map.isEmpty()) return "map()"
        val entries = map.entries.joinToString(", ") { (k, v) ->
            "'${escapeSql(k)}', '${escapeSql(v)}'"
        }
        return "map($entries)"
    }

    private fun QueuedComplianceEntry.toPostureFindingInput(): ComplianceFindingInput =
        ComplianceFindingInput(
            framework = framework,
            ruleId = ruleId,
            ruleName = ruleName,
            status = status,
            resourceType = resourceType,
            resourceId = resourceId,
            resourceName = resourceName,
            timestampMs = timestampMs,
        )

    private fun QueuedSecurityEventEntry.toRuntimeSecurityEventInput(): RuntimeSecurityEventInput =
        RuntimeSecurityEventInput(
            ruleId = ruleId,
            ruleName = ruleName,
            severity = severity,
            processName = processName,
            filePath = filePath,
            host = host,
            timestampMs = timestampMs,
        )
}
