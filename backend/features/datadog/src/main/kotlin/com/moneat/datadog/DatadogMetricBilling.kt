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

package com.moneat.datadog

import com.moneat.datadog.models.DatadogMetricSeriesV1
import com.moneat.datadog.models.DatadogMetricV1
import com.moneat.datadog.models.DatadogSketch
import com.moneat.datadog.models.DatadogSketchPayload

internal const val DATADOG_CUSTOM_METRIC_EVENT_TYPE = "dd_metric"
internal const val DATADOG_INFRA_METRIC_EVENT_TYPE = "infra_metric"

private val INFRA_METRIC_PREFIXES = listOf(
    "system.",
    "container.",
    "docker.",
    "datadog.",
)

internal data class DatadogMetricBillingRequest(
    val requestedUnitsByType: Map<String, Int>,
    val requestedBytesByType: Map<String, Long>,
)

internal fun metricSeriesBillingRequest(
    payload: DatadogMetricSeriesV1,
    totalBytes: Long,
): DatadogMetricBillingRequest {
    val infraSeriesKeys = mutableSetOf<String>()
    var infraPointCount = 0L
    var customPointCount = 0

    for (series in payload.series) {
        val validPoints = series.points.count { it.size >= 2 }
        if (validPoints <= 0) continue

        if (isDatadogInfraMetric(series.metric)) {
            infraPointCount += validPoints.toLong()
            infraSeriesKeys += metricSeriesKey(series.metric, series.host, series.tags)
        } else {
            customPointCount += validPoints
        }
    }

    return buildBillingRequest(
        totalBytes = totalBytes,
        infraUnits = infraSeriesKeys.size,
        customUnits = customPointCount,
        infraByteWeight = infraPointCount,
        customByteWeight = customPointCount.toLong(),
    )
}

internal fun sketchBillingRequest(
    payload: DatadogSketchPayload,
    totalBytes: Long,
): DatadogMetricBillingRequest {
    val infraSeriesKeys = mutableSetOf<String>()
    var infraDistributionCount = 0L
    var customDistributionCount = 0

    for (sketch in payload.sketches) {
        val distributions = sketch.distributions.size
        if (distributions <= 0) continue

        if (isDatadogInfraMetric(sketch.metric)) {
            infraDistributionCount += distributions.toLong()
            infraSeriesKeys += sketchSeriesKey(sketch)
        } else {
            customDistributionCount += distributions
        }
    }

    return buildBillingRequest(
        totalBytes = totalBytes,
        infraUnits = infraSeriesKeys.size,
        customUnits = customDistributionCount,
        infraByteWeight = infraDistributionCount,
        customByteWeight = customDistributionCount.toLong(),
    )
}

internal fun dogStatsDBillingRequest(
    metrics: List<DatadogMetricV1>,
    totalBytes: Long,
): DatadogMetricBillingRequest {
    val infraSeriesKeys = mutableSetOf<String>()
    var customMetricCount = 0

    for (metric in metrics) {
        if (isDatadogInfraMetric(metric.metric)) {
            infraSeriesKeys += metricSeriesKey(metric.metric, metric.host, metric.tags)
        } else {
            customMetricCount += 1
        }
    }

    return buildBillingRequest(
        totalBytes = totalBytes,
        infraUnits = infraSeriesKeys.size,
        customUnits = customMetricCount,
        infraByteWeight = infraSeriesKeys.size.toLong(),
        customByteWeight = customMetricCount.toLong(),
    )
}

internal fun isDatadogInfraMetric(metricName: String): Boolean {
    val normalized = metricName.trim().lowercase()
    return INFRA_METRIC_PREFIXES.any { normalized.startsWith(it) }
}

private fun buildBillingRequest(
    totalBytes: Long,
    infraUnits: Int,
    customUnits: Int,
    infraByteWeight: Long,
    customByteWeight: Long,
): DatadogMetricBillingRequest {
    val requestedUnits = mutableMapOf<String, Int>()
    if (infraUnits > 0) requestedUnits[DATADOG_INFRA_METRIC_EVENT_TYPE] = infraUnits
    if (customUnits > 0) requestedUnits[DATADOG_CUSTOM_METRIC_EVENT_TYPE] = customUnits

    val requestedBytes = mutableMapOf<String, Long>()
    val (infraBytes, customBytes) = splitBytes(totalBytes, infraByteWeight, customByteWeight)
    if (infraBytes > 0) requestedBytes[DATADOG_INFRA_METRIC_EVENT_TYPE] = infraBytes
    if (customBytes > 0) requestedBytes[DATADOG_CUSTOM_METRIC_EVENT_TYPE] = customBytes

    return DatadogMetricBillingRequest(
        requestedUnitsByType = requestedUnits,
        requestedBytesByType = requestedBytes,
    )
}

private fun splitBytes(
    totalBytes: Long,
    infraWeight: Long,
    customWeight: Long,
): Pair<Long, Long> {
    if (totalBytes <= 0) return 0L to 0L
    val totalWeight = infraWeight + customWeight
    if (totalWeight <= 0) return 0L to 0L

    val infraBytes = (totalBytes * infraWeight / totalWeight).coerceIn(0, totalBytes)
    return infraBytes to (totalBytes - infraBytes)
}

private fun metricSeriesKey(
    metricName: String,
    host: String,
    tags: List<String>,
): String {
    return listOf(metricName, host, tags.sorted().joinToString(",")).joinToString("|")
}

private fun sketchSeriesKey(sketch: DatadogSketch): String {
    return metricSeriesKey(sketch.metric, sketch.host, sketch.tags)
}
