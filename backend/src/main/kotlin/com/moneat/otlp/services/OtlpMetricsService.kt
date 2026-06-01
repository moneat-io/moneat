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

import com.google.protobuf.InvalidProtocolBufferException
import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.monitor.services.InfraMetricRollupRow
import com.moneat.monitor.services.InfraTelemetryRollups
import com.moneat.otlp.METRIC_BILLABLE_OVERHEAD_BYTES
import com.moneat.otlp.OtlpParsingUtils
import com.moneat.otlp.OtlpProtobufParser
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.formatClickHouseDateTime64MillisUtc
import io.ktor.http.isSuccess
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.metrics.v1.Metric
import io.opentelemetry.proto.metrics.v1.NumberDataPoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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

// OTLP AggregationTemporality (opentelemetry.proto.metrics.v1.AggregationTemporality)
private const val AGGREGATION_TEMPORALITY_DELTA = 1
private const val AGGREGATION_TEMPORALITY_CUMULATIVE = 2
private const val ERROR_MESSAGE_PREVIEW_LENGTH = 500

@Serializable
data class OtlpMetricInsert(
    val organizationId: Long,
    val projectId: Long? = null,
    val metricName: String,
    val metricType: String,
    val description: String,
    val unit: String,
    val timestampMs: Long,
    val value: Double,
    val isMonotonic: Int,
    val aggregationTemporality: String,
    val histCount: Long,
    val histSum: Double?,
    val histMin: Double?,
    val histMax: Double?,
    val histBucketCounts: List<Long>,
    val histExplicitBounds: List<Double>,
    val tags: Map<String, String>,
    val resourceAttributes: Map<String, String>,
    val serviceNamespace: String = "",
    val service: String,
    val env: String,
    val host: String,
)

@Serializable
data class QueuedOtlpMetricsBatch(
    val organizationId: Long,
    val metrics: List<OtlpMetricInsert>
)

@Serializable
private data class OtlpMetricJsonEachRow(
    @SerialName("organization_id") val organizationId: Long,
    @SerialName("project_id") val projectId: Long,
    @SerialName("metric_name") val metricName: String,
    @SerialName("metric_type") val metricType: String,
    val timestamp: String,
    val value: Double,
    val host: String,
    val tags: Map<String, String>,
    val unit: String,
    @SerialName("source_type_name") val sourceTypeName: String,
    val source: String,
    @SerialName("is_monotonic") val isMonotonic: Int,
    @SerialName("aggregation_temporality") val aggregationTemporality: String,
    @SerialName("hist_count") val histCount: Long,
    @SerialName("hist_sum") val histSum: Double?,
    @SerialName("hist_min") val histMin: Double?,
    @SerialName("hist_max") val histMax: Double?,
    @SerialName("hist_bucket_counts") val histBucketCounts: List<Long>,
    @SerialName("hist_explicit_bounds") val histExplicitBounds: List<Double>,
    @SerialName("resource_attributes") val resourceAttributes: Map<String, String>,
    val service: String,
    val env: String,
    val description: String,
)

private data class HistLikeFields(
    val attrs: Map<String, String>,
    val tsNs: Long,
    val count: Long,
    val sum: Double?,
    val min: Double?,
    val max: Double?,
)

private data class HistLikeInsertArgs(
    val type: String,
    val name: String,
    val description: String,
    val unit: String,
    val resourceCtx: com.moneat.otlp.ResourceContext,
    val temporality: String,
    val fields: HistLikeFields,
    val bucketCounts: List<Long> = emptyList(),
    val explicitBounds: List<Double> = emptyList(),
)

@Suppress("LongParameterList")
private data class MetricInsertSpec(
    val name: String,
    val type: String,
    val description: String,
    val unit: String,
    val timestampNs: Long,
    val value: Double,
    val attrs: Map<String, String>,
    val resourceCtx: com.moneat.otlp.ResourceContext,
    val isMonotonic: Int = 0,
    val aggregationTemporality: String = "",
    val histCount: Long = 0,
    val histSum: Double? = null,
    val histMin: Double? = null,
    val histMax: Double? = null,
    val histBucketCounts: List<Long> = emptyList(),
    val histExplicitBounds: List<Double> = emptyList(),
)

