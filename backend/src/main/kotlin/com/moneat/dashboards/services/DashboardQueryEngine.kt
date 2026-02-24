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

package com.moneat.dashboards.services

import com.moneat.config.ClickHouseClient
import com.moneat.dashboards.models.CustomDataSourceResponse
import com.moneat.dashboards.models.DataSource
import com.moneat.dashboards.models.DataSourceField
import com.moneat.dashboards.models.DataSourceInfo
import com.moneat.dashboards.models.FilterDef
import com.moneat.dashboards.models.FilterOp
import com.moneat.dashboards.models.GroupByType
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.TimeRangeDef
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import kotlin.collections.filter

private val logger = KotlinLogging.logger {}

class DashboardQueryEngine {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val json = Json { ignoreUnknownKeys = true }

    private val allowedTables = setOf(
        "events",
        "spans",
        "logs",
        "system_metrics",
        "container_metrics",
        "uptime_heartbeats",
        "llm_generations",
        "analytics_events"
    )

    companion object {
        private val INTERVAL_REGEX =
            Regex("""^\d+\s+(SECOND|MINUTE|HOUR|DAY|WEEK|MONTH|YEAR)$""", RegexOption.IGNORE_CASE)

        private val TIMESTAMP_COLUMNS = mapOf(
            "events" to "timestamp",
            "spans" to "timestamp",
            "logs" to "timestamp",
            "system_metrics" to "timestamp",
            "container_metrics" to "timestamp",
            "uptime_heartbeats" to "timestamp",
            "llm_generations" to "timestamp",
            "analytics_events" to "timestamp"
        )

        private val TIME_RANGE_REGEX = Regex("""^now-(\d+)([smhdwMy])$""")

        fun resolveTimeInterval(from: String, to: String): String {
            val rangeMs = parseRelativeTime(to) - parseRelativeTime(from)
            val rangeMinutes = rangeMs / 60_000
            return when {
                rangeMinutes <= 60 -> "1 MINUTE"
                rangeMinutes <= 360 -> "5 MINUTE"
                rangeMinutes <= 1440 -> "15 MINUTE"
                rangeMinutes <= 10080 -> "1 HOUR"
                rangeMinutes <= 43200 -> "4 HOUR"
                rangeMinutes <= 129600 -> "1 DAY"
                else -> "1 WEEK"
            }
        }

        private fun parseRelativeTime(expr: String): Long {
            if (expr == "now") return System.currentTimeMillis()
            val match = TIME_RANGE_REGEX.matchEntire(expr) ?: return System.currentTimeMillis()
            val amount = match.groupValues[1].toLong()
            val unit = match.groupValues[2]
            val ms = when (unit) {
                "s" -> amount * 1000
                "m" -> amount * 60_000
                "h" -> amount * 3_600_000
                "d" -> amount * 86_400_000
                "w" -> amount * 604_800_000
                "M" -> amount * 2_592_000_000
                "y" -> amount * 31_536_000_000
                else -> 0
            }
            return System.currentTimeMillis() - ms
        }
    }

    /**
     * Substitutes $varName and ${varName} patterns in a QueryDsl with sanitized variable values.
     */
    fun applyVariables(dsl: QueryDsl, variables: Map<String, String>): QueryDsl {
        if (variables.isEmpty()) return dsl

        // Sort by name length descending to prevent greedy matching
        // (e.g., $instance matching inside $instance_id)
        val sortedVars = variables.entries.sortedByDescending { it.key.length }

        fun substituteVars(input: String?): String? {
            if (input == null) return null
            var result: String = input
            for ((name, value) in sortedVars) {
                // Grafana's $__all means "match all" — use regex wildcard in PromQL
                val substitution = if (value == "\$__all") ".*" else ClickHouseSqlUtils.escapeSql(value)
                result = result
                    .replace("\${$name}", substitution)
                // Use word-boundary-aware replacement for bare $name
                result = Regex("""\$${Regex.escape(name)}(?![a-zA-Z0-9_])""")
                    .replace(result, Regex.escapeReplacement(substitution))
            }
            // When $__all produced .*, upgrade exact match to regex match in PromQL label selectors
            if (result.contains("=\".*\"")) {
                result = result.replace("=\".*\"", "=~\".*\"")
            }
            return result
        }

        return dsl.copy(
            filters = dsl.filters.map { f ->
                f.copy(
                    value = substituteVars(f.value),
                    values = f.values?.map { substituteVars(it) ?: it }
                )
            },
            rawQuery = substituteVars(dsl.rawQuery)
        )
    }

