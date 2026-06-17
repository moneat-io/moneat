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
import com.moneat.utils.suspendRunCatching
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
private const val MONEAT_ROW_PX = 30.0
private const val GRAFANA_DEFAULT_PANEL_W = 12
private const val GRAFANA_DEFAULT_PANEL_H = 4
private const val MONEAT_MAX_COL_IDX = MONEAT_COLS - 1
private const val MIN_WIDGET_HEIGHT = 3
private const val FILL_OPACITY_SCALE = 100.0
private const val REGEX_THIRD_GROUP_IDX = 3
private const val GRAFANA_SCHEMA_VERSION = 39
private const val IMPORT_PLACEHOLDER_ID = "00000000-0000-0000-0000-000000000000"

/** Imports and exports dashboards between Grafana JSON and Moneat dashboard models. */
class GrafanaTranslator : DashboardTranslator {

    private val widgetTypeMap = mapOf(
        "timeseries" to "timeseries",
        "barchart" to "bar",
        "grafana-piechart-panel" to "donut",
        "piechart" to "donut",
        "stat" to "stat",
        "singlestat" to "stat",
        "table" to "table",
        "table-old" to "table",
        "heatmap" to "heatmap",
        "text" to "text",
        "gauge" to "gauge",
        "bargauge" to "bargauge",
        "graph" to "timeseries",
        "logs" to "table",
        "status-history" to "status",
        "alertlist" to "text"
    )

    private val reverseWidgetTypeMap = mapOf(
        "timeseries" to "timeseries",
        "bar" to "barchart",
        "bargauge" to "bargauge",
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

        // Build a map from __inputs: "${DS_X}" -> pluginId (e.g. "redis-datasource")
        val inputsMap = parseInputsMap(json)

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
            suspendRunCatching {
                importPanel(panel, index, warnings, inputsMap)
            }.getOrElse { e ->
                warnings.add("Panel $index: failed to import - ${e.message}")
                null
            }
        }

        val dashboard = DashboardResponse(
            id = IMPORT_PLACEHOLDER_ID,
            orgId = IMPORT_PLACEHOLDER_ID,
            title = title,
            description = description,
            layoutType = "grid",
            createdBy = IMPORT_PLACEHOLDER_ID,
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
        warnings: MutableList<String>,
        inputsMap: Map<String, String> = emptyMap()
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
                id = IMPORT_PLACEHOLDER_ID,
                dashboardId = IMPORT_PLACEHOLDER_ID,
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
        val grafanaW = gridPos?.get("w")?.jsonPrimitive?.intOrNull ?: GRAFANA_DEFAULT_PANEL_W
        val grafanaH = gridPos?.get("h")?.jsonPrimitive?.intOrNull ?: GRAFANA_DEFAULT_PANEL_H

        // Grafana uses a 24-col grid, Moneat uses 12-col.
        // Use floor-aligned scaling: x = floor(gx*12/24), w = floor((gx+gw)*12/24) - x
        // This guarantees adjacent panels share exact column boundaries with no gaps or overflow.
        val gridX = (grafanaX * MONEAT_COLS / GRAFANA_COLS).coerceIn(0, MONEAT_MAX_COL_IDX)
        val gridY = scaleGridValue(grafanaY)
        val gridXEnd = ((grafanaX + grafanaW) * MONEAT_COLS / GRAFANA_COLS).coerceAtMost(MONEAT_COLS)
        val gridW = (gridXEnd - gridX).coerceAtLeast(1)
        val gridH = scaleGridValue(grafanaH)

        val queryConfigs = parseGrafanaTargets(panelJson, warnings, index, inputsMap)
        val displayConfig = extractDisplayConfig(panelJson)

        val minH = if (moneatType == "stat" || moneatType == "gauge") 1 else MIN_WIDGET_HEIGHT
        return WidgetResponse(
            id = IMPORT_PLACEHOLDER_ID,
            dashboardId = IMPORT_PLACEHOLDER_ID,
            title = panelTitle,
            widgetType = moneatType ?: "text",
            gridX = gridX,
            gridY = gridY,
            gridW = gridW,
            gridH = gridH.coerceIn(minH, MONEAT_COLS),
            queryConfigs = queryConfigs,
            displayConfig = displayConfig,
            sortOrder = index
        )
    }

    private fun scaleGridValue(grafanaUnits: Int): Int =
        (grafanaUnits * GRAFANA_ROW_PX / MONEAT_ROW_PX).roundToInt()

