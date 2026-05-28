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

import com.moneat.billing.models.PricingTierConfigs
import com.moneat.config.ClickHouseClient
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.McpTool
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpToolValidationTest {
    companion object {
        private const val CONTENT_TYPE_TEXT_PLAIN = "text/plain"
        private const val EVENT_ID = "01234567-89ab-cdef-0123-456789abcdef"
        private const val TRACE_ID = "trace-1"
        private var db: Database? = null
    }

    private val context = McpContext(
        organizationId = 1,
        userId = 2,
        tokenId = 3,
        scopes = setOf("project:write"),
        sessionId = "validation-test",
    )

    @BeforeEach
    fun setupDatabase() {
        db = db ?: Database.connect(
            url = "jdbc:h2:mem:moneat_mcp_tool_validation;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Organizations,
            Projects,
            Subscriptions,
            PricingTierConfigs,
        )
        transaction {
            Organizations.insert {
                it[id] = 1
                it[name] = "Test Org"
                it[slug] = "test-org"
            }
            Projects.insert {
                it[id] = 1
                it[organization_id] = 1
                it[name] = "Test Project"
                it[slug] = "test-project"
            }
        }
    }

    @Test
    fun `tools return validation errors before calling services`() = runBlocking {
        validationCases().forEach { case ->
            val result = case.tool.execute(case.args, context)

            assertTrue(result.isError, "${case.name} should be an error")
            assertTrue(result.content.isNotEmpty(), "${case.name} should include validation error content")
            val errorText = result.content.first().text.orEmpty()
            assertTrue(
                errorText.contains(case.expectedError),
                "${case.name} expected '${case.expectedError}' but got '$errorText'"
            )
        }
    }

    @Test
    fun `getTrace tool reports not found for invalid event id`() = runBlocking {
        val result = GetTraceTool().execute(obj("event_id" to "not-a-uuid"), context)

        assertTrue(result.isError)
        assertTrue(result.content.first().text.orEmpty().contains("Trace not found"))
    }

    @Test
    fun `getTrace tool returns trace by event id`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("event_type,") -> {
                    exchange.respond(
                        200,
                        """{"event_id":"$EVENT_ID","event_type":"error","project_id":1,"trace_id":"$TRACE_ID"}""",
                        CONTENT_TYPE_TEXT_PLAIN
                    )
                }

                query.contains("FROM `test`.apm_spans") && query.contains("trace_id_hex = '$TRACE_ID'") -> {
                    exchange.respond(200, spanRow(), CONTENT_TYPE_TEXT_PLAIN)
                }

                else -> exchange.respond(200, "", CONTENT_TYPE_TEXT_PLAIN)
            }
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val result = GetTraceTool().execute(obj("event_id" to EVENT_ID), context)

            assertFalse(result.isError)
            assertTrue(result.content.first().text.orEmpty().contains("TrimFrameWorker"))
        }
    }

    @Test
    fun `getTrace tool returns trace by trace id and project id`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            if (query.contains("FROM `test`.apm_spans") && query.contains("trace_id_hex = '$TRACE_ID'")) {
                exchange.respond(200, spanRow(), CONTENT_TYPE_TEXT_PLAIN)
            } else {
                exchange.respond(200, "", CONTENT_TYPE_TEXT_PLAIN)
            }
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val result = GetTraceTool().execute(
                obj("trace_id" to TRACE_ID, "project_id" to 1),
                context
            )

            assertFalse(result.isError)
            assertTrue(result.content.first().text.orEmpty().contains(TRACE_ID))
        }
    }

    @Test
    fun `getIssueTransactions tool returns correlated transaction rows`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("FROM `test`.issues") && query.contains("WHERE issue_id = 'issue-1'") -> {
                    exchange.respond(200, """{"project_id":1}""", CONTENT_TYPE_TEXT_PLAIN)
                }

                query.contains("event_type = 'transaction'") && query.contains("issue_id = 'issue-1'") -> {
                    exchange.respond(
                        200,
                        """{"event_id":"txn-1","name":"ProjectWorkChain","op":"project.workchain",""" +
                            """"duration":1250.0,"event_ts":"2026-02-03T00:00:00.000Z",""" +
                            """"status":"internal_error","trace_id":"$TRACE_ID"}""",
                        CONTENT_TYPE_TEXT_PLAIN
                    )
                }

                else -> exchange.respond(200, "", CONTENT_TYPE_TEXT_PLAIN)
            }
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val result = GetIssueTransactionsTool().execute(obj("issue_id" to "issue-1"), context)

            assertFalse(result.isError)
            assertTrue(result.content.first().text.orEmpty().contains("txn-1"))
        }
    }

    private fun validationCases(): List<ValidationCase> {
        val uuid = "11111111-1111-1111-1111-111111111111"
        return listOf(
            case("create_status_page_name", CreateStatusPageTool(), obj(), "name is required"),
            case("create_status_page_slug", CreateStatusPageTool(), obj("name" to "Status"), "slug is required"),
            case(
                "create_status_page_public",
                CreateStatusPageTool(),
                obj("name" to "Status", "slug" to "status", "is_public" to "yes"),
                "is_public must be true or false",
            ),
            case("get_status_page_id", GetStatusPageTool(), obj(), "page_id is required"),
            case("get_status_page_uuid", GetStatusPageTool(), obj("page_id" to "bad"), "Invalid page_id format"),
            case("update_status_page_id", UpdateStatusPageTool(), obj(), "page_id is required"),
            case(
                "update_status_page_public",
                UpdateStatusPageTool(),
                obj("page_id" to uuid, "is_public" to "yes"),
                "is_public must be true or false",
            ),
            case("add_status_monitor_id", AddStatusPageMonitorTool(), obj("page_id" to uuid), "monitor_id is required"),
            case(
                "add_status_monitor_uuid",
                AddStatusPageMonitorTool(),
                obj("page_id" to uuid, "monitor_id" to "bad"),
                "monitor_id must be a valid UUID",
            ),
            case("create_incident_title", CreateStatusPageIncidentTool(), obj("page_id" to uuid), "title is required"),
            case(
                "create_incident_status",
                CreateStatusPageIncidentTool(),
                obj("page_id" to uuid, "title" to "Outage", "status" to "bad"),
                "status must be one of",
            ),
            case(
                "create_incident_impact",
                CreateStatusPageIncidentTool(),
                obj(
                    "page_id" to uuid,
                    "title" to "Outage",
                    "status" to "investigating",
                    "impact" to "bad",
                ),
                "impact must be one of",
            ),
            case(
                "create_incident_message",
                CreateStatusPageIncidentTool(),
                obj("page_id" to uuid, "title" to "Outage", "status" to "investigating"),
                "message is required",
            ),
            case(
                "update_incident_uuid",
                UpdateStatusPageIncidentTool(),
                obj("page_id" to uuid, "incident_id" to "bad"),
                "Invalid incident_id format",
            ),
            case(
                "post_incident_status",
                PostIncidentUpdateTool(),
                obj("page_id" to uuid, "incident_id" to uuid),
                "status is required",
            ),
            case("update_dashboard_id", UpdateDashboardTool(), obj(), "dashboard_id is required"),
            case("update_dashboard_fields", UpdateDashboardTool(), obj("dashboard_id" to 1), "At least one"),
            case("delete_dashboard_id", DeleteDashboardTool(), obj(), "dashboard_id is required"),
            case(
                "create_dashboard_widget_type",
                CreateDashboardWidgetTool(),
                obj("dashboard_id" to 1),
                "widget_type is required",
            ),
            case(
                "create_dashboard_widget_unknown_type",
                CreateDashboardWidgetTool(),
                obj("dashboard_id" to 1, "widget_type" to "bad"),
                "Unknown widget_type",
            ),
            case(
                "update_dashboard_widget_id",
                UpdateDashboardWidgetTool(),
                obj("dashboard_id" to 1),
                "widget_id is required",
            ),
            case(
                "update_dashboard_widget_fields",
                UpdateDashboardWidgetTool(),
                obj("dashboard_id" to 1, "widget_id" to 2),
                "At least one widget field",
            ),
            case(
                "delete_dashboard_widget_id",
                DeleteDashboardWidgetTool(),
                obj("dashboard_id" to 1),
                "widget_id is required",
            ),
            case(
                "preview_dashboard_widget_query_config",
                PreviewDashboardWidgetQueryTool(),
                obj("dashboard_id" to 1),
                "query_config must be an object",
            ),
            case(
                "replace_dashboard_widgets_expected",
                ReplaceDashboardWidgetsTool(),
                obj("dashboard_id" to 1, "widgets" to emptyList<String>()),
                "expected_widget_count is required",
            ),
            case(
                "create_dashboard_alert_widget",
                CreateDashboardAlertTool(),
                obj("dashboard_id" to 1),
                "widget_id is required",
            ),
            case(
                "create_dashboard_alert_threshold",
                CreateDashboardAlertTool(),
                obj(
                    "dashboard_id" to 1,
                    "widget_id" to 2,
                    "name" to "CPU",
                    "condition" to "gt",
                    "threshold" to "bad",
                ),
                "threshold must be a number",
            ),
            case(
                "create_dashboard_alert_duration",
                CreateDashboardAlertTool(),
                obj(
                    "dashboard_id" to 1,
                    "widget_id" to 2,
                    "name" to "CPU",
                    "condition" to "gt",
                    "threshold" to 90,
                    "duration_seconds" to "bad",
                ),
                "duration_seconds must be a valid integer",
            ),
            case(
                "update_dashboard_alert_id",
                UpdateDashboardAlertTool(),
                obj("dashboard_id" to 1),
                "alert_id is required",
            ),
            case(
                "update_dashboard_alert_enabled",
                UpdateDashboardAlertTool(),
                obj("dashboard_id" to 1, "alert_id" to 2, "enabled" to "yes"),
                "enabled must be true or false",
            ),
            case(
                "update_dashboard_alert_fields",
                UpdateDashboardAlertTool(),
                obj("dashboard_id" to 1, "alert_id" to 2),
                "At least one field",
            ),
            case(
                "delete_dashboard_alert_id",
                DeleteDashboardAlertTool(),
                obj("dashboard_id" to 1),
                "alert_id is required",
            ),
            case("create_alert_host", CreateAlertTool(), obj(), "host_id is required"),
            case("create_alert_host_format", CreateAlertTool(), obj("host_id" to "bad"), "Invalid host_id format"),
            case("create_alert_metric", CreateAlertTool(), obj("host_id" to 1), "metric is required"),
            case(
                "create_alert_threshold",
                CreateAlertTool(),
                obj("host_id" to 1, "metric" to "cpu", "condition" to "gt", "threshold" to "bad"),
                "threshold must be a number",
            ),
            case("update_alert_alert_id", UpdateAlertTool(), obj("host_id" to 1), "alert_id is required"),
            case("delete_alert_alert_id", DeleteAlertTool(), obj("host_id" to 1), "alert_id is required"),
            case("create_silence_start", CreateSilencePeriodTool(), obj(), "starts_at is required"),
            case(
                "create_silence_order",
                CreateSilencePeriodTool(),
                obj("starts_at" to 10, "ends_at" to 5),
                "starts_at must be before",
            ),
            case("delete_silence_id", DeleteSilencePeriodTool(), obj(), "id is required"),
            case("delete_host_id", DeleteHostTool(), obj(), "host_id is required"),
            case("update_uptime_id", UpdateUptimeMonitorTool(), obj(), "monitor_id is required"),
            case(
                "update_uptime_interval",
                UpdateUptimeMonitorTool(),
                obj("monitor_id" to uuid, "interval_seconds" to "bad"),
                "interval_seconds must be a valid integer",
            ),
            case(
                "delete_uptime_uuid",
                DeleteUptimeMonitorTool(),
                obj("monitor_id" to "bad"),
                "Invalid monitor_id format",
            ),
            case(
                "pause_uptime_uuid",
                PauseUptimeMonitorTool(),
                obj("monitor_id" to "bad"),
                "Invalid monitor_id format",
            ),
            case(
                "resume_uptime_uuid",
                ResumeUptimeMonitorTool(),
                obj("monitor_id" to "bad"),
                "Invalid monitor_id format",
            ),
            case(
                "get_heartbeats_uuid",
                GetMonitorHeartbeatsTool(),
                obj("monitor_id" to "bad"),
                "Invalid monitor_id format",
            ),
            case("create_uptime_name", CreateUptimeMonitorTool(), obj(), "name is required"),
            case("create_uptime_url", CreateUptimeMonitorTool(), obj("name" to "API"), "url is required"),
            case("get_trace_lookup", GetTraceTool(), obj(), "event_id or trace_id is required"),
            case(
                "get_trace_project_id",
                GetTraceTool(),
                obj("trace_id" to TRACE_ID),
                "project_id is required when trace_id is used without event_id",
            ),
            case("create_datasource_name", CreateDataSourceTool(), obj(), "name is required"),
            case(
                "create_datasource_type",
                CreateDataSourceTool(),
                obj("name" to "Warehouse"),
                "source_type is required",
            ),
            case("datasource_schema_id", GetDataSourceSchemaTool(), obj(), "datasource_id is required"),
            case(
                "execute_datasource_query",
                ExecuteDataSourceQueryTool(),
                obj("datasource_id" to 1),
                "query is required",
            ),
            case("notification_issue", UpdateNotificationPreferencesTool(), obj(), "issue_alerts is required"),
            case(
                "notification_frequency",
                UpdateNotificationPreferencesTool(),
                obj(
                    "issue_alerts" to true,
                    "error_alerts" to true,
                    "weekly_summary" to true,
                    "alert_frequency_minutes" to 0,
                ),
                "alert_frequency_minutes must be >= 1",
            ),
            case("alert_channels_source", UpdateAlertNotificationChannelsTool(), obj(), "alert_source is required"),
            case(
                "alert_channels_invalid_source",
                UpdateAlertNotificationChannelsTool(),
                obj("alert_source" to "unknown"),
                "invalid alert_source",
            ),
            case(
                "alert_channels_email",
                UpdateAlertNotificationChannelsTool(),
                obj("alert_source" to "host_alert", "email_enabled" to "yes"),
                "email_enabled must be a boolean",
            ),
            case("create_feature_flag_key", CreateFeatureFlagTool(), obj(), "key is required"),
            case(
                "create_feature_flag_value_type",
                CreateFeatureFlagTool(),
                obj("key" to "checkout.enabled", "name" to "Checkout Enabled"),
                "value_type must be one of",
            ),
            case(
                "create_feature_flag_variants",
                CreateFeatureFlagTool(),
                obj(
                    "key" to "checkout.enabled",
                    "name" to "Checkout Enabled",
                    "value_type" to "BOOLEAN",
                ),
                "variants is required",
            ),
            case(
                "create_feature_flag_variants_format",
                CreateFeatureFlagTool(),
                obj(
                    "key" to "checkout.enabled",
                    "name" to "Checkout Enabled",
                    "value_type" to "BOOLEAN",
                    "variants" to "bad",
                    "client_visible" to "yes",
                ),
                "variants must be an array",
            ),
        )
    }

    private fun case(
        name: String,
        tool: McpTool,
        args: JsonObject,
        expectedError: String,
    ): ValidationCase = ValidationCase(name, tool, args, expectedError)

    private fun obj(vararg entries: Pair<String, Any>): JsonObject =
        JsonObject(entries.associate { (key, value) -> key to primitive(value) })

    private fun primitive(value: Any): JsonElement = when (value) {
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun spanRow(): String =
        """{"span_id":"s1","parent_span_id":"","trace_id":"$TRACE_ID",""" +
            """"meta":{"sentry.project_id":"1"},"op":"worker",""" +
            """"description":"TrimFrameWorker","start_ns":"1000000000",""" +
            """"duration_ns":"500000000","error":1}"""

    private data class ValidationCase(
        val name: String,
        val tool: McpTool,
        val args: JsonObject,
        val expectedError: String,
    )
}
