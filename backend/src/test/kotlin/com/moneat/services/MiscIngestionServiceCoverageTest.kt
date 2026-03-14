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

import com.moneat.config.ClickHouseClient
import com.moneat.datadog.services.MiscIngestionService
import com.moneat.datadog.services.QueuedContainerImageEntry
import com.moneat.datadog.services.QueuedDataLineageEntry
import com.moneat.datadog.services.QueuedDataStreamEntry
import com.moneat.datadog.services.QueuedMiscBatch
import com.moneat.datadog.services.QueuedPipelineStatEntry
import com.moneat.datadog.services.QueuedSbomEntry
import com.moneat.datadog.services.QueuedSymbolDbEntry
import com.moneat.datadog.services.QueuedSyntheticEntry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Extended coverage tests for MiscIngestionService covering:
 *  - insertBatch routing for all 7 batch types
 *  - SQL generation for each insert method
 *  - parseDdTagList edge cases
 *  - decodeBatch error handling
 *  - insertBatch with multiple entries per batch
 *  - direction normalization in data_streams
 *  - testType/status normalization in synthetics
 */
class MiscIngestionServiceCoverageTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @BeforeTest
    fun setup() {
        mockkObject(ClickHouseClient)
        every { ClickHouseClient.getDatabase() } returns "test_db"
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ClickHouseClient)
    }

    private fun mockClickHouseSuccess() {
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.OK
            response
        }
    }

    // ──── insertBatch routing ────

    @Test
    fun `insertBatch routes symbol_db batch`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            capturedSql.add(firstArg())
            val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.OK
            response
        }

        val batch = QueuedMiscBatch(
            organizationId = 1,
            batchType = "symbol_db",
            symbolDb = listOf(
                QueuedSymbolDbEntry(
                    service = "api",
                    env = "prod",
                    language = "java",
                    version = "11",
                    symbols = "com.example.Main",
                    timestampMs = 1700000000000L,
                )
            ),
        )

        MiscIngestionService.insertBatch(batch)

        assertEquals(1, capturedSql.size)
        assertTrue(capturedSql[0].contains("symbol_db"))
        assertTrue(capturedSql[0].contains("api"))
        assertTrue(capturedSql[0].contains("java"))
    }

    @Test
    fun `insertBatch routes pipeline_stats batch`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            capturedSql.add(firstArg())
            val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.OK
            response
        }

        val batch = QueuedMiscBatch(
            organizationId = 2,
            batchType = "pipeline_stats",
            pipelineStats = listOf(
                QueuedPipelineStatEntry(
                    pipelineId = "pipe-1",
                    stageName = "parse",
                    inCount = 100,
                    outCount = 95,
                    dropCount = 5,
                    errorCount = 0,
                    host = "agent-1",
                    timestampMs = 1700000000000L,
                )
            ),
        )

        MiscIngestionService.insertBatch(batch)

        assertEquals(1, capturedSql.size)
        assertTrue(capturedSql[0].contains("pipeline_stats"))
        assertTrue(capturedSql[0].contains("pipe-1"))
    }

    @Test
    fun `insertBatch routes data_lineage batch`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            capturedSql.add(firstArg())
            val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.OK
            response
        }

        val batch = QueuedMiscBatch(
            organizationId = 3,
            batchType = "data_lineage",
            dataLineage = listOf(
                QueuedDataLineageEntry(
                    runId = "run-1",
                    jobName = "etl-job",
                    namespace = "analytics",
                    inputs = listOf("table_a", "table_b"),
                    outputs = listOf("table_c"),
                    eventType = "complete",
                    facets = """{"duration":120}""",
                    timestampMs = 1700000000000L,
                )
            ),
        )

        MiscIngestionService.insertBatch(batch)

        assertEquals(1, capturedSql.size)
        assertTrue(capturedSql[0].contains("data_lineage"))
        assertTrue(capturedSql[0].contains("run-1"))
        assertTrue(capturedSql[0].contains("etl-job"))
    }

    @Test
    fun `insertBatch routes data_streams batch`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            capturedSql.add(firstArg())
            val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.OK
            response
        }

        val batch = QueuedMiscBatch(
            organizationId = 4,
            batchType = "data_streams",
            dataStreams = listOf(
                QueuedDataStreamEntry(
                    pipelineId = "stream-1",
                    stageName = "kafka-consumer",
                    latencyNs = 500000,
                    payloadSize = 1024,
                    direction = "in",
                    tags = mapOf("topic" to "orders"),
                    timestampMs = 1700000000000L,
                ),
                QueuedDataStreamEntry(
                    pipelineId = "stream-1",
                    stageName = "kafka-producer",
                    latencyNs = 200000,
                    payloadSize = 2048,
                    direction = "out",
                    tags = mapOf("topic" to "results"),
                    timestampMs = 1700000000000L,
                ),
            ),
        )

        MiscIngestionService.insertBatch(batch)

        assertEquals(1, capturedSql.size)
        assertTrue(capturedSql[0].contains("data_streams"))
        assertTrue(capturedSql[0].contains("'in'"))
        assertTrue(capturedSql[0].contains("'out'"))
    }

    @Test
    fun `insertBatch routes synthetics batch with type normalization`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            capturedSql.add(firstArg())
            val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.OK
            response
        }

        val batch = QueuedMiscBatch(
            organizationId = 5,
            batchType = "synthetics",
            synthetics = listOf(
                QueuedSyntheticEntry(
                    testId = "test-1",
                    testName = "Homepage",
                    testType = "browser",
                    status = "passed",
                    probeDc = "us-east-1",
                    durationMs = 2500,
                    errorMessage = "",
                    timings = mapOf("dns" to 10.0, "tcp" to 20.0),
                    tags = mapOf("env" to "prod"),
                    timestampMs = 1700000000000L,
                ),
                QueuedSyntheticEntry(
                    testId = "test-2",
                    testName = "API Check",
                    testType = "api",
                    status = "failed",
                    probeDc = "eu-west-1",
                    durationMs = 5000,
                    errorMessage = "Timeout",
                    timings = emptyMap(),
                    tags = emptyMap(),
                    timestampMs = 1700000000000L,
                ),
                QueuedSyntheticEntry(
                    testId = "test-3",
                    testName = "Multi",
                    testType = "multistep",
                    status = "skipped",
                    probeDc = "ap-1",
                    durationMs = 0,
                    errorMessage = "",
                    timings = emptyMap(),
                    tags = emptyMap(),
                    timestampMs = 1700000000000L,
                ),
                QueuedSyntheticEntry(
                    testId = "test-4",
                    testName = "Unknown",
                    testType = "unknown_type",
                    status = "unknown_status",
                    probeDc = "",
                    durationMs = 100,
                    errorMessage = "",
                    timings = emptyMap(),
                    tags = emptyMap(),
                    timestampMs = 1700000000000L,
                ),
            ),
        )

        MiscIngestionService.insertBatch(batch)

        val sql = capturedSql[0]
        assertTrue(sql.contains("synthetic_results"))
        assertTrue(sql.contains("'browser'"))
        assertTrue(sql.contains("'failed'"))
        assertTrue(sql.contains("'multistep'"))
        assertTrue(sql.contains("'skipped'"))
        // Unknown types default to 'api' and 'passed'
        assertTrue(sql.contains("'api'"))
        assertTrue(sql.contains("'passed'"))
    }

    @Test
    fun `insertBatch routes container_images batch`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            capturedSql.add(firstArg())
            val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.OK
            response
        }

        val batch = QueuedMiscBatch(
            organizationId = 6,
            batchType = "container_images",
            containerImages = listOf(
                QueuedContainerImageEntry(
                    imageName = "nginx",
                    imageTag = "1.25",
                    digest = "sha256:abc123",
                    registry = "docker.io",
                    sizeBytes = 50_000_000,
                    os = "linux",
                    architecture = "amd64",
                    layers = 5,
                    tags = mapOf("team" to "infra"),
                    timestampMs = 1700000000000L,
                )
            ),
        )

        MiscIngestionService.insertBatch(batch)

        assertTrue(capturedSql[0].contains("container_images"))
        assertTrue(capturedSql[0].contains("nginx"))
        assertTrue(capturedSql[0].contains("sha256:abc123"))
    }

    @Test
    fun `insertBatch routes sbom_packages batch`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            capturedSql.add(firstArg())
            val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.OK
            response
        }

        val batch = QueuedMiscBatch(
            organizationId = 7,
            batchType = "sbom_packages",
            sbomPackages = listOf(
                QueuedSbomEntry(
                    host = "web-01",
                    containerId = "abc123",
                    imageName = "myapp:latest",
                    packageName = "log4j",
                    packageVersion = "2.14.0",
                    packageType = "maven",
                    cveIds = listOf("CVE-2021-44228", "CVE-2021-45046"),
                    tags = mapOf("severity" to "critical"),
                    timestampMs = 1700000000000L,
                )
            ),
        )

        MiscIngestionService.insertBatch(batch)

        assertTrue(capturedSql[0].contains("sbom_packages"))
        assertTrue(capturedSql[0].contains("log4j"))
        assertTrue(capturedSql[0].contains("CVE-2021-44228"))
    }

    // ──── insertBatch empty batch skipping ────

    @Test
    fun `insertBatch skips symbol_db with empty entries`() = runBlocking {
        mockClickHouseSuccess()

        MiscIngestionService.insertBatch(
            QueuedMiscBatch(organizationId = 1, batchType = "symbol_db")
        )
        // No exception means it was skipped
    }

    @Test
    fun `insertBatch skips pipeline_stats with empty entries`() = runBlocking {
        mockClickHouseSuccess()

        MiscIngestionService.insertBatch(
            QueuedMiscBatch(organizationId = 1, batchType = "pipeline_stats")
        )
    }

    @Test
    fun `insertBatch skips data_lineage with empty entries`() = runBlocking {
        mockClickHouseSuccess()

        MiscIngestionService.insertBatch(
            QueuedMiscBatch(organizationId = 1, batchType = "data_lineage")
        )
    }

    @Test
    fun `insertBatch skips data_streams with empty entries`() = runBlocking {
        mockClickHouseSuccess()

        MiscIngestionService.insertBatch(
            QueuedMiscBatch(organizationId = 1, batchType = "data_streams")
        )
    }

    @Test
    fun `insertBatch skips synthetics with empty entries`() = runBlocking {
        mockClickHouseSuccess()

        MiscIngestionService.insertBatch(
            QueuedMiscBatch(organizationId = 1, batchType = "synthetics")
        )
    }

    @Test
    fun `insertBatch skips container_images with empty entries`() = runBlocking {
        mockClickHouseSuccess()

        MiscIngestionService.insertBatch(
            QueuedMiscBatch(organizationId = 1, batchType = "container_images")
        )
    }

    @Test
    fun `insertBatch skips sbom_packages with empty entries`() = runBlocking {
        mockClickHouseSuccess()

        MiscIngestionService.insertBatch(
            QueuedMiscBatch(organizationId = 1, batchType = "sbom_packages")
        )
    }

    @Test
    fun `insertBatch ignores unknown batch type`() = runBlocking {
        mockClickHouseSuccess()

        MiscIngestionService.insertBatch(
            QueuedMiscBatch(organizationId = 1, batchType = "unknown_type")
        )
        // No exception
    }

    // ──── insertBatch ClickHouse failure ────

    @Test
    fun `insertBatch throws when ClickHouse returns error`() = runBlocking {
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.InternalServerError
            response
        }

        assertFailsWith<IllegalStateException> {
            MiscIngestionService.insertBatch(
                QueuedMiscBatch(
                    organizationId = 1,
                    batchType = "symbol_db",
                    symbolDb = listOf(
                        QueuedSymbolDbEntry(
                            service = "test",
                            env = "test",
                            language = "java",
                            version = "1",
                            symbols = "sym",
                            timestampMs = 0L
                        )
                    )
                )
            )
        }
    }

    // ──── insertBatch with multiple entries ────

    @Test
    fun `insertBatch with multiple pipeline_stats entries`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            capturedSql.add(firstArg())
            val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.OK
            response
        }

        val batch = QueuedMiscBatch(
            organizationId = 1,
            batchType = "pipeline_stats",
            pipelineStats = listOf(
                QueuedPipelineStatEntry(
                    pipelineId = "p1",
                    stageName = "s1",
                    inCount = 10,
                    outCount = 9,
                    dropCount = 1,
                    errorCount = 0,
                    host = "h1",
                    timestampMs = 1700000000000L
                ),
                QueuedPipelineStatEntry(
                    pipelineId = "p1",
                    stageName = "s2",
                    inCount = 9,
                    outCount = 8,
                    dropCount = 1,
                    errorCount = 0,
                    host = "h1",
                    timestampMs = 1700000000000L
                ),
            ),
        )

        MiscIngestionService.insertBatch(batch)

        assertEquals(1, capturedSql.size)
        assertTrue(capturedSql[0].contains("s1"))
        assertTrue(capturedSql[0].contains("s2"))
    }

    @Test
    fun `insertBatch with multiple sbom entries including CVEs`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            capturedSql.add(firstArg())
            val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.OK
            response
        }

        val batch = QueuedMiscBatch(
            organizationId = 1,
            batchType = "sbom_packages",
            sbomPackages = listOf(
                QueuedSbomEntry(
                    host = "host-1", containerId = "c1", imageName = "app:v1",
                    packageName = "openssl", packageVersion = "1.1.1",
                    packageType = "deb", cveIds = listOf("CVE-2023-0001"),
                    tags = mapOf("env" to "prod"), timestampMs = 1700000000000L
                ),
                QueuedSbomEntry(
                    host = "host-1", containerId = "c1", imageName = "app:v1",
                    packageName = "libcurl", packageVersion = "7.88",
                    packageType = "deb", cveIds = emptyList(),
                    tags = emptyMap(), timestampMs = 1700000000000L
                ),
            ),
        )

        MiscIngestionService.insertBatch(batch)

        assertTrue(capturedSql[0].contains("openssl"))
        assertTrue(capturedSql[0].contains("libcurl"))
        assertTrue(capturedSql[0].contains("CVE-2023-0001"))
    }

    // ──── parseDdTagList edge cases ────

    @Test
    fun `parseDdTagList handles multiple colons in value`() {
        val result = MiscIngestionService.parseDdTagList(
            listOf("url:http://host:8080/path:extra")
        )
        assertEquals("http://host:8080/path:extra", result["url"])
    }

    @Test
    fun `parseDdTagList handles tag with only colon`() {
        val result = MiscIngestionService.parseDdTagList(listOf(":value"))
        // colonIdx = 0, so it's not > 0, meaning :value is treated as a key-less tag
        assertEquals("", result[":value"])
    }

    @Test
    fun `parseDdTagList handles mixed tags`() {
        val result = MiscIngestionService.parseDdTagList(
            listOf("env:prod", "standalone", "service:api", "version:1.2.3")
        )
        assertEquals("prod", result["env"])
        assertEquals("", result["standalone"])
        assertEquals("api", result["service"])
        assertEquals("1.2.3", result["version"])
    }

    // ──── decodeBatch ────

    @Test
    fun `decodeBatch with all fields populated`() {
        val batch = QueuedMiscBatch(
            organizationId = 42,
            batchType = "container_images",
            containerImages = listOf(
                QueuedContainerImageEntry(
                    imageName = "redis",
                    imageTag = "7.2",
                    digest = "sha256:def456",
                    registry = "gcr.io",
                    sizeBytes = 30_000_000,
                    os = "linux",
                    architecture = "arm64",
                    layers = 3,
                    tags = mapOf("env" to "staging"),
                    timestampMs = 1700000000000L,
                )
            ),
        )

        val encoded = json.encodeToString(batch)
        val decoded = MiscIngestionService.decodeBatch(encoded)

        assertEquals(42, decoded.organizationId)
        assertEquals("container_images", decoded.batchType)
        assertEquals(1, decoded.containerImages.size)
        assertEquals("redis", decoded.containerImages[0].imageName)
        assertEquals("arm64", decoded.containerImages[0].architecture)
        assertEquals(3, decoded.containerImages[0].layers)
    }

    @Test
    fun `decodeBatch with empty lists defaults`() {
        val encoded = """{"organization_id":1,"batch_type":"symbol_db"}"""
        val decoded = MiscIngestionService.decodeBatch(encoded)

        assertEquals(1, decoded.organizationId)
        assertEquals("symbol_db", decoded.batchType)
        assertTrue(decoded.symbolDb.isEmpty())
        assertTrue(decoded.pipelineStats.isEmpty())
        assertTrue(decoded.dataLineage.isEmpty())
        assertTrue(decoded.dataStreams.isEmpty())
        assertTrue(decoded.synthetics.isEmpty())
        assertTrue(decoded.containerImages.isEmpty())
        assertTrue(decoded.sbomPackages.isEmpty())
    }

    // ──── data_streams direction normalization ────

    @Test
    fun `data_streams normalizes unknown direction to in`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            capturedSql.add(firstArg())
            val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.OK
            response
        }

        val batch = QueuedMiscBatch(
            organizationId = 1,
            batchType = "data_streams",
            dataStreams = listOf(
                QueuedDataStreamEntry(
                    pipelineId = "s1",
                    stageName = "stage",
                    latencyNs = 100,
                    payloadSize = 50,
                    direction = "unknown_direction",
                    tags = emptyMap(),
                    timestampMs = 1700000000000L
                )
            ),
        )

        MiscIngestionService.insertBatch(batch)

        assertTrue(capturedSql[0].contains("'in'"))
    }

    // ──── synthetics timing map rendering ────

    @Test
    fun `synthetics with empty timings uses map()`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            capturedSql.add(firstArg())
            val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.OK
            response
        }

        val batch = QueuedMiscBatch(
            organizationId = 1,
            batchType = "synthetics",
            synthetics = listOf(
                QueuedSyntheticEntry(
                    testId = "t1", testName = "Test",
                    testType = "api", status = "passed",
                    probeDc = "dc1", durationMs = 100,
                    errorMessage = "", timings = emptyMap(),
                    tags = emptyMap(), timestampMs = 1700000000000L
                )
            ),
        )

        MiscIngestionService.insertBatch(batch)

        assertTrue(capturedSql[0].contains("map()"))
    }

    @Test
    fun `synthetics with populated timings renders map correctly`() = runBlocking {
        val capturedSql = mutableListOf<String>()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            capturedSql.add(firstArg())
            val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
            every { response.status } returns io.ktor.http.HttpStatusCode.OK
            response
        }

        val batch = QueuedMiscBatch(
            organizationId = 1,
            batchType = "synthetics",
            synthetics = listOf(
                QueuedSyntheticEntry(
                    testId = "t1", testName = "Test",
                    testType = "browser", status = "passed",
                    probeDc = "dc1", durationMs = 2000,
                    errorMessage = "",
                    timings = mapOf("dns" to 10.0, "tls" to 50.0, "firstByte" to 150.0),
                    tags = emptyMap(), timestampMs = 1700000000000L
                )
            ),
        )

        MiscIngestionService.insertBatch(batch)

        val sql = capturedSql[0]
        assertTrue(sql.contains("'dns'"))
        assertTrue(sql.contains("'tls'"))
        assertTrue(sql.contains("'firstByte'"))
    }
}
