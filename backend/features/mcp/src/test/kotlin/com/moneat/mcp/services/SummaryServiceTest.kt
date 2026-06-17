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

package com.moneat.mcp.services

import com.moneat.config.ClickHouseClient
import com.moneat.monitor.models.AlertResponse
import com.moneat.monitor.models.HostData
import com.moneat.monitor.models.LatestMetrics
import com.moneat.monitor.services.MonitorService
import com.moneat.uptime.models.UptimeMonitorResponse
import com.moneat.uptime.services.UptimeService
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class SummaryServiceTest {
    private val monitorService = mockk<MonitorService>()
    private val uptimeService = mockk<UptimeService>()
    private var clickHouseServer: HttpServer? = null

    @AfterEach
    fun tearDown() {
        clickHouseServer?.stop(0)
        clickHouseServer = null
        ClickHouseClient.close()
    }

    @Test
    fun `summaries aggregate hosts uptime alerts and log data`() = runBlocking {
        val hosts = listOf(
            host(id = 1, hostname = "api-1", displayName = "API 1", status = "online"),
            host(id = 2, hostname = "worker-1", displayName = null, status = "offline"),
            host(id = 3, hostname = "queue-1", displayName = "Queue", status = "warning"),
        )
        val monitors = listOf(
            uptimeMonitor(id = "mon-1", name = "Homepage", status = "up"),
            uptimeMonitor(id = "mon-2", name = "API", status = "down"),
        )

        every { monitorService.listHosts(ORG_ID) } returns hosts
        every { monitorService.listAlerts(1, ORG_ID) } returns
            listOf(alert(systemId = hostResourceId(1).toString(), lastTriggeredAt = 300L))
        every { monitorService.listAlerts(2, ORG_ID) } returns
            listOf(alert(systemId = hostResourceId(2).toString(), lastTriggeredAt = null))
        every { monitorService.listAlerts(3, ORG_ID) } returns
            listOf(alert(systemId = hostResourceId(3).toString(), lastTriggeredAt = 200L))
        every { uptimeService.listMonitors(ORG_ID) } returns monitors
        coEvery { monitorService.getLatestMetrics(1) } returns latestMetrics(cpuPercent = 12.5f, memPercent = 30.0f)
        coEvery { monitorService.getLatestMetrics(2) } returns null
        coEvery { monitorService.getLatestMetrics(3) } returns latestMetrics(cpuPercent = 75.0f, memPercent = 88.0f)

        startClickHouseStub { query ->
            when {
                query.contains("GROUP BY host") -> """{"data":[{"host":"api-1","error_count":7}]}"""
                query.contains("GROUP BY date") -> """
                    {"data":[{"date":"2026-05-21","error_count":2,"total_count":20}]}
                """.trimIndent()
                query.contains("warn_count") -> """
                    {"data":[{"error_count":3,"warn_count":4,"total_count":40}]}
                """.trimIndent()
                query.contains("SELECT count() AS error_count") -> """{"data":[{"error_count":5}]}"""
                else -> """{"data":[]}"""
            }
        }

        val service = SummaryService(monitorService, uptimeService)

        val infrastructure = service.getInfrastructureSummary(ORG_ID, "7d")
        assertEquals("7d", infrastructure.period)
        assertEquals(3, infrastructure.hostSummary.total)
        assertEquals(1, infrastructure.hostSummary.online)
        assertEquals(1, infrastructure.hostSummary.offline)
        assertEquals(1, infrastructure.hostSummary.warning)
        assertEquals(listOf("Homepage", "API"), infrastructure.uptimeMonitors.map { it.name })
        assertEquals(
            listOf(hostResourceId(1).toString(), hostResourceId(3).toString()),
            infrastructure.topAlerts.map { it.systemId }
        )
        assertEquals(7, infrastructure.topErrorHosts.single().errorCount)

        val overnight = service.getOvernightSummary(ORG_ID, "Not/AZone")
        assertEquals("America/New_York", overnight.timezone)
        assertEquals(3L, overnight.logErrorVolume.errorCount)
        assertEquals(4L, overnight.logErrorVolume.warnCount)
        assertEquals(40L, overnight.logErrorVolume.totalCount)

        val weekly = service.getWeeklyReport(ORG_ID)
        assertEquals(monitors.size, weekly.uptimeMonitors.size)
        assertEquals(0, weekly.incidentStats.total)
        assertNull(weekly.incidentStats.avgResolutionMinutes)
        assertEquals("2026-05-21", weekly.logTrend.single().date)
        assertEquals(2L, weekly.logTrend.single().errorCount)
        assertEquals(20L, weekly.logTrend.single().totalCount)

        val context = service.getIncidentContext(ORG_ID, incidentId = INCIDENT_RESOURCE_ID, userId = USER_ID)
        assertEquals(INCIDENT_RESOURCE_ID.toString(), context.incidentId)
        assertNull(context.incident)
        assertEquals(2, context.relatedAlerts.size)
        assertEquals(5L, context.recentLogErrors)
        assertEquals("API 1", context.hostMetricsSummary.first().systemName)
        assertEquals(12.5f, context.hostMetricsSummary.first().cpuPercent)
        assertEquals(88.0f, context.hostMetricsSummary.last().memPercent)
    }

    @Test
    fun `summaries return empty log data when ClickHouse errors`() = runBlocking {
        every { monitorService.listHosts(ORG_ID) } returns emptyList()
        every { uptimeService.listMonitors(ORG_ID) } returns emptyList()
        startClickHouseStub { """Code: 60, DB::Exception: table missing""" }

        val service = SummaryService(monitorService, uptimeService)

        assertTrue(service.getInfrastructureSummary(ORG_ID, "invalid").topErrorHosts.isEmpty())
        assertEquals(LogVolumeSummary(0, 0, 0), service.getOvernightSummary(ORG_ID, "UTC").logErrorVolume)
        assertTrue(service.getWeeklyReport(ORG_ID).logTrend.isEmpty())
        assertEquals(
            0L,
            service.getIncidentContext(ORG_ID, incidentId = UNKNOWN_INCIDENT_RESOURCE_ID, userId = USER_ID)
                .recentLogErrors
        )
    }

    private fun startClickHouseStub(responseFor: (String) -> String) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestBody.bufferedReader().use { it.readText() }
            val response = responseFor(query).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { output -> output.write(response) }
        }
        server.start()
        clickHouseServer = server
        ClickHouseClient.close()
        ClickHouseClient.init(
            baseUrl = "http://127.0.0.1:${server.address.port}",
            database = "test_db",
            user = "default",
            password = "",
        )
    }

    private fun host(
        id: Int,
        hostname: String,
        displayName: String?,
        status: String,
    ): HostData = HostData(
        id = id,
        resourceId = hostResourceId(id),
        organizationId = ORG_ID,
        hostname = hostname,
        displayName = displayName,
        status = status,
        lastSeenAt = Clock.System.now(),
        agentVersion = "1.0.0",
        os = "linux",
        arch = "amd64",
        firstSeenAt = Clock.System.now(),
        createdAt = Clock.System.now(),
    )

    private fun hostResourceId(id: Int): Uuid =
        Uuid.parse("00000000-0000-0000-0000-${id.toString().padStart(12, '0')}")

    private fun uptimeMonitor(
        id: String,
        name: String,
        status: String,
    ): UptimeMonitorResponse = UptimeMonitorResponse(
        id = id,
        organizationId = ORG_RESOURCE_ID,
        name = name,
        type = "http",
        active = true,
        url = "https://example.com",
        intervalSeconds = 60,
        timeoutSeconds = 5,
        retries = 2,
        retryIntervalSeconds = 30,
        status = status,
        uptime24h = 99.5f,
        uptime7d = 99.0f,
        uptime30d = 98.0f,
        createdAt = 1L,
        updatedAt = 2L,
    )

    private fun alert(systemId: String, lastTriggeredAt: Long?): AlertResponse = AlertResponse(
        id = alertResourceId(systemId),
        systemId = systemId,
        hostId = systemId,
        metric = "cpu",
        condition = ">",
        threshold = 90.0,
        durationSeconds = 60,
        enabled = true,
        lastTriggeredAt = lastTriggeredAt,
        createdAt = 1L,
    )

    private fun alertResourceId(systemId: String): String =
        Uuid.parse("10000000-0000-0000-0000-${systemId.takeLast(12)}").toString()

    private fun latestMetrics(cpuPercent: Float, memPercent: Float): LatestMetrics = LatestMetrics(
        cpuPercent = cpuPercent,
        memTotal = 1_000L,
        memUsed = 300L,
        memPercent = memPercent,
        diskTotal = 2_000L,
        diskUsed = 500L,
        diskPercent = 25.0f,
        netRecvBytes = 10L,
        netSentBytes = 20L,
        netRecvMbps = 1.5f,
        netSentMbps = 2.5f,
        load1 = 0.5f,
        tempMax = null,
        gpuPercent = null,
        batteryPercent = null,
    )

    companion object {
        private const val ORG_ID = 7
        private const val ORG_RESOURCE_ID = "00000000-0000-0000-0000-000000000007"
        private const val USER_ID = 11
        private val INCIDENT_RESOURCE_ID = Uuid.parse("20000000-0000-0000-0000-000000000042")
        private val UNKNOWN_INCIDENT_RESOURCE_ID = Uuid.parse("20000000-0000-0000-0000-000000000001")
    }
}
