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
import com.moneat.dashboards.translation.GrafanaTranslator
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

class GrafanaTranslatorTest {

    private val translator = GrafanaTranslator()

    // --- Import ---

    @Test
    fun `import extracts dashboard title`() {
        val json = buildJsonObject {
            put("title", "My Grafana Dashboard")
            put("panels", JsonArray(emptyList()))
        }
        val result = translator.import(json)
        assertEquals("My Grafana Dashboard", result.dashboard.title)
    }

    @Test
    fun `import uses default title when missing`() {
        val json = buildJsonObject {
            put("panels", JsonArray(emptyList()))
        }
        val result = translator.import(json)
        assertEquals("Imported Grafana Dashboard", result.dashboard.title)
    }

    @Test
    fun `import maps timeseries panel type`() {
        val json = buildJsonObject {
            put("title", "Test")
            put("panels", buildJsonArray {
                add(buildJsonObject {
                    put("type", "timeseries")
                    put("title", "CPU Over Time")
                    put("targets", buildJsonArray {
                        add(buildJsonObject {
                            put("expr", "rate(node_cpu_seconds_total{mode=\"idle\"})")
                        })
                    })
                    put("gridPos", buildJsonObject {
                        put("x", 0); put("y", 0); put("w", 12); put("h", 8)
                    })
                })
            })
        }
        val result = translator.import(json)
        assertEquals(1, result.dashboard.widgets.size)
        assertEquals("timeseries", result.dashboard.widgets[0].widgetType)
    }

    @Test
    fun `import maps barchart to bar`() {
        val json = buildJsonObject {
            put("title", "Test")
            put("panels", buildJsonArray {
                add(buildJsonObject {
                    put("type", "barchart")
                    put("targets", JsonArray(emptyList()))
                })
            })
        }
        val result = translator.import(json)
        assertEquals("bar", result.dashboard.widgets[0].widgetType)
    }

    @Test
    fun `import maps piechart to donut`() {
        val json = buildJsonObject {
            put("title", "Test")
            put("panels", buildJsonArray {
                add(buildJsonObject {
                    put("type", "piechart")
                    put("targets", JsonArray(emptyList()))
                })
            })
        }
        val result = translator.import(json)
        assertEquals("donut", result.dashboard.widgets[0].widgetType)
    }

    @Test
    fun `import maps gauge to stat`() {
        val json = buildJsonObject {
            put("title", "Test")
            put("panels", buildJsonArray {
                add(buildJsonObject {
                    put("type", "gauge")
                    put("targets", JsonArray(emptyList()))
                })
            })
        }
        val result = translator.import(json)
        assertEquals("stat", result.dashboard.widgets[0].widgetType)
    }

    @Test
    fun `import unsupported type produces warning`() {
        val json = buildJsonObject {
            put("title", "Test")
            put("panels", buildJsonArray {
                add(buildJsonObject {
                    put("type", "flamegraph")
                    put("targets", JsonArray(emptyList()))
                })
            })
        }
        val result = translator.import(json)
        assertEquals("text", result.dashboard.widgets[0].widgetType)
        assertTrue(result.warnings.any { it.contains("unsupported type") })
    }

    @Test
    fun `import scales 24-col grid to 12-col`() {
        val json = buildJsonObject {
            put("title", "Test")
            put("panels", buildJsonArray {
                add(buildJsonObject {
                    put("type", "stat")
                    put("targets", JsonArray(emptyList()))
                    put("gridPos", buildJsonObject {
                        put("x", 12); put("y", 0); put("w", 12); put("h", 6)
                    })
                })
            })
        }
        val result = translator.import(json)
        val w = result.dashboard.widgets[0]
        assertEquals(6, w.gridX)  // 12/2 = 6
        assertEquals(6, w.gridW)  // (12+1)/2 = 6
        assertEquals(0, w.gridY)
        assertEquals(6, w.gridH)
    }

    @Test
    fun `import handles missing gridPos`() {
        val json = buildJsonObject {
            put("title", "Test")
            put("panels", buildJsonArray {
                add(buildJsonObject {
                    put("type", "stat")
                    put("targets", JsonArray(emptyList()))
                })
            })
        }
        val result = translator.import(json)
        assertEquals(0, result.dashboard.widgets[0].gridX)
    }

    // --- parseGrafanaTargets ---

    @Test
    fun `parseGrafanaTargets with PromQL expr`() {
        val panel = buildJsonObject {
            put("targets", buildJsonArray {
                add(buildJsonObject {
                    put("expr", "rate(node_cpu_seconds_total{mode=\"idle\"})")
                })
            })
        }
        val warnings = mutableListOf<String>()
        val dsl = translator.parseGrafanaTargets(panel, warnings, 0)
        assertEquals("system_metrics", dsl.dataSource)
        assertEquals("cpu_percent", dsl.metrics[0].field)
    }