class OtlpMetricsService(
    private val usageTracking: UsageTrackingService = UsageTrackingService(),
) {
    companion object {
        /** Json for queued OTLP metric batches; use in tests for symmetric encode/decode with [decodeBatch]. */
        val queuedBatchJson: Json = Json { ignoreUnknownKeys = true }
    }

    private val json = queuedBatchJson

    private val clickhouseDb = ClickHouseClient.getDatabase()

    @Suppress("CyclomaticComplexMethod")
    fun parseOtlpMetricsProtobuf(bytes: ByteArray): List<OtlpMetricInsert>? {
        val request =
            try {
                ExportMetricsServiceRequest.parseFrom(bytes)
            } catch (e: InvalidProtocolBufferException) {
                logger.warn {
                    "Invalid OTLP protobuf metrics payload: ${e.message?.take(ERROR_MESSAGE_PREVIEW_LENGTH)}"
                }
                return null
            }

        val results = mutableListOf<OtlpMetricInsert>()

        request.resourceMetricsList.forEach { rm ->
            val resourceCtx = OtlpProtobufParser.extractResourceContext(rm.resource)

            rm.scopeMetricsList.forEach { sm ->
                sm.metricsList.forEach { metric ->
                    parseProtoMetric(metric, resourceCtx, results)
                }
            }
        }

        return results
    }

    private fun parseProtoMetric(
        metric: Metric,
        resourceCtx: com.moneat.otlp.ResourceContext,
        results: MutableList<OtlpMetricInsert>
    ) {
        val name = metric.name
        val description = metric.description
        val unit = metric.unit

        when (metric.dataCase) {
            Metric.DataCase.GAUGE -> metric.gauge.dataPointsList.forEach { dp ->
                val value = numberDataPointValue(dp) ?: return@forEach
                results += buildMetricInsert(
                    MetricInsertSpec(
                        name = name, type = "gauge", description = description, unit = unit,
                        timestampNs = dp.timeUnixNano, value = value,
                        attrs = OtlpProtobufParser.attributesToMap(dp.attributesList),
                        resourceCtx = resourceCtx,
                    )
                )
            }
            Metric.DataCase.SUM -> {
                val sum = metric.sum
                val temporality = mapAggregationTemporality(sum.aggregationTemporalityValue)
                val isMonotonic = if (sum.isMonotonic) 1 else 0
                sum.dataPointsList.forEach { dp ->
                    val value = numberDataPointValue(dp) ?: return@forEach
                    results += buildMetricInsert(
                        MetricInsertSpec(
                            name = name, type = "sum", description = description, unit = unit,
                            timestampNs = dp.timeUnixNano, value = value,
                            attrs = OtlpProtobufParser.attributesToMap(dp.attributesList),
                            resourceCtx = resourceCtx,
                            isMonotonic = isMonotonic, aggregationTemporality = temporality,
                        )
                    )
                }
            }
            Metric.DataCase.HISTOGRAM -> {
                val temporality = mapAggregationTemporality(metric.histogram.aggregationTemporalityValue)
                metric.histogram.dataPointsList.forEach { dp ->
                    results += buildMetricInsert(
                        histLikeSpec(
                            HistLikeInsertArgs(
                                type = "histogram",
                                name = name,
                                description = description,
                                unit = unit,
                                resourceCtx = resourceCtx,
                                temporality = temporality,
                                fields = HistLikeFields(
                                    attrs = OtlpProtobufParser.attributesToMap(dp.attributesList),
                                    tsNs = dp.timeUnixNano,
                                    count = dp.count,
                                    sum = dp.sum.takeIf { dp.hasSum() },
                                    min = dp.min.takeIf { dp.hasMin() },
                                    max = dp.max.takeIf { dp.hasMax() },
                                ),
                                bucketCounts = dp.bucketCountsList,
                                explicitBounds = dp.explicitBoundsList,
                            )
                        )
                    )
                }
            }
            Metric.DataCase.EXPONENTIAL_HISTOGRAM -> {
                val temporality = mapAggregationTemporality(
                    metric.exponentialHistogram.aggregationTemporalityValue
                )
                metric.exponentialHistogram.dataPointsList.forEach { dp ->
                    results += buildMetricInsert(
                        histLikeSpec(
                            HistLikeInsertArgs(
                                type = "exp_histogram",
                                name = name,
                                description = description,
                                unit = unit,
                                resourceCtx = resourceCtx,
                                temporality = temporality,
                                fields = HistLikeFields(
                                    attrs = OtlpProtobufParser.attributesToMap(dp.attributesList),
                                    tsNs = dp.timeUnixNano,
                                    count = dp.count,
                                    sum = dp.sum.takeIf { dp.hasSum() },
                                    min = dp.min.takeIf { dp.hasMin() },
                                    max = dp.max.takeIf { dp.hasMax() },
                                ),
                            )
                        )
                    )
                }
            }
            Metric.DataCase.SUMMARY -> metric.summary.dataPointsList.forEach { dp ->
                results += buildMetricInsert(
                    histLikeSpec(
                        HistLikeInsertArgs(
                            type = "summary",
                            name = name,
                            description = description,
                            unit = unit,
                            resourceCtx = resourceCtx,
                            temporality = "",
                            fields = HistLikeFields(
                                attrs = OtlpProtobufParser.attributesToMap(dp.attributesList),
                                tsNs = dp.timeUnixNano,
                                count = dp.count,
                                sum = dp.sum,
                                min = null,
                                max = null,
                            ),
                        )
                    )
                )
            }
            Metric.DataCase.DATA_NOT_SET, null -> Unit
        }
    }

    private fun numberDataPointValue(dp: NumberDataPoint): Double? =
        when (dp.valueCase) {
            NumberDataPoint.ValueCase.AS_DOUBLE -> dp.asDouble
            NumberDataPoint.ValueCase.AS_INT -> dp.asInt.toDouble()
            NumberDataPoint.ValueCase.VALUE_NOT_SET, null -> null
        }

    private fun histLikeSpec(args: HistLikeInsertArgs) = with(args) {
        val f = fields
        MetricInsertSpec(
            name = name, type = type, description = description, unit = unit,
            timestampNs = f.tsNs, value = f.sum ?: 0.0,
            attrs = f.attrs, resourceCtx = resourceCtx, aggregationTemporality = temporality,
            histCount = f.count, histSum = f.sum, histMin = f.min, histMax = f.max,
            histBucketCounts = bucketCounts, histExplicitBounds = explicitBounds,
        )
    }

    fun parseOtlpMetricsJson(payload: String): List<OtlpMetricInsert>? {
        val parsed =
            try {
                json.parseToJsonElement(payload).jsonObject
            } catch (e: SerializationException) {
                logger.warn(e) { "Invalid OTLP metrics JSON payload" }
                return null
            } catch (e: IllegalArgumentException) {
                logger.warn(e) { "Invalid OTLP metrics JSON payload" }
                return null
            }

        val resourceMetrics = parsed["resourceMetrics"]?.jsonArray ?: return null
        val results = mutableListOf<OtlpMetricInsert>()
        appendOtlpJsonMetricsFromResourceMetrics(resourceMetrics, results)
        return results
    }

    private fun appendOtlpJsonMetricsFromResourceMetrics(
        resourceMetrics: JsonArray,
        results: MutableList<OtlpMetricInsert>,
    ) {
        resourceMetrics.forEach { rmElement ->
            val rm = rmElement.jsonObject
            val resourceCtx = OtlpParsingUtils.extractResourceContext(
                rm["resource"]?.jsonObject
            )
            val scopeMetrics = rm["scopeMetrics"]?.jsonArray
                ?: rm["instrumentationLibraryMetrics"]?.jsonArray
                ?: JsonArray(emptyList())
            appendOtlpJsonMetricsFromScopeMetrics(scopeMetrics, resourceCtx, results)
        }
    }

    private fun appendOtlpJsonMetricsFromScopeMetrics(
        scopeMetrics: JsonArray,
        resourceCtx: com.moneat.otlp.ResourceContext,
        results: MutableList<OtlpMetricInsert>,
    ) {
        scopeMetrics.forEach { smElement ->
            val sm = smElement.jsonObject
            val metricsArray = OtlpParsingUtils.safeJsonArray(sm["metrics"])
            metricsArray.forEach { metricElement ->
                appendOtlpJsonMetric(metricElement.jsonObject, resourceCtx, results)
            }
        }
    }

    private fun appendOtlpJsonMetric(
        metric: kotlinx.serialization.json.JsonObject,
        resourceCtx: com.moneat.otlp.ResourceContext,
        results: MutableList<OtlpMetricInsert>,
    ) {
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

    private fun readJsonNumberValue(dpObj: kotlinx.serialization.json.JsonObject): Double? =
        dpObj["asDouble"]?.jsonPrimitive?.doubleOrNull
            ?: dpObj["asInt"]?.jsonPrimitive?.longOrNull?.toDouble()

    private fun parseGaugeDataPoints(
        gauge: kotlinx.serialization.json.JsonObject,
        name: String,
        description: String,
        unit: String,
        resourceCtx: com.moneat.otlp.ResourceContext,
        results: MutableList<OtlpMetricInsert>
    ) {
        OtlpParsingUtils.safeJsonArray(gauge["dataPoints"]).forEach { dp ->
            val dpObj = dp.jsonObject
            val value = readJsonNumberValue(dpObj) ?: return@forEach
            results += buildMetricInsert(
                MetricInsertSpec(
                    name = name, type = "gauge", description = description, unit = unit,
                    timestampNs = dpObj["timeUnixNano"]?.jsonPrimitive?.longOrNull ?: 0L,
                    value = value,
                    attrs = OtlpParsingUtils.attributesToMap(dpObj["attributes"]),
                    resourceCtx = resourceCtx,
                )
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
        OtlpParsingUtils.safeJsonArray(sum["dataPoints"]).forEach { dp ->
            val dpObj = dp.jsonObject
            val value = readJsonNumberValue(dpObj) ?: return@forEach
            results += buildMetricInsert(
                MetricInsertSpec(
                    name = name, type = "sum", description = description, unit = unit,
                    timestampNs = dpObj["timeUnixNano"]?.jsonPrimitive?.longOrNull ?: 0L,
                    value = value,
                    attrs = OtlpParsingUtils.attributesToMap(dpObj["attributes"]),
                    resourceCtx = resourceCtx,
                    isMonotonic = if (isMonotonic) 1 else 0,
                    aggregationTemporality = temporality,
                )
            )
        }
    }

    private fun readJsonHistLikeFields(dpObj: kotlinx.serialization.json.JsonObject) = HistLikeFields(
        attrs = OtlpParsingUtils.attributesToMap(dpObj["attributes"]),
        tsNs = dpObj["timeUnixNano"]?.jsonPrimitive?.longOrNull ?: 0L,
        count = dpObj["count"]?.jsonPrimitive?.longOrNull ?: 0L,
        sum = dpObj["sum"]?.jsonPrimitive?.doubleOrNull,
        min = dpObj["min"]?.jsonPrimitive?.doubleOrNull,
        max = dpObj["max"]?.jsonPrimitive?.doubleOrNull,
    )

    @Suppress("LongParameterList")
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
        OtlpParsingUtils.safeJsonArray(histogram["dataPoints"]).forEach { dp ->
            val dpObj = dp.jsonObject
            val h = readJsonHistLikeFields(dpObj)
            results += buildMetricInsert(
                histLikeSpec(
                    HistLikeInsertArgs(
                        type = "histogram",
                        name = name,
                        description = description,
                        unit = unit,
                        resourceCtx = resourceCtx,
                        temporality = temporality,
                        fields = h,
                        bucketCounts = dpObj["bucketCounts"]?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.longOrNull } ?: emptyList(),
                        explicitBounds = dpObj["explicitBounds"]?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.doubleOrNull } ?: emptyList(),
                    )
                )
            )
        }
    }

    @Suppress("LongParameterList")
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
        OtlpParsingUtils.safeJsonArray(expHist["dataPoints"]).forEach { dp ->
            val h = readJsonHistLikeFields(dp.jsonObject)
            results += buildMetricInsert(
                histLikeSpec(
                    HistLikeInsertArgs(
                        type = "exp_histogram",
                        name = name,
                        description = description,
                        unit = unit,
                        resourceCtx = resourceCtx,
                        temporality = temporality,
                        fields = h,
                    )
                )
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
        OtlpParsingUtils.safeJsonArray(summary["dataPoints"]).forEach { dp ->
            val dpObj = dp.jsonObject
            val count = dpObj["count"]?.jsonPrimitive?.longOrNull ?: 0L
            val sum = dpObj["sum"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            results += buildMetricInsert(
                histLikeSpec(
                    HistLikeInsertArgs(
                        type = "summary",
                        name = name,
                        description = description,
                        unit = unit,
                        resourceCtx = resourceCtx,
                        temporality = "",
                        fields = HistLikeFields(
                            attrs = OtlpParsingUtils.attributesToMap(dpObj["attributes"]),
                            tsNs = dpObj["timeUnixNano"]?.jsonPrimitive?.longOrNull ?: 0L,
                            count = count,
                            sum = sum,
                            min = null,
                            max = null,
                        ),
                    )
                )
            )
        }
    }

    private fun buildMetricInsert(spec: MetricInsertSpec): OtlpMetricInsert {
        val tsMs = OtlpParsingUtils.nanoToEpochMs(spec.timestampNs) ?: 0L
        return OtlpMetricInsert(
            organizationId = 0,
            projectId = null,
            metricName = spec.name,
            metricType = spec.type,
            description = spec.description,
            unit = spec.unit,
            timestampMs = tsMs,
            value = spec.value,
            isMonotonic = spec.isMonotonic,
            aggregationTemporality = spec.aggregationTemporality,
            histCount = spec.histCount,
            histSum = spec.histSum,
            histMin = spec.histMin,
            histMax = spec.histMax,
            histBucketCounts = spec.histBucketCounts,
            histExplicitBounds = spec.histExplicitBounds,
            tags = spec.attrs,
            resourceAttributes = spec.resourceCtx.attributes,
            serviceNamespace = spec.resourceCtx.serviceNamespace,
            service = spec.resourceCtx.serviceName,
            env = spec.resourceCtx.environment,
            host = spec.resourceCtx.hostName,
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

        val rows = batch.metrics.joinToString("\n") { metric ->
            json.encodeToString(metric.toJsonEachRow())
        }

        val insert = """
            INSERT INTO `$clickhouseDb`.metrics (
                organization_id, project_id, metric_name, metric_type,
                timestamp, value, host, tags, unit, source_type_name,
                source, is_monotonic, aggregation_temporality,
                hist_count, hist_sum, hist_min, hist_max,
                hist_bucket_counts, hist_explicit_bounds,
                resource_attributes, service, env, description
            ) FORMAT JSONEachRow
            $rows
        """.trimIndent()

        val response = ClickHouseClient.execute(insert)
        check(response.status.isSuccess()) { "Failed to insert OTLP metrics into ClickHouse" }

        InfraTelemetryRollups.insertMetricRollups(
            batch.metrics.map { metric ->
                InfraMetricRollupRow(
                    organizationId = metric.organizationId,
                    metricName = metric.metricName,
                    timestampMs = metric.timestampMs,
                    value = metric.value,
                    host = metric.host,
                    tags = metric.resourceAttributes + metric.tags,
                    unit = metric.unit,
                    source = "otlp",
                )
            }
        )

        val totalBytes = batch.metrics.sumOf { it.metricName.length + METRIC_BILLABLE_OVERHEAD_BYTES }
        usageTracking.recordOrgUsage(
            batch.organizationId.toInt(),
            "otlp_metric",
            totalBytes
        )
    }

    private fun OtlpMetricInsert.toJsonEachRow(): OtlpMetricJsonEachRow =
        OtlpMetricJsonEachRow(
            organizationId = organizationId,
            projectId = projectId ?: 0L,
            metricName = metricName,
            metricType = metricType,
            timestamp = formatClickHouseDateTime64MillisUtc(timestampMs),
            value = value,
            host = host,
            tags = tags,
            unit = unit,
            sourceTypeName = "",
            source = "otlp",
            isMonotonic = isMonotonic,
            aggregationTemporality = aggregationTemporality,
            histCount = histCount,
            histSum = histSum,
            histMin = histMin,
            histMax = histMax,
            histBucketCounts = histBucketCounts,
            histExplicitBounds = histExplicitBounds,
            resourceAttributes = resourceAttributes,
            service = service,
            env = env,
            description = description,
        )

    private fun mapAggregationTemporality(value: Int): String = when (value) {
        AGGREGATION_TEMPORALITY_DELTA -> "delta"
        AGGREGATION_TEMPORALITY_CUMULATIVE -> "cumulative"
        else -> ""
    }
}
