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

import com.moneat.monitor.models.CatalogOwner
import com.moneat.monitor.models.CatalogResourceTelemetry
import com.moneat.monitor.models.CatalogSecurityFinding
import com.moneat.monitor.models.ContainerMetricDataPoint
import com.moneat.monitor.models.ContainerMetricsResponse
import com.moneat.monitor.models.HistoricalMetricsResponse
import com.moneat.monitor.models.HostData
import com.moneat.monitor.models.LatestMetrics
import com.moneat.monitor.models.MetricDataPoint
import com.moneat.monitor.models.ResourceOwnershipClaim
import com.moneat.monitor.repositories.ResourceOwnershipRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ResourceCatalogServiceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val productionJson = Json { encodeDefaults = true }

    private companion object {
        const val ORGANIZATION_ID = 7
        const val OTHER_ORGANIZATION_ID = 8
        const val HOST_ID = 42
        const val HOSTNAME = "checkout-host-01"
        const val SERVICE_NAME = "checkout-api"
        const val CONTAINER_ID = "abc123"
        const val CLOUD_RESOURCE_ID = "aws:i-123"
        const val TEAM_INTERNAL_ID = 11
        const val TEAM_RESOURCE_ID = "11111111-1111-1111-1111-111111111111"
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
            status = "online",
            tags = mapOf(
                "env" to "staging",
                "region" to "sfo3",
                "cloud.provider" to "aws",
                "team" to "payments"
            )
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
            queryClient = NoopResourceCatalogQueryClient,
            securityReader = NoopResourceSecurityReader,
        )

        val resources = service.listResources(listOf(ORGANIZATION_ID))

        assertEquals(1, resources.size)
        val resource = resources.first()
        assertEquals("host:7:42", resource.id)
        assertEquals(HOSTNAME, resource.name)
        assertEquals("host", resource.kind)
        assertEquals("healthy", resource.health)
        assertEquals("staging", resource.environment)
        assertEquals("sfo3", resource.region)
        assertEquals("aws", resource.cloud)
        assertEquals(38, resource.telemetry.cpuPct)
        assertEquals(50, resource.telemetry.memPct)
        assertTrue(resource.tags.contains("source:host-agent"))
        assertTrue(resource.tags.contains("region:sfo3"))
        assertTrue(resource.tags.contains("team:payments"))
        assertTrue(resource.metadata.any { it.label == "Agent" && it.value == "7.63.0" })
    }

    @Test
    fun `telemetry serialization omits absent optional metrics with production defaults`() {
        val encoded = productionJson.encodeToString(CatalogResourceTelemetry(cpuPct = null, memPct = null))

        assertTrue(encoded.contains(""""cpuPct":null"""))
        assertTrue(encoded.contains(""""memPct":null"""))
        assertFalse(encoded.contains("latencyMs"))
        assertFalse(encoded.contains("errorRatePct"))
        assertFalse(encoded.contains("throughput"))
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
        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = queryClient,
            securityReader = NoopResourceSecurityReader,
        )

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
                      "tags": {"env": "staging", "team": "payments", "region": "us-east-2"},
                      "last_seen": "$LAST_SEEN"
                    }
                    """
                )
            )
        )

        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = queryClient,
            securityReader = NoopResourceSecurityReader,
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
        assertEquals("us-east-2", containerResource.region)
        assertEquals(12, containerResource.telemetry.cpuPct)
        assertEquals(50, containerResource.telemetry.memPct)
        assertTrue(containerResource.relationships.any { it.relation == "Runs on" && it.name == HOSTNAME })
    }

    @Test
    fun `resolves real relationships with pivotable target ids across kinds`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        val host = hostData(id = HOST_ID, organizationId = ORGANIZATION_ID, hostname = HOSTNAME, status = "online")
        every { monitorService.listHosts(ORGANIZATION_ID) } returns listOf(host)
        coEvery { monitorService.getLatestMetricsForHosts(listOf(HOST_ID), ORGANIZATION_ID) } returns emptyMap()

        val queryClient = StubResourceCatalogQueryClient(
            serviceRows = listOf(
                serviceRow(ORGANIZATION_ID, service = "checkout-api"),
                serviceRow(ORGANIZATION_ID, service = "payments-api"),
            ),
            containerRows = listOf(containerRow("ctr-1", "running", mapOf("env" to "prod"))),
            podRows = listOf(
                jsonObject(
                    """
                    {
                      "organization_id": "$ORGANIZATION_ID",
                      "id": "pod-1",
                      "namespace": "checkout",
                      "name": "checkout-api-7f9d",
                      "cluster_name": "prod-us-east",
                      "status": "Running",
                      "tags": {},
                      "labels": {"app": "checkout-api"},
                      "first_seen": "$FIRST_SEEN",
                      "last_seen": "$LAST_SEEN"
                    }
                    """
                )
            ),
            extras = StubResourceCatalogQueryExtras(
                edgeRows = listOf(
                    jsonObject(
                        """
                        {
                          "from_service": "checkout-api",
                          "to_service": "payments-api",
                          "call_count": 100,
                          "error_count": 1
                        }
                        """
                    )
                ),
            ),
        )
        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = queryClient,
            securityReader = NoopResourceSecurityReader,
        )

        val byName = service.listResources(listOf(ORGANIZATION_ID)).associateBy { it.name }

        // service -> service, both directions, resolved to real catalog ids.
        val checkout = byName.getValue("checkout-api")
        val dependsOn = checkout.relationships.single { it.relation == "Depends on" }
        assertEquals("payments-api", dependsOn.name)
        assertEquals("service:7:payments-api", dependsOn.targetId)
        val dependedOnBy = byName
            .getValue("payments-api")
            .relationships
            .single { it.relation == "Depended on by" }
        assertEquals("service:7:checkout-api", dependedOnBy.targetId)

        // container -> host resolves to the monitored host; host -> container is the reverse.
        val container = byName.getValue("ctr-1")
        val runsOn = container.relationships.single { it.relation == "Runs on" }
        assertEquals("host", runsOn.kind)
        assertEquals("host:7:42", runsOn.targetId)
        val hostResource = byName.getValue(HOSTNAME)
        assertTrue(
            hostResource.relationships.any { relationship ->
                relationship.relation == "Runs" && relationship.targetId == "container:7:ctr-1"
            }
        )

        // pod -> service via the app label.
        val partOf = byName.getValue("checkout-api-7f9d").relationships.single { it.relation == "Part of" }
        assertEquals("service:7:checkout-api", partOf.targetId)
    }

    @Test
    fun `builds a real deploy changes feed for services and leaves other kinds empty`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        val host = hostData(id = HOST_ID, organizationId = ORGANIZATION_ID, hostname = HOSTNAME, status = "online")
        every { monitorService.listHosts(ORGANIZATION_ID) } returns listOf(host)
        coEvery { monitorService.getLatestMetricsForHosts(listOf(HOST_ID), ORGANIZATION_ID) } returns emptyMap()

        val queryClient = StubResourceCatalogQueryClient(
            serviceRows = listOf(serviceRow(ORGANIZATION_ID, service = "checkout-api")),
            extras = StubResourceCatalogQueryExtras(
                deploymentRows = listOf(
                    jsonObject(
                        """
                        {
                          "service": "checkout-api",
                          "version": "v2026.6.41",
                          "deploy_at": "2026-06-07T10:00:00.000Z",
                          "deployer": "theo.marsh"
                        }
                        """
                    ),
                    jsonObject(
                        """
                        {
                          "service": "checkout-api",
                          "version": "v2026.6.40",
                          "deploy_at": "2026-06-05T09:00:00.000Z",
                          "deployer": ""
                        }
                        """
                    ),
                ),
            ),
        )
        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = queryClient,
            securityReader = NoopResourceSecurityReader,
        )

        val byName = service.listResources(listOf(ORGANIZATION_ID)).associateBy { it.name }

        val checkout = byName.getValue("checkout-api")
        assertEquals(2, checkout.changes.size)
        val latest = checkout.changes.first()
        assertEquals("deploy", latest.kind)
        assertEquals("Deployed v2026.6.41", latest.summary)
        assertEquals("theo.marsh", latest.actor)
        // most recent deploy becomes the service's last change.
        assertEquals("2026-06-07T10:00:00.000Z", checkout.lastChange)
        // blank deployer falls back to a neutral actor rather than an empty string.
        assertEquals("unknown", checkout.changes[1].actor)
        // hosts have no deploy source and keep an empty feed.
        assertTrue(byName.getValue(HOSTNAME).changes.isEmpty())
    }

    @Test
    fun `merges persisted ownership claims and ignores team tags for ownership`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        val host = hostData(
            id = HOST_ID,
            organizationId = ORGANIZATION_ID,
            hostname = HOSTNAME,
            status = "online",
            tags = mapOf("team" to "infra"),
        )
        every { monitorService.listHosts(ORGANIZATION_ID) } returns listOf(host)
        coEvery { monitorService.getLatestMetricsForHosts(listOf(HOST_ID), ORGANIZATION_ID) } returns emptyMap()

        val ownership = InMemoryResourceOwnershipRepository()
        val queryClient = StubResourceCatalogQueryClient(
            serviceRows = listOf(serviceRow(ORGANIZATION_ID, service = "checkout-api")),
        )
        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = queryClient,
            ownershipRepository = ownership,
            teamResolver = InMemoryResourceCatalogTeamResolver(
                mapOf(
                    TEAM_RESOURCE_ID to (
                        TEAM_INTERNAL_ID to CatalogOwner(
                            teamId = TEAM_RESOURCE_ID,
                            teamName = "Payments",
                            slack = "#pay",
                            repo = "moneat/pay",
                        )
                        ),
                ),
            ),
            securityReader = NoopResourceSecurityReader,
        )

        val claimed = service.claimOwnership(
            ORGANIZATION_ID,
            ResourceOwnershipClaim(
                resourceId = "service:7:checkout-api",
                teamId = TEAM_RESOURCE_ID,
            ),
            actor = "admin@moneat.io",
        )
        assertEquals("Payments", claimed?.teamName)

        val byName = service.listResources(listOf(ORGANIZATION_ID)).associateBy { it.name }

        // A persisted claim wins for the service.
        val checkout = byName.getValue("checkout-api")
        assertEquals(TEAM_RESOURCE_ID, checkout.owner?.teamId)
        assertEquals("Payments", checkout.owner?.teamName)
        assertEquals("#pay", checkout.owner?.slack)
        // No claim: a telemetry team tag remains a tag, not ownership.
        val hostResource = byName.getValue(HOSTNAME)
        assertNull(hostResource.owner)
        assertTrue("team:infra" in hostResource.tags)
    }

    @Test
    fun `claim ownership rejects resources outside the scoped catalog`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        every { monitorService.listHosts(ORGANIZATION_ID) } returns emptyList()

        val ownership = InMemoryResourceOwnershipRepository()
        val queryClient = StubResourceCatalogQueryClient(
            serviceRows = listOf(serviceRow(ORGANIZATION_ID, service = "checkout-api")),
        )
        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = queryClient,
            ownershipRepository = ownership,
            securityReader = NoopResourceSecurityReader,
        )

        val owner = service.claimOwnership(
            ORGANIZATION_ID,
            ResourceOwnershipClaim(
                resourceId = "service:8:checkout-api",
                teamId = TEAM_RESOURCE_ID,
            ),
            actor = "admin@moneat.io",
        )

        assertNull(owner)
        assertTrue(ownership.listByOrganization(ORGANIZATION_ID).isEmpty())
    }

    @Test
    fun `enriches resources with real vulnerabilities sbom components and posture by dimension`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        val host = hostData(id = HOST_ID, organizationId = ORGANIZATION_ID, hostname = HOSTNAME, status = "online")
        every { monitorService.listHosts(ORGANIZATION_ID) } returns listOf(host)
        coEvery { monitorService.getLatestMetricsForHosts(listOf(HOST_ID), ORGANIZATION_ID) } returns emptyMap()

        val queryClient = StubResourceCatalogQueryClient(
            serviceRows = listOf(serviceRow(ORGANIZATION_ID, service = SERVICE_NAME)),
            containerRows = listOf(containerRow("ctr-1", "running", mapOf("env" to "prod"))),
        )
        val securityReader = StubResourceSecurityReader(
            ResourceSecuritySnapshot(
                vulnerabilities = listOf(
                    ResourceVulnAggregate(
                        scope = SecurityScope.HOST,
                        key = HOSTNAME,
                        critical = 1,
                        high = 2,
                        medium = 0,
                        low = 3,
                        topFindings = listOf(
                            CatalogSecurityFinding(
                                "CVE-2026-9001",
                                "critical",
                                "glibc",
                                fixedVersion = "2.39",
                                cvss = 9.8,
                            ),
                            CatalogSecurityFinding("CVE-2026-9002", "high", "openssl", fixedVersion = "3.0.14"),
                        ),
                    ),
                    ResourceVulnAggregate(SecurityScope.SERVICE, SERVICE_NAME, 0, 1, 0, 0, emptyList()),
                    ResourceVulnAggregate(
                        scope = SecurityScope.IMAGE,
                        key = "ghcr.io/moneat/checkout:v42",
                        critical = 0,
                        high = 0,
                        medium = 3,
                        low = 0,
                        topFindings = emptyList(),
                    ),
                ),
                components = listOf(
                    ResourceComponentCount(SecurityScope.HOST, HOSTNAME, 120),
                    ResourceComponentCount(SecurityScope.SERVICE, SERVICE_NAME, 80),
                    ResourceComponentCount(SecurityScope.IMAGE, "ghcr.io/moneat/checkout:v42", 200),
                ),
                compliance = listOf(
                    ResourceComplianceRow("host", HOSTNAME, "pci", passed = true),
                    ResourceComplianceRow("host", HOSTNAME, "cis", passed = false),
                ),
            ),
        )
        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = queryClient,
            securityReader = securityReader,
        )

        val byName = service.listResources(listOf(ORGANIZATION_ID)).associateBy { it.name }

        // Host joins by hostname: vuln counts, top findings (most severe first), components, posture by framework.
        val hostResource = byName.getValue(HOSTNAME)
        assertEquals(1, hostResource.vulns.critical)
        assertEquals(2, hostResource.vulns.high)
        assertEquals(3, hostResource.vulns.low)
        assertEquals(120, hostResource.sbomComponents)
        assertEquals("critical", hostResource.findings.first().severity)
        assertEquals("glibc", hostResource.findings.first().pkg)
        assertEquals(listOf("cis", "pci"), hostResource.posture.map { it.label })
        assertFalse(hostResource.posture.first { it.label == "cis" }.pass)
        assertTrue(hostResource.posture.first { it.label == "pci" }.pass)

        // Service joins by name; it has no compliance findings so posture stays empty.
        val serviceResource = byName.getValue(SERVICE_NAME)
        assertEquals(1, serviceResource.vulns.high)
        assertEquals(80, serviceResource.sbomComponents)
        assertTrue(serviceResource.posture.isEmpty())

        // Container joins by image.
        val containerResource = byName.getValue("ctr-1")
        assertEquals(3, containerResource.vulns.medium)
        assertEquals(200, containerResource.sbomComponents)
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
            queryClient = queryClient,
            securityReader = NoopResourceSecurityReader,
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
                      "tags": {"env": "prod", "cloud.provider": "aws", "region": "eu-west-1"},
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
                      "tags": {"env": "dev", "region": "dc-east"},
                      "last_seen": "$LAST_SEEN"
                    }
                    """
                )
            )
        )

        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = queryClient,
            securityReader = NoopResourceSecurityReader,
        )

        val resources = service.listResources(listOf(ORGANIZATION_ID))

        val pod = resources.first { it.kind == "pod" }
        assertEquals("pod:7:pod-uid-1", pod.id)
        assertEquals("warn", pod.health)
        assertEquals("prod", pod.environment)
        assertEquals("aws", pod.cloud)
        assertEquals("eu-west-1", pod.region)
        assertTrue(pod.tags.contains("label:app:checkout"))
        assertTrue(pod.metadata.any { it.label == "Cluster" && it.value == "prod-us-east" })

        val networkDevice = resources.first { it.kind == "network-device" }
        assertEquals("network:7:switch-1", networkDevice.id)
        assertEquals("edge-switch-01", networkDevice.name)
        assertEquals("critical", networkDevice.health)
        assertEquals("dev", networkDevice.environment)
        assertEquals("dc-east", networkDevice.region)
        assertTrue(networkDevice.metadata.any { it.label == "Vendor" && it.value == "Arista" })
    }

    @Test
    fun `returns no resources for empty organization context and clamps result limit`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        every { monitorService.listHosts(ORGANIZATION_ID) } returns emptyList()
        val queryClient = StubResourceCatalogQueryClient(
            serviceRows = listOf(serviceRow(ORGANIZATION_ID), serviceRow(OTHER_ORGANIZATION_ID))
        )
        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = queryClient,
            securityReader = NoopResourceSecurityReader,
        )

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
            queryClient = queryClient,
            securityReader = NoopResourceSecurityReader,
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

    // ──── Telemetry series ────

    @Test
    fun `host telemetry maps real historical metrics and selects the range interval`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        every { monitorService.listHosts(ORGANIZATION_ID) } returns
            listOf(hostData(HOST_ID, ORGANIZATION_ID, HOSTNAME, "online"))
        coEvery { monitorService.getHistoricalMetrics(HOST_ID, any(), any(), null) } returns HistoricalMetricsResponse(
            systemId = "sys",
            from = 0,
            to = 0,
            intervalSeconds = 0,
            dataPoints = listOf(
                metricPoint(timestamp = 1000, cpu = 12f, mem = 40f),
                metricPoint(timestamp = 2000, cpu = 18f, mem = 45f),
            ),
        )
        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = NoopResourceCatalogQueryClient,
            securityReader = NoopResourceSecurityReader,
        )

        val telemetry = service.getResourceTelemetry(hostTelemetryRequest(hostId = HOST_ID, rangeSeconds = 86_400))

        assertEquals("host", telemetry.kind)
        assertEquals(86_400L, telemetry.rangeSeconds)
        // 24h range buckets at five-minute resolution.
        assertEquals(300, telemetry.intervalSeconds)
        val cpu = telemetry.metrics.first { it.key == "cpu" }
        assertEquals("CPU utilization", cpu.label)
        assertEquals(2, cpu.lines.first().points.size)
        assertEquals(12.0, cpu.lines.first().points.first().value)
        assertEquals(1_000_000L, cpu.lines.first().points.first().ts)
        // A column with no samples (disk) is omitted rather than emitted empty.
        assertTrue(telemetry.metrics.none { it.key == "disk" })
    }

    @Test
    fun `service telemetry derives latency throughput and error rate from span stats`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        val queryClient = StubResourceCatalogQueryClient(
            extras = StubResourceCatalogQueryExtras(
                telemetryRows = listOf(
                    jsonObject("""{"ts": 1000, "latency_ms": 245.0, "span_count": 3600, "error_count": 36}"""),
                    jsonObject("""{"ts": 2000, "latency_ms": 250.0, "error_count": 12}"""),
                ),
            ),
        )
        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = queryClient,
            securityReader = NoopResourceSecurityReader,
        )

        val telemetry = service.getResourceTelemetry(serviceTelemetryRequest(SERVICE_NAME, rangeSeconds = 3_600))

        // 1h range buckets at ten-second resolution.
        assertEquals(10, telemetry.intervalSeconds)
        assertEquals(245.0, telemetry.metrics.first { it.key == "latency" }.lines.first().points.first().value)
        // 3600 spans/hour = 1 req/s, 36/3600 errors = 1% error rate.
        val throughputPoints = telemetry.metrics.first { it.key == "throughput" }.lines.first().points
        assertEquals(1.0, throughputPoints.first().value)
        assertNull(throughputPoints.last().value)
        val errorRatePoints = telemetry.metrics.first { it.key == "errorRate" }.lines.first().points
        assertEquals(1.0, errorRatePoints.first().value)
        assertNull(errorRatePoints.last().value)
    }

    @Test
    fun `container telemetry resolves the owning host and maps real metric series`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        every { monitorService.listHosts(ORGANIZATION_ID) } returns
            listOf(hostData(HOST_ID, ORGANIZATION_ID, HOSTNAME, "online").copy(displayName = "checkout-prod"))
        coEvery {
            monitorService.getContainerHistoricalMetrics(HOST_ID, CONTAINER_ID, any(), any(), null)
        } returns ContainerMetricsResponse(
            containerName = CONTAINER_ID,
            from = 0,
            to = 0,
            intervalSeconds = 0,
            dataPoints = listOf(
                ContainerMetricDataPoint(
                    timestamp = 1000,
                    cpuPercent = 25f,
                    memUsed = 256,
                    memLimit = 512,
                    netRecvBytes = 1000,
                    netSentBytes = null,
                ),
                ContainerMetricDataPoint(
                    timestamp = 2000,
                    cpuPercent = null,
                    memUsed = 200,
                    memLimit = 0,
                    netRecvBytes = null,
                    netSentBytes = 2000,
                ),
            ),
        )
        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = NoopResourceCatalogQueryClient,
            securityReader = NoopResourceSecurityReader,
        )

        val telemetry = service.getResourceTelemetry(
            containerTelemetryRequest(containerHost = " CHECKOUT-PROD ", containerName = CONTAINER_ID)
        )

        assertEquals("container", telemetry.kind)
        assertEquals(604_800L, telemetry.rangeSeconds)
        assertEquals(1800, telemetry.intervalSeconds)
        assertEquals(25.0, telemetry.metrics.first { it.key == "cpu" }.lines.single().points.first().value)
        assertEquals(50.0, telemetry.metrics.first { it.key == "mem" }.lines.single().points.first().value)
        val network = telemetry.metrics.first { it.key == "network" }
        assertEquals(listOf("Received", "Sent"), network.lines.map { it.name })
        assertEquals(1000.0, network.lines.first { it.name == "Received" }.points.first().value)
        assertEquals(2000.0, network.lines.first { it.name == "Sent" }.points.last().value)

        val missingSelector = service.getResourceTelemetry(
            containerTelemetryRequest(containerHost = "", containerName = CONTAINER_ID)
        )
        assertTrue(missingSelector.metrics.isEmpty())

        val missingHost = service.getResourceTelemetry(
            containerTelemetryRequest(containerHost = "missing-host", containerName = CONTAINER_ID)
        )
        assertTrue(missingHost.metrics.isEmpty())
    }

    @Test
    fun `clamps the telemetry range and returns empty for unknown kinds and unauthorized hosts`() = runBlocking {
        val monitorService = mockk<MonitorService>()
        every { monitorService.listHosts(ORGANIZATION_ID) } returns emptyList()
        val service = ResourceCatalogService(
            monitorService = monitorService,
            queryClient = NoopResourceCatalogQueryClient,
            securityReader = NoopResourceSecurityReader,
        )

        // A sub-floor range is clamped up to the minimum window, and a host outside the org yields nothing.
        val clamped = service.getResourceTelemetry(hostTelemetryRequest(hostId = HOST_ID, rangeSeconds = 5))
        assertEquals(300L, clamped.rangeSeconds)
        assertTrue(clamped.metrics.isEmpty())

        // Kinds with no telemetry source stay empty.
        val unknownKind = service.getResourceTelemetry(
            ResourceTelemetryRequest(
                organizationIds = listOf(ORGANIZATION_ID),
                kind = "network-device",
                selector = ResourceTelemetrySelector(),
                rangeSeconds = 3_600,
            ),
        )
        assertTrue(unknownKind.metrics.isEmpty())

        // No organization context resolves to nothing.
        val noOrg = service.getResourceTelemetry(
            ResourceTelemetryRequest(
                organizationIds = emptyList(),
                kind = "host",
                selector = ResourceTelemetrySelector(hostId = HOST_ID),
                rangeSeconds = 3_600,
            ),
        )
        assertTrue(noOrg.metrics.isEmpty())
    }

    private fun metricPoint(timestamp: Long, cpu: Float, mem: Float): MetricDataPoint =
        MetricDataPoint(
            timestamp = timestamp,
            cpuPercent = cpu,
            memPercent = mem,
            diskPercent = null,
            netRecvBytes = 10,
            netSentBytes = 20,
            load1 = 0.5f,
            load5 = 0.4f,
            load15 = 0.3f,
            tempMax = null,
            gpuPercent = null,
            batteryPercent = null,
        )

    private fun hostTelemetryRequest(hostId: Int, rangeSeconds: Long): ResourceTelemetryRequest =
        ResourceTelemetryRequest(
            organizationIds = listOf(ORGANIZATION_ID),
            kind = "host",
            selector = ResourceTelemetrySelector(hostId = hostId),
            rangeSeconds = rangeSeconds,
        )

    private fun serviceTelemetryRequest(service: String, rangeSeconds: Long): ResourceTelemetryRequest =
        ResourceTelemetryRequest(
            organizationIds = listOf(ORGANIZATION_ID),
            kind = "service",
            selector = ResourceTelemetrySelector(service = service),
            rangeSeconds = rangeSeconds,
        )

    private fun containerTelemetryRequest(containerHost: String?, containerName: String?): ResourceTelemetryRequest =
        ResourceTelemetryRequest(
            organizationIds = listOf(ORGANIZATION_ID),
            kind = "container",
            selector = ResourceTelemetrySelector(containerHost = containerHost, containerName = containerName),
            rangeSeconds = 604_800,
        )

    private fun hostData(
        id: Int,
        organizationId: Int,
        hostname: String,
        status: String,
        tags: Map<String, String> = emptyMap(),
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
            tags = tags,
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

private class InMemoryResourceOwnershipRepository : ResourceOwnershipRepository {
    private val store = HashMap<Pair<Int, String>, Int>()

    override fun listByOrganization(organizationId: Int): Map<String, Int> =
        store.filterKeys { it.first == organizationId }.mapKeys { it.key.second }

    override fun upsert(organizationId: Int, resourceId: String, teamId: Int, updatedBy: String) {
        store[organizationId to resourceId] = teamId
    }

    override fun delete(organizationId: Int, resourceId: String): Boolean =
        store.remove(organizationId to resourceId) != null

    override fun escalationPolicyIdForResource(organizationId: Int, resourceId: String): Int? = null
}

private class InMemoryResourceCatalogTeamResolver(
    private val teamsByResourceId: Map<String, Pair<Int, CatalogOwner>>,
) : ResourceCatalogTeamResolver {
    override fun resolveTeamId(organizationId: Int, teamResourceId: String): Int? =
        teamsByResourceId[teamResourceId]?.first

    override fun catalogOwnersByInternalIds(organizationId: Int, teamIds: Set<Int>): Map<Int, CatalogOwner> =
        teamsByResourceId
            .values
            .filter { (teamId, _) -> teamId in teamIds }
            .associate { (teamId, owner) -> teamId to owner }
}

private object NoopResourceCatalogQueryClient : ResourceCatalogQueryClient {
    override suspend fun listApmServices(organizationIds: List<Int>, limit: Int): List<JsonObject> = emptyList()
    override suspend fun listContainers(organizationIds: List<Int>, limit: Int): List<JsonObject> = emptyList()
    override suspend fun listKubernetesPods(organizationIds: List<Int>, limit: Int): List<JsonObject> = emptyList()
    override suspend fun listNetworkDevices(organizationIds: List<Int>, limit: Int): List<JsonObject> = emptyList()
    override suspend fun listCloudResources(organizationIds: List<Int>, limit: Int): List<JsonObject> = emptyList()
    override suspend fun serviceTelemetrySeries(
        organizationIds: List<Int>,
        service: String,
        rangeSeconds: Long,
    ): List<JsonObject> = emptyList()

    override suspend fun serviceEdges(organizationIds: List<Int>): List<JsonObject> = emptyList()
    override suspend fun serviceDeployments(organizationIds: List<Int>): List<JsonObject> = emptyList()
}

private data class StubResourceCatalogQueryExtras(
    val telemetryRows: List<JsonObject> = emptyList(),
    val edgeRows: List<JsonObject> = emptyList(),
    val deploymentRows: List<JsonObject> = emptyList(),
)

private class StubResourceCatalogQueryClient(
    private val serviceRows: List<JsonObject> = emptyList(),
    private val containerRows: List<JsonObject> = emptyList(),
    private val podRows: List<JsonObject> = emptyList(),
    private val networkDeviceRows: List<JsonObject> = emptyList(),
    private val cloudRows: List<JsonObject> = emptyList(),
    private val extras: StubResourceCatalogQueryExtras = StubResourceCatalogQueryExtras(),
) : ResourceCatalogQueryClient {
    override suspend fun listApmServices(organizationIds: List<Int>, limit: Int): List<JsonObject> = serviceRows
    override suspend fun listContainers(organizationIds: List<Int>, limit: Int): List<JsonObject> = containerRows
    override suspend fun listKubernetesPods(organizationIds: List<Int>, limit: Int): List<JsonObject> = podRows
    override suspend fun listNetworkDevices(
        organizationIds: List<Int>,
        limit: Int,
    ): List<JsonObject> = networkDeviceRows

    override suspend fun listCloudResources(organizationIds: List<Int>, limit: Int): List<JsonObject> = cloudRows
    override suspend fun serviceTelemetrySeries(
        organizationIds: List<Int>,
        service: String,
        rangeSeconds: Long,
    ): List<JsonObject> = extras.telemetryRows

    override suspend fun serviceEdges(organizationIds: List<Int>): List<JsonObject> = extras.edgeRows
    override suspend fun serviceDeployments(organizationIds: List<Int>): List<JsonObject> = extras.deploymentRows
}

private class StubResourceSecurityReader(
    private val snapshot: ResourceSecuritySnapshot,
) : ResourceSecurityReader {
    override suspend fun read(organizationIds: List<Int>): ResourceSecuritySnapshot = snapshot
}