    @Test
    fun `parseGrafanaTargets with rawSql`() {
        val panel = buildJsonObject {
            put("targets", buildJsonArray {
                add(buildJsonObject {
                    put("rawSql", "SELECT count() FROM events WHERE level = 'error'")
                })
            })
        }
        val warnings = mutableListOf<String>()
        val dsl = translator.parseGrafanaTargets(panel, warnings, 0)
        assertEquals("events", dsl.dataSource)
        assertTrue(dsl.rawQuery != null)
    }

    @Test
    fun `parseGrafanaTargets with no targets uses defaults`() {
        val panel = buildJsonObject {
            put("targets", JsonArray(emptyList()))
        }
        val warnings = mutableListOf<String>()
        val dsl = translator.parseGrafanaTargets(panel, warnings, 0)
        assertEquals("events", dsl.dataSource)
        assertEquals(AggFunction.COUNT, dsl.metrics[0].function)
    }

    // --- parsePromQL ---

    @Test
    fun `parsePromQL with rate function`() {
        val warnings = mutableListOf<String>()
        val dsl = translator.parsePromQL("rate(http_requests_total{status=\"200\"})", warnings, 0)
        assertEquals("spans", dsl.dataSource)
        assertEquals(AggFunction.AVG, dsl.metrics[0].function)
        assertTrue(dsl.filters.any { it.field == "status" && it.value == "200" })
    }

    @Test
    fun `parsePromQL with sum function`() {
        val warnings = mutableListOf<String>()
        val dsl = translator.parsePromQL("sum(container_memory_usage_bytes{})", warnings, 0)
        assertEquals("container_metrics", dsl.dataSource)
        assertEquals(AggFunction.SUM, dsl.metrics[0].function)
    }

    @Test
    fun `parsePromQL with unparseable expression stores rawQuery`() {
        val warnings = mutableListOf<String>()
        val dsl = translator.parsePromQL("some_complex_expression", warnings, 0)
        assertTrue(dsl.rawQuery != null)
        assertTrue(warnings.any { it.contains("couldn't parse") })
    }

    // --- Export ---