    /** Builds Moneat display config key/value strings from Grafana panel fieldConfig, mappings, and options. */
    private fun extractDisplayConfig(panelJson: JsonObject): Map<String, String> {
        val config = mutableMapOf<String, String>()
        populateDisplayConfigFromPanel(panelJson, config)
        extractGrafanaTransformations(panelJson, config)
        return config
    }

    private fun populateDisplayConfigFromPanel(panelJson: JsonObject, config: MutableMap<String, String>) {
        val fieldDefaults = panelJson["fieldConfig"]?.jsonObject?.get("defaults")?.jsonObject
        val defaults = fieldDefaults?.get("custom")?.jsonObject
        val options = panelJson["options"]?.jsonObject

        putFieldDefaultUnitAndDecimals(fieldDefaults, config)
        putThresholdsFromFieldDefaults(fieldDefaults, config)
        putValueMappingsFromFieldDefaults(fieldDefaults, config)
        putCustomDisplayDefaults(defaults, config)
        putLegendFromOptions(options, config)
        putGaugeRangeAndBarGaugeOptions(fieldDefaults, options, config)
    }

    private fun putFieldDefaultUnitAndDecimals(
        fieldDefaults: JsonObject?,
        config: MutableMap<String, String>
    ) {
        val unit = fieldDefaults?.get("unit")?.jsonPrimitive?.contentOrNull
        if (unit != null) config["unit"] = unit
        val decimals = fieldDefaults?.get("decimals")?.jsonPrimitive?.intOrNull
        if (decimals != null) config["decimals"] = decimals.toString()
    }

    private fun putThresholdsFromFieldDefaults(
        fieldDefaults: JsonObject?,
        config: MutableMap<String, String>
    ) {
        val steps = fieldDefaults?.get("thresholds")?.jsonObject?.get("steps")?.jsonArray
        if (steps.isNullOrEmpty()) return
        applyParsedThresholdSteps(steps, config)
    }

    private fun applyParsedThresholdSteps(steps: JsonArray, config: MutableMap<String, String>) {
        val moneatThresholds = steps.mapNotNull { thresholdStepToJsonObject(it) }
        if (moneatThresholds.isEmpty()) return
        config["thresholds"] = JsonArray(moneatThresholds).toString()
    }

    private fun thresholdStepToJsonObject(step: JsonElement): JsonObject? {
        val stepObj = step.jsonObject
        val valuePrim = stepObj["value"]?.jsonPrimitive
        val intVal = valuePrim?.intOrNull
        val value = when {
            intVal != null -> intVal
            valuePrim?.contentOrNull == null -> 0
            else -> return null
        }
        val color = stepObj["color"]?.jsonPrimitive?.contentOrNull ?: return null
        return buildJsonObject {
            put("value", value)
            put("color", color)
        }
    }

    private fun grafanaValueMappingToJson(mapObj: JsonObject): JsonObject? {
        val type = mapObj["type"]?.jsonPrimitive?.contentOrNull
        return when (type) {
            "special" -> grafanaSpecialValueMapping(mapObj)
            "value" -> grafanaDiscreteValueMapping(mapObj)
            else -> null
        }
    }

    private fun grafanaSpecialValueMapping(mapObj: JsonObject): JsonObject? {
        val opts = mapObj["options"]?.jsonObject ?: return null
        val match = opts["match"]?.jsonPrimitive?.contentOrNull ?: return null
        val result = opts["result"]?.jsonObject ?: return null
        val text = result["text"]?.jsonPrimitive?.contentOrNull ?: return null
        val color = result["color"]?.jsonPrimitive?.contentOrNull
        return buildJsonObject {
            put("value", match)
            put("text", text)
            if (color != null) put("color", color)
        }
    }

    private fun grafanaDiscreteValueMapping(mapObj: JsonObject): JsonObject? {
        val opts = mapObj["options"]?.jsonObject ?: return null
        val firstEntry = opts.entries.firstOrNull() ?: return null
        val key = firstEntry.key
        val result = firstEntry.value.jsonObject["result"]?.jsonObject ?: return null
        val text = result["text"]?.jsonPrimitive?.contentOrNull ?: return null
        val color = result["color"]?.jsonPrimitive?.contentOrNull
        return buildJsonObject {
            put("value", key)
            put("text", text)
            if (color != null) put("color", color)
        }
    }

