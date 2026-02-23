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
import com.moneat.dashboards.models.DashboardResponse
import com.moneat.dashboards.models.FilterDef
import com.moneat.dashboards.models.FilterOp
import com.moneat.dashboards.models.MetricDef
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.WidgetResponse
import com.moneat.dashboards.translation.DataDogTranslator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataDogTranslatorTest {

    private val translator = DataDogTranslator()

    // --- Import ---

    @Test
    fun `import extracts dashboard title`() {
        val json = buildJsonObject {
            put("title", "My DD Dashboard")
            put("widgets", JsonArray(emptyList()))
        }
        val result = translator.import(json)
        assertEquals("My DD Dashboard", result.dashboard.title)
    }

    @Test
    fun `import uses default title when missing`() {
        val json = buildJsonObject {
            put("widgets", JsonArray(emptyList()))
        }
        val result = translator.import(json)
        assertEquals("Imported DataDog Dashboard", result.dashboard.title)
    }

    @Test
    fun `import maps timeseries widget type`() {
        val json = buildJsonObject {
            put("title", "Test")
            put(
                "widgets",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "definition",
                                buildJsonObject {
                                    put("type", "timeseries")
                                    put(
                                        "requests",
                                        buildJsonArray {
                                            add(buildJsonObject { put("q", "avg:system.cpu.user{*}") })
                                        }
                                    )
                                }
                            )
                            put(
                                "layout",
                                buildJsonObject {
                                    put("x", 0)
                                    put("y", 0)
                                    put("width", 6)
                                    put("height", 4)
                                }
                            )
                        }
                    )
                }
            )
        }
        val result = translator.import(json)
        assertEquals(1, result.dashboard.widgets.size)
        assertEquals("timeseries", result.dashboard.widgets[0].widgetType)
    }

    @Test
    fun `import maps query_value to stat`() {
        val json = buildJsonObject {
            put("title", "Test")
            put(
                "widgets",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "definition",
                                buildJsonObject {
                                    put("type", "query_value")
                                    put(
                                        "requests",
                                        buildJsonArray {
                                            add(buildJsonObject { put("q", "count:events{*}") })
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }
        val result = translator.import(json)
        assertEquals("stat", result.dashboard.widgets[0].widgetType)
    }

    @Test
    fun `import maps pie to donut`() {
        val json = buildJsonObject {
            put("title", "Test")
            put(
                "widgets",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "definition",
                                buildJsonObject {
                                    put("type", "pie")
                                    put(
                                        "requests",
                                        buildJsonArray {
                                            add(buildJsonObject { put("q", "count:events{*}") })
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }
        val result = translator.import(json)
        assertEquals("donut", result.dashboard.widgets[0].widgetType)
    }

    @Test
    fun `import unsupported widget type produces warning and text type`() {
        val json = buildJsonObject {
            put("title", "Test")
            put(
                "widgets",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "definition",
                                buildJsonObject {
                                    put("type", "unknown_type")
                                    put(
                                        "requests",
                                        buildJsonArray {
                                            add(buildJsonObject { put("q", "count:events{*}") })
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }
        val result = translator.import(json)
        assertEquals("text", result.dashboard.widgets[0].widgetType)
        assertTrue(result.warnings.any { it.contains("unsupported type") })
    }

    @Test
    fun `import extracts grid layout`() {
        val json = buildJsonObject {
            put("title", "Test")
            put(
                "widgets",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "definition",
                                buildJsonObject {
                                    put("type", "timeseries")
                                    put(
                                        "requests",
                                        buildJsonArray {
                                            add(buildJsonObject { put("q", "count:events{*}") })
                                        }
                                    )
                                }
                            )
                            put(
                                "layout",
                                buildJsonObject {
                                    put("x", 3)
                                    put("y", 5)
                                    put("width", 8)
                                    put("height", 3)
                                }
                            )
                        }
                    )
                }
            )
        }
        val result = translator.import(json)
        val w = result.dashboard.widgets[0]
        assertEquals(3, w.gridX)
        assertEquals(5, w.gridY)
        assertEquals(8, w.gridW)
        assertEquals(3, w.gridH)
    }

    @Test
    fun `import handles missing layout gracefully`() {
        val json = buildJsonObject {
            put("title", "Test")
            put(
                "widgets",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "definition",
                                buildJsonObject {
                                    put("type", "timeseries")
                                    put(
                                        "requests",
                                        buildJsonArray {
                                            add(buildJsonObject { put("q", "count:events{*}") })
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }
        val result = translator.import(json)
        assertEquals(0, result.dashboard.widgets[0].gridX)
    }

    // --- parseDataDogQueryString ---

    @Test
    fun `parseDataDogQueryString with avg system metric`() {
        val warnings = mutableListOf<String>()
        val dsl = translator.parseDataDogQueryString("avg:system.cpu.user{host:web01}", warnings, 0)
        assertEquals("system_metrics", dsl.dataSource)
        assertEquals(AggFunction.AVG, dsl.metrics[0].function)
        assertEquals("cpu_percent", dsl.metrics[0].field)
        assertTrue(dsl.filters.any { it.field == "host" && it.value == "web01" })
    }

    @Test
    fun `parseDataDogQueryString with count and wildcard tags`() {
        val warnings = mutableListOf<String>()
        val dsl = translator.parseDataDogQueryString("count:events{*}", warnings, 0)
        assertEquals("events", dsl.dataSource)
        assertEquals(AggFunction.COUNT, dsl.metrics[0].function)
        assertTrue(dsl.filters.isEmpty())
    }

    @Test
    fun `parseDataDogQueryString with trace metric maps to spans`() {
        val warnings = mutableListOf<String>()
        val dsl = translator.parseDataDogQueryString("p95:trace.latency{service:api}", warnings, 0)
        assertEquals("spans", dsl.dataSource)
        assertEquals(AggFunction.P95, dsl.metrics[0].function)
    }

    @Test
    fun `parseDataDogQueryString with multiple tags`() {
        val warnings = mutableListOf<String>()
        val dsl = translator.parseDataDogQueryString("sum:system.disk.used{host:web01,env:prod}", warnings, 0)
        assertEquals(2, dsl.filters.size)
    }

    // --- Export ---

    @Test
    fun `export generates valid DataDog JSON structure`() {
        val dashboard = DashboardResponse(
            id = 1,
            orgId = 1,
            title = "Test",
            createdBy = 1,
            createdAt = "",
            updatedAt = "",
            widgets = listOf(
                WidgetResponse(
                    id = 1, dashboardId = 1, title = "CPU",
                    widgetType = "timeseries",
                    gridX = 0, gridY = 0, gridW = 6, gridH = 4,
                    queryConfigs = listOf(
                        QueryDsl(
                            dataSource = "system_metrics",
                            metrics = listOf(MetricDef(AggFunction.AVG, "cpu_percent", "avg_cpu"))
                        )
                    ),
                )
            )
        )
        val exported = translator.export(dashboard)
        assertEquals("Test", exported["title"]?.jsonPrimitive?.content)
        assertEquals("ordered", exported["layout_type"]?.jsonPrimitive?.content)
        val widgets = exported["widgets"]!!.jsonArray
        assertEquals(1, widgets.size)
    }

    @Test
    fun `export maps widget types correctly`() {
        val dashboard = DashboardResponse(
            id = 1,
            orgId = 1,
            title = "Test",
            createdBy = 1,
            createdAt = "",
            updatedAt = "",
            widgets = listOf(
                WidgetResponse(
                    id = 1,
                    dashboardId = 1,
                    widgetType = "stat",
                    queryConfigs = listOf(QueryDsl(dataSource = "events"))
                ),
                WidgetResponse(
                    id = 2,
                    dashboardId = 1,
                    widgetType = "donut",
                    queryConfigs = listOf(QueryDsl(dataSource = "events"))
                )
            )
        )
        val exported = translator.export(dashboard)
        val widgets = exported["widgets"]!!.jsonArray
        val type0 = widgets[0].jsonObject["definition"]!!.jsonObject["type"]!!.jsonPrimitive.content
        val type1 = widgets[1].jsonObject["definition"]!!.jsonObject["type"]!!.jsonPrimitive.content
        assertEquals("query_value", type0)
        assertEquals("pie", type1)
    }

    // --- buildDdQueryString ---

    @Test
    fun `buildDdQueryString generates metric query format`() {
        val dsl = QueryDsl(
            dataSource = "system_metrics",
            metrics = listOf(MetricDef(AggFunction.AVG, "cpu_percent", "avg_cpu")),
            filters = listOf(FilterDef("host", FilterOp.EQ, "web01"))
        )
        val query = translator.buildDdQueryString(dsl)
        assertEquals("avg:cpu_percent{host:web01}", query)
    }

    @Test
    fun `buildDdQueryString with no filters uses wildcard`() {
        val dsl = QueryDsl(
            dataSource = "events",
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count"))
        )
        val query = translator.buildDdQueryString(dsl)
        assertEquals("count:events{*}", query)
    }

    @Test
    fun `import and export roundtrip preserves title`() {
        val original = buildJsonObject {
            put("title", "Roundtrip Test")
            put("layout_type", "ordered")
            put(
                "widgets",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "definition",
                                buildJsonObject {
                                    put("type", "timeseries")
                                    put(
                                        "requests",
                                        buildJsonArray {
                                            add(buildJsonObject { put("q", "count:events{*}") })
                                        }
                                    )
                                }
                            )
                            put(
                                "layout",
                                buildJsonObject {
                                    put("x", 0)
                                    put("y", 0)
                                    put("width", 12)
                                    put("height", 4)
                                }
                            )
                        }
                    )
                }
            )
        }
        val imported = translator.import(original)
        val exported = translator.export(imported.dashboard)
        assertEquals("Roundtrip Test", exported["title"]?.jsonPrimitive?.content)
    }
}
