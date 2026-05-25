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
import com.moneat.dashboards.models.DashboardWidgetAlerts
import com.moneat.dashboards.models.DashboardWidgets
import com.moneat.mcp.models.McpContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
                    metric_index INT DEFAULT 0 NOT NULL,
                    duration_seconds INT DEFAULT 0 NOT NULL,
                    incident_severity VARCHAR(20),
                    enabled BOOLEAN DEFAULT TRUE NOT NULL,
                    notification_channels TEXT NOT NULL,
                    last_triggered_at TIMESTAMP,
                    last_value DOUBLE PRECISION,
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """.trimIndent()
            )
        }
    }

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

    private fun seedDashboard(): Long = transaction {
        exec(
            """
            INSERT INTO dashboards (
                id, org_id, project_id, title, created_by, created_at, updated_at
            ) VALUES (
                $DASHBOARD_ID, $ORG_ID, $PROJECT_ID, 'MCP Test Dashboard',
                $CREATED_BY, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        DASHBOARD_ID
    }

    private fun seedWidget(dashboardId: Long): Long = transaction {
        exec(
            """
            INSERT INTO dashboard_widgets (
                id, dashboard_id, title, widget_type, query_config, query_configs, display_config,
                created_at, updated_at
            ) VALUES (
                $WIDGET_ID, $dashboardId, 'Test Widget', 'timeseries', '{}', '[]', '{}',
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        WIDGET_ID
    }

    private fun decodeAlert(text: String): DashboardAlertResponse =
        json.decodeFromString<DashboardAlertResponse>(text)

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

    companion object {
        private var db: Database? = null
        private const val ORG_ID = 1L
        private const val CREATED_BY = 100L
        private const val PROJECT_ID = 200L
        private const val DASHBOARD_ID = 10L
        private const val WIDGET_ID = 20L
        private const val DASHBOARD_JSONB_TYPE = "com.moneat.dashboards.models.JsonbColumnType"
    }
}
