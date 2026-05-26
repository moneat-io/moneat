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

@file:Suppress("MagicNumber")

package com.moneat.config

import com.moneat.dashboards.models.AggFunction
import com.moneat.dashboards.models.DashboardWidgets
import com.moneat.dashboards.models.Dashboards
import com.moneat.dashboards.models.FilterDef
import com.moneat.dashboards.models.FilterOp
import com.moneat.dashboards.models.GroupByDef
import com.moneat.dashboards.models.GroupByType
import com.moneat.dashboards.models.MetricDef
import com.moneat.dashboards.models.OrderByDef
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.TimeRangeDef
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Default time bucket for demo dashboard time-series widgets (Sonar: deduplicate literal). */
private const val DEMO_DASHBOARD_TIME_BUCKET = "1 HOUR"

internal data class DemoWidgetGrid(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

/** Title, widget type, and grid placement for demo dashboard widgets (Sonar S107: avoid long parameter lists). */
internal data class DemoWidgetPresentation(
    val title: String,
    val type: String,
    val grid: DemoWidgetGrid,
)

/** Single argument for [insertWidget] (Sonar S107: arity ≤ 7 on constructors and functions). */
internal data class DemoWidgetInsertSpec(
    val dashboardId: Long,
    val presentation: DemoWidgetPresentation,
    val queries: List<QueryDsl>,
    val display: Map<String, String> = emptyMap(),
    val order: Int = 0,
)

/** Query config for demo COUNT donut widgets (Sonar: keep insert helper arity ≤ 7). */
private data class DemoCountDonutQuerySpec(
    val dataSource: String,
    val groupByField: String,
    val filters: List<FilterDef> = emptyList(),
    val orderBy: OrderByDef? = null,
    val limit: Int = 100,
)

/** Query config for demo COUNT bar widgets (Sonar: keep insert helper arity ≤ 7). */
private data class DemoBarCountQuerySpec(
    val dataSource: String,
    val groupByField: String,
    val metricAlias: String = "count",
    val filters: List<FilterDef> = emptyList(),
    val limit: Int = 10,
    val display: Map<String, String> = emptyMap(),
)

internal fun countDemoDashboards(): Long =
    runCatching {
        transaction {
            Dashboards.selectAll()
                .where { (Dashboards.orgId eq DEMO_ORG_ID) and (Dashboards.createdBy eq DEMO_USER_ID) }
                .count()
        }
    }.getOrElse {
        logger.warn { "Failed to count demo dashboards (non-fatal): ${it.message}" }
        0L
    }

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// ── Demo Dashboard Seeding ─────────────────────────────────────────────

private const val DEMO_ORG_ID = -1L
private const val DEMO_USER_ID = -1L
internal const val DEMO_DASHBOARD_SEED_COUNT = 5

internal fun seedDemoDashboards() {
    runCatching {
        transaction {
            val demoDashboardIds = Dashboards.selectAll()
                .where { (Dashboards.orgId eq DEMO_ORG_ID) and (Dashboards.createdBy eq DEMO_USER_ID) }
                .map { it[Dashboards.id] }

            if (demoDashboardIds.isNotEmpty()) {
                DashboardWidgets.deleteWhere { dashboardId inList demoDashboardIds }
            }

            Dashboards.deleteWhere {
                (orgId eq DEMO_ORG_ID) and (createdBy eq DEMO_USER_ID)
            }

            seedErrorOverviewDashboard()
            seedPerformanceDashboard()
            seedLlmMonitoringDashboard()
            seedWebAnalyticsDashboard()
            seedWidgetGalleryDashboard()
        }
        logger.info { "Demo dashboards seeded successfully" }
    }.getOrElse { e ->
        logger.warn { "Demo dashboard seeding failed (non-fatal): ${e.message}" }
    }
}

internal fun insertDashboard(title: String, description: String): Long {
    val now = Clock.System.now()
    return Dashboards.insert {
        it[orgId] = DEMO_ORG_ID
        it[projectId] = null
        it[folderId] = null
        it[Dashboards.title] = title
        it[Dashboards.description] = description
        it[layoutType] = "grid"
        it[isDefault] = false
        it[variables] = "[]"
        it[createdBy] = DEMO_USER_ID
        it[createdAt] = now
        it[updatedAt] = now
    } get Dashboards.id
}

internal fun insertWidget(spec: DemoWidgetInsertSpec) {
    val now = Clock.System.now()
    val p = spec.presentation
    DashboardWidgets.insert {
        it[dashboardId] = spec.dashboardId
        it[DashboardWidgets.title] = p.title
        it[widgetType] = p.type
        it[gridX] = p.grid.x
        it[gridY] = p.grid.y
        it[gridW] = p.grid.w
        it[gridH] = p.grid.h
        it[queryConfig] =
            if (spec.queries.isNotEmpty()) json.encodeToString(spec.queries.first()) else "{}"
        it[queryConfigs] = json.encodeToString(spec.queries)
        it[displayConfig] = if (spec.display.isEmpty()) "{}" else json.encodeToString(spec.display)
        it[sortOrder] = spec.order
        it[createdAt] = now
        it[updatedAt] = now
    }
}

private val defaultTimeRange = TimeRangeDef("now-7d", "now")

/** Full-width section row; returns the next grid y after the section (Sonar: deduplicate section blocks). */
private fun insertDemoDashboardSectionRow(
    dashboardId: Long,
    title: String,
    row: Int,
    order: Int,
): Int {
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(
                title = title,
                type = "section",
                grid = DemoWidgetGrid(0, row, 12, 1),
            ),
            queries = emptyList(),
            order = order,
        ),
    )
    return row + 1
}

