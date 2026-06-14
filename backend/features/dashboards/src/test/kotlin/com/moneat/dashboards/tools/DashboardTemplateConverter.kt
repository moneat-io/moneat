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

package com.moneat.dashboards.tools

import com.moneat.dashboards.models.CreateDashboardRequest
import com.moneat.dashboards.models.CreateWidgetRequest
import com.moneat.dashboards.models.DashboardTemplateCatalog
import com.moneat.dashboards.models.DashboardTemplateDetail
import com.moneat.dashboards.models.DashboardTemplateSummary
import com.moneat.dashboards.translation.GrafanaTranslator
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

private const val DEFAULT_SOURCE_ROOT = "../grafana-dashboards"
private const val DEFAULT_OUTPUT_ROOT = "src/main/resources/dashboard-templates"
private const val DEFAULT_REPORT_PATH = "build/reports/dashboard-template-conversion.json"
private const val COMMUNITY_TEMPLATE_DIR = "community"

private val TAG_RULES = listOf(
    KeywordRule("Kubernetes", setOf("kubernetes", "k8s")),
    KeywordRule("Infrastructure", setOf("node", "host")),
    KeywordRule("Cloud", setOf("aws", "amazon")),
    KeywordRule("Databases", setOf("postgres", "mysql", "mongodb")),
    KeywordRule("Messaging", setOf("redis", "rabbitmq", "kafka")),
    KeywordRule("Network", setOf("nginx", "traefik", "envoy", "istio")),
    KeywordRule("Logs", setOf("logs", "loki")),
    KeywordRule("Applications", setOf("jvm", "spring", "nodejs")),
)

private val CATEGORY_RULES = listOf(
    KeywordRule("logs", setOf("logs")),
    KeywordRule("databases", setOf("database", "postgres", "postgresql", "mysql", "mongodb")),
    KeywordRule("cloud", setOf("cloud", "aws", "amazon")),
    KeywordRule("kubernetes", setOf("kubernetes", "k8s")),
    KeywordRule("applications", setOf("application", "jvm", "spring", "nodejs")),
)

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

fun main(args: Array<String>) {
    val sourceRoot = File(args.getOrNull(0) ?: DEFAULT_SOURCE_ROOT)
    val outputRoot = File(args.getOrNull(1) ?: DEFAULT_OUTPUT_ROOT)
    val reportFile = File(args.getOrNull(2) ?: DEFAULT_REPORT_PATH)
    val dashboardDir = if (sourceRoot.name == "dashboards") sourceRoot else File(sourceRoot, "dashboards")

    require(dashboardDir.isDirectory) { "Dashboard source directory not found: ${dashboardDir.absolutePath}" }

    val converter = DashboardTemplateConverter()
    val result = converter.convert(dashboardDir, outputRoot)

    reportFile.parentFile.mkdirs()
    reportFile.writeText(json.encodeToString(result.report))
    println(
        "Converted ${result.report.dashboards.size} dashboard templates to ${outputRoot.absolutePath}; " +
            "report: ${reportFile.absolutePath}"
    )
}

private class DashboardTemplateConverter {
    private val translator = GrafanaTranslator()

