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

import com.moneat.billing.services.PricingTierService
import com.moneat.config.ClickHouseClient
import com.moneat.events.services.DashboardQueryHelper
import com.moneat.events.services.ReleaseStatsService
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseStatsServiceTest {

    companion object {
        private const val TEXT_PLAIN = "text/plain"
        private const val COUNT_IF_ERRORS_0 = "countIf(errors = 0)"
    }

    private val retentionPolicyService = mockk<RetentionPolicyService>()
    private val pricingTierService = mockk<PricingTierService>()
    private lateinit var queryHelper: DashboardQueryHelper
    private lateinit var service: ReleaseStatsService

    @BeforeTest
    fun setup() {
        coEvery { retentionPolicyService.getRetentionDaysForProject(any()) } returns 30
        queryHelper = DashboardQueryHelper(retentionPolicyService, pricingTierService)
        service = ReleaseStatsService(queryHelper)
    }

    @Test
    fun `getReleases returns releases with issue counts and crash-free rates`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("first_release") && query.contains("GROUP BY first_release") -> {
                    exchange.respond(
                        200,
                        """{"version":"1.0.0","total":2}""",
                        contentType = TEXT_PLAIN
                    )
                }
                query.contains(COUNT_IF_ERRORS_0) && query.contains("sessions") -> {
                    exchange.respond(
                        200,
                        """{"version":"1.0.0","rate":98.5}""",
                        contentType = TEXT_PLAIN
                    )
                }
                else -> {
                    exchange.respond(
                        200,
                        """{"version":"1.0.0","first_seen":"2026-01-01T00:00:00.000Z","last_seen":"2026-01-02T00:00:00.000Z","event_count":10,"user_count":5}
                        """.trimIndent(),
                        contentType = TEXT_PLAIN
                    )
                }
            }
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val releases = service.getReleases(1L)
            assertEquals(1, releases.size)
            val release = releases.first()
            assertEquals("1.0.0", release.version)
            assertEquals(10L, release.eventCount)
            assertEquals(5L, release.userCount)
            assertEquals(2L, release.newIssueCount)
            assertEquals(98.5, release.crashFreeRate)
            assertEquals("2026-01-01T00:00:00.000Z", release.firstSeen)
            assertEquals("2026-01-02T00:00:00.000Z", release.lastSeen)
        }
    }

    @Test
    fun `getReleases returns empty list on error`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.requestBodyText()
            exchange.respond(500, "Internal Server Error", contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val releases = service.getReleases(1L)
            assertTrue(releases.isEmpty())
        }
    }

    @Test
    fun `getReleaseStats returns detailed release stats`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("count() as total") && query.contains("first_release") -> {
                    exchange.respond(
                        200,
                        """{"total":3}""",
                        contentType = TEXT_PLAIN
                    )
                }
                query.contains(COUNT_IF_ERRORS_0) && query.contains("sessions") -> {
                    exchange.respond(
                        200,
                        """{"rate":95.0}""",
                        contentType = TEXT_PLAIN
                    )
                }
                query.contains("toStartOfInterval") -> {
                    exchange.respond(
                        200,
                        """{"time":"2026-01-01T00:00:00.000Z","count":5}""",
                        contentType = TEXT_PLAIN
                    )
                }
                query.contains("GROUP BY level") -> {
                    exchange.respond(
                        200,
                        """{"level":"error","count":3}
{"level":"warning","count":7}""",
                        contentType = TEXT_PLAIN
                    )
                }
                query.contains("GROUP BY issue_id") -> {
                    exchange.respond(
                        200,
                        """{"issue_id":"iss-1","title":"NullPointerException","count":4}""",
                        contentType = TEXT_PLAIN
                    )
                }
                else -> {
                    exchange.respond(
                        200,
                        """{"first_seen":"2026-01-01T00:00:00.000Z","last_seen":"2026-01-02T00:00:00.000Z","total_events":20,"user_count":8}
                        """.trimIndent(),
                        contentType = TEXT_PLAIN
                    )
                }
            }
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val stats = service.getReleaseStats(1L, "1.0.0")
            assertNotNull(stats)
            assertEquals("1.0.0", stats.version)
            assertEquals("2026-01-01T00:00:00.000Z", stats.firstSeen)
            assertEquals("2026-01-02T00:00:00.000Z", stats.lastSeen)
            assertEquals(20L, stats.totalEvents)
            assertEquals(8L, stats.userCount)
            assertEquals(3L, stats.newIssues)
            assertEquals(95.0, stats.crashFreeSessionRate)
            assertNull(stats.crashFreeUserRate)
            assertTrue(stats.eventsTimeline.isNotEmpty())
            assertTrue(stats.eventsByLevel.isNotEmpty())
            assertTrue(stats.topIssues.isNotEmpty())
        }
    }

    @Test
    fun `getReleaseStats returns null on empty response`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.requestBodyText()
            exchange.respond(200, "", contentType = TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val stats = service.getReleaseStats(1L, "1.0.0")
            assertNull(stats)
        }
    }

    @Test
    fun `getReleaseStats filters NaN crash-free rate`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains(COUNT_IF_ERRORS_0) && query.contains("sessions") -> {
                    exchange.respond(
                        200,
                        """{"rate":"nan"}""",
                        contentType = TEXT_PLAIN
                    )
                }
                query.contains("count() as total") && query.contains("first_release") -> {
                    exchange.respond(
                        200,
                        """{"total":0}""",
                        contentType = TEXT_PLAIN
                    )
                }
                query.contains("toStartOfInterval") -> {
                    exchange.respond(200, "", contentType = TEXT_PLAIN)
                }
                query.contains("GROUP BY level") -> {
                    exchange.respond(200, "", contentType = TEXT_PLAIN)
                }
                query.contains("GROUP BY issue_id") -> {
                    exchange.respond(200, "", contentType = TEXT_PLAIN)
                }
                else -> {
                    exchange.respond(
                        200,
                        """{"first_seen":"2026-01-01T00:00:00.000Z","last_seen":"2026-01-02T00:00:00.000Z","total_events":5,"user_count":2}
                        """.trimIndent(),
                        contentType = TEXT_PLAIN
                    )
                }
            }
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            val stats = service.getReleaseStats(1L, "1.0.0")
            assertNotNull(stats)
            assertNull(stats.crashFreeSessionRate)
        }
    }
}
