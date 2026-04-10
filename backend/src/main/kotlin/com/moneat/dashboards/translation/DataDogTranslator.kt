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

package com.moneat.dashboards.translation

import com.moneat.dashboards.models.AggFunction
import com.moneat.dashboards.models.DashboardImportResult
import com.moneat.dashboards.models.DashboardResponse
import com.moneat.dashboards.models.DashboardVariable
import com.moneat.dashboards.models.FilterDef
import com.moneat.dashboards.models.FilterOp
import com.moneat.dashboards.models.GroupByDef
import com.moneat.dashboards.models.GroupByType
import com.moneat.dashboards.models.MetricDef
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.WidgetResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.moneat.utils.suspendRunCatching

private const val DD_DEFAULT_WIDGET_W = 6
private const val DD_DEFAULT_WIDGET_H = 4
private const val DD_MAX_GRID_COL = 11
private const val DD_GRID_COLS = 12

class DataDogTranslator : DashboardTranslator {

    private val widgetTypeMap = mapOf(
        "timeseries" to "timeseries",
        "toplist" to "toplist",
        "query_value" to "stat",
        "table" to "table",
        "heatmap" to "heatmap",
        "distribution" to "bar",
        "pie" to "donut",
        "note" to "text",
        "free_text" to "text",
        "group" to "text"
    )

    private val reverseWidgetTypeMap = mapOf(
        "timeseries" to "timeseries",
        "toplist" to "toplist",
        "stat" to "query_value",
        "table" to "table",
        "heatmap" to "heatmap",
        "bar" to "distribution",
        "donut" to "pie",
        "text" to "note"
    )

    // Maps DD metric namespace prefixes to Moneat data sources
    private val metricNamespaceMap = mapOf(
        "system." to "metrics",
        "trace." to "spans",
        "logs." to "logs",
        "container." to "containers",
        "network." to "metrics"
    )

    override fun import(json: JsonObject): DashboardImportResult {
        val warnings = mutableListOf<String>()

        val title = json["title"]?.jsonPrimitive?.contentOrNull ?: "Imported DataDog Dashboard"
        val description = json["description"]?.jsonPrimitive?.contentOrNull

        val ddWidgets = json["widgets"]?.jsonArray ?: JsonArray(emptyList())

        val widgets = ddWidgets.mapIndexedNotNull { index, element ->
            suspendRunCatching {
                importWidget(element.jsonObject, index, warnings)
            }.getOrElse { e ->
                warnings.add("Widget $index: failed to import - ${e.message}")
                null
            }
        }

        val dashboard = DashboardResponse(
            id = 0,
            orgId = 0,
            title = title,
            description = description,
            layoutType = "grid",
            createdBy = 0,
            createdAt = "",
            updatedAt = "",
            widgets = widgets
        )

        val variables = parseDataDogVariables(json)

        return DashboardImportResult(dashboard, warnings, variables)
    }

    private fun importWidget(
        widgetJson: JsonObject,
        index: Int,
        warnings: MutableList<String>
    ): WidgetResponse {
        val definition = widgetJson["definition"]?.jsonObject ?: JsonObject(emptyMap())
        val layout = widgetJson["layout"]?.jsonObject

        val ddType = definition["type"]?.jsonPrimitive?.contentOrNull ?: "timeseries"
        val moneatType = widgetTypeMap[ddType]
        if (moneatType == null) {
            warnings.add("Widget $index: unsupported type '$ddType', imported as 'text'")
        }

        val widgetTitle = definition["title"]?.jsonPrimitive?.contentOrNull

        // Parse grid position from DD layout (DD uses 12-col grid)
        val gridX = layout?.get("x")?.jsonPrimitive?.intOrNull ?: 0
        val gridY = layout?.get("y")?.jsonPrimitive?.intOrNull ?: 0
        val gridW = layout?.get("width")?.jsonPrimitive?.intOrNull ?: DD_DEFAULT_WIDGET_W
        val gridH = layout?.get("height")?.jsonPrimitive?.intOrNull ?: DD_DEFAULT_WIDGET_H

        val queryConfig = parseDataDogQuery(definition, warnings, index)

        return WidgetResponse(
            id = 0,
            dashboardId = 0,
            title = widgetTitle,
            widgetType = moneatType ?: "text",
            gridX = gridX.coerceIn(0, DD_MAX_GRID_COL),
            gridY = gridY,
            gridW = gridW.coerceIn(1, DD_GRID_COLS),
            gridH = gridH.coerceIn(1, DD_GRID_COLS),
            queryConfigs = listOf(queryConfig),
            sortOrder = index
        )
    }