    @Test
    fun `export generates valid Grafana JSON structure`() {
        val dashboard = DashboardResponse(
            id = 1, orgId = 1, title = "Test", createdBy = 1,
            createdAt = "", updatedAt = "",
            widgets = listOf(
                WidgetResponse(
                    id = 1, dashboardId = 1, title = "CPU",
                    widgetType = "timeseries",
                    gridX = 0, gridY = 0, gridW = 6, gridH = 4,
                    queryConfig = QueryDsl(
                        dataSource = "system_metrics",
                        metrics = listOf(MetricDef(AggFunction.AVG, "cpu_percent", "avg_cpu")),
                        groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "1 HOUR"))
                    )
                )
            )
        )
        val exported = translator.export(dashboard)
        assertEquals("Test", exported["title"]?.jsonPrimitive?.content)
        assertEquals(39, exported["schemaVersion"]?.jsonPrimitive?.int)
        val panels = exported["panels"]!!.jsonArray
        assertEquals(1, panels.size)
    }

    @Test
    fun `export scales 12-col grid to 24-col`() {
        val dashboard = DashboardResponse(
            id = 1, orgId = 1, title = "Test", createdBy = 1,
            createdAt = "", updatedAt = "",
            widgets = listOf(
                WidgetResponse(
                    id = 1, dashboardId = 1,
                    widgetType = "stat",
                    gridX = 3, gridY = 0, gridW = 6, gridH = 4,
                    queryConfig = QueryDsl(dataSource = "events")
                )
            )
        )
        val exported = translator.export(dashboard)
        val gridPos = exported["panels"]!!.jsonArray[0].jsonObject["gridPos"]!!.jsonObject
        assertEquals(6, gridPos["x"]!!.jsonPrimitive.int)   // 3 * 2
        assertEquals(12, gridPos["w"]!!.jsonPrimitive.int)   // 6 * 2
    }

    @Test
    fun `export maps toplist to table in Grafana`() {
        val dashboard = DashboardResponse(
            id = 1, orgId = 1, title = "Test", createdBy = 1,
            createdAt = "", updatedAt = "",
            widgets = listOf(
                WidgetResponse(
                    id = 1, dashboardId = 1, widgetType = "toplist",
                    queryConfig = QueryDsl(dataSource = "events")
                )
            )
        )
        val exported = translator.export(dashboard)
        val panelType = exported["panels"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content
        assertEquals("table", panelType)
    }

    // --- buildGrafanaSql ---

    @Test
    fun `buildGrafanaSql generates SQL with time bucket`() {
        val dsl = QueryDsl(
            dataSource = "events",
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
            groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "1 HOUR"))
        )
        val sql = translator.buildGrafanaSql(dsl)
        assertContains(sql, "toStartOfInterval")
        assertContains(sql, "FROM events")
        assertContains(sql, "GROUP BY time_bucket")
        assertContains(sql, "ORDER BY time_bucket ASC")
    }

    @Test
    fun `buildGrafanaSql uses rawQuery when available`() {
        val dsl = QueryDsl(
            dataSource = "events",
            rawQuery = "SELECT * FROM events LIMIT 10"
        )
        val sql = translator.buildGrafanaSql(dsl)
        assertEquals("SELECT * FROM events LIMIT 10", sql)
    }

    @Test
    fun `buildGrafanaSql includes filters in WHERE clause`() {
        val dsl = QueryDsl(
            dataSource = "logs",
            metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
            filters = listOf(FilterDef("level", FilterOp.EQ, "error"))
        )
        val sql = translator.buildGrafanaSql(dsl)
        assertContains(sql, "WHERE")
        assertContains(sql, "level")
    }

    @Test
    fun `import and export roundtrip preserves title`() {
        val original = buildJsonObject {
            put("title", "Roundtrip Test")
            put("panels", buildJsonArray {
                add(buildJsonObject {
                    put("type", "stat")
                    put("title", "Request Count")
                    put("targets", buildJsonArray {
                        add(buildJsonObject {
                            put("expr", "sum(http_requests_total{})")
                        })
                    })
                    put("gridPos", buildJsonObject {
                        put("x", 0); put("y", 0); put("w", 24); put("h", 8)
                    })
                })
            })
        }
        val imported = translator.import(original)
        val exported = translator.export(imported.dashboard)
        assertEquals("Roundtrip Test", exported["title"]?.jsonPrimitive?.content)
    }

    // --- Row panel handling ---

    @Test
    fun `import skips row panels without warnings`() {
        val json = buildJsonObject {
            put("title", "Test")
            put("panels", buildJsonArray {
                add(buildJsonObject {
                    put("type", "row")
                    put("title", "Section Header")
                })
                add(buildJsonObject {
                    put("type", "stat")
                    put("targets", JsonArray(emptyList()))
                })
            })
        }
        val result = translator.import(json)
        assertEquals(1, result.dashboard.widgets.size)
        assertEquals("stat", result.dashboard.widgets[0].widgetType)
        assertTrue(result.warnings.none { it.contains("row") })
    }

    @Test
    fun `import flattens nested panels from collapsed rows`() {
        val json = buildJsonObject {
            put("title", "Test")
            put("panels", buildJsonArray {
                add(buildJsonObject {
                    put("type", "row")
                    put("title", "Collapsed Section")
                    put("panels", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "timeseries")
                            put("title", "Nested Panel")
                            put("targets", buildJsonArray {
                                add(buildJsonObject {
                                    put("expr", "rate(node_cpu_seconds_total{mode=\"idle\"})")
                                })
                            })
                            put("gridPos", buildJsonObject {
                                put("x", 0); put("y", 0); put("w", 24); put("h", 8)
                            })
                        })
                    })
                })
            })
        }
        val result = translator.import(json)
        assertEquals(1, result.dashboard.widgets.size)
        assertEquals("timeseries", result.dashboard.widgets[0].widgetType)
        assertEquals("Nested Panel", result.dashboard.widgets[0].title)
    }

    // --- PromQL with range vectors ---

    @Test
    fun `parsePromQL with range vector duration`() {
        val warnings = mutableListOf<String>()
        val dsl = translator.parsePromQL(
            "rate(app_quality_feedback_total{response=\"good\"}[1h])",
            warnings,
            0
        )
        // Should parse successfully, not fall through to rawQuery
        assertTrue(warnings.isEmpty(), "Expected no warnings, got: $warnings")
        assertEquals(AggFunction.AVG, dsl.metrics[0].function) // rate → AVG
        assertTrue(dsl.filters.any { it.field == "response" && it.value == "good" })
    }

    @Test
    fun `parsePromQL with rate and 5m range vector`() {
        val warnings = mutableListOf<String>()
        val dsl = translator.parsePromQL(
            "rate(http_requests_total{status=\"200\"}[5m])",
            warnings,
            0
        )
        assertTrue(warnings.isEmpty(), "Expected no warnings, got: $warnings")
        assertEquals("spans", dsl.dataSource)
        assertEquals(AggFunction.AVG, dsl.metrics[0].function)
    }

    @Test
    fun `parsePromQL with irate`() {
        val warnings = mutableListOf<String>()
        val dsl = translator.parsePromQL(
            "irate(node_network_receive_bytes_total{device=\"eth0\"}[5m])",
            warnings,
            0
        )
        assertTrue(warnings.isEmpty(), "Expected no warnings, got: $warnings")
        assertEquals("system_metrics", dsl.dataSource)
        assertEquals("net_recv_bytes", dsl.metrics[0].field)
    }

    // --- Complex real-world dashboard ---

    @Test
    fun `import complex dashboard with mixed panel types`() {
        val json = buildJsonObject {
            put("title", "Application Overview")
            put("description", "Real-time application monitoring")
            put("panels", buildJsonArray {
                // Row panel (should be skipped)
                add(buildJsonObject {
                    put("type", "row")
                    put("title", "Overview")
                    put("gridPos", buildJsonObject {
                        put("x", 0); put("y", 0); put("w", 24); put("h", 1)
                    })
                })
                // Stat panel with PromQL + range vector
                add(buildJsonObject {
                    put("type", "stat")
                    put("title", "Feedback Rate")
                    put("targets", buildJsonArray {
                        add(buildJsonObject {
                            put("expr", "rate(app_quality_feedback_total{response=\"good\"}[1h])")
                            put("datasource", buildJsonObject {
                                put("type", "prometheus"); put("uid", "prom-1")
                            })
                        })
                    })
                    put("gridPos", buildJsonObject {
                        put("x", 0); put("y", 1); put("w", 6); put("h", 4)
                    })
                })
                // Timeseries with simple PromQL
                add(buildJsonObject {
                    put("type", "timeseries")
                    put("title", "Request Rate")
                    put("targets", buildJsonArray {
                        add(buildJsonObject {
                            put("expr", "rate(http_requests_total{method=\"GET\"}[5m])")
                        })
                    })
                    put("gridPos", buildJsonObject {
                        put("x", 6); put("y", 1); put("w", 18); put("h", 8)
                    })
                })
                // Row panel with collapsed nested panels
                add(buildJsonObject {
                    put("type", "row")
                    put("title", "Database")
                    put("panels", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "table")
                            put("title", "Slow Queries")
                            put("targets", buildJsonArray {
                                add(buildJsonObject {
                                    put("rawSql", "SELECT query, avg(duration_ms) as avg_duration FROM app_queries WHERE duration_ms > 1000 GROUP BY query ORDER BY avg_duration DESC LIMIT 20")
                                })
                            })
                            put("gridPos", buildJsonObject {
                                put("x", 0); put("y", 10); put("w", 24); put("h", 8)
                            })
                        })
                    })
                })
                // Another row (should be skipped)
                add(buildJsonObject {
                    put("type", "row")
                    put("title", "Infrastructure")
                })
                // Table with custom data source SQL
                add(buildJsonObject {
                    put("type", "table")
                    put("title", "Session Data")
                    put("targets", buildJsonArray {
                        add(buildJsonObject {
                            put("rawSql", "SELECT user_id, count() as sessions FROM app_sessions GROUP BY user_id ORDER BY sessions DESC LIMIT 50")
                        })
                    })
                    put("gridPos", buildJsonObject {
                        put("x", 0); put("y", 20); put("w", 24); put("h", 6)
                    })
                })
            })
        }
        val result = translator.import(json)

        // Row panels skipped, nested panels flattened
        assertEquals(4, result.dashboard.widgets.size)

        // Panel 0 (stat) - should parse PromQL with range vector
        assertEquals("stat", result.dashboard.widgets[0].widgetType)
        assertEquals("Feedback Rate", result.dashboard.widgets[0].title)
        assertEquals(0, result.dashboard.widgets[0].gridX)
        assertEquals(3, result.dashboard.widgets[0].gridW)  // 6/2 = 3

        // Panel 1 (timeseries) - PromQL with range vector
        assertEquals("timeseries", result.dashboard.widgets[1].widgetType)
        assertEquals("Request Rate", result.dashboard.widgets[1].title)

        // Panel 2 (nested table from collapsed row) - SQL with custom table
        assertEquals("table", result.dashboard.widgets[2].widgetType)
        assertEquals("Slow Queries", result.dashboard.widgets[2].title)
        assertTrue(result.dashboard.widgets[2].queryConfig.rawQuery?.contains("duration_ms") == true)

        // Panel 3 (table with SQL from custom app_sessions table)
        assertEquals("table", result.dashboard.widgets[3].widgetType)

        // Warnings: SQL queries and unknown tables
        assertTrue(result.warnings.any { it.contains("rawQuery") })
        assertTrue(result.warnings.none { it.contains("row") })
    }
}
