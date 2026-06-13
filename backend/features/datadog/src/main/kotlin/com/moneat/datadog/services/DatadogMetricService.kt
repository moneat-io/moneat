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
import com.moneat.datadog.models.DatadogMetricSeriesV1
import com.moneat.datadog.models.DatadogMetricV1
import com.moneat.datadog.models.DatadogSketchPayload
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.monitor.services.InfraMetricRollupRow
import com.moneat.monitor.services.InfraTelemetryRollups
import com.moneat.monitoring.OperationalMetrics
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import com.moneat.utils.TimeConstants.MILLIS_PER_SECOND_LONG
import com.moneat.utils.formatClickHouseDateTime64MillisUtc
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private const val METRIC_QUEUE_KEY = "moneat:metrics:queue"
private const val ERROR_BODY_MAX_LEN = 600

@Serializable
data class QueuedMetricBatch(
    @SerialName("organization_id") val organizationId: Long,
    @SerialName("project_id") val projectId: Long? = null,
    val metrics: List<QueuedMetricEntry>
)

@Serializable
data class QueuedMetricEntry(
    val name: String,
    val type: String,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val value: Double,
    val host: String = "",
    val tags: Map<String, String> = emptyMap(),
    val unit: String = "",
    @SerialName("source_type_name") val sourceTypeName: String = ""
)

private data class MetricInsertRow(
    val organizationId: Long,
    val projectId: Long?,
    val metric: QueuedMetricEntry,
)

@Serializable
private data class MetricJsonEachRow(
    @SerialName("organization_id") val organizationId: Long,
    @SerialName("service_id") val projectId: Long,
    @SerialName("project_id") val deprecatedProjectId: Long,
    @SerialName("metric_name") val metricName: String,
    @SerialName("metric_type") val metricType: String,
    val timestamp: String,
    val value: Double,
    val host: String,
    val tags: Map<String, String>,
    val unit: String,
    @SerialName("source_type_name") val sourceTypeName: String,
    val source: String,
)

@Serializable
data class QueuedSketchBatch(
    @SerialName("organization_id") val organizationId: Long,
    val sketches: List<QueuedSketchEntry>,
    @SerialName("project_id") val projectId: Long? = null,
)

@Serializable
data class QueuedSketchEntry(
    val name: String,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val host: String = "",
    val tags: Map<String, String> = emptyMap(),
    val count: Long = 0,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val avg: Double = 0.0,
    val sum: Double = 0.0,
    val k: List<Int> = emptyList(),
    val n: List<Int> = emptyList()
)

object DatadogMetricService {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun mapV1Series(
        organizationId: Long,
        payload: DatadogMetricSeriesV1,
        projectId: Long? = null,
    ): QueuedMetricBatch {
        val metrics = payload.series.flatMap { series ->
            flattenV1Points(series)
        }

        return QueuedMetricBatch(
            organizationId = organizationId,
            projectId = projectId,
            metrics = metrics
        )
    }

    internal fun flattenV1Points(
        series: DatadogMetricV1
    ): List<QueuedMetricEntry> {
        val tags = parseDdTagList(series.tags)
        val metricType = normalizeMetricType(series.type)

        return series.points.mapNotNull { point ->
            if (point.size < 2) return@mapNotNull null
            val timestampMs = (point[0] * MILLIS_PER_SECOND_LONG).toLong()
            val value = point[1]
            QueuedMetricEntry(
                name = series.metric,
                type = metricType,
                timestampMs = timestampMs,
                value = value,
                host = series.host,
                tags = tags,
                unit = series.unit,
                sourceTypeName = series.sourceTypeName
            )
        }
    }

    fun mapSketches(
        organizationId: Long,
        payload: DatadogSketchPayload,
        projectId: Long? = null,
    ): QueuedSketchBatch {
        val sketches = payload.sketches.flatMap { sketch ->
            val tags = parseDdTagList(sketch.tags)
            sketch.distributions.map { dist ->
                QueuedSketchEntry(
                    name = sketch.metric,
                    timestampMs = dist.ts * MILLIS_PER_SECOND_LONG,
                    host = sketch.host,
                    tags = tags,
                    count = dist.cnt,
                    min = dist.min,
                    max = dist.max,
                    avg = dist.avg,
                    sum = dist.sum,
                    k = dist.k,
                    n = dist.n.map { it }
                )
            }
        }

        return QueuedSketchBatch(
            organizationId = organizationId,
            sketches = sketches,
            projectId = projectId,
        )
    }

