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
import com.moneat.analytics.models.EventPropertyFilter
import com.moneat.analytics.models.AnalyticsOverviewResponse
import com.moneat.analytics.models.BreakdownResponse
import com.moneat.analytics.models.BreakdownRow
import com.moneat.analytics.models.FunnelResponse
import com.moneat.analytics.models.FunnelStep
import com.moneat.analytics.models.ProductActivityPoint
import com.moneat.analytics.models.ProductActivityResponse
import com.moneat.analytics.models.ProductActivitySeries
import com.moneat.analytics.models.ProductAnalyticsSummary
import com.moneat.analytics.models.ProductFeatureAdoptionItem
import com.moneat.analytics.models.ProductKpiMetric
import com.moneat.analytics.models.ProductMover
import com.moneat.analytics.models.ProductRetentionCohortRow
import com.moneat.analytics.models.ProductRetentionGrid
import com.moneat.analytics.models.ProductSegmentRow
import com.moneat.analytics.models.ProductSegmentation
import com.moneat.analytics.models.RealtimeResponse
import com.moneat.analytics.models.RetentionCohort
import com.moneat.analytics.models.RetentionPeriod
import com.moneat.analytics.models.RetentionResponse
import com.moneat.analytics.models.TimeseriesPoint
import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.utils.suspendRunCatching
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import kotlin.math.abs
import kotlin.math.roundToInt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}
private val jsonParser = Json { ignoreUnknownKeys = true }

private const val ERROR_TRUNCATE_LENGTH = 500
private const val PERCENTAGE_MULTIPLIER = 100
private const val DEFAULT_LIMIT = 100
private const val FUNNEL_WINDOW_SECONDS = 86400
private const val PRODUCT_SOURCE = "server"
private const val PRODUCT_SIGNUP_EVENT = "signup_completed"
private const val PRODUCT_ONBOARDING_EVENT = "onboarding_completed"
private const val PRODUCT_KEY_ACTION_EVENT = "first_key_action"
private const val PRODUCT_ACTIVATED_EVENT = "activated"
private const val PRODUCT_WEEK_DAYS = 7L
private const val PRODUCT_MONTH_DAYS = 29L
private const val PRODUCT_WEEK_OFFSET_DAYS = 6L
private const val PRODUCT_POWER_USER_KEY_ACTIONS = 5L
private const val PRODUCT_MOVER_LIMIT = 6
private const val PRODUCT_FEATURE_LIMIT = 8
private const val PRODUCT_SEGMENT_LIMIT = 8
private const val PRODUCT_RETENTION_DAYS_PER_PERIOD = 7
private const val POSITIVE_CHANGE_PREFIX = "+"

data class ProductRetentionRequest(
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val filters: List<AnalyticsFilter>,
    val mode: String,
    val customEvent: String?,
    val periodCount: Int,
)

data class AnalyticsFunnelQuery(
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val steps: List<String>,
    val groupBy: String = "session_id",
    val source: String? = null,
    val filters: List<AnalyticsFilter> = emptyList(),
    val propFilters: List<EventPropertyFilter> = emptyList(),
)

