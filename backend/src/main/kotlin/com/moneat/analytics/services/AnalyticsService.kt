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

package com.moneat.analytics.services

import com.moneat.analytics.models.AnalyticsFilter
import com.moneat.analytics.models.AnalyticsOverviewResponse
import com.moneat.analytics.models.BreakdownResponse
import com.moneat.analytics.models.BreakdownRow
import com.moneat.analytics.models.FunnelResponse
import com.moneat.analytics.models.FunnelStep
import com.moneat.analytics.models.RealtimeResponse
import com.moneat.analytics.models.TimeseriesPoint
import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}
private val jsonParser = Json { ignoreUnknownKeys = true }

/**
 * Query builder for analytics dashboard endpoints.
 * Builds ClickHouse SQL queries with filters, date ranges, and comparison periods.
 */
class AnalyticsService {

    // --- Overview ---

    suspend fun getOverview(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        compFrom: LocalDate?,
        compTo: LocalDate?,
    ): AnalyticsOverviewResponse {
        val main = queryOverviewMetrics(projectId, dateFrom, dateTo, filters)

        val comp = if (compFrom != null && compTo != null) {
            queryOverviewMetrics(projectId, compFrom, compTo, filters)
        } else {
            null
        }

        return AnalyticsOverviewResponse(
            visitors = main.visitors,
            pageviews = main.pageviews,
            bounceRate = main.bounceRate,
            avgVisitDuration = main.avgVisitDuration,
            viewsPerVisit = main.viewsPerVisit,
            compVisitors = comp?.visitors,
            compPageviews = comp?.pageviews,
            compBounceRate = comp?.bounceRate,
            compAvgVisitDuration = comp?.avgVisitDuration,
            compViewsPerVisit = comp?.viewsPerVisit,
        )
    }

    private suspend fun queryOverviewMetrics(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): OverviewMetrics {
        val where = buildWhere(projectId, dateFrom, dateTo, filters, "s", "hour")
        // Pre-aggregate per session_id to correctly handle SummingMergeTree (which sums pageviews
        // and is_bounce across unmerged rows). Without this, is_bounce gets summed to N (one per
        // insert) and avg(is_bounce)*100 produces values like 45000%.
        val sql = """
            SELECT
                count() AS visitors,
                sum(total_pageviews) AS pageviews,
                if(count() > 0, toFloat64(countIf(total_pageviews = 1)) / count() * 100, 0) AS bounce_rate,
                ifNull(avg(total_duration_sec), 0) AS avg_visit_duration,
                if(count() > 0, toFloat64(sum(total_pageviews)) / count(), 0) AS views_per_visit
            FROM (
                SELECT
                    session_id,
                    sum(pageviews) AS total_pageviews,
                    dateDiff('second', min(started), max(ended)) AS total_duration_sec
                FROM analytics_sessions_hourly AS s
                WHERE $where
                GROUP BY session_id
            )
            FORMAT JSONEachRow
        """.trimIndent()

        val body = ClickHouseClient.executeWithFormat(sql, "JSONEachRow")
        if (body.isBlank()) return OverviewMetrics()

        val row = jsonParser.parseToJsonElement(body.trim().lines().first()).jsonObject
        return OverviewMetrics(
            visitors = row.longValue("visitors"),
            pageviews = row.longValue("pageviews"),
            bounceRate = row.doubleValue("bounce_rate"),
            avgVisitDuration = row.doubleValue("avg_visit_duration"),
            viewsPerVisit = row.doubleValue("views_per_visit"),
        )
    }

    // --- Timeseries ---

    suspend fun getTimeseries(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): List<TimeseriesPoint> {
        val where = buildWhere(projectId, dateFrom, dateTo, filters, "s", "hour")
        val interval = if (dateTo.toEpochDay() - dateFrom.toEpochDay() <= 2) "toStartOfHour" else "toDate"

        val sql = """
            SELECT
                $interval(s.hour) AS date,
                uniq(s.session_id) AS visitors,
                sum(s.pageviews) AS pageviews
            FROM analytics_sessions_hourly AS s
            WHERE $where
            GROUP BY date
            ORDER BY date
            FORMAT JSONEachRow
        """.trimIndent()

        return parseRows(ClickHouseClient.executeWithFormat(sql, "JSONEachRow")) { row ->
            TimeseriesPoint(
                date = row.stringValue("date"),
                visitors = row.longValue("visitors"),
                pageviews = row.longValue("pageviews"),
            )
        }
    }

    // --- Breakdown queries ---

