// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.analytics.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.enterprise.analytics.models.AnalyticsFilter
import com.moneat.enterprise.analytics.models.AnalyticsOverviewResponse
import com.moneat.enterprise.analytics.models.BreakdownResponse
import com.moneat.enterprise.analytics.models.BreakdownRow
import com.moneat.enterprise.analytics.models.FunnelResponse
import com.moneat.enterprise.analytics.models.FunnelStep
import com.moneat.enterprise.analytics.models.RealtimeResponse
import com.moneat.enterprise.analytics.models.TimeseriesPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
        val where = buildWhere(projectId, dateFrom, dateTo, filters, "s")
        val sql = """
            SELECT
                uniq(s.session_id) AS visitors,
                sum(s.pageviews) AS pageviews,
                avg(s.is_bounce) * 100 AS bounce_rate,
                avg(dateDiff('second', s.started, s.ended)) AS avg_visit_duration,
                if(uniq(s.session_id) > 0, sum(s.pageviews) / uniq(s.session_id), 0) AS views_per_visit
            FROM analytics_sessions_hourly AS s
            WHERE $where
            FORMAT JSONEachRow
        """.trimIndent()

        val body = ClickHouseClient.executeWithFormat(sql, "JSONEachRow")
        if (body.isBlank()) return OverviewMetrics()

        val row = jsonParser.parseToJsonElement(body.trim().lines().first()).jsonObject
        return OverviewMetrics(
            visitors = row["visitors"]?.jsonPrimitive?.long ?: 0,
            pageviews = row["pageviews"]?.jsonPrimitive?.long ?: 0,
            bounceRate = row["bounce_rate"]?.jsonPrimitive?.double ?: 0.0,
            avgVisitDuration = row["avg_visit_duration"]?.jsonPrimitive?.double ?: 0.0,
            viewsPerVisit = row["views_per_visit"]?.jsonPrimitive?.double ?: 0.0,
        )
    }

    // --- Timeseries ---

    suspend fun getTimeseries(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): List<TimeseriesPoint> {
        val where = buildWhere(projectId, dateFrom, dateTo, filters, "s")
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
                date = row["date"]?.jsonPrimitive?.content ?: "",
                visitors = row["visitors"]?.jsonPrimitive?.long ?: 0,
                pageviews = row["pageviews"]?.jsonPrimitive?.long ?: 0,
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
        val where = buildWhere(projectId, dateFrom, dateTo, filters, alias)

        val sql = """
            SELECT
                $column AS name,
                uniq(${alias}.session_id) AS visitors,
                ${if (table == "analytics_sessions_hourly") "sum(${alias}.pageviews)" else "count()"} AS pageviews
            FROM $table AS $alias
            WHERE $where AND name != ''
            GROUP BY name
            ORDER BY visitors DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()

        val rows = parseRows(ClickHouseClient.executeWithFormat(sql, "JSONEachRow")) { row ->
            BreakdownRow(
                name = row["name"]?.jsonPrimitive?.content ?: "",
                visitors = row["visitors"]?.jsonPrimitive?.long ?: 0,
                pageviews = row["pageviews"]?.jsonPrimitive?.long ?: 0,
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
            logger.debug { "Failed to read realtime counter: ${e.message}" }
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
                WHERE project_id = $projectId AND $dateWhere
                GROUP BY session_id
            )
            WHERE level > 0
            GROUP BY level
            ORDER BY level
            FORMAT JSONEachRow
        """.trimIndent()

        val levelCounts = mutableMapOf<Int, Long>()
        parseRows(ClickHouseClient.executeWithFormat(sql, "JSONEachRow")) { row ->
            val level = row["level"]?.jsonPrimitive?.long?.toInt() ?: 0
            val cnt = row["cnt"]?.jsonPrimitive?.long ?: 0
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
        val where = buildWhere(projectId, dateFrom, dateTo, filters, "e")
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
                name = row["name"]?.jsonPrimitive?.content ?: "",
                visitors = row["visitors"]?.jsonPrimitive?.long ?: 0,
                pageviews = row["pageviews"]?.jsonPrimitive?.long ?: 0,
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
    ): String {
        val parts = mutableListOf<String>()
        parts.add("$alias.project_id = $projectId")
        parts.add(dateRange(dateFrom, dateTo, alias))

        for (filter in filters) {
            val col = "$alias.${AnalyticsIngestionWorker.escapeCH(filter.property)}"
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

    private fun dateRange(dateFrom: LocalDate, dateTo: LocalDate, alias: String = ""): String {
        val prefix = if (alias.isNotEmpty()) "$alias." else ""
        val from = dateFrom.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val to = dateTo.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        return "${prefix}timestamp >= '$from' AND ${prefix}timestamp < '$to'"
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
            } catch (e: Exception) {
                logger.debug { "Failed to parse row: ${e.message}" }
                null
            }
        }
    }

    private data class OverviewMetrics(
        val visitors: Long = 0,
        val pageviews: Long = 0,
        val bounceRate: Double = 0.0,
        val avgVisitDuration: Double = 0.0,
        val viewsPerVisit: Double = 0.0,
    )
}
