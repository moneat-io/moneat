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

import com.moneat.datadog.models.CreateDebuggerProbeRequest
import com.moneat.datadog.models.DebuggerProbes
import com.moneat.datadog.models.UpdateDebuggerProbeRequest
import com.moneat.datadog.services.DebuggerProbeService
import com.moneat.datadog.services.MiscIngestionService
import com.moneat.datadog.services.ProfileStorageService
import com.moneat.datadog.services.QueuedContainerImageEntry
import com.moneat.datadog.services.QueuedDataLineageEntry
import com.moneat.datadog.services.QueuedDataStreamEntry
import com.moneat.datadog.services.QueuedMiscBatch
import com.moneat.datadog.services.QueuedPipelineStatEntry
import com.moneat.datadog.services.QueuedSbomEntry
import com.moneat.datadog.services.QueuedSymbolDbEntry
import com.moneat.datadog.services.QueuedSyntheticEntry
import com.moneat.datadog.services.TelemetryProxyService
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatadogServicesExtendedTest {

    // ================================================================
    //  MiscIngestionService – pure-function tests (no Redis/ClickHouse)
    // ================================================================

    @Nested
    inner class MiscIngestionParseDdTagListTest {

        @Test
        fun `parseDdTagList parses key-value pairs`() {
            val result = MiscIngestionService.parseDdTagList(
                listOf("env:production", "team:backend", "region:us-east")
            )
            assertEquals("production", result["env"])
            assertEquals("backend", result["team"])
            assertEquals("us-east", result["region"])
        }

        @Test
        fun `parseDdTagList handles tags without values`() {
            val result = MiscIngestionService.parseDdTagList(
                listOf("standalone-tag", "env:prod")
            )
            assertEquals("", result["standalone-tag"])
            assertEquals("prod", result["env"])
        }

        @Test
        fun `parseDdTagList handles empty list`() {
            assertTrue(MiscIngestionService.parseDdTagList(emptyList()).isEmpty())
        }

        @Test
        fun `parseDdTagList handles tags with colons in value`() {
            val result = MiscIngestionService.parseDdTagList(
                listOf("url:http://example.com:8080/path")
            )
            assertEquals("http://example.com:8080/path", result["url"])
        }

        @Test
        fun `parseDdTagList ignores empty strings`() {
            val result = MiscIngestionService.parseDdTagList(listOf("", "env:prod"))
            assertEquals(1, result.size)
            assertEquals("prod", result["env"])
        }
    }

    @Nested
    inner class MiscIngestionDecodeBatchTest {

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        @Test
        fun `decodeBatch round-trips symbol_db batch`() {
            val batch = QueuedMiscBatch(
                organizationId = 1,
                batchType = "symbol_db",
                symbolDb = listOf(
                    QueuedSymbolDbEntry(
                        service = "api",
                        env = "prod",
                        language = "java",
                        version = "1.0",
                        symbols = "com.example.Main",
                        timestampMs = 1700000000000L,
                    )
                ),
            )

            val encoded = json.encodeToString(batch)
            val decoded = MiscIngestionService.decodeBatch(encoded)

            assertEquals("symbol_db", decoded.batchType)
            assertEquals(1, decoded.organizationId)
            assertEquals(1, decoded.symbolDb.size)
            assertEquals("api", decoded.symbolDb[0].service)
            assertEquals("java", decoded.symbolDb[0].language)
        }

        @Test
        fun `decodeBatch round-trips pipeline_stats batch`() {
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

            val encoded = json.encodeToString(batch)
            val decoded = MiscIngestionService.decodeBatch(encoded)

            assertEquals("pipeline_stats", decoded.batchType)
            assertEquals(1, decoded.pipelineStats.size)
            assertEquals("pipe-1", decoded.pipelineStats[0].pipelineId)
            assertEquals(100L, decoded.pipelineStats[0].inCount)
            assertEquals(95L, decoded.pipelineStats[0].outCount)
        }

        @Test
        fun `decodeBatch round-trips data_lineage batch`() {
            val batch = QueuedMiscBatch(
                organizationId = 3,
                batchType = "data_lineage",
                dataLineage = listOf(
                    QueuedDataLineageEntry(
                        runId = "run-abc",
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

            val encoded = json.encodeToString(batch)
            val decoded = MiscIngestionService.decodeBatch(encoded)

            assertEquals("data_lineage", decoded.batchType)
            assertEquals(1, decoded.dataLineage.size)
            assertEquals("run-abc", decoded.dataLineage[0].runId)
            assertEquals(listOf("table_a", "table_b"), decoded.dataLineage[0].inputs)
            assertEquals(listOf("table_c"), decoded.dataLineage[0].outputs)
        }

        @Test
        fun `decodeBatch round-trips data_streams batch`() {
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
                    )
                ),
            )

            val encoded = json.encodeToString(batch)
            val decoded = MiscIngestionService.decodeBatch(encoded)

            assertEquals("data_streams", decoded.batchType)
            assertEquals(1, decoded.dataStreams.size)
            assertEquals("stream-1", decoded.dataStreams[0].pipelineId)
            assertEquals(500000L, decoded.dataStreams[0].latencyNs)
            assertEquals("orders", decoded.dataStreams[0].tags["topic"])
        }

        @Test
        fun `decodeBatch round-trips synthetics batch`() {
            val batch = QueuedMiscBatch(
                organizationId = 5,
                batchType = "synthetics",
                synthetics = listOf(
                    QueuedSyntheticEntry(
                        testId = "test-1",
                        testName = "Homepage Check",
                        testType = "browser",
                        status = "passed",
                        probeDc = "us-east-1",
                        durationMs = 2500,
                        errorMessage = "",
                        timings = mapOf("dns" to 10.0, "tcp" to 20.0),
                        tags = mapOf("env" to "prod"),
                        timestampMs = 1700000000000L,
                    )
                ),
            )

            val encoded = json.encodeToString(batch)
            val decoded = MiscIngestionService.decodeBatch(encoded)

            assertEquals("synthetics", decoded.batchType)
            assertEquals(1, decoded.synthetics.size)
            assertEquals("test-1", decoded.synthetics[0].testId)
            assertEquals("browser", decoded.synthetics[0].testType)
            assertEquals(2500L, decoded.synthetics[0].durationMs)
            assertEquals(10.0, decoded.synthetics[0].timings["dns"])
        }

        @Test
        fun `decodeBatch round-trips container_images batch`() {
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

            val encoded = json.encodeToString(batch)
            val decoded = MiscIngestionService.decodeBatch(encoded)

            assertEquals("container_images", decoded.batchType)
            assertEquals(1, decoded.containerImages.size)
            assertEquals("nginx", decoded.containerImages[0].imageName)
            assertEquals("sha256:abc123", decoded.containerImages[0].digest)
            assertEquals(5, decoded.containerImages[0].layers)
        }

        @Test
        fun `decodeBatch round-trips sbom_packages batch`() {
            val batch = QueuedMiscBatch(
                organizationId = 7,
                batchType = "sbom_packages",
                sbomPackages = listOf(
                    QueuedSbomEntry(
                        host = "node-1",
                        containerId = "abc123",
                        imageName = "myapp:latest",
                        packageName = "openssl",
                        packageVersion = "1.1.1",
                        packageType = "deb",
                        cveIds = listOf("CVE-2023-0001", "CVE-2023-0002"),
                        tags = mapOf("env" to "prod"),
                        timestampMs = 1700000000000L,
                    )
                ),
            )

            val encoded = json.encodeToString(batch)
            val decoded = MiscIngestionService.decodeBatch(encoded)

            assertEquals("sbom_packages", decoded.batchType)
            assertEquals(1, decoded.sbomPackages.size)
            assertEquals("openssl", decoded.sbomPackages[0].packageName)
            assertEquals(
                listOf("CVE-2023-0001", "CVE-2023-0002"),
                decoded.sbomPackages[0].cveIds
            )
        }

        @Test
        fun `decodeBatch handles empty sub-lists`() {
            val batch = QueuedMiscBatch(
                organizationId = 1,
                batchType = "symbol_db",
            )

            val encoded = json.encodeToString(batch)
            val decoded = MiscIngestionService.decodeBatch(encoded)

            assertTrue(decoded.symbolDb.isEmpty())
            assertTrue(decoded.pipelineStats.isEmpty())
            assertTrue(decoded.dataLineage.isEmpty())
            assertTrue(decoded.dataStreams.isEmpty())
            assertTrue(decoded.synthetics.isEmpty())
            assertTrue(decoded.containerImages.isEmpty())
            assertTrue(decoded.sbomPackages.isEmpty())
        }
    }

    // ================================================================
    //  DebuggerProbeService – H2-backed CRUD tests
    // ================================================================

    @Nested
    inner class DebuggerProbeServiceTest {

        @BeforeEach
        fun setup() {
            Database.connect(
                "jdbc:h2:mem:test_debugger_probes_${UUID.randomUUID()}" +
                    ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                driver = "org.h2.Driver"
            )
            TestDatabaseHelper.resetSchema(Users, Organizations, DebuggerProbes)
            seedParentRows()
        }

        private fun seedParentRows() {
            transaction {
                Users.insert {
                    it[id] = 1
                    it[email] = "test@example.com"
                    it[password_hash] = "hash"
                    it[name] = "Test User"
                }
                Organizations.insert {
                    it[id] = 1
                    it[name] = "Test Org"
                    it[slug] = "test-org"
                }
            }
        }

        @Test
        fun `createProbe and listProbes returns created probe`() {
            val request = CreateDebuggerProbeRequest(
                probeType = "log_probe",
                service = "api-server",
                environment = "production",
                language = "java",
                whereType = "method",
                typeName = "com.example.UserService",
                methodName = "getUser",
            )

            val created = DebuggerProbeService.createProbe(1, null, request)

            assertEquals("log_probe", created.probeType)
            assertEquals("api-server", created.service)
            assertEquals("production", created.environment)
            assertEquals("java", created.language)
            assertTrue(created.active)
            assertEquals("method", created.whereType)
            assertEquals("com.example.UserService", created.typeName)
            assertEquals("getUser", created.methodName)

            val probes = DebuggerProbeService.listProbes(listOf(1))
            assertEquals(1, probes.size)
            assertEquals(created.id, probes[0].id)
        }

        @Test
        fun `createProbe with line probe type`() {
            val request = CreateDebuggerProbeRequest(
                probeType = "snapshot",
                service = "worker",
                language = "python",
                whereType = "line",
                sourceFile = "app/handler.py",
                sourceLines = "42-45",
            )

            val created = DebuggerProbeService.createProbe(1, null, request)

            assertEquals("snapshot", created.probeType)
            assertEquals("line", created.whereType)
            assertEquals("app/handler.py", created.sourceFile)
            assertEquals("42-45", created.sourceLines)
            assertNull(created.typeName)
            assertNull(created.methodName)
        }

        @Test
        fun `createProbe normalizes language node-js to nodejs`() {
            val request = CreateDebuggerProbeRequest(
                service = "frontend",
                language = "node.js",
                whereType = "method",
                typeName = "Handler",
                methodName = "process",
            )

            val created = DebuggerProbeService.createProbe(1, null, request)
            assertEquals("nodejs", created.language)
        }

        @Test
        fun `createProbe normalizes language dotnet`() {
            val request = CreateDebuggerProbeRequest(
                service = "dotnet-svc",
                language = ".net",
                whereType = "method",
                typeName = "Controller",
                methodName = "Index",
            )

            val created = DebuggerProbeService.createProbe(1, null, request)
            assertEquals("dotnet", created.language)
        }

        @Test
        fun `createProbe rejects unsupported probeType`() {
            val request = CreateDebuggerProbeRequest(
                probeType = "invalid_type",
                service = "svc",
                language = "java",
                whereType = "method",
                typeName = "Foo",
                methodName = "bar",
            )

            assertFailsWith<IllegalArgumentException> {
                DebuggerProbeService.createProbe(1, null, request)
            }
        }

        @Test
        fun `createProbe rejects unsupported whereType`() {
            val request = CreateDebuggerProbeRequest(
                service = "svc",
                language = "java",
                whereType = "class",
                typeName = "Foo",
                methodName = "bar",
            )

            assertFailsWith<IllegalArgumentException> {
                DebuggerProbeService.createProbe(1, null, request)
            }
        }

        @Test
        fun `createProbe rejects empty service`() {
            val request = CreateDebuggerProbeRequest(
                service = "  ",
                language = "java",
                whereType = "method",
                typeName = "Foo",
                methodName = "bar",
            )

            assertFailsWith<IllegalArgumentException> {
                DebuggerProbeService.createProbe(1, null, request)
            }
        }

        @Test
        fun `createProbe requires typeName for method probe`() {
            val request = CreateDebuggerProbeRequest(
                service = "svc",
                language = "java",
                whereType = "method",
                methodName = "bar",
            )

            assertFailsWith<IllegalArgumentException> {
                DebuggerProbeService.createProbe(1, null, request)
            }
        }

        @Test
        fun `createProbe requires sourceFile for line probe`() {
            val request = CreateDebuggerProbeRequest(
                service = "svc",
                language = "java",
                whereType = "line",
                sourceLines = "10",
            )

            assertFailsWith<IllegalArgumentException> {
                DebuggerProbeService.createProbe(1, null, request)
            }
        }

        @Test
        fun `updateProbe updates fields`() {
            val created = DebuggerProbeService.createProbe(
                1,
                null,
                CreateDebuggerProbeRequest(
                    service = "api",
                    language = "java",
                    whereType = "method",
                    typeName = "Foo",
                    methodName = "bar",
                )
            )

            val updated = DebuggerProbeService.updateProbe(
                UUID.fromString(created.id),
                listOf(1),
                UpdateDebuggerProbeRequest(
                    active = false,
                    service = "api-v2",
                    template = "userId={userId}",
                )
            )

            assertNotNull(updated)
            assertFalse(updated.active)
            assertEquals("api-v2", updated.service)
            assertEquals("userId={userId}", updated.template)
            assertEquals("method", updated.whereType)
            assertEquals("Foo", updated.typeName)
        }

        @Test
        fun `updateProbe returns null for wrong organization`() {
            val created = DebuggerProbeService.createProbe(
                1,
                null,
                CreateDebuggerProbeRequest(
                    service = "api",
                    language = "java",
                    whereType = "method",
                    typeName = "Foo",
                    methodName = "bar",
                )
            )

            val result = DebuggerProbeService.updateProbe(
                UUID.fromString(created.id),
                listOf(999),
                UpdateDebuggerProbeRequest(active = false),
            )

            assertNull(result)
        }

        @Test
        fun `updateProbe returns null for empty organizationIds`() {
            val result = DebuggerProbeService.updateProbe(
                UUID.randomUUID(),
                emptyList(),
                UpdateDebuggerProbeRequest(active = false),
            )
            assertNull(result)
        }

        @Test
        fun `deleteProbe removes probe`() {
            val created = DebuggerProbeService.createProbe(
                1,
                null,
                CreateDebuggerProbeRequest(
                    service = "api",
                    language = "java",
                    whereType = "method",
                    typeName = "Foo",
                    methodName = "bar",
                )
            )

            val deleted = DebuggerProbeService.deleteProbe(
                UUID.fromString(created.id),
                listOf(1)
            )
            assertTrue(deleted)

            val probes = DebuggerProbeService.listProbes(listOf(1))
            assertTrue(probes.isEmpty())
        }

        @Test
        fun `deleteProbe returns false for wrong organization`() {
            val created = DebuggerProbeService.createProbe(
                1,
                null,
                CreateDebuggerProbeRequest(
                    service = "api",
                    language = "java",
                    whereType = "method",
                    typeName = "Foo",
                    methodName = "bar",
                )
            )

            assertFalse(
                DebuggerProbeService.deleteProbe(
                    UUID.fromString(created.id),
                    listOf(999)
                )
            )
        }

        @Test
        fun `deleteProbe returns false for empty organizationIds`() {
            assertFalse(
                DebuggerProbeService.deleteProbe(UUID.randomUUID(), emptyList())
            )
        }

        @Test
        fun `listProbes returns empty for empty organizationIds`() {
            assertTrue(DebuggerProbeService.listProbes(emptyList()).isEmpty())
        }

        @Test
        fun `listAgentProbes filters by service and environment`() {
            DebuggerProbeService.createProbe(
                1,
                null,
                CreateDebuggerProbeRequest(
                    service = "api",
                    environment = "production",
                    language = "java",
                    whereType = "method",
                    typeName = "Foo",
                    methodName = "bar",
                )
            )
            DebuggerProbeService.createProbe(
                1,
                null,
                CreateDebuggerProbeRequest(
                    service = "worker",
                    environment = "staging",
                    language = "java",
                    whereType = "method",
                    typeName = "Baz",
                    methodName = "run",
                )
            )

            val apiProd = DebuggerProbeService.listAgentProbes(1, "api", "production")
            assertEquals(1, apiProd.size)
            assertEquals("api", apiProd[0].service)

            val workerStaging = DebuggerProbeService.listAgentProbes(
                1,
                "worker",
                "staging"
            )
            assertEquals(1, workerStaging.size)
            assertEquals("worker", workerStaging[0].service)
        }

        @Test
        fun `listAgentProbes wildcard environment matches any env`() {
            DebuggerProbeService.createProbe(
                1,
                null,
                CreateDebuggerProbeRequest(
                    service = "api",
                    environment = "*",
                    language = "java",
                    whereType = "method",
                    typeName = "Foo",
                    methodName = "bar",
                )
            )

            val result = DebuggerProbeService.listAgentProbes(1, "api", "production")
            assertEquals(1, result.size)
        }

        @Test
        fun `listAgentProbes returns only active probes`() {
            val created = DebuggerProbeService.createProbe(
                1,
                null,
                CreateDebuggerProbeRequest(
                    service = "api",
                    language = "java",
                    active = false,
                    whereType = "method",
                    typeName = "Foo",
                    methodName = "bar",
                )
            )

            val result = DebuggerProbeService.listAgentProbes(1, "api", null)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `createProbe with metric_probe type and metric fields`() {
            val request = CreateDebuggerProbeRequest(
                probeType = "metric_probe",
                service = "api",
                language = "java",
                whereType = "method",
                typeName = "Metrics",
                methodName = "record",
                metricName = "request.count",
                metricKind = "count",
            )

            val created = DebuggerProbeService.createProbe(1, null, request)

            assertEquals("metric_probe", created.probeType)
            assertEquals("request.count", created.metricName)
            assertEquals("count", created.metricKind)
        }

        @Test
        fun `createProbe rejects unsupported metricKind`() {
            val request = CreateDebuggerProbeRequest(
                probeType = "metric_probe",
                service = "api",
                language = "java",
                whereType = "method",
                typeName = "Metrics",
                methodName = "record",
                metricKind = "invalid_kind",
            )

            assertFailsWith<IllegalArgumentException> {
                DebuggerProbeService.createProbe(1, null, request)
            }
        }

        @Test
        fun `createProbe with span_decoration type`() {
            val request = CreateDebuggerProbeRequest(
                probeType = "span_decoration",
                service = "api",
                language = "java",
                whereType = "method",
                typeName = "Handler",
                methodName = "handle",
                tags = "user.id={userId}",
            )

            val created = DebuggerProbeService.createProbe(1, null, request)
            assertEquals("span_decoration", created.probeType)
            assertEquals("user.id={userId}", created.tags)
        }
    }

    // ================================================================
    //  ProfileStorageService – filesystem tests with temp directories
    // ================================================================

    @Nested
    inner class ProfileStorageServiceTest {

        private lateinit var storageDir: File

        @BeforeEach
        fun setup() {
            storageDir = File(
                System.getProperty("java.io.tmpdir"),
                "moneat-profile-test-${UUID.randomUUID()}"
            )
            storageDir.mkdirs()
            System.setProperty("PROFILE_STORAGE_PATH", storageDir.absolutePath)
        }

        @Test
        fun `store writes data and returns key`() {
            val data = "test profile data".toByteArray()

            val key = ProfileStorageService.store(1, data)

            assertTrue(key.startsWith("1/"))
            assertTrue(key.endsWith(".pprof.gz"))
        }

        @Test
        fun `read returns stored data`() {
            val data = "profile bytes".toByteArray()

            val key = ProfileStorageService.store(1, data)
            val read = ProfileStorageService.read(key)

            assertNotNull(read)
            assertTrue(read.isNotEmpty())
        }

        @Test
        fun `read returns null for missing key`() {
            assertNull(ProfileStorageService.read("nonexistent/key.pprof.gz"))
        }

        @Test
        fun `delete removes stored file`() {
            val key = ProfileStorageService.store(1, "data".toByteArray())

            assertTrue(ProfileStorageService.delete(key))
            assertNull(ProfileStorageService.read(key))
        }

        @Test
        fun `delete returns false for missing key`() {
            assertFalse(ProfileStorageService.delete("nonexistent/key.pprof.gz"))
        }

        @Test
        fun `storeMultiple stores several files under a prefix`() {
            val files = listOf(
                "cpu.pprof" to "cpu data".toByteArray(),
                "heap.pprof" to "heap data".toByteArray(),
            )

            val prefix = ProfileStorageService.storeMultiple(1, "profile-123", files)

            assertEquals("1/profile-123", prefix)
        }

        @Test
        fun `read from directory selects best file`() {
            val files = listOf(
                "delta.pprof" to "delta data".toByteArray(),
                "cpu.pprof" to "cpu data".toByteArray(),
            )

            val prefix = ProfileStorageService.storeMultiple(1, "profile-456", files)
            val read = ProfileStorageService.read(prefix)

            assertNotNull(read)
        }

        @Test
        fun `storeAdditional adds files to existing directory`() {
            val prefix = ProfileStorageService.storeMultiple(
                1,
                "profile-789",
                listOf("cpu.pprof" to "cpu".toByteArray())
            )

            ProfileStorageService.storeAdditional(
                prefix,
                listOf("heap.pprof" to "heap".toByteArray())
            )

            val read = ProfileStorageService.read(prefix)
            assertNotNull(read)
        }

        @Test
        fun `delete removes directory recursively`() {
            val prefix = ProfileStorageService.storeMultiple(
                1,
                "to-delete",
                listOf(
                    "a.pprof" to "a".toByteArray(),
                    "b.pprof" to "b".toByteArray(),
                )
            )

            assertTrue(ProfileStorageService.delete(prefix))
            assertNull(ProfileStorageService.read(prefix))
        }
    }

    // ================================================================
    //  TelemetryProxyService – basic coverage
    // ================================================================

    @Nested
    inner class TelemetryProxyServiceTest {

        @Test
        fun `acknowledge does not throw`() {
            TelemetryProxyService.acknowledge(
                organizationId = 1,
                path = "/api/v2/apmtelemetry",
                bodySize = 1024,
            )
        }

        @Test
        fun `acknowledge handles zero body size`() {
            TelemetryProxyService.acknowledge(
                organizationId = 1,
                path = "/api/v2/apmtelemetry",
                bodySize = 0,
            )
        }

        @Test
        fun `acknowledge handles large body size`() {
            TelemetryProxyService.acknowledge(
                organizationId = 42,
                path = "/api/v2/apmtelemetry",
                bodySize = 10_000_000,
            )
        }
    }

    // ================================================================
    //  QueuedMiscBatch insertBatch dispatch – verify empty batches
    // ================================================================

    @Nested
    inner class MiscInsertBatchDispatchTest {

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        @Test
        fun `QueuedMiscBatch serialization preserves all batch types`() {
            val types = listOf(
                "symbol_db",
                "pipeline_stats",
                "data_lineage",
                "data_streams",
                "synthetics",
                "container_images",
                "sbom_packages"
            )

            types.forEach { batchType ->
                val batch = QueuedMiscBatch(organizationId = 1, batchType = batchType)
                val encoded = json.encodeToString(batch)
                val decoded = json.decodeFromString<QueuedMiscBatch>(encoded)
                assertEquals(batchType, decoded.batchType)
            }
        }

        @Test
        fun `QueuedSyntheticEntry defaults are correct`() {
            val entry = QueuedSyntheticEntry(timestampMs = 1700000000000L)
            assertEquals("", entry.testId)
            assertEquals("api", entry.testType)
            assertEquals("passed", entry.status)
            assertEquals(0L, entry.durationMs)
            assertTrue(entry.timings.isEmpty())
            assertTrue(entry.tags.isEmpty())
        }

        @Test
        fun `QueuedContainerImageEntry defaults are correct`() {
            val entry = QueuedContainerImageEntry(timestampMs = 1700000000000L)
            assertEquals("", entry.imageName)
            assertEquals("", entry.imageTag)
            assertEquals(0L, entry.sizeBytes)
            assertEquals(0, entry.layers)
        }

        @Test
        fun `QueuedSbomEntry defaults are correct`() {
            val entry = QueuedSbomEntry(timestampMs = 1700000000000L)
            assertEquals("", entry.host)
            assertEquals("", entry.packageName)
            assertTrue(entry.cveIds.isEmpty())
            assertTrue(entry.tags.isEmpty())
        }

        @Test
        fun `QueuedDataStreamEntry direction defaults to in`() {
            val entry = QueuedDataStreamEntry(timestampMs = 1700000000000L)
            assertEquals("in", entry.direction)
        }

        @Test
        fun `QueuedPipelineStatEntry counters default to zero`() {
            val entry = QueuedPipelineStatEntry(timestampMs = 1700000000000L)
            assertEquals(0L, entry.inCount)
            assertEquals(0L, entry.outCount)
            assertEquals(0L, entry.dropCount)
            assertEquals(0L, entry.errorCount)
        }
    }
}
