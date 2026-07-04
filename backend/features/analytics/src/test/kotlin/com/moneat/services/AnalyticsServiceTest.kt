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
import com.moneat.analytics.services.AnalyticsQueryScope
import com.moneat.analytics.services.AnalyticsService
import com.moneat.analytics.services.ProductRetentionRequest
import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import com.moneat.testsupport.withClickHouseMockServer
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnalyticsServiceTest {

    companion object {
        private const val CONTENT_TYPE_TEXT_PLAIN = "text/plain"
    }

    private val service = AnalyticsService()

    private val dateFrom: LocalDate = LocalDate.of(2026, 1, 1)
    private val dateTo: LocalDate = LocalDate.of(2026, 1, 31)
    private val projectId = 42L

    @AfterTest
    fun cleanup() {
        ClickHouseClient.close()
    }

    // ──── Overview ────

    @Test
    fun `getOverview returns metrics from ClickHouse`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.requestBodyText()
            exchange.respond(
                200,
                """{"visitors":100,"pageviews":250,"bounce_rate":35.5,"avg_visit_duration":120.0,"views_per_visit":2.5}
                """.trimIndent(),
                contentType = CONTENT_TYPE_TEXT_PLAIN
            )
        }) { _ ->

            val result = service.getOverview(projectId, dateFrom, dateTo, emptyList(), null, null)

            assertEquals(100L, result.visitors)
            assertEquals(250L, result.pageviews)
            assertEquals(35.5, result.bounceRate)
            assertEquals(120.0, result.avgVisitDuration)
            assertEquals(2.5, result.viewsPerVisit)
            assertEquals(null, result.compVisitors)
            assertEquals(null, result.compPageviews)
        }
    }

    @Test
    fun `getOverview with comparison period returns both metric sets`() = runBlocking {
        var callCount = 0
        withClickHouseMockServer({ exchange ->
            exchange.requestBodyText()
            callCount++
            if (callCount == 1) {
                exchange.respond(
                    200,
                    """{"visitors":100,"pageviews":250,"bounce_rate":35.5,"avg_visit_duration":120.0,"views_per_visit":2.5}
                    """.trimIndent(),
                    contentType = CONTENT_TYPE_TEXT_PLAIN
                )
            } else {
                exchange.respond(
                    200,
                    """{"visitors":80,"pageviews":200,"bounce_rate":40.0,"avg_visit_duration":100.0,"views_per_visit":2.0}
                    """.trimIndent(),
                    contentType = CONTENT_TYPE_TEXT_PLAIN
                )
            }
        }) { _ ->

            val compFrom = LocalDate.of(2025, 12, 1)
            val compTo = LocalDate.of(2025, 12, 31)
            val result = service.getOverview(projectId, dateFrom, dateTo, emptyList(), compFrom, compTo)

            assertEquals(100L, result.visitors)
            assertEquals(250L, result.pageviews)
            assertEquals(80L, result.compVisitors)
            assertEquals(200L, result.compPageviews)
            assertEquals(40.0, result.compBounceRate)
        }
    }

    @Test
    fun `getOverview returns zeros when ClickHouse returns empty body`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.requestBodyText()
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            val result = service.getOverview(projectId, dateFrom, dateTo, emptyList(), null, null)

            assertEquals(0L, result.visitors)
            assertEquals(0L, result.pageviews)
            assertEquals(0.0, result.bounceRate)
            assertEquals(0.0, result.avgVisitDuration)
            assertEquals(0.0, result.viewsPerVisit)
        }
    }

    // ──── Timeseries ────

    @Test
    fun `getTimeseries returns list of data points`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.requestBodyText()
            exchange.respond(
                200,
                """{"date":"2026-01-01","visitors":10,"pageviews":25}
{"date":"2026-01-02","visitors":15,"pageviews":30}""",
                contentType = CONTENT_TYPE_TEXT_PLAIN
            )
        }) { _ ->

            val result = service.getTimeseries(projectId, dateFrom, dateTo, emptyList())

            assertEquals(2, result.size)
            assertEquals("2026-01-01", result[0].date)
            assertEquals(10L, result[0].visitors)
            assertEquals(25L, result[0].pageviews)
            assertEquals("2026-01-02", result[1].date)
        }
    }

    @Test
    fun `getTimeseries uses hourly interval for short date ranges`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            val shortFrom = LocalDate.of(2026, 1, 1)
            val shortTo = LocalDate.of(2026, 1, 2)
            service.getTimeseries(projectId, shortFrom, shortTo, emptyList())

            assertTrue(queries.any { it.contains("toStartOfHour") })
        }
    }

    @Test
    fun `getTimeseries uses daily interval for long date ranges`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            service.getTimeseries(projectId, dateFrom, dateTo, emptyList())

            assertTrue(queries.any { it.contains("toDate") })
        }
    }

    @Test
    fun `getTimeseries returns empty list for blank response`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.requestBodyText()
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            val result = service.getTimeseries(projectId, dateFrom, dateTo, emptyList())

            assertTrue(result.isEmpty())
        }
    }

    // ──── Breakdown ────

    @Test
    fun `getBreakdown returns breakdown rows for session dimension`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.requestBodyText()
            exchange.respond(
                200,
                """{"name":"Chrome","visitors":50,"pageviews":120}
{"name":"Firefox","visitors":30,"pageviews":80}""",
                contentType = CONTENT_TYPE_TEXT_PLAIN
            )
        }) { _ ->

            val result = service.getBreakdown(projectId, dateFrom, dateTo, emptyList(), "browser")

            assertEquals(2, result.results.size)
            assertEquals("Chrome", result.results[0].name)
            assertEquals(50L, result.results[0].visitors)
            assertEquals(120L, result.results[0].pageviews)
            assertEquals("Firefox", result.results[1].name)
        }
    }

    @Test
    fun `getBreakdown queries events table for pathname dimension`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(
                200,
                """{"name":"/home","visitors":100,"pageviews":200}""",
                contentType = CONTENT_TYPE_TEXT_PLAIN
            )
        }) { _ ->

            service.getBreakdown(projectId, dateFrom, dateTo, emptyList(), "pathname")

            assertTrue(queries.any { it.contains("analytics_events") })
        }
    }

    @Test
    fun `getBreakdown queries sessions table for session dimensions`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            service.getBreakdown(projectId, dateFrom, dateTo, emptyList(), "browser")

            assertTrue(queries.any { it.contains("analytics_sessions_hourly") })
        }
    }

    @Test
    fun `getBreakdown returns empty results for blank response`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.requestBodyText()
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            val result = service.getBreakdown(projectId, dateFrom, dateTo, emptyList(), "os")

            assertTrue(result.results.isEmpty())
        }
    }

    // ──── Pages, Entry Pages, Exit Pages (delegates to getBreakdown) ────

    @Test
    fun `getPages queries pathname dimension`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            service.getPages(projectId, dateFrom, dateTo, emptyList())

            assertTrue(queries.any { it.contains("e.pathname") })
        }
    }

    @Test
    fun `getEntryPages queries entry_page dimension`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            service.getEntryPages(projectId, dateFrom, dateTo, emptyList())

            assertTrue(queries.any { it.contains("s.entry_page") })
        }
    }

    @Test
    fun `getExitPages queries exit_page dimension`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            service.getExitPages(projectId, dateFrom, dateTo, emptyList())

            assertTrue(queries.any { it.contains("s.exit_page") })
        }
    }

    // ──── Realtime ────

    @Test
    fun `getRealtime returns visitor count from Redis`() {
        mockkObject(RedisConfig)
        try {
            val mockCommands = io.mockk.mockk<RedisCommands<String, String>>()
            every { RedisConfig.sync() } returns mockCommands
            every { mockCommands.pfcount("moneat:analytics:realtime:$projectId") } returns 7L

            val result = service.getRealtime(projectId)

            assertEquals(7L, result.visitors)
        } finally {
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `getRealtime counts visitors across services as a Redis union`() {
        mockkObject(RedisConfig)
        try {
            val mockCommands = io.mockk.mockk<RedisCommands<String, String>>()
            every { RedisConfig.sync() } returns mockCommands
            every {
                mockCommands.pfcount(
                    "moneat:analytics:realtime:42",
                    "moneat:analytics:realtime:43",
                )
            } returns 9L

            val result = service.getRealtime(AnalyticsQueryScope.services(listOf(42L, 43L)))

            assertEquals(9L, result.visitors)
        } finally {
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `getRealtime returns zero for empty service scope`() {
        val result = service.getRealtime(AnalyticsQueryScope.services(emptyList()))

        assertEquals(0L, result.visitors)
    }

    @Test
    fun `getRealtime returns zero when Redis throws exception`() {
        mockkObject(RedisConfig)
        try {
            every { RedisConfig.sync() } throws RuntimeException("Connection refused")

            val result = service.getRealtime(projectId)

            assertEquals(0L, result.visitors)
        } finally {
            unmockkObject(RedisConfig)
        }
    }

    // ──── Funnel ────

    @Test
    fun `getFunnel returns empty steps when fewer than 2 steps`() = runBlocking {
        val result = service.getFunnel(projectId, dateFrom, dateTo, listOf("pageview"))

        assertTrue(result.steps.isEmpty())
    }

    @Test
    fun `getFunnel returns funnel with cumulative visitors and dropoff`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.requestBodyText()
            exchange.respond(
                200,
                """{"level":1,"cnt":50}
{"level":2,"cnt":30}
{"level":3,"cnt":10}""",
                contentType = CONTENT_TYPE_TEXT_PLAIN
            )
        }) { _ ->

            val steps = listOf("page_load", "signup_click", "signup_complete")
            val result = service.getFunnel(projectId, dateFrom, dateTo, steps)

            assertEquals(3, result.steps.size)
            // Step 1: level>=1 => 50+30+10 = 90
            assertEquals("page_load", result.steps[0].name)
            assertEquals(90L, result.steps[0].visitors)
            assertEquals(0.0, result.steps[0].dropoff)
            assertEquals(100.0, result.steps[0].conversionRate)
            // Step 2: level>=2 => 30+10 = 40
            assertEquals("signup_click", result.steps[1].name)
            assertEquals(40L, result.steps[1].visitors)
            // dropoff = (90-40)/90*100 ≈ 55.56
            assertTrue(result.steps[1].dropoff > 55.0)
            assertTrue(result.steps[1].dropoff < 56.0)
            assertTrue(result.steps[1].conversionRate > 44.0)
            assertTrue(result.steps[1].conversionRate < 45.0)
            // Step 3: level>=3 => 10
            assertEquals("signup_complete", result.steps[2].name)
            assertEquals(10L, result.steps[2].visitors)
            // dropoff = (40-10)/40*100 = 75.0
            assertEquals(75.0, result.steps[2].dropoff)
            assertTrue(result.overallConversion > 11.0)
            assertTrue(result.overallConversion < 12.0)
        }
    }

    @Test
    fun `getFunnel returns empty counts when ClickHouse returns blank`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.requestBodyText()
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            val steps = listOf("step_a", "step_b")
            val result = service.getFunnel(projectId, dateFrom, dateTo, steps)

            assertEquals(2, result.steps.size)
            assertEquals(0L, result.steps[0].visitors)
            assertEquals(0L, result.steps[1].visitors)
            assertEquals(0.0, result.overallConversion)
        }
    }

    @Test
    fun `getFunnel can group product events by user id and source`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            service.getFunnel(
                projectId,
                dateFrom,
                dateTo,
                listOf("signup.completed", "recording.started"),
                groupBy = "user_id",
                source = "server"
            )

            assertTrue(queries.any { it.contains("GROUP BY e.project_id, e.user_id") })
            assertTrue(queries.any { it.contains("source = 'server'") })
            assertTrue(queries.any { it.contains("user_id != ''") })
        }
    }

    @Test
    fun `getFunnel rejects unsupported property filter operators`() = runBlocking {
        val error = assertFailsWith<IllegalArgumentException> {
            service.getFunnel(
                projectId,
                dateFrom,
                dateTo,
                listOf("signup.completed", "recording.started"),
                propFilters = listOf(EventPropertyFilter("destination", "starts_with", "private")),
            )
        }

        assertTrue(error.message!!.contains("Unsupported event property filter operator"))
    }

    @Test
    fun `getRetention returns weekly cohorts`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            val retentionRow = listOf(
                """"cohort_week":"2026-01-05 00:00:00"""",
                """"users":10""",
                """"eligible_1":10""",
                """"retained_1":4""",
                """"eligible_7":8""",
                """"retained_7":6""",
            ).joinToString(prefix = "{", postfix = "}")
            exchange.respond(
                200,
                retentionRow,
                contentType = CONTENT_TYPE_TEXT_PLAIN
            )
        }) { _ ->

            val result = service.getRetention(
                projectId,
                dateFrom,
                dateTo,
                "signup.completed",
                "recording.started",
                listOf(1, 7),
            )

            assertEquals("signup.completed", result.startEvent)
            assertEquals("recording.started", result.returnEvent)
            assertEquals(1, result.cohorts.size)
            assertEquals(10L, result.cohorts[0].users)
            assertEquals(10L, result.cohorts[0].periods[0].eligibleUsers)
            assertEquals(40.0, result.cohorts[0].periods[0].retentionRate)
            assertEquals(8L, result.cohorts[0].periods[1].eligibleUsers)
            assertEquals(75.0, result.cohorts[0].periods[1].retentionRate)
            assertTrue(queries.any { it.contains("e.source = 'server'") })
            assertTrue(queries.any { it.contains("eligible_7") })
            assertTrue(queries.any { it.contains("retained_7") })
        }
    }

    // ──── Events ────

    @Test
    fun `getEvents returns custom event breakdown`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.requestBodyText()
            exchange.respond(
                200,
                """{"name":"button_click","visitors":25,"pageviews":40}
{"name":"form_submit","visitors":10,"pageviews":15}""",
                contentType = CONTENT_TYPE_TEXT_PLAIN
            )
        }) { _ ->

            val result = service.getEvents(projectId, dateFrom, dateTo, emptyList())

            assertEquals(2, result.results.size)
            assertEquals("button_click", result.results[0].name)
            assertEquals(25L, result.results[0].visitors)
            assertEquals(40L, result.results[0].pageviews)
        }
    }

    @Test
    fun `getEvents excludes pageview events in query`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            service.getEvents(projectId, dateFrom, dateTo, emptyList())

            assertTrue(queries.any { it.contains("event_name != 'pageview'") })
        }
    }

    @Test
    fun `getEvents can count product events by user id and source`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            service.getEvents(
                projectId,
                dateFrom,
                dateTo,
                emptyList(),
                groupBy = "user_id",
                source = "server",
            )

            assertTrue(queries.any { it.contains("uniq(e.project_id, e.user_id)") })
            assertTrue(queries.any { it.contains("e.source = 'server'") })
            assertTrue(queries.any { it.contains("e.user_id != ''") })
        }
    }

    // ──── Product analytics ────

    @Test
    fun `getProductAnalyticsSummary returns product KPI metrics`() = runBlocking {
        val queries = mutableListOf<String>()
        var callCount = 0
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            callCount++
            val body = when (callCount) {
                1 ->
                    productSummaryRow(
                        ProductSummaryRowFixture(
                            activeUsers = 100,
                            weeklyActiveUsers = 90,
                            dailyActiveUsers = 12,
                            monthlyActiveUsers = 100,
                            newUsers = 20,
                            activatedNewUsers = 10,
                            powerUsers = 5,
                        )
                    )

                2 ->
                    productSummaryRow(
                        ProductSummaryRowFixture(
                            activeUsers = 80,
                            weeklyActiveUsers = 70,
                            dailyActiveUsers = 8,
                            monthlyActiveUsers = 80,
                            newUsers = 16,
                            activatedNewUsers = 4,
                            powerUsers = 4,
                        )
                    )

                3 -> """{"eligible_users":10,"retained_users":6}"""
                4 -> """{"eligible_users":8,"retained_users":4}"""
                else ->
                    """{"day":"2026-01-01","active_users":4,"new_users":2,"activated_users":1,"key_action_users":1}
{"day":"2026-01-02","active_users":6,"new_users":3,"activated_users":2,"key_action_users":2}"""
            }
            exchange.respond(200, body, contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            val result = service.getProductAnalyticsSummary(projectId, dateFrom, dateTo, emptyList())

            assertEquals(90.0, result.weeklyActiveUsers.value)
            assertEquals(70.0, result.weeklyActiveUsers.previous)
            assertEquals(12L, result.dailyActiveUsers)
            assertEquals(50.0, result.activationRate.value)
            assertEquals(60.0, result.week1Retention.value)
            assertEquals(2, result.weeklyActiveUsers.spark.size)
            assertTrue(result.stickiness.spark.isEmpty())
            assertTrue(queries.any { it.contains("e.source = 'server'") })
            assertTrue(queries.any { it.contains("e.user_id != ''") })
            assertTrue(queries.first().contains("countIf(month_event_count > 0) AS monthly_active_users"))
            assertTrue(queries.first().contains("e.timestamp >= '2026-01-02' AND e.timestamp < '2026-02-01'"))
        }
    }

    @Test
    fun `getProductActivity returns current points with previous period overlay`() = runBlocking {
        var callCount = 0
        withClickHouseMockServer({ exchange ->
            exchange.requestBodyText()
            callCount++
            val body = if (callCount == 1) {
                """{"day":"2026-01-01","active_users":3,"new_users":1,"activated_users":1,"key_action_users":2}
{"day":"2026-01-02","active_users":5,"new_users":2,"activated_users":1,"key_action_users":3}"""
            } else {
                """{"day":"2025-12-01","active_users":2,"new_users":1,"activated_users":0,"key_action_users":1}
{"day":"2025-12-02","active_users":4,"new_users":1,"activated_users":1,"key_action_users":2}"""
            }
            exchange.respond(200, body, contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            val result = service.getProductActivity(projectId, dateFrom, dateTo, emptyList())
            val active = result.series.first { it.metric == "active" }

            assertEquals(31, active.points.size)
            assertEquals("2026-01-01", active.points.first().timestamp)
            assertEquals(3L, active.points.first().value)
            assertEquals(2L, active.points.first().previous)
        }
    }

    @Test
    fun `getProductMovers returns event changes`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(
                200,
                """{"name":"activated","current_count":30,"previous_count":20}""",
                contentType = CONTENT_TYPE_TEXT_PLAIN
            )
        }) { _ ->

            val result = service.getProductMovers(projectId, dateFrom, dateTo, emptyList())

            assertEquals(1, result.size)
            assertEquals("activated", result.first().name)
            assertEquals("+50%", result.first().change)
            assertTrue(queries.any { it.contains("event_name != 'pageview'") })
        }
    }

    @Test
    fun `getProductFeatureAdoption derives feature usage from event props`() = runBlocking {
        val queries = mutableListOf<String>()
        var callCount = 0
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            callCount++
            val body = if (callCount == 1) {
                productSummaryRow(ProductSummaryRowFixture(activeUsers = 100))
            } else {
                """{"name":"Search","users":25}"""
            }
            exchange.respond(200, body, contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            val result = service.getProductFeatureAdoption(projectId, dateFrom, dateTo, emptyList())

            assertEquals(1, result.size)
            assertEquals("Search", result.first().name)
            assertEquals(25.0, result.first().adoptionRate)
            assertTrue(queries.any { it.contains("mapContains(e.props, 'feature')") })
        }
    }

    @Test
    fun `getProductSegmentation queries plan platform and country dimensions`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(
                200,
                productSegmentRow("pro"),
                contentType = CONTENT_TYPE_TEXT_PLAIN
            )
        }) { _ ->

            val result = service.getProductSegmentation(projectId, dateFrom, dateTo, emptyList())

            assertEquals("pro", result.plan.first().name)
            assertEquals(50.0, result.plan.first().activationRate)
            assertTrue(queries.any { it.contains("e.props['plan']") })
            assertTrue(queries.any { it.contains("e.props['platform']") })
            assertTrue(queries.any { it.contains("e.country_code") })
            assertTrue(queries.any { it.contains("countIf(month_event_count > 0) AS monthly_active_users") })
            assertTrue(queries.any { it.contains("e.timestamp >= '2026-01-02' AND e.timestamp < '2026-02-01'") })
        }
    }

    @Test
    fun `getProductRetention returns retention grid for custom event`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(
                200,
                productRetentionRow(),
                contentType = CONTENT_TYPE_TEXT_PLAIN
            )
        }) { _ ->

            val result = service.getProductRetention(
                projectId,
                ProductRetentionRequest(
                    dateFrom = dateFrom,
                    dateTo = dateTo,
                    filters = emptyList(),
                    mode = "custom",
                    customEvent = "purchase_completed",
                    periodCount = 3,
                ),
            )

            assertEquals("custom", result.mode)
            assertEquals(listOf(0, 1, 2), result.periods)
            assertEquals(listOf(100.0, 50.0, 40.0), result.cohorts.first().values)
            assertTrue(queries.any { it.contains("e.event_name = 'purchase_completed'") })
            assertTrue(queries.any { it.contains("eligible_2") })
            assertTrue(queries.any { it.contains("INTERVAL 14 DAY AND e.timestamp < c.first_seen + INTERVAL 21 DAY") })
            assertTrue(queries.any { it.contains("c.first_seen + INTERVAL 21 DAY <= '2026-02-01'") })
        }
    }

    // ──── Filters ────

    @Test
    fun `getTimeseries applies is filter`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            val filters = listOf(AnalyticsFilter("browser", "is", "Chrome"))
            service.getTimeseries(projectId, dateFrom, dateTo, filters)

            assertTrue(queries.any { it.contains("browser") && it.contains("= 'Chrome'") })
        }
    }

    @Test
    fun `getTimeseries applies is_not filter`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            val filters = listOf(AnalyticsFilter("browser", "is_not", "IE"))
            service.getTimeseries(projectId, dateFrom, dateTo, filters)

            assertTrue(queries.any { it.contains("browser") && it.contains("!= 'IE'") })
        }
    }

    @Test
    fun `getTimeseries applies contains filter`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            val filters = listOf(AnalyticsFilter("browser", "contains", "Chrome"))
            service.getTimeseries(projectId, dateFrom, dateTo, filters)

            assertTrue(queries.any { it.contains("LIKE '%Chrome%'") })
        }
    }

    @Test
    fun `getTimeseries applies not_contains filter`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            val filters = listOf(AnalyticsFilter("browser", "not_contains", "IE"))
            service.getTimeseries(projectId, dateFrom, dateTo, filters)

            assertTrue(queries.any { it.contains("NOT LIKE '%IE%'") })
        }
    }

    @Test
    fun `getOverview applies source filter mapped to referrer_source`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(
                200,
                """{"visitors":5,"pageviews":10,"bounce_rate":50.0,"avg_visit_duration":60.0,"views_per_visit":2.0}""",
                contentType = CONTENT_TYPE_TEXT_PLAIN
            )
        }) { _ ->

            val filters = listOf(AnalyticsFilter("source", "is", "google"))
            service.getOverview(projectId, dateFrom, dateTo, filters, null, null)

            assertTrue(queries.any { it.contains("referrer_source") && it.contains("'google'") })
        }
    }

    @Test
    fun `getOverview applies country filter mapped to country_code`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(
                200,
                """{"visitors":5,"pageviews":10,"bounce_rate":50.0,"avg_visit_duration":60.0,"views_per_visit":2.0}""",
                contentType = CONTENT_TYPE_TEXT_PLAIN
            )
        }) { _ ->

            val filters = listOf(AnalyticsFilter("country", "is", "US"))
            service.getOverview(projectId, dateFrom, dateTo, filters, null, null)

            assertTrue(queries.any { it.contains("country_code") && it.contains("'US'") })
        }
    }

    // ──── Dimension resolution ────

    @Test
    fun `getBreakdown resolves all session dimensions to sessions table`() = runBlocking {
        val sessionDimensions = listOf(
            "entry_page", "exit_page", "referrer_source",
            "utm_source", "utm_medium", "utm_campaign",
            "country_code", "browser", "os", "device_type"
        )
        for (dimension in sessionDimensions) {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                queries.add(exchange.requestBodyText())
                exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
            }) { _ ->
                service.getBreakdown(projectId, dateFrom, dateTo, emptyList(), dimension)
                assertTrue(
                    queries.any { it.contains("analytics_sessions_hourly") },
                    "Expected sessions table for dimension '$dimension'"
                )
            }
        }
    }

    @Test
    fun `getBreakdown resolves event dimensions to events table`() = runBlocking {
        val eventDimensions = listOf("pathname", "utm_term", "utm_content")
        for (dimension in eventDimensions) {
            val queries = mutableListOf<String>()
            withClickHouseMockServer({ exchange ->
                queries.add(exchange.requestBodyText())
                exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
            }) { _ ->
                service.getBreakdown(projectId, dateFrom, dateTo, emptyList(), dimension)
                assertTrue(
                    queries.any { it.contains("analytics_events") },
                    "Expected events table for dimension '$dimension'"
                )
            }
        }
    }

    @Test
    fun `getBreakdown uses custom limit`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            service.getBreakdown(projectId, dateFrom, dateTo, emptyList(), "browser", limit = 10)

            assertTrue(queries.any { it.contains("LIMIT 10") })
        }
    }

    // ──── Query structure ────

    @Test
    fun `getOverview query includes project_id and date range`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            service.getOverview(projectId, dateFrom, dateTo, emptyList(), null, null)

            assertTrue(queries.any { it.contains("toUInt64($projectId)") })
            assertTrue(queries.any { it.contains("2026-01-01") })
            assertTrue(queries.any { it.contains("2026-02-01") })
        }
    }

    @Test
    fun `getOverview scoped query filters by multiple service ids`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            service.getOverview(
                AnalyticsQueryScope.services(listOf(projectId, 43)),
                dateFrom,
                dateTo,
                emptyList(),
                null,
                null
            )

            assertTrue(queries.any { it.contains("s.project_id IN (toUInt64(42), toUInt64(43))") })
        }
    }

    @Test
    fun `getFunnel query includes windowFunnel`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            service.getFunnel(projectId, dateFrom, dateTo, listOf("step1", "step2"))

            assertTrue(queries.any { it.contains("windowFunnel") })
        }
    }

    @Test
    fun `getFunnel scoped query groups by service and visitor key`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            service.getFunnel(
                AnalyticsQueryScope.services(listOf(projectId, 43)),
                dateFrom,
                dateTo,
                listOf("step1", "step2")
            )

            assertTrue(queries.any { it.contains("project_id IN (toUInt64(42), toUInt64(43))") })
            assertTrue(queries.any { it.contains("GROUP BY e.project_id, e.session_id") })
        }
    }

    @Test
    fun `getRetention scoped query joins return events within the same service`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            service.getRetention(
                AnalyticsQueryScope.services(listOf(projectId, 43)),
                dateFrom,
                dateTo,
                "signup.completed",
                "recording.started",
                listOf(1, 7)
            )

            assertTrue(queries.any { it.contains("e.project_id IN (toUInt64(42), toUInt64(43))") })
            assertTrue(queries.any { it.contains("GROUP BY project_id, user_id") })
            assertTrue(queries.any { it.contains("ON e.project_id = c.project_id") })
        }
    }

    // ──── Filter column resolution edge cases ────

    @Test
    fun `filters with unknown property are ignored`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            val filters = listOf(AnalyticsFilter("nonexistent_prop", "is", "value"))
            service.getTimeseries(projectId, dateFrom, dateTo, filters)

            // Should not contain the unknown property in the query
            assertTrue(queries.none { it.contains("nonexistent_prop") })
        }
    }

    @Test
    fun `page filter resolves for events alias only`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            // getTimeseries uses alias "s" (sessions table), so "page" filter should be skipped
            val filters = listOf(AnalyticsFilter("page", "is", "/home"))
            service.getTimeseries(projectId, dateFrom, dateTo, filters)

            assertTrue(queries.none { it.contains("pathname") && it.contains("'/home'") })
        }
    }

    @Test
    fun `getEvents applies page filter since it uses events alias`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = CONTENT_TYPE_TEXT_PLAIN)
        }) { _ ->

            val filters = listOf(AnalyticsFilter("page", "is", "/home"))
            service.getEvents(projectId, dateFrom, dateTo, filters)

            assertTrue(queries.any { it.contains("pathname") && it.contains("'/home'") })
        }
    }

    // ──── JSON parsing edge cases ────

    @Test
    fun `getTimeseries handles malformed JSON rows gracefully`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.requestBodyText()
            exchange.respond(
                200,
                """{"date":"2026-01-01","visitors":10,"pageviews":25}
not valid json
{"date":"2026-01-02","visitors":15,"pageviews":30}""",
                contentType = CONTENT_TYPE_TEXT_PLAIN
            )
        }) { _ ->

            val result = service.getTimeseries(projectId, dateFrom, dateTo, emptyList())

            // Malformed row is skipped, valid rows are returned
            assertEquals(2, result.size)
        }
    }

    @Test
    fun `getOverview handles missing JSON fields with defaults`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.requestBodyText()
            exchange.respond(
                200,
                """{"visitors":5}""",
                contentType = CONTENT_TYPE_TEXT_PLAIN
            )
        }) { _ ->

            val result = service.getOverview(projectId, dateFrom, dateTo, emptyList(), null, null)

            assertEquals(5L, result.visitors)
            assertEquals(0L, result.pageviews)
            assertEquals(0.0, result.bounceRate)
        }
    }

    private fun productSummaryRow(row: ProductSummaryRowFixture = ProductSummaryRowFixture()): String =
        listOf(
            """"active_users":${row.activeUsers}""",
            """"weekly_active_users":${row.weeklyActiveUsers}""",
            """"daily_active_users":${row.dailyActiveUsers}""",
            """"monthly_active_users":${row.monthlyActiveUsers}""",
            """"new_users":${row.newUsers}""",
            """"activated_new_users":${row.activatedNewUsers}""",
            """"power_users":${row.powerUsers}""",
        ).joinToString(prefix = "{", postfix = "}")

    private data class ProductSummaryRowFixture(
        val activeUsers: Long = 0,
        val weeklyActiveUsers: Long = 0,
        val dailyActiveUsers: Long = 0,
        val monthlyActiveUsers: Long = 0,
        val newUsers: Long = 0,
        val activatedNewUsers: Long = 0,
        val powerUsers: Long = 0,
    )

    private fun productRetentionRow(): String =
        listOf(
            """"cohort":"2026-01-05 00:00:00"""",
            """"users":10""",
            """"eligible_1":10""",
            """"retained_1":5""",
            """"eligible_2":10""",
            """"retained_2":4""",
        ).joinToString(prefix = "{", postfix = "}")

    private fun productSegmentRow(name: String): String =
        listOf(
            """"name":"$name"""",
            """"users":20""",
            """"new_users":10""",
            """"activated_new_users":5""",
            """"eligible_users":8""",
            """"retained_users":4""",
            """"daily_active_users":6""",
            """"monthly_active_users":12""",
        ).joinToString(prefix = "{", postfix = "}")
}
