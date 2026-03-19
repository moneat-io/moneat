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

package com.moneat.otlp.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.otlp.OtlpParsingUtils
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class OtlpMetricInsert(
    val organizationId: Long,
    val metricName: String,
    val metricType: String,
    val description: String,
    val unit: String,
    val timestampMs: Long,
    val value: Double,
    val isMonotonic: Int,
    val aggregationTemporality: String,
    val histCount: Long,
    val histSum: Double,
    val histMin: Double,
    val histMax: Double,
    val histBucketCounts: List<Long>,
    val histExplicitBounds: List<Double>,
    val tags: Map<String, String>,
    val resourceAttributes: Map<String, String>,
    val service: String,
    val env: String,
    val host: String,
)

@Serializable
data class QueuedOtlpMetricsBatch(
    val organizationId: Long,
    val metrics: List<OtlpMetricInsert>
)

class OtlpMetricsService(
    private val usageTracking: UsageTrackingService = UsageTrackingService(),
) {
    private val clickhouseDb = ClickHouseClient.getDatabase()

    fun parseOtlpMetricsJson(payload: String): List<OtlpMetricInsert>? {
        val parsed =
            try {
                json.parseToJsonElement(payload).jsonObject
            } catch (e: Exception) {
                logger.warn(e) { "Invalid OTLP metrics JSON payload" }
                return null
            }

        val resourceMetrics = parsed["resourceMetrics"]?.jsonArray ?: return null
        val results = mutableListOf<OtlpMetricInsert>()

        resourceMetrics.forEach { rmElement ->
            val rm = rmElement.jsonObject
            val resourceCtx = OtlpParsingUtils.extractResourceContext(
                rm["resource"]?.jsonObject
            )
            val scopeMetrics = rm["scopeMetrics"]?.jsonArray
                ?: rm["instrumentationLibraryMetrics"]?.jsonArray
                ?: JsonArray(emptyList())

            scopeMetrics.forEach { smElement ->
                val sm = smElement.jsonObject
                val metricsArray = OtlpParsingUtils.safeJsonArray(sm["metrics"])

                metricsArray.forEach { metricElement ->
                    val metric = metricElement.jsonObject
                    val name = metric["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val description = metric["description"]?.jsonPrimitive?.contentOrNull ?: ""
                    val unit = metric["unit"]?.jsonPrimitive?.contentOrNull ?: ""

                    when {
                        metric.containsKey("gauge") -> {
                            parseGaugeDataPoints(
                                metric["gauge"]!!.jsonObject,
                                name,
                                description,
                                unit,
                                resourceCtx,
                                results
                            )
                        }
                        metric.containsKey("sum") -> {
                            parseSumDataPoints(
                                metric["sum"]!!.jsonObject,
                                name,
                                description,
                                unit,
                                resourceCtx,
                                results
                            )
                        }
                        metric.containsKey("histogram") -> {
                            parseHistogramDataPoints(
                                metric["histogram"]!!.jsonObject,
                                name,
                                description,
                                unit,
                                resourceCtx,
                                results
                            )
                        }
                        metric.containsKey("exponentialHistogram") -> {
                            parseExpHistogramDataPoints(
                                metric["exponentialHistogram"]!!.jsonObject,
                                name,
                                description,
                                unit,
                                resourceCtx,
                                results
                            )
                        }
                        metric.containsKey("summary") -> {
                            parseSummaryDataPoints(
                                metric["summary"]!!.jsonObject,
                                name,
                                description,
                                unit,
                                resourceCtx,
                                results
                            )
                        }
                    }
                }
            }
        }

        return results
    }

    private fun parseGaugeDataPoints(
        gauge: kotlinx.serialization.json.JsonObject,
        name: String,
        description: String,
        unit: String,
        resourceCtx: com.moneat.otlp.ResourceContext,
        results: MutableList<OtlpMetricInsert>
    ) {
        val dataPoints = OtlpParsingUtils.safeJsonArray(gauge["dataPoints"])
        dataPoints.forEach { dp ->
            val dpObj = dp.jsonObject
            val attrs = OtlpParsingUtils.attributesToMap(dpObj["attributes"])
            val tsNs = dpObj["timeUnixNano"]?.jsonPrimitive?.longOrNull ?: 0L
            val value = dpObj["asDouble"]?.jsonPrimitive?.doubleOrNull
                ?: dpObj["asInt"]?.jsonPrimitive?.longOrNull?.toDouble() ?: 0.0

            results += buildMetricInsert(
                name, "gauge", description, unit, tsNs, value,
                attrs, resourceCtx
            )
        }
    }

    private fun parseSumDataPoints(
        sum: kotlinx.serialization.json.JsonObject,
        name: String,
        description: String,
        unit: String,
        resourceCtx: com.moneat.otlp.ResourceContext,
        results: MutableList<OtlpMetricInsert>
    ) {
        val isMonotonic = sum["isMonotonic"]?.jsonPrimitive?.contentOrNull == "true"
        val temporality = mapAggregationTemporality(
            sum["aggregationTemporality"]?.jsonPrimitive?.intOrNull ?: 0
        )
        val dataPoints = OtlpParsingUtils.safeJsonArray(sum["dataPoints"])
        dataPoints.forEach { dp ->
            val dpObj = dp.jsonObject
            val attrs = OtlpParsingUtils.attributesToMap(dpObj["attributes"])
            val tsNs = dpObj["timeUnixNano"]?.jsonPrimitive?.longOrNull ?: 0L
            val value = dpObj["asDouble"]?.jsonPrimitive?.doubleOrNull
                ?: dpObj["asInt"]?.jsonPrimitive?.longOrNull?.toDouble() ?: 0.0

            results += buildMetricInsert(
                name, "sum", description, unit, tsNs, value,
                attrs, resourceCtx,
                isMonotonic = if (isMonotonic) 1 else 0,
                aggregationTemporality = temporality
            )
        }
    }

    private fun parseHistogramDataPoints(
        histogram: kotlinx.serialization.json.JsonObject,
        name: String,
        description: String,
        unit: String,
        resourceCtx: com.moneat.otlp.ResourceContext,
        results: MutableList<OtlpMetricInsert>
    ) {
        val temporality = mapAggregationTemporality(
            histogram["aggregationTemporality"]?.jsonPrimitive?.intOrNull ?: 0
        )
        val dataPoints = OtlpParsingUtils.safeJsonArray(histogram["dataPoints"])
        dataPoints.forEach { dp ->
            val dpObj = dp.jsonObject
            val attrs = OtlpParsingUtils.attributesToMap(dpObj["attributes"])
            val tsNs = dpObj["timeUnixNano"]?.jsonPrimitive?.longOrNull ?: 0L
            val count = dpObj["count"]?.jsonPrimitive?.longOrNull ?: 0L
            val sum = dpObj["sum"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val min = dpObj["min"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val max = dpObj["max"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val bucketCounts = dpObj["bucketCounts"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.longOrNull } ?: emptyList()
            val explicitBounds = dpObj["explicitBounds"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.doubleOrNull } ?: emptyList()

            results += buildMetricInsert(
                name, "histogram", description, unit, tsNs, sum,
                attrs, resourceCtx,
                aggregationTemporality = temporality,
                histCount = count, histSum = sum,
                histMin = min, histMax = max,
                histBucketCounts = bucketCounts,
                histExplicitBounds = explicitBounds
            )
        }
    }

    private fun parseExpHistogramDataPoints(
        expHist: kotlinx.serialization.json.JsonObject,
        name: String,
        description: String,
        unit: String,
        resourceCtx: com.moneat.otlp.ResourceContext,
        results: MutableList<OtlpMetricInsert>
    ) {
        val temporality = mapAggregationTemporality(
            expHist["aggregationTemporality"]?.jsonPrimitive?.intOrNull ?: 0
        )
        val dataPoints = OtlpParsingUtils.safeJsonArray(expHist["dataPoints"])
        dataPoints.forEach { dp ->
            val dpObj = dp.jsonObject
            val attrs = OtlpParsingUtils.attributesToMap(dpObj["attributes"])
            val tsNs = dpObj["timeUnixNano"]?.jsonPrimitive?.longOrNull ?: 0L
            val count = dpObj["count"]?.jsonPrimitive?.longOrNull ?: 0L
            val sum = dpObj["sum"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val min = dpObj["min"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val max = dpObj["max"]?.jsonPrimitive?.doubleOrNull ?: 0.0

            results += buildMetricInsert(
                name, "exp_histogram", description, unit, tsNs, sum,
                attrs, resourceCtx,
                aggregationTemporality = temporality,
                histCount = count, histSum = sum,
                histMin = min, histMax = max
            )
        }
    }

    private fun parseSummaryDataPoints(
        summary: kotlinx.serialization.json.JsonObject,
        name: String,
        description: String,
        unit: String,
        resourceCtx: com.moneat.otlp.ResourceContext,
        results: MutableList<OtlpMetricInsert>
    ) {
        val dataPoints = OtlpParsingUtils.safeJsonArray(summary["dataPoints"])
        dataPoints.forEach { dp ->
            val dpObj = dp.jsonObject
            val attrs = OtlpParsingUtils.attributesToMap(dpObj["attributes"])
            val tsNs = dpObj["timeUnixNano"]?.jsonPrimitive?.longOrNull ?: 0L
            val count = dpObj["count"]?.jsonPrimitive?.longOrNull ?: 0L
            val sum = dpObj["sum"]?.jsonPrimitive?.doubleOrNull ?: 0.0

            results += buildMetricInsert(
                name, "summary", description, unit, tsNs, sum,
                attrs, resourceCtx,
                histCount = count, histSum = sum
            )
        }
    }

    @Suppress("LongParameterList")
    private fun buildMetricInsert(
        name: String,
        type: String,
        description: String,
        unit: String,
        timestampNs: Long,
        value: Double,
        attrs: Map<String, String>,
        resourceCtx: com.moneat.otlp.ResourceContext,
        isMonotonic: Int = 0,
        aggregationTemporality: String = "",
        histCount: Long = 0,
        histSum: Double = 0.0,
        histMin: Double = 0.0,
        histMax: Double = 0.0,
        histBucketCounts: List<Long> = emptyList(),
        histExplicitBounds: List<Double> = emptyList(),
    ): OtlpMetricInsert {
        val tsMs = OtlpParsingUtils.nanoToEpochMs(timestampNs) ?: 0L
        return OtlpMetricInsert(
            organizationId = 0,
            metricName = name,
            metricType = type,
            description = description,
            unit = unit,
            timestampMs = tsMs,
            value = value,
            isMonotonic = isMonotonic,
            aggregationTemporality = aggregationTemporality,
            histCount = histCount,
            histSum = histSum,
            histMin = histMin,
            histMax = histMax,
            histBucketCounts = histBucketCounts,
            histExplicitBounds = histExplicitBounds,
            tags = attrs,
            resourceAttributes = resourceCtx.attributes,
            service = resourceCtx.serviceName,
            env = resourceCtx.environment,
            host = resourceCtx.hostName,
        )
    }

    fun enqueueMetrics(
        organizationId: Long,
        metrics: List<OtlpMetricInsert>,
        queueKey: String
    ): Int {
        if (metrics.isEmpty()) return 0
        val withOrg = metrics.map { it.copy(organizationId = organizationId) }
        val batch = QueuedOtlpMetricsBatch(
            organizationId = organizationId,
            metrics = withOrg
        )
        val encoded = json.encodeToString(batch)
        RedisConfig.sync().lpush(queueKey, encoded)
        return withOrg.size
    }

    fun decodeBatch(payload: String): QueuedOtlpMetricsBatch =
        json.decodeFromString(payload)

    suspend fun insertBatch(batch: QueuedOtlpMetricsBatch) {
        if (batch.metrics.isEmpty()) return

        val rows = batch.metrics.joinToString(",\n") { m ->
            val bucketCountsArr = m.histBucketCounts.joinToString(",")
            val boundsArr = m.histExplicitBounds.joinToString(",")

            """(
                generateUUIDv4(),
                ${m.organizationId},
                '${escapeSql(m.metricName)}',
                '${escapeSql(m.metricType)}',
                fromUnixTimestamp64Milli(${m.timestampMs}),
                ${m.value},
                '${escapeSql(m.host)}',
                ${mapToSqlMap(m.tags)},
                '${escapeSql(m.unit)}',
                '',
                'otlp',
                ${m.isMonotonic},
                '${escapeSql(m.aggregationTemporality)}',
                ${m.histCount},
                ${m.histSum},
                ${m.histMin},
                ${m.histMax},
                [$bucketCountsArr],
                [$boundsArr],
                ${mapToSqlMap(m.resourceAttributes)},
                '${escapeSql(m.service)}',
                '${escapeSql(m.env)}',
                '${escapeSql(m.description)}'
            )"""
        }

        val insert = """
            INSERT INTO `$clickhouseDb`.metrics (
                metric_id, organization_id, metric_name, metric_type,
                timestamp, value, host, tags, unit, source_type_name,
                source, is_monotonic, aggregation_temporality,
                hist_count, hist_sum, hist_min, hist_max,
                hist_bucket_counts, hist_explicit_bounds,
                resource_attributes, service, env, description
            ) VALUES
            $rows
        """.trimIndent()

        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to insert OTLP metrics into ClickHouse")
        }

        val totalBytes = batch.metrics.sumOf { it.metricName.length + 64 }
        usageTracking.recordOrgUsage(
            batch.organizationId.toInt(),
            "otlp_metric",
            totalBytes
        )
    }

    private fun mapAggregationTemporality(value: Int): String = when (value) {
        1 -> "delta"
        2 -> "cumulative"
        else -> ""
    }

    private fun mapToSqlMap(map: Map<String, String>): String {
        if (map.isEmpty()) return "map()"
        val entries = map.entries.joinToString(", ") { (k, v) ->
            "'${escapeSql(k)}', '${escapeSql(v)}'"
        }
        return "map($entries)"
    }
}