/** First section at row 0; returns dashboard id and next grid row (1). */
private fun openDemoDashboardWithSection(
    title: String,
    description: String,
    sectionTitle: String,
): Pair<Long, Int> {
    val id = insertDashboard(title, description)
    val nextRow = insertDemoDashboardSectionRow(id, sectionTitle, row = 0, order = 0)
    return id to nextRow
}

/** Donut: COUNT grouped by one field (Sonar: deduplicate donut widgets). */
private fun insertDemoCountDonutByField(
    dashboardId: Long,
    title: String,
    grid: DemoWidgetGrid,
    query: DemoCountDonutQuerySpec,
    order: Int,
) {
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(title = title, type = "donut", grid = grid),
            queries = listOf(
                QueryDsl(
                    dataSource = query.dataSource,
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                    groupBy = listOf(GroupByDef(query.groupByField, GroupByType.FIELD)),
                    filters = query.filters,
                    orderBy = query.orderBy,
                    timeRange = defaultTimeRange,
                    limit = query.limit,
                ),
            ),
            order = order,
        ),
    )
}

/** Bar: COUNT grouped by one field, descending order (Sonar: deduplicate bar widgets). */
private fun insertDemoBarCountByField(
    dashboardId: Long,
    title: String,
    grid: DemoWidgetGrid,
    query: DemoBarCountQuerySpec,
    order: Int,
) {
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(title = title, type = "bar", grid = grid),
            queries = listOf(
                QueryDsl(
                    dataSource = query.dataSource,
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = query.metricAlias)),
                    groupBy = listOf(GroupByDef(query.groupByField, GroupByType.FIELD)),
                    filters = query.filters,
                    orderBy = OrderByDef(query.metricAlias, "desc"),
                    timeRange = defaultTimeRange,
                    limit = query.limit,
                ),
            ),
            display = query.display,
            order = order,
        ),
    )
}

/**
 * Shared demo layout: timeseries + total + unique + bar (Sonar: deduplicate seed blocks).
 * [uniqueStat.filters] / [bar.filters]: null means reuse [rowFilters]; empty list means no filters.
 */
private data class DemoOverviewTimeseriesSpec(
    val title: String,
    val metricAlias: String,
    val groupBy: List<GroupByDef>,
)

private data class DemoOverviewTotalStatSpec(
    val title: String,
)

private data class DemoOverviewUniqueStatSpec(
    val title: String,
    val field: String,
    val metricAlias: String,
    val filters: List<FilterDef>? = null,
)

private data class DemoOverviewBarSpec(
    val title: String,
    val groupByField: String,
    val filters: List<FilterDef>? = null,
    val orderBy: OrderByDef? = null,
    val limit: Int? = null,
)

