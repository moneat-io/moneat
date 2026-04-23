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

import com.moneat.config.ClickHouseClient
import com.moneat.events.services.DashboardService
import com.moneat.events.services.ReleaseService
import com.moneat.incident.models.IncidentEventLog
import com.moneat.incident.models.IncidentProviderConfigs
import com.moneat.monitor.models.AlertResponse
import com.moneat.monitor.models.HostData
import com.moneat.monitor.models.LatestMetrics
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.summary.services.SummaryService
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import com.moneat.testsupport.withSummaryServiceMockServer
import com.moneat.uptime.models.UptimeMonitorResponse
import com.moneat.uptime.services.UptimeService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class SummaryServiceTest {
    companion object {
        private var db: Database? = null
        private const val ORG_ID = 1
        private const val PROJECT_ID = 1L
        private const val TEST_ORG = "Test Org"
        private const val TEST_ORG_SLUG = "test-org"
        private const val DEDUP_KEY = "moneat-host-alert-42-id_7"
        private const val DATA_EMPTY_JSON = """{"data":[]}"""
        private const val COUNT_AS_TOTAL = "count() as total"
        private const val ISSUE_ID_ANY_TITLE = "issue_id, any(message) as title"
        private const val TOTAL_ZERO_JSON = """{"total":0}"""
        private const val TEXT_PLAIN = "text/plain"
    }

    private val monitorService = mockk<MonitorService>()
    private val uptimeService = mockk<UptimeService>()
    private val alertService = mockk<MonitorAlertService>()
    private val dashboardService = mockk<DashboardService>()
    private val releaseService = mockk<ReleaseService>()

    private lateinit var service: SummaryService

    @BeforeTest
    fun setup() {
        service = SummaryService(
            monitorService = monitorService,
            uptimeService = uptimeService,
            alertService = alertService,
            dashboardService = dashboardService,
            releaseService = releaseService
        )

        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_summary_svc;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Organizations,
            Projects,
            IncidentProviderConfigs,
            IncidentEventLog
        )
    }

    private fun seedOrgAndProject() {
        transaction {
            Organizations.insert {
                it[id] = ORG_ID
                it[name] = TEST_ORG
                it[slug] = TEST_ORG_SLUG
            }
            Projects.insert {
                it[id] = PROJECT_ID
                it[organization_id] = ORG_ID
                it[name] = "Test Project"
                it[slug] = "test-project"
            }
        }
    }

    private fun seedIncidentLog(
        orgId: Int = ORG_ID,
        dedupKey: String = DEDUP_KEY,
        alertSource: String = "host_alert"
    ) {
        transaction {
            IncidentProviderConfigs.insert {
                it[organizationId] = orgId
                it[providerType] = "pagerduty"
                it[name] = "PD Config"
                it[apiKey] = "test-key"
                it[configJson] = "{}"
                it[enabled] = true
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
            IncidentEventLog.insert {
                it[organizationId] = orgId
                it[providerConfigId] = 1
                it[this.alertSource] = alertSource
                it[deduplicationKey] = dedupKey
                it[incidentSeverity] = "critical"
                it[incidentStatus] = "triggered"
                it[title] = "CPU alert fired"
                it[success] = true
                it[createdAt] = Clock.System.now()
            }
        }
    }

    private fun makeHost(
        id: Int = 42,
        orgId: Int = ORG_ID,
        status: String = "online",
        displayName: String? = "web-1",
        lastSeenAt: kotlin.time.Instant? = Clock.System.now()
    ): HostData = HostData(
        id = id,
        organizationId = orgId,
        hostname = "host-$id",
        displayName = displayName,
        status = status,
        lastSeenAt = lastSeenAt,
        agentVersion = "1.0",
        os = "linux",
        arch = "amd64",
        platform = null,
        processor = null,
        cpuCores = null,
        memoryTotalKb = null,
        firstSeenAt = Clock.System.now(),
        createdAt = Clock.System.now()
    )

    private fun makeMonitor(
        id: String = "mon-1",
        name: String = "API Monitor",
        status: String = "up"
    ): UptimeMonitorResponse = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.name } returns name
        every { this@mockk.status } returns status
        every { this@mockk.uptime24h } returns 99.9f
        every { this@mockk.uptime7d } returns 99.5f
        every { this@mockk.uptime30d } returns 99.0f
    }

    private fun makeAlert(
        id: Int = 7,
        hostId: Int? = 42,
        metric: String = "cpu_percent",
        condition: String = "gt",
        threshold: Double = 90.0,
        lastTriggered: Long? = System.currentTimeMillis()
    ): AlertResponse = AlertResponse(
        id = id,
        systemId = hostId?.toString(),
        hostId = hostId,
        metric = metric,
        condition = condition,
        threshold = threshold,
        durationSeconds = 60,
        enabled = true,
        lastTriggeredAt = lastTriggered,
        createdAt = System.currentTimeMillis()
    )

    private fun makeLatestMetrics(
        cpu: Float = 55.0f,
        mem: Float = 70.0f
    ): LatestMetrics = LatestMetrics(
        cpuPercent = cpu,
        memTotal = 16000000L,
        memUsed = 11200000L,
        memPercent = mem,
        diskTotal = 500000000L,
        diskUsed = 250000000L,
        diskPercent = 50.0f,
        netRecvBytes = 1000L,
        netSentBytes = 2000L,
        netRecvMbps = 1.0f,
        netSentMbps = 2.0f,
        load1 = 1.5f,
        tempMax = null,
        gpuPercent = null,
        batteryPercent = null
    )

    private fun mockClickHouseHandler(
        scalarTotal: Long = 10L,
        issueData: String = DATA_EMPTY_JSON,
        dailyData: String = DATA_EMPTY_JSON,
        latencyData: String = DATA_EMPTY_JSON,
        logData: String = DATA_EMPTY_JSON,
        spikeData: String = DATA_EMPTY_JSON
    ): (com.sun.net.httpserver.HttpExchange) -> Unit = { exchange ->
        val query = exchange.requestBodyText()
        val body = when {
            query.contains(COUNT_AS_TOTAL) ->
                """{"total":$scalarTotal}"""
            query.contains(ISSUE_ID_ANY_TITLE) &&
                query.contains("ORDER BY event_count") ->
                issueData
            query.contains("toDate(timestamp) as date") ->
                dailyData
            query.contains("quantile(0.95)(duration)") ->
                latencyData
            query.contains("level, message") || query.contains("service_name as service") ->
                logData
            query.contains("min(timestamp) as first_seen") ->
                spikeData
            query.contains(ISSUE_ID_ANY_TITLE) &&
                query.contains("ORDER BY event_count DESC") ->
                issueData
            else -> TOTAL_ZERO_JSON
        }
        exchange.respond(200, body, TEXT_PLAIN)
    }

    // ──── getInfrastructureSummary tests ────

    @Test
    fun `getInfrastructureSummary returns host counts and monitors`() = runBlocking {
        val hosts = listOf(
            makeHost(id = 1, status = "online"),
            makeHost(id = 2, status = "offline"),
            makeHost(id = 3, status = "warning")
        )
        val monitors = listOf(makeMonitor())

        every { monitorService.listHosts(ORG_ID) } returns hosts
        every { uptimeService.listMonitors(ORG_ID) } returns monitors
        every { monitorService.listAlerts(any(), any()) } returns emptyList()

        val result = service.getInfrastructureSummary(ORG_ID, "24h")

        assertEquals("24h", result.period)
        assertEquals(1, result.hostCounts.online)
        assertEquals(1, result.hostCounts.offline)
        assertEquals(1, result.hostCounts.warning)
        assertEquals(3, result.hostCounts.total)
        assertEquals(1, result.uptimeMonitors.size)
        assertEquals("mon-1", result.uptimeMonitors.first().id)
        assertEquals("API Monitor", result.uptimeMonitors.first().name)
    }

    @Test
    fun `getInfrastructureSummary returns top alerts sorted by trigger time`() = runBlocking {
        val host = makeHost(id = 1, status = "online")
        val now = System.currentTimeMillis()
        val alerts = listOf(
            makeAlert(id = 1, hostId = 1, lastTriggered = now - 1000),
            makeAlert(id = 2, hostId = 1, lastTriggered = now)
        )

        every { monitorService.listHosts(ORG_ID) } returns listOf(host)
        every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
        every { monitorService.listAlerts(1, ORG_ID) } returns alerts

        val result = service.getInfrastructureSummary(ORG_ID, "24h")

        assertEquals(2, result.topAlerts.size)
        assertEquals(2, result.topAlerts.first().alertId)
    }

    @Test
    fun `getInfrastructureSummary filters alerts outside period`() = runBlocking {
        val host = makeHost(id = 1, status = "online")
        val longAgo = System.currentTimeMillis() - 31L * 24 * 3600 * 1000

        every { monitorService.listHosts(ORG_ID) } returns listOf(host)
        every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
        every { monitorService.listAlerts(1, ORG_ID) } returns listOf(
            makeAlert(id = 1, hostId = 1, lastTriggered = longAgo)
        )

        val result = service.getInfrastructureSummary(ORG_ID, "24h")

        assertTrue(result.topAlerts.isEmpty())
    }

    @Test
    fun `getInfrastructureSummary top error rate hosts sorted by alert count`() = runBlocking {
        val host1 = makeHost(id = 1, status = "online")
        val host2 = makeHost(id = 2, status = "online")
        val now = System.currentTimeMillis()

        every { monitorService.listHosts(ORG_ID) } returns listOf(host1, host2)
        every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
        every { monitorService.listAlerts(1, ORG_ID) } returns listOf(
            makeAlert(id = 1, hostId = 1, lastTriggered = now),
            makeAlert(id = 2, hostId = 1, lastTriggered = now)
        )
        every { monitorService.listAlerts(2, ORG_ID) } returns listOf(
            makeAlert(id = 3, hostId = 2, lastTriggered = now)
        )

        val result = service.getInfrastructureSummary(ORG_ID, "7d")

        assertEquals(2, result.topErrorRateHosts.size)
        assertEquals("1", result.topErrorRateHosts.first().systemId)
        assertEquals(2, result.topErrorRateHosts.first().alertCount)
    }

    @Test
    fun `getInfrastructureSummary uses 30d period`() = runBlocking {
        val host = makeHost(id = 1, status = "online")
        val now = System.currentTimeMillis()
        val twentyDaysAgo = now - 20L * 24 * 3600 * 1000

        every { monitorService.listHosts(ORG_ID) } returns listOf(host)
        every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
        every { monitorService.listAlerts(1, ORG_ID) } returns listOf(
            makeAlert(id = 1, hostId = 1, lastTriggered = twentyDaysAgo)
        )

        val result = service.getInfrastructureSummary(ORG_ID, "30d")

        assertEquals(1, result.topAlerts.size)
    }

    @Test
    fun `getInfrastructureSummary unknown period defaults to 24h`() = runBlocking {
        val host = makeHost(id = 1, status = "online")
        val now = System.currentTimeMillis()
        val twoDaysAgo = now - 2L * 24 * 3600 * 1000

        every { monitorService.listHosts(ORG_ID) } returns listOf(host)
        every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
        every { monitorService.listAlerts(1, ORG_ID) } returns listOf(
            makeAlert(id = 1, hostId = 1, lastTriggered = twoDaysAgo)
        )

        val result = service.getInfrastructureSummary(ORG_ID, "unknown")

        assertTrue(result.topAlerts.isEmpty())
    }

    @Test
    fun `getInfrastructureSummary excludes hosts with zero alert count`() = runBlocking {
        val host = makeHost(id = 1, status = "online")

        every { monitorService.listHosts(ORG_ID) } returns listOf(host)
        every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
        every { monitorService.listAlerts(1, ORG_ID) } returns listOf(
            makeAlert(id = 1, hostId = 1, lastTriggered = null)
        )

        val result = service.getInfrastructureSummary(ORG_ID, "24h")

        assertTrue(result.topErrorRateHosts.isEmpty())
    }

    @Test
    fun `getInfrastructureSummary uses displayName fallback to hostname`() = runBlocking {
        val host = makeHost(id = 1, status = "online", displayName = null)
        val now = System.currentTimeMillis()

        every { monitorService.listHosts(ORG_ID) } returns listOf(host)
        every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
        every { monitorService.listAlerts(1, ORG_ID) } returns listOf(
            makeAlert(id = 1, hostId = 1, lastTriggered = now)
        )

        val result = service.getInfrastructureSummary(ORG_ID, "24h")

        assertEquals("host-1", result.topErrorRateHosts.first().systemName)
    }

    // ──── getOvernightSummary tests ────

    @Test
    fun `getOvernightSummary returns data with ClickHouse responses`() = runBlocking {
        val issueJson = """{"data":[
            {"issue_id":"iss-1","title":"NullPointer","event_count":5,"pid":1}
        ]}"""
        withSummaryServiceMockServer(
            mockClickHouseHandler(scalarTotal = 20, issueData = issueJson),
            db,
            ORG_ID,
            { seedOrgAndProject() },
            monitorService,
            uptimeService
        ) {
            val result = service.getOvernightSummary(ORG_ID, "UTC")
            assertEquals("UTC", result.timezone)
            assertNotNull(result.windowStart)
            assertNotNull(result.windowEnd)
            assertEquals(1, result.newIssues.size)
            assertEquals("iss-1", result.newIssues.first().issueId)
            assertTrue(result.regressedIssues.isEmpty())
        }
    }

    @Test
    fun `getOvernightSummary detects error spike`() = runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val today = java.time.ZonedDateTime.now(zone).toLocalDate()
        val dayBefore = today.minusDays(2).toString()
        withSummaryServiceMockServer(
            { exchange ->
                val query = exchange.requestBodyText()
                val body = when {
                    query.contains("event_type = 'error'") && query.contains(COUNT_AS_TOTAL) ->
                        if (query.contains(dayBefore)) """{"total":10}""" else """{"total":100}"""
                    query.contains("issue_id") -> DATA_EMPTY_JSON
                    query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                    else -> ""
                }
                exchange.respond(200, body, TEXT_PLAIN)
            },
            db,
            ORG_ID,
            { seedOrgAndProject() },
            monitorService,
            uptimeService
        ) {
            val result = service.getOvernightSummary(ORG_ID, "UTC")
            assertTrue(result.errorSpikes.spikeDetected)
            assertEquals(100L, result.errorSpikes.overnightCount)
            assertEquals(10L, result.errorSpikes.baselineCount)
        }
    }

    @Test
    fun `getOvernightSummary includes host status changes for offline hosts`() = runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val today = java.time.ZonedDateTime.now(zone).toLocalDate()
        val withinWindow = today.minusDays(1).atTime(23, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val offlineHost = makeHost(
            id = 10,
            status = "offline",
            lastSeenAt = kotlin.time.Instant.fromEpochMilliseconds(withinWindow)
        )
        withSummaryServiceMockServer(
            mockClickHouseHandler(),
            db,
            ORG_ID,
            { seedOrgAndProject() },
            monitorService,
            uptimeService
        ) {
            every { monitorService.listHosts(ORG_ID) } returns listOf(offlineHost)
            val result = service.getOvernightSummary(ORG_ID, "UTC")
            assertEquals(1, result.hostStatusChanges.size)
            assertEquals("offline", result.hostStatusChanges.first().currentStatus)
            assertEquals("online", result.hostStatusChanges.first().previousStatus)
        }
    }

    @Test
    fun `getOvernightSummary includes down uptime monitors`() = runBlocking {
        withSummaryServiceMockServer(
            mockClickHouseHandler(),
            db,
            ORG_ID,
            { seedOrgAndProject() },
            monitorService,
            uptimeService
        ) {
            every { uptimeService.listMonitors(ORG_ID) } returns listOf(
                makeMonitor(id = "mon-down", name = "Down Monitor", status = "down")
            )
            val result = service.getOvernightSummary(ORG_ID, "UTC")
            assertEquals(1, result.uptimeIncidents.size)
            assertEquals("mon-down", result.uptimeIncidents.first().monitorId)
        }
    }

    @Test
    fun `getOvernightSummary log volume change percent`() = runBlocking {
        withSummaryServiceMockServer(
            { exchange ->
                val query = exchange.requestBodyText()
                val body = when {
                    query.contains("logs") && query.contains(COUNT_AS_TOTAL) -> """{"total":30}"""
                    query.contains(COUNT_AS_TOTAL) -> """{"total":5}"""
                    query.contains("issue_id") -> DATA_EMPTY_JSON
                    else -> ""
                }
                exchange.respond(200, body, TEXT_PLAIN)
            },
            db,
            ORG_ID,
            { seedOrgAndProject() },
            monitorService,
            uptimeService
        ) {
            val result = service.getOvernightSummary(ORG_ID, "UTC")
            assertEquals(30L, result.logErrorVolume.overnightErrors)
            assertEquals(30L, result.logErrorVolume.previousNightErrors)
            assertEquals(0.0, result.logErrorVolume.changePercent)
        }
    }

    @Test
    fun `getOvernightSummary log volume zero baseline gives zero change percent`() = runBlocking {
        withSummaryServiceMockServer(
            { exchange ->
                val query = exchange.requestBodyText()
                val body = when {
                    query.contains("logs") -> TOTAL_ZERO_JSON
                    query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                    query.contains("issue_id") -> DATA_EMPTY_JSON
                    else -> ""
                }
                exchange.respond(200, body, TEXT_PLAIN)
            },
            db,
            ORG_ID,
            { seedOrgAndProject() },
            monitorService,
            uptimeService
        ) {
            val result = service.getOvernightSummary(ORG_ID, "UTC")
            assertEquals(0.0, result.logErrorVolume.changePercent)
        }
    }

    @Test
    fun `getOvernightSummary with no projects returns empty issues`() = runBlocking {
        withSummaryServiceMockServer(
            mockClickHouseHandler(),
            db,
            ORG_ID,
            {
                transaction {
                    Organizations.insert {
                        it[id] = ORG_ID
                        it[name] = TEST_ORG
                        it[slug] = TEST_ORG_SLUG
                    }
                }
            },
            monitorService,
            uptimeService
        ) {
            val result = service.getOvernightSummary(ORG_ID, "UTC")
            assertTrue(result.newIssues.isEmpty())
        }
    }

    // ──── getWeeklyReport tests ────

    @Test
    fun `getWeeklyReport returns complete report with ClickHouse data`() = runBlocking {
        val dailyJson = """{"data":[
            {"date":"2026-01-01","count":5},
            {"date":"2026-01-02","count":8}
        ]}"""
        val latencyJson = """{"data":[
            {"name":"/api/users","p95":"120.5","cnt":100}
        ]}"""
        val noisyJson = """{"data":[
            {"issue_id":"noisy-1","title":"Timeout","event_count":500,"pid":1}
        ]}"""
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains("toDate(timestamp) as date") -> dailyJson
                query.contains("quantile(0.95)(duration)") -> latencyJson
                query.contains("issue_id, any(message) as title") -> noisyJson
                query.contains(COUNT_AS_TOTAL) -> """{"total":42}"""
                else -> ""
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            every { monitorService.listHosts(ORG_ID) } returns emptyList()
            every { uptimeService.listMonitors(ORG_ID) } returns listOf(makeMonitor())

            val result = service.getWeeklyReport(ORG_ID)

            assertNotNull(result.periodStart)
            assertNotNull(result.periodEnd)
            assertEquals(2, result.errorTrend.size)
            assertEquals("2026-01-01", result.errorTrend.first().date)
            assertEquals(1, result.topTransactionLatencies.size)
            assertEquals("/api/users", result.topTransactionLatencies.first().name)
            assertEquals(120.5, result.topTransactionLatencies.first().p95)
            assertEquals(1, result.topNoisyIssues.size)
            assertEquals("noisy-1", result.topNoisyIssues.first().issueId)
            assertEquals(1, result.uptimeSummary.size)
            assertEquals(0, result.incidentCount)
            assertNull(result.mttrMinutes)
        }
    }

    @Test
    fun `getWeeklyReport computes week over week delta`() = runBlocking {
        var scalarCallCount = 0
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> {
                    scalarCallCount++
                    if (scalarCallCount <= 1) """{"total":50}""" else """{"total":200}"""
                }
                query.contains("toDate(timestamp)") -> DATA_EMPTY_JSON
                query.contains("quantile") -> DATA_EMPTY_JSON
                query.contains("issue_id") -> DATA_EMPTY_JSON
                else -> ""
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            every { monitorService.listHosts(ORG_ID) } returns emptyList()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()

            val result = service.getWeeklyReport(ORG_ID)

            assertNotNull(result.weekOverWeekDelta)
        }
    }

    @Test
    fun `getWeeklyReport zero previous week gives zero delta`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            every { monitorService.listHosts(ORG_ID) } returns emptyList()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()

            val result = service.getWeeklyReport(ORG_ID)

            assertEquals(0.0, result.weekOverWeekDelta)
        }
    }

    @Test
    fun `getWeeklyReport includes host resource trends from metrics`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            val host = makeHost(id = 1, status = "online")
            every { monitorService.listHosts(ORG_ID) } returns listOf(host)
            coEvery { monitorService.getLatestMetrics(1) } returns makeLatestMetrics(
                cpu = 60.0f,
                mem = 80.0f
            )
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()

            val result = service.getWeeklyReport(ORG_ID)

            assertEquals(60.0, result.hostResourceTrends.avgCpuPercent, 0.1)
            assertEquals(80.0, result.hostResourceTrends.avgMemoryPercent, 0.1)
            assertEquals(1, result.hostResourceTrends.hostCount)
        }
    }

    @Test
    fun `getWeeklyReport no hosts gives zero resource trends`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            every { monitorService.listHosts(ORG_ID) } returns emptyList()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()

            val result = service.getWeeklyReport(ORG_ID)

            assertEquals(0.0, result.hostResourceTrends.avgCpuPercent)
            assertEquals(0.0, result.hostResourceTrends.avgMemoryPercent)
            assertEquals(0, result.hostResourceTrends.hostCount)
        }
    }

    @Test
    fun `getWeeklyReport handles metrics failure gracefully`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            val host = makeHost(id = 1, status = "online")
            every { monitorService.listHosts(ORG_ID) } returns listOf(host)
            coEvery { monitorService.getLatestMetrics(1) } throws RuntimeException("connection refused")
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()

            val result = service.getWeeklyReport(ORG_ID)

            assertEquals(0.0, result.hostResourceTrends.avgCpuPercent)
            assertEquals(0.0, result.hostResourceTrends.avgMemoryPercent)
        }
    }

    @Test
    fun `getWeeklyReport with no projects returns empty lists`() = runBlocking {
        MockHttpServer(mockClickHouseHandler()).use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            transaction {
                Organizations.insert {
                    it[id] = ORG_ID
                    it[name] = TEST_ORG
                    it[slug] = TEST_ORG_SLUG
                }
            }
            every { monitorService.listHosts(ORG_ID) } returns emptyList()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()

            val result = service.getWeeklyReport(ORG_ID)

            assertTrue(result.errorTrend.isEmpty())
            assertTrue(result.topTransactionLatencies.isEmpty())
            assertTrue(result.topNoisyIssues.isEmpty())
        }
    }

    // ──── getIncidentContext tests ────

    @Test
    fun `getIncidentContext with incident log entry`() = runBlocking {
        val spikeJson = """{"data":[
            {"issue_id":"spike-1","title":"OOM","cnt":50,"first_seen":"2026-01-01T00:00:00Z"}
        ]}"""
        val logJson = """{"data":[
            {"timestamp":"2026-01-01T03:00:00Z","level":"error","message":"DB timeout","service":"api"}
        ]}"""
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains("min(timestamp) as first_seen") -> spikeJson
                query.contains("service_name as service") -> logJson
                query.contains(COUNT_AS_TOTAL) -> """{"total":5}"""
                query.contains("issue_id") -> DATA_EMPTY_JSON
                else -> ""
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            seedIncidentLog(dedupKey = DEDUP_KEY)

            val host = makeHost(id = 42, status = "online")
            val alert = makeAlert(id = 7, hostId = 42)
            every { monitorService.listHosts(ORG_ID) } returns listOf(host)
            every { monitorService.listAlerts(42, ORG_ID) } returns listOf(alert)
            coEvery { monitorService.getLatestMetrics(42) } returns makeLatestMetrics()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
            every { releaseService.listReleases(PROJECT_ID) } returns emptyList()

            val result = service.getIncidentContext(ORG_ID, 1L)

            assertEquals(1L, result.incidentId)
            assertNotNull(result.triggeringAlert)
            assertEquals(7, result.triggeringAlert.alertId)
            assertEquals("cpu_percent", result.triggeringAlert.metric)
            assertNotNull(result.hostMetrics)
            assertEquals(1, result.errorSpikes.size)
            assertEquals("spike-1", result.errorSpikes.first().issueId)
            assertEquals(1, result.relatedLogs.size)
            assertEquals("error", result.relatedLogs.first().level)
        }
    }

    @Test
    fun `getIncidentContext no incident log falls back to most recent alert`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()

            val host = makeHost(id = 1, status = "online")
            val alert = makeAlert(id = 5, hostId = 1)
            every { monitorService.listHosts(ORG_ID) } returns listOf(host)
            every { monitorService.listAlerts(1, ORG_ID) } returns listOf(alert)
            coEvery { monitorService.getLatestMetrics(1) } returns makeLatestMetrics()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
            every { releaseService.listReleases(PROJECT_ID) } returns emptyList()

            val result = service.getIncidentContext(ORG_ID, 999L)

            assertNotNull(result.triggeringAlert)
            assertEquals(5, result.triggeringAlert.alertId)
            assertNotNull(result.hostMetrics)
        }
    }

    @Test
    fun `getIncidentContext no incident log and no alerts gives null context`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()

            every { monitorService.listHosts(ORG_ID) } returns emptyList()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
            every { releaseService.listReleases(PROJECT_ID) } returns emptyList()

            val result = service.getIncidentContext(ORG_ID, 999L)

            assertNull(result.triggeringAlert)
            assertNull(result.hostMetrics)
        }
    }

    @Test
    fun `getIncidentContext with host-down dedup key`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            seedIncidentLog(dedupKey = "moneat-host-down-42")

            val host = makeHost(id = 42, status = "offline")
            val alert = makeAlert(id = 3, hostId = 42, lastTriggered = System.currentTimeMillis())
            every { monitorService.listHosts(ORG_ID) } returns listOf(host)
            every { monitorService.listAlerts(42, ORG_ID) } returns listOf(alert)
            coEvery { monitorService.getLatestMetrics(42) } returns makeLatestMetrics()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
            every { releaseService.listReleases(PROJECT_ID) } returns emptyList()

            val result = service.getIncidentContext(ORG_ID, 1L)

            assertNotNull(result.triggeringAlert)
            assertEquals("42", result.triggeringAlert.systemId)
        }
    }

    @Test
    fun `getIncidentContext with uptime dedup key gives no host match`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            seedIncidentLog(dedupKey = "moneat-uptime-mon-1")

            every { monitorService.listHosts(ORG_ID) } returns emptyList()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
            every { releaseService.listReleases(PROJECT_ID) } returns emptyList()

            val result = service.getIncidentContext(ORG_ID, 1L)

            assertNull(result.triggeringAlert)
        }
    }

    @Test
    fun `getIncidentContext includes affected down monitors`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()

            every { monitorService.listHosts(ORG_ID) } returns emptyList()
            every { uptimeService.listMonitors(ORG_ID) } returns listOf(
                makeMonitor(id = "m1", name = "Website", status = "down"),
                makeMonitor(id = "m2", name = "API", status = "up")
            )
            every { releaseService.listReleases(PROJECT_ID) } returns emptyList()

            val result = service.getIncidentContext(ORG_ID, 999L)

            assertEquals(1, result.affectedUptimeMonitors.size)
            assertEquals("m1", result.affectedUptimeMonitors.first().id)
        }
    }

    @Test
    fun `getIncidentContext includes recent deployments`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()

            every { monitorService.listHosts(ORG_ID) } returns emptyList()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
            every { releaseService.listReleases(PROJECT_ID) } returns listOf(
                mockk {
                    every { version } returns "v2.0.0"
                    every { dateCreated } returns "2026-01-01T12:00:00Z"
                },
                mockk {
                    every { version } returns "v1.9.0"
                    every { dateCreated } returns "2025-12-25T12:00:00Z"
                }
            )

            val result = service.getIncidentContext(ORG_ID, 999L)

            assertEquals(2, result.recentDeployments.size)
            assertEquals("v2.0.0", result.recentDeployments.first().version)
            assertEquals(PROJECT_ID, result.recentDeployments.first().projectId)
        }
    }

    @Test
    fun `getIncidentContext out of Int range incidentId gives null log`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()

            every { monitorService.listHosts(ORG_ID) } returns emptyList()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
            every { releaseService.listReleases(PROJECT_ID) } returns emptyList()

            val result = service.getIncidentContext(ORG_ID, Long.MAX_VALUE)

            assertNull(result.triggeringAlert)
        }
    }

    @Test
    fun `getIncidentContext metrics failure gives null host metrics`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            seedIncidentLog(dedupKey = DEDUP_KEY)

            val host = makeHost(id = 42, status = "online")
            every { monitorService.listHosts(ORG_ID) } returns listOf(host)
            every { monitorService.listAlerts(42, ORG_ID) } returns listOf(
                makeAlert(id = 7, hostId = 42)
            )
            coEvery { monitorService.getLatestMetrics(42) } throws RuntimeException("no data")
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
            every { releaseService.listReleases(PROJECT_ID) } returns emptyList()

            val result = service.getIncidentContext(ORG_ID, 1L)

            assertNotNull(result.triggeringAlert)
            assertNull(result.hostMetrics)
        }
    }

    @Test
    fun `getIncidentContext with tpl dedup key parses host but no alert id`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            seedIncidentLog(dedupKey = "moneat-host-alert-42-tpl_3")

            val host = makeHost(id = 42, status = "online")
            val triggeredAlert = makeAlert(
                id = 10,
                hostId = 42,
                lastTriggered = System.currentTimeMillis()
            )
            every { monitorService.listHosts(ORG_ID) } returns listOf(host)
            every { monitorService.listAlerts(42, ORG_ID) } returns listOf(triggeredAlert)
            coEvery { monitorService.getLatestMetrics(42) } returns makeLatestMetrics()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
            every { releaseService.listReleases(PROJECT_ID) } returns emptyList()

            val result = service.getIncidentContext(ORG_ID, 1L)

            assertNotNull(result.triggeringAlert)
            assertEquals(10, result.triggeringAlert.alertId)
        }
    }

    // ──── ClickHouse error handling tests ────

    @Test
    fun `ClickHouse error returns safe defaults`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(500, "Internal Server Error", TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            every { monitorService.listHosts(ORG_ID) } returns emptyList()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()

            val result = service.getOvernightSummary(ORG_ID, "UTC")

            assertTrue(result.newIssues.isEmpty())
            assertFalse(result.errorSpikes.spikeDetected)
        }
    }

    @Test
    fun `ClickHouse blank response returns safe defaults`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, "", TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            every { monitorService.listHosts(ORG_ID) } returns emptyList()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()

            val result = service.getOvernightSummary(ORG_ID, "UTC")

            assertEquals(0L, result.errorSpikes.overnightCount)
            assertEquals(0L, result.errorSpikes.baselineCount)
        }
    }

    @Test
    fun `getIncidentContext deployment failure is handled gracefully`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()

            every { monitorService.listHosts(ORG_ID) } returns emptyList()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
            every { releaseService.listReleases(PROJECT_ID) } throws RuntimeException("fail")

            val result = service.getIncidentContext(ORG_ID, 999L)

            assertTrue(result.recentDeployments.isEmpty())
        }
    }

    @Test
    fun `getIncidentContext fallback alert with no lastTriggeredAt is skipped`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()

            val host = makeHost(id = 1, status = "online")
            every { monitorService.listHosts(ORG_ID) } returns listOf(host)
            every { monitorService.listAlerts(1, ORG_ID) } returns listOf(
                makeAlert(id = 1, hostId = 1, lastTriggered = null)
            )
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
            every { releaseService.listReleases(PROJECT_ID) } returns emptyList()

            val result = service.getIncidentContext(ORG_ID, 999L)

            assertNull(result.triggeringAlert)
        }
    }

    @Test
    fun `getIncidentContext incident log host not in hosts list`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            seedIncidentLog(dedupKey = "moneat-host-alert-999-id_1")

            every { monitorService.listHosts(ORG_ID) } returns emptyList()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
            every { releaseService.listReleases(PROJECT_ID) } returns emptyList()

            val result = service.getIncidentContext(ORG_ID, 1L)

            assertNull(result.triggeringAlert)
            assertNull(result.hostMetrics)
        }
    }

    @Test
    fun `getIncidentContext with multiple projects and multiple releases`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            transaction {
                Organizations.insert {
                    it[id] = ORG_ID
                    it[name] = TEST_ORG
                    it[slug] = TEST_ORG_SLUG
                }
                Projects.insert {
                    it[id] = 1L
                    it[organization_id] = ORG_ID
                    it[name] = "Project 1"
                    it[slug] = "project-1"
                }
                Projects.insert {
                    it[id] = 2L
                    it[organization_id] = ORG_ID
                    it[name] = "Project 2"
                    it[slug] = "project-2"
                }
            }

            every { monitorService.listHosts(ORG_ID) } returns emptyList()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
            every { releaseService.listReleases(1L) } returns listOf(
                mockk {
                    every { version } returns "v1.0"
                    every { dateCreated } returns "2026-01-01T00:00:00Z"
                }
            )
            every { releaseService.listReleases(2L) } returns listOf(
                mockk {
                    every { version } returns "v2.0"
                    every { dateCreated } returns "2026-01-02T00:00:00Z"
                }
            )

            val result = service.getIncidentContext(ORG_ID, 999L)

            assertEquals(2, result.recentDeployments.size)
        }
    }

    @Test
    fun `getIncidentContext fallback picks most recently triggered alert`() = runBlocking {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            val body = when {
                query.contains(COUNT_AS_TOTAL) -> TOTAL_ZERO_JSON
                else -> DATA_EMPTY_JSON
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            val now = System.currentTimeMillis()
            val host1 = makeHost(id = 1, status = "online")
            val host2 = makeHost(id = 2, status = "online")

            every { monitorService.listHosts(ORG_ID) } returns listOf(host1, host2)
            every { monitorService.listAlerts(1, ORG_ID) } returns listOf(
                makeAlert(id = 1, hostId = 1, lastTriggered = now - 5000)
            )
            every { monitorService.listAlerts(2, ORG_ID) } returns listOf(
                makeAlert(id = 2, hostId = 2, lastTriggered = now)
            )
            coEvery { monitorService.getLatestMetrics(2) } returns makeLatestMetrics()
            every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
            every { releaseService.listReleases(PROJECT_ID) } returns emptyList()

            val result = service.getIncidentContext(ORG_ID, 999L)

            assertNotNull(result.triggeringAlert)
            assertEquals(2, result.triggeringAlert.alertId)
            assertEquals("2", result.triggeringAlert.systemId)
        }
    }
}
