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

package com.moneat.mcp.tools

import com.moneat.dashboards.models.DashboardAlertResponse
import com.moneat.dashboards.models.DashboardResponse
import com.moneat.dashboards.models.DashboardWidgetAlerts
import com.moneat.dashboards.models.DashboardWidgets
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.McpTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardCrudToolTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val context = McpContext(
        organizationId = ORG_ID.toInt(),
        userId = CREATED_BY.toInt(),
        tokenId = 1,
        scopes = setOf("project:write"),
        sessionId = "dashboard-crud-tool-test"
    )

    // ──── Setup ────

    @BeforeEach
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_mcp_dashboard_crud_tool;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db

        transaction {
            exec("DROP TABLE IF EXISTS dashboard_widget_alerts")
            exec("DROP TABLE IF EXISTS dashboard_widgets")
            exec("DROP TABLE IF EXISTS dashboard_favorites")
            exec("DROP TABLE IF EXISTS custom_data_sources")
            exec("DROP TABLE IF EXISTS dashboards")
            patchJsonbForH2(DashboardWidgets, DashboardWidgetAlerts)
            exec(
                """
                CREATE TABLE dashboards (
                    id BIGSERIAL PRIMARY KEY,
                    org_id BIGINT NOT NULL,
                    project_id BIGINT,
                    folder_id BIGINT,
                    title VARCHAR(255) NOT NULL,
                    description TEXT,
                    layout_type VARCHAR(20) DEFAULT 'grid' NOT NULL,
                    is_default BOOLEAN DEFAULT FALSE NOT NULL,
                    variables TEXT DEFAULT '[]' NOT NULL,
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE dashboard_favorites (
                    user_id INT NOT NULL,
                    dashboard_id BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (user_id, dashboard_id)
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE custom_data_sources (
                    id BIGSERIAL PRIMARY KEY,
                    org_id BIGINT NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    description TEXT,
                    source_type VARCHAR(50) NOT NULL,
                    host VARCHAR(512) NOT NULL,
                    port INT,
                    database_name VARCHAR(255),
                    encrypted_credentials TEXT NOT NULL,
                    extra_config TEXT NOT NULL,
                    enabled BOOLEAN DEFAULT TRUE NOT NULL,
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE dashboard_widgets (
                    id BIGSERIAL PRIMARY KEY,
                    dashboard_id BIGINT NOT NULL,
                    title VARCHAR(255),
                    widget_type VARCHAR(50) NOT NULL,
                    grid_x INT DEFAULT 0 NOT NULL,
                    grid_y INT DEFAULT 0 NOT NULL,
                    grid_w INT DEFAULT 6 NOT NULL,
                    grid_h INT DEFAULT 4 NOT NULL,
                    query_config TEXT NOT NULL,
                    query_configs TEXT NOT NULL,
                    display_config TEXT NOT NULL,
                    sort_order INT DEFAULT 0 NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE dashboard_widget_alerts (
                    id BIGSERIAL PRIMARY KEY,
                    widget_id BIGINT NOT NULL,
                    dashboard_id BIGINT NOT NULL,
                    org_id BIGINT NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    condition VARCHAR(5) NOT NULL,
                    threshold DOUBLE PRECISION NOT NULL,
                    warning_threshold DOUBLE PRECISION,
                    metric_index INT DEFAULT 0 NOT NULL,
                    duration_seconds INT DEFAULT 0 NOT NULL,
                    incident_severity VARCHAR(20),
                    enabled BOOLEAN DEFAULT TRUE NOT NULL,
                    notification_channels TEXT NOT NULL,
                    last_triggered_at TIMESTAMP,
                    last_triggered_level VARCHAR(20),
                    last_value DOUBLE PRECISION,
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """.trimIndent()
            )
        }
    }

    // ──── Tests ────

    @Test
    fun `create dashboard alert accepts MCP condition and severity aliases`() = runBlocking {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        val result = CreateDashboardAlertTool().execute(
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "widget_id" to JsonPrimitive(widgetId),
                    "name" to JsonPrimitive("CPU high"),
                    "condition" to JsonPrimitive("gt"),
                    "threshold" to JsonPrimitive(0.85),
                    "duration_seconds" to JsonPrimitive(300),
                    "incident_severity" to JsonPrimitive("P0")
                )
            ),
            context
        )

        val response = decodeAlert(result.content.first().text!!)
        assertFalse(result.isError)
        assertEquals(">", response.condition)
        assertEquals("CRITICAL", response.incidentSeverity)
        assertEquals(300, response.durationSeconds)
    }

    @Test
    fun `update dashboard alert can update duration severity and gte condition`() = runBlocking {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)
        val created = decodeAlert(
            CreateDashboardAlertTool().execute(
                JsonObject(
                    mapOf(
                        "dashboard_id" to JsonPrimitive(dashboardId),
                        "widget_id" to JsonPrimitive(widgetId),
                        "name" to JsonPrimitive("Heap high"),
                        "condition" to JsonPrimitive("gt"),
                        "threshold" to JsonPrimitive(0.85)
                    )
                ),
                context
            ).content.first().text!!
        )

        val result = UpdateDashboardAlertTool().execute(
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "alert_id" to JsonPrimitive(created.id),
                    "condition" to JsonPrimitive("gte"),
                    "threshold" to JsonPrimitive(0.9),
                    "duration_seconds" to JsonPrimitive(600),
                    "incident_severity" to JsonPrimitive("P1"),
                    "enabled" to JsonPrimitive(false)
                )
            ),
            context
        )

        val response = decodeAlert(result.content.first().text!!)
        assertFalse(result.isError)
        assertEquals(">=", response.condition)
        assertEquals(0.9, response.threshold)
        assertEquals(600, response.durationSeconds)
        assertEquals("HIGH", response.incidentSeverity)
        assertFalse(response.enabled)
    }

    @Test
    fun `dashboard alert schemas advertise aliases they accept`() {
        val createConditionEnum = enumValues(CreateDashboardAlertTool().inputSchema.properties, "condition")
        val updateSeverityEnum = enumValues(UpdateDashboardAlertTool().inputSchema.properties, "incident_severity")

        assertTrue("gte" in createConditionEnum)
        assertTrue(">=" in createConditionEnum)
        assertTrue("P0" in updateSeverityEnum)
        assertTrue("P1" in updateSeverityEnum)
        assertTrue("CRITICAL" in updateSeverityEnum)
    }

    @Test
    fun `create dashboard alert rejects unknown condition aliases`() = runBlocking {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        val result = CreateDashboardAlertTool().execute(
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "widget_id" to JsonPrimitive(widgetId),
                    "name" to JsonPrimitive("CPU high"),
                    "condition" to JsonPrimitive("above"),
                    "threshold" to JsonPrimitive(0.85)
                )
            ),
            context
        )

        assertTrue(result.isError)
        assertEquals(
            "Unknown dashboard alert condition: above",
            result.content.first().text,
        )
    }

    @Test
    fun `create dashboard alert rejects unknown severity aliases`() = runBlocking {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        val result = CreateDashboardAlertTool().execute(
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "widget_id" to JsonPrimitive(widgetId),
                    "name" to JsonPrimitive("CPU high"),
                    "condition" to JsonPrimitive("gt"),
                    "threshold" to JsonPrimitive(0.85),
                    "incident_severity" to JsonPrimitive("SEV0")
                )
            ),
            context
        )

        assertTrue(result.isError)
        assertEquals(
            "Unknown dashboard alert severity: SEV0",
            result.content.first().text,
        )
    }

    @Test
    fun `create dashboard alert accepts remaining condition and severity aliases`() = runBlocking {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)
        val conditionCases = listOf(
            "lt" to "<",
            "eq" to "==",
            "lte" to "<=",
            "<" to "<",
            "==" to "==",
            ">=" to ">=",
        )
        val severityCases = listOf(
            "P2" to "MEDIUM",
            "MEDIUM" to "MEDIUM",
            "P3" to "LOW",
            "P4" to "LOW",
            "P5" to "LOW",
            "LOW" to "LOW",
            "HIGH" to "HIGH",
            "CRITICAL" to "CRITICAL",
        )

        severityCases.forEachIndexed { index, severityCase ->
            val conditionCase = conditionCases[index % conditionCases.size]
            val result = CreateDashboardAlertTool().execute(
                JsonObject(
                    mapOf(
                        "dashboard_id" to JsonPrimitive(dashboardId),
                        "widget_id" to JsonPrimitive(widgetId),
                        "name" to JsonPrimitive("Alias alert $index"),
                        "condition" to JsonPrimitive(conditionCase.first),
                        "threshold" to JsonPrimitive(index.toDouble()),
                        "incident_severity" to JsonPrimitive(severityCase.first)
                    )
                ),
                context
            )

            val response = decodeAlert(result.content.first().text!!)
            assertFalse(result.isError)
            assertEquals(conditionCase.second, response.condition)
            assertEquals(severityCase.second, response.incidentSeverity)
        }
    }

    @Test
    fun `update dashboard alert rejects invalid validation inputs`() = runBlocking {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)
        val created = decodeAlert(
            CreateDashboardAlertTool().execute(
                JsonObject(
                    mapOf(
                        "dashboard_id" to JsonPrimitive(dashboardId),
                        "widget_id" to JsonPrimitive(widgetId),
                        "name" to JsonPrimitive("Heap high"),
                        "condition" to JsonPrimitive("gt"),
                        "threshold" to JsonPrimitive(0.85)
                    )
                ),
                context
            ).content.first().text!!
        )
        val baseArgs: Map<String, JsonElement> = mapOf(
            "dashboard_id" to JsonPrimitive(dashboardId),
            "alert_id" to JsonPrimitive(created.id),
        )
        val cases: List<Pair<Map<String, JsonElement>, String>> = listOf(
            mapOf("duration_seconds" to JsonPrimitive("soon")) to
                "duration_seconds must be a valid integer",
            mapOf("condition" to JsonPrimitive("above")) to
                "Unknown dashboard alert condition: above",
            mapOf("incident_severity" to JsonPrimitive("SEV0")) to
                "Unknown dashboard alert severity: SEV0",
            emptyMap<String, JsonElement>() to
                "At least one field must be provided to update",
        )

        cases.forEach { (extraArgs, expectedMessage) ->
            val result = UpdateDashboardAlertTool().execute(
                JsonObject(baseArgs + extraArgs),
                context
            )

            assertTrue(result.isError)
            assertEquals(expectedMessage, result.content.first().text)
        }
    }

    @Test
    fun `create dashboard widget appends below existing widgets`() = runBlocking {
        val dashboardId = seedDashboard()
        seedWidget(dashboardId)

        val result = CreateDashboardWidgetTool().execute(
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "title" to JsonPrimitive("Memory"),
                    "widget_type" to JsonPrimitive("stat"),
                    "query_configs" to JsonArray(listOf(queryDslJson("metrics"))),
                    "display_config" to JsonObject(mapOf("unit" to JsonPrimitive("bytes"))),
                )
            ),
            context
        )

        val dashboard = decodeDashboard(result.content.first().text!!)
        val created = dashboard.widgets.single { it.title == "Memory" }
        assertFalse(result.isError)
        assertEquals(2, dashboard.widgets.size)
        assertEquals("stat", created.widgetType)
        assertEquals(4, created.gridY)
        assertEquals("bytes", created.displayConfig["unit"])
        assertEquals("metrics", created.queryConfigs.single().dataSource)
    }

    @Test
    fun `update dashboard widget patches target and preserves sibling widgets`() = runBlocking {
        val dashboardId = seedDashboard()
        val firstWidgetId = seedWidget(dashboardId, title = "CPU")
        val secondWidgetId = seedWidget(dashboardId, widgetId = WIDGET_ID + 1, title = "Latency", sortOrder = 1)

        val result = UpdateDashboardWidgetTool().execute(
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "widget_id" to JsonPrimitive(firstWidgetId),
                    "title" to JsonPrimitive("CPU load"),
                    "widget_type" to JsonPrimitive("gauge"),
                    "grid_w" to JsonPrimitive(3),
                    "query_configs" to JsonArray(listOf(queryDslJson("spans"))),
                )
            ),
            context
        )

        val dashboard = decodeDashboard(result.content.first().text!!)
        val updated = dashboard.widgets.single { it.id == firstWidgetId }
        val untouched = dashboard.widgets.single { it.id == secondWidgetId }
        assertFalse(result.isError)
        assertEquals("CPU load", updated.title)
        assertEquals("gauge", updated.widgetType)
        assertEquals(3, updated.gridW)
        assertEquals("spans", updated.queryConfigs.single().dataSource)
        assertEquals("Latency", untouched.title)
        assertEquals("timeseries", untouched.widgetType)
    }

    @Test
    fun `delete dashboard widget removes only the target widget`() = runBlocking {
        val dashboardId = seedDashboard()
        val firstWidgetId = seedWidget(dashboardId, title = "CPU")
        val secondWidgetId = seedWidget(dashboardId, widgetId = WIDGET_ID + 1, title = "Latency", sortOrder = 1)

        val result = DeleteDashboardWidgetTool().execute(
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "widget_id" to JsonPrimitive(firstWidgetId),
                )
            ),
            context
        )

        val dashboard = decodeDashboard(result.content.first().text!!)
        assertFalse(result.isError)
        assertEquals(listOf(secondWidgetId), dashboard.widgets.map { it.id })
        assertEquals("Latency", dashboard.widgets.single().title)
    }

    @Test
    fun `replace dashboard widgets rejects stale expected widget count`() = runBlocking {
        val dashboardId = seedDashboard()
        seedWidget(dashboardId)

        val result = ReplaceDashboardWidgetsTool().execute(
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "expected_widget_count" to JsonPrimitive(2),
                    "widgets" to JsonArray(emptyList()),
                )
            ),
            context
        )

        assertTrue(result.isError)
        assertEquals(
            "Dashboard has 1 widgets but expected_widget_count is 2. " +
                "Read the dashboard first to get current state.",
            result.content.first().text,
        )
    }

    @Test
    fun `replace dashboard widgets swaps all widgets when expected count matches`() = runBlocking {
        val dashboardId = seedDashboard()
        seedWidget(dashboardId, title = "CPU")

        val result = ReplaceDashboardWidgetsTool().execute(
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "expected_widget_count" to JsonPrimitive(1),
                    "widgets" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "title" to JsonPrimitive("Requests"),
                                    "widget_type" to JsonPrimitive("bar"),
                                    "grid_x" to JsonPrimitive(1),
                                    "grid_y" to JsonPrimitive(2),
                                    "grid_w" to JsonPrimitive(8),
                                    "grid_h" to JsonPrimitive(3),
                                    "query_configs" to JsonArray(listOf(queryDslJson("logs"))),
                                    "display_config" to JsonObject(mapOf("unit" to JsonPrimitive("count"))),
                                    "sort_order" to JsonPrimitive(7),
                                )
                            )
                        )
                    ),
                )
            ),
            context
        )

        val dashboard = decodeDashboard(result.content.first().text!!)
        val replacement = dashboard.widgets.single()
        assertFalse(result.isError)
        assertEquals("Requests", replacement.title)
        assertEquals("bar", replacement.widgetType)
        assertEquals(2, replacement.gridY)
        assertEquals(8, replacement.gridW)
        assertEquals(7, replacement.sortOrder)
        assertEquals("logs", replacement.queryConfigs.single().dataSource)
        assertEquals("count", replacement.displayConfig["unit"])
    }

    @Test
    fun `preview dashboard widget query rejects mismatched project scope`() = runBlocking {
        val dashboardId = seedDashboard()

        val result = PreviewDashboardWidgetQueryTool().execute(
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "project_id" to JsonPrimitive(PROJECT_ID + 1),
                    "query_config" to queryDslJson("metrics"),
                )
            ),
            context
        )

        assertTrue(result.isError)
        assertEquals("Dashboard is scoped to project $PROJECT_ID", result.content.first().text)
    }

    @Test
    fun `preview dashboard widget query requires project id for unscoped dashboards`() = runBlocking {
        val dashboardId = seedDashboard(projectId = null)

        val result = PreviewDashboardWidgetQueryTool().execute(
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "query_config" to queryDslJson("metrics"),
                )
            ),
            context
        )

        assertTrue(result.isError)
        assertEquals("project_id is required when dashboard is not scoped to a project", result.content.first().text)
    }

    @Test
    fun `preview dashboard widget query validates custom data source id after substitutions`() = runBlocking {
        val dashboardId = seedDashboard()

        val result = PreviewDashboardWidgetQueryTool().execute(
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "query_config" to queryDslJson("custom:bad", rawQuery = "up{service=\"\$service\"}"),
                    "variables" to JsonObject(mapOf("service" to JsonPrimitive("api"))),
                    "time_range" to JsonObject(
                        mapOf(
                            "from" to JsonPrimitive("now-1h"),
                            "to" to JsonPrimitive("now"),
                        )
                    ),
                )
            ),
            context
        )

        assertTrue(result.isError)
        assertEquals("Invalid custom data source ID", result.content.first().text)
    }

    @Test
    fun `preview dashboard widget query reports missing custom data source`() = runBlocking {
        val dashboardId = seedDashboard()

        val result = PreviewDashboardWidgetQueryTool().execute(
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "query_config" to queryDslJson("custom:123", rawQuery = "up"),
                )
            ),
            context
        )

        assertTrue(result.isError)
        assertEquals("Data source not found", result.content.first().text)
    }

    @Test
    fun `preview dashboard widget query resolves prometheus alias`() = runBlocking {
        val dashboardId = seedDashboard()
        seedCustomDataSource(sourceType = "prometheus")

        val result = PreviewDashboardWidgetQueryTool().execute(
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "query_config" to queryDslJson("__prometheus", rawQuery = "up"),
                )
            ),
            context
        )

        assertTrue(result.isError)
        assertEquals("Failed to decrypt credentials", result.content.first().text)
    }

    @Test
    fun `preview dashboard widget query skips built in raw query execution`() = runBlocking {
        val dashboardId = seedDashboard()

        val result = PreviewDashboardWidgetQueryTool().execute(
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "query_config" to queryDslJson("metrics", rawQuery = "SELECT 1"),
                )
            ),
            context
        )

        assertFalse(result.isError)
        assertEquals("[]", result.content.first().text)
    }

    @Test
    fun `dashboard widget tools report missing dashboards and widgets`() = runBlocking {
        val dashboardId = seedDashboard()

        assertToolError(
            UpdateDashboardWidgetTool(),
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "widget_id" to JsonPrimitive(WIDGET_ID),
                    "title" to JsonPrimitive("CPU"),
                )
            ),
            "Widget not found on dashboard: $WIDGET_ID"
        )
        assertToolError(
            DeleteDashboardWidgetTool(),
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "widget_id" to JsonPrimitive(WIDGET_ID),
                )
            ),
            "Widget not found on dashboard: $WIDGET_ID"
        )
        assertToolError(
            ReplaceDashboardWidgetsTool(),
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(DASHBOARD_ID + 1),
                    "expected_widget_count" to JsonPrimitive(0),
                    "widgets" to JsonArray(emptyList()),
                )
            ),
            "Dashboard not found: ${DASHBOARD_ID + 1}"
        )
        assertToolError(
            PreviewDashboardWidgetQueryTool(),
            JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(DASHBOARD_ID + 1),
                    "query_config" to queryDslJson("metrics"),
                )
            ),
            "Dashboard not found: ${DASHBOARD_ID + 1}"
        )
    }

    @Test
    fun `dashboard widget tools reject malformed argument types`() = runBlocking {
        val dashboardId = seedDashboard()
        seedWidget(dashboardId)

        val baseCreateArgs = mapOf(
            "dashboard_id" to JsonPrimitive(dashboardId),
            "widget_type" to JsonPrimitive("stat"),
        )

        val cases = listOf(
            ToolCase(
                CreateDashboardWidgetTool(),
                JsonObject(baseCreateArgs + ("grid_x" to JsonObject(emptyMap()))),
                "grid_x must be a valid integer",
            ),
            ToolCase(
                CreateDashboardWidgetTool(),
                JsonObject(baseCreateArgs + ("title" to JsonObject(emptyMap()))),
                "title must be a string",
            ),
            ToolCase(
                CreateDashboardWidgetTool(),
                JsonObject(baseCreateArgs + ("query_configs" to JsonObject(emptyMap()))),
                "query_configs must be an array",
            ),
            ToolCase(
                CreateDashboardWidgetTool(),
                JsonObject(baseCreateArgs + ("display_config" to JsonArray(emptyList()))),
                "display_config must be an object",
            ),
            ToolCase(
                PreviewDashboardWidgetQueryTool(),
                JsonObject(
                    mapOf(
                        "dashboard_id" to JsonPrimitive(dashboardId),
                        "query_config" to JsonObject(emptyMap()),
                    )
                ),
                "Invalid query_config:",
            ),
            ToolCase(
                PreviewDashboardWidgetQueryTool(),
                JsonObject(
                    mapOf(
                        "dashboard_id" to JsonPrimitive(dashboardId),
                        "query_config" to queryDslJson("metrics"),
                        "variables" to JsonArray(emptyList()),
                    )
                ),
                "variables must be an object",
            ),
            ToolCase(
                PreviewDashboardWidgetQueryTool(),
                JsonObject(
                    mapOf(
                        "dashboard_id" to JsonPrimitive(dashboardId),
                        "query_config" to queryDslJson("metrics"),
                        "variables" to JsonObject(mapOf("service" to JsonObject(emptyMap()))),
                    )
                ),
                "variables.service must be a string",
            ),
            ToolCase(
                PreviewDashboardWidgetQueryTool(),
                JsonObject(
                    mapOf(
                        "dashboard_id" to JsonPrimitive(dashboardId),
                        "query_config" to queryDslJson("metrics"),
                        "time_range" to JsonArray(emptyList()),
                    )
                ),
                "time_range must be an object",
            ),
            ToolCase(
                ReplaceDashboardWidgetsTool(),
                JsonObject(
                    mapOf(
                        "dashboard_id" to JsonPrimitive(dashboardId),
                        "expected_widget_count" to JsonPrimitive(1),
                        "widgets" to JsonObject(emptyMap()),
                    )
                ),
                "widgets must be an array",
            ),
            ToolCase(
                ReplaceDashboardWidgetsTool(),
                JsonObject(
                    mapOf(
                        "dashboard_id" to JsonPrimitive(dashboardId),
                        "expected_widget_count" to JsonPrimitive(1),
                        "widgets" to JsonArray(
                            listOf(JsonObject(mapOf("widget_type" to JsonPrimitive("bad"))))
                        ),
                    )
                ),
                "Unknown widget_type: bad",
            ),
        )

        cases.forEach { case ->
            assertToolError(case.tool, case.args, case.expectedMessage)
        }
    }

    // ──── Helpers ────

    private data class ToolCase(
        val tool: McpTool,
        val args: JsonObject,
        val expectedMessage: String,
    )

    private suspend fun assertToolError(tool: McpTool, args: JsonObject, expectedMessage: String) {
        val result = tool.execute(args, context)

        assertTrue(result.isError)
        assertTrue(
            result.content.first().text!!.startsWith(expectedMessage),
            "Expected '${result.content.first().text}' to start with '$expectedMessage'",
        )
    }

    private fun seedDashboard(projectId: Long? = PROJECT_ID): Long = transaction {
        val projectValue = projectId?.toString() ?: "NULL"
        exec(
            """
            INSERT INTO dashboards (
                id, org_id, project_id, title, created_by, created_at, updated_at
            ) VALUES (
                $DASHBOARD_ID, $ORG_ID, $projectValue, 'MCP Test Dashboard',
                $CREATED_BY, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        DASHBOARD_ID
    }

    private fun seedWidget(
        dashboardId: Long,
        widgetId: Long = WIDGET_ID,
        title: String = "Test Widget",
        widgetType: String = "timeseries",
        gridY: Int = 0,
        gridH: Int = 4,
        sortOrder: Int = 0,
    ): Long = transaction {
        exec(
            """
            INSERT INTO dashboard_widgets (
                id, dashboard_id, title, widget_type, query_config, query_configs, display_config,
                grid_y, grid_h, sort_order, created_at, updated_at
            ) VALUES (
                $widgetId, $dashboardId, '$title', '$widgetType', '{}', '[]', '{}',
                $gridY, $gridH, $sortOrder,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        widgetId
    }

    private fun seedCustomDataSource(
        sourceId: Long = DATA_SOURCE_ID,
        sourceType: String,
    ): Long = transaction {
        exec(
            """
            INSERT INTO custom_data_sources (
                id, org_id, name, source_type, host, encrypted_credentials, extra_config,
                enabled, created_by, created_at, updated_at
            ) VALUES (
                $sourceId, $ORG_ID, 'Prometheus', '$sourceType', 'prometheus.local',
                'not-encrypted', '{}', TRUE, $CREATED_BY, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        sourceId
    }

    private fun decodeAlert(text: String): DashboardAlertResponse =
        json.decodeFromString<DashboardAlertResponse>(text)

    private fun decodeDashboard(text: String): DashboardResponse =
        json.decodeFromString<DashboardResponse>(text)

    private fun queryDslJson(dataSource: String, rawQuery: String? = null): JsonObject {
        val fields = mutableMapOf<String, JsonElement>("dataSource" to JsonPrimitive(dataSource))
        if (rawQuery != null) {
            fields["rawQuery"] = JsonPrimitive(rawQuery)
        }
        return JsonObject(fields)
    }

    private fun enumValues(properties: JsonObject, property: String): List<String> {
        val values = properties[property]!!.jsonObject["enum"] as JsonArray
        return values.jsonArray.map { it.jsonPrimitive.content }
    }

    private fun patchJsonbForH2(vararg tables: Table) {
        val h2TextJson = object : ColumnType<String>() {
            override fun sqlType(): String = "TEXT"
            override fun valueFromDB(value: Any): String = when (value) {
                is String -> value
                else -> value.toString()
            }

            override fun notNullValueToDB(value: String): Any = value
            override fun nonNullValueToString(value: String): String =
                "'${value.replace("'", "''")}'"
        }
        val field = Column::class.java.getDeclaredField("columnType")
        field.isAccessible = true
        tables.flatMap { it.columns }.forEach { column ->
            if (column.columnType::class.qualifiedName == DASHBOARD_JSONB_TYPE) {
                field.set(column, h2TextJson)
            }
        }
    }

    // ──── Companion ────

    companion object {
        private var db: Database? = null
        private const val ORG_ID = 1L
        private const val CREATED_BY = 100L
        private const val PROJECT_ID = 200L
        private const val DASHBOARD_ID = 10L
        private const val WIDGET_ID = 20L
        private const val DATA_SOURCE_ID = 30L
        private const val DASHBOARD_JSONB_TYPE = "com.moneat.dashboards.models.JsonbColumnType"
    }
}
