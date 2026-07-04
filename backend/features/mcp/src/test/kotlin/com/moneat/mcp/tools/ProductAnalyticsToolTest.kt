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
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
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
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val PRODUCT_ANALYTICS_PROJECT_RESOURCE_ID = "018f4ce4-3f2a-7a67-a32b-0c1848f62b9d"

class ProductAnalyticsToolTest {
    companion object {
        private var db: Database? = null
    }

    private val context = McpContext(
        organizationId = 1,
        userId = 2,
        tokenId = 3,
        scopes = setOf("event:read"),
        sessionId = "product-analytics-tool-test",
    )

    @BeforeEach
    fun setupDatabase() {
        db = db ?: Database.connect(
            url = "jdbc:h2:mem:moneat_product_analytics_tool;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Organizations, Projects)
        transaction {
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

    private fun registry(): McpToolRegistry =
        McpToolRegistry().also { registry ->
            registry.register(GetProductFunnelTool())
            registry.register(GetProductEventsTool())
            registry.register(GetProductRetentionTool())
        }
}
