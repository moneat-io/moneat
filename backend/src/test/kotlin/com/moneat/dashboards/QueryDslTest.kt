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

import com.moneat.dashboards.models.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryDslTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // --- DataSource ---

    @Test
    fun `DataSource fromString resolves known sources`() {
        assertEquals(DataSource.EVENTS, DataSource.fromString("events"))
        assertEquals(DataSource.SPANS, DataSource.fromString("spans"))
        assertEquals(DataSource.LOGS, DataSource.fromString("logs"))
        assertEquals(DataSource.SYSTEM_METRICS, DataSource.fromString("system_metrics"))
        assertEquals(DataSource.CONTAINER_METRICS, DataSource.fromString("container_metrics"))
        assertEquals(DataSource.LLM_GENERATIONS, DataSource.fromString("llm_generations"))
        assertEquals(DataSource.ANALYTICS_EVENTS, DataSource.fromString("analytics_events"))
    }

    @Test
    fun `DataSource fromString returns null for unknown source`() {
        assertNull(DataSource.fromString("nonexistent"))
    }

    // --- AggFunction ---

    @Test
    fun `AggFunction toClickHouse generates correct SQL for count`() {
        assertEquals("count()", AggFunction.COUNT.toClickHouse(null))
    }

    @Test
    fun `AggFunction toClickHouse generates correct SQL for avg`() {
        assertEquals("avg(duration_ms)", AggFunction.AVG.toClickHouse("duration_ms"))
    }

    @Test
    fun `AggFunction toClickHouse generates correct SQL for p95`() {
        assertEquals("quantile(0.95)(duration_ms)", AggFunction.P95.toClickHouse("duration_ms"))
    }

    @Test
    fun `AggFunction toClickHouse generates correct SQL for p50`() {
        assertEquals("quantile(0.50)(duration_ms)", AggFunction.P50.toClickHouse("duration_ms"))
    }

    @Test
    fun `AggFunction toClickHouse generates correct SQL for uniq`() {
        assertEquals("uniq(user_id)", AggFunction.UNIQ.toClickHouse("user_id"))
    }

    @Test
    fun `AggFunction toClickHouse generates correct SQL for sum`() {
        assertEquals("sum(bytes)", AggFunction.SUM.toClickHouse("bytes"))
    }

    @Test
    fun `AggFunction toClickHouse generates correct SQL for min and max`() {
        assertEquals("min(duration)", AggFunction.MIN.toClickHouse("duration"))
        assertEquals("max(duration)", AggFunction.MAX.toClickHouse("duration"))
    }

    // --- QueryDsl serialization ---

    @Test
    fun `QueryDsl serializes and deserializes correctly`() {
        val original = QueryDsl(
            dataSource = "events",
            metrics = listOf(
                MetricDef(AggFunction.COUNT, alias = "error_count"),
                MetricDef(AggFunction.AVG, "duration_ms", "avg_duration")
            ),
            groupBy = listOf(
                GroupByDef("environment", GroupByType.FIELD),
                GroupByDef("timestamp", GroupByType.TIME, "auto")
            ),
            filters = listOf(
                FilterDef("level", FilterOp.EQ, "error"),
                FilterDef("environment", FilterOp.IN, values = listOf("prod", "staging"))
            ),
            orderBy = OrderByDef("error_count", "desc"),
            limit = 50,
            timeRange = TimeRangeDef("now-24h", "now")
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<QueryDsl>(serialized)

        assertEquals(original.dataSource, deserialized.dataSource)
        assertEquals(original.metrics.size, deserialized.metrics.size)
        assertEquals(original.groupBy.size, deserialized.groupBy.size)
        assertEquals(original.filters.size, deserialized.filters.size)
        assertEquals(original.orderBy?.field, deserialized.orderBy?.field)
        assertEquals(original.limit, deserialized.limit)
        assertEquals(original.timeRange.from, deserialized.timeRange.from)
    }

    @Test
    fun `QueryDsl deserializes from JSON string`() {
        val jsonStr = """
        {
            "dataSource": "spans",
            "metrics": [{"function": "p95", "field": "duration_ms", "alias": "p95"}],
            "groupBy": [{"field": "timestamp", "type": "time", "interval": "auto"}],
            "filters": [],
            "limit": 100,
            "timeRange": {"from": "now-1h", "to": "now"}
        }
        """.trimIndent()

        val dsl = json.decodeFromString<QueryDsl>(jsonStr)
        assertEquals("spans", dsl.dataSource)
        assertEquals(AggFunction.P95, dsl.metrics[0].function)
        assertEquals("duration_ms", dsl.metrics[0].field)
        assertEquals(GroupByType.TIME, dsl.groupBy[0].type)
    }

    @Test
    fun `QueryDsl defaults work correctly`() {
        val dsl = QueryDsl(dataSource = "events")
        assertEquals(emptyList(), dsl.metrics)
        assertEquals(emptyList(), dsl.groupBy)
        assertEquals(emptyList(), dsl.filters)
        assertNull(dsl.orderBy)
        assertEquals(100, dsl.limit)
        assertEquals("now-24h", dsl.timeRange.from)
        assertEquals("now", dsl.timeRange.to)
        assertNull(dsl.rawQuery)
    }

    @Test
    fun `FilterOp values map to correct SQL operators`() {
        assertEquals("=", FilterOp.EQ.value)
        assertEquals("!=", FilterOp.NEQ.value)
        assertEquals(">", FilterOp.GT.value)
        assertEquals(">=", FilterOp.GTE.value)
        assertEquals("<", FilterOp.LT.value)
        assertEquals("<=", FilterOp.LTE.value)
        assertEquals("LIKE", FilterOp.LIKE.value)
        assertEquals("NOT LIKE", FilterOp.NOT_LIKE.value)
        assertEquals("IN", FilterOp.IN.value)
        assertEquals("NOT IN", FilterOp.NOT_IN.value)
        assertEquals("IS NULL", FilterOp.IS_NULL.value)
        assertEquals("IS NOT NULL", FilterOp.IS_NOT_NULL.value)
    }

    // --- MetricDef ---

    @Test
    fun `MetricDef with null field works for count`() {
        val metric = MetricDef(AggFunction.COUNT, null, "total")
        assertNull(metric.field)
        assertEquals("count()", metric.function.toClickHouse(metric.field))
    }

    // --- DataSourceInfo ---

    @Test
    fun `DataSourceInfo serializes correctly`() {
        val info = DataSourceInfo(
            name = "events",
            label = "Error Events",
            fields = listOf(
                DataSourceField("timestamp", "DateTime64", "Event timestamp"),
                DataSourceField("level", "String", "Error level")
            )
        )
        val serialized = json.encodeToString(info)
        assertContains(serialized, "Error Events")
        assertContains(serialized, "timestamp")
    }
}