    suspend fun getBreakdown(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        dimension: String,
        limit: Int = 100,
    ): BreakdownResponse {
        val (column, table, alias) = resolveDimension(dimension)
        val timeColumn = if (alias == "s") "hour" else "timestamp"
        val where = buildWhere(projectId, dateFrom, dateTo, filters, alias, timeColumn)

        val sql = """
            SELECT
                $column AS name,
                uniq($alias.session_id) AS visitors,
                ${if (table == "analytics_sessions_hourly") "sum($alias.pageviews)" else "count()"} AS pageviews
            FROM $table AS $alias
            WHERE $where AND name != ''
            GROUP BY name
            ORDER BY visitors DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()

        val rows = parseRows(ClickHouseClient.executeWithFormat(sql, "JSONEachRow")) { row ->
            BreakdownRow(
                name = row.stringValue("name"),
                visitors = row.longValue("visitors"),
                pageviews = row.longValue("pageviews"),
            )
        }
        return BreakdownResponse(rows)
    }

    // --- Pages breakdown (from events table for accurate path counts) ---

    suspend fun getPages(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        limit: Int = 100,
    ): BreakdownResponse = getBreakdown(projectId, dateFrom, dateTo, filters, "pathname", limit)

    suspend fun getEntryPages(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        limit: Int = 100,
    ): BreakdownResponse = getBreakdown(projectId, dateFrom, dateTo, filters, "entry_page", limit)

    suspend fun getExitPages(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        limit: Int = 100,
    ): BreakdownResponse = getBreakdown(projectId, dateFrom, dateTo, filters, "exit_page", limit)

    // --- Realtime ---

    fun getRealtime(projectId: Long): RealtimeResponse {
        val key = "${AnalyticsIngestionWorker.REALTIME_KEY_PREFIX}$projectId"
        val count = try {
            RedisConfig.sync().pfcount(key)
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }
            val errorMsg = e.toString().take(500)
            logger.debug { "Failed to read realtime counter: $errorMsg" }
            0L
        }
        return RealtimeResponse(count)
    }

    // --- Funnel ---

    suspend fun getFunnel(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        steps: List<String>,
    ): FunnelResponse {
        if (steps.size < 2) return FunnelResponse(emptyList())
        val dateWhere = dateRange(dateFrom, dateTo)

        // Build a windowFunnel query
        val events = steps.joinToString(", ") { "event_name = '${AnalyticsIngestionWorker.escapeCH(it)}'" }
        val sql = """
            SELECT
                level,
                count() AS cnt
            FROM (
                SELECT
                    session_id,
                    windowFunnel(86400)(timestamp, $events) AS level
                FROM analytics_events
                WHERE project_id = ${asClickHouseProjectId(projectId)} AND $dateWhere
                GROUP BY session_id
            )
            WHERE level > 0
            GROUP BY level
            ORDER BY level
            FORMAT JSONEachRow
        """.trimIndent()

        val levelCounts = mutableMapOf<Int, Long>()
        parseRows(ClickHouseClient.executeWithFormat(sql, "JSONEachRow")) { row ->
            val level = row.longValue("level").toInt()
            val cnt = row.longValue("cnt")
            levelCounts[level] = cnt
            level to cnt
        }

        // Build cumulative funnel: visitors at step N = sum of visitors at level >= N
        val funnelSteps = steps.mapIndexed { index, name ->
            val stepNumber = index + 1
            val visitors = levelCounts.filter { it.key >= stepNumber }.values.sum()
            FunnelStep(
                name = name,
                visitors = visitors,
                dropoff = 0.0,
            )
        }

        // Calculate dropoff rates
        val withDropoff = funnelSteps.mapIndexed { i, step ->
            if (i == 0) {
                step
            } else {
                val prev = funnelSteps[i - 1].visitors
                val dropoff = if (prev > 0) ((prev - step.visitors).toDouble() / prev * 100) else 0.0
                step.copy(dropoff = dropoff)
            }
        }
        return FunnelResponse(withDropoff)
    }

    // --- Events breakdown (custom events) ---

    suspend fun getEvents(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        limit: Int = 100,
    ): BreakdownResponse {
        val where = buildWhere(projectId, dateFrom, dateTo, filters, "e", "timestamp")
        val sql = """
            SELECT
                e.event_name AS name,
                uniq(e.session_id) AS visitors,
                count() AS pageviews
            FROM analytics_events AS e
            WHERE $where AND e.event_name != 'pageview'
            GROUP BY name
            ORDER BY visitors DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()

        val rows = parseRows(ClickHouseClient.executeWithFormat(sql, "JSONEachRow")) { row ->
            BreakdownRow(
                name = row.stringValue("name"),
                visitors = row.longValue("visitors"),
                pageviews = row.longValue("pageviews"),
            )
        }
        return BreakdownResponse(rows)
    }

    // --- Query helpers ---

