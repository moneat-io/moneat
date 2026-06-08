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
private const val DD_GROUP_BY_MATCH_GROUP_INDEX = 4

private const val DD_IMPORT_STRATEGY_NATIVE = "native"
private const val DD_IMPORT_STRATEGY_UNSUPPORTED = "unsupported"
private const val SOURCE_DEFINITION_JSON_KEY = "source_definition_json"
private const val SOURCE_LAYOUT_JSON_KEY = "source_layout_json"

private val DD_METRIC_QUERY_REGEX = Regex(
    """^\s*([A-Za-z_][A-Za-z0-9_]*):([A-Za-z0-9_.]+)\{([^}]*)}\s*(?:by\s+\{([^}]*)})?\s*$"""
)
private val DD_SIMPLE_FORMULA_REGEX = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")
private val DD_IN_TAG_REGEX = Regex("""^([A-Za-z0-9_.-]+)\s+IN\s+\(([^)]*)\)$""", RegexOption.IGNORE_CASE)
private val DD_NOT_IN_TAG_REGEX = Regex("""^([A-Za-z0-9_.-]+)\s+NOT\s+IN\s+\(([^)]*)\)$""", RegexOption.IGNORE_CASE)

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

private data class DataDogWidgetImportSupport(
    val moneatType: String,
    val strategy: String = DD_IMPORT_STRATEGY_NATIVE
)

private fun native(moneatType: String): DataDogWidgetImportSupport {
    return DataDogWidgetImportSupport(moneatType)
}

private data class NamedDataDogQuery(
    val name: String?,
    val dsl: QueryDsl
)

class DataDogTranslator : DashboardTranslator {

    private val widgetImportSupportByType = mapOf(
        "timeseries" to native("timeseries"),
        "toplist" to native("toplist"),
        "query_value" to native("stat"),
        "table" to native("table"),
        "query_table" to native("table"),
        "heatmap" to native("heatmap"),
        "distribution" to native("bar"),
        "pie" to native("donut"),
        "sunburst" to native("donut"),
        "note" to native("text"),
        "free_text" to native("text"),
        "image" to native("text"),
        "group" to native("section"),
        "bar_chart" to native("bar"),
        "list_stream" to native("stream"),
        "log_stream" to native("stream"),
        "event_stream" to native("stream"),
        "event_timeline" to native("timeline"),
        "geomap" to native("geo_map"),
        "hostmap" to native("host_map"),
        "topology_map" to native("topology_map"),
        "sankey" to native("sankey"),
        "treemap" to native("treemap"),
        "scatterplot" to native("scatter"),
        "check_status" to native("status"),
        "manage_status" to native("status"),
        "change" to native("change"),
        "wildcard" to native("custom"),
        "flame_graph" to native("flame_graph"),
        "cloud_cost_summary" to native("cost_summary"),
        "iframe" to native("iframe")
    )

