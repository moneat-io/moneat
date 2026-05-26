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
import com.moneat.utils.suspendRunCatching
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

private const val DD_DEFAULT_WIDGET_W = 6
private const val DD_DEFAULT_WIDGET_H = 4
private const val DD_MAX_GRID_COL = 11
private const val DD_GRID_COLS = 12
private const val DD_SECTION_HEIGHT = 1
private const val DD_AUTO_WIDGETS_PER_ROW = 2
private const val DD_DEFAULT_TABLE_LIMIT = 100

private val DD_METRIC_QUERY_REGEX = Regex("""([A-Za-z_][A-Za-z0-9_]*):([A-Za-z0-9_.]+)\{([^}]*)}""")
private val DD_GROUP_BY_REGEX = Regex("""\s+by\s+\{([^}]*)}""")
private val DD_SIMPLE_FORMULA_REGEX = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")

private data class DataDogLayout(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

private data class ParsedDataDogQuery(
    val function: AggFunction,
    val metricName: String,
    val tagText: String?,
    val groupByText: String?
)

class DataDogTranslator : DashboardTranslator {

    private val widgetTypeMap = mapOf(
        "timeseries" to "timeseries",
        "toplist" to "toplist",
        "query_value" to "stat",
        "table" to "table",
        "query_table" to "table",
        "heatmap" to "heatmap",
        "distribution" to "bar",
        "pie" to "donut",
        "sunburst" to "donut",
        "note" to "text",
        "free_text" to "text",
        "image" to "text",
        "group" to "section"
    )

    private val reverseWidgetTypeMap = mapOf(
        "timeseries" to "timeseries",
        "toplist" to "toplist",
        "stat" to "query_value",
        "table" to "query_table",
        "heatmap" to "heatmap",
        "bar" to "distribution",
        "donut" to "pie",
        "text" to "note"
    )

    private val metricNamespaceMap = mapOf(
        "system." to "metrics",
        "trace." to "spans",
        "logs." to "logs",
        "container." to "containers",
        "docker." to "containers",
        "network." to "metrics"
    )

    private val filterFieldsBySource = mapOf(
        "metrics" to setOf("metric_name", "host"),
        "containers" to setOf("host", "name"),
        "spans" to setOf("op", "status", "environment"),
        "logs" to setOf("level", "message", "service", "environment", "host")
    )

    override fun import(json: JsonObject): DashboardImportResult {
        val warnings = mutableListOf<String>()

        val title = json["title"]?.jsonPrimitive?.contentOrNull ?: "Imported DataDog Dashboard"
        val description = json["description"]?.jsonPrimitive?.contentOrNull
        val ddWidgets = json["widgets"]?.jsonArray ?: JsonArray(emptyList())
        val widgets = importWidgets(ddWidgets, warnings)
            .mapIndexed { index, widget -> widget.copy(sortOrder = index) }

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

    private fun importWidgets(
        ddWidgets: JsonArray,
        warnings: MutableList<String>,
        parentLayout: DataDogLayout? = null
    ): List<WidgetResponse> {
        var autoColumn = 0
        var nextAutoY = 0
        return ddWidgets.flatMapIndexed { index, element ->
            val widgetJson = element.jsonObject
            val explicitLayout = readLayout(widgetJson["layout"]?.jsonObject)
            val layout = explicitLayout ?: autoLayout(autoColumn, nextAutoY).also {
                autoColumn = (autoColumn + 1) % DD_AUTO_WIDGETS_PER_ROW
                if (autoColumn == 0) nextAutoY += DD_DEFAULT_WIDGET_H
            }
            if (explicitLayout != null) {
                nextAutoY = maxOf(nextAutoY, explicitLayout.y + explicitLayout.height)
            }

            suspendRunCatching {
                importWidget(widgetJson, index, warnings, layout, parentLayout)
            }.getOrElse { e ->
                warnings.add("Widget $index: failed to import - ${e.message}")
                emptyList()
            }
        }
    }

    private fun importWidget(
        widgetJson: JsonObject,
        index: Int,
        warnings: MutableList<String>,
        layout: DataDogLayout,
        parentLayout: DataDogLayout? = null
    ): List<WidgetResponse> {
        val definition = widgetJson["definition"]?.jsonObject ?: JsonObject(emptyMap())
        val ddType = definition["type"]?.jsonPrimitive?.contentOrNull ?: "timeseries"
        val moneatType = widgetTypeMap[ddType]
        if (moneatType == null) {
            warnings.add("Widget $index: unsupported type '$ddType', imported as 'text'")
        }

        if (ddType == "group") {
            return importGroupWidget(definition, index, warnings, layout)
        }

        val resolvedLayout = resolveLayout(layout, parentLayout)
        val widgetTitle = definition["title"]?.jsonPrimitive?.contentOrNull
        val queryConfigs = parseDataDogQueries(definition, warnings, index)
        val displayConfig = extractDisplayConfig(definition, ddType)

        return listOf(
            WidgetResponse(
                id = 0,
                dashboardId = 0,
                title = widgetTitle,
                widgetType = moneatType ?: "text",
                gridX = resolvedLayout.x.coerceIn(0, DD_MAX_GRID_COL),
                gridY = resolvedLayout.y,
                gridW = resolvedLayout.width.coerceIn(1, DD_GRID_COLS),
                gridH = resolvedLayout.height.coerceIn(1, DD_GRID_COLS),
                queryConfigs = queryConfigs,
                displayConfig = displayConfig,
                sortOrder = index
            )
        )
    }

    private fun importGroupWidget(
        definition: JsonObject,
        index: Int,
        warnings: MutableList<String>,
        layout: DataDogLayout
    ): List<WidgetResponse> {
        val title = definition["title"]?.jsonPrimitive?.contentOrNull ?: "Section"
        val children = definition["widgets"]?.jsonArray ?: JsonArray(emptyList())
        val section = WidgetResponse(
            id = 0,
            dashboardId = 0,
            title = title,
            widgetType = "section",
            gridX = 0,
            gridY = layout.y,
            gridW = DD_GRID_COLS,
            gridH = DD_SECTION_HEIGHT,
            queryConfigs = emptyList(),
            displayConfig = mapOf(
                "source_format" to "datadog",
                "datadog_widget_type" to "group",
                "collapsed" to "false"
            ),
            sortOrder = index
        )
        val childParentLayout = layout.copy(y = layout.y + DD_SECTION_HEIGHT)
        return listOf(section) + importWidgets(children, warnings, childParentLayout)
    }

    private fun readLayout(layout: JsonObject?): DataDogLayout? {
        if (layout == null) return null
        return DataDogLayout(
            x = layout["x"]?.jsonPrimitive?.intOrNull ?: 0,
            y = layout["y"]?.jsonPrimitive?.intOrNull ?: 0,
            width = layout["width"]?.jsonPrimitive?.intOrNull ?: DD_DEFAULT_WIDGET_W,
            height = layout["height"]?.jsonPrimitive?.intOrNull ?: DD_DEFAULT_WIDGET_H
        )
    }

    private fun autoLayout(column: Int, y: Int): DataDogLayout {
        val x = column * DD_DEFAULT_WIDGET_W
        return DataDogLayout(x, y, DD_DEFAULT_WIDGET_W, DD_DEFAULT_WIDGET_H)
    }

    private fun resolveLayout(layout: DataDogLayout, parentLayout: DataDogLayout?): DataDogLayout {
        if (parentLayout == null) return layout
        return DataDogLayout(
            x = parentLayout.x + layout.x,
            y = parentLayout.y + layout.y,
            width = layout.width.coerceAtMost((DD_GRID_COLS - parentLayout.x).coerceAtLeast(1)),
            height = layout.height
        )
    }

    private fun extractDisplayConfig(definition: JsonObject, ddType: String): Map<String, String> {
        val config = mutableMapOf(
            "source_format" to "datadog",
            "datadog_widget_type" to ddType
        )
        definition["content"]?.jsonPrimitive?.contentOrNull?.let { content ->
            config["content"] = content
        }
        definition["url"]?.jsonPrimitive?.contentOrNull?.let { url ->
            config["content"] = "![]($url)"
            config["image_url"] = url
        }
        definition["legend_layout"]?.jsonPrimitive?.contentOrNull?.let { config["legendLayout"] = it }
        definition["display_type"]?.jsonPrimitive?.contentOrNull?.let { config["displayType"] = it }
        definition["background_color"]?.jsonPrimitive?.contentOrNull?.let { config["backgroundColor"] = it }
        return config
    }

    internal fun parseDataDogQuery(
        definition: JsonObject,
        warnings: MutableList<String>,
        widgetIndex: Int
    ): QueryDsl {
        return parseDataDogQueries(definition, warnings, widgetIndex).first()
    }

    internal fun parseDataDogQueries(
        definition: JsonObject,
        warnings: MutableList<String>,
        widgetIndex: Int
    ): List<QueryDsl> {
        val requests = definition["requests"]?.jsonArray

        if (requests.isNullOrEmpty()) {
            return listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count"))
                )
            )
        }

        val parsedQueries = requests.flatMap { requestElement ->
            parseRequestQueries(requestElement.jsonObject, warnings, widgetIndex)
        }

        if (parsedQueries.isEmpty()) {
            warnings.add("Widget $widgetIndex: no query string found, using default")
            return listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count"))
                )
            )
        }

        return parsedQueries
    }

    private fun parseRequestQueries(
        request: JsonObject,
        warnings: MutableList<String>,
        widgetIndex: Int
    ): List<QueryDsl> {
        request["q"]?.jsonPrimitive?.contentOrNull?.let { queryStr ->
            return listOf(parseDataDogQueryString(queryStr, warnings, widgetIndex))
        }

        val formulaAliases = extractFormulaAliases(request)
        val queries = request["queries"]?.jsonArray ?: return emptyList()
        return queries.mapNotNull { queryElement ->
            val queryObj = queryElement.jsonObject
            val queryStr = queryObj["query"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val queryName = queryObj["name"]?.jsonPrimitive?.contentOrNull
            val alias = queryName?.let { formulaAliases[it] } ?: queryName
            parseDataDogQueryString(queryStr, warnings, widgetIndex, alias, queryName)
        }
    }

    private fun extractFormulaAliases(request: JsonObject): Map<String, String> {
        val formulas = request["formulas"]?.jsonArray ?: return emptyMap()
        return formulas.mapNotNull { formulaElement ->
            val formula = formulaElement.jsonObject
            val expression = formula["formula"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!DD_SIMPLE_FORMULA_REGEX.matches(expression)) return@mapNotNull null
            val alias = formula["alias"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            expression to alias
        }.toMap()
    }

    internal fun parseDataDogQueryString(
        queryStr: String,
        warnings: MutableList<String>,
        widgetIndex: Int,
        alias: String? = null,
        refId: String? = null
    ): QueryDsl {
        val parsed = parseMetricQuery(queryStr)
        if (parsed == null) {
            warnings.add("Widget $widgetIndex: unable to parse Datadog query, preserving as raw query")
            return rawQueryDsl(queryStr, alias, refId)
        }

        val dataSource = resolveDataSource(parsed.metricName)
        val field = mapMetricField(dataSource)
        val filters = parseDataDogTags(parsed.tagText, dataSource, parsed.metricName)
        val groupBy = parseDataDogGroupBy(parsed.groupByText, dataSource)

        return QueryDsl(
            dataSource = dataSource,
            metrics = listOf(MetricDef(parsed.function, field, alias ?: "value")),
            groupBy = groupBy,
            filters = filters,
            limit = DD_DEFAULT_TABLE_LIMIT,
            refId = refId
        )
    }

    private fun parseMetricQuery(queryStr: String): ParsedDataDogQuery? {
        val match = DD_METRIC_QUERY_REGEX.find(queryStr) ?: return null
        val groupByText = DD_GROUP_BY_REGEX.find(queryStr)?.groupValues?.get(1)
        return ParsedDataDogQuery(
            function = mapDdFunction(match.groupValues[1]),
            metricName = match.groupValues[2].trim(),
            tagText = match.groupValues[3],
            groupByText = groupByText
        )
    }

    private fun rawQueryDsl(queryStr: String, alias: String?, refId: String?): QueryDsl {
        return QueryDsl(
            dataSource = "metrics",
            metrics = listOf(MetricDef(AggFunction.AVG, "value", alias ?: "value")),
            groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "auto")),
            rawQuery = queryStr,
            refId = refId
        )
    }

    private fun parseDataDogTags(
        tagText: String?,
        dataSource: String,
        metricName: String
    ): List<FilterDef> {
        val filters = mutableListOf<FilterDef>()
        if (dataSource == "metrics") {
            filters.add(FilterDef("metric_name", FilterOp.EQ, metricName))
        }
        if (tagText.isNullOrBlank() || tagText == "*") return filters

        tagText.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "*" }
            .forEach { tag -> addDataDogTagFilter(tag, dataSource, filters) }
        return filters
    }

    private fun addDataDogTagFilter(tag: String, dataSource: String, filters: MutableList<FilterDef>) {
        val filter = parseTagFilter(tag) ?: return
        if (isSupportedFilterField(dataSource, filter.field)) {
            filters.add(filter)
        }
    }

    private fun parseTagFilter(tag: String): FilterDef? {
        if (tag.startsWith("$")) {
            val name = tag.removePrefix("$")
            return FilterDef(name, FilterOp.EQ, tag)
        }

        val normalized = tag.removePrefix("!")
        val parts = normalized.split(":", limit = 2)
        if (parts.size != 2 || parts[1] == "*") return null

        return FilterDef(
            field = parts[0].trim(),
            op = if (tag.startsWith("!")) FilterOp.NEQ else FilterOp.EQ,
            value = parts[1].trim()
        )
    }

    private fun parseDataDogGroupBy(groupByText: String?, dataSource: String): List<GroupByDef> {
        val groupFields = groupByText?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && isSupportedFilterField(dataSource, it) }
            ?: emptyList()
        return listOf(GroupByDef("timestamp", GroupByType.TIME, "auto")) +
            groupFields.map { GroupByDef(it, GroupByType.FIELD) }
    }

    private fun isSupportedFilterField(dataSource: String, field: String): Boolean {
        return filterFieldsBySource[dataSource]?.contains(field) ?: false
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
        if (metricName == "events") return "events"
        for ((prefix, source) in metricNamespaceMap) {
            if (metricName.startsWith(prefix)) return source
        }
        return "metrics"
    }

    private fun mapMetricField(dataSource: String): String? {
        return when (dataSource) {
            "metrics" -> "value"
            "spans" -> "duration_ms"
            "logs" -> null
            "containers" -> "cpu_percent"
            else -> "value"
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
                        widget.displayConfig["content"]?.let { put("content", it) }
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
        dsl.rawQuery?.let { return it }
        val metric = dsl.metrics.firstOrNull() ?: return "count:events{*}"
        val fn = metric.function.value
        val metricName = dsl.filters.firstOrNull { it.field == "metric_name" }?.value
            ?: metric.field
            ?: dsl.dataSource
        val filterStr = dsl.filters
            .filter { it.field != "metric_name" }
            .joinToString(",") { f ->
                "${f.field}:${f.value ?: f.values?.firstOrNull() ?: "*"}"
            }.ifEmpty { "*" }
        return "$fn:$metricName{$filterStr}"
    }
}