private data class DemoOverviewMetricRowConfig(
    val dataSource: String,
    val rowFilters: List<FilterDef>,
    val timeseries: DemoOverviewTimeseriesSpec,
    val totalStat: DemoOverviewTotalStatSpec,
    val uniqueStat: DemoOverviewUniqueStatSpec,
    val bar: DemoOverviewBarSpec,
)

private fun insertDemoOverviewMetricRow(
    dashboardId: Long,
    row: Int,
    config: DemoOverviewMetricRowConfig,
    orderStart: Int,
) {
    val uniqueFilters = config.uniqueStat.filters ?: config.rowFilters
    val barFilterList = config.bar.filters ?: config.rowFilters
    with(config) {
        insertWidget(
            DemoWidgetInsertSpec(
                dashboardId = dashboardId,
                presentation = DemoWidgetPresentation(
                    title = timeseries.title,
                    type = "timeseries",
                    grid = DemoWidgetGrid(0, row, 8, 4),
                ),
                queries = listOf(
                    QueryDsl(
                        dataSource = dataSource,
                        metrics = listOf(MetricDef(AggFunction.COUNT, alias = timeseries.metricAlias)),
                        groupBy = timeseries.groupBy,
                        filters = rowFilters,
                        timeRange = defaultTimeRange,
                        limit = 1000,
                    ),
                ),
                order = orderStart,
            ),
        )
        insertWidget(
            DemoWidgetInsertSpec(
                dashboardId = dashboardId,
                presentation = DemoWidgetPresentation(
                    title = totalStat.title,
                    type = "stat",
                    grid = DemoWidgetGrid(8, row, 2, 2),
                ),
                queries = listOf(
                    QueryDsl(
                        dataSource = dataSource,
                        metrics = listOf(MetricDef(AggFunction.COUNT, alias = "total")),
                        filters = rowFilters,
                        timeRange = defaultTimeRange,
                    ),
                ),
                order = orderStart + 1,
            ),
        )
        insertWidget(
            DemoWidgetInsertSpec(
                dashboardId = dashboardId,
                presentation = DemoWidgetPresentation(
                    title = uniqueStat.title,
                    type = "stat",
                    grid = DemoWidgetGrid(10, row, 2, 2),
                ),
                queries = listOf(
                    QueryDsl(
                        dataSource = dataSource,
                        metrics = listOf(
                            MetricDef(AggFunction.UNIQ, uniqueStat.field, uniqueStat.metricAlias),
                        ),
                        filters = uniqueFilters,
                        timeRange = defaultTimeRange,
                    ),
                ),
                order = orderStart + 2,
            ),
        )
        insertWidget(
            DemoWidgetInsertSpec(
                dashboardId = dashboardId,
                presentation = DemoWidgetPresentation(
                    title = bar.title,
                    type = "bar",
                    grid = DemoWidgetGrid(8, row + 2, 4, 2),
                ),
                queries = listOf(
                    QueryDsl(
                        dataSource = dataSource,
                        metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                        groupBy = listOf(GroupByDef(bar.groupByField, GroupByType.FIELD)),
                        filters = barFilterList,
                        orderBy = bar.orderBy,
                        timeRange = defaultTimeRange,
                        limit = bar.limit ?: 100,
                    ),
                ),
                order = orderStart + 3,
            ),
        )
    }
}

// ── Error Overview Dashboard ───────────────────────────────────────────

