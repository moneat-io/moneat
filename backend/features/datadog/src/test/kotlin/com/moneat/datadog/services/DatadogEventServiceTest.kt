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

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.datadog.models.DatadogEvent
import com.moneat.datadog.models.DatadogServiceCheck
import com.moneat.datadog.models.DatadogServiceCheckPayload
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

class DatadogEventServiceTest {

    @Test
    fun `insert event batches writes one escaped ClickHouse insert`() = runBlocking {
        val queries = mutableListOf<String>()
        MockHttpServer { exchange ->
            queries += exchange.requestBodyText()
            exchange.respond(200, "", contentType = "text/plain")
        }.use { server ->
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            try {
                DatadogEventService.insertEventBatches(
                    listOf(
                        QueuedEventBatch(
                            organizationId = 1,
                            events = listOf(
                                QueuedEventEntry(
                                    title = "O'Reilly deploy",
                                    text = "ready",
                                    timestampMs = 1_700_000_000_000,
                                    tags = mapOf("env" to "prod"),
                                )
                            ),
                        ),
                        QueuedEventBatch(
                            organizationId = 2,
                            events = listOf(QueuedEventEntry(title = "second", timestampMs = 1_700_000_001_000)),
                        ),
                    )
                )
            } finally {
                ClickHouseClient.close()
            }
        }

        val query = queries.single()
        assertTrue(query.contains("INSERT INTO `test`.infra_events"))
        assertTrue(query.contains("O\\'Reilly deploy"))
        assertTrue(query.contains("'env','prod'"))
        assertTrue(query.contains("\n"))
    }

    @Test
    fun `insert service check batches maps every status in one ClickHouse insert`() = runBlocking {
        val queries = mutableListOf<String>()
        MockHttpServer { exchange ->
            queries += exchange.requestBodyText()
            exchange.respond(200, "", contentType = "text/plain")
        }.use { server ->
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            try {
                DatadogEventService.insertServiceCheckBatch(
                    QueuedServiceCheckBatch(
                        organizationId = 3,
                        serviceChecks = (0..3).map { status ->
                            QueuedServiceCheckEntry(
                                checkName = "check-$status",
                                status = status,
                                timestampMs = 1_700_000_000_000,
                                tags = if (status == 0) mapOf("env" to "prod") else emptyMap(),
                            )
                        },
                    )
                )
            } finally {
                ClickHouseClient.close()
            }
        }

        val query = queries.single()
        assertTrue(query.contains("INSERT INTO `test`.service_checks"))
        listOf("ok", "warning", "critical", "unknown").forEach { status ->
            assertTrue(query.contains("'$status'"))
        }
    }

