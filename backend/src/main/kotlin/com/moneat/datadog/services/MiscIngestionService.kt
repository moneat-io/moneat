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
import com.moneat.datadog.models.DdContainerImagePayload
import com.moneat.datadog.models.DdDataLineagePayload
import com.moneat.datadog.models.DdDataStreamsPayload
import com.moneat.datadog.models.DdPipelineStatsPayload
import com.moneat.datadog.models.DdSbomPayload
import com.moneat.datadog.models.DdSymbolDbPayload
import com.moneat.datadog.models.DdSyntheticsPayload
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private const val MISC_QUEUE_KEY = "moneat:dd:misc:queue"

@Serializable
data class QueuedMiscBatch(
    @SerialName("organization_id") val organizationId: Int,
    @SerialName("batch_type") val batchType: String,
    @SerialName("symbol_db") val symbolDb: List<QueuedSymbolDbEntry> = emptyList(),
    @SerialName("pipeline_stats") val pipelineStats: List<QueuedPipelineStatEntry> = emptyList(),
    @SerialName("data_lineage") val dataLineage: List<QueuedDataLineageEntry> = emptyList(),
    @SerialName("data_streams") val dataStreams: List<QueuedDataStreamEntry> = emptyList(),
    val synthetics: List<QueuedSyntheticEntry> = emptyList(),
    @SerialName("container_images") val containerImages: List<QueuedContainerImageEntry> = emptyList(),
    @SerialName("sbom_packages") val sbomPackages: List<QueuedSbomEntry> = emptyList(),
)