internal fun seedErrorOverviewDashboard() {
    val (id, row0) = openDemoDashboardWithSection(
        "Error Overview",
        "Cross-platform error monitoring across Android, iOS, and React Native",
        "Error Trends",
    )
    var row = row0
    insertDemoOverviewMetricRow(
        id,
        row,
        DemoOverviewMetricRowConfig(
            dataSource = "events",
            rowFilters = listOf(FilterDef("level", FilterOp.EQ, "error")),
            timeseries = DemoOverviewTimeseriesSpec(
                title = "Errors Over Time",
                metricAlias = "errors",
                groupBy = listOf(
                    GroupByDef("timestamp", GroupByType.TIME, DEMO_DASHBOARD_TIME_BUCKET),
                    GroupByDef("platform", GroupByType.FIELD),
                ),
            ),
            totalStat = DemoOverviewTotalStatSpec(title = "Total Errors"),
            uniqueStat = DemoOverviewUniqueStatSpec(
                title = "Affected Users",
                field = "user_id",
                metricAlias = "users",
            ),
            bar = DemoOverviewBarSpec(
                title = "Top Error Types",
                groupByField = "exception_type",
                orderBy = OrderByDef("count", "desc"),
                limit = 5,
            ),
        ),
        orderStart = 1,
    )
    row += 4

    // Section: Error Details
    row = insertDemoDashboardSectionRow(id, "Error Details", row, order = 5)

    // Recent errors table
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = id,
            presentation = DemoWidgetPresentation(
                title = "Recent Errors",
                type = "table",
                grid = DemoWidgetGrid(0, row, 8, 4),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                    groupBy = listOf(
                        GroupByDef("exception_type", GroupByType.FIELD),
                        GroupByDef("exception_value", GroupByType.FIELD),
                    ),
                    filters = listOf(FilterDef("level", FilterOp.EQ, "error")),
                    orderBy = OrderByDef("count", "desc"),
                    timeRange = defaultTimeRange,
                    limit = 20,
                ),
            ),
            order = 6,
        ),
    )

    // Errors by platform donut
    insertDemoCountDonutByField(
        dashboardId = id,
        title = "Errors by Platform",
        grid = DemoWidgetGrid(8, row, 4, 4),
        query = DemoCountDonutQuerySpec(
            dataSource = "events",
            groupByField = "platform",
            filters = listOf(FilterDef("level", FilterOp.EQ, "error")),
        ),
        order = 7,
    )
}

// ── Performance Dashboard ──────────────────────────────────────────────

internal fun seedPerformanceDashboard() {
    val (id, row0) = openDemoDashboardWithSection(
        "Performance",
        "Transaction performance and session monitoring",
        "Transactions",
    )
    var row = row0
    insertDemoOverviewMetricRow(
        id,
        row,
        DemoOverviewMetricRowConfig(
            dataSource = "events",
            rowFilters = listOf(FilterDef("event_type", FilterOp.EQ, "transaction")),
            timeseries = DemoOverviewTimeseriesSpec(
                title = "Transactions Over Time",
                metricAlias = "transactions",
                groupBy = listOf(
                    GroupByDef("timestamp", GroupByType.TIME, DEMO_DASHBOARD_TIME_BUCKET),
                ),
            ),
            totalStat = DemoOverviewTotalStatSpec(title = "Total Transactions"),
            uniqueStat = DemoOverviewUniqueStatSpec(
                title = "Unique Users",
                field = "user_id",
                metricAlias = "users",
            ),
            bar = DemoOverviewBarSpec(
                title = "Transactions by Platform",
                groupByField = "platform",
            ),
        ),
        orderStart = 1,
    )
    row += 4

    // Section: Sessions
    row = insertDemoDashboardSectionRow(id, "Sessions", row, order = 5)

    // Sessions over time
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = id,
            presentation = DemoWidgetPresentation(
                title = "Sessions Over Time",
                type = "timeseries",
                grid = DemoWidgetGrid(0, row, 6, 4),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "sessions",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "sessions")),
                    groupBy = listOf(GroupByDef("started", GroupByType.TIME, DEMO_DASHBOARD_TIME_BUCKET)),
                    timeRange = defaultTimeRange,
                    limit = 1000,
                ),
            ),
            order = 6,
        ),
    )

    // Total sessions stat
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = id,
            presentation = DemoWidgetPresentation(
                title = "Total Sessions",
                type = "stat",
                grid = DemoWidgetGrid(6, row, 3, 2),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "sessions",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "total")),
                    timeRange = defaultTimeRange,
                ),
            ),
            order = 7,
        ),
    )

    // Unique session users
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = id,
            presentation = DemoWidgetPresentation(
                title = "Unique Session Users",
                type = "stat",
                grid = DemoWidgetGrid(9, row, 3, 2),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "sessions",
                    metrics = listOf(MetricDef(AggFunction.UNIQ, "user_id", "users")),
                    timeRange = defaultTimeRange,
                ),
            ),
            order = 8,
        ),
    )
}

// ── LLM Monitoring Dashboard ───────────────────────────────────────────