    fun convert(sourceDir: File, outputRoot: File): DashboardTemplateConversionResult {
        val templateDir = File(outputRoot, COMMUNITY_TEMPLATE_DIR)
        outputRoot.mkdirs()
        templateDir.mkdirs()

        val files = sourceDir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedBy { it.name }
            ?: emptyList()
        val summaries = mutableListOf<DashboardTemplateSummary>()
        val reportRows = mutableListOf<DashboardTemplateConversionRow>()

        for (file in files) {
            val parsed = json.parseToJsonElement(file.readText()).jsonObject
            val importResult = translator.import(parsed)
            val id = file.nameWithoutExtension
            val description = neutralDescription(importResult.dashboard.title)
            val requiredSources = requiredSources(importResult.dashboard.widgets.flatMap { it.queryConfigs })
            val widgets = importResult.dashboard.widgets.map { widget ->
                CreateWidgetRequest(
                    title = widget.title,
                    widgetType = widget.widgetType,
                    gridX = widget.gridX,
                    gridY = widget.gridY,
                    gridW = widget.gridW,
                    gridH = widget.gridH,
                    queryConfigs = widget.queryConfigs,
                    displayConfig = widget.displayConfig,
                    sortOrder = widget.sortOrder
                )
            }
            val createRequest = CreateDashboardRequest(
                title = importResult.dashboard.title,
                description = description,
                layoutType = importResult.dashboard.layoutType,
                variables = importResult.variables,
                widgets = widgets
            )
            val tags = inferTags(id, importResult.dashboard.title, requiredSources)
            val resourcePath = "dashboard-templates/$COMMUNITY_TEMPLATE_DIR/$id.json"
            val summary = DashboardTemplateSummary(
                id = id,
                title = importResult.dashboard.title,
                description = description,
                category = inferCategory(id, importResult.dashboard.title, tags),
                tags = tags,
                requiredSources = requiredSources,
                widgetCount = widgets.size,
                variableCount = importResult.variables.size,
                resourcePath = resourcePath
            )
            val detail = DashboardTemplateDetail(
                id = summary.id,
                title = summary.title,
                description = summary.description,
                category = summary.category,
                tags = summary.tags,
                requiredSources = summary.requiredSources,
                widgetCount = summary.widgetCount,
                variableCount = summary.variableCount,
                warnings = importResult.warnings,
                dashboard = createRequest
            )
            summaries += summary
            File(templateDir, "$id.json").writeText(json.encodeToString(detail))
            reportRows += DashboardTemplateConversionRow(
                id = id,
                file = file.name,
                title = summary.title,
                widgetCount = widgets.size,
                variableCount = importResult.variables.size,
                requiredSources = requiredSources,
                warnings = importResult.warnings
            )
        }

        val catalog = DashboardTemplateCatalog(summaries)
        File(outputRoot, "catalog.json").writeText(json.encodeToString(catalog))
        return DashboardTemplateConversionResult(
            DashboardTemplateConversionReport(
                dashboards = reportRows,
                warningSummary = reportRows.flatMap { it.warnings }
                    .groupingBy { it }
                    .eachCount()
                    .toSortedMap()
            )
        )
    }

    private fun requiredSources(queries: List<com.moneat.dashboards.models.QueryDsl>): List<String> =
        queries.mapNotNull { query ->
            when (query.dataSource) {
                "__prometheus" -> "Prometheus"
                "__cloudwatch" -> "CloudWatch"
                "__elasticsearch" -> "Elasticsearch"
                "__graphite" -> "Graphite"
                "__influxdb" -> "InfluxDB"
                "__loki" -> "Loki"
                "__postgresql", "__postgres" -> "PostgreSQL"
                "__redis" -> "Redis"
                else -> null
            }
        }.distinct().sorted()

    private fun neutralDescription(title: String): String =
        "Prebuilt Moneat dashboard for $title telemetry."

    private fun inferTags(id: String, title: String, requiredSources: List<String>): List<String> {
        val haystack = "$id $title".lowercase()
        val tags = mutableSetOf<String>()
        for (source in requiredSources) tags += source
        TAG_RULES.filter { it.matches(haystack) }.forEach { tags += it.label }
        if (tags.isEmpty()) tags += "Infrastructure"
        return tags.sorted()
    }

    private fun inferCategory(id: String, title: String, tags: List<String>): String {
        val haystack = "$id $title ${tags.joinToString(" ")}".lowercase()
        return CATEGORY_RULES.firstOrNull { it.matches(haystack) }?.label ?: "infrastructure"
    }
}

private data class KeywordRule(
    val label: String,
    val keywords: Set<String>,
) {
    fun matches(haystack: String): Boolean = keywords.any { it in haystack }
}

private data class DashboardTemplateConversionResult(
    val report: DashboardTemplateConversionReport
)

@Serializable
private data class DashboardTemplateConversionReport(
    val dashboards: List<DashboardTemplateConversionRow>,
    val warningSummary: Map<String, Int>,
)

@Serializable
private data class DashboardTemplateConversionRow(
    val id: String,
    val file: String,
    val title: String,
    val widgetCount: Int,
    val variableCount: Int,
    val requiredSources: List<String>,
    val warnings: List<String>,
)
