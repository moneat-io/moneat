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
import com.moneat.datadog.models.DdDbmActivityPayload
import com.moneat.datadog.models.DdDbmHealthPayload
import com.moneat.datadog.models.DdDbmMetadataPayload
import com.moneat.datadog.models.DdDbmMetricsPayload
import com.moneat.datadog.models.DdDbmQueryPayload
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import com.moneat.utils.TimeConstants.MILLIS_PER_SECOND_LONG

private val logger = KotlinLogging.logger {}
private const val DBM_QUEUE_KEY = "moneat:dd:dbm:queue"

@Serializable
data class QueuedDbmBatch(
    @SerialName("organization_id") val organizationId: Int,
    @SerialName("batch_type") val batchType: String,
    val queries: List<QueuedDbmQueryEntry> = emptyList(),
    val metrics: List<QueuedDbmMetricEntry> = emptyList(),
    val activity: List<QueuedDbmActivityEntry> = emptyList(),
    val metadata: List<QueuedDbmMetadataEntry> = emptyList(),
    val health: List<QueuedDbmHealthEntry> = emptyList(),
)

@Serializable
data class QueuedDbmQueryEntry(
    @SerialName("db_host") val dbHost: String = "",
    @SerialName("db_system") val dbSystem: String = "",
    @SerialName("db_name") val dbName: String = "",
    @SerialName("db_user") val dbUser: String = "",
    @SerialName("query_signature") val querySignature: String = "",
    @SerialName("resource_hash") val resourceHash: String = "",
    val statement: String = "",
    @SerialName("query_truncated") val queryTruncated: Boolean = false,
    @SerialName("duration_ns") val durationNs: Long = 0,
    @SerialName("rows_affected") val rowsAffected: Long = 0,
    @SerialName("error_code") val errorCode: Int = 0,
    @SerialName("error_message") val errorMessage: String = "",
    @SerialName("timestamp_ms") val timestampMs: Long,
    val host: String = "",
    val env: String = "",
    val service: String = "",
    val tags: Map<String, String> = emptyMap(),
)

@Serializable
data class QueuedDbmMetricEntry(
    @SerialName("db_host") val dbHost: String = "",
    @SerialName("db_system") val dbSystem: String = "",
    @SerialName("db_name") val dbName: String = "",
    @SerialName("query_signature") val querySignature: String = "",
    @SerialName("timestamp_ms") val timestampMs: Long,
    val calls: Long = 0,
    @SerialName("total_time_ns") val totalTimeNs: Long = 0,
    val rows: Long = 0,
    @SerialName("shared_blks_hit") val sharedBlksHit: Long = 0,
    @SerialName("shared_blks_read") val sharedBlksRead: Long = 0,
    val host: String = "",
    val env: String = "",
    val tags: Map<String, String> = emptyMap(),
)

@Serializable
data class QueuedDbmActivityEntry(
    @SerialName("db_host") val dbHost: String = "",
    @SerialName("db_system") val dbSystem: String = "",
    @SerialName("db_name") val dbName: String = "",
    @SerialName("db_user") val dbUser: String = "",
    @SerialName("query_signature") val querySignature: String = "",
    val statement: String = "",
    val state: String = "",
    @SerialName("wait_event_type") val waitEventType: String = "",
    @SerialName("wait_event") val waitEvent: String = "",
    @SerialName("blocking_pids") val blockingPids: List<Long> = emptyList(),
    @SerialName("duration_ns") val durationNs: Long = 0,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val host: String = "",
    val env: String = "",
    val tags: Map<String, String> = emptyMap(),
)