internal fun seedLlmMonitoringDashboard() {
    val id = insertDashboard(
        "LLM Monitoring",
        "AI/LLM generation tracking — usage, latency, cost, and model breakdown"
    )
    var row = 0

    // Section: Usage Overview
    row = insertDemoDashboardSectionRow(id, "Usage Overview", row, order = 0)

    // Generations over time
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = id,
            presentation = DemoWidgetPresentation(
                title = "Generations Over Time",
                type = "timeseries",
                grid = DemoWidgetGrid(0, row, 6, 4),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "generations")),
                    groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, DEMO_DASHBOARD_TIME_BUCKET)),
                    timeRange = defaultTimeRange,
                    limit = 1000,
                ),
            ),
            order = 1,
        ),
    )

    // Total generations stat
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = id,
            presentation = DemoWidgetPresentation(
                title = "Total Generations",
                type = "stat",
                grid = DemoWidgetGrid(6, row, 2, 2),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "total")),
                    timeRange = defaultTimeRange,
                ),
            ),
            order = 2,
        ),
    )

    // Total tokens stat
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = id,
            presentation = DemoWidgetPresentation(
                title = "Total Tokens",
                type = "stat",
                grid = DemoWidgetGrid(8, row, 2, 2),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.SUM, "total_tokens", "tokens")),
                    timeRange = defaultTimeRange,
                ),
            ),
            order = 3,
        ),
    )

    // Total cost stat
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = id,
            presentation = DemoWidgetPresentation(
                title = "Total Cost",
                type = "stat",
                grid = DemoWidgetGrid(10, row, 2, 2),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.SUM, "cost_usd", "cost")),
                    timeRange = defaultTimeRange,
                ),
            ),
            display = mapOf("unit" to "currency_usd"),
            order = 4,
        ),
    )

    // Avg latency stat
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = id,
            presentation = DemoWidgetPresentation(
                title = "Avg Latency",
                type = "stat",
                grid = DemoWidgetGrid(6, row + 2, 2, 2),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.AVG, "duration_ms", "avg_ms")),
                    timeRange = defaultTimeRange,
                ),
            ),
            display = mapOf("unit" to "ms"),
            order = 5,
        ),
    )

    // P95 latency stat
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = id,
            presentation = DemoWidgetPresentation(
                title = "P95 Latency",
                type = "stat",
                grid = DemoWidgetGrid(8, row + 2, 2, 2),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.P95, "duration_ms", "p95_ms")),
                    timeRange = defaultTimeRange,
                ),
            ),
            display = mapOf("unit" to "ms"),
            order = 6,
        ),
    )

    // Error rate stat
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = id,
            presentation = DemoWidgetPresentation(
                title = "Error Generations",
                type = "stat",
                grid = DemoWidgetGrid(10, row + 2, 2, 2),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "errors")),
                    filters = listOf(FilterDef("status", FilterOp.EQ, "error")),
                    timeRange = defaultTimeRange,
                ),
            ),
            order = 7,
        ),
    )
    row += 4

    // Section: Model Breakdown
    row = insertDemoDashboardSectionRow(id, "Model Breakdown", row, order = 8)

    // Generations by model
    insertDemoBarCountByField(
        dashboardId = id,
        title = "Generations by Model",
        grid = DemoWidgetGrid(0, row, 4, 4),
        query = DemoBarCountQuerySpec(
            dataSource = "llm_generations",
            groupByField = "model",
        ),
        order = 9,
    )

    // Avg latency by model
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = id,
            presentation = DemoWidgetPresentation(
                title = "Avg Latency by Model",
                type = "bar",
                grid = DemoWidgetGrid(4, row, 4, 4),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.AVG, "duration_ms", "avg_ms")),
                    groupBy = listOf(GroupByDef("model", GroupByType.FIELD)),
                    orderBy = OrderByDef("avg_ms", "desc"),
                    timeRange = defaultTimeRange,
                    limit = 10,
                ),
            ),
            display = mapOf("unit" to "ms"),
            order = 10,
        ),
    )

    // Generations by provider donut
    insertDemoCountDonutByField(
        dashboardId = id,
        title = "Generations by Provider",
        grid = DemoWidgetGrid(8, row, 4, 4),
        query = DemoCountDonutQuerySpec(
            dataSource = "llm_generations",
            groupByField = "provider",
        ),
        order = 11,
    )
}

