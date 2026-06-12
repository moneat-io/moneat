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

import com.moneat.billing.models.PricingTierConfigResponse
import com.moneat.billing.services.EffectiveTierContext
import com.moneat.billing.services.PricingTierService
import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.monitor.models.AlertRow
import com.moneat.monitor.models.HostData
import com.moneat.monitor.repositories.HostAlertRepository
import com.moneat.monitor.repositories.HostRepository
import com.moneat.monitor.services.AgentApiKeyService
import com.moneat.monitor.services.MonitorService
import com.moneat.shared.models.AgentApiKeys
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.testsupport.TestDatabaseHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlin.time.Clock

class MonitorServiceExtendedTest {

    private val hostRepo = mockk<HostRepository>()
    private val alertRepo = mockk<HostAlertRepository>(relaxed = true)
    private val pricingTierService = mockk<PricingTierService>()
    private val retentionPolicyService = mockk<RetentionPolicyService>()

    private val service = MonitorService(
        hostRepository = hostRepo,
        hostAlertRepository = alertRepo,
        pricingTierService = pricingTierService,
        retentionPolicyService = retentionPolicyService
    )

    private val testHost = HostData(
        id = 1,
        organizationId = 10,
        hostname = "test-server",
        displayName = "Test Server",
        status = "up",
        lastSeenAt = Clock.System.now(),
        agentVersion = "1.0.0",
        os = "linux",
        arch = "amd64",
        firstSeenAt = Clock.System.now(),
        createdAt = Clock.System.now()
    )