data class AnalyticsEventsQuery(
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val filters: List<AnalyticsFilter>,
    val limit: Int = DEFAULT_LIMIT,
    val groupBy: String = "session_id",
    val source: String? = null,
    val propFilters: List<EventPropertyFilter> = emptyList(),
)

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
    ): AnalyticsOverviewResponse =
        getOverview(AnalyticsQueryScope.service(projectId), dateFrom, dateTo, filters, compFrom, compTo)

    suspend fun getOverview(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        compFrom: LocalDate?,
        compTo: LocalDate?,
    ): AnalyticsOverviewResponse {
        val main = queryOverviewMetrics(scope, dateFrom, dateTo, filters)

        val comp = if (compFrom != null && compTo != null) {
            queryOverviewMetrics(scope, compFrom, compTo, filters)
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
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): OverviewMetrics {
        val where = buildWhere(scope, dateFrom, dateTo, filters, "s", "hour")
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
                    project_id,
                    session_id,
                    sum(pageviews) AS total_pageviews,
                    dateDiff('second', min(started), max(ended)) AS total_duration_sec
                FROM analytics_sessions_hourly AS s
                WHERE $where
                GROUP BY project_id, session_id
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
    ): List<TimeseriesPoint> =
        getTimeseries(AnalyticsQueryScope.service(projectId), dateFrom, dateTo, filters)

    suspend fun getTimeseries(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): List<TimeseriesPoint> {
        val where = buildWhere(scope, dateFrom, dateTo, filters, "s", "hour")
        val interval = if (dateTo.toEpochDay() - dateFrom.toEpochDay() <= 2) "toStartOfHour" else "toDate"

        val sql = """
            SELECT
                $interval(s.hour) AS date,
                uniq(s.project_id, s.session_id) AS visitors,
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
        limit: Int = DEFAULT_LIMIT,
    ): BreakdownResponse =
        getBreakdown(AnalyticsQueryScope.service(projectId), dateFrom, dateTo, filters, dimension, limit)

    suspend fun getBreakdown(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        dimension: String,
        limit: Int = DEFAULT_LIMIT,
    ): BreakdownResponse {
        val (column, table, alias) = resolveDimension(dimension)
        val timeColumn = if (alias == "s") "hour" else "timestamp"
        val where = buildWhere(scope, dateFrom, dateTo, filters, alias, timeColumn)

        val sql = """
            SELECT
                $column AS name,
                uniq($alias.project_id, $alias.session_id) AS visitors,
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
        limit: Int = DEFAULT_LIMIT,
    ): BreakdownResponse = getPages(AnalyticsQueryScope.service(projectId), dateFrom, dateTo, filters, limit)

    suspend fun getPages(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        limit: Int = DEFAULT_LIMIT,
    ): BreakdownResponse = getBreakdown(scope, dateFrom, dateTo, filters, "pathname", limit)

    suspend fun getEntryPages(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        limit: Int = DEFAULT_LIMIT,
    ): BreakdownResponse = getEntryPages(AnalyticsQueryScope.service(projectId), dateFrom, dateTo, filters, limit)

    suspend fun getEntryPages(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        limit: Int = DEFAULT_LIMIT,
    ): BreakdownResponse = getBreakdown(scope, dateFrom, dateTo, filters, "entry_page", limit)

    suspend fun getExitPages(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        limit: Int = DEFAULT_LIMIT,
    ): BreakdownResponse = getExitPages(AnalyticsQueryScope.service(projectId), dateFrom, dateTo, filters, limit)

    suspend fun getExitPages(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        limit: Int = DEFAULT_LIMIT,
    ): BreakdownResponse = getBreakdown(scope, dateFrom, dateTo, filters, "exit_page", limit)

    // --- Realtime ---

    fun getRealtime(projectId: Long): RealtimeResponse =
        getRealtime(AnalyticsQueryScope.service(projectId))

    fun getRealtime(scope: AnalyticsQueryScope): RealtimeResponse {
        val keys = scope.serviceIds.map { serviceId ->
            "${AnalyticsIngestionWorker.REALTIME_KEY_PREFIX}$serviceId"
        }
        if (keys.isEmpty()) return RealtimeResponse(0L)

        val count = suspendRunCatching {
            RedisConfig.sync().pfcount(*keys.toTypedArray())
        }.getOrElse { e ->
            val errorMsg = e.toString().take(ERROR_TRUNCATE_LENGTH)
            logger.debug { "Failed to read realtime counter: $errorMsg" }
            0L
        }
        return RealtimeResponse(count)
    }

    // --- Funnel ---

    suspend fun getFunnel(
        projectId: Long,
        query: AnalyticsFunnelQuery,
    ): FunnelResponse =
        getFunnel(AnalyticsQueryScope.service(projectId), query)

    suspend fun getFunnel(
        scope: AnalyticsQueryScope,
        query: AnalyticsFunnelQuery,
    ): FunnelResponse {
        val dateFrom = query.dateFrom
        val dateTo = query.dateTo
        val steps = query.steps
        val groupBy = query.groupBy
        val source = query.source
        val filters = query.filters
        val propFilters = query.propFilters
        if (steps.size < 2) return FunnelResponse(emptyList(), 0.0)
        val where = buildWhere(scope, dateFrom, dateTo, filters, "e", "timestamp", propFilters)
        val groupByColumn = resolveGroupByColumn(groupBy)
        val sourceClause = sourceWhere(source, "e")
        val nonEmptyGroupClause = if (groupByColumn == "user_id") "AND e.user_id != ''" else ""

        // Build a windowFunnel query
        val events = steps.joinToString(", ") { "e.event_name = '${AnalyticsIngestionWorker.escapeCH(it)}'" }
        val sql = """
            SELECT
                level,
                count() AS cnt
            FROM (
                SELECT
                    e.project_id,
                    e.$groupByColumn,
                    windowFunnel($FUNNEL_WINDOW_SECONDS)(toDateTime(e.timestamp), $events) AS level
                FROM analytics_events AS e
                WHERE $where
                  $sourceClause
                  $nonEmptyGroupClause
                GROUP BY e.project_id, e.$groupByColumn
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
                conversionRate = 0.0,
            )
        }

        // Calculate dropoff and conversion rates
        val firstStepVisitors = funnelSteps.firstOrNull()?.visitors ?: 0
        val withMetrics = funnelSteps.mapIndexed { i, step ->
            val conversionRate = percentage(step.visitors, firstStepVisitors)
            if (i == 0) {
                step.copy(conversionRate = conversionRate)
            } else {
                val prev = funnelSteps[i - 1].visitors
                val dropoff = percentage(prev - step.visitors, prev)
                step.copy(dropoff = dropoff, conversionRate = conversionRate)
            }
        }
        val overallConversion = percentage(funnelSteps.lastOrNull()?.visitors ?: 0, firstStepVisitors)
        return FunnelResponse(withMetrics, overallConversion)
    }

    suspend fun getRetention(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        startEvent: String,
        returnEvent: String,
        periods: List<Int>,
    ): RetentionResponse =
        getRetention(
            AnalyticsQueryScope.service(projectId),
            dateFrom,
            dateTo,
            startEvent,
            returnEvent,
            periods
        )

    suspend fun getRetention(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        startEvent: String,
        returnEvent: String,
        periods: List<Int>,
    ): RetentionResponse {
        if (startEvent.isBlank() || returnEvent.isBlank() || periods.isEmpty()) {
            return RetentionResponse(startEvent, returnEvent, emptyList())
        }

        val scopeWhere = serviceScopeWhere(scope, "e")
        val dateWhere = dateRange(dateFrom, dateTo, "e", "timestamp")
        val escapedStartEvent = AnalyticsIngestionWorker.escapeCH(startEvent)
        val escapedReturnEvent = AnalyticsIngestionWorker.escapeCH(returnEvent)
        val maxPeriod = periods.maxOrNull() ?: 0
        val retentionColumns = periods.joinToString(",\n") { period ->
            val cohortIsMature = "c.first_seen + INTERVAL $period DAY <= now()"
            "                uniqExactIf(c.user_id, $cohortIsMature) AS eligible_$period,\n" +
                "                uniqExactIf(c.user_id, $cohortIsMature " +
                "AND e.timestamp > c.first_seen " +
                "AND e.timestamp <= c.first_seen + INTERVAL $period DAY) AS retained_$period"
        }

        val sql = """
            WITH cohorts AS (
                SELECT
                    project_id,
                    user_id,
                    min(timestamp) AS first_seen,
                    toStartOfWeek(min(timestamp)) AS cohort_week
                FROM analytics_events AS e
                WHERE $scopeWhere
                  AND e.source = 'server'
                  AND e.event_name = '$escapedStartEvent'
                  AND e.user_id != ''
                  AND $dateWhere
                GROUP BY project_id, user_id
            )
            SELECT
                toString(c.cohort_week) AS cohort_week,
                uniqExact(c.user_id) AS users,
$retentionColumns
            FROM cohorts AS c
            LEFT JOIN analytics_events AS e
                ON e.project_id = c.project_id
                AND e.source = 'server'
                AND e.user_id = c.user_id
                AND e.event_name = '$escapedReturnEvent'
                AND e.timestamp > c.first_seen
                AND e.timestamp <= c.first_seen + INTERVAL $maxPeriod DAY
            GROUP BY c.cohort_week
            ORDER BY c.cohort_week
            FORMAT JSONEachRow
        """.trimIndent()

        val cohorts = parseRows(ClickHouseClient.executeWithFormat(sql, "JSONEachRow")) { row ->
            val users = row.longValue("users")
            RetentionCohort(
                cohortWeek = row.stringValue("cohort_week"),
                users = users,
                periods = periods.map { period ->
                    val eligibleUsers = row.longValue("eligible_$period")
                    val retainedUsers = row.longValue("retained_$period")
                    RetentionPeriod(
                        days = period,
                        retainedUsers = retainedUsers,
                        eligibleUsers = eligibleUsers,
                        retentionRate = percentage(retainedUsers, eligibleUsers),
                    )
                },
            )
        }
        return RetentionResponse(startEvent, returnEvent, cohorts)
    }

    // --- Events breakdown (custom events) ---

    suspend fun getEvents(
        projectId: Long,
        query: AnalyticsEventsQuery,
    ): BreakdownResponse =
        getEvents(AnalyticsQueryScope.service(projectId), query)

    suspend fun getEvents(
        scope: AnalyticsQueryScope,
        query: AnalyticsEventsQuery,
    ): BreakdownResponse {
        val dateFrom = query.dateFrom
        val dateTo = query.dateTo
        val filters = query.filters
        val limit = query.limit
        val groupBy = query.groupBy
        val source = query.source
        val propFilters = query.propFilters
        val where = buildWhere(scope, dateFrom, dateTo, filters, "e", "timestamp", propFilters)
        val groupByColumn = resolveGroupByColumn(groupBy)
        val sourceClause = sourceWhere(source, "e")
        val nonEmptyGroupClause = if (groupByColumn == "user_id") "AND e.user_id != ''" else ""
        val sql = """
            SELECT
                e.event_name AS name,
                uniq(e.project_id, e.$groupByColumn) AS visitors,
                count() AS pageviews
            FROM analytics_events AS e
            WHERE $where
              AND e.event_name != 'pageview'
              $sourceClause
              $nonEmptyGroupClause
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

    // --- Product analytics ---

    suspend fun getProductAnalyticsSummary(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): ProductAnalyticsSummary =
        getProductAnalyticsSummary(AnalyticsQueryScope.service(projectId), dateFrom, dateTo, filters)

    suspend fun getProductAnalyticsSummary(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): ProductAnalyticsSummary {
        val current = queryProductSummaryMetrics(scope, dateFrom, dateTo, filters)
        val previousRange = previousRange(dateFrom, dateTo)
        val previous = queryProductSummaryMetrics(scope, previousRange.dateFrom, previousRange.dateTo, filters)
        val currentWeek1Retention = queryProductWeek1Retention(scope, dateFrom, dateTo, filters)
        val previousWeek1Retention = queryProductWeek1Retention(
            scope,
            previousRange.dateFrom,
            previousRange.dateTo,
            filters
        )
        val spark = productSpark(queryProductDailyMetrics(scope, dateFrom, dateTo, filters))

        return ProductAnalyticsSummary(
            weeklyActiveUsers = countMetric(current.weeklyActiveUsers, previous.weeklyActiveUsers, spark.activeUsers),
            dailyActiveUsers = current.dailyActiveUsers,
            newUsers = countMetric(current.newUsers, previous.newUsers, spark.newUsers),
            activationRate = rateMetric(
                percentage(current.activatedNewUsers, current.newUsers),
                percentage(previous.activatedNewUsers, previous.newUsers),
                spark.activationRates,
            ),
            stickiness = rateMetric(
                percentage(current.dailyActiveUsers, current.monthlyActiveUsers),
                percentage(previous.dailyActiveUsers, previous.monthlyActiveUsers),
                spark.stickinessRates,
            ),
            week1Retention = rateMetric(currentWeek1Retention, previousWeek1Retention, emptyList()),
            powerUsers = rateMetric(
                percentage(current.powerUsers, current.weeklyActiveUsers),
                percentage(previous.powerUsers, previous.weeklyActiveUsers),
                spark.powerUserRates,
            ),
        )
    }

    suspend fun getProductActivity(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): ProductActivityResponse =
        getProductActivity(AnalyticsQueryScope.service(projectId), dateFrom, dateTo, filters)

    suspend fun getProductActivity(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): ProductActivityResponse {
        val currentRange = ProductDateRange(dateFrom, dateTo)
        val previousRange = previousRange(dateFrom, dateTo)
        val current = queryProductDailyMetrics(scope, currentRange.dateFrom, currentRange.dateTo, filters)
        val previous = queryProductDailyMetrics(scope, previousRange.dateFrom, previousRange.dateTo, filters)
        val previousDates = dateBuckets(previousRange)
        val previousByOffset = previousDates.mapIndexedNotNull { index, date ->
            previous[date]?.let { index to it }
        }.toMap()
        val currentDates = dateBuckets(currentRange)

        return ProductActivityResponse(
            series = listOf(
                productActivitySeries("active", currentDates, current, previousByOffset) { it.activeUsers },
                productActivitySeries("new", currentDates, current, previousByOffset) { it.newUsers },
                productActivitySeries("key_action", currentDates, current, previousByOffset) { it.keyActionUsers },
            ),
        )
    }

    suspend fun getProductMovers(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): List<ProductMover> =
        getProductMovers(AnalyticsQueryScope.service(projectId), dateFrom, dateTo, filters)

    suspend fun getProductMovers(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): List<ProductMover> {
        val previousRange = previousRange(dateFrom, dateTo)
        val where = productWhere(scope, previousRange.dateFrom, dateTo, filters, "e")
        val currentDate = dateRange(dateFrom, dateTo, "e", "timestamp")
        val previousDate = dateRange(previousRange.dateFrom, previousRange.dateTo, "e", "timestamp")
        val sql = """
            SELECT
                e.event_name AS name,
                countIf($currentDate) AS current_count,
                countIf($previousDate) AS previous_count
            FROM analytics_events AS e
            WHERE $where
              AND e.event_name != ''
              AND e.event_name != 'pageview'
            GROUP BY name
            HAVING current_count > 0 OR previous_count > 0
            ORDER BY abs(current_count - previous_count) DESC
            LIMIT $PRODUCT_MOVER_LIMIT
            FORMAT JSONEachRow
        """.trimIndent()

        return parseRows(ClickHouseClient.executeWithFormat(sql, "JSONEachRow")) { row ->
            ProductMoverCounts(
                name = row.stringValue("name"),
                current = row.longValue("current_count"),
                previous = row.longValue("previous_count"),
            )
        }.filter { it.current != it.previous }
            .map { counts ->
                ProductMover(
                    name = counts.name,
                    category = "event",
                    detail = "${counts.current} this period",
                    change = changeLabel(counts.current, counts.previous),
                    tone = if (counts.current >= counts.previous) "good" else "bad",
                )
            }
    }

    suspend fun getProductFeatureAdoption(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): List<ProductFeatureAdoptionItem> =
        getProductFeatureAdoption(AnalyticsQueryScope.service(projectId), dateFrom, dateTo, filters)

    suspend fun getProductFeatureAdoption(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): List<ProductFeatureAdoptionItem> {
        val activeUsers = queryProductSummaryMetrics(scope, dateFrom, dateTo, filters).activeUsers
        if (activeUsers == 0L) return emptyList()

        val where = productWhere(scope, dateFrom, dateTo, filters, "e")
        val featureExpr = "if(mapContains(e.props, 'feature') AND e.props['feature'] != '', " +
            "e.props['feature'], e.event_name)"
        val lifecycleEvents = productLifecycleEventList()
        val sql = """
            SELECT
                feature AS name,
                uniqExact(user_id) AS users
            FROM (
                SELECT
                    e.user_id AS user_id,
                    $featureExpr AS feature
                FROM analytics_events AS e
                WHERE $where
                  AND e.event_name != 'pageview'
                  AND (
                    (mapContains(e.props, 'feature') AND e.props['feature'] != '')
                    OR e.event_name NOT IN ($lifecycleEvents)
                  )
            )
            WHERE feature != ''
            GROUP BY feature
            ORDER BY users DESC
            LIMIT $PRODUCT_FEATURE_LIMIT
            FORMAT JSONEachRow
        """.trimIndent()

        return parseRows(ClickHouseClient.executeWithFormat(sql, "JSONEachRow")) { row ->
            ProductFeatureUsers(
                name = row.stringValue("name"),
                users = row.longValue("users"),
            )
        }.map { feature ->
            ProductFeatureAdoptionItem(
                name = feature.name,
                adoptionRate = percentage(feature.users, activeUsers),
            )
        }
    }

    suspend fun getProductSegmentation(
        projectId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): ProductSegmentation =
        getProductSegmentation(AnalyticsQueryScope.service(projectId), dateFrom, dateTo, filters)

    suspend fun getProductSegmentation(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): ProductSegmentation =
        ProductSegmentation(
            plan = queryProductSegment(scope, dateFrom, dateTo, filters, ProductSegmentDimension.PLAN),
            platform = queryProductSegment(scope, dateFrom, dateTo, filters, ProductSegmentDimension.PLATFORM),
            country = queryProductSegment(scope, dateFrom, dateTo, filters, ProductSegmentDimension.COUNTRY),
        )

    suspend fun getProductRetention(
        projectId: Long,
        request: ProductRetentionRequest,
    ): ProductRetentionGrid =
        getProductRetention(AnalyticsQueryScope.service(projectId), request)

    suspend fun getProductRetention(
        scope: AnalyticsQueryScope,
        request: ProductRetentionRequest,
    ): ProductRetentionGrid {
        val periods = (0 until request.periodCount).toList()
        val where = productWhere(scope, request.dateFrom, request.dateTo, request.filters, "e")
        val retentionColumns = periods.drop(1).joinToString(",\n") { period ->
            val periodStartDays = period * PRODUCT_RETENTION_DAYS_PER_PERIOD
            val periodEndDays = (period + 1) * PRODUCT_RETENTION_DAYS_PER_PERIOD
            val mature = "c.first_seen + INTERVAL $periodEndDays DAY <= '${endExclusive(request.dateTo)}'"
            "                uniqExactIf(c.user_id, $mature) AS eligible_$period,\n" +
                "                uniqExactIf(c.user_id, $mature AND " +
                "e.timestamp >= c.first_seen + INTERVAL $periodStartDays DAY AND " +
                "e.timestamp < c.first_seen + INTERVAL $periodEndDays DAY AND " +
                "${productRetentionEventCondition(request.mode, request.customEvent, "e")}) AS retained_$period"
        }
        val extraColumns = if (retentionColumns.isBlank()) "" else ",\n$retentionColumns"
        val maxRetentionDays = request.periodCount * PRODUCT_RETENTION_DAYS_PER_PERIOD
        val sql = """
            WITH cohorts AS (
                SELECT
                    e.project_id AS project_id,
                    e.user_id AS user_id,
                    min(e.timestamp) AS first_seen,
                    toStartOfWeek(min(e.timestamp)) AS cohort_week
                FROM analytics_events AS e
                WHERE $where
                  AND e.event_name = '$PRODUCT_SIGNUP_EVENT'
                GROUP BY e.project_id, e.user_id
            )
            SELECT
                toString(c.cohort_week) AS cohort,
                uniqExact(c.user_id) AS users$extraColumns
            FROM cohorts AS c
            LEFT JOIN analytics_events AS e
                ON e.project_id = c.project_id
                AND e.source = '$PRODUCT_SOURCE'
                AND e.user_id = c.user_id
                AND e.timestamp > c.first_seen
                AND e.timestamp < '${endExclusive(request.dateTo)}'
                AND e.timestamp <= c.first_seen + INTERVAL $maxRetentionDays DAY
            GROUP BY c.cohort_week
            ORDER BY c.cohort_week
            FORMAT JSONEachRow
        """.trimIndent()

        val cohorts = parseRows(ClickHouseClient.executeWithFormat(sql, "JSONEachRow")) { row ->
            ProductRetentionCohortRow(
                cohort = row.stringValue("cohort"),
                users = row.longValue("users"),
                values = periods.map { period ->
                    if (period == 0) {
                        PERCENTAGE_MULTIPLIER.toDouble()
                    } else {
                        val eligible = row.longValue("eligible_$period")
                        if (eligible == 0L) null else percentage(row.longValue("retained_$period"), eligible)
                    }
                },
            )
        }
        return ProductRetentionGrid(request.mode, periods, cohorts)
    }

    private suspend fun queryProductSummaryMetrics(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): ProductSummaryMetrics {
        val selectedStart = dateFrom.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val selectedEnd = endExclusive(dateTo)
        val dayStart = dateTo.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weekStart = dateTo.minusDays(PRODUCT_WEEK_OFFSET_DAYS).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val monthStart = dateTo.minusDays(PRODUCT_MONTH_DAYS).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val selectedWindow = "e.timestamp >= '$selectedStart' AND e.timestamp < '$selectedEnd'"
        val dayWindow = "e.timestamp >= '$dayStart' AND e.timestamp < '$selectedEnd'"
        val weekWindow = "e.timestamp >= '$weekStart' AND e.timestamp < '$selectedEnd'"
        val monthWindow = "e.timestamp >= '$monthStart' AND e.timestamp < '$selectedEnd'"
        val where = productWhere(scope, productLookbackStart(dateFrom, dateTo), dateTo, filters, "e")
        val sql = """
            SELECT
                countIf(selected_event_count > 0) AS active_users,
                countIf(week_event_count > 0) AS weekly_active_users,
                countIf(day_event_count > 0) AS daily_active_users,
                countIf(month_event_count > 0) AS monthly_active_users,
                countIf(selected_signup_count > 0) AS new_users,
                countIf(selected_signup_count > 0 AND selected_activated_count > 0) AS activated_new_users,
                countIf(selected_key_action_count >= $PRODUCT_POWER_USER_KEY_ACTIONS) AS power_users
            FROM (
                SELECT
                    e.project_id,
                    e.user_id,
                    countIf($selectedWindow) AS selected_event_count,
                    countIf($weekWindow) AS week_event_count,
                    countIf($dayWindow) AS day_event_count,
                    countIf($monthWindow) AS month_event_count,
                    countIf($selectedWindow AND e.event_name = '$PRODUCT_SIGNUP_EVENT') AS selected_signup_count,
                    countIf($selectedWindow AND e.event_name = '$PRODUCT_ACTIVATED_EVENT') AS selected_activated_count,
                    countIf($selectedWindow AND ${keyActionEventCondition("e")}) AS selected_key_action_count
                FROM analytics_events AS e
                WHERE $where
                GROUP BY e.project_id, e.user_id
            )
            FORMAT JSONEachRow
        """.trimIndent()

        val body = ClickHouseClient.executeWithFormat(sql, "JSONEachRow")
        if (body.isBlank()) return ProductSummaryMetrics()
        val row = jsonParser.parseToJsonElement(body.trim().lines().first()).jsonObject
        return ProductSummaryMetrics(
            activeUsers = row.longValue("active_users"),
            weeklyActiveUsers = row.longValue("weekly_active_users"),
            dailyActiveUsers = row.longValue("daily_active_users"),
            monthlyActiveUsers = row.longValue("monthly_active_users"),
            newUsers = row.longValue("new_users"),
            activatedNewUsers = row.longValue("activated_new_users"),
            powerUsers = row.longValue("power_users"),
        )
    }

    private suspend fun queryProductWeek1Retention(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): Double {
        val where = productWhere(scope, dateFrom, dateTo, filters, "e")
        val sql = """
            WITH cohorts AS (
                SELECT
                    e.project_id AS project_id,
                    e.user_id AS user_id,
                    min(e.timestamp) AS first_seen
                FROM analytics_events AS e
                WHERE $where
                  AND e.event_name = '$PRODUCT_SIGNUP_EVENT'
                GROUP BY e.project_id, e.user_id
            )
            SELECT
                uniqExactIf(c.user_id, c.first_seen + INTERVAL $PRODUCT_WEEK_DAYS DAY <= '${endExclusive(dateTo)}')
                    AS eligible_users,
                uniqExactIf(
                    c.user_id,
                    c.first_seen + INTERVAL $PRODUCT_WEEK_DAYS DAY <= '${endExclusive(dateTo)}'
                    AND e.timestamp > c.first_seen
                    AND e.timestamp <= c.first_seen + INTERVAL $PRODUCT_WEEK_DAYS DAY
                    AND ${keyActionEventCondition("e")}
                ) AS retained_users
            FROM cohorts AS c
            LEFT JOIN analytics_events AS e
                ON e.project_id = c.project_id
                AND e.source = '$PRODUCT_SOURCE'
                AND e.user_id = c.user_id
                AND e.timestamp > c.first_seen
                AND e.timestamp <= c.first_seen + INTERVAL $PRODUCT_WEEK_DAYS DAY
            FORMAT JSONEachRow
        """.trimIndent()

        val body = ClickHouseClient.executeWithFormat(sql, "JSONEachRow")
        if (body.isBlank()) return 0.0
        val row = jsonParser.parseToJsonElement(body.trim().lines().first()).jsonObject
        return percentage(row.longValue("retained_users"), row.longValue("eligible_users"))
    }

    private suspend fun queryProductDailyMetrics(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
    ): Map<LocalDate, ProductDailyMetrics> {
        val where = productWhere(scope, dateFrom, dateTo, filters, "e")
        val sql = """
            SELECT
                toString(toDate(e.timestamp)) AS day,
                uniqExact(e.user_id) AS active_users,
                uniqExactIf(e.user_id, e.event_name = '$PRODUCT_SIGNUP_EVENT') AS new_users,
                uniqExactIf(e.user_id, e.event_name = '$PRODUCT_ACTIVATED_EVENT') AS activated_users,
                uniqExactIf(e.user_id, ${keyActionEventCondition("e")}) AS key_action_users
            FROM analytics_events AS e
            WHERE $where
            GROUP BY day
            ORDER BY day
            FORMAT JSONEachRow
        """.trimIndent()

        return parseRows(ClickHouseClient.executeWithFormat(sql, "JSONEachRow")) { row ->
            ProductDailyMetrics(
                day = LocalDate.parse(row.stringValue("day")),
                activeUsers = row.longValue("active_users"),
                newUsers = row.longValue("new_users"),
                activatedUsers = row.longValue("activated_users"),
                keyActionUsers = row.longValue("key_action_users"),
            )
        }.associateBy { it.day }
    }

    private suspend fun queryProductSegment(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        dimension: ProductSegmentDimension,
    ): List<ProductSegmentRow> {
        val selectedStart = dateFrom.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val selectedEnd = endExclusive(dateTo)
        val dayStart = dateTo.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val monthStart = dateTo.minusDays(PRODUCT_MONTH_DAYS).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val selectedWindow = "e.timestamp >= '$selectedStart' AND e.timestamp < '$selectedEnd'"
        val dayWindow = "e.timestamp >= '$dayStart' AND e.timestamp < '$selectedEnd'"
        val monthWindow = "e.timestamp >= '$monthStart' AND e.timestamp < '$selectedEnd'"
        val where = productWhere(scope, productLookbackStart(dateFrom, dateTo), dateTo, filters, "e")
        val segmentExpr = productSegmentExpression(dimension)
        val sql = """
            SELECT
                segment AS name,
                countIf(selected_event_count > 0) AS users,
                countIf(selected_signup_count > 0) AS new_users,
                countIf(selected_signup_count > 0 AND selected_activated_count > 0) AS activated_new_users,
                countIf(
                    selected_signup_count > 0
                    AND signup_at + INTERVAL $PRODUCT_WEEK_DAYS DAY <= '${endExclusive(dateTo)}'
                ) AS eligible_users,
                countIf(
                    selected_signup_count > 0
                    AND key_action_at > signup_at
                    AND key_action_at <= signup_at + INTERVAL $PRODUCT_WEEK_DAYS DAY
                ) AS retained_users,
                countIf(day_event_count > 0) AS daily_active_users,
                countIf(month_event_count > 0) AS monthly_active_users
            FROM (
                SELECT
                    e.project_id,
                    e.user_id,
                    anyIf($segmentExpr, $segmentExpr != '') AS segment,
                    countIf($selectedWindow) AS selected_event_count,
                    countIf($dayWindow) AS day_event_count,
                    countIf($monthWindow) AS month_event_count,
                    countIf($selectedWindow AND e.event_name = '$PRODUCT_SIGNUP_EVENT') AS selected_signup_count,
                    countIf($selectedWindow AND e.event_name = '$PRODUCT_ACTIVATED_EVENT') AS selected_activated_count,
                    minIf(e.timestamp, $selectedWindow AND e.event_name = '$PRODUCT_SIGNUP_EVENT') AS signup_at,
                    minIf(e.timestamp, $selectedWindow AND ${keyActionEventCondition("e")}) AS key_action_at
                FROM analytics_events AS e
                WHERE $where
                GROUP BY e.project_id, e.user_id
            )
            WHERE segment != ''
            GROUP BY segment
            ORDER BY users DESC
            LIMIT $PRODUCT_SEGMENT_LIMIT
            FORMAT JSONEachRow
        """.trimIndent()

        return parseRows(ClickHouseClient.executeWithFormat(sql, "JSONEachRow")) { row ->
            ProductSegmentRow(
                name = row.stringValue("name"),
                users = row.longValue("users"),
                activationRate = percentage(row.longValue("activated_new_users"), row.longValue("new_users")),
                week1Retention = percentage(row.longValue("retained_users"), row.longValue("eligible_users")),
                stickiness = percentage(row.longValue("daily_active_users"), row.longValue("monthly_active_users")),
            )
        }
    }

    // --- Query helpers ---

    private fun previousRange(
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): ProductDateRange {
        val days = ChronoUnit.DAYS.between(dateFrom, dateTo) + 1
        val previousTo = dateFrom.minusDays(1)
        return ProductDateRange(previousTo.minusDays(days - 1), previousTo)
    }

    private fun dateBuckets(range: ProductDateRange): List<LocalDate> {
        if (range.dateTo.isBefore(range.dateFrom)) return emptyList()
        val days = ChronoUnit.DAYS.between(range.dateFrom, range.dateTo)
        return (0..days).map { offset -> range.dateFrom.plusDays(offset) }
    }

    private fun endExclusive(dateTo: LocalDate): String =
        dateTo.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun productLookbackStart(
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): LocalDate {
        val monthStart = dateTo.minusDays(PRODUCT_MONTH_DAYS)
        return if (monthStart.isBefore(dateFrom)) monthStart else dateFrom
    }

    private fun productWhere(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        alias: String,
    ): String =
        buildWhere(scope, dateFrom, dateTo, filters, alias, "timestamp") +
            " AND $alias.source = '$PRODUCT_SOURCE' AND $alias.user_id != ''"

    private fun countMetric(
        value: Long,
        previous: Long,
        spark: List<Double>,
    ): ProductKpiMetric =
        ProductKpiMetric(value = value.toDouble(), previous = previous.toDouble(), spark = spark)

    private fun rateMetric(
        value: Double,
        previous: Double,
        spark: List<Double>,
    ): ProductKpiMetric =
        ProductKpiMetric(value = value, previous = previous, spark = spark)

    private fun productSpark(metricsByDate: Map<LocalDate, ProductDailyMetrics>): ProductSpark {
        val metrics = metricsByDate.toSortedMap().values.toList()
        return ProductSpark(
            activeUsers = metrics.map { it.activeUsers.toDouble() },
            newUsers = metrics.map { it.newUsers.toDouble() },
            activationRates = metrics.map { percentage(it.activatedUsers, it.newUsers) },
            stickinessRates = emptyList(),
            powerUserRates = metrics.map { percentage(it.keyActionUsers, it.activeUsers) },
        )
    }

    private fun productActivitySeries(
        metric: String,
        dates: List<LocalDate>,
        current: Map<LocalDate, ProductDailyMetrics>,
        previousByOffset: Map<Int, ProductDailyMetrics>,
        selector: (ProductDailyMetrics) -> Long,
    ): ProductActivitySeries =
        ProductActivitySeries(
            metric = metric,
            points = dates.mapIndexed { index, date ->
                ProductActivityPoint(
                    timestamp = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    value = current[date]?.let(selector) ?: 0L,
                    previous = previousByOffset[index]?.let(selector),
                )
            },
        )

    private fun keyActionEventCondition(alias: String): String =
        "$alias.event_name IN ('$PRODUCT_KEY_ACTION_EVENT', '$PRODUCT_ACTIVATED_EVENT')"

    private fun productRetentionEventCondition(
        mode: String,
        customEvent: String?,
        alias: String,
    ): String =
        when (mode) {
            "any_session" -> "$alias.event_name != ''"
            "custom" -> "$alias.event_name = '${AnalyticsIngestionWorker.escapeCH(customEvent.orEmpty())}'"
            else -> keyActionEventCondition(alias)
        }

    private fun productLifecycleEventList(): String =
        listOf(
            PRODUCT_SIGNUP_EVENT,
            PRODUCT_ONBOARDING_EVENT,
            PRODUCT_KEY_ACTION_EVENT,
            PRODUCT_ACTIVATED_EVENT,
        ).joinToString(", ") { "'$it'" }

    private fun productSegmentExpression(dimension: ProductSegmentDimension): String =
        when (dimension) {
            ProductSegmentDimension.PLAN ->
                "if(mapContains(e.props, 'plan') AND e.props['plan'] != '', e.props['plan'], '')"
            ProductSegmentDimension.PLATFORM ->
                "if(mapContains(e.props, 'platform') AND e.props['platform'] != '', " +
                    "e.props['platform'], e.device_type)"
            ProductSegmentDimension.COUNTRY -> "e.country_code"
        }

    private fun changeLabel(
        current: Long,
        previous: Long,
    ): String {
        if (previous == 0L) return if (current > 0) "${POSITIVE_CHANGE_PREFIX}100%" else "0%"
        val ratio = (current - previous).toDouble() / previous * PERCENTAGE_MULTIPLIER
        val prefix = if (ratio > 0) POSITIVE_CHANGE_PREFIX else ""
        return "$prefix${abs(ratio).roundToInt()}%"
    }

    @Suppress("LongParameterList")
    private fun buildWhere(
        scope: AnalyticsQueryScope,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        filters: List<AnalyticsFilter>,
        alias: String,
        timeColumn: String,
        propFilters: List<EventPropertyFilter> = emptyList(),
    ): String {
        val parts = mutableListOf<String>()
        parts.add(serviceScopeWhere(scope, alias))
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
        if (alias == "e") {
            for (filter in propFilters) {
                parts.add(eventPropertyFilterClause(filter, alias))
            }
        }
        return parts.joinToString(" AND ")
    }

    private fun eventPropertyFilterClause(filter: EventPropertyFilter, alias: String): String {
        val key = AnalyticsIngestionWorker.escapeCH(filter.key)
        val value = AnalyticsIngestionWorker.escapeCH(filter.value)
        val contains = "mapContains($alias.props, '$key')"
        val property = "$alias.props['$key']"
        return when (filter.operator) {
            "is" -> "$contains AND $property = '$value'"
            "is_not" -> "$contains AND $property != '$value'"
            "contains" -> "$contains AND $property LIKE '%$value%'"
            "not_contains" -> "$contains AND $property NOT LIKE '%$value%'"
            else -> throw IllegalArgumentException("Unsupported event property filter operator: ${filter.operator}")
        }
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

    private fun resolveGroupByColumn(groupBy: String): String =
        when (groupBy) {
            "user_id" -> "user_id"
            else -> "session_id"
        }

    private fun percentage(
        numerator: Long,
        denominator: Long,
    ): Double =
        if (denominator > 0) numerator.toDouble() / denominator * PERCENTAGE_MULTIPLIER else 0.0

    private fun sourceWhere(
        source: String?,
        alias: String = "",
    ): String {
        if (source.isNullOrBlank()) return ""
        val prefix = if (alias.isNotEmpty()) "$alias." else ""
        val escapedSource = AnalyticsIngestionWorker.escapeCH(source)
        return "AND ${prefix}source = '$escapedSource'"
    }

    private fun serviceScopeWhere(scope: AnalyticsQueryScope, alias: String = ""): String {
        val prefix = if (alias.isNotEmpty()) "$alias." else ""
        val serviceIds = scope.serviceIds
        if (serviceIds.isEmpty()) return "0 = 1"
        if (serviceIds.size == 1) return "${prefix}project_id = ${asClickHouseServiceId(serviceIds.first())}"
        val values = serviceIds.joinToString(", ") { asClickHouseServiceId(it) }
        return "${prefix}project_id IN ($values)"
    }

    private fun asClickHouseServiceId(serviceId: Long): String =
        "toUInt64($serviceId)"

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

    private data class ProductDateRange(
        val dateFrom: LocalDate,
        val dateTo: LocalDate,
    )

    private data class ProductSummaryMetrics(
        val activeUsers: Long = 0,
        val weeklyActiveUsers: Long = 0,
        val dailyActiveUsers: Long = 0,
        val monthlyActiveUsers: Long = 0,
        val newUsers: Long = 0,
        val activatedNewUsers: Long = 0,
        val powerUsers: Long = 0,
    )

    private data class ProductDailyMetrics(
        val day: LocalDate,
        val activeUsers: Long,
        val newUsers: Long,
        val activatedUsers: Long,
        val keyActionUsers: Long,
    )

    private data class ProductSpark(
        val activeUsers: List<Double>,
        val newUsers: List<Double>,
        val activationRates: List<Double>,
        val stickinessRates: List<Double>,
        val powerUserRates: List<Double>,
    )

    private data class ProductMoverCounts(
        val name: String,
        val current: Long,
        val previous: Long,
    )

    private data class ProductFeatureUsers(
        val name: String,
        val users: Long,
    )

    private enum class ProductSegmentDimension {
        PLAN,
        PLATFORM,
        COUNTRY,
    }
}