// ── Web Analytics Dashboard ────────────────────────────────────────────

internal fun seedWebAnalyticsDashboard() {
    val (id, row0) = openDemoDashboardWithSection(
        "Web Analytics",
        "Website traffic, pageviews, and visitor demographics",
        "Traffic Overview",
    )
    var row = row0
    insertDemoOverviewMetricRow(
        id,
        row,
        DemoOverviewMetricRowConfig(
            dataSource = "analytics_events",
            rowFilters = listOf(FilterDef("event_name", FilterOp.EQ, "pageview")),
            timeseries = DemoOverviewTimeseriesSpec(
                title = "Pageviews Over Time",
                metricAlias = "pageviews",
                groupBy = listOf(
                    GroupByDef("timestamp", GroupByType.TIME, DEMO_DASHBOARD_TIME_BUCKET),
                ),
            ),
            totalStat = DemoOverviewTotalStatSpec(title = "Total Pageviews"),
            uniqueStat = DemoOverviewUniqueStatSpec(
                title = "Unique Sessions",
                field = "session_id",
                metricAlias = "sessions",
                filters = emptyList(),
            ),
            bar = DemoOverviewBarSpec(
                title = "Events by Type",
                groupByField = "event_name",
                filters = emptyList(),
                orderBy = OrderByDef("count", "desc"),
                limit = 10,
            ),
        ),
        orderStart = 1,
    )
    row += 4

    // Section: Breakdown
    row = insertDemoDashboardSectionRow(id, "Breakdown", row, order = 5)

    // Top pages bar
    insertDemoBarCountByField(
        dashboardId = id,
        title = "Top Pages",
        grid = DemoWidgetGrid(0, row, 6, 4),
        query = DemoBarCountQuerySpec(
            dataSource = "analytics_events",
            groupByField = "pathname",
            metricAlias = "views",
            filters = listOf(FilterDef("event_name", FilterOp.EQ, "pageview")),
        ),
        order = 6,
    )

    // Traffic by country donut
    insertDemoCountDonutByField(
        dashboardId = id,
        title = "Traffic by Country",
        grid = DemoWidgetGrid(6, row, 3, 4),
        query = DemoCountDonutQuerySpec(
            dataSource = "analytics_events",
            groupByField = "country_code",
            orderBy = OrderByDef("count", "desc"),
            limit = 10,
        ),
        order = 7,
    )

    // Traffic by device type donut
    insertDemoCountDonutByField(
        dashboardId = id,
        title = "Traffic by Device",
        grid = DemoWidgetGrid(9, row, 3, 4),
        query = DemoCountDonutQuerySpec(
            dataSource = "analytics_events",
            groupByField = "device_type",
        ),
        order = 8,
    )
    row += 4

    // Traffic by browser bar
    insertDemoBarCountByField(
        dashboardId = id,
        title = "Traffic by Browser",
        grid = DemoWidgetGrid(0, row, 6, 4),
        query = DemoBarCountQuerySpec(
            dataSource = "analytics_events",
            groupByField = "browser",
        ),
        order = 9,
    )

    // Traffic by OS bar
    insertDemoBarCountByField(
        dashboardId = id,
        title = "Traffic by OS",
        grid = DemoWidgetGrid(6, row, 6, 4),
        query = DemoBarCountQuerySpec(
            dataSource = "analytics_events",
            groupByField = "os",
        ),
        order = 10,
    )
}

// ── Widget Gallery Dashboard ──────────────────────────────────────────

private data class DemoGalleryQuerySpec(
    val dataSource: String,
    val groupByFields: List<String>,
    val metricAlias: String = "count",
    val filters: List<FilterDef> = emptyList(),
    val orderBy: OrderByDef? = null,
    val limit: Int = 20,
)

private fun countByFieldsQuery(spec: DemoGalleryQuerySpec): QueryDsl =
    QueryDsl(
        dataSource = spec.dataSource,
        metrics = listOf(MetricDef(AggFunction.COUNT, alias = spec.metricAlias)),
        groupBy = spec.groupByFields.map { GroupByDef(it, GroupByType.FIELD) },
        filters = spec.filters,
        orderBy = spec.orderBy ?: OrderByDef(spec.metricAlias, "desc"),
        timeRange = defaultTimeRange,
        limit = spec.limit,
    )