    fun buildQuery(
        dsl: QueryDsl,
        projectId: Long,
        demoEpochMs: Long? = null,
        retentionDays: Int = 90
    ): String {
        val dataSource = DataSource.fromString(dsl.dataSource)
            ?: throw IllegalArgumentException("Unknown data source: ${dsl.dataSource}")

        require(dataSource.tableName in allowedTables) {
            "Data source not allowed: ${dsl.dataSource}"
        }

        val table = "$clickhouseDb.${dataSource.tableName}"
        val tsCol = TIMESTAMP_COLUMNS[dataSource.tableName] ?: "timestamp"

        val selectClauses = buildSelectClauses(dsl, tsCol)
        val whereClauses = buildWhereClauses(dsl, projectId, tsCol, demoEpochMs, retentionDays)
        val groupByClauses = buildGroupByClauses(dsl)
        val orderByClause = buildOrderByClause(dsl)

        return buildString {
            append("SELECT ")
            append(selectClauses.joinToString(", "))
            append(" FROM $table")
            append(" WHERE ")
            append(whereClauses.joinToString(" AND "))
            if (groupByClauses.isNotEmpty()) {
                append(" GROUP BY ")
                append(groupByClauses.joinToString(", "))
            }
            if (orderByClause.isNotEmpty()) {
                append(" ORDER BY $orderByClause")
            }
            append(" LIMIT ${dsl.limit.coerceIn(1, 10000)}")
            append(" FORMAT JSONEachRow")
        }
    }

    internal fun buildSelectClauses(dsl: QueryDsl, tsCol: String): List<String> {
        val clauses = mutableListOf<String>()

        // Add group-by fields to select
        for (gb in dsl.groupBy) {
            when (gb.type) {
                GroupByType.TIME -> {
                    val interval = if (gb.interval == "auto" || gb.interval == null) {
                        resolveTimeInterval(dsl.timeRange.from, dsl.timeRange.to)
                    } else {
                        require(INTERVAL_REGEX.matches(gb.interval)) {
                            "Invalid interval: ${gb.interval}. Must be e.g. '1 MINUTE', '5 HOUR'."
                        }
                        gb.interval
                    }
                    clauses.add("toStartOfInterval($tsCol, INTERVAL $interval) AS time_bucket")
                }
                GroupByType.FIELD -> {
                    ClickHouseSqlUtils.validateFieldName(gb.field)
                    clauses.add(gb.field)
                }
            }
        }

        // Add metric aggregations
        for (metric in dsl.metrics) {
            metric.field?.let { ClickHouseSqlUtils.validateFieldName(it) }
            val aggExpr = metric.function.toClickHouse(metric.field)
            val alias = metric.alias ?: "${metric.function.value}_${metric.field ?: "all"}"
            ClickHouseSqlUtils.validateFieldName(alias)
            clauses.add("$aggExpr AS $alias")
        }

        if (clauses.isEmpty()) {
            clauses.add("count() AS total")
        }

        return clauses
    }

    internal fun buildWhereClauses(
        dsl: QueryDsl,
        projectId: Long,
        tsCol: String,
        demoEpochMs: Long?,
        retentionDays: Int
    ): List<String> {
        val clauses = mutableListOf<String>()

        clauses.add(ClickHouseQueryUtils.projectIdClause(projectId))
        clauses.add(ClickHouseQueryUtils.timestampRetentionClause(tsCol, retentionDays, demoEpochMs))
        clauses.addAll(buildTimeRangeClauses(dsl.timeRange, tsCol, demoEpochMs))

        for (filter in dsl.filters) {
            clauses.add(buildFilterClause(filter))
        }

        return clauses
    }

    internal fun buildTimeRangeClauses(
        timeRange: TimeRangeDef,
        tsCol: String,
        demoEpochMs: Long?
    ): List<String> {
        val clauses = mutableListOf<String>()
        val nowExpr = if (demoEpochMs != null) {
            "toDateTime64(${demoEpochMs / 1000.0}, 3)"
        } else {
            "now()"
        }

        val fromExpr = parseTimeExpression(timeRange.from, nowExpr)
        val toExpr = parseTimeExpression(timeRange.to, nowExpr)
        clauses.add("$tsCol >= $fromExpr")
        clauses.add("$tsCol <= $toExpr")
        return clauses
    }