    private val reverseWidgetTypeMap = mapOf(
        "timeseries" to "timeseries",
        "toplist" to "toplist",
        "stat" to "query_value",
        "table" to "query_table",
        "heatmap" to "heatmap",
        "bar" to "distribution",
        "donut" to "pie",
        "text" to "note",
        "stream" to "list_stream",
        "timeline" to "event_timeline",
        "geo_map" to "geomap",
        "host_map" to "hostmap",
        "topology_map" to "topology_map",
        "sankey" to "sankey",
        "treemap" to "treemap",
        "scatter" to "scatterplot",
        "status" to "check_status",
        "change" to "change",
        "custom" to "wildcard",
        "flame_graph" to "flame_graph",
        "cost_summary" to "cloud_cost_summary",
        "iframe" to "iframe"
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
        val support = widgetImportSupportByType[ddType]
        if (support == null) {
            warnings.add(
                "Widget $index: unsupported Datadog widget type '$ddType', imported as 'text'; " +
                    "original definition was preserved in displayConfig.$SOURCE_DEFINITION_JSON_KEY"
            )
        }

        if (ddType == "group") {
            return importGroupWidget(definition, index, warnings, layout, parentLayout)
        }

        val resolvedLayout = resolveLayout(layout, parentLayout)
        val widgetTitle = definition["title"]?.jsonPrimitive?.contentOrNull
        val queryConfigs = parseDataDogQueries(definition, warnings, index)
        val displayConfig = extractDisplayConfig(definition, layout, ddType, support)

        return listOf(
            WidgetResponse(
                id = 0,
                dashboardId = 0,
                title = widgetTitle,
                widgetType = support?.moneatType ?: "text",
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
        layout: DataDogLayout,
        parentLayout: DataDogLayout? = null
    ): List<WidgetResponse> {
        val resolvedLayout = resolveLayout(layout, parentLayout)
        val title = definition["title"]?.jsonPrimitive?.contentOrNull ?: "Section"
        val children = definition["widgets"]?.jsonArray ?: JsonArray(emptyList())
        val section = WidgetResponse(
            id = 0,
            dashboardId = 0,
            title = title,
            widgetType = "section",
            gridX = resolvedLayout.x.coerceIn(0, DD_MAX_GRID_COL),
            gridY = resolvedLayout.y,
            gridW = resolvedLayout.width.coerceIn(1, DD_GRID_COLS),
            gridH = DD_SECTION_HEIGHT,
            queryConfigs = emptyList(),
            displayConfig = mapOf(
                "source_format" to "datadog",
                "source_widget_type" to "group",
                "source_import_strategy" to DD_IMPORT_STRATEGY_NATIVE,
                "collapsed" to "false"
            ),
            sortOrder = index
        )
        val childParentLayout = resolvedLayout.copy(y = resolvedLayout.y + DD_SECTION_HEIGHT)
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

    private fun extractDisplayConfig(
        definition: JsonObject,
        layout: DataDogLayout,
        ddType: String,
        support: DataDogWidgetImportSupport?
    ): Map<String, String> {
        val config = mutableMapOf(
            "source_format" to "datadog",
            "source_widget_type" to ddType,
            "source_import_strategy" to (support?.strategy ?: DD_IMPORT_STRATEGY_UNSUPPORTED),
            SOURCE_DEFINITION_JSON_KEY to definition.toString(),
            SOURCE_LAYOUT_JSON_KEY to layout.toJsonObject().toString()
        )
        config["source_request_count"] = (definition["requests"]?.jsonArray?.size ?: 0).toString()
        config["source_query_count"] = countDataDogQueries(definition).toString()
        if (definition["requests"]?.jsonArray?.any { it.jsonObject["formulas"] is JsonArray } == true) {
            config["source_has_formulas"] = "true"
        }
        definition["content"]?.jsonPrimitive?.contentOrNull?.let { content ->
            config["content"] = content
        }
        definition["url"]?.jsonPrimitive?.contentOrNull?.let { url ->
            config["content"] = if (ddType == "iframe") "[$url]($url)" else "![]($url)"
            config["image_url"] = url
            if (ddType == "iframe") {
                config["iframe_url"] = url
            }
        }
        definition["legend_layout"]?.jsonPrimitive?.contentOrNull?.let { config["legendLayout"] = it }
        definition["display_type"]?.jsonPrimitive?.contentOrNull?.let { config["displayType"] = it }
        definition["background_color"]?.jsonPrimitive?.contentOrNull?.let { config["backgroundColor"] = it }
        return config
    }

    private fun DataDogLayout.toJsonObject(): JsonObject = buildJsonObject {
        put("x", x)
        put("y", y)
        put("width", width)
        put("height", height)
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
        val ddType = definition["type"]?.jsonPrimitive?.contentOrNull ?: "timeseries"
        val requests = definition["requests"]?.jsonArray

        if (requests.isNullOrEmpty()) {
            return listOf(defaultQueryForWidget(definition, ddType, warnings, widgetIndex))
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

        val queries = request["queries"]?.jsonArray ?: return emptyList()
        val namedQueries = queries.mapNotNull { queryElement ->
            val queryObj = queryElement.jsonObject
            val queryStr = extractDataDogQueryString(queryObj) ?: return@mapNotNull null
            val queryName = queryObj["name"]?.jsonPrimitive?.contentOrNull
            val dataSource = queryObj["data_source"]?.jsonPrimitive?.contentOrNull
            val parsed = parseDataDogQueryString(queryStr, warnings, widgetIndex, queryName, queryName)
            NamedDataDogQuery(queryName, applyDataDogQuerySource(parsed, dataSource))
        }

        val formulaQueries = parseFormulaQueries(request, namedQueries, warnings, widgetIndex)
        val hasPreservedFormula = formulaQueries.any { query ->
            query.rawQuery != null && namedQueries.none { it.dsl.rawQuery == query.rawQuery }
        }
        if (hasPreservedFormula) {
            return namedQueries.map { it.dsl } + formulaQueries
        }
        return formulaQueries.ifEmpty { namedQueries.map { it.dsl } }
    }

    private fun parseFormulaQueries(
        request: JsonObject,
        namedQueries: List<NamedDataDogQuery>,
        warnings: MutableList<String>,
        widgetIndex: Int
    ): List<QueryDsl> {
        val formulas = request["formulas"]?.jsonArray ?: return emptyList()
        return formulas.mapNotNull { formulaElement ->
            val formula = formulaElement.jsonObject
            val expression = formula["formula"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val alias = formula["alias"]?.jsonPrimitive?.contentOrNull
            if (DD_SIMPLE_FORMULA_REGEX.matches(expression)) {
                val namedQuery = namedQueries.firstOrNull { it.name == expression }
                if (namedQuery != null) {
                    return@mapNotNull namedQuery.dsl.withMetricAlias(alias)
                }
            }
            warnings.add(
                "Widget $widgetIndex: Datadog formula '$expression' cannot be translated directly; " +
                    "preserved as raw query for manual review"
            )
            rawFormulaQueryDsl(expression, alias)
        }
    }

    private fun QueryDsl.withMetricAlias(alias: String?): QueryDsl {
        if (alias.isNullOrBlank() || metrics.isEmpty()) return this
        return copy(
            metrics = metrics.mapIndexed { index, metric ->
                if (index == 0) metric.copy(alias = alias) else metric
            }
        )
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
        val match = DD_METRIC_QUERY_REGEX.matchEntire(queryStr) ?: return null
        val groupByText = match.groupValues.getOrNull(DD_GROUP_BY_MATCH_GROUP_INDEX)?.takeIf { it.isNotBlank() }
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

    private fun rawFormulaQueryDsl(expression: String, alias: String?): QueryDsl {
        return QueryDsl(
            dataSource = "metrics",
            metrics = listOf(MetricDef(AggFunction.AVG, "value", alias ?: "formula")),
            groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "auto")),
            rawQuery = expression
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

        splitDataDogTags(tagText)
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "*" }
            .forEach { tag -> addDataDogTagFilter(tag, dataSource, filters) }
        return filters
    }

    private fun splitDataDogTags(tagText: String): List<String> {
        val tags = mutableListOf<String>()
        val current = StringBuilder()
        var parenthesisDepth = 0
        for (char in tagText) {
            when (char) {
                '(' -> {
                    parenthesisDepth++
                    current.append(char)
                }
                ')' -> {
                    parenthesisDepth = (parenthesisDepth - 1).coerceAtLeast(0)
                    current.append(char)
                }
                ',' -> {
                    if (parenthesisDepth == 0) {
                        tags.add(current.toString())
                        current.clear()
                    } else {
                        current.append(char)
                    }
                }
                else -> current.append(char)
            }
        }
        tags.add(current.toString())
        return tags
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

        DD_NOT_IN_TAG_REGEX.matchEntire(tag)?.let { match ->
            return FilterDef(match.groupValues[1], FilterOp.NOT_IN, values = parseTagValues(match.groupValues[2]))
        }
        DD_IN_TAG_REGEX.matchEntire(tag)?.let { match ->
            return FilterDef(match.groupValues[1], FilterOp.IN, values = parseTagValues(match.groupValues[2]))
        }

        val normalized = tag.removePrefix("!").removePrefix("-")
        val parts = normalized.split(":", limit = 2)
        if (parts.size != 2 || parts[1] == "*") return null

        return FilterDef(
            field = parts[0].trim(),
            op = if (tag.startsWith("!") || tag.startsWith("-")) FilterOp.NEQ else FilterOp.EQ,
            value = parts[1].trim()
        )
    }

    private fun parseTagValues(valueText: String): List<String> {
        return valueText.split(",")
            .map { it.trim().trim('\'', '"') }
            .filter { it.isNotBlank() }
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

    private fun defaultQueryForWidget(
        definition: JsonObject,
        ddType: String,
        warnings: MutableList<String>,
        widgetIndex: Int
    ): QueryDsl {
        val dataSource = defaultDataSourceForWidget(ddType)
        val query = extractDataDogQueryString(definition)
        if (!query.isNullOrBlank()) {
            warnings.add(
                "Widget $widgetIndex: Datadog widget type '$ddType' query preserved as raw query; " +
                    "manual review is required before it can execute"
            )
            return preservedRawQueryDsl(query, dataSource)
        }
        return QueryDsl(
            dataSource = dataSource,
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count"))
        )
    }

    private fun defaultDataSourceForWidget(ddType: String): String = when (ddType) {
        "log_stream", "list_stream" -> "logs"
        "event_stream", "event_timeline" -> "events"
        "flame_graph", "topology_map", "sankey" -> "spans"
        "hostmap", "check_status", "manage_status", "cloud_cost_summary" -> "metrics"
        else -> "events"
    }

    private fun extractDataDogQueryString(json: JsonObject): String? {
        json["query"]?.jsonPrimitive?.contentOrNull?.let { return it }
        json["q"]?.jsonPrimitive?.contentOrNull?.let { return it }
        return json["search"]?.jsonObject?.get("query")?.jsonPrimitive?.contentOrNull
    }

    private fun applyDataDogQuerySource(dsl: QueryDsl, dataDogSource: String?): QueryDsl {
        if (dsl.rawQuery == null || dataDogSource.isNullOrBlank()) return dsl
        val dataSource = mapDataDogQuerySource(dataDogSource)
        return dsl.copy(
            dataSource = dataSource,
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count"))
        )
    }

    private fun mapDataDogQuerySource(dataDogSource: String): String = when (dataDogSource.lowercase()) {
        "logs", "log" -> "logs"
        "events", "event" -> "events"
        "spans", "trace", "traces", "apm", "apm_resource_stats", "profiles" -> "spans"
        "containers", "container" -> "containers"
        else -> "metrics"
    }

    private fun preservedRawQueryDsl(query: String, dataSource: String): QueryDsl {
        return QueryDsl(
            dataSource = dataSource,
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
            rawQuery = query
        )
    }

    private fun countDataDogQueries(definition: JsonObject): Int {
        val requests = definition["requests"]?.jsonArray ?: return 0
        return requests.sumOf { requestElement ->
            val request = requestElement.jsonObject
            val directQueryCount = if (request["q"]?.jsonPrimitive?.contentOrNull != null) 1 else 0
            directQueryCount + (request["queries"]?.jsonArray?.size ?: 0)
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
                        put("type", exportDataDogWidgetType(widget))
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

    private fun exportDataDogWidgetType(widget: WidgetResponse): String {
        val sourceType = widget.displayConfig["source_widget_type"]
        if (widget.displayConfig["source_format"] == "datadog" &&
            sourceType != null &&
            widgetImportSupportByType.containsKey(sourceType)
        ) {
            return sourceType
        }
        return reverseWidgetTypeMap[widget.widgetType] ?: "timeseries"
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