private fun countByTimeAndFieldsQuery(spec: DemoGalleryQuerySpec): QueryDsl =
    countByFieldsQuery(spec).copy(
        groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, DEMO_DASHBOARD_TIME_BUCKET)) +
            spec.groupByFields.map { GroupByDef(it, GroupByType.FIELD) },
        orderBy = spec.orderBy ?: OrderByDef("time_bucket", "desc"),
    )

private fun insertWidgetGallerySection(dashboardId: Long, row: Int, order: Int): Int =
    insertDemoDashboardSectionRow(dashboardId, "Generic widget previews", row, order)

private fun insertWidgetGalleryTopRow(dashboardId: Long, row: Int) {
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(
                title = "Event Stream",
                type = "stream",
                grid = DemoWidgetGrid(0, row, 6, 4),
            ),
            queries = listOf(
                countByTimeAndFieldsQuery(
                    DemoGalleryQuerySpec(
                        dataSource = "events",
                        groupByFields = listOf("level", "environment", "message"),
                        limit = 40,
                    ),
                ),
            ),
            order = 1,
        ),
    )
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(
                title = "Incident Timeline",
                type = "timeline",
                grid = DemoWidgetGrid(6, row, 6, 4),
            ),
            queries = listOf(
                countByTimeAndFieldsQuery(
                    DemoGalleryQuerySpec(
                        dataSource = "events",
                        groupByFields = listOf("message", "platform"),
                        limit = 24,
                    ),
                ),
            ),
            order = 2,
        ),
    )
}

private fun insertWidgetGalleryMapRow(dashboardId: Long, row: Int) {
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(
                title = "Traffic Geography",
                type = "geo_map",
                grid = DemoWidgetGrid(0, row, 4, 4),
            ),
            queries = listOf(
                countByFieldsQuery(
                    DemoGalleryQuerySpec(
                        dataSource = "analytics_events",
                        groupByFields = listOf("country_code", "city"),
                        orderBy = OrderByDef("views", "desc"),
                        metricAlias = "views",
                        limit = 50,
                    ),
                ),
            ),
            order = 3,
        ),
    )
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(
                title = "Host Load Map",
                type = "host_map",
                grid = DemoWidgetGrid(4, row, 4, 4),
            ),
            queries = listOf(
                countByFieldsQuery(
                    DemoGalleryQuerySpec(
                        dataSource = "events",
                        groupByFields = listOf("server_name", "environment"),
                        limit = 80,
                    ),
                ),
            ),
            order = 4,
        ),
    )
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(
                title = "Service Topology",
                type = "topology_map",
                grid = DemoWidgetGrid(8, row, 4, 4),
            ),
            queries = listOf(
                countByFieldsQuery(
                    DemoGalleryQuerySpec(
                        dataSource = "events",
                        groupByFields = listOf("platform", "server_name"),
                        limit = 18,
                    ),
                ),
            ),
            order = 5,
        ),
    )
}

private fun insertWidgetGalleryBreakdownRow(dashboardId: Long, row: Int) {
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(
                title = "Service to Host Flow",
                type = "sankey",
                grid = DemoWidgetGrid(0, row, 4, 4),
            ),
            queries = listOf(
                countByFieldsQuery(
                    DemoGalleryQuerySpec(
                        dataSource = "events",
                        groupByFields = listOf("platform", "server_name"),
                        limit = 40,
                    ),
                ),
            ),
            order = 6,
        ),
    )
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(
                title = "Event Treemap",
                type = "treemap",
                grid = DemoWidgetGrid(4, row, 4, 4),
            ),
            queries = listOf(
                countByFieldsQuery(
                    DemoGalleryQuerySpec(
                        dataSource = "events",
                        groupByFields = listOf("platform", "environment"),
                        limit = 40,
                    ),
                ),
            ),
            order = 7,
        ),
    )
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(
                title = "Endpoint Status",
                type = "status",
                grid = DemoWidgetGrid(8, row, 4, 4),
            ),
            queries = listOf(
                countByFieldsQuery(
                    DemoGalleryQuerySpec(
                        dataSource = "events",
                        groupByFields = listOf("transaction_name", "level", "environment"),
                        limit = 36,
                    ),
                ),
            ),
            order = 8,
        ),
    )
}

