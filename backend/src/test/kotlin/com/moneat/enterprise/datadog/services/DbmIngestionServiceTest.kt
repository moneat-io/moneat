// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.services

import com.moneat.enterprise.datadog.models.DdDbmActivityPayload
import com.moneat.enterprise.datadog.models.DdDbmActivityRow
import com.moneat.enterprise.datadog.models.DdDbmMetricRow
import com.moneat.enterprise.datadog.models.DdDbmMetricsPayload
import com.moneat.enterprise.datadog.models.DdDbmQueryPayload
import com.moneat.enterprise.datadog.models.DdDbmQueryRow
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DbmIngestionServiceTest {

    // ============ MAP QUERIES TESTS ============

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
            dbHost = "test", dbSystem = "pg", rows = emptyList(),
        )

        val batch = DbmIngestionService.mapQueries(1, payload)
        assertTrue(batch.queries.isEmpty())
    }

    @Test
    fun `mapQueries uses current time when timestamp is null`() {
        val payload = DdDbmQueryPayload(
            dbHost = "test", dbSystem = "pg",
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

    // ============ MAP METRICS TESTS ============

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

    // ============ MAP ACTIVITY TESTS ============

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

    // ============ ENCODE/DECODE ROUND-TRIP TESTS ============

    @Test
    fun `decodeBatch round-trips queries batch`() {
        val payload = DdDbmQueryPayload(
            dbHost = "test", dbSystem = "pg",
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
            dbHost = "test", dbSystem = "pg",
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
            dbHost = "test", dbSystem = "pg",
            activity = listOf(
                DdDbmActivityRow(
                    dbName = "mydb", querySignature = "sig1",
                    statement = "SELECT 1", state = "active",
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

    // ============ TAG PARSING TESTS ============

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