@Serializable
data class QueuedSymbolDbEntry(
    val service: String = "",
    val env: String = "",
    val language: String = "",
    val version: String = "",
    val symbols: String = "",
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Serializable
data class QueuedPipelineStatEntry(
    @SerialName("pipeline_id") val pipelineId: String = "",
    @SerialName("stage_name") val stageName: String = "",
    @SerialName("in_count") val inCount: Long = 0,
    @SerialName("out_count") val outCount: Long = 0,
    @SerialName("drop_count") val dropCount: Long = 0,
    @SerialName("error_count") val errorCount: Long = 0,
    val host: String = "",
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Serializable
data class QueuedDataLineageEntry(
    @SerialName("run_id") val runId: String = "",
    @SerialName("job_name") val jobName: String = "",
    val namespace: String = "",
    val inputs: List<String> = emptyList(),
    val outputs: List<String> = emptyList(),
    @SerialName("event_type") val eventType: String = "",
    val facets: String = "",
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Serializable
data class QueuedDataStreamEntry(
    @SerialName("pipeline_id") val pipelineId: String = "",
    @SerialName("stage_name") val stageName: String = "",
    @SerialName("latency_ns") val latencyNs: Long = 0,
    @SerialName("payload_size") val payloadSize: Long = 0,
    val direction: String = "in",
    val tags: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Serializable
data class QueuedSyntheticEntry(
    @SerialName("test_id") val testId: String = "",
    @SerialName("test_name") val testName: String = "",
    @SerialName("test_type") val testType: String = "api",
    val status: String = "passed",
    @SerialName("probe_dc") val probeDc: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("error_message") val errorMessage: String = "",
    val timings: Map<String, Double> = emptyMap(),
    val tags: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Serializable
data class QueuedContainerImageEntry(
    @SerialName("image_name") val imageName: String = "",
    @SerialName("image_tag") val imageTag: String = "",
    val digest: String = "",
    val registry: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val os: String = "",
    val architecture: String = "",
    val layers: Int = 0,
    val tags: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Serializable
data class QueuedSbomEntry(
    val host: String = "",
    @SerialName("container_id") val containerId: String = "",
    @SerialName("image_name") val imageName: String = "",
    @SerialName("package_name") val packageName: String = "",
    @SerialName("package_version") val packageVersion: String = "",
    @SerialName("package_type") val packageType: String = "",
    @SerialName("cve_ids") val cveIds: List<String> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Suppress("TooManyFunctions")
object MiscIngestionService {
    private val clickhouseDb by lazy { ClickHouseClient.getDatabase() }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun enqueueSymbolDb(orgId: Int, payload: DdSymbolDbPayload) {
        val entry = QueuedSymbolDbEntry(
            service = payload.service,
            env = payload.env,
            language = payload.language,
            version = payload.version,
            symbols = payload.symbols,
            timestampMs = System.currentTimeMillis(),
        )
        val batch = QueuedMiscBatch(orgId, "symbol_db", symbolDb = listOf(entry))
        RedisConfig.sync().lpush(MISC_QUEUE_KEY, json.encodeToString(batch))
    }

    fun enqueuePipelineStats(orgId: Int, payload: DdPipelineStatsPayload): Int {
        val now = System.currentTimeMillis()
        val entries = payload.stats.map { s ->
            QueuedPipelineStatEntry(
                pipelineId = payload.pipelineId,
                stageName = s.stageName,
                inCount = s.inCount,
                outCount = s.outCount,
                dropCount = s.dropCount,
                errorCount = s.errorCount,
                host = payload.host,
                timestampMs = now,
            )
        }
        if (entries.isEmpty()) return 0
        val batch = QueuedMiscBatch(orgId, "pipeline_stats", pipelineStats = entries)
        RedisConfig.sync().lpush(MISC_QUEUE_KEY, json.encodeToString(batch))
        return entries.size
    }

    fun enqueueDataLineage(orgId: Int, payload: DdDataLineagePayload) {
        val entry = QueuedDataLineageEntry(
            runId = payload.runId,
            jobName = payload.jobName,
            namespace = payload.namespace,
            inputs = payload.inputs,
            outputs = payload.outputs,
            eventType = payload.eventType,
            facets = payload.facets,
            timestampMs = System.currentTimeMillis(),
        )
        val batch = QueuedMiscBatch(orgId, "data_lineage", dataLineage = listOf(entry))
        RedisConfig.sync().lpush(MISC_QUEUE_KEY, json.encodeToString(batch))
    }

    fun enqueueDataStreams(orgId: Int, payload: DdDataStreamsPayload): Int {
        val now = System.currentTimeMillis()
        val tags = parseDdTagList(payload.tags)
        val entries = payload.stats.map { s ->
            QueuedDataStreamEntry(
                pipelineId = payload.pipelineId,
                stageName = s.stageName,
                latencyNs = s.latencyNs,
                payloadSize = s.payloadSize,
                direction = s.direction,
                tags = tags,
                timestampMs = now,
            )
        }
        if (entries.isEmpty()) return 0
        val batch = QueuedMiscBatch(orgId, "data_streams", dataStreams = entries)
        RedisConfig.sync().lpush(MISC_QUEUE_KEY, json.encodeToString(batch))
        return entries.size
    }

    fun enqueueSynthetics(orgId: Int, payload: DdSyntheticsPayload): Int {
        val now = System.currentTimeMillis()
        val entries = payload.results.map { r ->
            QueuedSyntheticEntry(
                testId = r.testId, testName = r.testName,
                testType = r.testType, status = r.status,
                probeDc = r.probeDc, durationMs = r.durationMs,
                errorMessage = r.errorMessage,
                timings = r.timings,
                tags = parseDdTagList(r.tags),
                timestampMs = now,
            )
        }
        if (entries.isEmpty()) return 0
        val batch = QueuedMiscBatch(orgId, "synthetics", synthetics = entries)
        RedisConfig.sync().lpush(MISC_QUEUE_KEY, json.encodeToString(batch))
        return entries.size
    }

    fun enqueueContainerImage(orgId: Int, payload: DdContainerImagePayload) {
        val entry = QueuedContainerImageEntry(
            imageName = payload.imageName, imageTag = payload.imageTag,
            digest = payload.digest, registry = payload.registry,
            sizeBytes = payload.sizeBytes, os = payload.os,
            architecture = payload.architecture, layers = payload.layers,
            tags = parseDdTagList(payload.tags),
            timestampMs = System.currentTimeMillis(),
        )
        val batch = QueuedMiscBatch(orgId, "container_images", containerImages = listOf(entry))
        RedisConfig.sync().lpush(MISC_QUEUE_KEY, json.encodeToString(batch))
    }

    fun enqueueSbom(orgId: Int, payload: DdSbomPayload): Int {
        val now = System.currentTimeMillis()
        val tags = parseDdTagList(payload.tags)
        val entries = payload.packages.map { p ->
            QueuedSbomEntry(
                host = payload.host, containerId = payload.containerId,
                imageName = payload.imageName,
                packageName = p.name, packageVersion = p.version,
                packageType = p.type, cveIds = p.cveIds,
                tags = tags, timestampMs = now,
            )
        }
        if (entries.isEmpty()) return 0
        val batch = QueuedMiscBatch(orgId, "sbom_packages", sbomPackages = entries)
        RedisConfig.sync().lpush(MISC_QUEUE_KEY, json.encodeToString(batch))
        return entries.size
    }

    @Suppress("LongMethod")
    suspend fun insertBatch(batch: QueuedMiscBatch) {
        when (batch.batchType) {
            "symbol_db" -> insertSymbolDb(batch)
            "pipeline_stats" -> insertPipelineStats(batch)
            "data_lineage" -> insertDataLineage(batch)
            "data_streams" -> insertDataStreams(batch)
            "synthetics" -> insertSynthetics(batch)
            "container_images" -> insertContainerImages(batch)
            "sbom_packages" -> insertSbomPackages(batch)
        }
    }

    private suspend fun insertSymbolDb(batch: QueuedMiscBatch) {
        if (batch.symbolDb.isEmpty()) return
        val rows = batch.symbolDb.joinToString(",\n") { s ->
            """(
                ${batch.organizationId},
                '${escapeSql(s.service)}', '${escapeSql(s.env)}',
                '${escapeSql(s.language)}', '${escapeSql(s.version)}',
                '${escapeSql(s.symbols)}',
                fromUnixTimestamp64Milli(${s.timestampMs})
            )"""
        }
        executeInsert(
            """INSERT INTO `$clickhouseDb`.symbol_db (
                organization_id, service, env, language,
                version, symbols, timestamp
            ) VALUES $rows""",
            "symbol_db"
        )
    }

    private suspend fun insertPipelineStats(batch: QueuedMiscBatch) {
        if (batch.pipelineStats.isEmpty()) return
        val rows = batch.pipelineStats.joinToString(",\n") { p ->
            """(
                ${batch.organizationId},
                '${escapeSql(p.pipelineId)}',
                '${escapeSql(p.stageName)}',
                ${p.inCount}, ${p.outCount},
                ${p.dropCount}, ${p.errorCount},
                '${escapeSql(p.host)}',
                fromUnixTimestamp64Milli(${p.timestampMs})
            )"""
        }
        executeInsert(
            """INSERT INTO `$clickhouseDb`.pipeline_stats (
                organization_id, pipeline_id, stage_name,
                in_count, out_count, drop_count, error_count,
                host, timestamp
            ) VALUES $rows""",
            "pipeline_stats"
        )
    }

    private suspend fun insertDataLineage(batch: QueuedMiscBatch) {
        if (batch.dataLineage.isEmpty()) return
        val rows = batch.dataLineage.joinToString(",\n") { l ->
            val inputs = l.inputs.joinToString(",") { "'${escapeSql(it)}'" }
            val outputs = l.outputs.joinToString(",") { "'${escapeSql(it)}'" }
            """(
                ${batch.organizationId},
                '${escapeSql(l.runId)}', '${escapeSql(l.jobName)}',
                '${escapeSql(l.namespace)}',
                [$inputs], [$outputs],
                '${escapeSql(l.eventType)}',
                '${escapeSql(l.facets)}',
                fromUnixTimestamp64Milli(${l.timestampMs})
            )"""
        }
        executeInsert(
            """INSERT INTO `$clickhouseDb`.data_lineage (
                organization_id, run_id, job_name, namespace,
                inputs, outputs, event_type, facets, timestamp
            ) VALUES $rows""",
            "data_lineage"
        )
    }

    private suspend fun insertDataStreams(batch: QueuedMiscBatch) {
        if (batch.dataStreams.isEmpty()) return
        val rows = batch.dataStreams.joinToString(",\n") { d ->
            val dir = if (d.direction == "out") "out" else "in"
            """(
                ${batch.organizationId},
                '${escapeSql(d.pipelineId)}',
                '${escapeSql(d.stageName)}',
                ${d.latencyNs}, ${d.payloadSize},
                '$dir',
                ${mapToSqlMap(d.tags)},
                fromUnixTimestamp64Milli(${d.timestampMs})
            )"""
        }
        executeInsert(
            """INSERT INTO `$clickhouseDb`.data_streams (
                organization_id, pipeline_id, stage_name,
                latency_ns, payload_size, direction,
                tags, timestamp
            ) VALUES $rows""",
            "data_streams"
        )
    }

    private suspend fun insertSynthetics(batch: QueuedMiscBatch) {
        if (batch.synthetics.isEmpty()) return
        val rows = batch.synthetics.joinToString(",\n") { s ->
            val testType = when (s.testType) {
                "browser" -> "browser"
                "multistep" -> "multistep"
                else -> "api"
            }
            val status = when (s.status) {
                "failed" -> "failed"
                "skipped" -> "skipped"
                else -> "passed"
            }
            val timingsMap = if (s.timings.isEmpty()) {
                "map()"
            } else {
                val entries = s.timings.entries.joinToString(", ") { (k, v) ->
                    "'${escapeSql(k)}', $v"
                }
                "map($entries)"
            }
            """(
                ${batch.organizationId},
                '${escapeSql(s.testId)}', '${escapeSql(s.testName)}',
                '$testType', '$status',
                '${escapeSql(s.probeDc)}', ${s.durationMs},
                '${escapeSql(s.errorMessage)}',
                $timingsMap,
                ${mapToSqlMap(s.tags)},
                fromUnixTimestamp64Milli(${s.timestampMs})
            )"""
        }
        executeInsert(
            """INSERT INTO `$clickhouseDb`.synthetic_results (
                organization_id, test_id, test_name,
                test_type, status, probe_dc, duration_ms,
                error_message, timings, tags, timestamp
            ) VALUES $rows""",
            "synthetic_results"
        )
    }

    private suspend fun insertContainerImages(batch: QueuedMiscBatch) {
        if (batch.containerImages.isEmpty()) return
        val rows = batch.containerImages.joinToString(",\n") { c ->
            """(
                ${batch.organizationId},
                '${escapeSql(c.imageName)}', '${escapeSql(c.imageTag)}',
                '${escapeSql(c.digest)}', '${escapeSql(c.registry)}',
                ${c.sizeBytes}, '${escapeSql(c.os)}',
                '${escapeSql(c.architecture)}', ${c.layers},
                ${mapToSqlMap(c.tags)},
                fromUnixTimestamp64Milli(${c.timestampMs})
            )"""
        }
        executeInsert(
            """INSERT INTO `$clickhouseDb`.container_images (
                organization_id, image_name, image_tag,
                digest, registry, size_bytes, os,
                architecture, layers, tags, collected_at
            ) VALUES $rows""",
            "container_images"
        )
    }

    private suspend fun insertSbomPackages(batch: QueuedMiscBatch) {
        if (batch.sbomPackages.isEmpty()) return
        val rows = batch.sbomPackages.joinToString(",\n") { p ->
            val cves = p.cveIds.joinToString(",") { "'${escapeSql(it)}'" }
            """(
                ${batch.organizationId},
                '${escapeSql(p.host)}', '${escapeSql(p.containerId)}',
                '${escapeSql(p.imageName)}',
                '${escapeSql(p.packageName)}',
                '${escapeSql(p.packageVersion)}',
                '${escapeSql(p.packageType)}',
                [$cves],
                ${mapToSqlMap(p.tags)},
                fromUnixTimestamp64Milli(${p.timestampMs})
            )"""
        }
        executeInsert(
            """INSERT INTO `$clickhouseDb`.sbom_packages (
                organization_id, host, container_id,
                image_name, package_name, package_version,
                package_type, cve_ids, tags, collected_at
            ) VALUES $rows""",
            "sbom_packages"
        )
    }

    fun decodeBatch(encoded: String): QueuedMiscBatch =
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

    private fun mapToSqlMap(map: Map<String, String>): String {
        if (map.isEmpty()) return "map()"
        val entries = map.entries.joinToString(", ") { (k, v) ->
            "'${escapeSql(k)}', '${escapeSql(v)}'"
        }
        return "map($entries)"
    }
}
