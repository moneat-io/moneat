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

package com.moneat.dashboards.services.handlers

import com.moneat.dashboards.models.DataSourceField
import com.moneat.dashboards.models.TestConnectionRequest
import com.moneat.dashboards.models.TestConnectionResult
import com.moneat.dashboards.models.TimeRangeDef
import com.moneat.dashboards.services.DataSourceCredentials
import com.moneat.monitoring.OperationalMetrics
import com.moneat.utils.TimeConstants.MILLIS_PER_SECOND
import com.moneat.utils.TimeConstants.SECONDS_PER_DAY
import com.moneat.utils.TimeConstants.SECONDS_PER_HOUR
import com.moneat.utils.TimeConstants.SECONDS_PER_MINUTE
import com.moneat.utils.TimeConstants.SECONDS_PER_MONTH_30
import com.moneat.utils.TimeConstants.SECONDS_PER_WEEK
import com.moneat.utils.TimeConstants.SECONDS_PER_YEAR_365
import com.moneat.utils.suspendRunCatching
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class PrometheusHandler : HttpApiHandler() {

    companion object {
        private const val PROMETHEUS_LABEL_LIMIT = 20
        private const val SECONDS_SIX_HOURS = 21_600L
        private const val PROMETHEUS_SOURCE = "prometheus"

        private fun httpStatusLabel(status: Int): String = "http_$status"
    }

    override val defaultPort: Int = 9090
    override val httpAuthDefault = HttpAuthDefault.BEARER

    override suspend fun testConnection(request: TestConnectionRequest): TestConnectionResult {
        return suspendRunCatching {
            val baseUrl = buildUrl(request.host, request.port)
            val response = httpClient.get("$baseUrl/api/v1/label/__name__/values") {
                applyHttpAuth(request.toCredentials())
                parameter("limit", PROMETHEUS_LABEL_LIMIT)
            }
            if (response.status.isSuccess()) {
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                val metrics = body["data"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                TestConnectionResult(true, "Connected successfully", metrics = metrics.take(PROMETHEUS_LABEL_LIMIT))
            } else {
                OperationalMetrics.recordDatasourceQueryFailure(
                    PROMETHEUS_SOURCE,
                    "test_connection",
                    httpStatusLabel(response.status.value)
                )
                TestConnectionResult(false, "Prometheus returned ${response.status}")
            }
        }.getOrElse { e ->
            OperationalMetrics.recordDatasourceQueryFailure(PROMETHEUS_SOURCE, "test_connection", cause = e)
            logger.warn(e) { "Prometheus connection test failed" }
            TestConnectionResult(false, "Connection failed: ${e.message}")
        }
    }

    override suspend fun executeLabelValuesQuery(
        host: String,
        port: Int?,
        credentials: DataSourceCredentials,
        query: String,
    ): List<String> {
        val twoArgMatch = Regex("""label_values\((.+),\s*(\w+)\)""").matchEntire(query)
        val oneArgMatch = if (twoArgMatch == null) {
            Regex("""label_values\((\w+)\)""").matchEntire(query)
        } else {
            null
        }

        val matcher: String?
        val labelName: String
        if (twoArgMatch != null) {
            matcher = twoArgMatch.groupValues[1].trim()
            labelName = twoArgMatch.groupValues[2].trim()
        } else if (oneArgMatch != null) {
            matcher = null
            labelName = oneArgMatch.groupValues[1].trim()
        } else {
            return emptyList()
        }

        val baseUrl = buildUrl(host, port)
        return suspendRunCatching {
            val response = httpClient.get("$baseUrl/api/v1/label/$labelName/values") {
                applyHttpAuth(credentials)
                if (!matcher.isNullOrEmpty()) parameter("match[]", matcher)
            }
            if (response.status.isSuccess()) {
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["data"]?.jsonArray?.map { it.jsonPrimitive.content }?.sorted() ?: emptyList()
            } else {
                OperationalMetrics.recordDatasourceQueryFailure(
                    PROMETHEUS_SOURCE,
                    "label_values",
                    httpStatusLabel(response.status.value)
                )
                logger.warn { "Prometheus label_values query failed: ${response.status}" }
                emptyList()
            }
        }.getOrElse { e ->
            OperationalMetrics.recordDatasourceQueryFailure(PROMETHEUS_SOURCE, "label_values", cause = e)
            logger.warn(e) { "Failed to execute label_values query" }
            emptyList()
        }
    }

    override suspend fun executeQuery(
        sourceId: Long,
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
        query: String,
        limit: Int,
        timeRange: TimeRangeDef?,
    ): List<Map<String, JsonElement>> {
        val baseUrl = buildUrl(host, port)
        val promLimit = limit

        return suspendRunCatching {
            val response = if (timeRange != null) {
                val nowSec = System.currentTimeMillis() / MILLIS_PER_SECOND
                val fromSec = resolveRelativeTimeSec(timeRange.from, nowSec)
                val toSec = resolveRelativeTimeSec(timeRange.to, nowSec)
                val step = resolvePrometheusStep(toSec - fromSec)

                httpClient.get("$baseUrl/api/v1/query_range") {
                    applyHttpAuth(credentials)
                    parameter("query", query)
                    parameter("start", fromSec)
                    parameter("end", toSec)
                    parameter("step", step)
                }
            } else {
                httpClient.get("$baseUrl/api/v1/query") {
                    applyHttpAuth(credentials)
                    parameter("query", query)
                }
            }

            if (!response.status.isSuccess()) {
                OperationalMetrics.recordDatasourceQueryFailure(
                    PROMETHEUS_SOURCE,
                    "query",
                    httpStatusLabel(response.status.value)
                )
                logger.error { "Prometheus query failed: ${response.status} | query=$query" }
                return emptyList()
            }
            parsePrometheusResponse(response.bodyAsText(), promLimit)
        }.getOrElse { e ->
            OperationalMetrics.recordDatasourceQueryFailure(PROMETHEUS_SOURCE, "query", cause = e)
            logger.error(e) { "Failed to execute Prometheus query" }
            emptyList()
        }
    }

    override suspend fun getSchema(
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
    ): List<DataSourceField> {
        val baseUrl = buildUrl(host, port)
        return suspendRunCatching {
            val response = httpClient.get("$baseUrl/api/v1/label/__name__/values") {
                applyHttpAuth(credentials)
            }
            if (response.status.isSuccess()) {
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["data"]?.jsonArray?.map {
                    DataSourceField(it.jsonPrimitive.content, "gauge", "Prometheus metric")
                } ?: emptyList()
            } else {
                OperationalMetrics.recordDatasourceQueryFailure(
                    PROMETHEUS_SOURCE,
                    "schema",
                    httpStatusLabel(response.status.value)
                )
                emptyList()
            }
        }.getOrElse { e ->
            OperationalMetrics.recordDatasourceQueryFailure(PROMETHEUS_SOURCE, "schema", cause = e)
            logger.error(e) { "Failed to fetch Prometheus metrics" }
            emptyList()
        }
    }

    internal fun parsePrometheusResponse(body: String, limit: Int): List<Map<String, JsonElement>> {
        if (limit <= 0) return emptyList()
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonObject ?: return emptyList()
        val resultType = data["resultType"]?.jsonPrimitive?.content
        val results = data["result"]?.jsonArray ?: return emptyList()

        val rows = mutableListOf<Map<String, JsonElement>>()
        for (result in results) {
            val metric = result.jsonObject["metric"]?.jsonObject ?: JsonObject(emptyMap())
            val metricName = metric["__name__"]?.jsonPrimitive?.content ?: "value"

            when (resultType) {
                "matrix" -> {
                    val values = result.jsonObject["values"]?.jsonArray ?: continue
                    appendPrometheusMatrixPoints(values, metric, metricName, rows, limit)
                }
                "vector" -> appendPrometheusVectorRow(result.jsonObject, metric, metricName, rows)
            }
            if (rows.size >= limit) break
        }
        return rows
    }

    private fun appendPrometheusMatrixPoints(
        values: JsonArray,
        metric: JsonObject,
        metricName: String,
        rows: MutableList<Map<String, JsonElement>>,
        limit: Int,
    ) {
        for (point in values) {
            val arr = point.jsonArray
            val row = mutableMapOf<String, JsonElement>()
            row["time_bucket"] = promTimestampToMs(arr[0])
            row[metricName] = promValueToNumber(arr[1])
            for ((k, v) in metric) {
                if (k != "__name__") row[k] = v
            }
            rows.add(row)
            if (rows.size >= limit) return
        }
    }

    private fun appendPrometheusVectorRow(
        resultObj: JsonObject,
        metric: JsonObject,
        metricName: String,
        rows: MutableList<Map<String, JsonElement>>,
    ) {
        val value = resultObj["value"]?.jsonArray ?: return
        val row = mutableMapOf<String, JsonElement>()
        row["time_bucket"] = promTimestampToMs(value[0])
        row[metricName] = promValueToNumber(value[1])
        for ((k, v) in metric) {
            if (k != "__name__") row[k] = v
        }
        rows.add(row)
    }

    private fun promTimestampToMs(element: JsonElement): JsonElement {
        val sec = element.jsonPrimitive.doubleOrNull ?: return element
        return JsonPrimitive((sec * MILLIS_PER_SECOND).toLong())
    }

    private fun promValueToNumber(element: JsonElement): JsonElement {
        val str = element.jsonPrimitive.contentOrNull ?: return element
        if (str == "NaN" || str == "+Inf" || str == "-Inf") return kotlinx.serialization.json.JsonNull
        return str.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: element
    }

    internal fun resolveRelativeTimeSec(expr: String, nowSec: Long): Long {
        if (expr == "now") return nowSec
        val match = Regex("""^now-(\d+)([smhdwMy])$""").matchEntire(expr) ?: return nowSec
        val amount = match.groupValues[1].toLong()
        val offsetSec = when (match.groupValues[2]) {
            "s" -> amount
            "m" -> amount * SECONDS_PER_MINUTE
            "h" -> amount * SECONDS_PER_HOUR
            "d" -> amount * SECONDS_PER_DAY
            "w" -> amount * SECONDS_PER_WEEK
            "M" -> amount * SECONDS_PER_MONTH_30
            "y" -> amount * SECONDS_PER_YEAR_365
            else -> 0
        }
        return nowSec - offsetSec
    }

    internal fun resolvePrometheusStep(rangeSec: Long): String = when {
        rangeSec <= SECONDS_PER_HOUR -> "15s"
        rangeSec <= SECONDS_SIX_HOURS -> "1m"
        rangeSec <= SECONDS_PER_DAY -> "5m"
        rangeSec <= SECONDS_PER_WEEK -> "1h"
        else -> "1d"
    }
}