    internal fun parseTimeExpression(expr: String, nowExpr: String): String {
        if (expr == "now") return nowExpr
        val match = TIME_RANGE_REGEX.matchEntire(expr)
        if (match != null) {
            val amount = match.groupValues[1]
            val unit = match.groupValues[2]
            val chUnit = when (unit) {
                "s" -> "SECOND"
                "m" -> "MINUTE"
                "h" -> "HOUR"
                "d" -> "DAY"
                "w" -> "WEEK"
                "M" -> "MONTH"
                "y" -> "YEAR"
                else -> "DAY"
            }
            return "$nowExpr - INTERVAL $amount $chUnit"
        }
        // Assume ISO timestamp
        val escaped = ClickHouseSqlUtils.escapeSql(expr)
        return "toDateTime64('$escaped', 3)"
    }

    internal fun buildFilterClause(filter: FilterDef): String {
        ClickHouseSqlUtils.validateFieldName(filter.field)

        return when (filter.op) {
            FilterOp.IS_NULL -> "${filter.field} IS NULL"
            FilterOp.IS_NOT_NULL -> "${filter.field} IS NOT NULL"
            FilterOp.IN, FilterOp.NOT_IN -> {
                val vals = (filter.values ?: listOfNotNull(filter.value))
                    .joinToString(", ") { "'${ClickHouseSqlUtils.escapeSql(it)}'" }
                "${filter.field} ${filter.op.value} ($vals)"
            }
            FilterOp.LIKE, FilterOp.NOT_LIKE -> {
                val escaped = ClickHouseSqlUtils.escapeLikePattern(filter.value)
                "${filter.field} ${filter.op.value} '%$escaped%'"
            }
            else -> {
                val escaped = ClickHouseSqlUtils.escapeSql(filter.value)
                "${filter.field} ${filter.op.value} '$escaped'"
            }
        }
    }

    internal fun buildGroupByClauses(dsl: QueryDsl): List<String> {
        return dsl.groupBy.map { gb ->
            when (gb.type) {
                GroupByType.TIME -> "time_bucket"
                GroupByType.FIELD -> {
                    ClickHouseSqlUtils.validateFieldName(gb.field)
                    gb.field
                }
            }
        }
    }

    internal fun buildOrderByClause(dsl: QueryDsl): String {
        if (dsl.orderBy != null) {
            ClickHouseSqlUtils.validateFieldName(dsl.orderBy.field)
            val dir = if (dsl.orderBy.direction.lowercase() == "asc") "ASC" else "DESC"
            return "${dsl.orderBy.field} $dir"
        }
        // Default: order by time_bucket if time grouping exists
        val hasTimeGroup = dsl.groupBy.any { it.type == GroupByType.TIME }
        return if (hasTimeGroup) "time_bucket ASC" else ""
    }

    suspend fun executeQuery(
        dsl: QueryDsl,
        projectId: Long,
        demoEpochMs: Long? = null,
        retentionDays: Int = 90
    ): List<Map<String, JsonElement>> {
        if (dsl.rawQuery != null) {
            logger.warn { "Skipping raw query execution for security - use query DSL" }
            return emptyList()
        }

        val sql = buildQuery(dsl, projectId, demoEpochMs, retentionDays)
        logger.debug { "Executing dashboard query: ${sql.take(500)}" }

        return try {
            val response = ClickHouseClient.execute(sql)
            val body = response.bodyAsText()

            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "ClickHouse error: ${body.take(400)}" }
                return emptyList()
            }

            if (body.isBlank()) return emptyList()

