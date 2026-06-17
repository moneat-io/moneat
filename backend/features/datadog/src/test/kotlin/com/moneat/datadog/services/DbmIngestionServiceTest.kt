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

import com.moneat.config.RedisConfig
import com.moneat.datadog.models.DdDbmActivityPayload
import com.moneat.datadog.models.DdDbmActivityRow
import com.moneat.datadog.models.DdDbmHealthPayload
import com.moneat.datadog.models.DdDbmMetadataPayload
import com.moneat.datadog.models.DdDbmMetricRow
import com.moneat.datadog.models.DdDbmMetricsPayload
import com.moneat.datadog.models.DdDbmQueryPayload
import com.moneat.datadog.models.DdDbmQueryRow
import io.lettuce.core.XAddArgs
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TEST_DBM_QUEUE = "test:dd:dbm:queue"

class DbmIngestionServiceTest {

    // ──── MAP QUERIES TESTS ────

    @Test
    fun `mapQueries maps payload fields correctly`() {
        val payload = DdDbmQueryPayload(
            dbHost = "pg-primary.local",
            dbSystem = "postgresql",
            dbName = "mydb",
            dbUser = "app_user",
            host = "agent-host",
            env = "production",
            service = "api-server",
            tags = listOf("team:backend", "region:us-east"),
            rows = listOf(
                DdDbmQueryRow(
                    querySignature = "abc123",
                    resourceHash = "hash456",
                    statement = "SELECT * FROM users WHERE id = ?",
                    queryTruncated = false,
                    durationNs = 5000000,
                    rowsAffected = 1,
                    errorCode = 0,
                    errorMessage = "",
                    timestamp = 1700000000L,
                )
            ),
        )

        val batch = DbmIngestionService.mapQueries(1, payload)

        assertEquals(1, batch.organizationId)
        assertEquals("queries", batch.batchType)
        assertEquals(1, batch.queries.size)

        val q = batch.queries[0]
        assertEquals("pg-primary.local", q.dbHost)
        assertEquals("postgresql", q.dbSystem)
        assertEquals("mydb", q.dbName)
        assertEquals("app_user", q.dbUser)
        assertEquals("abc123", q.querySignature)
        assertEquals("hash456", q.resourceHash)
        assertEquals("SELECT * FROM users WHERE id = ?", q.statement)
        assertFalse(q.queryTruncated)
        assertEquals(5000000L, q.durationNs)
        assertEquals(1L, q.rowsAffected)
        assertEquals(0, q.errorCode)
        assertEquals(1700000000000L, q.timestampMs)
        assertEquals("agent-host", q.host)
        assertEquals("production", q.env)
        assertEquals("api-server", q.service)
        assertEquals("backend", q.tags["team"])
        assertEquals("us-east", q.tags["region"])
    }

    @Test
    fun `mapQueries handles empty rows`() {
        val payload = DdDbmQueryPayload(
            dbHost = "test",
            dbSystem = "pg",
            rows = emptyList(),
        )

        val batch = DbmIngestionService.mapQueries(1, payload)
        assertTrue(batch.queries.isEmpty())
    }

    @Test
    fun `mapQueries uses current time when timestamp is null`() {
        val payload = DdDbmQueryPayload(
            dbHost = "test",
            dbSystem = "pg",
            rows = listOf(
                DdDbmQueryRow(
                    querySignature = "sig1",
                    statement = "SELECT 1",
                    timestamp = null,
                )
            ),
        )

        val before = System.currentTimeMillis()
        val batch = DbmIngestionService.mapQueries(1, payload)
        val after = System.currentTimeMillis()

        assertTrue(batch.queries[0].timestampMs in before..after)
    }

    // ──── MAP METRICS TESTS ────

    @Test
    fun `mapMetrics maps payload fields correctly`() {
        val payload = DdDbmMetricsPayload(
            dbHost = "pg-primary.local",
            dbSystem = "postgresql",
            host = "agent-host",
            env = "production",
            tags = listOf("team:backend"),
            rows = listOf(
                DdDbmMetricRow(
                    dbName = "mydb",
                    querySignature = "sig1",
                    timestamp = 1700000000L,
                    calls = 100,
                    totalTimeNs = 5000000000L,
                    rows = 500,
                    sharedBlksHit = 1000,
                    sharedBlksRead = 50,
                )
            ),
        )

        val batch = DbmIngestionService.mapMetrics(1, payload)

        assertEquals("metrics", batch.batchType)
        assertEquals(1, batch.metrics.size)

        val m = batch.metrics[0]
        assertEquals("pg-primary.local", m.dbHost)
        assertEquals("mydb", m.dbName)
        assertEquals("sig1", m.querySignature)
        assertEquals(100L, m.calls)
        assertEquals(5000000000L, m.totalTimeNs)
        assertEquals(500L, m.rows)
        assertEquals(1000L, m.sharedBlksHit)
        assertEquals(50L, m.sharedBlksRead)
        assertEquals("backend", m.tags["team"])
    }

