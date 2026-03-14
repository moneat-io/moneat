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

@file:Suppress("LargeClass")

package com.moneat.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.datadog.models.DdNdmConfig
import com.moneat.datadog.models.DdNdmDevice
import com.moneat.datadog.models.DdNdmFlow
import com.moneat.datadog.models.DdNdmPath
import com.moneat.datadog.models.DdNdmPayload
import com.moneat.datadog.models.DdNdmTrap
import com.moneat.datadog.networkdevices.NdmIngestionService
import com.moneat.datadog.networkdevices.QueuedNdmBatch
import com.moneat.datadog.networkdevices.QueuedNdmConfigEntry
import com.moneat.datadog.networkdevices.QueuedNdmDeviceEntry
import com.moneat.datadog.networkdevices.QueuedNdmFlowEntry
import com.moneat.datadog.networkdevices.QueuedNdmPathEntry
import com.moneat.datadog.networkdevices.QueuedNdmTrapEntry
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.TestIpConstants
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkDevicesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val orgId = 42

    private lateinit var mockRedis: RedisCommands<String, String>

    @BeforeTest
    fun setup() {
        mockkObject(RedisConfig)
        @Suppress("UNCHECKED_CAST")
        mockRedis = io.mockk.mockk<RedisCommands<String, String>>()
        every { RedisConfig.sync() } returns mockRedis
        every { mockRedis.lpush(any(), any<String>()) } returns 1L
    }

    @AfterTest
    fun cleanup() {
        ClickHouseClient.close()
        unmockkObject(RedisConfig)
    }

    // ---------------------------------------------------------------
    // parseDdTagList
    // ---------------------------------------------------------------

    @Test
    fun `parseDdTagList splits key-colon-value tags`() {
        val result = NdmIngestionService.parseDdTagList(
            listOf("env:prod", "region:us-east-1")
        )
        assertEquals(mapOf("env" to "prod", "region" to "us-east-1"), result)
    }

    @Test
    fun `parseDdTagList handles tag with no value`() {
        val result = NdmIngestionService.parseDdTagList(listOf("standalone"))
        assertEquals(mapOf("standalone" to ""), result)
    }

    @Test
    fun `parseDdTagList handles empty list`() {
        val result = NdmIngestionService.parseDdTagList(emptyList())
        assertEquals(emptyMap(), result)
    }

    @Test
    fun `parseDdTagList handles colon in value`() {
        val result = NdmIngestionService.parseDdTagList(
            listOf("url:http://example.com:8080")
        )
        assertEquals(
            mapOf("url" to "http://example.com:8080"),
            result
        )
    }

    @Test
    fun `parseDdTagList skips empty strings`() {
        val result = NdmIngestionService.parseDdTagList(listOf("", "env:dev"))
        assertEquals(mapOf("env" to "dev"), result)
    }

    // ---------------------------------------------------------------
    // enqueue - devices (type=ndm)
    // ---------------------------------------------------------------

    @Test
    fun `enqueue devices pushes to Redis and returns count`() {
        val payload = DdNdmPayload(
            type = "ndm",
            devices = listOf(
                DdNdmDevice(
                    deviceId = "dev1",
                    ipAddress = TestIpConstants.IP_1,
                    hostname = "switch-1",
                    vendor = "Cisco",
                    model = "C9300",
                    osVersion = "17.3",
                    deviceType = "switch",
                    status = "up",
                    reachability = "reachable",
                    snmpVersion = "v2c",
                    tags = listOf("env:prod", "site:dc1"),
                )
            ),
        )

        val count = NdmIngestionService.enqueue(orgId, payload)

        assertEquals(1, count)
        val pushed = slot<String>()
        verify { mockRedis.lpush("moneat:dd:ndm:queue", capture(pushed)) }
        val batch = json.decodeFromString<QueuedNdmBatch>(pushed.captured)
        assertEquals("devices", batch.batchType)
        assertEquals(orgId, batch.organizationId)
        assertEquals(1, batch.devices.size)
        assertEquals("dev1", batch.devices[0].deviceId)
        assertEquals(TestIpConstants.IP_1, batch.devices[0].ipAddress)
        assertEquals("Cisco", batch.devices[0].vendor)
        assertEquals(mapOf("env" to "prod", "site" to "dc1"), batch.devices[0].tags)
    }

    @Test
    fun `enqueue devices with empty list returns zero`() {
        val payload = DdNdmPayload(type = "ndm", devices = emptyList())
        val count = NdmIngestionService.enqueue(orgId, payload)
        assertEquals(0, count)
    }

    // ---------------------------------------------------------------
    // enqueue - traps (type=ndmtraps)
    // ---------------------------------------------------------------

    @Test
    fun `enqueue traps pushes to Redis and returns count`() {
        val payload = DdNdmPayload(
            type = "ndmtraps",
            traps = listOf(
                DdNdmTrap(
                    deviceIp = TestIpConstants.IP_1,
                    oid = "1.3.6.1.4.1.9.9.43",
                    severity = "warning",
                    message = "Link down",
                    variables = mapOf("ifIndex" to "42"),
                )
            ),
        )

        val count = NdmIngestionService.enqueue(orgId, payload)

        assertEquals(1, count)
        val pushed = slot<String>()
        verify { mockRedis.lpush("moneat:dd:ndm:queue", capture(pushed)) }
        val batch = json.decodeFromString<QueuedNdmBatch>(pushed.captured)
        assertEquals("traps", batch.batchType)
        assertEquals(1, batch.traps.size)
        assertEquals(TestIpConstants.IP_1, batch.traps[0].deviceIp)
        assertEquals("warning", batch.traps[0].severity)
    }

    // ---------------------------------------------------------------
    // enqueue - flows (type=ndmflow)
    // ---------------------------------------------------------------

    @Test
    fun `enqueue flows pushes to Redis and returns count`() {
        val payload = DdNdmPayload(
            type = "ndmflow",
            flows = listOf(
                DdNdmFlow(
                    srcIp = TestIpConstants.IP_10,
                    dstIp = TestIpConstants.IP_5,
                    srcPort = 443,
                    dstPort = 54321,
                    protocol = "TCP",
                    bytes = 1024L,
                    packets = 10L,
                    direction = "ingress",
                    flowType = "sflow",
                    tags = listOf("env:staging"),
                )
            ),
        )

        val count = NdmIngestionService.enqueue(orgId, payload)

        assertEquals(1, count)
        val pushed = slot<String>()
        verify { mockRedis.lpush("moneat:dd:ndm:queue", capture(pushed)) }
        val batch = json.decodeFromString<QueuedNdmBatch>(pushed.captured)
        assertEquals("flows", batch.batchType)
        assertEquals(1, batch.flows.size)
        assertEquals(TestIpConstants.IP_10, batch.flows[0].srcIp)
        assertEquals(443, batch.flows[0].srcPort)
        assertEquals(1024L, batch.flows[0].bytes)
        assertEquals(mapOf("env" to "staging"), batch.flows[0].tags)
    }

    // ---------------------------------------------------------------
    // enqueue - paths (type=netpath)
    // ---------------------------------------------------------------

    @Test
    fun `enqueue paths pushes to Redis and returns count`() {
        val payload = DdNdmPayload(
            type = "netpath",
            paths = listOf(
                DdNdmPath(
                    source = TestIpConstants.IP_1,
                    destination = TestIpConstants.IP_OTHER,
                    hops = listOf(TestIpConstants.IP_1, TestIpConstants.IP_254, TestIpConstants.IP_OTHER),
                    hopRtts = listOf(1.2, 3.4, 5.6),
                    tags = listOf("dc:east"),
                )
            ),
        )

        val count = NdmIngestionService.enqueue(orgId, payload)

        assertEquals(1, count)
        val pushed = slot<String>()
        verify { mockRedis.lpush("moneat:dd:ndm:queue", capture(pushed)) }
        val batch = json.decodeFromString<QueuedNdmBatch>(pushed.captured)
        assertEquals("paths", batch.batchType)
        assertEquals(1, batch.paths.size)
        assertEquals(TestIpConstants.IP_1, batch.paths[0].source)
        assertEquals(3, batch.paths[0].hops.size)
        assertEquals(listOf(1.2, 3.4, 5.6), batch.paths[0].hopRtts)
    }

    // ---------------------------------------------------------------
    // enqueue - configs (type=ndmconfig)
    // ---------------------------------------------------------------

    @Test
    fun `enqueue configs pushes to Redis and returns count`() {
        val payload = DdNdmPayload(
            type = "ndmconfig",
            configs = listOf(
                DdNdmConfig(
                    deviceId = "dev1",
                    configType = "running",
                    content = "hostname router1",
                    tags = listOf("env:prod"),
                )
            ),
        )

        val count = NdmIngestionService.enqueue(orgId, payload)

        assertEquals(1, count)
        val pushed = slot<String>()
        verify { mockRedis.lpush("moneat:dd:ndm:queue", capture(pushed)) }
        val batch = json.decodeFromString<QueuedNdmBatch>(pushed.captured)
        assertEquals("configs", batch.batchType)
        assertEquals(1, batch.configs.size)
        assertEquals("dev1", batch.configs[0].deviceId)
        assertEquals("running", batch.configs[0].configType)
    }

    // ---------------------------------------------------------------
    // enqueue - unknown type
    // ---------------------------------------------------------------

    @Test
    fun `enqueue unknown type returns zero`() {
        val payload = DdNdmPayload(type = "unknown_type")
        val count = NdmIngestionService.enqueue(orgId, payload)
        assertEquals(0, count)
    }

    // ---------------------------------------------------------------
    // enqueue - multiple entries
    // ---------------------------------------------------------------

    @Test
    fun `enqueue multiple devices returns correct count`() {
        val payload = DdNdmPayload(
            type = "ndm",
            devices = listOf(
                DdNdmDevice(deviceId = "d1", tags = emptyList()),
                DdNdmDevice(deviceId = "d2", tags = emptyList()),
                DdNdmDevice(deviceId = "d3", tags = emptyList()),
            ),
        )

        val count = NdmIngestionService.enqueue(orgId, payload)

        assertEquals(3, count)
    }

    // ---------------------------------------------------------------
    // decodeBatch round-trip
    // ---------------------------------------------------------------

    @Test
    fun `decodeBatch round-trips a device batch`() {
        val batch = QueuedNdmBatch(
            organizationId = orgId,
            batchType = "devices",
            devices = listOf(
                QueuedNdmDeviceEntry(
                    deviceId = "d1",
                    ipAddress = TestIpConstants.IP_1,
                    hostname = "sw1",
                    vendor = "Cisco",
                    model = "C9300",
                    osVersion = "17.3",
                    deviceType = "switch",
                    status = "up",
                    reachability = "reachable",
                    snmpVersion = "v2c",
                    tags = mapOf("env" to "prod"),
                    timestampMs = 1700000000000L,
                )
            ),
        )
        val encoded = json.encodeToString(batch)
        val decoded = NdmIngestionService.decodeBatch(encoded)
        assertEquals(batch, decoded)
    }

    @Test
    fun `decodeBatch round-trips a trap batch`() {
        val batch = QueuedNdmBatch(
            organizationId = orgId,
            batchType = "traps",
            traps = listOf(
                QueuedNdmTrapEntry(
                    deviceIp = TestIpConstants.IP_1,
                    oid = "1.3.6",
                    severity = "critical",
                    message = "down",
                    variables = mapOf("k" to "v"),
                    timestampMs = 1700000000000L,
                )
            ),
        )
        val encoded = json.encodeToString(batch)
        val decoded = NdmIngestionService.decodeBatch(encoded)
        assertEquals(batch, decoded)
    }

    @Test
    fun `decodeBatch round-trips a flow batch`() {
        val batch = QueuedNdmBatch(
            organizationId = orgId,
            batchType = "flows",
            flows = listOf(
                QueuedNdmFlowEntry(
                    srcIp = TestIpConstants.IP_SRC,
                    dstIp = TestIpConstants.IP_DST,
                    srcPort = 80,
                    dstPort = 12345,
                    protocol = "UDP",
                    bytes = 2048L,
                    packets = 4L,
                    direction = "egress",
                    flowType = "ipfix",
                    tags = emptyMap(),
                    timestampMs = 1700000000000L,
                )
            ),
        )
        val encoded = json.encodeToString(batch)
        val decoded = NdmIngestionService.decodeBatch(encoded)
        assertEquals(batch, decoded)
    }

    @Test
    fun `decodeBatch round-trips a path batch`() {
        val batch = QueuedNdmBatch(
            organizationId = orgId,
            batchType = "paths",
            paths = listOf(
                QueuedNdmPathEntry(
                    source = "a",
                    destination = "b",
                    hops = listOf("a", "c", "b"),
                    hopRtts = listOf(0.5, 1.0, 1.5),
                    tags = mapOf("dc" to "us1"),
                    timestampMs = 1700000000000L,
                )
            ),
        )
        val encoded = json.encodeToString(batch)
        val decoded = NdmIngestionService.decodeBatch(encoded)
        assertEquals(batch, decoded)
    }

    @Test
    fun `decodeBatch round-trips a config batch`() {
        val batch = QueuedNdmBatch(
            organizationId = orgId,
            batchType = "configs",
            configs = listOf(
                QueuedNdmConfigEntry(
                    deviceId = "dev1",
                    configType = "startup",
                    content = "hostname r1",
                    tags = emptyMap(),
                    timestampMs = 1700000000000L,
                )
            ),
        )
        val encoded = json.encodeToString(batch)
        val decoded = NdmIngestionService.decodeBatch(encoded)
        assertEquals(batch, decoded)
    }

    // ---------------------------------------------------------------
    // insertBatch - devices → ClickHouse
    // ---------------------------------------------------------------

    @Test
    fun `insertBatch devices sends INSERT to ClickHouse`() = runBlocking {
        val captured = mutableListOf<String>()
        MockHttpServer { exchange ->
            captured.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val batch = QueuedNdmBatch(
                organizationId = orgId,
                batchType = "devices",
                devices = listOf(
                    QueuedNdmDeviceEntry(
                        deviceId = "d1",
                        ipAddress = TestIpConstants.IP_1,
                        hostname = "sw1",
                        vendor = "Cisco",
                        model = "C9300",
                        osVersion = "17.3",
                        deviceType = "switch",
                        status = "up",
                        reachability = "reachable",
                        snmpVersion = "v2c",
                        tags = mapOf("env" to "prod"),
                        timestampMs = 1700000000000L,
                    )
                ),
            )

            NdmIngestionService.insertBatch(batch)

            assertEquals(1, captured.size)
            val sql = captured[0]
            assertTrue(sql.contains("INSERT INTO"), "Expected INSERT statement")
            assertTrue(sql.contains("ndm_devices"), "Expected ndm_devices table")
            assertTrue(sql.contains("d1"), "Expected device_id")
            assertTrue(sql.contains(TestIpConstants.IP_1), "Expected ip_address")
            assertTrue(sql.contains("Cisco"), "Expected vendor")
        }
    }

    @Test
    fun `insertBatch devices with empty list is a no-op`() = runBlocking {
        val captured = mutableListOf<String>()
        MockHttpServer { exchange ->
            captured.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val batch = QueuedNdmBatch(
                organizationId = orgId,
                batchType = "devices",
                devices = emptyList(),
            )

            NdmIngestionService.insertBatch(batch)
            assertEquals(0, captured.size)
        }
    }

    @Test
    fun `insertBatch devices with multiple entries sends all rows`() =
        runBlocking {
            val captured = mutableListOf<String>()
            MockHttpServer { exchange ->
                captured.add(exchange.requestBodyText())
                exchange.respond(200, "")
            }.use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")

                val batch = QueuedNdmBatch(
                    organizationId = orgId,
                    batchType = "devices",
                    devices = listOf(
                        QueuedNdmDeviceEntry(
                            deviceId = "d1",
                            timestampMs = 1700000000000L,
                        ),
                        QueuedNdmDeviceEntry(
                            deviceId = "d2",
                            timestampMs = 1700000000000L,
                        ),
                    ),
                )

                NdmIngestionService.insertBatch(batch)

                assertEquals(1, captured.size)
                assertTrue(captured[0].contains("d1"))
                assertTrue(captured[0].contains("d2"))
            }
        }

    // ---------------------------------------------------------------
    // insertBatch - traps → ClickHouse
    // ---------------------------------------------------------------

    @Test
    fun `insertBatch traps sends INSERT to ClickHouse`() = runBlocking {
        val captured = mutableListOf<String>()
        MockHttpServer { exchange ->
            captured.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val batch = QueuedNdmBatch(
                organizationId = orgId,
                batchType = "traps",
                traps = listOf(
                    QueuedNdmTrapEntry(
                        deviceIp = TestIpConstants.IP_1,
                        oid = "1.3.6.1",
                        severity = "warning",
                        message = "Link down",
                        variables = mapOf("ifIndex" to "1"),
                        timestampMs = 1700000000000L,
                    )
                ),
            )

            NdmIngestionService.insertBatch(batch)

            assertEquals(1, captured.size)
            val sql = captured[0]
            assertTrue(sql.contains("ndm_traps"))
            assertTrue(sql.contains(TestIpConstants.IP_1))
            assertTrue(sql.contains("1.3.6.1"))
            assertTrue(sql.contains("warning"))
        }
    }

    @Test
    fun `insertBatch traps with empty list is a no-op`() = runBlocking {
        val captured = mutableListOf<String>()
        MockHttpServer { exchange ->
            captured.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            NdmIngestionService.insertBatch(
                QueuedNdmBatch(orgId, "traps", traps = emptyList())
            )
            assertEquals(0, captured.size)
        }
    }

    // ---------------------------------------------------------------
    // insertBatch - flows → ClickHouse
    // ---------------------------------------------------------------

    @Test
    fun `insertBatch flows sends INSERT to ClickHouse`() = runBlocking {
        val captured = mutableListOf<String>()
        MockHttpServer { exchange ->
            captured.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val batch = QueuedNdmBatch(
                organizationId = orgId,
                batchType = "flows",
                flows = listOf(
                    QueuedNdmFlowEntry(
                        srcIp = TestIpConstants.IP_SRC,
                        dstIp = TestIpConstants.IP_DST,
                        srcPort = 80,
                        dstPort = 12345,
                        protocol = "TCP",
                        bytes = 4096L,
                        packets = 8L,
                        direction = "ingress",
                        flowType = "netflow",
                        tags = mapOf("site" to "dc1"),
                        timestampMs = 1700000000000L,
                    )
                ),
            )

            NdmIngestionService.insertBatch(batch)

            assertEquals(1, captured.size)
            val sql = captured[0]
            assertTrue(sql.contains("ndm_flows"))
            assertTrue(sql.contains(TestIpConstants.IP_SRC))
            assertTrue(sql.contains("TCP"))
            assertTrue(sql.contains("4096"))
        }
    }

    @Test
    fun `insertBatch flows normalizes flow type`() = runBlocking {
        val captured = mutableListOf<String>()
        MockHttpServer { exchange ->
            captured.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val entries = listOf("sflow", "ipfix", "random").map { ft ->
                QueuedNdmFlowEntry(
                    flowType = ft,
                    timestampMs = 1700000000000L,
                )
            }

            NdmIngestionService.insertBatch(
                QueuedNdmBatch(orgId, "flows", flows = entries)
            )

            assertEquals(1, captured.size)
            val sql = captured[0]
            assertTrue(sql.contains("'sflow'"))
            assertTrue(sql.contains("'ipfix'"))
            assertTrue(sql.contains("'netflow'"))
        }
    }

    @Test
    fun `insertBatch flows with empty list is a no-op`() = runBlocking {
        val captured = mutableListOf<String>()
        MockHttpServer { exchange ->
            captured.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            NdmIngestionService.insertBatch(
                QueuedNdmBatch(orgId, "flows", flows = emptyList())
            )
            assertEquals(0, captured.size)
        }
    }

    // ---------------------------------------------------------------
    // insertBatch - paths → ClickHouse
    // ---------------------------------------------------------------

    @Test
    fun `insertBatch paths sends INSERT to ClickHouse`() = runBlocking {
        val captured = mutableListOf<String>()
        MockHttpServer { exchange ->
            captured.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val batch = QueuedNdmBatch(
                organizationId = orgId,
                batchType = "paths",
                paths = listOf(
                    QueuedNdmPathEntry(
                        source = TestIpConstants.IP_1,
                        destination = TestIpConstants.IP_OTHER,
                        hops = listOf(TestIpConstants.IP_1, TestIpConstants.IP_254),
                        hopRtts = listOf(1.5, 3.0),
                        tags = mapOf("dc" to "east"),
                        timestampMs = 1700000000000L,
                    )
                ),
            )

            NdmIngestionService.insertBatch(batch)

            assertEquals(1, captured.size)
            val sql = captured[0]
            assertTrue(sql.contains("network_paths"))
            assertTrue(sql.contains(TestIpConstants.IP_1))
            assertTrue(sql.contains(TestIpConstants.IP_254))
        }
    }

    @Test
    fun `insertBatch paths with empty list is a no-op`() = runBlocking {
        val captured = mutableListOf<String>()
        MockHttpServer { exchange ->
            captured.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            NdmIngestionService.insertBatch(
                QueuedNdmBatch(orgId, "paths", paths = emptyList())
            )
            assertEquals(0, captured.size)
        }
    }

    // ---------------------------------------------------------------
    // insertBatch - configs → ClickHouse
    // ---------------------------------------------------------------

    @Test
    fun `insertBatch configs sends INSERT to ClickHouse`() = runBlocking {
        val captured = mutableListOf<String>()
        MockHttpServer { exchange ->
            captured.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val batch = QueuedNdmBatch(
                organizationId = orgId,
                batchType = "configs",
                configs = listOf(
                    QueuedNdmConfigEntry(
                        deviceId = "dev1",
                        configType = "running",
                        content = "hostname router1",
                        tags = mapOf("env" to "prod"),
                        timestampMs = 1700000000000L,
                    )
                ),
            )

            NdmIngestionService.insertBatch(batch)

            assertEquals(1, captured.size)
            val sql = captured[0]
            assertTrue(sql.contains("ndm_configs"))
            assertTrue(sql.contains("dev1"))
            assertTrue(sql.contains("running"))
            assertTrue(sql.contains("hostname router1"))
        }
    }

    @Test
    fun `insertBatch configs with empty list is a no-op`() = runBlocking {
        val captured = mutableListOf<String>()
        MockHttpServer { exchange ->
            captured.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            NdmIngestionService.insertBatch(
                QueuedNdmBatch(orgId, "configs", configs = emptyList())
            )
            assertEquals(0, captured.size)
        }
    }

    // ---------------------------------------------------------------
    // insertBatch - ClickHouse failure
    // ---------------------------------------------------------------

    @Test
    fun `insertBatch throws on ClickHouse error`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.requestBodyText()
            exchange.respond(500, "Code: 60. DB::Exception")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val batch = QueuedNdmBatch(
                organizationId = orgId,
                batchType = "devices",
                devices = listOf(
                    QueuedNdmDeviceEntry(
                        deviceId = "d1",
                        timestampMs = 1700000000000L,
                    )
                ),
            )

            var thrown = false
            try {
                NdmIngestionService.insertBatch(batch)
            } catch (e: IllegalStateException) {
                thrown = true
                assertTrue(
                    e.message?.contains("Failed to insert") == true
                )
            }
            assertTrue(thrown, "Expected IllegalStateException")
        }
    }

    // ---------------------------------------------------------------
    // insertBatch - unknown batch type is a no-op
    // ---------------------------------------------------------------

    @Test
    fun `insertBatch with unknown batchType is a no-op`() = runBlocking {
        val captured = mutableListOf<String>()
        MockHttpServer { exchange ->
            captured.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            NdmIngestionService.insertBatch(
                QueuedNdmBatch(orgId, "bogus")
            )
            assertEquals(0, captured.size)
        }
    }

    // ---------------------------------------------------------------
    // SQL escaping in INSERT statements
    // ---------------------------------------------------------------

    @Test
    fun `insertBatch devices escapes SQL injection in fields`() =
        runBlocking {
            val captured = mutableListOf<String>()
            MockHttpServer { exchange ->
                captured.add(exchange.requestBodyText())
                exchange.respond(200, "")
            }.use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")

                val batch = QueuedNdmBatch(
                    organizationId = orgId,
                    batchType = "devices",
                    devices = listOf(
                        QueuedNdmDeviceEntry(
                            deviceId = "d'; DROP TABLE --",
                            hostname = "host'name",
                            timestampMs = 1700000000000L,
                        )
                    ),
                )

                NdmIngestionService.insertBatch(batch)

                assertEquals(1, captured.size)
                val sql = captured[0]
                assertTrue(
                    !sql.contains("d'; DROP"),
                    "Raw SQL injection should be escaped"
                )
                assertTrue(
                    sql.contains("d\\'"),
                    "Single quote should be escaped"
                )
            }
        }

    // ---------------------------------------------------------------
    // mapToSqlMap via insertBatch (tags)
    // ---------------------------------------------------------------

    @Test
    fun `insertBatch devices with empty tags produces map()`() =
        runBlocking {
            val captured = mutableListOf<String>()
            MockHttpServer { exchange ->
                captured.add(exchange.requestBodyText())
                exchange.respond(200, "")
            }.use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")

                val batch = QueuedNdmBatch(
                    organizationId = orgId,
                    batchType = "devices",
                    devices = listOf(
                        QueuedNdmDeviceEntry(
                            deviceId = "d1",
                            tags = emptyMap(),
                            timestampMs = 1700000000000L,
                        )
                    ),
                )

                NdmIngestionService.insertBatch(batch)

                assertTrue(captured[0].contains("map()"))
            }
        }

    @Test
    fun `insertBatch devices with tags produces map with entries`() =
        runBlocking {
            val captured = mutableListOf<String>()
            MockHttpServer { exchange ->
                captured.add(exchange.requestBodyText())
                exchange.respond(200, "")
            }.use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")

                val batch = QueuedNdmBatch(
                    organizationId = orgId,
                    batchType = "devices",
                    devices = listOf(
                        QueuedNdmDeviceEntry(
                            deviceId = "d1",
                            tags = mapOf("env" to "prod", "dc" to "us1"),
                            timestampMs = 1700000000000L,
                        )
                    ),
                )

                NdmIngestionService.insertBatch(batch)

                val sql = captured[0]
                assertTrue(sql.contains("map("))
                assertTrue(sql.contains("'env'"))
                assertTrue(sql.contains("'prod'"))
                assertTrue(sql.contains("'dc'"))
                assertTrue(sql.contains("'us1'"))
            }
        }

    // ---------------------------------------------------------------
    // enqueue full round-trip: enqueue → decodeBatch → insertBatch
    // ---------------------------------------------------------------

    @Test
    fun `full round-trip enqueue then decode and insert`() = runBlocking {
        val pushed = slot<String>()
        every {
            mockRedis.lpush("moneat:dd:ndm:queue", capture(pushed))
        } returns 1L

        val payload = DdNdmPayload(
            type = "ndm",
            devices = listOf(
                DdNdmDevice(
                    deviceId = "roundtrip-dev",
                    ipAddress = TestIpConstants.IP_OTHER,
                    vendor = "Juniper",
                    tags = listOf("env:test"),
                )
            ),
        )

        NdmIngestionService.enqueue(orgId, payload)

        val batch = NdmIngestionService.decodeBatch(pushed.captured)
        assertEquals("devices", batch.batchType)
        assertEquals(orgId, batch.organizationId)
        assertEquals("roundtrip-dev", batch.devices[0].deviceId)

        val captured = mutableListOf<String>()
        MockHttpServer { exchange ->
            captured.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            NdmIngestionService.insertBatch(batch)

            assertTrue(captured[0].contains("roundtrip-dev"))
            assertTrue(captured[0].contains(TestIpConstants.IP_OTHER))
        }
    }
}
