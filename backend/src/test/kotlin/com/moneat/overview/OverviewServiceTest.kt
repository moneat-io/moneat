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
        private const val HOUR_MILLIS = 3_600_000L
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
}
