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

import com.moneat.config.ClickHouseClient
import com.moneat.analytics.models.ProductAnalyticsFunnels
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.queryBasedClickHouseHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val PRODUCT_ANALYTICS_PROJECT_RESOURCE_ID = "018f4ce4-3f2a-7a67-a32b-0c1848f62b9d"

@ResourceLock("exposed-default-database")
class ProductAnalyticsToolTest {
    private val context = McpContext(
        organizationId = 1,
        userId = 2,
        tokenId = 3,
        scopes = setOf("event:read", "project:write"),
        sessionId = "product-analytics-tool-test",
    )

    @BeforeEach
    fun setupDatabase() {
        val db = Database.connect(
            url = "jdbc:h2:mem:moneat_product_analytics_tool_${System.nanoTime()};" +
                "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.dropAndPatchJsonb(Users, Organizations, Projects, ProductAnalyticsFunnels)
        createSchema()
        transaction {
            Users.insert {
                it[id] = 2
                it[email] = "user@example.com"
                it[password_hash] = "hash"
            }
            Organizations.insert {
                it[id] = 1
                it[name] = "Test Org"
                it[slug] = "test-org"
            }
            Projects.insert {
                it[id] = 1
                it[organization_id] = 1
                it[resource_id] = Uuid.parse(PRODUCT_ANALYTICS_PROJECT_RESOURCE_ID)
                it[name] = "Bandapella Backend"
                it[slug] = "bandapella-backend"
            }
        }
    }

    private fun createSchema() {
        transaction {
            SchemaUtils.create(Users, Organizations, Projects)
            exec(
                """
                CREATE TABLE product_analytics_funnels (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    resource_id UUID NOT NULL DEFAULT RANDOM_UUID(),
                    organization_id INT NOT NULL,
                    project_id BIGINT NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    description TEXT NULL,
                    steps_json TEXT NOT NULL DEFAULT '[]',
                    filters_json TEXT NOT NULL DEFAULT '[]',
                    prop_filters_json TEXT NOT NULL DEFAULT '[]',
                    group_by VARCHAR(32) NOT NULL DEFAULT 'session_id',
                    source VARCHAR(255) NULL,
                    created_by INT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    archived_at TIMESTAMP NULL
                )
                """.trimIndent(),
            )
            exec(
                """
                CREATE UNIQUE INDEX product_analytics_funnels_org_resource_unique
                    ON product_analytics_funnels (organization_id, resource_id)
                """.trimIndent(),
            )
        }
    }

    @AfterEach
    fun tearDownClickHouse() {
        ClickHouseClient.close()
    }

    @Test
    fun `get product funnel wraps analytics funnel query`() = runBlocking {
        val queries = mutableListOf<String>()
        MockHttpServer(
            queryBasedClickHouseHandler(
                "windowFunnel" to """{"level":1,"cnt":6}
                    |{"level":2,"cnt":4}
                """.trimMargin(),
                captureQueries = queries,
            ),
        ).use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val result = registry().callTool(
                "get_product_funnel",
                JsonObject(
                    mapOf(
                        "project_id" to JsonPrimitive(PRODUCT_ANALYTICS_PROJECT_RESOURCE_ID),
                        "date_from" to JsonPrimitive("2026-06-01"),
                        "date_to" to JsonPrimitive("2026-06-30"),
                        "steps" to JsonArray(
                            listOf(
                                JsonPrimitive("signup_completed"),
                                JsonPrimitive("first_key_action"),
                            ),
                        ),
                        "group_by" to JsonPrimitive("user_id"),
                        "source" to JsonPrimitive("server"),
                        "filters" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "property" to JsonPrimitive("device_type"),
                                        "operator" to JsonPrimitive("is"),
                                        "value" to JsonPrimitive("ios"),
                                    ),
                                ),
                            ),
                        ),
                        "prop_filters" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "key" to JsonPrimitive("destination"),
                                        "operator" to JsonPrimitive("is"),
                                        "value" to JsonPrimitive("private"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                context,
            )

            assertFalse(result.isError, result.content.single().text.orEmpty())
            val response = toolJson.parseToJsonElement(result.content.single().text.orEmpty()).jsonObject
            assertEquals("user_id", response["groupBy"]!!.jsonPrimitive.content)
            assertEquals(40.0, response["overallConversion"]!!.jsonPrimitive.content.toDouble())
            assertEquals(2, response["steps"]!!.jsonArray.size)
            assertTrue(queries.single().contains("windowFunnel"))
            assertTrue(queries.single().contains("project_id = toUInt64(1)"))
            assertTrue(queries.single().contains("event_name = 'signup_completed'"))
            assertTrue(queries.single().contains("source = 'server'"))
            assertTrue(queries.single().contains("e.device_type = 'ios'"))
            assertTrue(queries.single().contains("mapContains(e.props, 'destination')"))
            assertTrue(queries.single().contains("e.props['destination'] = 'private'"))
            assertTrue(queries.single().contains("user_id != ''"))
        }
    }

    @Test
    fun `get product events applies source filters and bounded limits`() = runBlocking {
        val queries = mutableListOf<String>()
        MockHttpServer(
            queryBasedClickHouseHandler(
                "GROUP BY name" to """{"name":"record_started","visitors":3,"pageviews":8}""",
                captureQueries = queries,
            ),
        ).use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val result = registry().callTool(
                "get_product_events",
                JsonObject(
                    mapOf(
                        "project_id" to JsonPrimitive(PRODUCT_ANALYTICS_PROJECT_RESOURCE_ID),
                        "date_from" to JsonPrimitive("2026-06-01"),
                        "date_to" to JsonPrimitive("2026-06-30"),
                        "group_by" to JsonPrimitive("user_id"),
                        "source" to JsonPrimitive("server"),
                        "limit" to JsonPrimitive(999),
                        "filters" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "property" to JsonPrimitive("device_type"),
                                        "operator" to JsonPrimitive("is"),
                                        "value" to JsonPrimitive("ios"),
                                    ),
                                ),
                            ),
                        ),
                        "prop_filters" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "key" to JsonPrimitive("media_type"),
                                        "operator" to JsonPrimitive("is"),
                                        "value" to JsonPrimitive("video"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                context,
            )

            assertFalse(result.isError, result.content.single().text.orEmpty())
            val response = toolJson.parseToJsonElement(result.content.single().text.orEmpty()).jsonObject
            val event = response["events"]!!.jsonArray.single().jsonObject
            assertEquals("record_started", event["name"]!!.jsonPrimitive.content)
            assertEquals("3", event["visitors"]!!.jsonPrimitive.content)
            assertTrue(queries.single().contains("e.device_type = 'ios'"))
            assertTrue(queries.single().contains("mapContains(e.props, 'media_type')"))
            assertTrue(queries.single().contains("e.props['media_type'] = 'video'"))
            assertTrue(queries.single().contains("e.source = 'server'"))
            assertTrue(queries.single().contains("e.user_id != ''"))
            assertTrue(queries.single().contains("LIMIT 200"))
        }
    }

    @Test
    fun `get product retention returns signup cohort retention grid`() = runBlocking {
        val queries = mutableListOf<String>()
        MockHttpServer(
            queryBasedClickHouseHandler(
                "WITH cohorts AS" to
                    """{"cohort":"2026-06-01","users":10,"eligible_1":10,"retained_1":5,""" +
                    """"eligible_2":8,"retained_2":4}""",
                captureQueries = queries,
            ),
        ).use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val result = registry().callTool(
                "get_product_retention",
                JsonObject(
                    mapOf(
                        "project_id" to JsonPrimitive(PRODUCT_ANALYTICS_PROJECT_RESOURCE_ID),
                        "date_from" to JsonPrimitive("2026-06-01"),
                        "date_to" to JsonPrimitive("2026-06-30"),
                        "mode" to JsonPrimitive("custom"),
                        "custom_event" to JsonPrimitive("record_started"),
                        "period_count" to JsonPrimitive(3),
                    ),
                ),
                context,
            )

            assertFalse(result.isError, result.content.single().text.orEmpty())
            val response = toolJson.parseToJsonElement(result.content.single().text.orEmpty()).jsonObject
            assertEquals("custom", response["mode"]!!.jsonPrimitive.content)
            assertEquals(3, response["periods"]!!.jsonArray.size)
            val values = response["cohorts"]!!.jsonArray.single().jsonObject["values"]!!.jsonArray
            assertEquals("100.0", values[0].jsonPrimitive.content)
            assertEquals("50.0", values[1].jsonPrimitive.content)
            assertEquals("50.0", values[2].jsonPrimitive.content)
            assertTrue(queries.single().contains("e.event_name = 'signup_completed'"))
            assertTrue(queries.single().contains("e.event_name = 'record_started'"))
        }
    }

    @Test
    fun `product analytics tools validate arguments before ClickHouse queries`() = runBlocking {
        val badGroupBy = registry().callTool(
            "get_product_funnel",
            JsonObject(
                mapOf(
                    "project_id" to JsonPrimitive(PRODUCT_ANALYTICS_PROJECT_RESOURCE_ID),
                    "date_from" to JsonPrimitive("2026-06-01"),
                    "date_to" to JsonPrimitive("2026-06-30"),
                    "steps" to JsonArray(listOf(JsonPrimitive("signup_completed"), JsonPrimitive("activated"))),
                    "group_by" to JsonPrimitive("device_id"),
                ),
            ),
            context,
        )
        val badFilter = registry().callTool(
            "get_product_events",
            JsonObject(
                mapOf(
                    "project_id" to JsonPrimitive(PRODUCT_ANALYTICS_PROJECT_RESOURCE_ID),
                    "date_from" to JsonPrimitive("2026-06-01"),
                    "date_to" to JsonPrimitive("2026-06-30"),
                    "filters" to JsonArray(listOf(JsonPrimitive("unsupported:is:value"))),
                ),
            ),
            context,
        )
        val missingCustomEvent = registry().callTool(
            "get_product_retention",
            JsonObject(
                mapOf(
                    "project_id" to JsonPrimitive(PRODUCT_ANALYTICS_PROJECT_RESOURCE_ID),
                    "date_from" to JsonPrimitive("2026-06-01"),
                    "date_to" to JsonPrimitive("2026-06-30"),
                    "mode" to JsonPrimitive("custom"),
                ),
            ),
            context,
        )

        listOf(badGroupBy, badFilter, missingCustomEvent).forEach { result ->
            assertTrue(result.isError, result.content.single().text)
        }
        assertTrue(badGroupBy.content.single().text!!.contains("group_by must be one of"))
        assertTrue(badFilter.content.single().text!!.contains("filters[0].property must be one of"))
        assertTrue(missingCustomEvent.content.single().text!!.contains("custom_event is required"))
    }

    @Test
    fun `saved product funnel tools create list run and delete persisted funnels`() = runBlocking {
        val created = registry().callTool(
            "create_saved_product_funnel",
            JsonObject(
                mapOf(
                    "project_id" to JsonPrimitive(PRODUCT_ANALYTICS_PROJECT_RESOURCE_ID),
                    "name" to JsonPrimitive("Record to export"),
                    "steps" to JsonArray(
                        listOf(
                            JsonPrimitive("recording.started"),
                            JsonPrimitive("export.completed"),
                        ),
                    ),
                    "group_by" to JsonPrimitive("user_id"),
                    "source" to JsonPrimitive("server"),
                    "filters" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "property" to JsonPrimitive("device_type"),
                                    "operator" to JsonPrimitive("is"),
                                    "value" to JsonPrimitive("ios"),
                                ),
                            ),
                        ),
                    ),
                    "prop_filters" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "key" to JsonPrimitive("destination"),
                                    "operator" to JsonPrimitive("is"),
                                    "value" to JsonPrimitive("local_device"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            context,
        )

        assertFalse(created.isError, created.content.single().text.orEmpty())
        val createdJson = toolJson.parseToJsonElement(created.content.single().text.orEmpty()).jsonObject
        val funnelId = createdJson["id"]!!.jsonPrimitive.content
        assertEquals("Record to export", createdJson["name"]!!.jsonPrimitive.content)

        val listed = registry().callTool(
            "list_saved_product_funnels",
            JsonObject(mapOf("project_id" to JsonPrimitive(PRODUCT_ANALYTICS_PROJECT_RESOURCE_ID))),
            context,
        )
        assertFalse(listed.isError, listed.content.single().text.orEmpty())
        assertEquals(
            1,
            toolJson.parseToJsonElement(listed.content.single().text.orEmpty())
                .jsonObject["funnels"]!!
                .jsonArray
                .size,
        )

        val queries = mutableListOf<String>()
        MockHttpServer(
            queryBasedClickHouseHandler(
                "windowFunnel" to """{"level":1,"cnt":3}
                    |{"level":2,"cnt":2}
                """.trimMargin(),
                captureQueries = queries,
            ),
        ).use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val run = registry().callTool(
                "run_saved_product_funnel",
                JsonObject(
                    mapOf(
                        "funnel_id" to JsonPrimitive(funnelId),
                        "date_from" to JsonPrimitive("2026-06-01"),
                        "date_to" to JsonPrimitive("2026-06-30"),
                    ),
                ),
                context,
            )

            assertFalse(run.isError, run.content.single().text.orEmpty())
            val runJson = toolJson.parseToJsonElement(run.content.single().text.orEmpty()).jsonObject
            assertEquals(40.0, runJson["result"]!!.jsonObject["overallConversion"]!!.jsonPrimitive.content.toDouble())
            assertTrue(queries.single().contains("e.device_type = 'ios'"))
            assertTrue(queries.single().contains("mapContains(e.props, 'destination')"))
            assertTrue(queries.single().contains("e.props['destination'] = 'local_device'"))
            assertTrue(queries.single().contains("e.source = 'server'"))
        }

        val deleted = registry().callTool(
            "delete_saved_product_funnel",
            JsonObject(mapOf("funnel_id" to JsonPrimitive(funnelId))),
            context,
        )
        assertFalse(deleted.isError, deleted.content.single().text.orEmpty())
        assertEquals(
            "true",
            toolJson.parseToJsonElement(deleted.content.single().text.orEmpty())
                .jsonObject["deleted"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `feature flag funnel comparison joins flag variants to analytics identities`() = runBlocking {
        val registry = registry()
        val savedFunnel = registry.callTool(
            "create_saved_product_funnel",
            JsonObject(
                mapOf(
                    "project_id" to JsonPrimitive(PRODUCT_ANALYTICS_PROJECT_RESOURCE_ID),
                    "name" to JsonPrimitive("Export gate experiment"),
                    "steps" to JsonArray(
                        listOf(
                            JsonPrimitive("recording.started"),
                            JsonPrimitive("export.completed"),
                        ),
                    ),
                    "group_by" to JsonPrimitive("user_id"),
                    "source" to JsonPrimitive("server"),
                    "filters" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "property" to JsonPrimitive("device_type"),
                                    "operator" to JsonPrimitive("is"),
                                    "value" to JsonPrimitive("ios"),
                                ),
                            ),
                        ),
                    ),
                    "prop_filters" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "key" to JsonPrimitive("destination"),
                                    "operator" to JsonPrimitive("is"),
                                    "value" to JsonPrimitive("private"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            context,
        )
        assertFalse(savedFunnel.isError, savedFunnel.content.single().text.orEmpty())
        val funnelId = toolJson.parseToJsonElement(savedFunnel.content.single().text.orEmpty())
            .jsonObject["id"]!!
            .jsonPrimitive
            .content
        val queries = mutableListOf<String>()
        MockHttpServer(
            queryBasedClickHouseHandler(
                "flag_assignments" to """{"variant_key":"control","level":1,"cnt":5,"evaluations":12,"unique_targets":9}
                    |{"variant_key":"control","level":2,"cnt":4,"evaluations":12,"unique_targets":9}
                    |{"variant_key":"treatment","level":1,"cnt":3,"evaluations":14,"unique_targets":10}
                    |{"variant_key":"treatment","level":2,"cnt":7,"evaluations":14,"unique_targets":10}
                """.trimMargin(),
                captureQueries = queries,
            ),
        ).use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val result = registry.callTool(
                "compare_product_funnel_by_feature_flag",
                JsonObject(
                    mapOf(
                        "funnel_id" to JsonPrimitive(funnelId),
                        "date_from" to JsonPrimitive("2026-06-01"),
                        "date_to" to JsonPrimitive("2026-06-30"),
                        "flag_key" to JsonPrimitive("paywall.export_gate"),
                        "environment" to JsonPrimitive("production"),
                    ),
                ),
                context,
            )

            assertFalse(result.isError, result.content.single().text.orEmpty())
            val response = toolJson.parseToJsonElement(result.content.single().text.orEmpty()).jsonObject
            val variants = response["variants"]!!.jsonArray
            assertEquals(2, variants.size)
            assertEquals("treatment", variants[0].jsonObject["variantKey"]!!.jsonPrimitive.content)
            assertEquals(70.0, variants[0].jsonObject["overallConversion"]!!.jsonPrimitive.content.toDouble())
            assertTrue(response["identityCaveat"]!!.jsonPrimitive.content.contains("targeting_key"))
            assertTrue(queries.single().contains("FROM feature_flag_evaluations"))
            assertTrue(queries.single().contains("argMax(variant_key, event_time)"))
            assertTrue(queries.single().contains("INNER JOIN flag_assignments AS f ON f.targeting_key = e.user_id"))
            assertTrue(queries.single().contains("e.source = 'server'"))
            assertTrue(queries.single().contains("e.device_type = 'ios'"))
            assertTrue(queries.single().contains("mapContains(e.props, 'destination')"))
            assertTrue(queries.single().contains("e.props['destination'] = 'private'"))
        }
    }

    private fun registry(): McpToolRegistry =
        McpToolRegistry().also { registry ->
            registry.register(GetProductFunnelTool())
            registry.register(GetProductEventsTool())
            registry.register(GetProductRetentionTool())
            registry.register(ListSavedProductFunnelsTool())
            registry.register(GetSavedProductFunnelTool())
            registry.register(CreateSavedProductFunnelTool())
            registry.register(UpdateSavedProductFunnelTool())
            registry.register(DeleteSavedProductFunnelTool())
            registry.register(RunSavedProductFunnelTool())
            registry.register(CompareProductFunnelByFeatureFlagTool())
        }
}
