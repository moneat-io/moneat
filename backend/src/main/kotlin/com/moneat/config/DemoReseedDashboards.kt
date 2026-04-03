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

internal fun insertWidget(
    dashId: Long,
    title: String,
    type: String,
    grid: DemoWidgetGrid,
    queries: List<QueryDsl>,
    display: Map<String, String> = emptyMap(),
    order: Int = 0,
) {
    val now = Clock.System.now()
    DashboardWidgets.insert {
        it[dashboardId] = dashId
        it[DashboardWidgets.title] = title
        it[widgetType] = type
        it[gridX] = grid.x
        it[gridY] = grid.y
        it[gridW] = grid.w
        it[gridH] = grid.h
        it[queryConfig] = if (queries.isNotEmpty()) json.encodeToString(queries.first()) else "{}"
        it[queryConfigs] = json.encodeToString(queries)
        it[displayConfig] = if (display.isEmpty()) "{}" else json.encodeToString(display)
        it[sortOrder] = order
        it[createdAt] = now
        it[updatedAt] = now
    }
}

private val defaultTimeRange = TimeRangeDef("now-7d", "now")

/** First section at row 0; returns dashboard id and next grid row (1). */
private fun openDemoDashboardWithSection(
    title: String,
    description: String,
    sectionTitle: String,
): Pair<Long, Int> {
    val id = insertDashboard(title, description)
    insertWidget(
        id,
        sectionTitle,
        "section",
        DemoWidgetGrid(0, 0, 12, 1),
        emptyList(),
        order = 0,
    )
    return id to 1
}

/**
 * Shared demo layout: timeseries + total + unique + bar (Sonar: deduplicate seed blocks).
 * [uniqueStatFilters] / [barFilters]: null means reuse [rowFilters]; empty list means no filters.
 */
private data class DemoOverviewMetricRowConfig(
    val dataSource: String,
    val timeseriesTitle: String,
    val timeseriesMetricAlias: String,
    val timeseriesGroupBy: List<GroupByDef>,
    val rowFilters: List<FilterDef>,
    val totalStatTitle: String,
    val uniqueStatTitle: String,
    val uniqueField: String,
    val uniqueMetricAlias: String,
    val uniqueStatFilters: List<FilterDef>? = null,
    val barTitle: String,
    val barGroupByField: String,
    val barFilters: List<FilterDef>? = null,
    val barOrderBy: OrderByDef? = null,
    val barLimit: Int? = null,
)