    suspend fun enqueueMetrics(
        organizationId: Long,
        payload: DatadogMetricSeriesV1,
        projectId: Long? = null,
        queueKey: String = METRIC_QUEUE_KEY,
    ): Int {
        val batch = mapV1Series(organizationId, payload, projectId)
        if (batch.metrics.isEmpty()) return 0
        val message = json.encodeToString(batch)
        IngestionQueueClient.enqueue(IngestionPipeline.DD_METRICS, queueKey, message)
        OperationalMetrics.recordDatadogMetricPayloadQueued(batch.metrics.size)
        logger.debug {
            "Enqueued ${batch.metrics.size} DD metrics for org $organizationId"
        }
        return batch.metrics.size
    }

    suspend fun insertMetricBatch(batch: QueuedMetricBatch) {
        if (batch.metrics.isEmpty()) return
        val normalizedBatch = normalizeMetricBatchBestEffort(batch)
        insertMetricRows(rowsForBatch(normalizedBatch))
        insertMetricRollupsBestEffort(normalizedBatch)
    }

    suspend fun insertMetricBatches(batches: List<QueuedMetricBatch>) {
        val nonEmptyBatches = batches.filter { it.metrics.isNotEmpty() }
        if (nonEmptyBatches.isEmpty()) return
        val normalizedBatches = nonEmptyBatches.map { normalizeMetricBatchBestEffort(it) }
        insertMetricRows(normalizedBatches.flatMap(::rowsForBatch))
        normalizedBatches.forEach { insertMetricRollupsBestEffort(it) }
    }