@Serializable
data class QueuedDbmMetadataEntry(
    val host: String = "",
    @SerialName("db_system") val dbSystem: String = "",
    @SerialName("schema_json") val schemaJson: String = "",
    @SerialName("explain_plan_hash") val explainPlanHash: String = "",
    @SerialName("explain_plan") val explainPlan: String = "",
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Serializable
data class QueuedDbmHealthEntry(
    val host: String = "",
    @SerialName("db_system") val dbSystem: String = "",
    @SerialName("agent_version") val agentVersion: String = "",
    val status: String = "ok",
    @SerialName("checks_run") val checksRun: Int = 0,
    @SerialName("checks_failed") val checksFailed: Int = 0,
    @SerialName("host_name") val hostName: String = "",
    @SerialName("timestamp_ms") val timestampMs: Long,
)

object DbmIngestionService {
    private val clickhouseDb by lazy { ClickHouseClient.getDatabase() }
    private val usageTracking = UsageTrackingService.instance

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun mapQueries(organizationId: Int, payload: DdDbmQueryPayload): QueuedDbmBatch {
        val tags = parseDdTagList(payload.tags)
        val queries = payload.rows.map { row ->
            QueuedDbmQueryEntry(
                dbHost = payload.dbHost, dbSystem = payload.dbSystem,
                dbName = payload.dbName, dbUser = payload.dbUser,
                querySignature = row.querySignature,
                resourceHash = row.resourceHash,
                statement = row.statement,
                queryTruncated = row.queryTruncated,
                durationNs = row.durationNs,
                rowsAffected = row.rowsAffected,
                errorCode = row.errorCode,
                errorMessage = row.errorMessage,
                timestampMs = row.timestamp?.let { it * MILLIS_PER_SECOND_LONG }
                    ?: System.currentTimeMillis(),
                host = payload.host, env = payload.env,
                service = payload.service, tags = tags,
            )
        }
        return QueuedDbmBatch(organizationId, "queries", queries = queries)
    }

    fun mapMetrics(organizationId: Int, payload: DdDbmMetricsPayload): QueuedDbmBatch {
        val tags = parseDdTagList(payload.tags)
        val metrics = payload.rows.map { row ->
            QueuedDbmMetricEntry(
                dbHost = payload.dbHost, dbSystem = payload.dbSystem,
                dbName = row.dbName, querySignature = row.querySignature,
                timestampMs = row.timestamp?.let { it * MILLIS_PER_SECOND_LONG }
                    ?: System.currentTimeMillis(),
                calls = row.calls, totalTimeNs = row.totalTimeNs,
                rows = row.rows, sharedBlksHit = row.sharedBlksHit,
                sharedBlksRead = row.sharedBlksRead,
                host = payload.host, env = payload.env, tags = tags,
            )
        }
        return QueuedDbmBatch(organizationId, "metrics", metrics = metrics)
    }

    fun mapActivity(organizationId: Int, payload: DdDbmActivityPayload): QueuedDbmBatch {
        val tags = parseDdTagList(payload.tags)
        val activity = payload.activity.map { row ->
            QueuedDbmActivityEntry(
                dbHost = payload.dbHost, dbSystem = payload.dbSystem,
                dbName = row.dbName, dbUser = row.dbUser,
                querySignature = row.querySignature,
                statement = row.statement, state = row.state,
                waitEventType = row.waitEventType,
                waitEvent = row.waitEvent,
                blockingPids = row.blockingPids,
                durationNs = row.durationNs,
                timestampMs = row.timestamp?.let { it * MILLIS_PER_SECOND_LONG }
                    ?: System.currentTimeMillis(),
                host = payload.host, env = payload.env, tags = tags,
            )
        }
        return QueuedDbmBatch(organizationId, "activity", activity = activity)
    }

    fun enqueueQueries(organizationId: Int, payload: DdDbmQueryPayload, queueKey: String = DBM_QUEUE_KEY): Int {
        val batch = mapQueries(organizationId, payload)
        if (batch.queries.isEmpty()) return 0
        RedisConfig.sync().lpush(queueKey, json.encodeToString(batch))
        return batch.queries.size
    }

    fun enqueueMetrics(organizationId: Int, payload: DdDbmMetricsPayload, queueKey: String = DBM_QUEUE_KEY): Int {
        val batch = mapMetrics(organizationId, payload)
        if (batch.metrics.isEmpty()) return 0
        RedisConfig.sync().lpush(queueKey, json.encodeToString(batch))
        return batch.metrics.size
    }

    fun enqueueActivity(organizationId: Int, payload: DdDbmActivityPayload, queueKey: String = DBM_QUEUE_KEY): Int {
        val batch = mapActivity(organizationId, payload)
        if (batch.activity.isEmpty()) return 0
        RedisConfig.sync().lpush(queueKey, json.encodeToString(batch))
        return batch.activity.size
    }

    fun enqueueMetadata(organizationId: Int, payload: DdDbmMetadataPayload, queueKey: String = DBM_QUEUE_KEY): Int {
        val entry = QueuedDbmMetadataEntry(
            host = payload.host,
            dbSystem = payload.dbSystem,
            schemaJson = payload.schemaJson,
            explainPlanHash = payload.explainPlanHash,
            explainPlan = payload.explainPlan,
            timestampMs = System.currentTimeMillis(),
        )
        val batch = QueuedDbmBatch(organizationId, "metadata", metadata = listOf(entry))
        RedisConfig.sync().lpush(queueKey, json.encodeToString(batch))
        return 1
    }

    fun enqueueHealth(organizationId: Int, payload: DdDbmHealthPayload, queueKey: String = DBM_QUEUE_KEY): Int {
        val entry = QueuedDbmHealthEntry(
            host = payload.host,
            dbSystem = payload.dbSystem,
            agentVersion = payload.agentVersion,
            status = payload.status,
            checksRun = payload.checksRun,
            checksFailed = payload.checksFailed,
            hostName = payload.hostName,
            timestampMs = System.currentTimeMillis(),
        )
        val batch = QueuedDbmBatch(organizationId, "health", health = listOf(entry))
        RedisConfig.sync().lpush(queueKey, json.encodeToString(batch))
        return 1
    }

    suspend fun insertBatch(batch: QueuedDbmBatch) {
        when (batch.batchType) {
            "queries" -> insertQueries(batch)
            "metrics" -> insertMetrics(batch)
            "activity" -> insertActivity(batch)
            "metadata" -> insertMetadata(batch)
            "health" -> insertHealth(batch)
        }
    }

    @Suppress("LongMethod")
    private suspend fun insertQueries(batch: QueuedDbmBatch) {
        if (batch.queries.isEmpty()) return
        val rows = batch.queries.joinToString(",\n") { q ->
            val truncated = if (q.queryTruncated) "truncated" else "not_truncated"
            """(
                ${batch.organizationId}, '${escapeSql(q.dbHost)}',
                '${escapeSql(q.dbSystem)}', '${escapeSql(q.dbName)}',
                '${escapeSql(q.dbUser)}', '${escapeSql(q.querySignature)}',
                '${escapeSql(q.resourceHash)}', '${escapeSql(q.statement)}',
                '$truncated', ${q.durationNs}, ${q.rowsAffected},
                ${q.errorCode}, '${escapeSql(q.errorMessage)}',
                fromUnixTimestamp64Milli(${q.timestampMs}),
                '${escapeSql(q.host)}', '${escapeSql(q.env)}',
                '${escapeSql(q.service)}', ${mapToSqlMap(q.tags)}
            )"""
        }
        val insert = """
            INSERT INTO `$clickhouseDb`.dbm_queries (
                organization_id, db_host, db_system, db_name, db_user,
                query_signature, resource_hash, statement, query_truncated,
                duration_ns, rows_affected, error_code, error_message,
                timestamp, host, env, service, tags
            ) VALUES $rows
        """.trimIndent()
        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to insert DD DBM queries")
        }
        val totalBytes = batch.queries.sumOf { it.statement.length }
        usageTracking.recordOrgUsage(batch.organizationId, "dd_dbm", totalBytes)
    }

    private suspend fun insertMetrics(batch: QueuedDbmBatch) {
        if (batch.metrics.isEmpty()) return
        val rows = batch.metrics.joinToString(",\n") { m ->
            """(
                ${batch.organizationId}, '${escapeSql(m.dbHost)}',
                '${escapeSql(m.dbSystem)}', '${escapeSql(m.dbName)}',
                '${escapeSql(m.querySignature)}',
                fromUnixTimestamp64Milli(${m.timestampMs}),
                ${m.calls}, ${m.totalTimeNs}, ${m.rows},
                ${m.sharedBlksHit}, ${m.sharedBlksRead},
                '${escapeSql(m.host)}', '${escapeSql(m.env)}',
                ${mapToSqlMap(m.tags)}
            )"""
        }
        val insert = """
            INSERT INTO `$clickhouseDb`.dbm_metrics (
                organization_id, db_host, db_system, db_name,
                query_signature, timestamp, calls, total_time_ns, rows,
                shared_blks_hit, shared_blks_read, host, env, tags
            ) VALUES $rows
        """.trimIndent()
        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to insert DD DBM metrics")
        }
    }

    private suspend fun insertActivity(batch: QueuedDbmBatch) {
        if (batch.activity.isEmpty()) return
        val rows = batch.activity.joinToString(",\n") { a ->
            val pids = a.blockingPids.joinToString(",")
            """(
                ${batch.organizationId}, '${escapeSql(a.dbHost)}',
                '${escapeSql(a.dbSystem)}', '${escapeSql(a.dbName)}',
                '${escapeSql(a.dbUser)}', '${escapeSql(a.querySignature)}',
                '${escapeSql(a.statement)}', '${escapeSql(a.state)}',
                '${escapeSql(a.waitEventType)}', '${escapeSql(a.waitEvent)}',
                [$pids], ${a.durationNs},
                fromUnixTimestamp64Milli(${a.timestampMs}),
                '${escapeSql(a.host)}', '${escapeSql(a.env)}',
                ${mapToSqlMap(a.tags)}
            )"""
        }
        val insert = """
            INSERT INTO `$clickhouseDb`.dbm_activity (
                organization_id, db_host, db_system, db_name, db_user,
                query_signature, statement, state, wait_event_type,
                wait_event, blocking_pids, duration_ns, timestamp,
                host, env, tags
            ) VALUES $rows
        """.trimIndent()
        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to insert DD DBM activity")
        }
    }

    private suspend fun insertMetadata(batch: QueuedDbmBatch) {
        if (batch.metadata.isEmpty()) return
        val rows = batch.metadata.joinToString(",\n") { m ->
            """(
                ${batch.organizationId},
                '${escapeSql(m.host)}',
                '${escapeSql(m.dbSystem)}',
                '${escapeSql(m.schemaJson)}',
                '${escapeSql(m.explainPlanHash)}',
                '${escapeSql(m.explainPlan)}',
                fromUnixTimestamp64Milli(${m.timestampMs})
            )"""
        }
        val insert = """
            INSERT INTO `$clickhouseDb`.dbm_metadata (
                organization_id, host, db_system,
                schema_json, explain_plan_hash, explain_plan,
                collected_at
            ) VALUES $rows
        """.trimIndent()
        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to insert DD DBM metadata")
        }
    }

    private suspend fun insertHealth(batch: QueuedDbmBatch) {
        if (batch.health.isEmpty()) return
        val rows = batch.health.joinToString(",\n") { h ->
            val st = when (h.status) {
                "warn" -> "warn"
                "error" -> "error"
                else -> "ok"
            }
            """(
                ${batch.organizationId},
                '${escapeSql(h.host)}',
                '${escapeSql(h.dbSystem)}',
                '${escapeSql(h.agentVersion)}',
                '$st',
                ${h.checksRun},
                ${h.checksFailed},
                '${escapeSql(h.hostName)}',
                fromUnixTimestamp64Milli(${h.timestampMs})
            )"""
        }
        val insert = """
            INSERT INTO `$clickhouseDb`.dbm_health (
                organization_id, host, db_system,
                agent_version, status, checks_run,
                checks_failed, host_name, timestamp
            ) VALUES $rows
        """.trimIndent()
        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to insert DD DBM health")
        }
    }

    fun decodeBatch(encoded: String): QueuedDbmBatch = json.decodeFromString(encoded)

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

    private fun mapToSqlMap(map: Map<String, String>): String {
        if (map.isEmpty()) return "map()"
        val entries = map.entries.joinToString(", ") { (k, v) ->
            "'${escapeSql(k)}', '${escapeSql(v)}'"
        }
        return "map($entries)"
    }
}
