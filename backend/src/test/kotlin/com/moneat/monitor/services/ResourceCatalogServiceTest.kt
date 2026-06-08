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

package com.moneat.monitor.services

import com.moneat.monitor.models.HostData
import com.moneat.monitor.models.LatestMetrics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ResourceCatalogServiceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `maps monitored hosts into resource catalog shape`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        val host = hostData(
            id = 42,
            organizationId = 7,
            hostname = "checkout-host-01",
            status = "online"
        )
        val metrics = LatestMetrics(
            cpuPercent = 37.5f,
            memTotal = 16_000_000,
            memUsed = 8_000_000,
            memPercent = 50.0f,
            diskTotal = 200_000_000,
            diskUsed = 80_000_000,
            diskPercent = 40.0f,
            netRecvBytes = 123,
            netSentBytes = 456,
            netRecvMbps = null,
            netSentMbps = null,
            load1 = 1.2f,
            tempMax = null,
            gpuPercent = null,
            batteryPercent = null
        )

        every { monitorService.listHosts(7) } returns listOf(host)
        coEvery { monitorService.getLatestMetricsForHosts(listOf(42), 7) } returns mapOf(42 to metrics)

        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = NoopResourceCatalogQueryClient
        )

        val resources = service.listResources(listOf(7))

        assertEquals(1, resources.size)
        val resource = resources.first()
        assertEquals("host:42", resource.id)
        assertEquals("checkout-host-01", resource.name)
        assertEquals("host", resource.kind)
        assertEquals("healthy", resource.health)
        assertEquals("prod", resource.environment)
        assertEquals("on-prem", resource.cloud)
        assertEquals(38, resource.telemetry.cpuPct)
        assertEquals(50, resource.telemetry.memPct)
        assertTrue(resource.tags.contains("source:host-agent"))
        assertTrue(resource.metadata.any { it.label == "Agent" && it.value == "7.63.0" })
    }

    @Test
    fun `maps ClickHouse service and container rows into source neutral resources`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        every { monitorService.listHosts(7) } returns emptyList()

        val queryClient = StubResourceCatalogQueryClient(
            serviceRows = listOf(
                jsonObject(
                    """
                    {
                      "service": "checkout-api",
                      "env": "staging",
                      "span_count": 1200,
                      "error_count": 36,
                      "latency_ms": 245,
                      "error_rate_pct": 3.0,
                      "last_seen": "2026-06-07T12:00:00.000Z"
                    }
                    """
                )
            ),
            containerRows = listOf(
                jsonObject(
                    """
                    {
                      "id": "abc123",
                      "host": "checkout-host-01",
                      "name": "checkout-api",
                      "image": "ghcr.io/moneat/checkout:v42",
                      "state": "running",
                      "cpu_percent": 12.4,
                      "mem_usage": 256000000,
                      "mem_limit": 512000000,
                      "tags": {"env": "staging", "team": "payments"},
                      "last_seen": "2026-06-07T12:00:00.000Z"
                    }
                    """
                )
            )
        )

        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = queryClient
        )

        val resources = service.listResources(listOf(7))

        assertEquals(listOf("service", "container"), resources.map { it.kind })
        val serviceResource = resources.first { it.kind == "service" }
        assertEquals("service:checkout-api", serviceResource.id)
        assertEquals("warn", serviceResource.health)
        assertEquals("staging", serviceResource.environment)
        assertEquals(245, serviceResource.telemetry.latencyMs)
        assertEquals(3.0, serviceResource.telemetry.errorRatePct)
        assertTrue(serviceResource.tags.contains("source:apm"))

        val containerResource = resources.first { it.kind == "container" }
        assertEquals("container:abc123", containerResource.id)
        assertEquals("checkout-api", containerResource.name)
        assertEquals("healthy", containerResource.health)
        assertEquals(12, containerResource.telemetry.cpuPct)
        assertEquals(50, containerResource.telemetry.memPct)
        assertTrue(containerResource.relationships.any { it.relation == "Runs on" && it.name == "checkout-host-01" })
    }

    @Test
    fun `maps ClickHouse cloud rows into catalog resources`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        every { monitorService.listHosts(7) } returns emptyList()

        val queryClient = StubResourceCatalogQueryClient(
            cloudRows = listOf(
                jsonObject(
                    """
                    {
                      "id": "aws:i-123",
                      "name": "checkout-node",
                      "resource_type": "ec2_instance",
                      "provider": "aws",
                      "region": "us-east-1",
                      "account": "123456789012",
                      "health": "healthy",
                      "tags": {"env": "prod", "team": "payments"},
                      "metadata": {"Instance type": "m7i.large"},
                      "cpu_percent": 18.4,
                      "monthly_usd": 42.5,
                      "cost_trend_pct": 3.2,
                      "first_seen": "2026-06-01T00:00:00.000Z",
                      "last_seen": "2026-06-07T12:00:00.000Z"
                    }
                    """
                )
            )
        )

        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = queryClient
        )

        val resource = service.listResources(listOf(7)).single()

        assertEquals("aws:i-123", resource.id)
        assertEquals("checkout-node", resource.name)
        assertEquals("cloud", resource.kind)
        assertEquals("aws", resource.cloud)
        assertEquals("prod", resource.environment)
        assertEquals("us-east-1", resource.region)
        assertEquals(18, resource.telemetry.cpuPct)
        assertEquals(42.5, resource.monthlyUsd)
        assertEquals(3.2, resource.costTrendPct)
        assertTrue(resource.tags.contains("source:cloud"))
        assertTrue(resource.metadata.any { it.label == "Account" && it.value == "123456789012" })
    }

    private fun hostData(
        id: Int,
        organizationId: Int,
        hostname: String,
        status: String,
    ): HostData =
        HostData(
            id = id,
            organizationId = organizationId,
            hostname = hostname,
            displayName = null,
            status = status,
            lastSeenAt = Instant.parse("2026-06-07T12:00:00Z"),
            agentVersion = "7.63.0",
            os = "linux",
            arch = "amd64",
            platform = "ubuntu",
            processor = "x86_64",
            cpuCores = 8,
            memoryTotalKb = 16_000_000,
            firstSeenAt = Instant.parse("2026-06-01T00:00:00Z"),
            createdAt = Instant.parse("2026-06-01T00:00:00Z")
        )

    private fun jsonObject(body: String): JsonObject =
        json.parseToJsonElement(body.trimIndent()).jsonObject
}