    @BeforeTest
    fun setup() {
        mockkObject(ClickHouseClient)
        mockkObject(RedisConfig)
        every { ClickHouseClient.getDatabase() } returns "moneat_test"
        every { RedisConfig.isConnected() } returns false
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ClickHouseClient)
        unmockkObject(RedisConfig)
    }

    // ──── getLatestMetrics ────

    @Test
    fun `getLatestMetrics returns null when host does not exist`() = runBlocking {
        every { hostRepo.getById(999) } returns null
        assertNull(service.getLatestMetrics(999))
    }

    @Test
    fun `getLatestMetrics returns null when ClickHouse returns blank`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 3
        coEvery { hostRepo.executeClickHouseQuery(any()) } returns ""
        assertNull(service.getLatestMetrics(1))
    }

    @Test
    fun `getLatestMetrics parses valid ClickHouse response`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 3
        coEvery {
            hostRepo.executeClickHouseQuery(any())
        } returns """
            {
                "data": [
                    [75.5, "8589934592", "4294967296", "4294967296", "107374182400",
                     "53687091200", "123456", "654321", 1.5, 62.0, 45.0, 85.0]
                ]
            }
        """.trimIndent()

        val metrics = service.getLatestMetrics(1)
        assertNotNull(metrics)
        assertEquals(75.5f, metrics.cpuPercent)
        assertEquals(8589934592L, metrics.memTotal)
        // mem_used = memTotal - memAvailable = 8589934592 - 4294967296 = 4294967296
        assertEquals(4294967296L, metrics.memUsed)
        assertEquals(107374182400L, metrics.diskTotal)
        assertEquals(53687091200L, metrics.diskUsed)
        assertEquals(123456L, metrics.netRecvBytes)
        assertEquals(654321L, metrics.netSentBytes)
        assertEquals(1.5f, metrics.load1)
        assertEquals(62.0f, metrics.tempMax)
        assertEquals(45.0f, metrics.gpuPercent)
        assertEquals(85.0f, metrics.batteryPercent)
    }

    @Test
    fun `getLatestMetrics returns null for malformed JSON`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 3
        coEvery { hostRepo.executeClickHouseQuery(any()) } returns "not valid json"

        assertNull(service.getLatestMetrics(1))
    }

    @Test
    fun `getLatestMetrics returns null when data array is empty`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 3
        coEvery { hostRepo.executeClickHouseQuery(any()) } returns DATA_EMPTY_JSON

        assertNull(service.getLatestMetrics(1))
    }

    @Test
    fun `getLatestMetrics computes mem_percent correctly`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 3
        // mem_total=1000, mem_used=0, mem_available=600 => effective=400, percent=40%
        coEvery {
            hostRepo.executeClickHouseQuery(any())
        } returns """
            {"data": [[50.0, "1000", "0", "600", "0", "0", "0", "0", 0.0, null, null, null]]}
        """.trimIndent()

        val metrics = service.getLatestMetrics(1)
        assertNotNull(metrics)
        assertEquals(400L, metrics.memUsed)
        assertEquals(40.0f, metrics.memPercent)
    }

    @Test
    fun `getLatestMetrics uses memUsed when memAvailable is zero`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 3
        // mem_total=2000, mem_used=800, mem_available=0 => effective=800 (fallback)
        coEvery {
            hostRepo.executeClickHouseQuery(any())
        } returns """
            {"data": [[10.0, "2000", "800", "0", "500", "100", "0", "0", 0.0, null, null, null]]}
        """.trimIndent()

        val metrics = service.getLatestMetrics(1)
        assertNotNull(metrics)
        assertEquals(800L, metrics.memUsed)
    }

    @Test
    fun `getLatestMetrics constrains latest query to recent metric names`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        val queries = mutableListOf<String>()
        coEvery { hostRepo.executeClickHouseQuery(any()) } coAnswers {
            queries.add(firstArg())
            ""
        }

        assertNull(service.getLatestMetrics(1))
        val query = queries.single()
        assertTrue(query.contains("metric_name IN ("))
        assertTrue(query.contains("system.cpu.percent"))
        assertTrue(query.contains("timestamp >= now64(3) - INTERVAL 6 HOUR"))
    }

    // ──── getLatestMetricsForHosts ────

    @Test
    fun `getLatestMetricsForHosts returns empty map for empty list`() = runBlocking {
        val result = service.getLatestMetricsForHosts(emptyList(), 10)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getLatestMetricsForHosts returns null map for blank response`() = runBlocking {
        val queries = mutableListOf<String>()
        coEvery { hostRepo.executeClickHouseQuery(any()) } coAnswers {
            queries.add(firstArg())
            ""
        }

        val result = service.getLatestMetricsForHosts(listOf(1, 2), 10)
        assertEquals(2, result.size)
        assertNull(result[1])
        assertNull(result[2])
        val query = queries.single()
        assertTrue(query.contains("metric_name IN ("))
        assertTrue(query.contains("system.cpu.percent"))
        assertTrue(query.contains("timestamp >= now64(3) - INTERVAL 6 HOUR"))
    }

    @Test
    fun `getLatestMetricsForHosts uses demo epoch for freshness window`() = runBlocking {
        val queries = mutableListOf<String>()
        coEvery { hostRepo.executeClickHouseQuery(any()) } coAnswers {
            queries.add(firstArg())
            ""
        }

        service.getLatestMetricsForHosts(listOf(1, 2), 10, demoEpochMs = 1_709_312_400_000L)

        val query = queries.single()
        assertTrue(query.contains("timestamp >= toDateTime64(1709312400.000, 3) - INTERVAL 6 HOUR"))
    }

    @Test
    fun `getLatestMetricsForHosts parses batch response`() = runBlocking {
        coEvery { retentionPolicyService.getRetentionDaysForOrganization(10) } returns 3
        coEvery {
            hostRepo.executeClickHouseQuery(any())
        } returns """
            {
                "data": [
                    ["1", 80.0, "4096", "2048", "2048", "500", "250", "100", "200", 1.0, null, null, null],
                    ["2", 60.0, "8192", "4096", "4096", "1000", "500", "300", "400", 2.0, 55.0, null, null]
                ]
            }
        """.trimIndent()

        val result = service.getLatestMetricsForHosts(listOf(1, 2, 3), 10)
        assertEquals(3, result.size)
        assertNotNull(result[1])
        assertEquals(80.0f, result[1]?.cpuPercent)
        assertNotNull(result[2])
        assertEquals(60.0f, result[2]?.cpuPercent)
        assertNull(result[3])
    }

    @Test
    fun `getLatestMetricsForHosts returns null map for malformed JSON`() = runBlocking {
        coEvery { retentionPolicyService.getRetentionDaysForOrganization(10) } returns 3
        coEvery { hostRepo.executeClickHouseQuery(any()) } returns "bad json"

        val result = service.getLatestMetricsForHosts(listOf(1), 10)
        assertNull(result[1])
    }

    // ──── getLatestContainers ────

    @Test
    fun `getLatestContainers returns empty for non-existent host`() = runBlocking {
        every { hostRepo.getById(999) } returns null
        assertTrue(service.getLatestContainers(999).isEmpty())
    }

    @Test
    fun `getLatestContainers returns empty for blank response`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 3
        stubTierConfig(monitorIntervalSeconds = 60)
        coEvery { hostRepo.executeClickHouseQuery(any()) } returns ""

        assertTrue(service.getLatestContainers(1).isEmpty())
    }

    @Test
    fun `getLatestContainers parses valid container response`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 3
        stubTierConfig(monitorIntervalSeconds = 60)
        coEvery {
            hostRepo.executeClickHouseQuery(any())
        } returns """
            {
                "data": [
                    ["nginx", "abc123", "nginx:latest", "running",
                     25.5, "524288000", "1073741824", "12345", "67890"]
                ]
            }
        """.trimIndent()

        val containers = service.getLatestContainers(1)
        assertEquals(1, containers.size)
        assertEquals("nginx", containers[0].name)
        assertEquals("abc123", containers[0].id)
        assertEquals("nginx:latest", containers[0].image)
        assertEquals("running", containers[0].status)
        assertEquals(25.5f, containers[0].cpuPercent)
        assertEquals(524288000L, containers[0].memUsed)
        assertEquals(1073741824L, containers[0].memLimit)
        assertEquals(12345L, containers[0].netRecvBytes)
        assertEquals(67890L, containers[0].netSentBytes)
    }

    @Test
    fun `getLatestContainers computes mem_percent correctly`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 3
        stubTierConfig(monitorIntervalSeconds = 60)
        // mem_used=500, mem_limit=1000 => mem_percent=50%
        coEvery {
            hostRepo.executeClickHouseQuery(any())
        } returns """
            {
                "data": [
                    ["app", "def456", "app:v1", "running",
                     10.0, "500", "1000", "0", "0"]
                ]
            }
        """.trimIndent()

        val containers = service.getLatestContainers(1)
        assertEquals(50.0f, containers[0].memPercent)
    }

    @Test
    fun `getLatestContainers returns empty for malformed JSON`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 3
        stubTierConfig(monitorIntervalSeconds = 60)
        coEvery { hostRepo.executeClickHouseQuery(any()) } returns "not json"

        assertTrue(service.getLatestContainers(1).isEmpty())
    }

    // ──── getLatestContainersForOrganizations ────

    @Test
    fun `getLatestContainersForOrganizations aggregates across hosts`() = runBlocking {
        val host1 = testHost.copy(id = 1, hostname = "host-1", displayName = "Host 1")
        val host2 = testHost.copy(id = 2, hostname = "host-2", displayName = "Host 2")
        every { hostRepo.listByOrganizationId(10) } returns listOf(host1, host2)
        every { hostRepo.getById(1) } returns host1
        every { hostRepo.getById(2) } returns host2
        coEvery { retentionPolicyService.getRetentionDaysForHost(any()) } returns 3
        stubTierConfig(monitorIntervalSeconds = 60)

        // Host 1 has one container, Host 2 returns blank (no containers)
        coEvery {
            hostRepo.executeClickHouseQuery(
                match { query ->
                    query.contains("containers_latest_by_host") &&
                        query.contains("host_id = 1")
                }
            )
        } returns """
            {
                "data": [
                    ["web", "c1", "nginx:latest", "running",
                     10.0, "100", "200", "0", "0"]
                ]
            }
        """.trimIndent()
        coEvery {
            hostRepo.executeClickHouseQuery(
                match { query ->
                    query.contains("containers_latest_by_host") &&
                        query.contains("host_id = 2")
                }
            )
        } returns ""

        val result = service.getLatestContainersForOrganizations(listOf(10))
        assertEquals(1, result.size)
        assertEquals("Host 1", result[0].systemName)
        assertEquals("web", result[0].name)
    }

    // ──── getHistoricalMetrics ────

    @Test
    fun `getHistoricalMetrics returns empty for non-existent host`() = runBlocking {
        every { hostRepo.getById(999) } returns null

        val result = service.getHistoricalMetrics(999, 1000, 2000, null)
        assertTrue(result.dataPoints.isEmpty())
        assertNull(result.hostId)
    }

    @Test
    fun `getHistoricalMetrics returns empty when range outside retention`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 1

        // Set from/to far in the past beyond retention
        val nowEpoch = Clock.System.now().epochSeconds
        val farPast = nowEpoch - 86400L * 30
        val result = service.getHistoricalMetrics(1, farPast, farPast + 100, null)
        assertTrue(result.dataPoints.isEmpty())
    }

    @Test
    fun `getHistoricalMetrics auto-calculates interval for short range`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 7

        val nowEpoch = Clock.System.now().epochSeconds
        // 30-minute range is served from the 1-minute rollup table.
        coEvery { hostRepo.executeClickHouseQuery(any()) } returns DATA_EMPTY_JSON

        val result = service.getHistoricalMetrics(
            1,
            nowEpoch - 1800,
            nowEpoch,
            null
        )
        assertEquals(60, result.intervalSeconds)
        assertTrue(result.dataPoints.isEmpty())
    }

    @Test
    fun `getHistoricalMetrics uses provided interval`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 7
        val nowEpoch = Clock.System.now().epochSeconds
        coEvery { hostRepo.executeClickHouseQuery(any()) } returns DATA_EMPTY_JSON

        val result = service.getHistoricalMetrics(1, nowEpoch - 3600, nowEpoch, 120)
        assertEquals(120, result.intervalSeconds)
    }

    @Test
    fun `getHistoricalMetrics prefers Datadog percent metrics over derived ratios`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 7
        val queries = mutableListOf<String>()
        val nowEpoch = Clock.System.now().epochSeconds
        coEvery { hostRepo.executeClickHouseQuery(any()) } coAnswers {
            queries.add(firstArg())
            DATA_EMPTY_JSON
        }

        service.getHistoricalMetrics(1, nowEpoch - 3600, nowEpoch, 120)

        val query = queries.single()
        assertTrue(query.indexOf("system.mem.pct_usable") < query.indexOf("system.mem.available"))
        assertTrue(query.indexOf("system.disk.in_use") < query.indexOf("system.disk.used"))
    }

    @Test
    fun `getHistoricalMetrics parses data points`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 7
        val nowEpoch = Clock.System.now().epochSeconds
        coEvery {
            hostRepo.executeClickHouseQuery(any())
        } returns """
            {
                "data": [
                    ["${nowEpoch - 60}", 55.0, 70.0, 45.0, "1000", "2000", 1.2, 1.5, 2.0, 60.0, 30.0, 95.0],
                    ["$nowEpoch", 60.0, 72.0, 46.0, "1100", "2100", 1.3, 1.6, 2.1, 61.0, 31.0, 94.0]
                ]
            }
        """.trimIndent()

        val result = service.getHistoricalMetrics(1, nowEpoch - 3600, nowEpoch, 60)
        assertEquals(2, result.dataPoints.size)
        assertEquals(55.0f, result.dataPoints[0].cpuPercent)
        assertEquals(70.0f, result.dataPoints[0].memPercent)
        assertEquals(60.0f, result.dataPoints[1].cpuPercent)
    }

    // ──── getContainerHistoricalMetrics ────

    @Test
    fun `getContainerHistoricalMetrics returns empty for missing host`() = runBlocking {
        every { hostRepo.getById(999) } returns null

        val result = service.getContainerHistoricalMetrics(999, "nginx", 1000, 2000, null)
        assertTrue(result.dataPoints.isEmpty())
        assertEquals("nginx", result.containerName)
    }

    @Test
    fun `getContainerHistoricalMetrics returns empty when outside retention`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 1
        val farPast = Clock.System.now().epochSeconds - 86400L * 30

        val result = service.getContainerHistoricalMetrics(1, "nginx", farPast, farPast + 100, null)
        assertTrue(result.dataPoints.isEmpty())
    }

    @Test
    fun `getContainerHistoricalMetrics parses container data points`() = runBlocking {
        every { hostRepo.getById(1) } returns testHost
        coEvery { retentionPolicyService.getRetentionDaysForHost(1) } returns 7
        val nowEpoch = Clock.System.now().epochSeconds
        coEvery {
            hostRepo.executeClickHouseQuery(any())
        } returns """
            {
                "data": [
                    ["$nowEpoch", 25.0, "524288000", "1073741824", "12345", "67890"]
                ]
            }
        """.trimIndent()

        val result = service.getContainerHistoricalMetrics(
            1,
            "nginx",
            nowEpoch - 3600,
            nowEpoch,
            60
        )
        assertEquals(1, result.dataPoints.size)
        assertEquals(25.0f, result.dataPoints[0].cpuPercent)
        assertEquals(524288000L, result.dataPoints[0].memUsed)
        assertEquals(1073741824L, result.dataPoints[0].memLimit)
    }

    // ──── getLatestInfraContainers ────

    @Test
    fun `getLatestInfraContainers returns empty for empty org list`() = runBlocking {
        val result = service.getLatestInfraContainers(emptyList(), null, 100)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getLatestInfraContainers returns empty for blank response`() = runBlocking {
        coEvery { hostRepo.executeClickHouseQuery(any()) } returns ""

        val result = service.getLatestInfraContainers(listOf(10), null, 100)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getLatestInfraContainers parses row into map`() = runBlocking {
        coEvery {
            hostRepo.executeClickHouseQuery(any())
        } returns """
            {
                "data": [
                    ["host-1", "c1", "nginx", "nginx:latest",
                     "running", 15.5, 524288, 1073741824, 100, 200,
                     "{}", "2024-01-01 00:00:00"]
                ]
            }
        """.trimIndent()

        val result = service.getLatestInfraContainers(listOf(10), null, 100)
        assertEquals(1, result.size)
        assertEquals("host-1", result[0]["host"])
        assertEquals("c1", result[0]["container_id"])
        assertEquals("nginx", result[0]["name"])
        assertEquals(15.5f, result[0]["cpu_percent"])
    }

    @Test
    fun `getLatestInfraContainers includes host filter in query`() = runBlocking {
        var capturedQuery = ""
        coEvery { hostRepo.executeClickHouseQuery(any()) } answers {
            capturedQuery = firstArg()
            ""
        }

        service.getLatestInfraContainers(listOf(10), "my-host", 50)
        assertTrue(capturedQuery.contains("host = 'my-host'"))
    }

    @Test
    fun `getLatestInfraContainers returns empty for malformed JSON`() = runBlocking {
        coEvery { hostRepo.executeClickHouseQuery(any()) } returns "invalid"

        val result = service.getLatestInfraContainers(listOf(10), null, 100)
        assertTrue(result.isEmpty())
    }

    // ──── checkHostQuota with self-host ────

    @Test
    fun `checkHostQuota returns true when self-host is enabled`() {
        mockkObject(com.moneat.config.EnvConfig.SelfHost)
        every { com.moneat.config.EnvConfig.SelfHost.enabled } returns true
        assertTrue(service.checkHostQuota(10))
        unmockkObject(com.moneat.config.EnvConfig.SelfHost)
    }

    // ──── ensureOrganizationAlertTemplates ────

    @Test
    fun `ensureOrganizationAlertTemplates does not create when templates exist`() {
        every { alertRepo.listGlobalAlertsForHost(10, -1) } returns listOf(
            AlertRow(
                id = 1, hostId = 0, organizationId = 10,
                metric = "cpu_percent", condition = ">", threshold = 80.0,
                durationSeconds = 0, enabled = false,
                lastTriggeredAt = null, createdAt = Clock.System.now(),
                scope = "global"
            )
        )
        // Should not call createAlert since templates already exist
        service.ensureOrganizationAlertTemplates(10)
        // No exception means it returned early
    }

    @Test
    fun `ensureOrganizationAlertTemplates creates default templates`() {
        every { alertRepo.listGlobalAlertsForHost(10, -1) } returns emptyList()
        every { alertRepo.createAlert(any()) } returns Uuid.parse("00000000-0000-0000-0000-000000000001")

        service.ensureOrganizationAlertTemplates(10)
        // Verify 7 default templates were created
        io.mockk.verify(exactly = 7) { alertRepo.createAlert(any()) }
    }

    // ──── ensureHostAlertsSeeded ────

    @Test
    fun `ensureHostAlertsSeeded does not seed when alerts exist`() {
        every { alertRepo.listByHostAndOrg(1, 10) } returns listOf(
            AlertRow(
                id = 1, hostId = 1, organizationId = 10,
                metric = "cpu_percent", condition = ">", threshold = 80.0,
                durationSeconds = 0, enabled = false,
                lastTriggeredAt = null, createdAt = Clock.System.now(),
                scope = "host"
            )
        )
        service.ensureHostAlertsSeeded(1, 10)
        // No new alerts should be created
        io.mockk.verify(exactly = 0) { alertRepo.createAlert(any()) }
    }

    @Test
    fun `ensureHostAlertsSeeded seeds from templates when available`() {
        every { alertRepo.listByHostAndOrg(1, 10) } returns emptyList()
        every { alertRepo.listGlobalAlertsForHost(10, 1) } returns listOf(
            AlertRow(
                id = 100, hostId = 0, organizationId = 10,
                metric = "cpu_percent", condition = ">", threshold = 90.0,
                durationSeconds = 60, enabled = true,
                lastTriggeredAt = null, createdAt = Clock.System.now(),
                scope = "global"
            )
        )
        every { alertRepo.createAlert(any()) } returns Uuid.parse("00000000-0000-0000-0000-000000000001")

        service.ensureHostAlertsSeeded(1, 10)
        io.mockk.verify(exactly = 1) { alertRepo.createAlert(any()) }
    }

    @Test
    fun `ensureHostAlertsSeeded seeds defaults when no templates`() {
        every { alertRepo.listByHostAndOrg(1, 10) } returns emptyList()
        every { alertRepo.listGlobalAlertsForHost(10, 1) } returns emptyList()
        every { alertRepo.createAlert(any()) } returns Uuid.parse("00000000-0000-0000-0000-000000000001")

        service.ensureHostAlertsSeeded(1, 10)
        // 7 default alert templates
        io.mockk.verify(exactly = 7) { alertRepo.createAlert(any()) }
    }

    // ──── AgentApiKeyService ────

    companion object {
        private var db: Database? = null
        private const val DATA_EMPTY_JSON = """{"data":[]}"""
        private const val ORG1_KEY = "org1-key"
    }

    private val agentApiKeyService = AgentApiKeyService()

    private fun ensureDb() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_extended_test;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, AgentApiKeys)
    }

    private fun seedOrg(name: String = "Key Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedUser(email: String = "test@moneat.io"): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hash"
            } get Users.id
        }

    @Test
    fun `AgentApiKeyService createKey returns key with prefix`() {
        ensureDb()
        val orgId = seedOrg()
        val userId = seedUser()

        val result = agentApiKeyService.createKey(orgId, "test-key", userId)
        assertTrue(result.key.startsWith("magt_"))
        assertEquals("test-key", result.name)
        assertEquals(result.id, Uuid.parse(result.id).toString())
        assertEquals(result.key.take(12), result.keyPrefix)
    }

    @Test
    fun `AgentApiKeyService validateKey returns orgId for valid key`() {
        ensureDb()
        val orgId = seedOrg()
        val userId = seedUser()
        val created = agentApiKeyService.createKey(orgId, "validate-key", userId)

        val result = agentApiKeyService.validateKey(created.key)
        assertEquals(orgId, result)
    }

    @Test
    fun `AgentApiKeyService validateKey returns null for invalid key`() {
        ensureDb()
        assertNull(agentApiKeyService.validateKey("invalid-key"))
    }

    @Test
    fun `AgentApiKeyService validateKey returns null for key without prefix`() {
        ensureDb()
        assertNull(agentApiKeyService.validateKey("short"))
    }

    @Test
    fun `AgentApiKeyService validateKey returns null for deleted key`() {
        ensureDb()
        val orgId = seedOrg()
        val userId = seedUser()
        val created = agentApiKeyService.createKey(orgId, "to-delete", userId)

        agentApiKeyService.deleteKey(orgId, created.id)
        assertNull(agentApiKeyService.validateKey(created.key))
    }

    @Test
    fun `AgentApiKeyService listKeys returns active keys`() {
        ensureDb()
        val orgId = seedOrg()
        val userId = seedUser()

        agentApiKeyService.createKey(orgId, "key-1", userId)
        agentApiKeyService.createKey(orgId, "key-2", userId)

        val keys = agentApiKeyService.listKeys(orgId)
        assertEquals(2, keys.size)
        assertTrue(keys.any { it.name == "key-1" })
        assertTrue(keys.any { it.name == "key-2" })
    }

    @Test
    fun `AgentApiKeyService listKeys does not return deleted keys`() {
        ensureDb()
        val orgId = seedOrg()
        val userId = seedUser()

        val key1 = agentApiKeyService.createKey(orgId, "active", userId)
        val key2 = agentApiKeyService.createKey(orgId, "deleted", userId)
        agentApiKeyService.deleteKey(orgId, key2.id)

        val keys = agentApiKeyService.listKeys(orgId)
        assertEquals(1, keys.size)
        assertEquals("active", keys[0].name)
    }

    @Test
    fun `AgentApiKeyService listKeys only returns keys for given org`() {
        ensureDb()
        val org1 = seedOrg("Org A")
        val org2 = seedOrg("Org B")
        val userId = seedUser()

        agentApiKeyService.createKey(org1, ORG1_KEY, userId)
        agentApiKeyService.createKey(org2, "org2-key", userId)

        assertEquals(1, agentApiKeyService.listKeys(org1).size)
        assertEquals(ORG1_KEY, agentApiKeyService.listKeys(org1)[0].name)
    }

    @Test
    fun `AgentApiKeyService deleteKey returns true for existing key`() {
        ensureDb()
        val orgId = seedOrg()
        val userId = seedUser()
        val created = agentApiKeyService.createKey(orgId, "to-delete", userId)

        assertTrue(agentApiKeyService.deleteKey(orgId, created.id))
    }

    @Test
    fun `AgentApiKeyService deleteKey returns false for non-existent key`() {
        ensureDb()
        val orgId = seedOrg()
        assertFalse(agentApiKeyService.deleteKey(orgId, "99999999-9999-9999-9999-999999999999"))
    }

    @Test
    fun `AgentApiKeyService deleteKey cannot delete key from other org`() {
        ensureDb()
        val org1 = seedOrg("Org 1")
        val org2 = seedOrg("Org 2")
        val userId = seedUser()
        val created = agentApiKeyService.createKey(org1, ORG1_KEY, userId)

        assertFalse(agentApiKeyService.deleteKey(org2, created.id))
        // Key should still be valid
        assertEquals(org1, agentApiKeyService.validateKey(created.key))
    }

    // ──── Helpers ────

    private fun stubTierConfig(monitorIntervalSeconds: Int = 60) {
        val tierConfig = mockk<PricingTierConfigResponse>()
        every { tierConfig.monitorIntervalSeconds } returns monitorIntervalSeconds
        every { tierConfig.maxHosts } returns null
        val effectiveTier = mockk<EffectiveTierContext>()
        every { effectiveTier.tier } returns tierConfig
        every { pricingTierService.getEffectiveTierForOrganization(any()) } returns effectiveTier
    }
}
