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
import com.moneat.dashboards.models.GroupByType
import com.moneat.dashboards.models.MetricDef
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.WidgetResponse
import com.moneat.dashboards.translation.DataDogTranslator
import kotlinx.serialization.json.Json
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
    private val json = Json { ignoreUnknownKeys = true }

    private fun loadFixture(path: String) =
        json.parseToJsonElement(
            requireNotNull(javaClass.classLoader.getResource(path)) {
                "Missing test fixture: $path"
            }.readText()
        ).jsonObject

    // ──── Import ────

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

    @Test
    fun `import flattens Datadog integration groups into sections and child widgets`() {
        val fixture = loadFixture("dashboards/datadog/postgres-integration-overview.json")
        val result = translator.import(fixture)

        assertEquals("Postgres - Overview", result.dashboard.title)
        assertEquals(6, result.dashboard.widgets.size)
        assertEquals("section", result.dashboard.widgets[0].widgetType)
        assertEquals("Resource Utilization", result.dashboard.widgets[0].title)
        assertEquals("text", result.dashboard.widgets[1].widgetType)
        assertEquals("stat", result.dashboard.widgets[2].widgetType)
        assertEquals("timeseries", result.dashboard.widgets[3].widgetType)
        assertEquals("table", result.dashboard.widgets[4].widgetType)
        assertEquals("timeseries", result.dashboard.widgets[5].widgetType)
    }

    @Test
    fun `import offsets child widget layout below Datadog group section`() {
        val fixture = loadFixture("dashboards/datadog/postgres-integration-overview.json")
        val result = translator.import(fixture)
        val note = result.dashboard.widgets[1]
        val table = result.dashboard.widgets[4]
        val standalone = result.dashboard.widgets[5]

        assertEquals(1, note.gridY)
        assertEquals(6, table.gridW)
        assertEquals(3, table.gridY)
        assertTrue(standalone.gridY >= 6)
    }

    @Test
    fun `import auto-places Datadog group children after explicit child layouts`() {
        val json = buildJsonObject {
            put("title", "Grouped")
            put(
                "widgets",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "definition",
                                buildJsonObject {
                                    put("type", "group")
                                    put("title", "Group")
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
                                                            put("y", 2)
                                                            put("width", 6)
                                                            put("height", 3)
                                                        }
                                                    )
                                                }
                                            )
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
                            )
                            put(
                                "layout",
                                buildJsonObject {
                                    put("x", 0)
                                    put("y", 0)
                                    put("width", 12)
                                    put("height", 6)
                                }
                            )
                        }
                    )
                }
            )
        }

        val widgets = translator.import(json).dashboard.widgets

        assertEquals(3, widgets[1].gridY)
        assertTrue(widgets[2].gridY >= 6)
    }

    @Test
    fun `import parses all Datadog request queries from integration fixture`() {
        val fixture = loadFixture("dashboards/datadog/postgres-integration-overview.json")
        val result = translator.import(fixture)
        val timeseries = result.dashboard.widgets.first { it.title == "Rows fetched / returned" }
        val table = result.dashboard.widgets.first { it.title == "Locks by host" }

        assertEquals(2, timeseries.queryConfigs.size)
        assertEquals("rows fetched", timeseries.queryConfigs[0].metrics[0].alias)
        assertEquals("rows returned", timeseries.queryConfigs[1].metrics[0].alias)
        assertEquals(2, table.queryConfigs.size)
        assertTrue(
            table.queryConfigs.all { query ->
                query.filters.any { it.field == "metric_name" && it.value?.startsWith("postgresql.") == true }
            }
        )
    }

    @Test
    fun `import supports Datadog effective dashboard fixture shapes`() {
        val fixture = loadFixture("dashboards/datadog/kubernetes-capacity-planning.json")
        val result = translator.import(fixture)

        assertEquals("Kubernetes Capacity Planning", result.dashboard.title)
        assertEquals(6, result.dashboard.widgets.size)
        assertEquals("text", result.dashboard.widgets[0].widgetType)
        assertEquals("text", result.dashboard.widgets[1].widgetType)
        assertEquals("section", result.dashboard.widgets[2].widgetType)
        assertEquals("table", result.dashboard.widgets[3].widgetType)
        assertEquals("heatmap", result.dashboard.widgets[4].widgetType)
        assertEquals("text", result.dashboard.widgets[5].widgetType)
        assertTrue(result.warnings.any { it.contains("unsupported type 'scatterplot'") })
    }

    // ──── parseDataDogQueryString ────

    @Test
    fun `parseDataDogQueryString with avg system metric`() {
        val warnings = mutableListOf<String>()
        val dsl = translator.parseDataDogQueryString("avg:system.cpu.user{host:web01}", warnings, 0)
        assertEquals("metrics", dsl.dataSource)
        assertEquals(AggFunction.AVG, dsl.metrics[0].function)
        assertEquals("value", dsl.metrics[0].field)
        assertTrue(dsl.filters.any { it.field == "metric_name" && it.value == "system.cpu.user" })
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
        assertTrue(dsl.filters.any { it.field == "metric_name" && it.value == "system.disk.used" })
        assertTrue(dsl.filters.any { it.field == "host" && it.value == "web01" })
    }

    @Test
    fun `parseDataDogQueryString extracts supported group by fields`() {
        val warnings = mutableListOf<String>()
        val dsl = translator.parseDataDogQueryString("sum:postgresql.locks{host:web01} by {host,env}", warnings, 0)
        assertTrue(dsl.groupBy.any { it.field == "timestamp" && it.type == GroupByType.TIME })
        assertTrue(dsl.groupBy.any { it.field == "host" && it.type == GroupByType.FIELD })
        assertTrue(dsl.groupBy.none { it.field == "env" })
    }

    @Test
    fun `parseDataDogQuery handles Datadog queries array and formula aliases`() {
        val warnings = mutableListOf<String>()
        val definition = buildJsonObject {
            put("type", "query_table")
            put(
                "requests",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "queries",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("name", "query1")
                                            put("query", "sum:mysql.net.connections{host:db01}")
                                        }
                                    )
                                    add(
                                        buildJsonObject {
                                            put("name", "query2")
                                            put("query", "max:mysql.net.max_connections{host:db01}")
                                        }
                                    )
                                }
                            )
                            put(
                                "formulas",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("formula", "query1")
                                            put("alias", "connections")
                                        }
                                    )
                                    add(
                                        buildJsonObject {
                                            put("formula", "query2")
                                            put("alias", "max connections")
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }

        val queries = translator.parseDataDogQueries(definition, warnings, 0)
        assertEquals(2, queries.size)
        assertEquals("connections", queries[0].metrics[0].alias)
        assertEquals("max connections", queries[1].metrics[0].alias)
    }

    // ──── Export ────

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
                            dataSource = "metrics",
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

    // ──── buildDdQueryString ────

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

    // ──── Variable import ────

    @Test
    fun `import parses DataDog template variables`() {
        val json = buildJsonObject {
            put("title", "Test")
            put("widgets", JsonArray(emptyList()))
            put(
                "template_variables",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("name", "environment")
                            put("prefix", "env")
                            put("default", "production")
                            put(
                                "available_values",
                                buildJsonArray {
                                    add(kotlinx.serialization.json.JsonPrimitive("production"))
                                    add(kotlinx.serialization.json.JsonPrimitive("staging"))
                                }
                            )
                        }
                    )
                    add(
                        buildJsonObject {
                            put("name", "host")
                            put("prefix", "host")
                            put("default", "*")
                        }
                    )
                }
            )
        }
        val result = translator.import(json)
        assertEquals(2, result.variables.size)

        assertEquals("environment", result.variables[0].name)
        assertEquals("env:environment", result.variables[0].label)
        assertEquals("custom", result.variables[0].type)
        assertEquals("production", result.variables[0].defaultValue)
        assertEquals(listOf("production", "staging"), result.variables[0].options)

        assertEquals("host", result.variables[1].name)
        assertEquals("textbox", result.variables[1].type)
        assertEquals("*", result.variables[1].defaultValue)
    }

    @Test
    fun `import returns empty variables when no template_variables`() {
        val json = buildJsonObject {
            put("title", "Test")
            put("widgets", JsonArray(emptyList()))
        }
        val result = translator.import(json)
        assertTrue(result.variables.isEmpty())
    }

    @Test
    fun `export includes template_variables`() {
        val dashboard = com.moneat.dashboards.models.DashboardResponse(
            id = 1,
            orgId = 1,
            title = "Test",
            createdBy = 1,
            createdAt = "",
            updatedAt = "",
            variables = listOf(
                com.moneat.dashboards.models.DashboardVariable(
                    name = "env",
                    label = "host:env",
                    type = "custom",
                    defaultValue = "prod",
                    options = listOf("prod", "staging")
                )
            )
        )
        val exported = translator.export(dashboard)
        val vars = exported["template_variables"]!!.jsonArray
        assertEquals(1, vars.size)
        assertEquals("env", vars[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("host", vars[0].jsonObject["prefix"]!!.jsonPrimitive.content)
    }
}
