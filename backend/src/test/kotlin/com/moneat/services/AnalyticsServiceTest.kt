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
import com.moneat.analytics.services.AnalyticsService
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

    // --- Overview ---

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

    // --- Timeseries ---

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

    // --- Breakdown ---

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

    // --- Pages, Entry Pages, Exit Pages (delegates to getBreakdown) ---

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

    // --- Realtime ---

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

    // --- Funnel ---

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
            // Step 2: level>=2 => 30+10 = 40
            assertEquals("signup_click", result.steps[1].name)
            assertEquals(40L, result.steps[1].visitors)
            // dropoff = (90-40)/90*100 ≈ 55.56
            assertTrue(result.steps[1].dropoff > 55.0)
            assertTrue(result.steps[1].dropoff < 56.0)
            // Step 3: level>=3 => 10
            assertEquals("signup_complete", result.steps[2].name)
            assertEquals(10L, result.steps[2].visitors)
            // dropoff = (40-10)/40*100 = 75.0
            assertEquals(75.0, result.steps[2].dropoff)
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
        }
    }

    // --- Events ---

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

    // --- Filters ---

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

    // --- Dimension resolution ---

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

    // --- Query structure ---

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

    // --- Filter column resolution edge cases ---

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

    // --- JSON parsing edge cases ---

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
}
