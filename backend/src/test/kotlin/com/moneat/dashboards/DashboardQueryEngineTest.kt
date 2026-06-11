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

package com.moneat.dashboards

import com.moneat.dashboards.models.AggFunction
import com.moneat.dashboards.models.CustomDataSourceResponse
import com.moneat.dashboards.models.FilterDef
import com.moneat.dashboards.models.FilterOp
import com.moneat.dashboards.models.GroupByDef
import com.moneat.dashboards.models.GroupByType
import com.moneat.dashboards.models.MetricDef
import com.moneat.dashboards.models.OrderByDef
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.TimeRangeDef
import com.moneat.dashboards.services.CustomDataSourceService
import com.moneat.dashboards.services.DashboardQueryEngine
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardQueryEngineTest {
    private companion object {
        const val ORG_RESOURCE_ID = "33333333-3333-3333-3333-333333333333"
        const val USER_RESOURCE_ID = "44444444-4444-4444-4444-444444444444"
    }

    private val engine = DashboardQueryEngine()

    // ──── buildSelectClauses ────

    @Test
    fun `buildSelectClauses with count metric`() {
        val dsl = QueryDsl(
            dataSource = "events",
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = "error_count"))
        )
        val clauses = engine.buildSelectClauses(dsl, "timestamp")
        assertEquals(1, clauses.size)
        assertEquals("count() AS error_count", clauses[0])
    }

    @Test
    fun `buildSelectClauses with avg metric and field`() {
        val dsl = QueryDsl(
            dataSource = "spans",
            metrics = listOf(MetricDef(AggFunction.AVG, "duration_ms", "avg_duration"))
        )
        val clauses = engine.buildSelectClauses(dsl, "timestamp")
        assertEquals(1, clauses.size)
        assertEquals("avg(duration_ms) AS avg_duration", clauses[0])
    }

    @Test
    fun `buildSelectClauses with p95 metric`() {
        val dsl = QueryDsl(
            dataSource = "spans",
            metrics = listOf(MetricDef(AggFunction.P95, "duration_ms", "p95_duration"))
        )
        val clauses = engine.buildSelectClauses(dsl, "timestamp")
        assertEquals("quantile(0.95)(duration_ms) AS p95_duration", clauses[0])
    }

    @Test
    fun `buildSelectClauses with uniq metric`() {
        val dsl = QueryDsl(
            dataSource = "events",
            metrics = listOf(MetricDef(AggFunction.UNIQ, "user_id", "unique_users"))
        )
        val clauses = engine.buildSelectClauses(dsl, "timestamp")
        assertEquals("uniq(user_id) AS unique_users", clauses[0])
    }

    @Test
    fun `buildSelectClauses with time group by`() {
        val dsl = QueryDsl(
            dataSource = "events",
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
            groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "1 HOUR"))
        )
        val clauses = engine.buildSelectClauses(dsl, "timestamp")
        assertEquals(2, clauses.size)
        assertContains(clauses[0], "toStartOfInterval(timestamp, INTERVAL 1 HOUR) AS time_bucket")
    }

    @Test
    fun `buildSelectClauses with field group by`() {
        val dsl = QueryDsl(
            dataSource = "events",
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
            groupBy = listOf(GroupByDef("environment", GroupByType.FIELD))
        )
        val clauses = engine.buildSelectClauses(dsl, "timestamp")
        assertEquals(2, clauses.size)
        assertEquals("environment", clauses[0])
    }

    @Test
    fun `buildSelectClauses with auto interval resolves based on time range`() {
        val dsl = QueryDsl(
            dataSource = "events",
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
            groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "auto")),
            timeRange = TimeRangeDef("now-1h", "now")
        )
        val clauses = engine.buildSelectClauses(dsl, "timestamp")
        // 1h range -> 1 MINUTE interval
        assertContains(clauses[0], "INTERVAL 1 MINUTE")
    }

    @Test
    fun `buildSelectClauses with empty metrics returns count default`() {
        val dsl = QueryDsl(dataSource = "events")
        val clauses = engine.buildSelectClauses(dsl, "timestamp")
        assertEquals(1, clauses.size)
        assertEquals("count() AS total", clauses[0])
    }

    @Test
    fun `buildSelectClauses with multiple metrics`() {
        val dsl = QueryDsl(
            dataSource = "spans",
            metrics = listOf(
                MetricDef(AggFunction.AVG, "duration_ms", "avg"),
                MetricDef(AggFunction.P95, "duration_ms", "p95"),
                MetricDef(AggFunction.COUNT, alias = "total")
            )
        )
        val clauses = engine.buildSelectClauses(dsl, "timestamp")
        assertEquals(3, clauses.size)
    }

    // ──── buildFilterClause ────

    @Test
    fun `buildFilterClause with eq operator`() {
        val filter = FilterDef("level", FilterOp.EQ, "error")
        val clause = engine.buildFilterClause(filter)
        assertEquals("level = 'error'", clause)
    }

    @Test
    fun `buildFilterClause with neq operator`() {
        val filter = FilterDef("level", FilterOp.NEQ, "debug")
        val clause = engine.buildFilterClause(filter)
        assertEquals("level != 'debug'", clause)
    }

    @Test
    fun `buildFilterClause with in operator`() {
        val filter = FilterDef("environment", FilterOp.IN, values = listOf("prod", "staging"))
        val clause = engine.buildFilterClause(filter)
        assertEquals("environment IN ('prod', 'staging')", clause)
    }

    @Test
    fun `buildFilterClause with not_in operator`() {
        val filter = FilterDef("level", FilterOp.NOT_IN, values = listOf("debug", "trace"))
        val clause = engine.buildFilterClause(filter)
        assertEquals("level NOT IN ('debug', 'trace')", clause)
    }

    @Test
    fun `buildFilterClause with like operator`() {
        val filter = FilterDef("message", FilterOp.LIKE, "timeout")
        val clause = engine.buildFilterClause(filter)
        assertEquals("message LIKE '%timeout%'", clause)
    }

    @Test
    fun `buildFilterClause with is_null operator`() {
        val filter = FilterDef("user_id", FilterOp.IS_NULL)
        val clause = engine.buildFilterClause(filter)
        assertEquals("user_id IS NULL", clause)
    }

    @Test
    fun `buildFilterClause with is_not_null operator`() {
        val filter = FilterDef("user_id", FilterOp.IS_NOT_NULL)
        val clause = engine.buildFilterClause(filter)
        assertEquals("user_id IS NOT NULL", clause)
    }

    @Test
    fun `buildFilterClause escapes SQL injection attempts`() {
        val filter = FilterDef("level", FilterOp.EQ, "error'; DROP TABLE events; --")
        val clause = engine.buildFilterClause(filter)
        assertContains(clause, "\\'")
    }

    @Test
    fun `buildFilterClause rejects invalid field names`() {
        val filter = FilterDef("level; DROP TABLE", FilterOp.EQ, "error")
        assertFailsWith<IllegalArgumentException> {
            engine.buildFilterClause(filter)
        }
    }

    // ──── buildGroupByClauses ────

    @Test
    fun `buildGroupByClauses with time group`() {
        val dsl = QueryDsl(
            dataSource = "events",
            groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "1 HOUR"))
        )
        val clauses = engine.buildGroupByClauses(dsl)
        assertEquals(listOf("time_bucket"), clauses)
    }

    @Test
    fun `buildGroupByClauses with field group`() {
        val dsl = QueryDsl(
            dataSource = "events",
            groupBy = listOf(GroupByDef("environment", GroupByType.FIELD))
        )
        val clauses = engine.buildGroupByClauses(dsl)
        assertEquals(listOf("environment"), clauses)
    }

    @Test
    fun `buildGroupByClauses with multiple groups`() {
        val dsl = QueryDsl(
            dataSource = "events",
            groupBy = listOf(
                GroupByDef("timestamp", GroupByType.TIME, "auto"),
                GroupByDef("environment", GroupByType.FIELD)
            )
        )
        val clauses = engine.buildGroupByClauses(dsl)
        assertEquals(2, clauses.size)
        assertEquals("time_bucket", clauses[0])
        assertEquals("environment", clauses[1])
    }

    // ──── buildOrderByClause ────

    @Test
    fun `buildOrderByClause with explicit order`() {
        val dsl = QueryDsl(
            dataSource = "events",
            orderBy = OrderByDef("error_count", "desc")
        )
        val clause = engine.buildOrderByClause(dsl)
        assertEquals("error_count DESC", clause)
    }

    @Test
    fun `buildOrderByClause with asc direction`() {
        val dsl = QueryDsl(
            dataSource = "events",
            orderBy = OrderByDef("timestamp", "asc")
        )
        val clause = engine.buildOrderByClause(dsl)
        assertEquals("timestamp ASC", clause)
    }

    @Test
    fun `buildOrderByClause defaults to time_bucket when time group exists`() {
        val dsl = QueryDsl(
            dataSource = "events",
            groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "auto"))
        )
        val clause = engine.buildOrderByClause(dsl)
        assertEquals("time_bucket ASC", clause)
    }

    @Test
    fun `buildOrderByClause returns empty when no order and no time group`() {
        val dsl = QueryDsl(dataSource = "events")
        val clause = engine.buildOrderByClause(dsl)
        assertEquals("", clause)
    }

    // ──── parseTimeExpression ────

    @Test
    fun `parseTimeExpression handles now`() {
        val result = engine.parseTimeExpression("now", "now()")
        assertEquals("now()", result)
    }

    @Test
    fun `parseTimeExpression handles relative hours`() {
        val result = engine.parseTimeExpression("now-24h", "now()")
        assertEquals("now() - INTERVAL 24 HOUR", result)
    }

    @Test
    fun `parseTimeExpression handles relative days`() {
        val result = engine.parseTimeExpression("now-7d", "now()")
        assertEquals("now() - INTERVAL 7 DAY", result)
    }

    @Test
    fun `parseTimeExpression handles relative minutes`() {
        val result = engine.parseTimeExpression("now-15m", "now()")
        assertEquals("now() - INTERVAL 15 MINUTE", result)
    }

    @Test
    fun `parseTimeExpression handles ISO timestamp`() {
        val result = engine.parseTimeExpression("2024-01-01T00:00:00Z", "now()")
        assertContains(result, "toDateTime64")
        assertContains(result, "2024-01-01T00:00:00Z")
    }

    @Test
    fun `parseTimeExpression with demo epoch`() {
        val demoNow = "toDateTime64(1706745600.0, 3)"
        val result = engine.parseTimeExpression("now-1h", demoNow)
        assertEquals("$demoNow - INTERVAL 1 HOUR", result)
    }

    // ──── resolveTimeInterval ────

    @Test
    fun `resolveTimeInterval for 1 hour range returns 1 MINUTE`() {
        val interval = DashboardQueryEngine.resolveTimeInterval("now-1h", "now")
        assertEquals("1 MINUTE", interval)
    }

    @Test
    fun `resolveTimeInterval for 24 hour range returns 15 MINUTE`() {
        val interval = DashboardQueryEngine.resolveTimeInterval("now-24h", "now")
        assertEquals("15 MINUTE", interval)
    }

    @Test
    fun `resolveTimeInterval for 7 day range returns 1 HOUR`() {
        val interval = DashboardQueryEngine.resolveTimeInterval("now-7d", "now")
        assertEquals("1 HOUR", interval)
    }

    @Test
    fun `resolveTimeInterval for 30 day range returns 4 HOUR`() {
        val interval = DashboardQueryEngine.resolveTimeInterval("now-30d", "now")
        assertEquals("4 HOUR", interval)
    }

    @Test
    fun `resolveTimeInterval for 90 day range returns 1 DAY`() {
        val interval = DashboardQueryEngine.resolveTimeInterval("now-90d", "now")
        assertEquals("1 DAY", interval)
    }

    // ──── buildWhereClauses ────

    @Test
    fun `buildWhereClauses includes project_id clause`() {
        val dsl = QueryDsl(dataSource = "events")
        val clauses = engine.buildWhereClauses(dsl, 123, "timestamp", null, 90)
        assertTrue(clauses.any { it.contains("project_id = 123") })
    }

    @Test
    fun `buildWhereClauses scopes log queries by organization when org id is available`() {
        val dsl = QueryDsl(dataSource = "logs")
        val clauses = engine.buildWhereClauses(dsl, 123, "timestamp", null, 90, orgId = 456)
        assertTrue(clauses.any { it.contains("organization_id = 456") })
        assertFalse(clauses.any { it.contains("project_id = 123") })
    }

    @Test
    fun `buildWhereClauses scopes log queries by project when org id is unavailable`() {
        val dsl = QueryDsl(dataSource = "logs")
        val clauses = engine.buildWhereClauses(dsl, 123, "timestamp", null, 90)
        assertTrue(clauses.any { it.contains("project_id = 123") })
        assertFalse(clauses.any { it.contains("organization_id =") })
    }

    @Test
    fun `buildWhereClauses scopes metrics and containers by organization when org id is available`() {
        val orgScopedSources = listOf("metrics", "containers")
        for (source in orgScopedSources) {
            val dsl = QueryDsl(dataSource = source)
            val clauses = engine.buildWhereClauses(dsl, 123, "timestamp", null, 90, orgId = 456)
            assertTrue(clauses.any { it.contains("organization_id = 456") })
            assertFalse(clauses.any { it.contains("project_id = 123") })
        }
    }

    @Test
    fun `buildWhereClauses keeps event queries project scoped when org id is available`() {
        val dsl = QueryDsl(dataSource = "events")
        val clauses = engine.buildWhereClauses(dsl, 123, "timestamp", null, 90, orgId = 456)
        assertTrue(clauses.any { it.contains("project_id = 123") })
        assertFalse(clauses.any { it.contains("organization_id = 456") })
    }

    @Test
    fun `buildWhereClauses includes retention clause`() {
        val dsl = QueryDsl(dataSource = "events")
        val clauses = engine.buildWhereClauses(dsl, 123, "timestamp", null, 90)
        assertTrue(clauses.any { it.contains("INTERVAL 90 DAY") })
    }

    @Test
    fun `buildWhereClauses with demo epoch uses toDateTime64`() {
        val dsl = QueryDsl(dataSource = "events")
        val clauses = engine.buildWhereClauses(dsl, -1, "timestamp", 1706745600000L, 90)
        assertTrue(clauses.any { it.contains("toInt64(project_id) IN (-1, -2, -3)") })
        assertTrue(clauses.any { it.contains("toDateTime64") })
    }

    @Test
    fun `buildWhereClauses includes filters`() {
        val dsl = QueryDsl(
            dataSource = "events",
            filters = listOf(FilterDef("level", FilterOp.EQ, "error"))
        )
        val clauses = engine.buildWhereClauses(dsl, 123, "timestamp", null, 90)
        assertTrue(clauses.any { it.contains("level = 'error'") })
    }

    @Test
    fun `buildWhereClauses applies log raw query through explorer parser`() {
        val dsl = QueryDsl(
            dataSource = "logs",
            rawQuery = "level:error service:api timeout"
        )

        val clauses = engine.buildWhereClauses(dsl, 123, "timestamp", null, 90, orgId = 456)
        val rawClause = clauses.single { it.contains("toString(level) = 'error'") }

        assertContains(rawClause, "service = 'api'")
        assertContains(rawClause, "hasTokenCaseInsensitive(message, 'timeout')")
    }

    @Test
    fun `buildLogRawQueryClause ignores non log and blank raw queries`() {
        assertNull(engine.buildLogRawQueryClause(QueryDsl(dataSource = "events", rawQuery = "level:error")))
        assertNull(engine.buildLogRawQueryClause(QueryDsl(dataSource = "logs", rawQuery = " ")))
    }

    // ──── buildQuery (integration / full SQL) ────

    @Test
    fun `buildQuery rejects unknown data source`() {
        val dsl = QueryDsl(dataSource = "nonexistent_table")
        assertFailsWith<IllegalArgumentException> {
            engine.buildQuery(dsl, 123)
        }
    }

    @Test
    fun `buildQuery enforces limit bounds`() {
        val dsl = QueryDsl(dataSource = "events", limit = 50000)
        val sql = engine.buildQuery(dsl, 123)
        assertContains(sql, "LIMIT 10000")
    }

    @Test
    fun `buildQuery enforces minimum limit`() {
        val dsl = QueryDsl(dataSource = "events", limit = -5)
        val sql = engine.buildQuery(dsl, 123)
        assertContains(sql, "LIMIT 1")
    }

    @Test
    fun `buildQuery includes FORMAT JSONEachRow`() {
        val dsl = QueryDsl(dataSource = "events")
        val sql = engine.buildQuery(dsl, 123)
        assertContains(sql, "FORMAT JSONEachRow")
    }

    // ──── getDataSources ────

    @Test
    fun `getDataSources returns all 8 data sources`() {
        val sources = engine.getDataSources()
        assertEquals(8, sources.size)
        val names = sources.map { it.name }
        assertContains(names, "events")
        assertContains(names, "spans")
        assertContains(names, "logs")
        assertContains(names, "metrics")
        assertContains(names, "containers")
        assertContains(names, "uptime_heartbeats")
        assertContains(names, "llm_generations")
        assertContains(names, "analytics_events")
    }

    @Test
    fun `getDataSources all have non-empty fields`() {
        val sources = engine.getDataSources()
        sources.forEach { ds ->
            assertTrue(ds.fields.isNotEmpty(), "Data source ${ds.name} should have fields")
        }
    }

    @Test
    fun `resolveTemplateDataSource maps marker to enabled custom source`() {
        val dataSourceService = mockk<CustomDataSourceService>()
        every { dataSourceService.listDataSources(1L) } returns listOf(
            customDataSource(id = 42, sourceType = "prometheus", enabled = true)
        )

        val resolved = engine.resolveTemplateDataSource(
            QueryDsl(dataSource = "__prometheus", rawQuery = "up"),
            1L,
            dataSourceService,
        )

        assertEquals("custom:11111111-1111-1111-1111-000000000042", resolved.dataSource)
    }

    @Test
    fun `resolveTemplateDataSource leaves marker unchanged without enabled source`() {
        val dataSourceService = mockk<CustomDataSourceService>()
        every { dataSourceService.listDataSources(1L) } returns listOf(
            customDataSource(id = 42, sourceType = "prometheus", enabled = false)
        )

        val resolved = engine.resolveTemplateDataSource(
            QueryDsl(dataSource = "__prometheus", rawQuery = "up"),
            1L,
            dataSourceService,
        )

        assertEquals("__prometheus", resolved.dataSource)
    }

    // ──── applyVariables ────

    @Test
    fun `applyVariables substitutes dollar-name in filter value`() {
        val dsl = QueryDsl(
            dataSource = "events",
            filters = listOf(FilterDef(field = "environment", op = FilterOp.EQ, value = "\$env"))
        )
        val result = engine.applyVariables(dsl, mapOf("env" to "production"))
        assertEquals("production", result.filters[0].value)
    }

    @Test
    fun `applyVariables substitutes braced variable in filter value`() {
        val dsl = QueryDsl(
            dataSource = "events",
            filters = listOf(FilterDef(field = "environment", op = FilterOp.EQ, value = "\${env}"))
        )
        val result = engine.applyVariables(dsl, mapOf("env" to "staging"))
        assertEquals("staging", result.filters[0].value)
    }

    @Test
    fun `applyVariables substitutes in rawQuery`() {
        val dsl = QueryDsl(
            dataSource = "events",
            rawQuery = "SELECT * FROM events WHERE env = '\$environment'"
        )
        val result = engine.applyVariables(dsl, mapOf("environment" to "production"))
        assertEquals("SELECT * FROM events WHERE env = 'production'", result.rawQuery)
    }

    @Test
    fun `applyVariables substitutes in filter values list`() {
        val dsl = QueryDsl(
            dataSource = "events",
            filters = listOf(FilterDef(field = "env", op = FilterOp.IN, values = listOf("\$e1", "\$e2")))
        )
        val result = engine.applyVariables(dsl, mapOf("e1" to "prod", "e2" to "staging"))
        assertEquals(listOf("prod", "staging"), result.filters[0].values)
    }

    @Test
    fun `applyVariables returns unchanged dsl when variables empty`() {
        val dsl = QueryDsl(
            dataSource = "events",
            filters = listOf(FilterDef(field = "env", op = FilterOp.EQ, value = "\$env")),
            rawQuery = "SELECT \$env"
        )
        val result = engine.applyVariables(dsl, emptyMap())
        assertEquals("\$env", result.filters[0].value)
        assertEquals("SELECT \$env", result.rawQuery)
    }

    @Test
    fun `applyVariables escapes SQL injection in values`() {
        val dsl = QueryDsl(
            dataSource = "events",
            filters = listOf(FilterDef(field = "env", op = FilterOp.EQ, value = "\$env"))
        )
        val result = engine.applyVariables(dsl, mapOf("env" to "'; DROP TABLE events; --"))
        // Single quotes should be escaped with backslash
        val value = result.filters[0].value!!
        assertContains(value, "\\'")
        // The raw unescaped single quote should not appear without a preceding backslash
        assertFalse(value.startsWith("';"), "Value should not start with unescaped quote")
    }

    @Test
    fun `applyVariables expands a multi-value selection into an IN list`() {
        val dsl = QueryDsl(
            dataSource = "events",
            filters = listOf(FilterDef(field = "pod", op = FilterOp.EQ, value = "\$pod"))
        )
        val result = engine.applyVariables(dsl, mapOf("pod" to "pod-a,pod-b,pod-c"))
        assertEquals(1, result.filters.size)
        assertEquals(FilterOp.IN, result.filters[0].op)
        assertEquals(listOf("pod-a", "pod-b", "pod-c"), result.filters[0].values)
        assertNull(result.filters[0].value)
    }

    @Test
    fun `applyVariables expands a multi-value NEQ into a NOT IN list`() {
        val dsl = QueryDsl(
            dataSource = "events",
            filters = listOf(FilterDef(field = "pod", op = FilterOp.NEQ, value = "\$pod"))
        )
        val result = engine.applyVariables(dsl, mapOf("pod" to "a,b"))
        assertEquals(FilterOp.NOT_IN, result.filters[0].op)
        assertEquals(listOf("a", "b"), result.filters[0].values)
    }

    @Test
    fun `applyVariables drops a pure-reference filter when All is selected`() {
        val dsl = QueryDsl(
            dataSource = "events",
            filters = listOf(FilterDef(field = "env", op = FilterOp.EQ, value = "\$env"))
        )
        val result = engine.applyVariables(dsl, mapOf("env" to "\$__all"))
        assertTrue(result.filters.isEmpty())
    }

    @Test
    fun `applyVariables renders multi-value as a regex alternation in rawQuery`() {
        val dsl = QueryDsl(
            dataSource = "events",
            rawQuery = "pod=~\"\$pod\""
        )
        val result = engine.applyVariables(dsl, mapOf("pod" to "a,b"))
        assertEquals("pod=~\"(a|b)\"", result.rawQuery)
    }

    @Test
    fun `applyVariables keeps a single value as equality`() {
        val dsl = QueryDsl(
            dataSource = "events",
            filters = listOf(FilterDef(field = "pod", op = FilterOp.EQ, value = "\$pod"))
        )
        val result = engine.applyVariables(dsl, mapOf("pod" to "only-one"))
        assertEquals(FilterOp.EQ, result.filters[0].op)
        assertEquals("only-one", result.filters[0].value)
    }

    private fun customDataSource(
        id: Long,
        sourceType: String,
        enabled: Boolean,
    ): CustomDataSourceResponse =
        CustomDataSourceResponse(
            id = "11111111-1111-1111-1111-${id.toString().padStart(12, '0')}",
            orgId = ORG_RESOURCE_ID,
            name = sourceType,
            sourceType = sourceType,
            host = "localhost",
            numericId = id,
            port = 9090,
            enabled = enabled,
            createdBy = USER_RESOURCE_ID,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )
}