            body.lines()
                .filter { it.isNotBlank() }
                .map { line -> json.parseToJsonElement(line).jsonObject.toMap() }
        } catch (e: Exception) {
            logger.error(e) { "Failed to execute dashboard query" }
            emptyList()
        }
    }

    /**
     * Returns all data sources: built-in ClickHouse sources + custom org-level sources.
     */
    fun getDataSources(customSources: List<CustomDataSourceResponse> = emptyList()): List<DataSourceInfo> {
        val builtIn = getBuiltInDataSources()
        val custom = customSources.filter { it.enabled }.map { src ->
            DataSourceInfo(
                name = "custom:${src.id}",
                label = "${src.name} (${src.sourceType})",
                fields = emptyList() // Fields fetched on demand via schema endpoint
            )
        }
        return builtIn + custom
    }

    fun isCustomDataSource(dataSource: String): Boolean = dataSource.startsWith("custom:")

    fun parseCustomDataSourceId(dataSource: String): Long? =
        if (dataSource.startsWith("custom:")) dataSource.removePrefix("custom:").toLongOrNull() else null

    private fun getBuiltInDataSources(): List<DataSourceInfo> = listOf(
        DataSourceInfo(
            "events",
            "Error Events",
            listOf(
                DataSourceField("timestamp", "DateTime64", "Event timestamp"),
                DataSourceField("level", "String", "Error level"),
                DataSourceField("environment", "String", "Environment"),
                DataSourceField("release", "String", "Release version"),
                DataSourceField("user_id", "String", "User identifier"),
                DataSourceField("transaction", "String", "Transaction name"),
                DataSourceField("platform", "String", "Platform"),
            )
        ),
        DataSourceInfo(
            "spans",
            "Trace Spans",
            listOf(
                DataSourceField("timestamp", "DateTime64", "Span start time"),
                DataSourceField("duration_ms", "Float64", "Duration in milliseconds"),
                DataSourceField("op", "String", "Operation name"),
                DataSourceField("description", "String", "Span description"),
                DataSourceField("status", "String", "Span status"),
                DataSourceField("environment", "String", "Environment"),
            )
        ),
        DataSourceInfo(
            "logs",
            "Log Entries",
            listOf(
                DataSourceField("timestamp", "DateTime64", "Log timestamp"),
                DataSourceField("level", "String", "Log level"),
                DataSourceField("message", "String", "Log message"),
                DataSourceField("service", "String", "Service name"),
                DataSourceField("environment", "String", "Environment"),
                DataSourceField("host", "String", "Hostname"),
            )
        ),
        DataSourceInfo(
            "system_metrics",
            "System Metrics",
            listOf(
                DataSourceField("timestamp", "DateTime64", "Metric timestamp"),
                DataSourceField("cpu_percent", "Float32", "CPU usage percent"),
                DataSourceField("mem_used", "UInt64", "Memory used bytes"),
                DataSourceField("disk_used", "UInt64", "Disk used bytes"),
                DataSourceField("load_1", "Float32", "1-minute load average"),
                DataSourceField("net_recv_bytes", "UInt64", "Network bytes received"),
                DataSourceField("net_sent_bytes", "UInt64", "Network bytes sent"),
            )
        ),
        DataSourceInfo(
            "container_metrics",
            "Container Metrics",
            listOf(
                DataSourceField("timestamp", "DateTime64", "Metric timestamp"),
                DataSourceField("name", "String", "Container name"),
                DataSourceField("cpu_percent", "Float32", "CPU usage percent"),
                DataSourceField("mem_used", "UInt64", "Memory used bytes"),
            )
        ),
        DataSourceInfo(
            "uptime_heartbeats",
            "Uptime Heartbeats",
            listOf(
                DataSourceField("timestamp", "DateTime64", "Check timestamp"),
                DataSourceField("status", "String", "Check status"),
                DataSourceField("response_time_ms", "Float64", "Response time ms"),
            )
        ),
        DataSourceInfo(
            "llm_generations",
            "LLM Generations",
            listOf(
                DataSourceField("timestamp", "DateTime64", "Generation timestamp"),
                DataSourceField("model", "String", "Model name"),
                DataSourceField("provider", "String", "Provider"),
                DataSourceField("prompt_tokens", "UInt32", "Prompt token count"),
                DataSourceField("completion_tokens", "UInt32", "Completion token count"),
                DataSourceField("duration_ms", "Float64", "Duration in milliseconds"),
                DataSourceField("cost", "Float64", "Estimated cost"),
            )
        ),
        DataSourceInfo(
            "analytics_events",
            "Product Analytics",
            listOf(
                DataSourceField("timestamp", "DateTime64", "Event timestamp"),
                DataSourceField("event_name", "String", "Event name"),
                DataSourceField("page_path", "String", "Page URL path"),
                DataSourceField("referrer_source", "String", "Traffic source"),
                DataSourceField("country", "String", "Country code"),
                DataSourceField("browser", "String", "Browser"),
                DataSourceField("os", "String", "Operating system"),
            )
        )
    )
}
