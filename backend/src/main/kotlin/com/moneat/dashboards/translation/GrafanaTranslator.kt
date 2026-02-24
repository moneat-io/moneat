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
import kotlinx.serialization.json.JsonElement
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
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

private const val GRAFANA_COLS = 24
private const val MONEAT_COLS = 12
private const val GRAFANA_ROW_PX = 30.0
private const val MONEAT_ROW_PX = 80.0

class GrafanaTranslator : DashboardTranslator {

    private val widgetTypeMap = mapOf(
        "timeseries" to "timeseries",
        "barchart" to "bar",
        "piechart" to "donut",
        "stat" to "stat",
        "table" to "table",
        "heatmap" to "heatmap",
        "text" to "text",
        "gauge" to "gauge",
        "bargauge" to "bar",
        "graph" to "timeseries",
        "logs" to "table"
    )

    private val reverseWidgetTypeMap = mapOf(
        "timeseries" to "timeseries",
        "bar" to "barchart",
        "donut" to "piechart",
        "stat" to "stat",
        "gauge" to "gauge",
        "table" to "table",
        "heatmap" to "heatmap",
        "text" to "text",
        "toplist" to "table",
        "section" to "row"
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

        // Row panels become full-width collapsible section headers
        if (grafanaType == "row") {
            val rowTitle = panelJson["title"]?.jsonPrimitive?.contentOrNull ?: "Section"
            val gridPos = panelJson["gridPos"]?.jsonObject
            val grafanaY = gridPos?.get("y")?.jsonPrimitive?.intOrNull ?: 0
            val gridY = scaleGridValue(grafanaY)
            val collapsed = panelJson["collapsed"]?.jsonPrimitive?.contentOrNull == "true"
            return WidgetResponse(
                id = 0,
                dashboardId = 0,
                title = rowTitle,
                widgetType = "section",
                gridX = 0,
                gridY = gridY,
                gridW = 12,
                gridH = 1,
                queryConfigs = emptyList(),
                displayConfig = mapOf("collapsed" to collapsed.toString()),
                sortOrder = index
            )
        }

        val moneatType = widgetTypeMap[grafanaType]
        if (moneatType == null) {
            warnings.add("Panel $index: unsupported type '$grafanaType', imported as 'text'")
        }

        val panelTitle = panelJson["title"]?.jsonPrimitive?.contentOrNull

        val gridPos = panelJson["gridPos"]?.jsonObject
        val grafanaX = gridPos?.get("x")?.jsonPrimitive?.intOrNull ?: 0
        val grafanaY = gridPos?.get("y")?.jsonPrimitive?.intOrNull ?: 0
        val grafanaW = gridPos?.get("w")?.jsonPrimitive?.intOrNull ?: 12
        val grafanaH = gridPos?.get("h")?.jsonPrimitive?.intOrNull ?: 4

        // Grafana uses a 24-col grid, Moneat uses 12-col.
        // Use floor-aligned scaling: x = floor(gx*12/24), w = floor((gx+gw)*12/24) - x
        // This guarantees adjacent panels share exact column boundaries with no gaps or overflow.
        val gridX = (grafanaX * MONEAT_COLS / GRAFANA_COLS).coerceIn(0, 11)
        val gridY = scaleGridValue(grafanaY)
        val gridXEnd = ((grafanaX + grafanaW) * MONEAT_COLS / GRAFANA_COLS).coerceAtMost(MONEAT_COLS)
        val gridW = (gridXEnd - gridX).coerceAtLeast(1)
        val gridH = scaleGridValue(grafanaH)

        val queryConfigs = parseGrafanaTargets(panelJson, warnings, index)
        val displayConfig = extractDisplayConfig(panelJson)

        val minH = if (moneatType == "stat" || moneatType == "gauge") 1 else 3
        return WidgetResponse(
            id = 0,
            dashboardId = 0,
            title = panelTitle,
            widgetType = moneatType ?: "text",
            gridX = gridX,
            gridY = gridY,
            gridW = gridW,
            gridH = gridH.coerceIn(minH, 12),
            queryConfigs = queryConfigs,
            displayConfig = displayConfig,
            sortOrder = index
        )
    }