    @Test
    fun `insert service check batch surfaces ClickHouse rejection`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.requestBodyText()
            exchange.respond(500, "service check insert failed", contentType = "text/plain")
        }.use { server ->
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            try {
                val error = assertFailsWith<IllegalStateException> {
                    DatadogEventService.insertServiceCheckBatch(
                        QueuedServiceCheckBatch(
                            organizationId = 5,
                            serviceChecks = listOf(
                                QueuedServiceCheckEntry(
                                    checkName = "failed",
                                    timestampMs = 1_700_000_000_000,
                                )
                            ),
                        )
                    )
                }
                assertTrue(error.message.orEmpty().contains("service check insert failed"))
            } finally {
                ClickHouseClient.close()
            }
        }
    }

    @Test
    fun `insert event batches surfaces ClickHouse rejection`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.requestBodyText()
            exchange.respond(500, "insert failed", contentType = "text/plain")
        }.use { server ->
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            try {
                val error = assertFailsWith<IllegalStateException> {
                    DatadogEventService.insertEventBatch(
                        QueuedEventBatch(
                            organizationId = 4,
                            events = listOf(QueuedEventEntry(title = "failed", timestampMs = 1_700_000_000_000)),
                        )
                    )
                }
                assertTrue(error.message.orEmpty().contains("insert failed"))
            } finally {
                ClickHouseClient.close()
            }
        }
    }

    @Test
    fun `empty event and service check batches skip ClickHouse`() = runBlocking {
        DatadogEventService.insertEventBatches(emptyList())
        DatadogEventService.insertServiceCheckBatches(emptyList())
    }

    @Test
    fun `enqueueEvents serializes events for the dedicated queue`() = runBlocking {
        val redis = mockk<RedisCommands<String, String>>()
        val queuedPayload = slot<String>()

        mockkObject(RedisConfig)
        try {
            every { RedisConfig.sync() } returns redis
            every {
                redis.eval<String>(
                    any<String>(),
                    ScriptOutputType.VALUE,
                    arrayOf("test:dd:event:queue:stream"),
                    any<String>(),
                    capture(queuedPayload),
                    "dd-events",
                    any<String>(),
                )
            } returns "1-0"

            val count = DatadogEventService.enqueueEvents(
                organizationId = 42,
                events = listOf(DatadogEvent(title = "queued")),
                queueKey = "test:dd:event:queue",
            )
            val emptyCount = DatadogEventService.enqueueEvents(
                organizationId = 42,
                events = emptyList(),
                queueKey = "test:dd:event:queue",
            )

            val batch = DatadogEventService.decodeEventBatch(queuedPayload.captured)
            assertEquals(1, count)
            assertEquals(0, emptyCount)
            assertEquals(42, batch.organizationId)
            assertEquals("queued", batch.events.single().title)
        } finally {
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `enqueueServiceChecks serializes checks for the dedicated queue`() = runBlocking {
        val redis = mockk<RedisCommands<String, String>>()
        val queuedPayload = slot<String>()
        val checks = listOf(DatadogServiceCheck(check = "ready", hostName = "host-1", status = 0))

        mockkObject(RedisConfig)
        try {
            every { RedisConfig.sync() } returns redis
            every {
                redis.eval<String>(
                    any<String>(),
                    ScriptOutputType.VALUE,
                    arrayOf("test:dd:service-check:queue:stream"),
                    any<String>(),
                    capture(queuedPayload),
                    "dd-service-checks",
                    any<String>(),
                )
            } returns "1-0"

            val count = DatadogEventService.enqueueServiceChecks(
                organizationId = 42L,
                checks = checks,
                queueKey = "test:dd:service-check:queue",
            )

            val batch = DatadogEventService.decodeServiceCheckBatch(queuedPayload.captured)
            assertEquals(1, count)
            assertEquals(42L, batch.organizationId)
            assertEquals("host-1", batch.serviceChecks.single().host)
        } finally {
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `mapEvents maps fields correctly`() {
        val events = listOf(
            DatadogEvent(
                title = "Deploy started",
                text = "v2.0 rolling out",
                dateHappened = 1700000000,
                priority = "normal",
                host = "web-01",
                tags = listOf("env:prod", "service:api"),
                alertType = "info",
                aggregationKey = "deploy-v2"
            )
        )

        val batch = DatadogEventService.mapEvents(42L, events)

        assertEquals(42L, batch.organizationId)
        assertEquals(1, batch.events.size)

        val e = batch.events[0]
        assertEquals("Deploy started", e.title)
        assertEquals("v2.0 rolling out", e.text)
        assertEquals(1700000000000L, e.timestampMs)
        assertEquals("normal", e.priority)
        assertEquals("web-01", e.host)
        assertEquals("prod", e.tags["env"])
        assertEquals("api", e.tags["service"])
        assertEquals("info", e.alertType)
        assertEquals("deploy-v2", e.aggregationKey)
    }

    @Test
    fun `mapEvents uses current time when dateHappened is null`() {
        val events = listOf(
            DatadogEvent(title = "Test event")
        )

        val before = System.currentTimeMillis()
        val batch = DatadogEventService.mapEvents(1L, events)
        val after = System.currentTimeMillis()

        assertTrue(batch.events[0].timestampMs in before..after)
    }

    @Test
    fun `mapEvents handles empty list`() {
        val batch = DatadogEventService.mapEvents(1L, emptyList())
        assertEquals(0, batch.events.size)
    }

    @Test
    fun `mapEvents normalizes alert types`() {
        val events = listOf(
            DatadogEvent(title = "info", alertType = "info"),
            DatadogEvent(title = "warning", alertType = "warning"),
            DatadogEvent(title = "error", alertType = "error"),
            DatadogEvent(title = "success", alertType = "success"),
            DatadogEvent(title = "unknown", alertType = "custom")
        )

        val batch = DatadogEventService.mapEvents(1L, events)

        assertEquals("info", batch.events[0].alertType)
        assertEquals("warning", batch.events[1].alertType)
        assertEquals("error", batch.events[2].alertType)
        assertEquals("success", batch.events[3].alertType)
        assertEquals("info", batch.events[4].alertType) // default
    }

    @Test
    fun `mapServiceChecks maps fields correctly`() {
        val checks = listOf(
            DatadogServiceCheck(
                check = "http.can_connect",
                hostName = "web-01",
                status = 0,
                timestamp = 1700000000,
                tags = listOf("env:prod"),
                message = "Connection OK"
            )
        )

        val batch = DatadogEventService.mapServiceChecks(42L, checks)

        assertEquals(42L, batch.organizationId)
        assertEquals(1, batch.serviceChecks.size)

        val sc = batch.serviceChecks[0]
        assertEquals("http.can_connect", sc.checkName)
        assertEquals("web-01", sc.host)
        assertEquals(0, sc.status)
        assertEquals(1700000000000L, sc.timestampMs)
        assertEquals("prod", sc.tags["env"])
        assertEquals("Connection OK", sc.message)
    }

    @Test
    fun `mapServiceChecks uses current time when null`() {
        val checks = listOf(
            DatadogServiceCheck(check = "test.check")
        )

        val before = System.currentTimeMillis()
        val batch = DatadogEventService.mapServiceChecks(1L, checks)
        val after = System.currentTimeMillis()

        assertTrue(
            batch.serviceChecks[0].timestampMs in before..after
        )
    }

    @Test
    fun `mapServiceChecks handles empty list`() {
        val batch = DatadogEventService.mapServiceChecks(
            1L,
            emptyList()
        )
        assertEquals(0, batch.serviceChecks.size)
    }

    @Test
    fun `V1 check_run JSON array deserializes correctly`() {
        val payload = """[
            {
                "check": "http.can_connect",
                "host_name": "web-01",
                "status": 0,
                "timestamp": 1700000000,
                "tags": ["env:prod", "service:api"],
                "message": "Connection OK"
            },
            {
                "check": "disk.check",
                "host_name": "web-02",
                "status": 2,
                "tags": ["env:staging"]
            }
        ]"""

        val checks = json.decodeFromString<List<DatadogServiceCheck>>(payload)

        assertEquals(2, checks.size)
        assertEquals("http.can_connect", checks[0].check)
        assertEquals("web-01", checks[0].hostName)
        assertEquals(0, checks[0].status)
        assertEquals(1700000000L, checks[0].timestamp)
        assertEquals(listOf("env:prod", "service:api"), checks[0].tags)
        assertEquals("Connection OK", checks[0].message)

        assertEquals("disk.check", checks[1].check)
        assertEquals(2, checks[1].status)
    }

    @Test
    fun `V1 check_run empty array deserializes correctly`() {
        val checks = json.decodeFromString<List<DatadogServiceCheck>>("[]")
        assertEquals(0, checks.size)
    }

    @Test
    fun `V2 service_checks wrapped payload deserializes correctly`() {
        val payload = """{
            "service_checks": [
                {
                    "check": "http.check",
                    "host_name": "web-01",
                    "status": 1
                }
            ]
        }"""

        val parsed = json.decodeFromString<DatadogServiceCheckPayload>(payload)
        assertEquals(1, parsed.serviceChecks.size)
        assertEquals("http.check", parsed.serviceChecks[0].check)
    }

    @Test
    fun `mapServiceChecks handles V1 array input correctly`() {
        val payload = """[
            {
                "check": "ntp.offset",
                "host_name": "docker-desktop",
                "status": 0,
                "timestamp": 1700000000,
                "tags": ["env:prod"],
                "message": "Offset within bounds"
            }
        ]"""

        val checks = json.decodeFromString<List<DatadogServiceCheck>>(payload)
        val batch = DatadogEventService.mapServiceChecks(42L, checks)

        assertEquals(42L, batch.organizationId)
        assertEquals(1, batch.serviceChecks.size)
        assertEquals("ntp.offset", batch.serviceChecks[0].checkName)
        assertEquals("docker-desktop", batch.serviceChecks[0].host)
        assertEquals(0, batch.serviceChecks[0].status)
    }

    @Test
    fun `decodeEventBatch roundtrips correctly`() {
        val batch = QueuedEventBatch(
            organizationId = 1L,
            events = listOf(
                QueuedEventEntry(
                    title = "Test",
                    timestampMs = 1700000000000L,
                    alertType = "info"
                )
            )
        )

        val encoded = kotlinx.serialization.json.Json
            .encodeToString(batch)
        val decoded = DatadogEventService.decodeEventBatch(encoded)

        assertEquals(batch.organizationId, decoded.organizationId)
        assertEquals(batch.events.size, decoded.events.size)
        assertEquals(
            batch.events[0].title,
            decoded.events[0].title
        )
    }

    @Test
    fun `decodeServiceCheckBatch roundtrips correctly`() {
        val batch = QueuedServiceCheckBatch(
            organizationId = 1L,
            serviceChecks = listOf(
                QueuedServiceCheckEntry(
                    checkName = "http.check",
                    timestampMs = 1700000000000L,
                    status = 0
                )
            )
        )

        val encoded = kotlinx.serialization.json.Json
            .encodeToString(batch)
        val decoded =
            DatadogEventService.decodeServiceCheckBatch(encoded)

        assertEquals(
            batch.organizationId,
            decoded.organizationId
        )
        assertEquals(
            batch.serviceChecks.size,
            decoded.serviceChecks.size
        )
        assertEquals(
            batch.serviceChecks[0].checkName,
            decoded.serviceChecks[0].checkName
        )
    }
}
