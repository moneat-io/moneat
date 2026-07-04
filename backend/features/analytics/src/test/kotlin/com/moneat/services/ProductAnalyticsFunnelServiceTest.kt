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

package com.moneat.services

import com.moneat.analytics.models.AnalyticsFilter
import com.moneat.analytics.models.EventPropertyFilter
import com.moneat.analytics.models.FunnelResponse
import com.moneat.analytics.models.FunnelStep
import com.moneat.analytics.models.ProductAnalyticsFunnels
import com.moneat.analytics.models.SavedProductFunnelCreateRequest
import com.moneat.analytics.models.SavedProductFunnelUpdateRequest
import com.moneat.analytics.services.FeatureFlagFunnelComparisonDefinition
import com.moneat.analytics.services.AnalyticsFunnelQuery
import com.moneat.analytics.services.AnalyticsService
import com.moneat.analytics.services.ProductAnalyticsFunnelService
import com.moneat.config.ClickHouseClient
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.queryBasedClickHouseHandler
import kotlinx.coroutines.runBlocking
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val FUNNEL_PROJECT_RESOURCE_ID = "018f4ce4-3f2a-7a67-a32b-0c1848f62b9d"

@ResourceLock("exposed-default-database")
class ProductAnalyticsFunnelServiceTest {
    private val service = ProductAnalyticsFunnelService()