private object NoopResourceCatalogQueryClient : ResourceCatalogQueryClient {
    override suspend fun listApmServices(organizationIds: List<Int>, limit: Int): List<JsonObject> = emptyList()
    override suspend fun listContainers(organizationIds: List<Int>, limit: Int): List<JsonObject> = emptyList()
    override suspend fun listKubernetesPods(organizationIds: List<Int>, limit: Int): List<JsonObject> = emptyList()
    override suspend fun listNetworkDevices(organizationIds: List<Int>, limit: Int): List<JsonObject> = emptyList()
    override suspend fun listCloudResources(organizationIds: List<Int>, limit: Int): List<JsonObject> = emptyList()
}

private class StubResourceCatalogQueryClient(
    private val serviceRows: List<JsonObject> = emptyList(),
    private val containerRows: List<JsonObject> = emptyList(),
    private val podRows: List<JsonObject> = emptyList(),
    private val networkDeviceRows: List<JsonObject> = emptyList(),
    private val cloudRows: List<JsonObject> = emptyList(),
) : ResourceCatalogQueryClient {
    override suspend fun listApmServices(organizationIds: List<Int>, limit: Int): List<JsonObject> = serviceRows
    override suspend fun listContainers(organizationIds: List<Int>, limit: Int): List<JsonObject> = containerRows
    override suspend fun listKubernetesPods(organizationIds: List<Int>, limit: Int): List<JsonObject> = podRows
    override suspend fun listNetworkDevices(
        organizationIds: List<Int>,
        limit: Int,
    ): List<JsonObject> = networkDeviceRows

    override suspend fun listCloudResources(organizationIds: List<Int>, limit: Int): List<JsonObject> = cloudRows
}