    private fun putValueMappingsFromFieldDefaults(
        fieldDefaults: JsonObject?,
        config: MutableMap<String, String>
    ) {
        val mappings = fieldDefaults?.get("mappings")?.jsonArray ?: return
        applyParsedValueMappings(mappings, config)
    }

    private fun applyParsedValueMappings(mappings: JsonArray, config: MutableMap<String, String>) {
        val moneatMappings = mappings.mapNotNull { grafanaValueMappingToJson(it.jsonObject) }
        if (moneatMappings.isEmpty()) return
        config["valueMappings"] = JsonArray(moneatMappings).toString()
    }

    private fun putCustomDisplayDefaults(defaults: JsonObject?, config: MutableMap<String, String>) {
        val drawStyle = defaults?.get("drawStyle")?.jsonPrimitive?.contentOrNull
        if (drawStyle != null) config["drawStyle"] = drawStyle

        val lineWidth = defaults?.get("lineWidth")?.jsonPrimitive?.intOrNull
        if (lineWidth != null) config["lineWidth"] = lineWidth.toString()

        val fillOpacity = defaults?.get("fillOpacity")?.jsonPrimitive?.intOrNull
        if (fillOpacity != null) config["fillOpacity"] = (fillOpacity / FILL_OPACITY_SCALE).toString()

        val stackMode = defaults?.get("stacking")?.jsonObject?.get("mode")?.jsonPrimitive?.contentOrNull
        if (stackMode != null) config["stackMode"] = stackMode

        val scaleType = defaults?.get("scaleDistribution")?.jsonObject?.get("type")
            ?.jsonPrimitive?.contentOrNull
        if (scaleType != null) config["scaleType"] = scaleType
    }

    private fun putLegendFromOptions(options: JsonObject?, config: MutableMap<String, String>) {
        val legend = options?.get("legend")?.jsonObject ?: return
        val placement = legend["placement"]?.jsonPrimitive?.contentOrNull
        if (placement != null) config["legendPlacement"] = placement
        val mode = legend["displayMode"]?.jsonPrimitive?.contentOrNull ?: return
        config["legendMode"] = when (mode) {
            "hidden" -> "hidden"
            "table" -> "table"
            else -> "list"
        }
    }

    private fun putGaugeRangeAndBarGaugeOptions(
        fieldDefaults: JsonObject?,
        options: JsonObject?,
        config: MutableMap<String, String>
    ) {
        val min = fieldDefaults?.get("min")?.jsonPrimitive?.intOrNull
        if (min != null) config["gaugeMin"] = min.toString()
        val max = fieldDefaults?.get("max")?.jsonPrimitive?.intOrNull
        if (max != null) config["gaugeMax"] = max.toString()

        val orientation = options?.get("orientation")?.jsonPrimitive?.contentOrNull
        if (orientation != null) config["orientation"] = orientation
        val displayMode = options?.get("displayMode")?.jsonPrimitive?.contentOrNull
        if (displayMode != null) config["displayMode"] = displayMode
    }