    @BeforeEach
    fun setupDatabase() {
        val db = Database.connect(
            url = "jdbc:h2:mem:moneat_product_analytics_funnel_service_${System.nanoTime()};" +
                "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.dropAndPatchJsonb(Users, Organizations, Projects, ProductAnalyticsFunnels)
        createSchema()
        seedProject()
    }

    @AfterEach
    fun tearDownClickHouse() {
        ClickHouseClient.close()
    }

    @Test
    fun `create list update and delete funnel round trips serialized definition`() {
        val created = service.createFunnel(
            organizationId = 1,
            actorUserId = 2,
            request = createRequest(),
        )

        assertEquals(FUNNEL_PROJECT_RESOURCE_ID, created.projectId)
        assertEquals("Activation", created.name)
        assertEquals("Signup to first recording", created.description)
        assertEquals(listOf("signup_completed", "recording_created"), created.steps)
        assertEquals("user_id", created.groupBy)
        assertEquals("app", created.source)
        assertEquals("device_type", created.filters.single().property)
        assertEquals("destination", created.propFilters.single().key)

        val listed = service.listFunnels(organizationId = 1, projectId = 1)
        assertEquals(listOf(created.id), listed.funnels.map { it.id })

        val loaded = service.getFunnel(organizationId = 1, funnelResourceId = Uuid.parse(created.id))
        assertEquals(created.id, loaded?.id)

        val updated = service.updateFunnel(
            organizationId = 1,
            funnelResourceId = Uuid.parse(created.id),
            request = SavedProductFunnelUpdateRequest(
                name = "Activation Updated",
                description = " ",
                steps = listOf("signup_completed", "paywall_viewed", "subscription_started"),
                filters = emptyList(),
                propFilters = listOf(EventPropertyFilter("plan", "contains", "pro")),
                groupBy = "session_id",
                source = "",
            ),
        )

        assertNotNull(updated)
        assertEquals("Activation Updated", updated.name)
        assertNull(updated.description)
        assertEquals(listOf("signup_completed", "paywall_viewed", "subscription_started"), updated.steps)
        assertTrue(updated.filters.isEmpty())
        assertEquals("plan", updated.propFilters.single().key)
        assertEquals("session_id", updated.groupBy)
        assertNull(updated.source)

        val deleted = service.deleteFunnel(organizationId = 1, funnelResourceId = Uuid.parse(created.id))
        assertTrue(deleted.deleted)
        assertTrue(service.listFunnels(organizationId = 1, projectId = 1).funnels.isEmpty())
        assertNull(service.getFunnel(organizationId = 1, funnelResourceId = Uuid.parse(created.id)))
    }

    @Test
    fun `saved funnel operations validate ownership and definitions`() {
        assertTrue(service.listFunnels(organizationId = 99, projectId = 1).funnels.isEmpty())

        assertFailsWith<IllegalArgumentException> {
            service.createFunnel(
                organizationId = 1,
                actorUserId = 2,
                request = createRequest(name = " "),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.createFunnel(
                organizationId = 1,
                actorUserId = 2,
                request = createRequest(steps = listOf("signup_completed")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.createFunnel(
                organizationId = 1,
                actorUserId = 2,
                request = createRequest(groupBy = "account_id"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.createFunnel(
                organizationId = 99,
                actorUserId = 2,
                request = createRequest(),
            )
        }

        val missing = service.deleteFunnel(organizationId = 1, funnelResourceId = Uuid.random())
        assertFalse(missing.deleted)
        assertNull(
            service.updateFunnel(
                organizationId = 1,
                funnelResourceId = Uuid.random(),
                request = SavedProductFunnelUpdateRequest(name = "Missing"),
            ),
        )
    }

    @Test
    fun `run saved funnel delegates stored definition to analytics service`() = runBlocking {
        val analyticsService = mockk<AnalyticsService>()
        val service = ProductAnalyticsFunnelService(analyticsService)
        val created = service.createFunnel(
            organizationId = 1,
            actorUserId = 2,
            request = createRequest(),
        )
        val response = FunnelResponse(
            steps = listOf(
                FunnelStep("signup_completed", visitors = 12, dropoff = 0.0, conversionRate = 100.0),
                FunnelStep("recording_created", visitors = 5, dropoff = 58.3, conversionRate = 41.7),
            ),
            overallConversion = 41.7,
        )
        coEvery {
            analyticsService.getFunnel(
                projectId = 1,
                query = any<AnalyticsFunnelQuery>(),
            )
        } returns response

        val result = service.runFunnel(
            organizationId = 1,
            funnelResourceId = Uuid.parse(created.id),
            dateFrom = LocalDate.of(2026, 6, 1),
            dateTo = LocalDate.of(2026, 6, 30),
        )

        assertNotNull(result)
        assertEquals(created.id, result.funnel.id)
        assertEquals("2026-06-01", result.dateFrom)
        assertEquals("2026-06-30", result.dateTo)
        assertEquals(41.7, result.result.overallConversion)
        coVerify {
            analyticsService.getFunnel(
                projectId = 1,
                query = match {
                    it.steps == created.steps &&
                        it.groupBy == "user_id" &&
                        it.source == "app" &&
                        it.filters == created.filters &&
                        it.propFilters == created.propFilters
                },
            )
        }
        assertNull(
            service.runFunnel(
                organizationId = 99,
                funnelResourceId = Uuid.parse(created.id),
                dateFrom = LocalDate.of(2026, 6, 1),
                dateTo = LocalDate.of(2026, 6, 30),
            ),
        )
    }

    @Test
    fun `compare funnel by feature flag maps variants and applies filters`() = runBlocking {
        val queries = mutableListOf<String>()
        MockHttpServer(
            queryBasedClickHouseHandler(
                "feature_flag_evaluations" to """
                    {"variant_key":"control","level":1,"cnt":6,"evaluations":12,"unique_targets":10}
                    {"variant_key":"control","level":2,"cnt":4,"evaluations":12,"unique_targets":10}
                    {"variant_key":"enabled","level":1,"cnt":2,"evaluations":9,"unique_targets":8}
                    {"variant_key":"enabled","level":2,"cnt":6,"evaluations":9,"unique_targets":8}
                """.trimIndent(),
                captureQueries = queries,
            ),
        ).use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val result = service.compareFunnelByFeatureFlag(
                organizationId = 1,
                definition = FeatureFlagFunnelComparisonDefinition(
                    projectId = 1,
                    dateFrom = LocalDate.of(2026, 6, 1),
                    dateTo = LocalDate.of(2026, 6, 30),
                    steps = listOf("recording_started", "export_completed"),
                    groupBy = "user_id",
                    source = "app",
                    filters = listOf(AnalyticsFilter("device_type", "is", "ios")),
                    propFilters = listOf(EventPropertyFilter("plan", "contains", "pro")),
                    flagKey = "free_export_gate",
                    environment = "production",
                ),
            )

            assertEquals("free_export_gate", result.flagKey)
            assertEquals("production", result.environment)
            assertEquals("user_id", result.groupBy)
            assertEquals("app", result.source)
            assertEquals(listOf("control", "enabled"), result.variants.map { it.variantKey })
            assertEquals(40.0, result.variants[0].overallConversion)
            assertEquals(75.0, result.variants[1].overallConversion)
            assertEquals(60.0, result.variants[0].steps[1].dropoff)
            assertEquals(25.0, result.variants[1].steps[1].dropoff)

            val query = queries.single()
            assertTrue(query.contains("flag_key = 'free_export_gate'"))
            assertTrue(query.contains("AND environment = 'production'"))
            assertTrue(query.contains("e.source = 'app'"))
            assertTrue(query.contains("e.device_type = 'ios'"))
            assertTrue(query.contains("mapContains(e.props, 'plan')"))
            assertTrue(query.contains("e.props['plan'] LIKE '%pro%'"))
            assertTrue(query.contains("INNER JOIN flag_assignments AS f ON f.targeting_key = e.user_id"))
        }
    }

    @Test
    fun `compare funnel by feature flag applies alternate filters and empty variants`() = runBlocking {
        val queries = mutableListOf<String>()
        MockHttpServer(
            queryBasedClickHouseHandler(
                "feature_flag_evaluations" to "",
                captureQueries = queries,
            ),
        ).use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val result = service.compareFunnelByFeatureFlag(
                organizationId = 1,
                definition = FeatureFlagFunnelComparisonDefinition(
                    projectId = 1,
                    dateFrom = LocalDate.of(2026, 6, 1),
                    dateTo = LocalDate.of(2026, 6, 1),
                    steps = listOf("page_viewed", "signup_completed"),
                    groupBy = "session_id",
                    source = null,
                    filters = listOf(
                        AnalyticsFilter("page", "is_not", "/pricing"),
                        AnalyticsFilter("country", "contains", "US"),
                        AnalyticsFilter("browser", "not_contains", "Bot"),
                        AnalyticsFilter("os", "is", "iOS"),
                        AnalyticsFilter("utm_source", "is", "newsletter"),
                        AnalyticsFilter("utm_medium", "is", "email"),
                        AnalyticsFilter("utm_campaign", "is", "summer"),
                        AnalyticsFilter("utm_term", "is", "acapella"),
                        AnalyticsFilter("utm_content", "is", "hero"),
                        AnalyticsFilter("event", "is", "signup_completed"),
                        AnalyticsFilter("unknown", "is", "ignored"),
                    ),
                    propFilters = listOf(
                        EventPropertyFilter("plan", "is_not", "free"),
                        EventPropertyFilter("destination", "not_contains", "private"),
                    ),
                    flagKey = "onboarding_variant",
                    environment = null,
                ),
            )

            assertTrue(result.variants.isEmpty())
            val query = queries.single()
            assertTrue(query.contains("organization_id = 1"))
            assertTrue(query.contains("f.targeting_key = e.session_id"))
            assertTrue(query.contains("e.pathname != '/pricing'"))
            assertTrue(query.contains("e.country_code LIKE '%US%'"))
            assertTrue(query.contains("e.browser NOT LIKE '%Bot%'"))
            assertTrue(query.contains("e.os = 'iOS'"))
            assertTrue(query.contains("e.utm_source = 'newsletter'"))
            assertTrue(query.contains("e.utm_medium = 'email'"))
            assertTrue(query.contains("e.utm_campaign = 'summer'"))
            assertTrue(query.contains("e.utm_term = 'acapella'"))
            assertTrue(query.contains("e.utm_content = 'hero'"))
            assertTrue(query.contains("e.event_name = 'signup_completed'"))
            assertTrue(query.contains("e.props['plan'] != 'free'"))
            assertTrue(query.contains("e.props['destination'] NOT LIKE '%private%'"))
            assertFalse(query.contains("ignored"))
        }
    }

    private fun createRequest(
        name: String = "Activation",
        steps: List<String> = listOf(" signup_completed ", "recording_created"),
        groupBy: String = "user_id",
    ) = SavedProductFunnelCreateRequest(
        projectId = 1,
        name = name,
        description = " Signup to first recording ",
        steps = steps,
        filters = listOf(AnalyticsFilter("device_type", "is", "ios")),
        propFilters = listOf(EventPropertyFilter("destination", "is", "private")),
        groupBy = groupBy,
        source = " app ",
    )

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

    private fun seedProject() = transaction {
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
            it[resource_id] = Uuid.parse(FUNNEL_PROJECT_RESOURCE_ID)
            it[name] = "Bandapella Backend"
            it[slug] = "bandapella-backend"
        }
    }
}
