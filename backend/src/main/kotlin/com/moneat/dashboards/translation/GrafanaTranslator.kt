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

import com.moneat.dashboards.models.*
import kotlinx.serialization.json.*
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
        "graph" to "timeseries"
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

        // Flatten: row panels may contain nested panels
        val allPanels = mutableListOf<JsonObject>()
        panels.forEach { element ->
            val panel = element.jsonObject
            val type = panel["type"]?.jsonPrimitive?.contentOrNull
            if (type == "row") {
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

        return DashboardImportResult(dashboard, warnings)
    }

    private fun importPanel(
        panelJson: JsonObject,
        index: Int,
        warnings: MutableList<String>
    ): WidgetResponse? {
        val grafanaType = panelJson["type"]?.jsonPrimitive?.contentOrNull ?: "timeseries"

        // Skip row panels — they're layout separators, not actual widgets
        if (grafanaType == "row") {
            return null
        }

        val moneatType = widgetTypeMap[grafanaType]
        if (moneatType == null) {
            warnings.add("Panel $index: unsupported type '$grafanaType', imported as 'text'")
        }

        val panelTitle = panelJson["title"]?.jsonPrimitive?.contentOrNull

        // Grafana uses 24-col grid, Moneat uses 12-col
        // Grafana height units are also larger (1 = ~30px), scale down by ~3
        val gridPos = panelJson["gridPos"]?.jsonObject
        val gridX = (gridPos?.get("x")?.jsonPrimitive?.intOrNull ?: 0) / 2
        val gridY = gridPos?.get("y")?.jsonPrimitive?.intOrNull ?: 0
        val gridW = ((gridPos?.get("w")?.jsonPrimitive?.intOrNull ?: 12) + 1) / 2
        val grafanaH = gridPos?.get("h")?.jsonPrimitive?.intOrNull ?: 4
        val gridH = (grafanaH + 2) / 3  // Scale down: 9 → 3, 12 → 4, 6 → 2

        val queryConfig = parseGrafanaTargets(panelJson, warnings, index)

        return WidgetResponse(
            id = 0,
            dashboardId = 0,
            title = panelTitle,
            widgetType = moneatType ?: "text",
            gridX = gridX.coerceIn(0, 11),
            gridY = gridY,
            gridW = gridW.coerceIn(1, 12),
            gridH = gridH.coerceIn(1, 12),
            queryConfig = queryConfig,
            sortOrder = index
        )
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

        // Try to parse SQL-style query (common with ClickHouse datasource)
        val rawSql = firstTarget["rawSql"]?.jsonPrimitive?.contentOrNull
        if (rawSql != null) {
            return parseGrafanaSql(rawSql, warnings, panelIndex)
        }

        // Try to parse PromQL expression
        val expr = firstTarget["expr"]?.jsonPrimitive?.contentOrNull
        if (expr != null) {
            return parsePromQL(expr, warnings, panelIndex)
        }

        // Try generic query field
        val query = firstTarget["query"]?.jsonPrimitive?.contentOrNull
        if (query != null) {
            warnings.add("Panel $panelIndex: generic query stored as rawQuery")
            return QueryDsl(
                dataSource = "events",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                rawQuery = query
            )
        }

        warnings.add("Panel $panelIndex: no recognizable query target")
        return QueryDsl(
            dataSource = "events",
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
            rawQuery = rawSql
        )
    }

    internal fun parsePromQL(
        expr: String,
        warnings: MutableList<String>,
        panelIndex: Int
    ): QueryDsl {
        // PromQL queries need to be executed against Prometheus, not ClickHouse
        // Always store the original expression as rawQuery and use a marker datasource
        val promMatch = Regex("""(\w+)\(([^{(]+?)(?:\{([^}]*)\})?(?:\[([^\]]*)\])?\)""").find(expr)

        if (promMatch == null) {
            warnings.add("Panel $panelIndex: couldn't parse PromQL '$expr', stored as rawQuery")
            return QueryDsl(
                dataSource = "__prometheus",
                metrics = listOf(MetricDef(AggFunction.AVG, alias = "value")),
                rawQuery = expr
            )
        }

        val fn = promMatch.groupValues[1]
        val metricName = promMatch.groupValues[2].trim()
        val labelStr = promMatch.groupValues[3]

        val aggFunction = mapPromFunction(fn)

        val filters = mutableListOf<FilterDef>()
        if (!labelStr.isNullOrBlank()) {
            labelStr.split(",").forEach { label ->
                val parts = label.trim().split("=", limit = 2)
                if (parts.size == 2) {
                    val value = parts[1].trim().removeSurrounding("\"")
                    filters.add(FilterDef(parts[0].trim(), FilterOp.EQ, value))
                }
            }
        }

        // Always store original PromQL and use marker datasource
        return QueryDsl(
            dataSource = "__prometheus",
            metrics = listOf(MetricDef(aggFunction, mapPromMetricField(metricName), "value")),
            groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "auto")),
            filters = filters,
            rawQuery = expr
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

    private fun resolveDataSourceFromPromMetric(metricName: String): String = when {
        metricName.contains("container") -> "container_metrics"
        metricName.contains("cpu") || metricName.contains("mem") ||
            metricName.contains("disk") || metricName.contains("node_") -> "system_metrics"
        metricName.contains("http") || metricName.contains("request") -> "spans"
        metricName.contains("log") -> "logs"
        else -> "system_metrics"
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

    override fun export(dashboard: DashboardResponse): JsonObject {
        val panels = dashboard.widgets.mapIndexed { index, widget ->
            buildJsonObject {
                put("id", index + 1)
                put("type", reverseWidgetTypeMap[widget.widgetType] ?: "timeseries")
                widget.title?.let { put("title", it) }
                put("gridPos", buildJsonObject {
                    put("x", widget.gridX * 2)
                    put("y", widget.gridY)
                    put("w", widget.gridW * 2)
                    put("h", widget.gridH)
                })
                put("targets", buildJsonArray {
                    add(buildJsonObject {
                        put("refId", "A")
                        put("rawSql", buildGrafanaSql(widget.queryConfig))
                        put("format", "time_series")
                    })
                })
                put("datasource", buildJsonObject {
                    put("type", "clickhouse")
                    put("uid", "moneat-clickhouse")
                })
            }
        }

        return buildJsonObject {
            put("title", dashboard.title)
            dashboard.description?.let { put("description", it) }
            put("panels", JsonArray(panels))
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