    /**
     * Parse Grafana panel `transformations` array and extract:
     * - `filterFieldsByName` → `visibleFields` (comma-separated field names)
     * - `organize.renameByName` → `fieldRenames` (JSON object: old→new)
     */
    internal fun extractGrafanaTransformations(
        panelJson: JsonObject,
        config: MutableMap<String, String>
    ) {
        val transforms = panelJson["transformations"]?.jsonArray ?: return

        for (element in transforms) {
            val transform = element.jsonObject
            val id = transform["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val opts = transform["options"]?.jsonObject ?: continue

            when (id) {
                "filterFieldsByName" -> applyFilterFieldsByNameTransform(opts, config)
                "organize" -> applyOrganizeTransform(opts, config)
            }
        }
    }

    private fun applyFilterFieldsByNameTransform(opts: JsonObject, config: MutableMap<String, String>) {
        val names = opts["include"]?.jsonObject?.get("names")?.jsonArray
        if (names == null || names.isEmpty()) return
        config["visibleFields"] = names.joinToString(",") { it.jsonPrimitive.content }
    }

    private fun applyOrganizeTransform(opts: JsonObject, config: MutableMap<String, String>) {
        val renameByName = opts["renameByName"]?.jsonObject ?: return
        if (renameByName.isEmpty()) return

        val renames = renameByName.entries
            .filter { it.value.jsonPrimitive.contentOrNull?.isNotEmpty() == true }
            .associate { it.key to it.value.jsonPrimitive.content }
        if (renames.isEmpty()) return

        config["fieldRenames"] = buildJsonObject {
            renames.forEach { (k, v) -> put(k, v) }
        }.toString()
    }

    internal fun parseGrafanaTargets(
        panelJson: JsonObject,
        warnings: MutableList<String>,
        panelIndex: Int,
        inputsMap: Map<String, String> = emptyMap()
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
        val preMappedDataSource = resolveDatasource(panelDs, inputsMap)

        return targets.mapIndexed { idx, targetEl ->
            val target = targetEl.jsonObject
            val refId = target["refId"]?.jsonPrimitive?.contentOrNull ?: ('A' + idx).toString()
            val legendFormat = target["legendFormat"]?.jsonPrimitive?.contentOrNull

            // Per-target datasource overrides panel-level
            val targetDs = target["datasource"]
            val effectiveDs = resolveDatasource(targetDs, inputsMap)
                ?: preMappedDataSource

            val parsed = parseTarget(target, warnings, panelIndex, legendFormat)
            val withDs = if (effectiveDs != null) parsed.copy(dataSource = effectiveDs) else parsed
            withDs.copy(refId = refId)
        }
    }

    internal fun resolveDatasource(
        ds: JsonElement?,
        inputsMap: Map<String, String> = emptyMap()
    ): String? = when (ds) {
        is JsonPrimitive if ds.isString -> {
            grafanaDatasourceToMoneatMarker(resolveDatasourceRef(ds.content, inputsMap))
        }
        is JsonObject -> {
            val type = ds["type"]?.jsonPrimitive?.contentOrNull
            if (type?.startsWith("custom:") == true) {
                type
            } else {
                type?.let(::grafanaDatasourceToMoneatMarker)
            }
        }
        else -> null
    }

    private fun resolveDatasourceRef(ref: String, inputsMap: Map<String, String>): String {
        val trimmed = ref.trim()
        // Resolve ${DS_...} template variable references via __inputs
        val varMatch = Regex("""\$\{(\w+)\}""").matchEntire(trimmed)
        return if (varMatch != null) {
            inputsMap[varMatch.groupValues[1]] ?: trimmed
        } else {
            trimmed
        }
    }

    private fun grafanaDatasourceToMoneatMarker(value: String): String? {
        if (value.startsWith("custom:")) return value
        if (value.startsWith("$")) return null
        val normalized = value.lowercase()
            .removePrefix("grafana-")
            .removeSuffix("-datasource")
            .replace("_", "-")
        return when (normalized) {
            "datasource", "grafana" -> null
            "prometheus" -> "__prometheus"
            "cloudwatch" -> "__cloudwatch"
            "elasticsearch" -> "__elasticsearch"
            "graphite" -> "__graphite"
            "influxdb", "influx" -> "__influxdb"
            "loki" -> "__loki"
            "postgres", "postgresql" -> "__postgresql"
            "redis" -> "__redis"
            else -> value
        }
    }

    private fun parseTarget(
        target: JsonObject,
        warnings: MutableList<String>,
        panelIndex: Int,
        legendFormat: String?
    ): QueryDsl {
        tryParsePromqlGrafanaTarget(target, warnings, panelIndex, legendFormat)?.let { return it }
        tryParseSqlGrafanaTarget(target, warnings, panelIndex)?.let { return it }
        tryParseElasticsearchGrafanaTarget(target, legendFormat)?.let { return it }
        tryParseGenericQueryGrafanaTarget(target, warnings, panelIndex, legendFormat)?.let { return it }
        tryParseRedisGrafanaTarget(target, legendFormat)?.let { return it }
        tryParseInfluxGrafanaTarget(target, legendFormat)?.let { return it }
        tryParseCloudWatchGrafanaTarget(target, legendFormat)?.let { return it }
        tryParseGraphiteGrafanaTarget(target, legendFormat)?.let { return it }
        return parseGrafanaTargetFallback(target, warnings, panelIndex, legendFormat)
    }

    /** PromQL takes priority — rawSql may be a Grafana default template. */
    private fun tryParsePromqlGrafanaTarget(
        target: JsonObject,
        warnings: MutableList<String>,
        panelIndex: Int,
        legendFormat: String?
    ): QueryDsl? {
        val expr = target["expr"]?.jsonPrimitive?.contentOrNull
        if (expr.isNullOrBlank()) return null
        val parsed = parsePromQL(expr, warnings, panelIndex)
        return if (legendFormat != null && parsed.metrics.isNotEmpty()) {
            parsed.copy(metrics = parsed.metrics.map { it.copy(alias = legendFormat) })
        } else {
            parsed
        }
    }

    private fun tryParseSqlGrafanaTarget(
        target: JsonObject,
        warnings: MutableList<String>,
        panelIndex: Int
    ): QueryDsl? {
        val rawSql = target["rawSql"]?.jsonPrimitive?.contentOrNull ?: return null
        return parseGrafanaSql(rawSql, warnings, panelIndex)
    }

    /** ES targets with Lucene query + aggregations — before generic [query] handling. */
    private fun tryParseElasticsearchGrafanaTarget(
        target: JsonObject,
        legendFormat: String?
    ): QueryDsl? {
        val esMetrics = target["metrics"] as? JsonArray
        val esBucketAggs = target["bucketAggs"] as? JsonArray
        if (esMetrics == null && esBucketAggs == null) return null
        val esQuery = translateGrafanaElasticsearchTarget(target)
        return QueryDsl(
            dataSource = "__elasticsearch",
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = legendFormat ?: "count")),
            rawQuery = esQuery
        )
    }

    private fun tryParseGenericQueryGrafanaTarget(
        target: JsonObject,
        warnings: MutableList<String>,
        panelIndex: Int,
        legendFormat: String?
    ): QueryDsl? {
        val query = target["query"]?.jsonPrimitive?.contentOrNull
        if (query.isNullOrBlank()) return null
        warnings.add("Panel $panelIndex: generic query stored as rawQuery")
        return QueryDsl(
            dataSource = "events",
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = legendFormat ?: "count")),
            rawQuery = query
        )
    }

    private fun tryParseRedisGrafanaTarget(target: JsonObject, legendFormat: String?): QueryDsl? {
        val redisCommand = target["command"]?.jsonPrimitive?.contentOrNull ?: return null
        val rawCmd = translateGrafanaRedisCommand(redisCommand, target)
        return QueryDsl(
            dataSource = "__redis",
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = legendFormat ?: "count")),
            rawQuery = rawCmd
        )
    }

    private fun tryParseInfluxGrafanaTarget(target: JsonObject, legendFormat: String?): QueryDsl? {
        if (target["measurement"]?.jsonPrimitive?.contentOrNull == null) return null
        val fluxFilter = translateGrafanaInfluxTarget(target)
        return QueryDsl(
            dataSource = "__influxdb",
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = legendFormat ?: "count")),
            rawQuery = fluxFilter
        )
    }

    private fun tryParseCloudWatchGrafanaTarget(target: JsonObject, legendFormat: String?): QueryDsl? {
        val namespace = target["namespace"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!target.containsKey("metricName")) return null
        val cwJson = translateGrafanaCloudWatchTarget(target)
        return QueryDsl(
            dataSource = "__cloudwatch",
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = legendFormat ?: "count")),
            rawQuery = cwJson
        )
    }

    private fun tryParseGraphiteGrafanaTarget(target: JsonObject, legendFormat: String?): QueryDsl? {
        val graphiteTarget = target["target"]?.jsonPrimitive?.contentOrNull
        if (graphiteTarget.isNullOrBlank()) return null
        return QueryDsl(
            dataSource = "__graphite",
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = legendFormat ?: "count")),
            rawQuery = graphiteTarget
        )
    }

    private fun parseGrafanaTargetFallback(
        target: JsonObject,
        warnings: MutableList<String>,
        panelIndex: Int,
        legendFormat: String?
    ): QueryDsl {
        val knownKeys = setOf("refId", "datasource", "legendFormat", "query")
        val hasExtraFields = target.keys.any { it !in knownKeys }
        if (hasExtraFields) {
            warnings.add(
                "Panel $panelIndex: non-standard query target stored as rawQuery"
            )
            return QueryDsl(
                dataSource = "events",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = legendFormat ?: "count")),
                rawQuery = target.toString()
            )
        }
        warnings.add("Panel $panelIndex: no recognizable query target")
        return QueryDsl(
            dataSource = "events",
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count"))
        )
    }

    /**
     * Convert Grafana Redis datasource plugin target to a Redis command string
     * that [RedisHandler] can execute directly.
     */
    internal fun translateGrafanaRedisCommand(
        command: String,
        target: JsonObject
    ): String {
        val section = target["section"]?.jsonPrimitive?.contentOrNull
        val field = target["query"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        return when (command.lowercase()) {
            "info" -> buildString {
                append("INFO")
                if (!section.isNullOrBlank()) append(" $section")
                if (!field.isNullOrBlank()) append(" $field")
            }
            "clientlist" -> "CLIENT LIST"
            "slowlogget" -> "SLOWLOG GET"
            "clusterinfo" -> "CLUSTER INFO"
            "clusternodes" -> "CLUSTER NODES"
            "dbsize" -> "DBSIZE"
            else -> throw IllegalArgumentException(
                "Unsupported Redis command for import: $command"
            )
        }
    }

    /**
     * Convert Grafana InfluxDB plugin target to a Flux filter() expression
     * that [InfluxDBHandler] can wrap with from(bucket)/range.
     */
    internal fun translateGrafanaInfluxTarget(target: JsonObject): String {
        val measurement = target["measurement"]?.jsonPrimitive?.contentOrNull ?: ""
        val conditions = mutableListOf<String>()
        if (measurement.isNotBlank()) {
            conditions.add("r._measurement == \"$measurement\"")
        }
        // Extract field names from the select array
        // Format: [[{type:"field",params:["usage_idle"]},{type:"mean"}],...]
        val selectArr = target["select"]?.jsonArray
        val fieldPredicates = mutableListOf<String>()
        if (selectArr != null) {
            for (col in selectArr) {
                val parts = col.jsonArray
                val fieldPart = parts.firstOrNull {
                    it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "field"
                }
                val fieldName = fieldPart?.jsonObject
                    ?.get("params")?.jsonArray
                    ?.firstOrNull()?.jsonPrimitive?.contentOrNull
                if (!fieldName.isNullOrBlank()) {
                    fieldPredicates.add("r._field == \"$fieldName\"")
                }
            }
        }
        if (fieldPredicates.size == 1) {
            conditions.add(fieldPredicates[0])
        } else if (fieldPredicates.size > 1) {
            conditions.add("(${fieldPredicates.joinToString(" or ")})")
        }
        // Extract tag filters
        val tags = target["tags"]?.jsonArray
        if (tags != null) {
            for (tag in tags) {
                val obj = tag.jsonObject
                val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: continue
                val value = obj["value"]?.jsonPrimitive?.contentOrNull ?: continue
                val op = obj["operator"]?.jsonPrimitive?.contentOrNull ?: "="
                val fluxOp = if (op == "=~") "=~" else "=="
                val fluxVal = if (fluxOp == "=~") value else "\"$value\""
                conditions.add("r.$key $fluxOp $fluxVal")
            }
        }
        return if (conditions.isNotEmpty()) {
            "filter(fn: (r) => ${conditions.joinToString(" and ")})"
        } else {
            "filter(fn: (r) => true)"
        }
    }

    /**
     * Convert Grafana CloudWatch plugin target to the PascalCase JSON
     * that [CloudWatchHandler] expects.
     */
    internal fun translateGrafanaCloudWatchTarget(target: JsonObject): String {
        return buildJsonObject {
            target["namespace"]?.jsonPrimitive?.contentOrNull?.let { put("Namespace", it) }
            target["metricName"]?.jsonPrimitive?.contentOrNull?.let { put("MetricName", it) }
            target["period"]?.jsonPrimitive?.contentOrNull?.let { put("Period", it) }
            val stats = target["statistics"]?.jsonArray
            if (stats != null) {
                put("Statistics", stats)
            }
            val dims = target["dimensions"]
            if (dims is JsonObject) {
                put("Dimensions", buildCloudWatchDimensionsArray(dims))
            }
        }.toString()
    }

    private fun buildCloudWatchDimensionsArray(dims: JsonObject): JsonArray = buildJsonArray {
        for ((key, value) in dims) {
            val vals = cloudWatchDimensionValueStrings(value)
            for (v in vals) {
                add(
                    buildJsonObject {
                        put("Name", key)
                        put("Value", v)
                    }
                )
            }
        }
    }

    private fun cloudWatchDimensionValueStrings(value: JsonElement): List<String> =
        if (value is JsonArray) {
            value.map { it.jsonPrimitive.content }
        } else {
            listOf(value.jsonPrimitive.content)
        }

    /**
     * Convert Grafana Elasticsearch plugin target to an ES query JSON body
     * from the metrics/bucketAggs arrays + Lucene query filter.
     */
    internal fun translateGrafanaElasticsearchTarget(target: JsonObject): String {
        val luceneQuery = target["query"]?.jsonPrimitive?.contentOrNull ?: "*"
        val metricsArr = target["metrics"]?.jsonArray
        val bucketAggsArr = target["bucketAggs"]?.jsonArray

        return buildJsonObject {
            // Query section
            put(
                "query",
                buildJsonObject {
                    put(
                        "query_string",
                        buildJsonObject {
                            put("query", luceneQuery)
                        }
                    )
                }
            )
            put("size", 0)

            // Build aggregations from bucketAggs + metrics
            if (bucketAggsArr != null && bucketAggsArr.isNotEmpty()) {
                put("aggs", buildBucketAggs(bucketAggsArr, metricsArr))
            } else if (metricsArr != null) {
                // No bucket aggs — just metric aggs at top level
                put("aggs", buildMetricAggs(metricsArr))
            }
        }.toString()
    }

    private fun buildBucketAggs(
        bucketAggs: JsonArray,
        metrics: JsonArray?
    ): JsonObject {
        // Recursively nest bucket aggs; innermost gets metric aggs
        val first = bucketAggs.firstOrNull()?.jsonObject ?: return buildMetricAggs(metrics)
        val id = first["id"]?.jsonPrimitive?.contentOrNull ?: "1"
        val type = first["type"]?.jsonPrimitive?.contentOrNull ?: "terms"
        val field = first["field"]?.jsonPrimitive?.contentOrNull
        val settings = first["settings"]?.jsonObject

        val innerAggs = if (bucketAggs.size > 1) {
            val remaining = JsonArray(bucketAggs.drop(1))
            buildBucketAggs(remaining, metrics)
        } else {
            buildMetricAggs(metrics)
        }

        return buildJsonObject {
            put(
                id,
                buildJsonObject {
                    put(
                        type,
                        buildJsonObject {
                            if (field != null) put("field", field)
                            if (settings != null) {
                                for ((k, v) in settings) {
                                    put(k, v)
                                }
                            }
                        }
                    )
                    if (innerAggs.isNotEmpty()) {
                        put("aggs", innerAggs)
                    }
                }
            )
        }
    }

    private fun buildMetricAggs(metrics: JsonArray?): JsonObject {
        if (metrics == null) return JsonObject(emptyMap())
        return buildJsonObject {
            for (metric in metrics) {
                val obj = metric.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue
                if (type == "count") continue // count is implicit
                val field = obj["field"]?.jsonPrimitive?.contentOrNull ?: continue
                put(
                    id,
                    buildJsonObject {
                        put(
                            type,
                            buildJsonObject {
                                put("field", field)
                            }
                        )
                    }
                )
            }
        }
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

        val parsedMetric = resolvePromqlMetricAndLabels(aggByMatch, funcMatch, bareMatch)
        if (parsedMetric == null) {
            warnings.add("Panel $panelIndex: couldn't parse PromQL '$expr', stored as rawQuery")
            return QueryDsl(
                dataSource = "__prometheus",
                metrics = listOf(MetricDef(AggFunction.AVG, alias = "value")),
                rawQuery = expr,
                limit = 5000
            )
        }
        val (metricName, labelStr, aggFunction) = parsedMetric
        val filters = parsePromqlLabelMatchers(labelStr)

        return QueryDsl(
            dataSource = "__prometheus",
            metrics = listOf(MetricDef(aggFunction, mapPromMetricField(metricName), "value")),
            groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "auto")),
            filters = filters,
            rawQuery = expr,
            limit = 5000
        )
    }

    private fun resolvePromqlMetricAndLabels(
        aggByMatch: MatchResult?,
        funcMatch: MatchResult?,
        bareMatch: MatchResult?
    ): Triple<String, String, AggFunction>? = when {
        aggByMatch != null -> {
            val innerExpr = aggByMatch.groupValues[REGEX_THIRD_GROUP_IDX].trim()
            val innerFunc = Regex("""(\w+)\(([^{(]+?)(?:\{[^}]*\})?(?:\[[^]]*])?.*\)""").find(innerExpr)
            val metric = innerFunc?.groupValues?.getOrNull(2)?.trim() ?: "unknown"
            val innerLabels = Regex("""\{([^}]*)\}""").find(innerExpr)?.groupValues?.get(1) ?: ""
            Triple(metric, innerLabels, mapPromFunction(aggByMatch.groupValues[1]))
        }
        funcMatch != null -> {
            Triple(
                funcMatch.groupValues[2].trim(),
                funcMatch.groupValues[REGEX_THIRD_GROUP_IDX],
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
        else -> null
    }

    private fun parsePromqlLabelMatchers(labelStr: String): List<FilterDef> {
        if (labelStr.isBlank()) return emptyList()
        val labelRegex = Regex("""(\w+)\s*(=~|!=|!~|=)\s*"([^"]*?)"""")
        return labelRegex.findAll(labelStr).map { m ->
            val op = when (m.groupValues[2]) {
                "=~" -> FilterOp.LIKE
                "!~" -> FilterOp.NOT_LIKE
                "!=" -> FilterOp.NEQ
                else -> FilterOp.EQ
            }
            FilterDef(m.groupValues[1], op, m.groupValues[REGEX_THIRD_GROUP_IDX])
        }.toList()
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

    /**
     * Parse Grafana `__inputs` array to build a map from template variable
     * names (e.g. "DS_REDIS") to their pluginId (e.g. "redis-datasource").
     */
    internal fun parseInputsMap(json: JsonObject): Map<String, String> {
        val inputs = json["__inputs"]?.jsonArray ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        for (element in inputs) {
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type != "datasource") continue
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val pluginId = obj["pluginId"]?.jsonPrimitive?.contentOrNull ?: continue
            map[name] = pluginId
        }
        return map
    }

    internal fun parseGrafanaVariables(json: JsonObject): List<DashboardVariable> {
        val templating = json["templating"]?.jsonObject ?: return emptyList()
        val list = templating["list"]?.jsonArray ?: return emptyList()
        val inputsMap = parseInputsMap(json)

        return list.mapNotNull { element ->
            suspendRunCatching {
                parseSingleGrafanaVariable(element.jsonObject, inputsMap)
            }.getOrElse { e ->
                logger.warn { "Failed to parse Grafana variable: ${e.message}" }
                null
            }
        }
    }

    private fun parseSingleGrafanaVariable(
        varObj: JsonObject,
        inputsMap: Map<String, String>
    ): DashboardVariable? {
        val name = varObj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val type = varObj["type"]?.jsonPrimitive?.contentOrNull ?: "custom"
        val label = varObj["label"]?.jsonPrimitive?.contentOrNull
        val query = varObj["query"]?.let(::grafanaVariableQueryFromJson)
        val current = varObj["current"]?.jsonObject?.get("value")?.let(::grafanaVariableCurrentString)
        val options = varObj["options"]?.jsonArray?.mapNotNull { opt ->
            opt.jsonObject["value"]?.jsonPrimitive?.contentOrNull
        } ?: emptyList()
        val datasource = varObj["datasource"]?.let { grafanaVariableDatasourceFromJson(it, inputsMap) }

        val supportedTypes = setOf("query", "custom", "textbox", "constant")
        return DashboardVariable(
            name = name,
            label = label,
            type = if (type in supportedTypes) type else "custom",
            query = query,
            defaultValue = current,
            current = current,
            options = options.filter { it != "\$__all" },
            datasource = datasource
        )
    }

    private fun grafanaVariableQueryFromJson(q: JsonElement): String? = when (q) {
        is JsonPrimitive -> q.contentOrNull
        is JsonObject -> q["query"]?.jsonPrimitive?.contentOrNull
        else -> null
    }

    private fun grafanaVariableCurrentString(v: JsonElement): String? = when (v) {
        is JsonPrimitive -> v.contentOrNull
        else -> null
    }

    private fun grafanaVariableDatasourceFromJson(
        ds: JsonElement,
        inputsMap: Map<String, String>
    ): String? = when (ds) {
        is JsonPrimitive -> ds.contentOrNull?.let { datasourceRef ->
            val resolved = resolveDatasourceRef(datasourceRef, inputsMap)
            grafanaDatasourceToMoneatMarker(resolved)
        }
        is JsonObject -> ds["type"]?.jsonPrimitive?.contentOrNull?.let { grafanaDatasourceToMoneatMarker(it) }
        else -> null
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
            put("schemaVersion", GRAFANA_SCHEMA_VERSION)
            put("version", 1)
            put("timezone", "browser")
        }
    }

    internal fun buildGrafanaSql(dsl: QueryDsl): String {
        val rawQuery = dsl.rawQuery
        if (rawQuery != null) return rawQuery

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
