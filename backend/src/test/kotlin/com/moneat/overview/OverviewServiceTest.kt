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

package com.moneat.overview

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.config.ClickHouseClient
import com.moneat.overview.services.OverviewService
import com.moneat.shared.models.HostAlerts
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Releases
import com.moneat.shared.models.Users
import com.moneat.statuspage.models.StatusPages
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.uptime.models.UptimeMonitors
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class OverviewServiceTest {

    @Test
    fun `overview assembles relational data with empty analytics fallbacks`() = runBlocking {
        val orgId = seedOverviewData()

        val overview = OverviewService().getOverview(orgId, demoEpochMs = DEMO_EPOCH_MS)

        assertEquals("Action needed", overview.systemStatus.state)
        assertEquals("bad", overview.systemStatus.severity)
        assertEquals(1, overview.systemStatus.counts.incidents)
        assertEquals(1, overview.systemStatus.counts.alerts)
        assertEquals(1, overview.systemStatus.counts.hostsOffline)
        assertTrue(overview.systemStatus.ai.summary.contains("need attention"))

        assertEquals("1/2 up", overview.infra.upLabel)
        assertEquals("db-node-1", overview.infra.offlineNode)
        assertEquals(0, overview.infra.containers)
        assertEquals(0, overview.infra.pods)
        assertEquals(listOf("CPU", "Mem", "Disk", "Net"), overview.infra.gauges.map { gauge -> gauge.label })

        assertEquals("1/2 up", overview.uptime.upLabel)
        assertEquals("1 status pages", overview.uptime.statusPages)
        assertEquals(setOf("Checkout", "Website"), overview.uptime.monitors.map { monitor -> monitor.name }.toSet())
        val checkoutMonitor = assertNotNull(overview.uptime.monitors.find { monitor -> monitor.name == "Checkout" })
        assertTrue(checkoutMonitor.down)
        assertEquals("DOWN", checkoutMonitor.uptimeLabel)

        assertEquals("v1.2.3", overview.deploys.single().version)
        assertEquals("Backend API", overview.deploys.single().service)
        assertEquals("v1.2.3", overview.telemetry.deployLabel)
        assertEquals(100, overview.telemetry.deployAtPct)

        assertEquals("Checkout unavailable", overview.triage.incidents.single().title)
        assertEquals("error", overview.triage.alerts.single().level)
        assertEquals("cpu > 90.0", overview.triage.alerts.single().title)
        assertTrue(overview.triage.issues.isEmpty())
        assertTrue(overview.triage.security.isEmpty())

        val uptimeKpi = assertNotNull(overview.kpis.find { kpi -> kpi.id == "uptime" })
        assertEquals("50.00", uptimeKpi.value)
        assertEquals("bad", uptimeKpi.status)
        assertEquals(6, overview.kpis.size)

        assertEquals(listOf("incident", "deploy"), overview.activity.map { item -> item.kind })
        assertTrue(overview.activity.first().text.contains("Checkout unavailable"))
        assertTrue(overview.serviceHealth.isEmpty())
    }

    @Test
    fun `overview maps analytics rows into service health telemetry and triage`() = runBlocking {
        val orgId = seedOverviewData()
        stubOverviewClickHouse()

        try {
            val overview = OverviewService().getOverview(orgId, demoEpochMs = DEMO_EPOCH_MS)

            assertEquals(3, overview.serviceHealth.size)
            assertEquals(listOf("bad", "warn", "good"), overview.serviceHealth.map { service -> service.status })
            assertEquals("prod", overview.serviceHealth.first().env)
            assertEquals(800, overview.serviceHealth.first().p95Ms)
            assertEquals(40, overview.serviceHealth.first().issues)

            assertEquals("1.4k", overview.kpis.first { kpi -> kpi.id == "errors" }.value)
            assertEquals("bad", overview.kpis.first { kpi -> kpi.id == "latency" }.status)
            assertEquals("2", overview.kpis.first { kpi -> kpi.id == "throughput" }.value)
            assertEquals("bad", overview.kpis.first { kpi -> kpi.id == "apdex" }.status)

            assertEquals("Checkout failed", overview.triage.issues.first().title)
            assertEquals("warn", overview.triage.issues.first().level)
            assertEquals("fatal", overview.triage.issues.last().level)

            assertEquals(5, overview.infra.containers)
            assertEquals(2, overview.infra.pods)
            assertEquals("checkout synthetic", overview.uptime.syntheticFailing)
            assertTrue(overview.telemetry.latency.any { value -> value > 0 })
            assertTrue(overview.telemetry.throughput.any { value -> value > 0 })
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `overview with no relational data reports healthy empty state`() = runBlocking {
        val orgId = seedEmptyOrganization()

        val overview = OverviewService().getOverview(orgId, demoEpochMs = DEMO_EPOCH_MS)

        assertEquals("Healthy", overview.systemStatus.state)
        assertEquals("good", overview.systemStatus.severity)
        assertEquals("0/0 up", overview.infra.upLabel)
        assertEquals(null, overview.infra.offlineNode)
        assertEquals("No deploys", overview.telemetry.deployLabel)
        assertEquals(0, overview.telemetry.deployAtPct)
        assertEquals("0/0 up", overview.uptime.upLabel)
        assertTrue(overview.activity.isEmpty())
    }

    @Test
    fun `trace summary subquery reads finalized and live trace rollups`() {
        val query = OverviewService().traceSummarySubquery(
            organizationId = -1,
            demoEpochMs = DEMO_EPOCH_MS,
        )

        assertTrue(query.contains("FROM apm_traces_final"))
        assertTrue(query.contains("UNION ALL"))
        assertTrue(query.contains("FROM apm_trace_summaries"))
        assertTrue(query.contains("toInt64(organization_id) IN (-1, -2, -3)"))
        assertTrue(query.contains("toDateTime64(1709312400.000, 3) - INTERVAL 24 HOUR"))
        assertFalse(query.contains("now() - INTERVAL 24 HOUR"))
    }

    @Test
    fun `previous trace summary subquery only reads finalized comparison window`() {
        val query = OverviewService().traceSummarySubquery(
            organizationId = 42,
            demoEpochMs = null,
            previousWindow = true,
        )

        assertTrue(query.contains("FROM apm_traces_final"))
        assertFalse(query.contains("UNION ALL"))
        assertFalse(query.contains("FROM apm_trace_summaries"))
        assertTrue(query.contains("organization_id = 42"))
        assertTrue(query.contains("trace_bucket >= toStartOfHour(now() - INTERVAL 48 HOUR)"))
        assertTrue(query.contains("trace_bucket < toStartOfHour(now() - INTERVAL 24 HOUR)"))
    }

    private companion object {
        private const val DEMO_EPOCH_MS = 1_709_312_400_000L
        private const val ORGANIZATION_ID = 101
        private const val EMPTY_ORGANIZATION_ID = 202
        private const val HOUR_MILLIS = 3_600_000L
        private const val TRACE_CURRENT_ROW =
            """{"currentTraces":2880,"currentErrors":40,"p95Ms":800,"satisfied":1000,"tolerated":2000}"""
        private const val TRACE_PREVIOUS_ROW =
            """{"previousTraces":1440,"previousP95Ms":350,"previousSatisfied":1300,"previousTolerated":1440}"""
        private const val EVENT_COUNT_ROW = """{"currentErrors":1200,"previousErrors":600}"""
        private const val ISSUE_COUNT_ROW = """{"openIssues":3,"newIssues":1}"""
        private const val LOG_COUNT_ROW = """{"currentErrors":200,"previousErrors":100}"""
        private const val CONTAINER_COUNT_ROW = """{"containers":5,"pods":2}"""
        private const val SYNTHETIC_FAILING_ROW = """{"name":"checkout synthetic"}"""
        private val SERVICE_ROWS = listOf(
            """{"service":"checkout-api","env":"","traces":1440,"errors":40,"p95Ms":800,""" +
                """"satisfied":200,"tolerated":400}""",
            """{"service":"search-api","env":"staging","traces":1440,"errors":15,"p95Ms":450,""" +
                """"satisfied":1300,"tolerated":1400}""",
            """{"service":"worker","env":"prod","traces":1440,"errors":0,"p95Ms":100,""" +
                """"satisfied":1440,"tolerated":1440}""",
        ).joinToString("\n")
        private val ISSUE_ROWS = listOf(
            """{"title":"Checkout failed","level":"warning","eventCount":7,"ageSeconds":120}""",
            """{"title":"Worker crash","level":"fatal","eventCount":2,"ageSeconds":3600}""",
        ).joinToString("\n")
    }

    private fun seedOverviewData(): Int {
        Database.connect(
            url = "jdbc:h2:mem:overview_service_${System.nanoTime()};DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        ).also { database -> TransactionManager.defaultDatabase = database }

        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Projects,
            Hosts,
            HostAlerts,
            Releases,
            AlertEpisodes,
            StatusPages,
            UptimeMonitors,
        )

        val now = Clock.System.now()
        transaction {
            Organizations.insert {
                it[id] = ORGANIZATION_ID
                it[name] = "Overview Org"
                it[slug] = "overview-org"
            }
            val projectId = Projects.insert {
                it[organization_id] = ORGANIZATION_ID
                it[name] = "Backend API"
                it[slug] = "backend-api"
            }[Projects.id]
            val offlineHostId = Hosts.insert {
                it[organization_id] = ORGANIZATION_ID
                it[hostname] = "db-01.internal"
                it[display_name] = "db-node-1"
                it[status] = "offline"
                it[first_seen_at] = now
                it[last_seen_at] = now
            }[Hosts.id]
            Hosts.insert {
                it[organization_id] = ORGANIZATION_ID
                it[hostname] = "web-01.internal"
                it[display_name] = "web-node-1"
                it[status] = "online"
                it[first_seen_at] = now
                it[last_seen_at] = now
            }
            HostAlerts.insert {
                it[host_id] = offlineHostId
                it[organization_id] = ORGANIZATION_ID
                it[metric] = "cpu"
                it[condition] = ">"
                it[threshold] = 90.0
                it[duration_seconds] = 300
                it[enabled] = true
                it[last_triggered_at] = now
                it[alert_priority] = "p1"
                it[created_at] = now
            }
            Releases.insert {
                it[project_id] = projectId
                it[version] = "v1.2.3"
                it[created_at] = Clock.System.now().toEpochMilliseconds() - HOUR_MILLIS
            }
            AlertEpisodes.insert {
                it[organizationId] = ORGANIZATION_ID
                it[sourceName] = "Checkout unavailable"
                it[deduplicationKey] = "checkout-unavailable"
                it[episodeSeq] = 1
                it[episodeKey] = "checkout-unavailable-1"
                it[status] = "FIRING"
                it[openedAt] = now
                it[lastSeenAt] = now
                it[createdAt] = now
                it[updatedAt] = now
            }
            StatusPages.insert {
                it[organizationId] = ORGANIZATION_ID
                it[name] = "Public Status"
                it[slug] = "public-status-${UUID.randomUUID()}"
                it[createdAt] = now
                it[updatedAt] = now
            }
            seedMonitor("Checkout", "down")
            seedMonitor("Website", "up")
        }
        return ORGANIZATION_ID
    }

    private fun seedEmptyOrganization(): Int {
        Database.connect(
            url = "jdbc:h2:mem:overview_empty_${System.nanoTime()};DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        ).also { database -> TransactionManager.defaultDatabase = database }

        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Projects,
            Hosts,
            HostAlerts,
            Releases,
            AlertEpisodes,
            StatusPages,
            UptimeMonitors,
        )

        transaction {
            Organizations.insert {
                it[id] = EMPTY_ORGANIZATION_ID
                it[name] = "Empty Org"
                it[slug] = "empty-org"
            }
        }
        return EMPTY_ORGANIZATION_ID
    }

    private fun seedMonitor(name: String, status: String) {
        UptimeMonitors.insert {
            it[id] = UUID.randomUUID()
            it[organizationId] = ORGANIZATION_ID
            it[UptimeMonitors.name] = name
            it[type] = "http"
            it[active] = true
            it[url] = "https://example.com/$name"
            it[intervalSeconds] = 60
            it[timeoutSeconds] = 30
            it[retries] = 0
            it[retryIntervalSeconds] = 60
            it[UptimeMonitors.status] = status
            it[createdAt] = Clock.System.now()
            it[updatedAt] = Clock.System.now()
        }
    }

    private fun stubOverviewClickHouse() {
        mockkObject(ClickHouseClient)
        every { ClickHouseClient.isInitialized() } returns true
        every { ClickHouseClient.getDatabase() } returns "moneat_test"
        coEvery { ClickHouseClient.execute(any()) } throws IllegalStateException(
            "ClickHouse metrics unavailable",
        )
        coEvery { ClickHouseClient.execute(any(), any()) } throws IllegalStateException(
            "ClickHouse metrics unavailable",
        )
        coEvery { ClickHouseClient.executeWithFormat(any(), "JSONEachRow") } coAnswers {
            overviewClickHouseRows(firstArg())
        }
    }

    private fun overviewClickHouseRows(query: String): String =
        overviewQueryStubs().firstOrNull { stub -> stub.matches(query) }?.body.orEmpty()

    private fun overviewQueryStubs(): List<QueryStub> =
        listOf(
            QueryStub({ query -> query.contains("count() AS currentTraces") }, TRACE_CURRENT_ROW),
            QueryStub({ query -> query.contains("count() AS previousTraces") }, TRACE_PREVIOUS_ROW),
            QueryStub({ query -> query.contains("GROUP BY root_service") }, SERVICE_ROWS),
            QueryStub({ query -> query.contains("round(100 * countIf") }, seriesRows(80, 88)),
            QueryStub(::isTraceLatencySeriesQuery, seriesRows(420, 800)),
            QueryStub(::isTraceThroughputSeriesQuery, seriesRows(1, 2)),
            QueryStub(::isEventCountQuery, EVENT_COUNT_ROW),
            QueryStub(::isIssueCountQuery, ISSUE_COUNT_ROW),
            QueryStub(::isIssueRowsQuery, ISSUE_ROWS),
            QueryStub(::isEventSeriesQuery, seriesRows(4, 12)),
            QueryStub(::isIssueSeriesQuery, seriesRows(1, 2)),
            QueryStub(::isLogCountQuery, LOG_COUNT_ROW),
            QueryStub(::isLogErrorSeriesQuery, seriesRows(5, 7)),
            QueryStub(::isLogVolumeSeriesQuery, seriesRows(300, 450)),
            QueryStub({ query -> query.contains("FROM containers_latest_by_host") }, CONTAINER_COUNT_ROW),
            QueryStub({ query -> query.contains("FROM synthetic_results") }, SYNTHETIC_FAILING_ROW),
        )

    private fun isTraceLatencySeriesQuery(query: String): Boolean =
        query.contains("quantileExact(0.95)(duration_ns") && query.contains("GROUP BY bucket")

    private fun isTraceThroughputSeriesQuery(query: String): Boolean =
        query.contains("count() AS value") && query.contains("FROM (")

    private fun isEventCountQuery(query: String): Boolean =
        query.contains("FROM events") && query.contains("currentErrors")

    private fun isIssueCountQuery(query: String): Boolean =
        query.contains("FROM issues FINAL") && query.contains("openIssues")

    private fun isIssueRowsQuery(query: String): Boolean =
        query.contains("FROM issues FINAL") && query.contains("eventCount")

    private fun isEventSeriesQuery(query: String): Boolean =
        query.contains("FROM events") && query.contains("GROUP BY bucket")

    private fun isIssueSeriesQuery(query: String): Boolean =
        query.contains("FROM issues FINAL") && query.contains("GROUP BY bucket")

    private fun isLogCountQuery(query: String): Boolean =
        query.contains("FROM logs") && query.contains("currentErrors")

    private fun isLogErrorSeriesQuery(query: String): Boolean =
        query.contains("FROM logs") && query.contains("countIf(level IN")

    private fun isLogVolumeSeriesQuery(query: String): Boolean =
        query.contains("FROM logs") && query.contains("count() AS value")

    private fun seriesRows(first: Int, last: Int): String =
        """
        {"bucket":0,"value":$first}
        {"bucket":23,"value":$last}
        {"bucket":24,"value":999}
        not-json
        """.trimIndent()

    private data class QueryStub(
        val matches: (String) -> Boolean,
        val body: String,
    )
}