private fun insertDemoOverviewMetricRow(
    dashboardId: Long,
    row: Int,
    config: DemoOverviewMetricRowConfig,
    orderStart: Int,
) {
    val uniqueFilters = config.uniqueStatFilters ?: config.rowFilters
    val barFilterList = config.barFilters ?: config.rowFilters
    with(config) {
        insertWidget(
            dashboardId,
            timeseriesTitle,
            "timeseries",
            DemoWidgetGrid(0, row, 8, 4),
            listOf(
                QueryDsl(
                    dataSource = dataSource,
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = timeseriesMetricAlias)),
                    groupBy = timeseriesGroupBy,
                    filters = rowFilters,
                    timeRange = defaultTimeRange,
                    limit = 1000,
                ),
            ),
            order = orderStart,
        )
        insertWidget(
            dashboardId,
            totalStatTitle,
            "stat",
            DemoWidgetGrid(8, row, 2, 2),
            listOf(
                QueryDsl(
                    dataSource = dataSource,
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "total")),
                    filters = rowFilters,
                    timeRange = defaultTimeRange,
                ),
            ),
            order = orderStart + 1,
        )
        insertWidget(
            dashboardId,
            uniqueStatTitle,
            "stat",
            DemoWidgetGrid(10, row, 2, 2),
            listOf(
                QueryDsl(
                    dataSource = dataSource,
                    metrics = listOf(MetricDef(AggFunction.UNIQ, uniqueField, uniqueMetricAlias)),
                    filters = uniqueFilters,
                    timeRange = defaultTimeRange,
                ),
            ),
            order = orderStart + 2,
        )
        insertWidget(
            dashboardId,
            barTitle,
            "bar",
            DemoWidgetGrid(8, row + 2, 4, 2),
            listOf(
                QueryDsl(
                    dataSource = dataSource,
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                    groupBy = listOf(GroupByDef(barGroupByField, GroupByType.FIELD)),
                    filters = barFilterList,
                    orderBy = barOrderBy,
                    timeRange = defaultTimeRange,
                    limit = barLimit ?: 100,
                ),
            ),
            order = orderStart + 3,
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
            timeseriesTitle = "Errors Over Time",
            timeseriesMetricAlias = "errors",
            timeseriesGroupBy = listOf(
                GroupByDef("timestamp", GroupByType.TIME, DEMO_DASHBOARD_TIME_BUCKET),
                GroupByDef("platform", GroupByType.FIELD),
            ),
            rowFilters = listOf(FilterDef("level", FilterOp.EQ, "error")),
            totalStatTitle = "Total Errors",
            uniqueStatTitle = "Affected Users",
            uniqueField = "user_id",
            uniqueMetricAlias = "users",
            barTitle = "Top Error Types",
            barGroupByField = "exception_type",
            barOrderBy = OrderByDef("count", "desc"),
            barLimit = 5,
        ),
        orderStart = 1,
    )
    row += 4

    // Section: Error Details
    insertWidget(
        id,
        "Error Details",
        "section",
        DemoWidgetGrid(0, row, 12, 1),
        emptyList(),
        order = 5,
    )
    row += 1

    // Recent errors table
    insertWidget(
        id,
        "Recent Errors",
        "table",
        DemoWidgetGrid(0, row, 8, 4),
        listOf(
            QueryDsl(
                dataSource = "events",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                groupBy = listOf(
                    GroupByDef("exception_type", GroupByType.FIELD),
                    GroupByDef("exception_value", GroupByType.FIELD)
                ),
                filters = listOf(FilterDef("level", FilterOp.EQ, "error")),
                orderBy = OrderByDef("count", "desc"),
                timeRange = defaultTimeRange,
                limit = 20
            )
        ),
        order = 6,
    )

    // Errors by platform donut
    insertWidget(
        id,
        "Errors by Platform",
        "donut",
        DemoWidgetGrid(8, row, 4, 4),
        listOf(
            QueryDsl(
                dataSource = "events",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                groupBy = listOf(GroupByDef("platform", GroupByType.FIELD)),
                filters = listOf(FilterDef("level", FilterOp.EQ, "error")),
                timeRange = defaultTimeRange
            )
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
            timeseriesTitle = "Transactions Over Time",
            timeseriesMetricAlias = "transactions",
            timeseriesGroupBy = listOf(
                GroupByDef("timestamp", GroupByType.TIME, DEMO_DASHBOARD_TIME_BUCKET),
            ),
            rowFilters = listOf(FilterDef("event_type", FilterOp.EQ, "transaction")),
            totalStatTitle = "Total Transactions",
            uniqueStatTitle = "Unique Users",
            uniqueField = "user_id",
            uniqueMetricAlias = "users",
            barTitle = "Transactions by Platform",
            barGroupByField = "platform",
        ),
        orderStart = 1,
    )
    row += 4

    // Section: Sessions
    insertWidget(
        id,
        "Sessions",
        "section",
        DemoWidgetGrid(0, row, 12, 1),
        emptyList(),
        order = 5,
    )
    row += 1

    // Sessions over time
    insertWidget(
        id,
        "Sessions Over Time",
        "timeseries",
        DemoWidgetGrid(0, row, 6, 4),
        listOf(
            QueryDsl(
                dataSource = "sessions",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "sessions")),
                groupBy = listOf(GroupByDef("started", GroupByType.TIME, DEMO_DASHBOARD_TIME_BUCKET)),
                timeRange = defaultTimeRange,
                limit = 1000
            )
        ),
        order = 6,
    )

    // Total sessions stat
    insertWidget(
        id,
        "Total Sessions",
        "stat",
        DemoWidgetGrid(6, row, 3, 2),
        listOf(
            QueryDsl(
                dataSource = "sessions",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "total")),
                timeRange = defaultTimeRange
            )
        ),
        order = 7,
    )

    // Unique session users
    insertWidget(
        id,
        "Unique Session Users",
        "stat",
        DemoWidgetGrid(9, row, 3, 2),
        listOf(
            QueryDsl(
                dataSource = "sessions",
                metrics = listOf(MetricDef(AggFunction.UNIQ, "user_id", "users")),
                timeRange = defaultTimeRange
            )
        ),
        order = 8,
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
    insertWidget(
        id,
        "Usage Overview",
        "section",
        DemoWidgetGrid(0, row, 12, 1),
        emptyList(),
        order = 0,
    )
    row += 1

    // Generations over time
    insertWidget(
        id,
        "Generations Over Time",
        "timeseries",
        DemoWidgetGrid(0, row, 6, 4),
        listOf(
            QueryDsl(
                dataSource = "llm_generations",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "generations")),
                groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, DEMO_DASHBOARD_TIME_BUCKET)),
                timeRange = defaultTimeRange,
                limit = 1000
            )
        ),
        order = 1,
    )

    // Total generations stat
    insertWidget(
        id,
        "Total Generations",
        "stat",
        DemoWidgetGrid(6, row, 2, 2),
        listOf(
            QueryDsl(
                dataSource = "llm_generations",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "total")),
                timeRange = defaultTimeRange
            )
        ),
        order = 2,
    )

    // Total tokens stat
    insertWidget(
        id,
        "Total Tokens",
        "stat",
        DemoWidgetGrid(8, row, 2, 2),
        listOf(
            QueryDsl(
                dataSource = "llm_generations",
                metrics = listOf(MetricDef(AggFunction.SUM, "total_tokens", "tokens")),
                timeRange = defaultTimeRange
            )
        ),
        order = 3,
    )

    // Total cost stat
    insertWidget(
        id,
        "Total Cost",
        "stat",
        DemoWidgetGrid(10, row, 2, 2),
        listOf(
            QueryDsl(
                dataSource = "llm_generations",
                metrics = listOf(MetricDef(AggFunction.SUM, "cost_usd", "cost")),
                timeRange = defaultTimeRange
            )
        ),
        mapOf("unit" to "currency_usd"),
        order = 4,
    )

    // Avg latency stat
    insertWidget(
        id,
        "Avg Latency",
        "stat",
        DemoWidgetGrid(6, row + 2, 2, 2),
        listOf(
            QueryDsl(
                dataSource = "llm_generations",
                metrics = listOf(MetricDef(AggFunction.AVG, "duration_ms", "avg_ms")),
                timeRange = defaultTimeRange
            )
        ),
        mapOf("unit" to "ms"),
        order = 5,
    )

    // P95 latency stat
    insertWidget(
        id,
        "P95 Latency",
        "stat",
        DemoWidgetGrid(8, row + 2, 2, 2),
        listOf(
            QueryDsl(
                dataSource = "llm_generations",
                metrics = listOf(MetricDef(AggFunction.P95, "duration_ms", "p95_ms")),
                timeRange = defaultTimeRange
            )
        ),
        mapOf("unit" to "ms"),
        order = 6,
    )

    // Error rate stat
    insertWidget(
        id,
        "Error Generations",
        "stat",
        DemoWidgetGrid(10, row + 2, 2, 2),
        listOf(
            QueryDsl(
                dataSource = "llm_generations",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "errors")),
                filters = listOf(FilterDef("status", FilterOp.EQ, "error")),
                timeRange = defaultTimeRange
            )
        ),
        order = 7,
    )
    row += 4

    // Section: Model Breakdown
    insertWidget(
        id,
        "Model Breakdown",
        "section",
        DemoWidgetGrid(0, row, 12, 1),
        emptyList(),
        order = 8,
    )
    row += 1

    // Generations by model
    insertWidget(
        id,
        "Generations by Model",
        "bar",
        DemoWidgetGrid(0, row, 4, 4),
        listOf(
            QueryDsl(
                dataSource = "llm_generations",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                groupBy = listOf(GroupByDef("model", GroupByType.FIELD)),
                orderBy = OrderByDef("count", "desc"),
                timeRange = defaultTimeRange,
                limit = 10
            )
        ),
        order = 9,
    )

    // Avg latency by model
    insertWidget(
        id,
        "Avg Latency by Model",
        "bar",
        DemoWidgetGrid(4, row, 4, 4),
        listOf(
            QueryDsl(
                dataSource = "llm_generations",
                metrics = listOf(MetricDef(AggFunction.AVG, "duration_ms", "avg_ms")),
                groupBy = listOf(GroupByDef("model", GroupByType.FIELD)),
                orderBy = OrderByDef("avg_ms", "desc"),
                timeRange = defaultTimeRange,
                limit = 10
            )
        ),
        mapOf("unit" to "ms"),
        order = 10,
    )

    // Generations by provider donut
    insertWidget(
        id,
        "Generations by Provider",
        "donut",
        DemoWidgetGrid(8, row, 4, 4),
        listOf(
            QueryDsl(
                dataSource = "llm_generations",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                groupBy = listOf(GroupByDef("provider", GroupByType.FIELD)),
                timeRange = defaultTimeRange
            )
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
            timeseriesTitle = "Pageviews Over Time",
            timeseriesMetricAlias = "pageviews",
            timeseriesGroupBy = listOf(
                GroupByDef("timestamp", GroupByType.TIME, DEMO_DASHBOARD_TIME_BUCKET),
            ),
            rowFilters = listOf(FilterDef("event_name", FilterOp.EQ, "pageview")),
            totalStatTitle = "Total Pageviews",
            uniqueStatTitle = "Unique Sessions",
            uniqueField = "session_id",
            uniqueMetricAlias = "sessions",
            uniqueStatFilters = emptyList(),
            barTitle = "Events by Type",
            barGroupByField = "event_name",
            barFilters = emptyList(),
            barOrderBy = OrderByDef("count", "desc"),
            barLimit = 10,
        ),
        orderStart = 1,
    )
    row += 4

    // Section: Breakdown
    insertWidget(
        id,
        "Breakdown",
        "section",
        DemoWidgetGrid(0, row, 12, 1),
        emptyList(),
        order = 5,
    )
    row += 1

    // Top pages bar
    insertWidget(
        id,
        "Top Pages",
        "bar",
        DemoWidgetGrid(0, row, 6, 4),
        listOf(
            QueryDsl(
                dataSource = "analytics_events",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "views")),
                groupBy = listOf(GroupByDef("pathname", GroupByType.FIELD)),
                filters = listOf(FilterDef("event_name", FilterOp.EQ, "pageview")),
                orderBy = OrderByDef("views", "desc"),
                timeRange = defaultTimeRange,
                limit = 10
            )
        ),
        order = 6,
    )

    // Traffic by country donut
    insertWidget(
        id,
        "Traffic by Country",
        "donut",
        DemoWidgetGrid(6, row, 3, 4),
        listOf(
            QueryDsl(
                dataSource = "analytics_events",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                groupBy = listOf(GroupByDef("country_code", GroupByType.FIELD)),
                orderBy = OrderByDef("count", "desc"),
                timeRange = defaultTimeRange,
                limit = 10
            )
        ),
        order = 7,
    )

    // Traffic by device type donut
    insertWidget(
        id,
        "Traffic by Device",
        "donut",
        DemoWidgetGrid(9, row, 3, 4),
        listOf(
            QueryDsl(
                dataSource = "analytics_events",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                groupBy = listOf(GroupByDef("device_type", GroupByType.FIELD)),
                timeRange = defaultTimeRange
            )
        ),
        order = 8,
    )
    row += 4

    // Traffic by browser bar
    insertWidget(
        id,
        "Traffic by Browser",
        "bar",
        DemoWidgetGrid(0, row, 6, 4),
        listOf(
            QueryDsl(
                dataSource = "analytics_events",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                groupBy = listOf(GroupByDef("browser", GroupByType.FIELD)),
                orderBy = OrderByDef("count", "desc"),
                timeRange = defaultTimeRange,
                limit = 10
            )
        ),
        order = 9,
    )

    // Traffic by OS bar
    insertWidget(
        id,
        "Traffic by OS",
        "bar",
        DemoWidgetGrid(6, row, 6, 4),
        listOf(
            QueryDsl(
                dataSource = "analytics_events",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                groupBy = listOf(GroupByDef("os", GroupByType.FIELD)),
                orderBy = OrderByDef("count", "desc"),
                timeRange = defaultTimeRange,
                limit = 10
            )
        ),
        order = 10,
    )
}