    private fun scaleGridValue(grafanaUnits: Int): Int =
        (grafanaUnits * GRAFANA_ROW_PX / MONEAT_ROW_PX).roundToInt()

    private fun extractDisplayConfig(panelJson: JsonObject): Map<String, String> {
        val config = mutableMapOf<String, String>()

        val fieldConfig = panelJson["fieldConfig"]?.jsonObject
        val fieldDefaults = fieldConfig?.get("defaults")?.jsonObject
        val defaults = fieldDefaults?.get("custom")?.jsonObject

        // Unit and decimals from fieldConfig.defaults
        fieldDefaults?.get("unit")?.jsonPrimitive?.contentOrNull?.let { config["unit"] = it }
        fieldDefaults?.get("decimals")?.jsonPrimitive?.intOrNull?.let { config["decimals"] = it.toString() }

        // Thresholds from fieldConfig.defaults.thresholds
        fieldDefaults?.get("thresholds")?.jsonObject?.let { thresholds ->
            val steps = thresholds["steps"]?.jsonArray
            if (steps != null && steps.size > 0) {
                val moneatThresholds = steps.mapNotNull { step ->
                    val stepObj = step.jsonObject
                    val valuePrim = stepObj["value"]?.jsonPrimitive
                    val value = valuePrim?.intOrNull
                        ?: if (valuePrim?.contentOrNull == null) 0 else return@mapNotNull null
                    val color = stepObj["color"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    buildJsonObject {
                        put("value", value)
                        put("color", color)
                    }
                }
                if (moneatThresholds.isNotEmpty()) {
                    config["thresholds"] = JsonArray(moneatThresholds).toString()
                }
            }
        }

        // Value mappings from fieldConfig.defaults.mappings
        fieldDefaults?.get("mappings")?.jsonArray?.let { mappings ->
            val moneatMappings = mappings.mapNotNull { mapping ->
                val mapObj = mapping.jsonObject
                val type = mapObj["type"]?.jsonPrimitive?.contentOrNull
                when (type) {
                    "special" -> {
                        val opts = mapObj["options"]?.jsonObject ?: return@mapNotNull null
                        val match = opts["match"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val text = opts["result"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null
                        val color = opts["result"]?.jsonObject?.get("color")?.jsonPrimitive?.contentOrNull
                        buildJsonObject {
                            put("value", match)
                            put("text", text)
                            color?.let { put("color", it) }
                        }
                    }
                    "value" -> {
                        val opts = mapObj["options"]?.jsonObject ?: return@mapNotNull null
                        opts.entries.firstOrNull()?.let { (key, entry) ->
                            val result = entry.jsonObject["result"]?.jsonObject ?: return@let null
                            val text = result["text"]?.jsonPrimitive?.contentOrNull ?: return@let null
                            val color = result["color"]?.jsonPrimitive?.contentOrNull
                            buildJsonObject {
                                put("value", key)
                                put("text", text)
                                color?.let { put("color", it) }
                            }
                        }
                    }
                    else -> null
                }
            }
            if (moneatMappings.isNotEmpty()) {
                config["valueMappings"] = JsonArray(moneatMappings).toString()
            }
        }

        // Draw style and line width from custom config
        defaults?.get("drawStyle")?.jsonPrimitive?.contentOrNull?.let { config["drawStyle"] = it }
        defaults?.get("lineWidth")?.jsonPrimitive?.intOrNull?.let { config["lineWidth"] = it.toString() }

        // fillOpacity: Grafana uses 0-100 scale, Moneat uses 0-1
        defaults?.get("fillOpacity")?.jsonPrimitive?.intOrNull?.let {
            config["fillOpacity"] = (it / 100.0).toString()
        }

        // Stacking → stackMode (frontend key)
        defaults?.get(
            "stacking"
        )?.jsonObject?.get("mode")?.jsonPrimitive?.contentOrNull?.let { config["stackMode"] = it }

        defaults?.get(
            "scaleDistribution"
        )?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull?.let { config["scaleType"] = it }

        val options = panelJson["options"]?.jsonObject
        options?.get("legend")?.jsonObject?.let { legend ->
            legend["placement"]?.jsonPrimitive?.contentOrNull?.let { config["legendPlacement"] = it }
            legend["displayMode"]?.jsonPrimitive?.contentOrNull?.let { mode ->
                config["legendMode"] = when (mode) {
                    "hidden" -> "hidden"
                    "table" -> "table"
                    else -> "list"
                }
            }
        }

        // Gauge-specific: min/max range
        fieldDefaults?.get("min")?.jsonPrimitive?.intOrNull?.let { config["gaugeMin"] = it.toString() }
        fieldDefaults?.get("max")?.jsonPrimitive?.intOrNull?.let { config["gaugeMax"] = it.toString() }

        return config
    }

    internal fun parseGrafanaTargets(
        panelJson: JsonObject,
        warnings: MutableList<String>,
        panelIndex: Int
    ): List<QueryDsl> {
        val targets = panelJson["targets"]?.jsonArray

        if (targets.isNullOrEmpty()) {
            return listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count"))
                )
            )
        }

        // Resolve datasource: check panel-level, then fall back per-target
        val panelDs = panelJson["datasource"]
        val preMappedDataSource = resolveDatasource(panelDs, panelIndex)

        return targets.mapIndexed { idx, targetEl ->
            val target = targetEl.jsonObject
            val refId = target["refId"]?.jsonPrimitive?.contentOrNull ?: ('A' + idx).toString()
            val legendFormat = target["legendFormat"]?.jsonPrimitive?.contentOrNull

            // Per-target datasource overrides panel-level
            val targetDs = target["datasource"]
            val effectiveDs = resolveDatasource(targetDs, panelIndex) ?: preMappedDataSource

            val parsed = parseTarget(target, warnings, panelIndex, legendFormat)
            val withDs = if (effectiveDs != null) parsed.copy(dataSource = effectiveDs) else parsed
            withDs.copy(refId = refId)
        }
    }

    private fun resolveDatasource(ds: JsonElement?): String? = when (ds) {
        is JsonPrimitive if ds.isString -> ds.content
        is JsonObject if ds["type"]?.jsonPrimitive?.contentOrNull?.startsWith("custom:") == true ->
            ds["type"]?.jsonPrimitive?.content
        else -> null
    }

    private fun parseTarget(
        target: JsonObject,
        warnings: MutableList<String>,
        panelIndex: Int,
        legendFormat: String?
    ): QueryDsl {
        // Try PromQL first (takes priority — rawSql may be a Grafana default template)
        val expr = target["expr"]?.jsonPrimitive?.contentOrNull
        if (!expr.isNullOrBlank()) {
            val parsed = parsePromQL(expr, warnings, panelIndex)
            // Apply legendFormat as the metric alias so the chart uses it as series name
            return if (legendFormat != null && parsed.metrics.isNotEmpty()) {
                parsed.copy(metrics = parsed.metrics.map { it.copy(alias = legendFormat) })
            } else {
                parsed
            }
        }

        // Try SQL
        val rawSql = target["rawSql"]?.jsonPrimitive?.contentOrNull
        if (rawSql != null) {
            return parseGrafanaSql(rawSql, warnings, panelIndex)
        }

        // Try generic query
        val query = target["query"]?.jsonPrimitive?.contentOrNull
        if (query != null) {
            warnings.add("Panel $panelIndex: generic query stored as rawQuery")
            return QueryDsl(
                dataSource = "events",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = legendFormat ?: "count")),
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

        // Normalize whitespace (collapse newlines and runs of spaces into single space)
        val normalized = expr.trim().replace(Regex("""\s+"""), " ")

        // Try function-wrapped: rate(metric{labels}[5m])
        val funcMatch = Regex("""(\w+)\(([^{(]+?)(?:\{([^}]*)\})?(?:\[([^]]*)])?\)""").find(normalized)
        // Try bare metric: metric_name{labels} possibly with math (*100, /other_metric{})
        val bareMatch = Regex("""^([a-zA-Z_]\w[\w.:]+)(?:\{([^}]*)\})?(.*)$""").find(normalized)
        // Try aggregation with by/without: sum by (labels) (inner_expr)
        val aggByMatch = Regex("""^(\w+)\s+(?:by|without)\s*\(([^)]*)\)\s*\((.+)\)$""").find(normalized)

        val (metricName, labelStr, aggFunction) = when {
            aggByMatch != null -> {
                // Parse inner expression recursively for metric name
                val innerExpr = aggByMatch.groupValues[3].trim()
                val innerFunc = Regex("""(\w+)\(([^{(]+?)(?:\{[^}]*\})?(?:\[[^]]*])?.*\)""").find(innerExpr)
                val metric = innerFunc?.groupValues?.getOrNull(2)?.trim() ?: "unknown"
                val innerLabels = Regex("""\{([^}]*)\}""").find(innerExpr)?.groupValues?.get(1) ?: ""
                Triple(metric, innerLabels, mapPromFunction(aggByMatch.groupValues[1]))
            }
            funcMatch != null -> {
                Triple(
                    funcMatch.groupValues[2].trim(),
                    funcMatch.groupValues[3],
                    mapPromFunction(funcMatch.groupValues[1])
                )
            }
            bareMatch != null -> {
                Triple(
                    bareMatch.groupValues[1].trim(),
                    bareMatch.groupValues[2],
                    AggFunction.AVG
                )
            }
            else -> {
                warnings.add("Panel $panelIndex: couldn't parse PromQL '$expr', stored as rawQuery")
                return QueryDsl(
                    dataSource = "__prometheus",
                    metrics = listOf(MetricDef(AggFunction.AVG, alias = "value")),
                    rawQuery = expr,
                    limit = 5000
                )
            }
        }

        val filters = mutableListOf<FilterDef>()
        if (labelStr.isNotBlank()) {
            // Parse label matchers: key="val", key=~"val", key!="val", key!~"val"
            Regex("""(\w+)\s*(=~|!=|!~|=)\s*"([^"]*?)"""").findAll(labelStr).forEach { m ->
                val key = m.groupValues[1]
                val op = when (m.groupValues[2]) {
                    "=~" -> FilterOp.LIKE
                    "!~" -> FilterOp.NOT_LIKE
                    "!=" -> FilterOp.NEQ
                    else -> FilterOp.EQ
                }
                filters.add(FilterDef(key, op, m.groupValues[3]))
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
                put(
                    "templating",
                    buildJsonObject {
                        put(
                            "list",
                            buildJsonArray {
                                dashboard.variables.forEach { v ->
                                    add(
                                        buildJsonObject {
                                            put("name", v.name)
                                            v.label?.let { put("label", it) }
                                            put("type", v.type)
                                            v.query?.let { put("query", it) }
                                            v.current?.let { cur ->
                                                put(
                                                    "current",
                                                    buildJsonObject {
                                                        put("value", cur)
                                                        put("text", cur)
                                                    }
                                                )
                                            }
                                            put(
                                                "options",
                                                buildJsonArray {
                                                    v.options.forEach { opt ->
                                                        add(
                                                            buildJsonObject {
                                                                put("value", opt)
                                                                put("text", opt)
                                                            }
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        )
                    }
                )
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