    private fun buildWhere(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        alias: String,
        timeColumn: String,
    ): String {
        val parts = mutableListOf<String>()
        parts.add("$alias.project_id = ${asClickHouseProjectId(projectId)}")
        parts.add(dateRange(dateFrom, dateTo, alias, timeColumn))

        for (filter in filters) {
            val resolvedProperty = resolveFilterColumn(filter.property, alias) ?: continue
            val col = "$alias.${AnalyticsIngestionWorker.escapeCH(resolvedProperty)}"
            val value = AnalyticsIngestionWorker.escapeCH(filter.value)
            when (filter.operator) {
                "is" -> parts.add("$col = '$value'")
                "is_not" -> parts.add("$col != '$value'")
                "contains" -> parts.add("$col LIKE '%$value%'")
                "not_contains" -> parts.add("$col NOT LIKE '%$value%'")
            }
        }
        return parts.joinToString(" AND ")
    }

    private fun resolveFilterColumn(property: String, alias: String): String? {
        return when (property) {
            "page", "pathname" -> if (alias == "e") "pathname" else null
            "entry_page" -> if (alias == "s") "entry_page" else null
            "exit_page" -> if (alias == "s") "exit_page" else null
            "source", "referrer_source" -> "referrer_source"
            "country", "country_code" -> "country_code"
            "browser" -> "browser"
            "os" -> "os"
            "device", "device_type" -> "device_type"
            "utm_source" -> "utm_source"
            "utm_medium" -> "utm_medium"
            "utm_campaign" -> "utm_campaign"
            "utm_term" -> if (alias == "e") "utm_term" else null
            "utm_content" -> if (alias == "e") "utm_content" else null
            "event", "event_name" -> if (alias == "e") "event_name" else null
            else -> null
        }
    }

    private fun asClickHouseProjectId(projectId: Long): String {
        return "toUInt64($projectId)"
    }

    private fun dateRange(
        dateFrom: LocalDate,
        dateTo: LocalDate,
        alias: String = "",
        timeColumn: String = "timestamp",
    ): String {
        val prefix = if (alias.isNotEmpty()) "$alias." else ""
        val escapedTimeColumn = AnalyticsIngestionWorker.escapeCH(timeColumn)
        val from = dateFrom.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val to = dateTo.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        return "${prefix}$escapedTimeColumn >= '$from' AND ${prefix}$escapedTimeColumn < '$to'"
    }

    private data class DimensionInfo(val column: String, val table: String, val alias: String)

    private fun resolveDimension(dimension: String): DimensionInfo {
        return when (dimension) {
            "pathname" -> DimensionInfo("e.pathname", "analytics_events", "e")
            "entry_page" -> DimensionInfo("s.entry_page", "analytics_sessions_hourly", "s")
            "exit_page" -> DimensionInfo("s.exit_page", "analytics_sessions_hourly", "s")
            "referrer_source" -> DimensionInfo("s.referrer_source", "analytics_sessions_hourly", "s")
            "utm_source" -> DimensionInfo("s.utm_source", "analytics_sessions_hourly", "s")
            "utm_medium" -> DimensionInfo("s.utm_medium", "analytics_sessions_hourly", "s")
            "utm_campaign" -> DimensionInfo("s.utm_campaign", "analytics_sessions_hourly", "s")
            "utm_term" -> DimensionInfo("e.utm_term", "analytics_events", "e")
            "utm_content" -> DimensionInfo("e.utm_content", "analytics_events", "e")
            "country_code" -> DimensionInfo("s.country_code", "analytics_sessions_hourly", "s")
            "browser" -> DimensionInfo("s.browser", "analytics_sessions_hourly", "s")
            "os" -> DimensionInfo("s.os", "analytics_sessions_hourly", "s")
            "device_type" -> DimensionInfo("s.device_type", "analytics_sessions_hourly", "s")
            else -> DimensionInfo("e.$dimension", "analytics_events", "e")
        }
    }

    private fun <T> parseRows(
        body: String,
        mapper: (kotlinx.serialization.json.JsonObject) -> T,
    ): List<T> {
        if (body.isBlank()) return emptyList()
        return body.trim().lines().mapNotNull { line ->
            try {
                val row = jsonParser.parseToJsonElement(line).jsonObject
                mapper(row)
            } catch (e: SerializationException) {
                logger.debug { "Failed to parse row: ${e.message}" }
                null
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObject.longValue(key: String): Long {
        return this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
    }

    private fun kotlinx.serialization.json.JsonObject.doubleValue(key: String): Double {
        return this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
    }

    private fun kotlinx.serialization.json.JsonObject.stringValue(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull ?: ""
    }

    private data class OverviewMetrics(
        val visitors: Long = 0,
        val pageviews: Long = 0,
        val bounceRate: Double = 0.0,
        val avgVisitDuration: Double = 0.0,
        val viewsPerVisit: Double = 0.0,
    )
}