    // ──── MAP ACTIVITY TESTS ────

    @Test
    fun `mapActivity maps payload fields correctly`() {
        val payload = DdDbmActivityPayload(
            dbHost = "pg-primary.local",
            dbSystem = "postgresql",
            host = "agent-host",
            env = "production",
            tags = listOf("team:backend"),
            activity = listOf(
                DdDbmActivityRow(
                    dbName = "mydb",
                    dbUser = "app_user",
                    querySignature = "sig1",
                    statement = "UPDATE orders SET status = ?",
                    state = "active",
                    waitEventType = "Lock",
                    waitEvent = "tuple",
                    blockingPids = listOf(123L, 456L),
                    durationNs = 30000000000L,
                    timestamp = 1700000000L,
                )
            ),
        )

        val batch = DbmIngestionService.mapActivity(1, payload)

        assertEquals("activity", batch.batchType)
        assertEquals(1, batch.activity.size)

        val a = batch.activity[0]
        assertEquals("mydb", a.dbName)
        assertEquals("app_user", a.dbUser)
        assertEquals("sig1", a.querySignature)
        assertEquals("UPDATE orders SET status = ?", a.statement)
        assertEquals("active", a.state)
        assertEquals("Lock", a.waitEventType)
        assertEquals("tuple", a.waitEvent)
        assertEquals(listOf(123L, 456L), a.blockingPids)
        assertEquals(30000000000L, a.durationNs)
    }

    // ──── ENCODE/DECODE ROUND-TRIP TESTS ────

    @Test
    fun `decodeBatch round-trips queries batch`() {
        val payload = DdDbmQueryPayload(
            dbHost = "test",
            dbSystem = "pg",
            rows = listOf(
                DdDbmQueryRow(querySignature = "sig1", statement = "SELECT 1", timestamp = 1700000000L),
            ),
        )

        val batch = DbmIngestionService.mapQueries(1, payload)
        val json = kotlinx.serialization.json.Json.encodeToString(batch)
        val decoded = DbmIngestionService.decodeBatch(json)

        assertEquals("queries", decoded.batchType)
        assertEquals(1, decoded.queries.size)
        assertEquals("sig1", decoded.queries[0].querySignature)
    }

    @Test
    fun `decodeBatch round-trips metrics batch`() {
        val payload = DdDbmMetricsPayload(
            dbHost = "test",
            dbSystem = "pg",
            rows = listOf(
                DdDbmMetricRow(dbName = "mydb", querySignature = "sig1", timestamp = 1700000000L, calls = 50),
            ),
        )

        val batch = DbmIngestionService.mapMetrics(1, payload)
        val json = kotlinx.serialization.json.Json.encodeToString(batch)
        val decoded = DbmIngestionService.decodeBatch(json)

        assertEquals("metrics", decoded.batchType)
        assertEquals(1, decoded.metrics.size)
        assertEquals(50L, decoded.metrics[0].calls)
    }

    @Test
    fun `decodeBatch round-trips activity batch`() {
        val payload = DdDbmActivityPayload(
            dbHost = "test",
            dbSystem = "pg",
            activity = listOf(
                DdDbmActivityRow(
                    dbName = "mydb",
                    querySignature = "sig1",
                    statement = "SELECT 1",
                    state = "active",
                    blockingPids = listOf(1L, 2L),
                    timestamp = 1700000000L,
                ),
            ),
        )

        val batch = DbmIngestionService.mapActivity(1, payload)
        val json = kotlinx.serialization.json.Json.encodeToString(batch)
        val decoded = DbmIngestionService.decodeBatch(json)

        assertEquals("activity", decoded.batchType)
        assertEquals(1, decoded.activity.size)
        assertEquals(listOf(1L, 2L), decoded.activity[0].blockingPids)
    }