    private suspend fun insertMetricRows(rows: List<MetricInsertRow>) {
        if (rows.isEmpty()) return
        val db = ClickHouseClient.getDatabase()

        val encodedRows = rows.joinToString("\n") { row ->
            json.encodeToString(row.toJsonEachRow())
        }

        val insert = """
            INSERT INTO `$db`.metrics (
                organization_id, service_id, project_id, metric_name, metric_type, timestamp,
                value, host, tags, unit, source_type_name,
                source
            ) FORMAT JSONEachRow
            $encodedRows
        """.trimIndent()

        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw IllegalStateException(
                "Failed to insert DD metrics: ${errorBody.take(ERROR_BODY_MAX_LEN)}"
            )
        }
    }

    private fun rowsForBatch(batch: QueuedMetricBatch): List<MetricInsertRow> =
        batch.metrics.map { metric ->
            MetricInsertRow(
                organizationId = batch.organizationId,
                projectId = batch.projectId,
                metric = metric,
            )
        }

    private fun MetricInsertRow.toJsonEachRow(): MetricJsonEachRow =
        MetricJsonEachRow(
            organizationId = organizationId,
            projectId = projectId ?: 0L,
            deprecatedProjectId = projectId ?: 0L,
            metricName = metric.name,
            metricType = metric.type,
            timestamp = formatClickHouseDateTime64MillisUtc(metric.timestampMs),
            value = metric.value,
            host = metric.host,
            tags = metric.tags,
            unit = metric.unit,
            sourceTypeName = metric.sourceTypeName,
            source = "datadog",
        )

    suspend fun insertSketchBatch(batch: QueuedSketchBatch) {
        if (batch.sketches.isEmpty()) return
        val db = ClickHouseClient.getDatabase()

        val rows = batch.sketches.joinToString(",\n") { s ->
            val tagsMap = s.tags.entries.joinToString(",") { (k, v) ->
                "'${escapeSql(k)}','${escapeSql(v)}'"
            }
            val kArray = s.k.joinToString(",")
            val nArray = s.n.joinToString(",")
            """(
                ${batch.organizationId},
                ${batch.projectId ?: 0L},
                ${batch.projectId ?: 0L},
                '${escapeSql(s.name)}',
                fromUnixTimestamp64Milli(${s.timestampMs}),
                '${escapeSql(s.host)}',
                map($tagsMap),
                ${s.count},
                ${s.min},
                ${s.max},
                ${s.avg},
                ${s.sum},
                [$kArray],
                [$nArray]
            )"""
        }

        val insert = """
            INSERT INTO `$db`.metric_sketches (
                organization_id, service_id, project_id, metric_name, timestamp, host, tags,
                sketch_count, sketch_min, sketch_max, sketch_avg,
                sketch_sum, sketch_k, sketch_n
            ) VALUES $rows
        """.trimIndent()

        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw IllegalStateException(
                "Failed to insert DD sketches: ${errorBody.take(ERROR_BODY_MAX_LEN)}"
            )
        }
    }

    fun decodeMetricBatch(encoded: String): QueuedMetricBatch {
        return json.decodeFromString(encoded)
    }

    fun decodeSketchBatch(encoded: String): QueuedSketchBatch {
        return json.decodeFromString(encoded)
    }

    internal fun parseDdTagList(
        tags: List<String>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        tags.forEach { tag ->
            val colonIdx = tag.indexOf(':')
            if (colonIdx > 0) {
                result[tag.substring(0, colonIdx)] =
                    tag.substring(colonIdx + 1)
            } else if (tag.isNotEmpty()) {
                result[tag] = ""
            }
        }
        return result
    }

    private fun normalizeMetricType(type: String): String {
        return when (type.lowercase()) {
            "gauge" -> "gauge"
            "rate" -> "rate"
            "count" -> "count"
            else -> "gauge"
        }
    }

    private fun QueuedMetricEntry.withNormalizedHost(): QueuedMetricEntry {
        val hostName = hostLookupName()
        val normalizedTags = if (tags["host_id"].isNullOrBlank()) tags - "host_id" else tags
        return copy(host = host.ifBlank { hostName }, tags = normalizedTags)
    }

    private suspend fun normalizeMetricBatchBestEffort(batch: QueuedMetricBatch): QueuedMetricBatch {
        try {
            return batch.copy(metrics = enrichMetrics(batch))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) {
                "Failed to resolve Datadog host ids for org ${batch.organizationId} " +
                    "(${batch.metrics.size} metrics); raw metrics will keep host names only"
            }
            return batch.copy(metrics = batch.metrics.map { it.withNormalizedHost() })
        }
    }

    private suspend fun enrichMetrics(batch: QueuedMetricBatch): List<QueuedMetricEntry> {
        val hostnamesNeedingIds = batch.metrics
            .filter { it.tags["host_id"].isNullOrBlank() }
            .map { it.hostLookupName() }
            .filter { it.isNotBlank() }
            .toSet()
        val hostIdsByName = if (hostnamesNeedingIds.isEmpty()) {
            emptyMap()
        } else {
            DatadogHostService.resolveHostIds(batch.organizationId.toInt(), hostnamesNeedingIds)
        }
        return batch.metrics.map { metric ->
            val hostName = metric.hostLookupName()
            val existingHostId = metric.tags["host_id"]?.takeUnless { it.isBlank() }
            val hostId = existingHostId ?: hostIdsByName[hostName]?.toString()
            val tags = if (hostId != null) {
                metric.tags + ("host_id" to hostId)
            } else {
                metric.tags - "host_id"
            }
            metric.copy(
                host = metric.host.ifBlank { hostName },
                tags = tags,
            )
        }
    }

    private suspend fun insertMetricRollupsBestEffort(batch: QueuedMetricBatch) {
        try {
            InfraTelemetryRollups.insertMetricRollups(
                batch.metrics.map { metric ->
                    InfraMetricRollupRow(
                        organizationId = batch.organizationId,
                        metricName = metric.name,
                        timestampMs = metric.timestampMs,
                        value = metric.value,
                        host = metric.host,
                        tags = metric.tags,
                        unit = metric.unit,
                        source = "datadog",
                    )
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) {
                "Failed to write Datadog metric rollups for org ${batch.organizationId} " +
                    "(${batch.metrics.size} metrics); raw metrics were already written"
            }
        }
    }

    private fun QueuedMetricEntry.hostLookupName(): String =
        host.ifBlank {
            tags["host"] ?: tags["host.name"] ?: tags["hostname"] ?: ""
        }
}
