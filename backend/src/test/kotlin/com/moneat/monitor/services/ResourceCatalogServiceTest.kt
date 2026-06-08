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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ResourceCatalogServiceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val ORGANIZATION_ID = 7
        const val OTHER_ORGANIZATION_ID = 8
        const val HOST_ID = 42
        const val HOSTNAME = "checkout-host-01"
        const val SERVICE_NAME = "checkout-api"
        const val CONTAINER_ID = "abc123"
        const val CLOUD_RESOURCE_ID = "aws:i-123"
        const val LAST_SEEN = "2026-06-07T12:00:00.000Z"
        const val FIRST_SEEN = "2026-06-01T00:00:00.000Z"
    }

    // ──── Hosts ────

    @Test
    fun `maps monitored hosts into resource catalog shape`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        val host = hostData(
            id = HOST_ID,
            organizationId = ORGANIZATION_ID,
            hostname = HOSTNAME,
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

        every { monitorService.listHosts(ORGANIZATION_ID) } returns listOf(host)
        coEvery {
            monitorService.getLatestMetricsForHosts(listOf(HOST_ID), ORGANIZATION_ID)
        } returns mapOf(HOST_ID to metrics)

        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = NoopResourceCatalogQueryClient
        )

        val resources = service.listResources(listOf(ORGANIZATION_ID))

        assertEquals(1, resources.size)
        val resource = resources.first()
        assertEquals("host:7:42", resource.id)
        assertEquals(HOSTNAME, resource.name)
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
    fun `normalizes alternate catalog health environment and cloud values`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        val warningHost = hostData(HOST_ID, ORGANIZATION_ID, "warning-host", "degraded")
        val criticalHost = hostData(HOST_ID + 1, ORGANIZATION_ID, "critical-host", "offline")
        val unknownHost = hostData(HOST_ID + 2, ORGANIZATION_ID, "unknown-host", "maintenance")
        every { monitorService.listHosts(ORGANIZATION_ID) } returns listOf(warningHost, criticalHost, unknownHost)
        coEvery {
            monitorService.getLatestMetricsForHosts(listOf(HOST_ID, HOST_ID + 1, HOST_ID + 2), ORGANIZATION_ID)
        } returns emptyMap()

        val queryClient = StubResourceCatalogQueryClient(
            serviceRows = listOf(
                serviceRow(ORGANIZATION_ID, service = "broken-api", errorRatePct = 6.0, env = "testing")
            ),
            containerRows = listOf(
                containerRow(CONTAINER_ID, "restarting", mapOf("env" to "local", "cloud.provider" to "google_cloud")),
                containerRow("dead-container", "exited", mapOf("env" to "qa", "cloud.provider" to "azure"))
            ),
            podRows = listOf(
                podRow("running-pod", "Running", mapOf("env" to "development", "cloud.provider" to "google-cloud")),
                podRow("failed-pod", "Failed", mapOf("env" to "review", "cloud.provider" to "azure"))
            ),
            cloudRows = listOf(
                cloudRow("gcp:project:moneat", "google_cloud"),
                cloudRow("azure:subscription:sub-1", "azure"),
                cloudRow("custom:thing:one", "custom")
            )
        )
        val service = ResourceCatalogService(monitorService = monitorService, queryClient = queryClient)

        val byName = service.listResources(listOf(ORGANIZATION_ID)).associateBy { it.name }

        assertEquals("warn", byName.getValue("warning-host").health)
        assertEquals("critical", byName.getValue("critical-host").health)
        assertEquals("unknown", byName.getValue("unknown-host").health)
        assertEquals("critical", byName.getValue("broken-api").health)
        assertEquals("dev", byName.getValue("broken-api").environment)
        assertEquals("dev", byName.getValue(CONTAINER_ID).environment)
        assertEquals("gcp", byName.getValue(CONTAINER_ID).cloud)
        assertEquals("critical", byName.getValue("dead-container").health)
        assertEquals("azure", byName.getValue("dead-container").cloud)
        assertEquals("healthy", byName.getValue("running-pod").health)
        assertEquals("gcp", byName.getValue("running-pod").cloud)
        assertEquals("critical", byName.getValue("failed-pod").health)
        assertEquals("azure", byName.getValue("failed-pod").cloud)
        assertEquals("gcp", byName.getValue("gcp:project:moneat").cloud)
        assertEquals("azure", byName.getValue("azure:subscription:sub-1").cloud)
        assertEquals("on-prem", byName.getValue("custom:thing:one").cloud)
        assertEquals("prod", byName.getValue("custom:thing:one").environment)
    }

    // ──── APM and containers ────

    @Test
    fun `maps ClickHouse service and container rows into source neutral resources`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        every { monitorService.listHosts(ORGANIZATION_ID) } returns emptyList()

        val queryClient = StubResourceCatalogQueryClient(
            serviceRows = listOf(
                jsonObject(
                    """
                    {
                      "organization_id": "$ORGANIZATION_ID",
                      "service": "$SERVICE_NAME",
                      "env": "staging",
                      "span_count": 1200,
                      "error_count": 36,
                      "latency_ms": 245,
                      "error_rate_pct": 3.0,
                      "last_seen": "$LAST_SEEN"
                    }
                    """
                )
            ),
            containerRows = listOf(
                jsonObject(
                    """
                    {
                      "organization_id": "$ORGANIZATION_ID",
                      "id": "$CONTAINER_ID",
                      "host": "$HOSTNAME",
                      "name": "$SERVICE_NAME",
                      "image": "ghcr.io/moneat/checkout:v42",
                      "state": "running",
                      "cpu_percent": 12.4,
                      "mem_usage": 256000000,
                      "mem_limit": 512000000,
                      "tags": {"env": "staging", "team": "payments"},
                      "last_seen": "$LAST_SEEN"
                    }
                    """
                )
            )
        )

        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = queryClient
        )

        val resources = service.listResources(listOf(ORGANIZATION_ID))

        assertEquals(listOf("service", "container"), resources.map { it.kind })
        val serviceResource = resources.first { it.kind == "service" }
        assertEquals("service:7:checkout-api", serviceResource.id)
        assertEquals("warn", serviceResource.health)
        assertEquals("staging", serviceResource.environment)
        assertEquals(245, serviceResource.telemetry.latencyMs)
        assertEquals(3.0, serviceResource.telemetry.errorRatePct)
        assertTrue(serviceResource.tags.contains("source:apm"))

        val containerResource = resources.first { it.kind == "container" }
        assertEquals("container:7:abc123", containerResource.id)
        assertEquals(SERVICE_NAME, containerResource.name)
        assertEquals("healthy", containerResource.health)
        assertEquals(12, containerResource.telemetry.cpuPct)
        assertEquals(50, containerResource.telemetry.memPct)
        assertTrue(containerResource.relationships.any { it.relation == "Runs on" && it.name == HOSTNAME })
    }

    @Test
    fun `keeps same-named services from different organizations distinct`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        every { monitorService.listHosts(ORGANIZATION_ID) } returns emptyList()
        every { monitorService.listHosts(OTHER_ORGANIZATION_ID) } returns emptyList()

        val queryClient = StubResourceCatalogQueryClient(
            serviceRows = listOf(
                serviceRow(ORGANIZATION_ID),
                serviceRow(OTHER_ORGANIZATION_ID)
            )
        )
        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = queryClient
        )

        val resources = service.listResources(listOf(ORGANIZATION_ID, OTHER_ORGANIZATION_ID))

        assertEquals(
            listOf("service:7:checkout-api", "service:8:checkout-api"),
            resources.map { it.id }
        )
    }

    @Test
    fun `maps kubernetes pods and network devices into catalog resources`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        every { monitorService.listHosts(ORGANIZATION_ID) } returns emptyList()
        val queryClient = StubResourceCatalogQueryClient(
            podRows = listOf(
                jsonObject(
                    """
                    {
                      "organization_id": "$ORGANIZATION_ID",
                      "id": "pod-uid-1",
                      "namespace": "checkout",
                      "name": "checkout-api-7f9d",
                      "cluster_name": "prod-us-east",
                      "status": "Pending",
                      "tags": {"env": "prod", "cloud.provider": "aws"},
                      "labels": {"app": "checkout"},
                      "first_seen": "$FIRST_SEEN",
                      "last_seen": "$LAST_SEEN"
                    }
                    """
                )
            ),
            networkDeviceRows = listOf(
                jsonObject(
                    """
                    {
                      "organization_id": "$ORGANIZATION_ID",
                      "id": "switch-1",
                      "hostname": "edge-switch-01",
                      "ip_address": "10.0.0.4",
                      "vendor": "Arista",
                      "model": "7050",
                      "os_version": "4.30",
                      "device_type": "switch",
                      "status": "up",
                      "reachability": "unreachable",
                      "tags": {"env": "dev"},
                      "last_seen": "$LAST_SEEN"
                    }
                    """
                )
            )
        )

        val service = ResourceCatalogService(monitorService = monitorService, queryClient = queryClient)

        val resources = service.listResources(listOf(ORGANIZATION_ID))

        val pod = resources.first { it.kind == "pod" }
        assertEquals("pod:7:pod-uid-1", pod.id)
        assertEquals("warn", pod.health)
        assertEquals("prod", pod.environment)
        assertEquals("aws", pod.cloud)
        assertTrue(pod.tags.contains("label:app:checkout"))
        assertTrue(pod.metadata.any { it.label == "Cluster" && it.value == "prod-us-east" })

        val networkDevice = resources.first { it.kind == "network-device" }
        assertEquals("network:7:switch-1", networkDevice.id)
        assertEquals("edge-switch-01", networkDevice.name)
        assertEquals("critical", networkDevice.health)
        assertEquals("dev", networkDevice.environment)
        assertTrue(networkDevice.metadata.any { it.label == "Vendor" && it.value == "Arista" })
    }

    @Test
    fun `returns no resources for empty organization context and clamps result limit`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        every { monitorService.listHosts(ORGANIZATION_ID) } returns emptyList()
        val queryClient = StubResourceCatalogQueryClient(
            serviceRows = listOf(serviceRow(ORGANIZATION_ID), serviceRow(OTHER_ORGANIZATION_ID))
        )
        val service = ResourceCatalogService(monitorService = monitorService, queryClient = queryClient)

        assertTrue(service.listResources(emptyList()).isEmpty())
        assertEquals(1, service.listResources(listOf(ORGANIZATION_ID), limit = 1).size)
    }

    // ──── Cloud resources ────

    @Test
    fun `maps ClickHouse cloud rows into catalog resources`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        every { monitorService.listHosts(ORGANIZATION_ID) } returns emptyList()

        val queryClient = StubResourceCatalogQueryClient(
            cloudRows = listOf(
                jsonObject(
                    """
                    {
                      "organization_id": "$ORGANIZATION_ID",
                      "id": "$CLOUD_RESOURCE_ID",
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
                      "first_seen": "$FIRST_SEEN",
                      "last_seen": "$LAST_SEEN"
                    }
                    """
                )
            )
        )

        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = queryClient
        )

        val resource = service.listResources(listOf(ORGANIZATION_ID)).single()

        assertEquals("cloud:7:aws:i-123", resource.id)
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

    private fun serviceRow(
        organizationId: Int,
        service: String = SERVICE_NAME,
        errorRatePct: Double = 0.0,
        env: String = "prod",
    ): JsonObject =
        jsonObject(
            """
            {
              "organization_id": "$organizationId",
              "service": "$service",
              "env": "$env",
              "span_count": 10,
              "error_count": 0,
              "latency_ms": 20,
              "error_rate_pct": $errorRatePct,
              "last_seen": "$LAST_SEEN"
            }
            """
        )

    private fun containerRow(id: String, state: String, tags: Map<String, String>): JsonObject =
        jsonObject(
            """
            {
              "organization_id": "$ORGANIZATION_ID",
              "id": "$id",
              "host": "$HOSTNAME",
              "name": "$id",
              "image": "ghcr.io/moneat/checkout:v42",
              "state": "$state",
              "cpu_percent": 12.4,
              "mem_usage": 256000000,
              "mem_limit": 512000000,
              "tags": ${json.encodeToString(tags)},
              "last_seen": "$LAST_SEEN"
            }
            """
        )

    private fun podRow(id: String, status: String, tags: Map<String, String>): JsonObject =
        jsonObject(
            """
            {
              "organization_id": "$ORGANIZATION_ID",
              "id": "$id",
              "namespace": "checkout",
              "name": "$id",
              "cluster_name": "prod-us-east",
              "status": "$status",
              "tags": ${json.encodeToString(tags)},
              "labels": {},
              "first_seen": "$FIRST_SEEN",
              "last_seen": "$LAST_SEEN"
            }
            """
        )

    private fun cloudRow(id: String, provider: String): JsonObject =
        jsonObject(
            """
            {
              "organization_id": "$ORGANIZATION_ID",
              "id": "$id",
              "name": "$id",
              "resource_type": "cloud_resource",
              "provider": "$provider",
              "region": "global",
              "account": "acct",
              "health": "ok",
              "tags": {},
              "metadata": {},
              "first_seen": "$FIRST_SEEN",
              "last_seen": "$LAST_SEEN"
            }
            """
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
