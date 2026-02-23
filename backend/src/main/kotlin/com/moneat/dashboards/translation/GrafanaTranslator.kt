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
import com.moneat.dashboards.models.DataSource
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
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class GrafanaTranslator : DashboardTranslator {

    private val widgetTypeMap = mapOf(
        "timeseries" to "timeseries",
        "barchart" to "bar",
        "piechart" to "donut",
        "stat" to "stat",
        "table" to "table",
        "heatmap" to "heatmap",
        "text" to "text",
        "gauge" to "stat",
        "bargauge" to "bar",
        "graph" to "timeseries",
        "logs" to "table"
    )

    private val reverseWidgetTypeMap = mapOf(
        "timeseries" to "timeseries",
        "bar" to "barchart",
        "donut" to "piechart",
        "stat" to "stat",
        "table" to "table",
        "heatmap" to "heatmap",
        "text" to "text",
        "toplist" to "table"
    )

    override fun import(json: JsonObject): DashboardImportResult {
        val warnings = mutableListOf<String>()

        val title = json["title"]?.jsonPrimitive?.contentOrNull ?: "Imported Grafana Dashboard"
        val description = json["description"]?.jsonPrimitive?.contentOrNull

        val panels = json["panels"]?.jsonArray ?: JsonArray(emptyList())

        // Flatten: row panels may contain nested panels; import rows as text widgets
        val allPanels = mutableListOf<JsonObject>()
        panels.forEach { element ->
            val panel = element.jsonObject
            val type = panel["type"]?.jsonPrimitive?.contentOrNull
            if (type == "row") {
                allPanels.add(panel)
                // Extract nested panels from collapsed rows
                panel["panels"]?.jsonArray?.forEach { nested ->
                    allPanels.add(nested.jsonObject)
                }
            } else {
                allPanels.add(panel)
            }
        }

        val widgets = allPanels.mapIndexedNotNull { index, panel ->
            try {
                importPanel(panel, index, warnings)
            } catch (e: Exception) {
                warnings.add("Panel $index: failed to import - ${e.message}")
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

        val variables = parseGrafanaVariables(json)

        return DashboardImportResult(dashboard, warnings, variables)
    }

    private fun importPanel(
        panelJson: JsonObject,
        index: Int,
        warnings: MutableList<String>
    ): WidgetResponse? {
        val grafanaType = panelJson["type"]?.jsonPrimitive?.contentOrNull ?: "timeseries"

        // Row panels become full-width text section headers
        if (grafanaType == "row") {
            val rowTitle = panelJson["title"]?.jsonPrimitive?.contentOrNull ?: "Section"
            val gridPos = panelJson["gridPos"]?.jsonObject
            val grafanaY = gridPos?.get("y")?.jsonPrimitive?.intOrNull ?: 0
            val gridY = (grafanaY + 2) / 3
            return WidgetResponse(
                id = 0,
                dashboardId = 0,
                title = rowTitle,
                widgetType = "text",
                gridX = 0,
                gridY = gridY,
                gridW = 12,
                gridH = 1,
                queryConfigs = emptyList(),
                displayConfig = mapOf("content" to "## $rowTitle"),
                sortOrder = index
            )
        }

        val moneatType = widgetTypeMap[grafanaType]
        if (moneatType == null) {
            warnings.add("Panel $index: unsupported type '$grafanaType', imported as 'text'")
        }

        val panelTitle = panelJson["title"]?.jsonPrimitive?.contentOrNull

        // Grafana uses 24-col grid, Moneat uses 12-col
        // Grafana height units are also larger (1 = ~30px), scale down by ~3
        // Y positions must be scaled by the same factor as height to keep panels packed
        val gridPos = panelJson["gridPos"]?.jsonObject
        val gridX = (gridPos?.get("x")?.jsonPrimitive?.intOrNull ?: 0) / 2
        val grafanaY = gridPos?.get("y")?.jsonPrimitive?.intOrNull ?: 0
        val gridY = (grafanaY + 2) / 3
        val gridW = ((gridPos?.get("w")?.jsonPrimitive?.intOrNull ?: 12) + 1) / 2
        val grafanaH = gridPos?.get("h")?.jsonPrimitive?.intOrNull ?: 4
        val gridH = (grafanaH + 2) / 3 // Scale down: 9 → 3, 12 → 4, 6 → 2

        val queryConfig = parseGrafanaTargets(panelJson, warnings, index)
        val displayConfig = extractDisplayConfig(panelJson)

        val minH = if (moneatType == "stat") 2 else 3
        return WidgetResponse(
            id = 0,
            dashboardId = 0,
            title = panelTitle,
            widgetType = moneatType ?: "text",
            gridX = gridX.coerceIn(0, 11),
            gridY = gridY,
            gridW = gridW.coerceIn(1, 12),
            gridH = gridH.coerceIn(minH, 12),
            queryConfigs = listOf(queryConfig),
            displayConfig = displayConfig,
            sortOrder = index
        )
    }

    private fun extractDisplayConfig(panelJson: JsonObject): Map<String, String> {
        val config = mutableMapOf<String, String>()

        val defaults = panelJson["fieldConfig"]?.jsonObject
            ?.get("defaults")?.jsonObject
            ?.get("custom")?.jsonObject

        defaults?.get("drawStyle")?.jsonPrimitive?.contentOrNull?.let { config["drawStyle"] = it }
        defaults?.get("fillOpacity")?.jsonPrimitive?.intOrNull?.let { config["fillOpacity"] = it.toString() }
        defaults?.get(
            "stacking"
        )?.jsonObject?.get("mode")?.jsonPrimitive?.contentOrNull?.let { config["stacking"] = it }
        defaults?.get(
            "scaleDistribution"
        )?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull?.let { config["scaleType"] = it }

        val options = panelJson["options"]?.jsonObject
        options?.get(
            "legend"
        )?.jsonObject?.get("placement")?.jsonPrimitive?.contentOrNull?.let { config["legendPlacement"] = it }

        return config
    }

    internal fun parseGrafanaTargets(
        panelJson: JsonObject,
        warnings: MutableList<String>,
        panelIndex: Int
    ): QueryDsl {
        val targets = panelJson["targets"]?.jsonArray

        if (targets.isNullOrEmpty()) {
            return QueryDsl(
                dataSource = "events",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count"))
            )
        }

        val firstTarget = targets.first().jsonObject

        // Check for pre-mapped datasource (frontend replaces with strings like "custom:Prometheus")
        // Check target-level datasource first, then fall back to panel-level
        val targetDs = firstTarget["datasource"]
        val panelDs = panelJson["datasource"]
        val datasource = targetDs ?: panelDs

        val preMappedDataSource = when (datasource) {
            is JsonPrimitive if datasource.isString -> {
                logger.info("Panel $panelIndex: found pre-mapped string datasource: ${datasource.content}")
                datasource.content
            }

            is JsonObject if datasource["type"]?.jsonPrimitive?.contentOrNull?.startsWith("custom:") == true -> {
                val dsType = datasource["type"]?.jsonPrimitive?.content
                logger.info("Panel $panelIndex: found pre-mapped object datasource: $dsType")
                dsType
            }

            else -> {
                logger.info("Panel $panelIndex: no pre-mapped datasource found, datasource=$datasource")
                null
            }
        }

        // Try to parse SQL-style query (common with ClickHouse datasource)
        val rawSql = firstTarget["rawSql"]?.jsonPrimitive?.contentOrNull
        if (rawSql != null) {
            val parsed = parseGrafanaSql(rawSql, warnings, panelIndex)
            // Use pre-mapped datasource if available
            return if (preMappedDataSource != null) {
                parsed.copy(dataSource = preMappedDataSource)
            } else {
                parsed
            }
        }

        // Try to parse PromQL expression
        val expr = firstTarget["expr"]?.jsonPrimitive?.contentOrNull
        if (expr != null) {
            val parsed = parsePromQL(expr, warnings, panelIndex)
            // Use pre-mapped datasource if available
            return if (preMappedDataSource != null) {
                parsed.copy(dataSource = preMappedDataSource)
            } else {
                parsed
            }
        }

        // Try generic query field
        val query = firstTarget["query"]?.jsonPrimitive?.contentOrNull
        if (query != null) {
            warnings.add("Panel $panelIndex: generic query stored as rawQuery")
            return QueryDsl(
                dataSource = preMappedDataSource ?: "events",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                rawQuery = query
            )
        }

        warnings.add("Panel $panelIndex: no recognizable query target")
        return QueryDsl(
            dataSource = preMappedDataSource ?: "events",
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count"))
        )
    }

    internal fun parseGrafanaSql(
        rawSql: String,
        warnings: MutableList<String>,
        panelIndex: Int
    ): QueryDsl {
        // Best-effort parse of SQL SELECT statements
        val sql = rawSql.trim().lowercase()

        // Extract the table name but preserve it as-is — it may refer to a custom data source
        val tableMatch = Regex("""from\s+(\w+)""").find(sql)
        val tableName = tableMatch?.groupValues?.get(1) ?: "events"
        val dataSource = DataSource.fromString(tableName)?.tableName ?: tableName

        // Store as rawQuery since full SQL parsing is complex
        warnings.add("Panel $panelIndex: SQL query imported as rawQuery for manual review")
        return QueryDsl(
            dataSource = dataSource,
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
            groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "auto")),
            rawQuery = rawSql,
            limit = 5000
        )
    }

    internal fun parsePromQL(
        expr: String,
        warnings: MutableList<String>,
        panelIndex: Int
    ): QueryDsl {
        // PromQL queries need to be executed against Prometheus, not ClickHouse
        // Always store the original expression as rawQuery and use a marker datasource
        val promMatch = Regex("""(\w+)\(([^{(]+?)(?:\{([^}]*)\})?(?:\[([^]]*)])?\)""").find(expr)

        if (promMatch == null) {
            warnings.add("Panel $panelIndex: couldn't parse PromQL '$expr', stored as rawQuery")
            return QueryDsl(
                dataSource = "__prometheus",
                metrics = listOf(MetricDef(AggFunction.AVG, alias = "value")),
                rawQuery = expr,
                limit = 5000
            )
        }

        val fn = promMatch.groupValues[1]
        val metricName = promMatch.groupValues[2].trim()
        val labelStr = promMatch.groupValues[3]

        val aggFunction = mapPromFunction(fn)

        val filters = mutableListOf<FilterDef>()
        if (labelStr.isNotBlank()) {
            labelStr.split(",").forEach { label ->
                val parts = label.trim().split("=", limit = 2)
                if (parts.size == 2) {
                    val value = parts[1].trim().removeSurrounding("\"")
                    filters.add(FilterDef(parts[0].trim(), FilterOp.EQ, value))
                }
            }
        }

        return QueryDsl(
            dataSource = "__prometheus",
            metrics = listOf(MetricDef(aggFunction, mapPromMetricField(metricName), "value")),
            groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "auto")),
            filters = filters,
            rawQuery = expr,
            limit = 5000
        )
    }

    private fun mapPromFunction(fn: String): AggFunction = when (fn.lowercase()) {
        "rate", "irate", "increase" -> AggFunction.AVG
        "sum" -> AggFunction.SUM
        "avg" -> AggFunction.AVG
        "min" -> AggFunction.MIN
        "max" -> AggFunction.MAX
        "count" -> AggFunction.COUNT
        "histogram_quantile" -> AggFunction.P95
        else -> AggFunction.AVG
    }

    private fun mapPromMetricField(metricName: String): String? = when {
        metricName.contains("cpu") -> "cpu_percent"
        metricName.contains("memory") || metricName.contains("mem") -> "mem_used"
        metricName.contains("disk") -> "disk_used"
        metricName.contains("network_receive") || metricName.contains("net_recv") -> "net_recv_bytes"
        metricName.contains("network_transmit") || metricName.contains("net_sent") -> "net_sent_bytes"
        metricName.contains("duration") || metricName.contains("latency") -> "duration_ms"
        else -> null
    }

    internal fun parseGrafanaVariables(json: JsonObject): List<DashboardVariable> {
        val templating = json["templating"]?.jsonObject ?: return emptyList()
        val list = templating["list"]?.jsonArray ?: return emptyList()

        return list.mapNotNull { element ->
            try {
                val varObj = element.jsonObject
                val name = varObj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val type = varObj["type"]?.jsonPrimitive?.contentOrNull ?: "custom"
                val label = varObj["label"]?.jsonPrimitive?.contentOrNull
                val query = varObj["query"]?.let { q ->
                    when (q) {
                        is JsonPrimitive -> q.contentOrNull
                        is JsonObject -> q["query"]?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                }
                val current = varObj["current"]?.jsonObject?.get("value")?.let { v ->
                    when (v) {
                        is JsonPrimitive -> v.contentOrNull
                        else -> null
                    }
                }
                val options = varObj["options"]?.jsonArray?.mapNotNull { opt ->
                    opt.jsonObject["value"]?.jsonPrimitive?.contentOrNull
                } ?: emptyList()

                val datasource = varObj["datasource"]?.let { ds ->
                    when (ds) {
                        is JsonPrimitive -> ds.contentOrNull
                        is JsonObject -> ds["type"]?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                }

                val supportedTypes = setOf("query", "custom", "textbox", "constant")
                DashboardVariable(
                    name = name,
                    label = label,
                    type = if (type in supportedTypes) type else "custom",
                    query = query,
                    defaultValue = current,
                    current = current,
                    options = options.filter { it != "\$__all" },
                    datasource = datasource
                )
            } catch (e: Exception) {
                logger.warn { "Failed to parse Grafana variable: ${e.message}" }
                null
            }
        }
    }

    override fun export(dashboard: DashboardResponse): JsonObject {
        val panels = dashboard.widgets.mapIndexed { index, widget ->
            buildJsonObject {
                put("id", index + 1)
                put("type", reverseWidgetTypeMap[widget.widgetType] ?: "timeseries")
                widget.title?.let { put("title", it) }
                put(
                    "gridPos",
                    buildJsonObject {
                        put("x", widget.gridX * 2)
                        put("y", widget.gridY)
                        put("w", widget.gridW * 2)
                        put("h", widget.gridH)
                    }
                )
                put(
                    "targets",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("refId", "A")
                                put(
                                    "rawSql",
                                    buildGrafanaSql(
                                        widget.queryConfigs.firstOrNull() ?: QueryDsl(dataSource = "events")
                                    )
                                )
                                put("format", "time_series")
                            }
                        )
                    }
                )
                put(
                    "datasource",
                    buildJsonObject {
                        put("type", "clickhouse")
                        put("uid", "moneat-clickhouse")
                    }
                )
            }
        }

        return buildJsonObject {
            put("title", dashboard.title)
            dashboard.description?.let { put("description", it) }
            put("panels", JsonArray(panels))
            if (dashboard.variables.isNotEmpty()) {
                put("templating", buildJsonObject {
                    put("list", buildJsonArray {
                        dashboard.variables.forEach { v ->
                            add(buildJsonObject {
                                put("name", v.name)
                                v.label?.let { put("label", it) }
                                put("type", v.type)
                                v.query?.let { put("query", it) }
                                v.current?.let { cur ->
                                    put("current", buildJsonObject {
                                        put("value", cur)
                                        put("text", cur)
                                    })
                                }
                                put("options", buildJsonArray {
                                    v.options.forEach { opt ->
                                        add(buildJsonObject {
                                            put("value", opt)
                                            put("text", opt)
                                        })
                                    }
                                })
                            })
                        }
                    })
                })
            }
            put("schemaVersion", 39)
            put("version", 1)
            put("timezone", "browser")
        }
    }

    internal fun buildGrafanaSql(dsl: QueryDsl): String {
        if (dsl.rawQuery != null) return dsl.rawQuery

        val metrics = dsl.metrics.joinToString(", ") { m ->
            val fn = m.function.toClickHouse(m.field)
            val alias = m.alias ?: "value"
            "$fn AS $alias"
        }.ifEmpty { "count() AS count" }

        val table = dsl.dataSource
        val where = dsl.filters.joinToString(" AND ") { f ->
            "${f.field} ${f.op.value} '${f.value ?: ""}'"
        }

        val groupBy = dsl.groupBy.joinToString(", ") { gb ->
            if (gb.type == GroupByType.TIME) "time_bucket" else gb.field
        }

        return buildString {
            append("SELECT ")
            if (dsl.groupBy.any { it.type == GroupByType.TIME }) {
                val interval = dsl.groupBy.find { it.type == GroupByType.TIME }?.interval ?: "1 HOUR"
                append("toStartOfInterval(timestamp, INTERVAL $interval) AS time_bucket, ")
            }
            append(metrics)
            append(" FROM $table")
            if (where.isNotBlank()) append(" WHERE $where")
            if (groupBy.isNotBlank()) append(" GROUP BY $groupBy")
            append(" ORDER BY ")
            if (dsl.groupBy.any { it.type == GroupByType.TIME }) {
                append("time_bucket ASC")
            } else {
                append("1 DESC")
            }
            append(" LIMIT ${dsl.limit}")
        }
    }
}
