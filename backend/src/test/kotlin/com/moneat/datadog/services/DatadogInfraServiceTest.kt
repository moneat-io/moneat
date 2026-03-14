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

package com.moneat.datadog.services

import com.moneat.datadog.models.DatadogConnection
import com.moneat.testsupport.TestIpConstants
import com.moneat.datadog.models.DatadogConnectionsPayload
import com.moneat.datadog.models.DatadogContainer
import com.moneat.datadog.models.DatadogContainerPayload
import com.moneat.datadog.models.DatadogProcess
import com.moneat.datadog.models.DatadogProcessPayload
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatadogInfraServiceTest {

    @Test
    fun `mapProcesses maps fields correctly`() {
        val payload = DatadogProcessPayload(
            host = "web-01",
            processes = listOf(
                DatadogProcess(
                    pid = 1234,
                    name = "java",
                    command = "java -jar app.jar",
                    user = "app",
                    cpuPercent = 25.5,
                    memRss = 1024000,
                    memVms = 2048000,
                    state = "running",
                    threadCount = 42,
                    openFdCount = 100,
                    tags = listOf("env:prod")
                )
            )
        )

        val batch = DatadogInfraService.mapProcesses(42L, payload)

        assertEquals(42L, batch.organizationId)
        assertEquals("processes", batch.type)
        assertEquals(1, batch.processes.size)

        val p = batch.processes[0]
        assertEquals("web-01", p.host)
        assertEquals(1234, p.pid)
        assertEquals("java", p.name)
        assertEquals("java -jar app.jar", p.command)
        assertEquals("app", p.user)
        assertEquals(25.5, p.cpuPercent)
        assertEquals(1024000, p.memRss)
        assertEquals(42, p.threadCount)
        assertEquals("prod", p.tags["env"])
    }

    @Test
    fun `mapProcesses handles empty processes`() {
        val payload = DatadogProcessPayload(host = "web-01")
        val batch = DatadogInfraService.mapProcesses(1L, payload)
        assertEquals(0, batch.processes.size)
    }

    @Test
    fun `mapContainers maps fields correctly`() {
        val payload = DatadogContainerPayload(
            host = "docker-01",
            containers = listOf(
                DatadogContainer(
                    containerId = "abc123",
                    name = "nginx",
                    image = "nginx:latest",
                    state = "running",
                    cpuPercent = 5.0,
                    memUsage = 50000000,
                    memLimit = 100000000,
                    netRxBytes = 1000,
                    netTxBytes = 2000,
                    tags = listOf("service:web")
                )
            )
        )

        val batch = DatadogInfraService.mapContainers(
            42L,
            payload
        )

        assertEquals(42L, batch.organizationId)
        assertEquals("containers", batch.type)
        assertEquals(1, batch.containers.size)

        val c = batch.containers[0]
        assertEquals("docker-01", c.host)
        assertEquals("abc123", c.containerId)
        assertEquals("nginx", c.name)
        assertEquals("nginx:latest", c.image)
        assertEquals("running", c.state)
        assertEquals(5.0, c.cpuPercent)
        assertEquals(50000000, c.memUsage)
        assertEquals("web", c.tags["service"])
    }

    @Test
    fun `mapContainers handles empty containers`() {
        val payload = DatadogContainerPayload(host = "h")
        val batch = DatadogInfraService.mapContainers(1L, payload)
        assertEquals(0, batch.containers.size)
    }

    @Test
    fun `mapConnections maps fields correctly`() {
        val payload = DatadogConnectionsPayload(
            host = "web-01",
            connections = listOf(
                DatadogConnection(
                    pid = 1234,
                    localAddr = TestIpConstants.IP_1,
                    localPort = 8080,
                    remoteAddr = TestIpConstants.IP_2,
                    remotePort = 443,
                    protocol = "tcp",
                    family = "IPv4",
                    direction = "outgoing",
                    bytesSent = 5000,
                    bytesRecv = 10000,
                    tags = listOf("service:api")
                )
            )
        )

        val batch = DatadogInfraService.mapConnections(
            42L,
            payload
        )

        assertEquals(42L, batch.organizationId)
        assertEquals("connections", batch.type)
        assertEquals(1, batch.connections.size)

        val c = batch.connections[0]
        assertEquals("web-01", c.host)
        assertEquals(1234, c.pid)
        assertEquals(TestIpConstants.IP_1, c.localAddr)
        assertEquals(8080, c.localPort)
        assertEquals(TestIpConstants.IP_2, c.remoteAddr)
        assertEquals(443, c.remotePort)
        assertEquals("tcp", c.protocol)
        assertEquals("IPv4", c.family)
        assertEquals("outgoing", c.direction)
        assertEquals(5000, c.bytesSent)
        assertEquals(10000, c.bytesRecv)
        assertEquals("api", c.tags["service"])
    }

    @Test
    fun `mapConnections handles empty connections`() {
        val payload = DatadogConnectionsPayload(host = "h")
        val batch = DatadogInfraService.mapConnections(1L, payload)
        assertEquals(0, batch.connections.size)
    }

    @Test
    fun `decodeInfraBatch roundtrips processes`() {
        val batch = QueuedInfraBatch(
            organizationId = 1L,
            type = "processes",
            processes = listOf(
                QueuedProcessEntry(
                    host = "h",
                    pid = 1,
                    name = "test",
                    timestampMs = 1700000000000L
                )
            )
        )

        val encoded = kotlinx.serialization.json.Json
            .encodeToString(batch)
        val decoded = DatadogInfraService.decodeInfraBatch(encoded)

        assertEquals(batch.organizationId, decoded.organizationId)
        assertEquals("processes", decoded.type)
        assertEquals(1, decoded.processes.size)
        assertEquals("test", decoded.processes[0].name)
    }

    @Test
    fun `decodeInfraBatch roundtrips containers`() {
        val batch = QueuedInfraBatch(
            organizationId = 1L,
            type = "containers",
            containers = listOf(
                QueuedContainerEntry(
                    host = "h",
                    containerId = "abc",
                    timestampMs = 1700000000000L
                )
            )
        )

        val encoded = kotlinx.serialization.json.Json
            .encodeToString(batch)
        val decoded = DatadogInfraService.decodeInfraBatch(encoded)

        assertEquals("containers", decoded.type)
        assertEquals(1, decoded.containers.size)
        assertEquals("abc", decoded.containers[0].containerId)
    }

    @Test
    fun `mapProcesses sets timestamp near current time`() {
        val payload = DatadogProcessPayload(
            host = "h",
            processes = listOf(DatadogProcess(pid = 1))
        )

        val before = System.currentTimeMillis()
        val batch = DatadogInfraService.mapProcesses(1L, payload)
        val after = System.currentTimeMillis()

        assertTrue(
            batch.processes[0].timestampMs in before..after
        )
    }
}
