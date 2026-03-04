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

package com.moneat.datadog.security

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.datadog.models.DdActivityDumpPayload
import com.moneat.datadog.models.DdCompliancePayload
import com.moneat.datadog.models.DdSecurityEventPayload
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

    fun enqueueSecurityEvents(
        orgId: Int,
        payload: DdSecurityEventPayload,
    ): Int {
        val now = System.currentTimeMillis()
        val entries = payload.events.map { e ->
            QueuedSecurityEventEntry(
                ruleId = e.ruleId, ruleName = e.ruleName,
                ruleCategory = e.ruleCategory, severity = e.severity,
                agentRuleVersion = e.agentRuleVersion,
                eventType = e.eventType, processName = e.processName,
                filePath = e.filePath, host = e.host, env = e.env,
                tags = parseDdTagList(e.tags), timestampMs = now,
            )
        }
        if (entries.isEmpty()) return 0
        val batch = QueuedSecurityBatch(orgId, "events", events = entries)
        RedisConfig.sync().lpush(SECURITY_QUEUE_KEY, json.encodeToString(batch))
        return entries.size
    }

    fun enqueueActivityDumps(
        orgId: Int,
        payload: DdActivityDumpPayload,
    ): Int {
        val now = System.currentTimeMillis()
        val entries = payload.dumps.map { d ->
            QueuedActivityDumpEntry(
                activityType = d.activityType,
                processName = d.processName, host = d.host,
                durationNs = d.durationNs, dumpData = d.dumpData,
                tags = parseDdTagList(d.tags), timestampMs = now,
            )
        }
        if (entries.isEmpty()) return 0
        val batch = QueuedSecurityBatch(orgId, "dumps", dumps = entries)
        RedisConfig.sync().lpush(SECURITY_QUEUE_KEY, json.encodeToString(batch))
        return entries.size
    }

    fun enqueueCompliance(
        orgId: Int,
        payload: DdCompliancePayload,
    ): Int {
        val now = System.currentTimeMillis()
        val entries = payload.findings.map { f ->
            QueuedComplianceEntry(
                framework = f.framework, ruleId = f.ruleId,
                ruleName = f.ruleName, status = f.status,
                resourceType = f.resourceType,
                resourceId = f.resourceId,
                resourceName = f.resourceName,
                tags = parseDdTagList(f.tags), timestampMs = now,
            )
        }
        if (entries.isEmpty()) return 0
        val batch = QueuedSecurityBatch(orgId, "findings", findings = entries)
        RedisConfig.sync().lpush(SECURITY_QUEUE_KEY, json.encodeToString(batch))
        return entries.size
    }

    suspend fun insertBatch(batch: QueuedSecurityBatch) {
        when (batch.batchType) {
            "events" -> insertSecurityEvents(batch)
            "dumps" -> insertActivityDumps(batch)
            "findings" -> insertComplianceFindings(batch)
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
                ${batch.organizationId},
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
            """INSERT INTO $clickhouseDb.security_events (
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
                ${batch.organizationId},
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
            """INSERT INTO $clickhouseDb.security_dumps (
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
                ${batch.organizationId},
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
            """INSERT INTO $clickhouseDb.compliance_findings (
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