    internal fun parseDataDogQuery(
        definition: JsonObject,
        warnings: MutableList<String>,
        widgetIndex: Int
    ): QueryDsl {
        val requests = definition["requests"]?.jsonArray

        if (requests.isNullOrEmpty()) {
            return QueryDsl(
                dataSource = "events",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count"))
            )
        }

        val firstRequest = requests.first().jsonObject
        val queryStr = firstRequest["q"]?.jsonPrimitive?.contentOrNull
            ?: firstRequest["queries"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("query")?.jsonPrimitive?.contentOrNull

        if (queryStr == null) {
            warnings.add("Widget $widgetIndex: no query string found, using default")
            return QueryDsl(
                dataSource = "events",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count"))
            )
        }

        return parseDataDogQueryString(queryStr, warnings, widgetIndex)
    }

    internal fun parseDataDogQueryString(
        queryStr: String,
        warnings: MutableList<String>,
        widgetIndex: Int
    ): QueryDsl {
        // DD query format: "function:metric.name{tag:value,tag2:value2}.as_count()"
        val functionMatch = Regex("""^(\w+):(.+)$""").find(queryStr)
        val aggFunction: AggFunction
        val metricPart: String

        if (functionMatch != null) {
            aggFunction = mapDdFunction(functionMatch.groupValues[1])
            metricPart = functionMatch.groupValues[2]
        } else {
            aggFunction = AggFunction.AVG
            metricPart = queryStr
        }

        // Extract metric name and tags: metric.name{tag:value}
        val metricMatch = Regex("""^([^{]+)(?:\{([^}]*)\})?""").find(metricPart)
        val metricName = metricMatch?.groupValues?.get(1)?.trim()?.removeSuffix(".as_count()") ?: "count"
        val tagStr = metricMatch?.groupValues?.get(2)

        val dataSource = resolveDataSource(metricName)
        val field = mapMetricField(metricName, dataSource)

        val filters = mutableListOf<FilterDef>()
        if (!tagStr.isNullOrBlank() && tagStr != "*") {
            tagStr.split(",").forEach { tag ->
                val parts = tag.trim().split(":", limit = 2)
                if (parts.size == 2) {
                    filters.add(FilterDef(parts[0].trim(), FilterOp.EQ, parts[1].trim()))
                }
            }
        }

        if (field == null && metricName.isNotBlank()) {
            warnings.add("Widget $widgetIndex: metric '$metricName' mapped as raw query")
            return QueryDsl(
                dataSource = dataSource,
                metrics = listOf(MetricDef(aggFunction, alias = "value")),
                filters = filters,
                rawQuery = queryStr
            )
        }

        return QueryDsl(
            dataSource = dataSource,
            metrics = listOf(MetricDef(aggFunction, field, alias = "value")),
            groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "auto")),
            filters = filters
        )
    }

    private fun mapDdFunction(fn: String): AggFunction = when (fn.lowercase()) {
        "avg" -> AggFunction.AVG
        "sum" -> AggFunction.SUM
        "min" -> AggFunction.MIN
        "max" -> AggFunction.MAX
        "count" -> AggFunction.COUNT
        "p50" -> AggFunction.P50
        "p75" -> AggFunction.P75
        "p90" -> AggFunction.P90
        "p95" -> AggFunction.P95
        "p99" -> AggFunction.P99
        else -> AggFunction.AVG
    }

    private fun resolveDataSource(metricName: String): String {
        for ((prefix, source) in metricNamespaceMap) {
            if (metricName.startsWith(prefix)) return source
        }
        return "events"
    }

    private fun mapMetricField(metricName: String, dataSource: String): String? {
        return when {
            metricName.contains("cpu") -> "cpu_percent"
            metricName.contains("mem") -> "mem_used"
            metricName.contains("disk") -> "disk_used"
            metricName.contains("net.recv") || metricName.contains("net_recv") -> "net_recv_bytes"
            metricName.contains("net.sent") || metricName.contains("net_sent") -> "net_sent_bytes"
            metricName.contains("duration") || metricName.contains("latency") -> "duration_ms"
            metricName.contains("load") -> "load_1"
            dataSource == "events" -> null
            else -> null
        }
    }

    internal fun parseDataDogVariables(json: JsonObject): List<DashboardVariable> {
        val templateVars = json["template_variables"]?.jsonArray ?: return emptyList()

        return templateVars.mapNotNull { element ->
            suspendRunCatching {
                val varObj = element.jsonObject
                val name = varObj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val defaultValue = varObj["default"]?.jsonPrimitive?.contentOrNull
                val prefix = varObj["prefix"]?.jsonPrimitive?.contentOrNull
                val availableValues = varObj["available_values"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                } ?: emptyList()

                DashboardVariable(
                    name = name,
                    label = prefix?.let { "$it:$name" },
                    type = if (availableValues.isNotEmpty()) "custom" else "textbox",
                    defaultValue = defaultValue,
                    current = defaultValue,
                    options = availableValues,
                    datasource = null
                )
            }.getOrElse { _ ->
                null
            }
        }
    }

    override fun export(dashboard: DashboardResponse): JsonObject {
        val widgets = dashboard.widgets.map { widget ->
            buildJsonObject {
                put(
                    "definition",
                    buildJsonObject {
                        put("type", reverseWidgetTypeMap[widget.widgetType] ?: "timeseries")
                        widget.title?.let { put("title", it) }
                        put(
                            "requests",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put(
                                            "q",
                                            buildDdQueryString(
                                                widget.queryConfigs.firstOrNull() ?: QueryDsl(dataSource = "events")
                                            )
                                        )
                                        put("display_type", widget.widgetType)
                                    }
                                )
                            }
                        )
                    }
                )
                put(
                    "layout",
                    buildJsonObject {
                        put("x", widget.gridX)
                        put("y", widget.gridY)
                        put("width", widget.gridW)
                        put("height", widget.gridH)
                    }
                )
            }
        }

        return buildJsonObject {
            put("title", dashboard.title)
            dashboard.description?.let { put("description", it) }
            put("layout_type", "ordered")
            put("widgets", JsonArray(widgets))
            if (dashboard.variables.isNotEmpty()) {
                put(
                    "template_variables",
                    buildJsonArray {
                        dashboard.variables.forEach { v ->
                            add(
                                buildJsonObject {
                                    put("name", v.name)
                                    v.defaultValue?.let { put("default", it) }
                                    v.label?.let { label ->
                                        val prefix = label.substringBefore(":", label)
                                        put("prefix", prefix)
                                    }
                                    if (v.options.isNotEmpty()) {
                                        put(
                                            "available_values",
                                            buildJsonArray {
                                                v.options.forEach { opt -> add(JsonPrimitive(opt)) }
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    internal fun buildDdQueryString(dsl: QueryDsl): String {
        val metric = dsl.metrics.firstOrNull() ?: return "count:events{*}"
        val fn = metric.function.value
        val field = metric.field ?: dsl.dataSource
        val filterStr = dsl.filters.joinToString(",") { f ->
            "${f.field}:${f.value ?: f.values?.firstOrNull() ?: "*"}"
        }.ifEmpty { "*" }
        return "$fn:$field{$filterStr}"
    }
}