private fun insertWidgetGalleryAnalysisRow(dashboardId: Long, row: Int) {
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(
                title = "Latency vs Volume",
                type = "scatter",
                grid = DemoWidgetGrid(0, row, 4, 4),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(
                        MetricDef(AggFunction.AVG, "duration_ms", "avg_ms"),
                        MetricDef(AggFunction.COUNT, alias = "events"),
                    ),
                    groupBy = listOf(GroupByDef("transaction_name", GroupByType.FIELD)),
                    filters = listOf(FilterDef("event_type", FilterOp.EQ, "transaction")),
                    orderBy = OrderByDef("events", "desc"),
                    timeRange = defaultTimeRange,
                    limit = 40,
                ),
            ),
            display = mapOf("unit" to "ms"),
            order = 9,
        ),
    )
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(
                title = "Request Change",
                type = "change",
                grid = DemoWidgetGrid(4, row, 4, 4),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "requests")),
                    groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, DEMO_DASHBOARD_TIME_BUCKET)),
                    filters = listOf(FilterDef("event_type", FilterOp.EQ, "transaction")),
                    timeRange = defaultTimeRange,
                    limit = 1000,
                ),
            ),
            display = mapOf("unit" to "short"),
            order = 10,
        ),
    )
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(
                title = "Trace Flame Graph",
                type = "flame_graph",
                grid = DemoWidgetGrid(8, row, 4, 4),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.P95, "duration_ms", "p95_ms")),
                    groupBy = listOf(
                        GroupByDef("transaction_name", GroupByType.FIELD),
                        GroupByDef("transaction_op", GroupByType.FIELD),
                    ),
                    filters = listOf(FilterDef("event_type", FilterOp.EQ, "transaction")),
                    orderBy = OrderByDef("p95_ms", "desc"),
                    timeRange = defaultTimeRange,
                    limit = 40,
                ),
            ),
            order = 11,
        ),
    )
}

private fun insertWidgetGalleryContentRow(dashboardId: Long, row: Int) {
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(
                title = "Model Cost Summary",
                type = "cost_summary",
                grid = DemoWidgetGrid(0, row, 4, 4),
            ),
            queries = listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.SUM, "cost_usd", "cost")),
                    groupBy = listOf(
                        GroupByDef("model", GroupByType.FIELD),
                        GroupByDef("provider", GroupByType.FIELD),
                    ),
                    orderBy = OrderByDef("cost", "desc"),
                    timeRange = defaultTimeRange,
                    limit = 12,
                ),
            ),
            display = mapOf("decimals" to "4"),
            order = 12,
        ),
    )
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(
                title = "Import Notes",
                type = "custom",
                grid = DemoWidgetGrid(4, row, 4, 4),
            ),
            queries = emptyList(),
            display = mapOf(
                "content" to "### Widget gallery\n\nThis dashboard previews the generic widget shapes used by imports.",
            ),
            order = 13,
        ),
    )
    insertWidget(
        DemoWidgetInsertSpec(
            dashboardId = dashboardId,
            presentation = DemoWidgetPresentation(
                title = "Embedded Runbook",
                type = "iframe",
                grid = DemoWidgetGrid(8, row, 4, 4),
            ),
            queries = emptyList(),
            display = mapOf("iframe_url" to "/demo/widget-preview-embed.html"),
            order = 14,
        ),
    )
}

internal fun seedWidgetGalleryDashboard() {
    val id = insertDashboard(
        "Widget Gallery",
        "Preview of generic Moneat widget types using the demo telemetry dataset",
    )
    var row = insertWidgetGallerySection(id, row = 0, order = 0)
    insertWidgetGalleryTopRow(id, row)
    row += 4
    insertWidgetGalleryMapRow(id, row)
    row += 4
    insertWidgetGalleryBreakdownRow(id, row)
    row += 4
    insertWidgetGalleryAnalysisRow(id, row)
    row += 4
    insertWidgetGalleryContentRow(id, row)
}