    @Test
    fun `enqueueQueryPayloads writes one combined Redis batch`() {
        val redis = mockk<RedisCommands<String, String>>()
        val queuedBody = slot<Map<String, String>>()

        mockkObject(RedisConfig)
        try {
            every { RedisConfig.sync() } returns redis
            every { redis.xadd(any<String>(), any<XAddArgs>(), capture(queuedBody)) } returns "1-0"

            val count = DbmIngestionService.enqueueQueryPayloads(
                organizationId = 42,
                payloads = listOf(
                    DdDbmQueryPayload(
                        dbHost = "pg-a",
                        rows = listOf(DdDbmQueryRow(querySignature = "sig-a", statement = "SELECT 1")),
                    ),
                    DdDbmQueryPayload(
                        dbHost = "pg-b",
                        rows = listOf(DdDbmQueryRow(querySignature = "sig-b", statement = "SELECT 2")),
                    ),
                ),
            )

            val batch = DbmIngestionService.decodeBatch(requireNotNull(queuedBody.captured["payload"]))
            assertEquals(2, count)
            assertEquals(42, batch.organizationId)
            assertEquals("queries", batch.batchType)
            assertEquals(listOf("sig-a", "sig-b"), batch.queries.map { it.querySignature })
            verify(exactly = 1) { redis.xadd(any<String>(), any<XAddArgs>(), any<Map<String, String>>()) }
        } finally {
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `enqueueQueryPayloads skips Redis when batch is empty`() {
        val redis = mockk<RedisCommands<String, String>>(relaxed = true)

        mockkObject(RedisConfig)
        try {
            val count = DbmIngestionService.enqueueQueryPayloads(
                organizationId = 42,
                payloads = listOf(DdDbmQueryPayload(rows = emptyList())),
            )

            assertEquals(0, count)
            verify(exactly = 0) { RedisConfig.sync() }
            verify(exactly = 0) { redis.xadd(any<String>(), any<XAddArgs>(), any<Map<String, String>>()) }
        } finally {
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `enqueueMetricPayloads writes one combined Redis batch`() {
        val redis = mockk<RedisCommands<String, String>>()
        val queuedBody = slot<Map<String, String>>()

        mockkObject(RedisConfig)
        try {
            every { RedisConfig.sync() } returns redis
            every { redis.xadd(any<String>(), any<XAddArgs>(), capture(queuedBody)) } returns "1-0"

            val count = DbmIngestionService.enqueueMetricPayloads(
                organizationId = 42,
                payloads = listOf(
                    DdDbmMetricsPayload(
                        dbHost = "pg-a",
                        rows = listOf(DdDbmMetricRow(dbName = "db-a", querySignature = "sig-a", calls = 10)),
                    ),
                    DdDbmMetricsPayload(
                        dbHost = "pg-b",
                        rows = listOf(DdDbmMetricRow(dbName = "db-b", querySignature = "sig-b", calls = 20)),
                    ),
                ),
            )

            val batch = DbmIngestionService.decodeBatch(requireNotNull(queuedBody.captured["payload"]))
            assertEquals(2, count)
            assertEquals("metrics", batch.batchType)
            assertEquals(listOf("sig-a", "sig-b"), batch.metrics.map { it.querySignature })
            assertEquals(listOf(10L, 20L), batch.metrics.map { it.calls })
            verify(exactly = 1) { redis.xadd(any<String>(), any<XAddArgs>(), any<Map<String, String>>()) }
        } finally {
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `enqueueActivityPayloads writes one combined Redis batch`() {
        val redis = mockk<RedisCommands<String, String>>()
        val queuedBody = slot<Map<String, String>>()

        mockkObject(RedisConfig)
        try {
            every { RedisConfig.sync() } returns redis
            every { redis.xadd(any<String>(), any<XAddArgs>(), capture(queuedBody)) } returns "1-0"

            val count = DbmIngestionService.enqueueActivityPayloads(
                organizationId = 42,
                payloads = listOf(
                    DdDbmActivityPayload(
                        dbHost = "pg-a",
                        activity = listOf(
                            DdDbmActivityRow(querySignature = "sig-a", statement = "SELECT 1", state = "active"),
                        ),
                    ),
                    DdDbmActivityPayload(
                        dbHost = "pg-b",
                        activity = listOf(
                            DdDbmActivityRow(querySignature = "sig-b", statement = "SELECT 2", state = "idle"),
                        ),
                    ),
                ),
            )

            val batch = DbmIngestionService.decodeBatch(requireNotNull(queuedBody.captured["payload"]))
            assertEquals(2, count)
            assertEquals("activity", batch.batchType)
            assertEquals(listOf("sig-a", "sig-b"), batch.activity.map { it.querySignature })
            assertEquals(listOf("active", "idle"), batch.activity.map { it.state })
            verify(exactly = 1) { redis.xadd(any<String>(), any<XAddArgs>(), any<Map<String, String>>()) }
        } finally {
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `enqueueMetadataPayloads writes one combined Redis batch`() {
        val redis = mockk<RedisCommands<String, String>>()
        val queuedBody = slot<Map<String, String>>()

        mockkObject(RedisConfig)
        try {
            every { RedisConfig.sync() } returns redis
            every { redis.xadd(any<String>(), any<XAddArgs>(), capture(queuedBody)) } returns "1-0"

            val count = DbmIngestionService.enqueueMetadataPayloads(
                organizationId = 42,
                payloads = listOf(
                    DdDbmMetadataPayload(host = "pg-a", dbSystem = "postgres", schemaJson = """{"a":1}"""),
                    DdDbmMetadataPayload(host = "pg-b", dbSystem = "postgres", explainPlanHash = "plan-b"),
                ),
            )

            val batch = DbmIngestionService.decodeBatch(requireNotNull(queuedBody.captured["payload"]))
            assertEquals(2, count)
            assertEquals("metadata", batch.batchType)
            assertEquals(listOf("pg-a", "pg-b"), batch.metadata.map { it.host })
            assertEquals("plan-b", batch.metadata[1].explainPlanHash)
            verify(exactly = 1) { redis.xadd(any<String>(), any<XAddArgs>(), any<Map<String, String>>()) }
        } finally {
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `enqueueHealthPayloads writes one combined Redis batch`() {
        val redis = mockk<RedisCommands<String, String>>()
        val queuedBody = slot<Map<String, String>>()

        mockkObject(RedisConfig)
        try {
            every { RedisConfig.sync() } returns redis
            every { redis.xadd(any<String>(), any<XAddArgs>(), capture(queuedBody)) } returns "1-0"

            val count = DbmIngestionService.enqueueHealthPayloads(
                organizationId = 42,
                payloads = listOf(
                    DdDbmHealthPayload(host = "pg-a", status = "ok", checksRun = 3),
                    DdDbmHealthPayload(host = "pg-b", status = "degraded", checksFailed = 1),
                ),
            )

            val batch = DbmIngestionService.decodeBatch(requireNotNull(queuedBody.captured["payload"]))
            assertEquals(2, count)
            assertEquals("health", batch.batchType)
            assertEquals(listOf("pg-a", "pg-b"), batch.health.map { it.host })
            assertEquals(listOf("ok", "degraded"), batch.health.map { it.status })
            verify(exactly = 1) { redis.xadd(any<String>(), any<XAddArgs>(), any<Map<String, String>>()) }
        } finally {
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `single payload enqueue methods delegate to batch helpers`() {
        val redis = mockk<RedisCommands<String, String>>()

        mockkObject(RedisConfig)
        try {
            every { RedisConfig.sync() } returns redis
            every { redis.xadd("$TEST_DBM_QUEUE:stream", any<XAddArgs>(), any<Map<String, String>>()) } returns "1-0"

            val counts = listOf(
                DbmIngestionService.enqueueQueries(
                    42,
                    DdDbmQueryPayload(rows = listOf(DdDbmQueryRow(querySignature = "sig", statement = "SELECT 1"))),
                    TEST_DBM_QUEUE,
                ),
                DbmIngestionService.enqueueMetrics(
                    42,
                    DdDbmMetricsPayload(rows = listOf(DdDbmMetricRow(querySignature = "sig", calls = 1))),
                    TEST_DBM_QUEUE,
                ),
                DbmIngestionService.enqueueActivity(
                    42,
                    DdDbmActivityPayload(activity = listOf(DdDbmActivityRow(querySignature = "sig"))),
                    TEST_DBM_QUEUE,
                ),
                DbmIngestionService.enqueueMetadata(
                    42,
                    DdDbmMetadataPayload(host = "pg-a", dbSystem = "postgres"),
                    TEST_DBM_QUEUE,
                ),
                DbmIngestionService.enqueueHealth(
                    42,
                    DdDbmHealthPayload(host = "pg-a", status = "ok"),
                    TEST_DBM_QUEUE,
                ),
            )

            assertEquals(listOf(1, 1, 1, 1, 1), counts)
            verify(exactly = 5) {
                redis.xadd("$TEST_DBM_QUEUE:stream", any<XAddArgs>(), any<Map<String, String>>())
            }
        } finally {
            unmockkObject(RedisConfig)
        }
    }

    // ──── TAG PARSING TESTS ────

    @Test
    fun `parseDdTagList parses key-value pairs`() {
        val result = DbmIngestionService.parseDdTagList(
            listOf("env:production", "team:backend", "region:us-east")
        )
        assertEquals("production", result["env"])
        assertEquals("backend", result["team"])
        assertEquals("us-east", result["region"])
    }

    @Test
    fun `parseDdTagList handles tags without values`() {
        val result = DbmIngestionService.parseDdTagList(
            listOf("standalone-tag", "env:prod")
        )
        assertEquals("", result["standalone-tag"])
        assertEquals("prod", result["env"])
    }

    @Test
    fun `parseDdTagList handles empty list`() {
        val result = DbmIngestionService.parseDdTagList(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseDdTagList handles tags with colons in value`() {
        val result = DbmIngestionService.parseDdTagList(
            listOf("url:http://example.com:8080/path")
        )
        assertEquals("http://example.com:8080/path", result["url"])
    }
}
