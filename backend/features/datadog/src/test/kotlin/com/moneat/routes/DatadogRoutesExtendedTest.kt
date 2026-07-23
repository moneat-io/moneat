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

package com.moneat.routes

import com.google.protobuf.CodedOutputStream
import com.moneat.billing.models.BillingUsageResponse
import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.QuotaReservationResult
import com.moneat.config.ClickHouseClient
import com.moneat.datadog.auth.DatadogAuthContext
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.decompression.ProcessAgentPayloadDecoder
import com.moneat.datadog.models.DatadogConnectionsPayload
import com.moneat.datadog.models.DatadogProcessPayload
import com.moneat.datadog.models.DdApiKeys
import com.moneat.datadog.models.DdApmErrorsResponse
import com.moneat.datadog.models.DdResourceStatsResponse
import com.moneat.datadog.models.DdServiceLatencyResponse
import com.moneat.datadog.models.DdServiceMapResponse
import com.moneat.datadog.models.DdStatsPayload
import com.moneat.datadog.models.DdTraceListResponse
import com.moneat.datadog.routes.datadogDogStatsDRoutes
import com.moneat.datadog.routes.datadogEventRoutes
import com.moneat.datadog.routes.datadogHostIngestRoutes
import com.moneat.datadog.routes.datadogHostQueryRoutes
import com.moneat.datadog.routes.datadogInfraRoutes
import com.moneat.datadog.routes.datadogLogRoutes
import com.moneat.datadog.routes.datadogMetricRoutes
import com.moneat.datadog.routes.datadogRoutes
import com.moneat.datadog.routes.datadogValidateRoutes
import com.moneat.datadog.routes.dbmIngestRoutes
import com.moneat.datadog.routes.debuggerIngestRoutes
import com.moneat.datadog.routes.miscIngestRoutes
import com.moneat.datadog.routes.orchestratorIngestRoutes
import com.moneat.datadog.routes.profileIngestRoutes
import com.moneat.datadog.routes.telemetryProxyRoutes
import com.moneat.datadog.routes.traceDashboardRoutes
import com.moneat.datadog.routes.traceIngestRoutes
import com.moneat.datadog.services.DatadogEventService
import com.moneat.datadog.services.DatadogHostService
import com.moneat.datadog.services.DatadogInfraService
import com.moneat.datadog.services.DatadogLogService
import com.moneat.datadog.services.DatadogMetricService
import com.moneat.datadog.services.DatadogService
import com.moneat.datadog.services.DbmIngestionService
import com.moneat.datadog.services.DdHostInfo
import com.moneat.datadog.services.DdResourceStatsQuery
import com.moneat.datadog.services.DdTraceListQuery
import com.moneat.datadog.services.DebuggerIngestionService
import com.moneat.datadog.services.MiscIngestionService
import com.moneat.datadog.services.OrchestratorIngestionService
import com.moneat.datadog.services.ProfileIngestionService
import com.moneat.datadog.services.QueuedConnectionEntry
import com.moneat.datadog.services.QueuedInfraBatch
import com.moneat.datadog.services.QueuedProcessEntry
import com.moneat.datadog.services.TelemetryProxyService
import com.moneat.datadog.services.TraceIngestionService
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueCapacityException
import com.moneat.plugins.installErrorHandling
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.forms.FormBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.ByteArrayOutputStream
import org.msgpack.core.MessageBufferPacker
import org.msgpack.core.MessagePack
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DatadogRoutesExtendedTest {

    private data class TraceAliasRequest(
        val method: HttpMethod,
        val path: String,
        val body: String,
    )

    companion object {
        private const val TEST_ORG_ID = 1
        private const val TEST_ORG_RESOURCE_ID = "00000000-0000-0000-0000-000000000001"
        private const val TEST_API_KEY = "TEST_API_KEY_PLACEHOLDER"
        private const val DD_API_KEY_HEADER = "DD-API-KEY"
        private const val DD_METADATA_PATH = "/dd/api/v1/metadata"
        private const val TEST_HOST = "test-host"
        private const val HOST_RESOURCE_ID = "11111111-1111-1111-1111-111111111111"
        private const val MISSING_HOST_RESOURCE_ID = "22222222-2222-2222-2222-222222222222"
        private const val DD_LOGS_PATH = "/dd/api/v2/logs"
        private const val EMPTY_ROWS_JSON = """{"rows":[]}"""
        private const val AGENT_API_KEYS_PATH = "/v1/agent-api-keys"
        private const val PROCESS_AGENT_HEADER_SIZE = 16
        private const val PROCESS_AGENT_MESSAGE_V3: Byte = 3
        private const val PROCESS_AGENT_PROTOBUF_ENCODING: Byte = 0
        private var db: Database? = null

        @JvmStatic
        @BeforeAll
        fun installObjectMocks() {
            mockkObject(
                DatadogAuthMiddleware,
                DatadogHostService,
                DatadogMetricService,
                DatadogEventService,
                DatadogLogService,
                DatadogInfraService,
                MiscIngestionService,
                DbmIngestionService,
                OrchestratorIngestionService,
                DebuggerIngestionService,
                TelemetryProxyService,
                DatadogService,
                TraceIngestionService,
                ProfileIngestionService,
                ClickHouseClient,
            )
        }

        @JvmStatic
        @AfterAll
        fun removeObjectMocks() {
            unmockkAll()
        }
    }

    private val allowingQuotaService = mockk<BillingQuotaService> {
        every { isEnforcementEnabled() } returns false
    }

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_dd_routes_ext;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            DdApiKeys
        )
        DatadogAuthMiddleware.clearCache()

        clearMocks(
            DatadogAuthMiddleware,
            DatadogHostService,
            DatadogMetricService,
            DatadogEventService,
            DatadogLogService,
            DatadogInfraService,
            MiscIngestionService,
            DbmIngestionService,
            OrchestratorIngestionService,
            DebuggerIngestionService,
            TelemetryProxyService,
            DatadogService,
            TraceIngestionService,
            ProfileIngestionService,
            ClickHouseClient,
        )

        coEvery {
            DatadogAuthMiddleware.authenticate(any())
        } returns TEST_ORG_ID
        coEvery {
            DatadogAuthMiddleware.authenticateContext(any())
        } returns DatadogAuthContext(TEST_ORG_ID, null)

        // Default stubs for ingest services
        every { DatadogHostService.upsertFromMetadata(any(), any()) } just Runs
        every { DatadogHostService.upsertFromIntake(any(), any()) } just Runs
        every { DatadogHostService.touchHostLastSeen(any(), any()) } just Runs
        coEvery { DatadogMetricService.enqueueMetrics(any(), any(), any()) } returns 1
        coEvery { DatadogMetricService.enqueueSketches(any(), any(), any()) } returns 1
        coEvery { DatadogEventService.enqueueEvents(any(), any()) } returns 1
        coEvery { DatadogEventService.enqueueServiceChecks(any(), any()) } returns 1
        coEvery { DatadogLogService.enqueueLogs(any(), any()) } returns 1
        every { MiscIngestionService.enqueueSymbolDb(any(), any()) } just Runs
        every { MiscIngestionService.enqueuePipelineStats(any(), any()) } returns 1
        every { MiscIngestionService.enqueueDataLineage(any(), any()) } just Runs
        every { MiscIngestionService.enqueueDataStreams(any(), any()) } returns 1
        every { MiscIngestionService.enqueueSynthetics(any(), any()) } returns 1
        every { MiscIngestionService.enqueueContainerImage(any(), any()) } just Runs
        every { MiscIngestionService.enqueueSbom(any(), any()) } returns 1
        every { DbmIngestionService.enqueueQueries(any(), any()) } returns 1
        every { DbmIngestionService.enqueueQueryPayloads(any(), any()) } returns 1
        every { DbmIngestionService.enqueueMetrics(any(), any()) } returns 1
        every { DbmIngestionService.enqueueMetricPayloads(any(), any()) } returns 1
        every { DbmIngestionService.enqueueActivity(any(), any()) } returns 1
        every { DbmIngestionService.enqueueActivityPayloads(any(), any()) } returns 1
        every { DbmIngestionService.enqueueMetadata(any(), any()) } returns 1
        every { DbmIngestionService.enqueueMetadataPayloads(any(), any()) } returns 1
        every { DbmIngestionService.enqueueHealth(any(), any()) } returns 1
        every { DbmIngestionService.enqueueHealthPayloads(any(), any()) } returns 1
        every { OrchestratorIngestionService.enqueueResources(any(), any()) } returns 1
        every { OrchestratorIngestionService.enqueueManifests(any(), any()) } returns 1
        every { DebuggerIngestionService.enqueueDebuggerLogs(any(), any()) } returns 1
        every { DebuggerIngestionService.enqueueDiagnostics(any(), any()) } returns 1
        every { TelemetryProxyService.acknowledge(any(), any(), any()) } just Runs
        coEvery { TraceIngestionService.insertTraceStats(any(), any()) } just Runs
        coEvery { ProfileIngestionService.ingestProfile(any(), any(), any(), any()) } returns "profile-test"
    }

    private fun Application.installAuth() {
        installJwtAuth()
    }

    private fun jwtToken(
        userId: Int = 1,
        orgId: Int = TEST_ORG_ID,
    ): String =
        RouteTestSupport.createToken(userId, orgId)

    private fun quotaUsage(): BillingUsageResponse {
        return BillingUsageResponse(
            organizationId = TEST_ORG_RESOURCE_ID,
            periodStart = "2026-05-01",
            periodEnd = "2026-05-31",
            retentionDays = 30,
            apmTraceRetentionDays = 30,
            usedUnits = 0,
            usedErrors = 0,
            errorLimit = 0,
            usedTransactions = 0,
            transactionLimit = 0,
            usedReplays = 0,
            replayLimit = 0,
            usedFeedback = 0,
            feedbackLimit = 0,
            usedBytes = 0,
            bytesLimit = 0,
            baseLimitUnits = 0,
            paygLimitUnits = 0,
            totalLimitUnits = 0,
            paygBudgetCents = 0,
            paygUsedUnits = 0,
            paygUsedCentsEstimate = 0,
            plan = "PRO",
            status = "active",
            withinQuota = true,
        )
    }

    private fun rejectingQuotaService(): BillingQuotaService {
        val quotaService = mockk<BillingQuotaService>()
        every { quotaService.isEnforcementEnabled() } returns true
        every {
            quotaService.reserveUnits(any(), any(), any(), any())
        } returns QuotaReservationResult(
            allowed = false,
            reason = "gb_quota_exceeded",
            usage = quotaUsage(),
        )
        every {
            quotaService.reserveUnitsBatch(any(), any(), any())
        } returns QuotaReservationResult(
            allowed = false,
            reason = "event_type_quota_exceeded",
            usage = quotaUsage(),
        )
        return quotaService
    }

    private fun buildProto(block: CodedOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val coded = CodedOutputStream.newInstance(out)
        coded.block()
        coded.flush()
        return out.toByteArray()
    }

    private fun buildProcessAgentMessage(type: Int, body: ByteArray): ByteArray {
        val header = ByteArray(PROCESS_AGENT_HEADER_SIZE)
        header[0] = PROCESS_AGENT_MESSAGE_V3
        header[1] = PROCESS_AGENT_PROTOBUF_ENCODING
        header[2] = type.toByte()
        return header + body
    }

    private fun buildProcessPayload(): ByteArray {
        val command = buildProto { writeString(1, "/usr/bin/nginx") }
        val process = buildProto {
            writeInt32(2, 1234)
            writeByteArray(4, command)
        }
        return buildProto {
            writeString(2, TEST_HOST)
            writeByteArray(3, process)
        }
    }

    private fun buildDiscoveryPayload(): ByteArray {
        val command = buildProto { writeString(1, "/usr/bin/java") }
        val discovery = buildProto {
            writeInt32(1, 4321)
            writeByteArray(4, command)
        }
        return buildProto {
            writeString(1, TEST_HOST)
            writeByteArray(4, discovery)
        }
    }

    private fun buildConnectionsPayload(): ByteArray {
        val localAddr = buildProto {
            writeString(2, "10.0.0.5")
            writeInt32(3, 8080)
        }
        val remoteAddr = buildProto {
            writeString(2, "203.0.113.20")
            writeInt32(3, 443)
        }
        val connection = buildProto {
            writeInt32(1, 1234)
            writeByteArray(5, localAddr)
            writeByteArray(6, remoteAddr)
            writeEnum(10, 0)
            writeEnum(11, 0)
            writeUInt64(16, 5000)
            writeUInt64(17, 10000)
            writeEnum(19, 2)
        }
        return buildProto {
            writeString(2, TEST_HOST)
            writeByteArray(3, connection)
        }
    }

    private fun assertProcessAgentCollectorResponse(bytes: ByteArray) {
        val header = ProcessAgentPayloadDecoder.readHeader(bytes)
        assertNotNull(header)
        assertEquals(ProcessAgentPayloadDecoder.TYPE_RES_COLLECTOR, header.type)
        assertEquals(PROCESS_AGENT_PROTOBUF_ENCODING.toInt(), header.encoding)
    }

    private fun buildStatsMsgpackPayload(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(2)
        packer.packString("AgentHostname")
        packer.packString(TEST_HOST)
        packer.packString("Stats")
        packer.packArrayHeader(1)
        packClientStatsPayload(packer)
        packer.close()
        return packer.toByteArray()
    }

    private fun packClientStatsPayload(packer: MessageBufferPacker) {
        packer.packMapHeader(1)
        packer.packString("Stats")
        packer.packArrayHeader(1)
        packer.packMapHeader(3)
        packer.packString("Start")
        packer.packLong(1700000000000000000L)
        packer.packString("Duration")
        packer.packLong(10000000000L)
        packer.packString("Stats")
        packer.packArrayHeader(1)
        packGroupedStats(packer)
    }

    private fun packGroupedStats(packer: MessageBufferPacker) {
        packer.packMapHeader(8)
        packer.packString("Name")
        packer.packString("web.request")
        packer.packString("Service")
        packer.packString("api")
        packer.packString("Resource")
        packer.packString("GET /health")
        packer.packString("Type")
        packer.packString("web")
        packer.packString("HTTPStatusCode")
        packer.packInt(200)
        packer.packString("Hits")
        packer.packLong(1)
        packer.packString("TopLevelHits")
        packer.packLong(1)
        packer.packString("Duration")
        packer.packLong(50000000L)
    }

    private fun seedUserAndOrg(): Pair<Int, Int> {
        val orgId = transaction {
            Organizations.insert {
                it[name] = "DD Test Org"
                it[slug] = "dd-test-org-${System.nanoTime()}"
            } get Organizations.id
        }
        val userId = transaction {
            Users.insert {
                it[email] = "dd-test-${System.nanoTime()}@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            } get Users.id
        }
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
        }
        return Pair(userId, orgId)
    }

    // ──── DatadogHostRoutes: Ingest ────

    @Test
    fun `POST dd api v1 metadata returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogHostIngestRoutes() }
        }
        val response = client.post(DD_METADATA_PATH) {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"hostname":"$TEST_HOST","agent_version":"7.0"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
    }

    @Test
    fun `POST dd api v1 metadata returns 400 for invalid payload`() =
        testApplication {
            coEvery {
                DatadogAuthMiddleware.authenticate(any())
            } returns TEST_ORG_ID

            application {
                install(ContentNegotiation) { json() }
                routing { datadogHostIngestRoutes() }
            }
            val response = client.post(DD_METADATA_PATH) {
                header(DD_API_KEY_HEADER, TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("not valid json!!!")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST dd api v2 host_metadata returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogHostIngestRoutes() }
        }
        val response = client.post("/dd/api/v2/host_metadata") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"hostname":"v2-host","os":"linux"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd intake returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogHostIngestRoutes() }
        }
        val response = client.post("/dd/intake/") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"apiKey":"k","gohai":"{}"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd intake returns 400 for invalid payload`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { datadogHostIngestRoutes() }
            }
            val response = client.post("/dd/intake/") {
                header(DD_API_KEY_HEADER, TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("bad json")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST dd api v1 metadata returns 403 without API key`() =
        testApplication {
            coEvery {
                DatadogAuthMiddleware.authenticate(any())
            } coAnswers {
                val call = firstArg<io.ktor.server.application.ApplicationCall>()
                with(call) {
                    respond(
                        HttpStatusCode.Forbidden,
                        mapOf("errors" to listOf("API key is missing"))
                    )
                }
                null
            }
            application {
                install(ContentNegotiation) { json() }
                routing { datadogHostIngestRoutes() }
            }
            val response = client.post(DD_METADATA_PATH) {
                contentType(ContentType.Application.Json)
                setBody("""{"hostname":"test"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // ──── DatadogHostRoutes: Query (JWT) ────

    @Test
    fun `GET v1 hosts returns 200 with host list`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        every { DatadogHostService.listHosts(orgId) } returns listOf(
            sampleHost(orgId)
        )
        application {
            installAuth()
            routing { datadogHostQueryRoutes() }
        }
        val response = client.get("/v1/hosts") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(TEST_HOST))
        assertTrue(response.bodyAsText().contains("totalCount"))
    }

    @Test
    fun `GET v1 hosts hostId returns 200`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        every { DatadogHostService.getHost(orgId, Uuid.parse(HOST_RESOURCE_ID)) } returns sampleHost(orgId)
        application {
            installAuth()
            routing { datadogHostQueryRoutes() }
        }
        val response = client.get("/v1/hosts/$HOST_RESOURCE_ID") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(TEST_HOST))
    }

    @Test
    fun `GET v1 hosts hostId returns 404 when missing`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        every { DatadogHostService.getHost(orgId, Uuid.parse(MISSING_HOST_RESOURCE_ID)) } returns null
        application {
            installAuth()
            routing { datadogHostQueryRoutes() }
        }
        val response = client.get("/v1/hosts/$MISSING_HOST_RESOURCE_ID") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET v1 hosts hostId returns 400 for non-int`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        application {
            installAuth()
            routing { datadogHostQueryRoutes() }
        }
        val response = client.get("/v1/hosts/abc") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `DELETE v1 hosts hostId returns 204`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        every { DatadogHostService.deleteHost(orgId, Uuid.parse(HOST_RESOURCE_ID)) } returns true
        application {
            installAuth()
            routing { datadogHostQueryRoutes() }
        }
        val response = client.delete("/v1/hosts/$HOST_RESOURCE_ID") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `DELETE v1 hosts hostId returns 404 when missing`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        every { DatadogHostService.deleteHost(orgId, Uuid.parse(MISSING_HOST_RESOURCE_ID)) } returns false
        application {
            installAuth()
            routing { datadogHostQueryRoutes() }
        }
        val response = client.delete("/v1/hosts/$MISSING_HOST_RESOURCE_ID") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET v1 hosts returns 401 without JWT`() = testApplication {
        application {
            installAuth()
            routing { datadogHostQueryRoutes() }
        }
        val response = client.get("/v1/hosts")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ──── DatadogMetricRoutes ────

    @Test
    fun `POST dd api v1 series returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogMetricRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v1/series") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(
                """{"series":[{"metric":"system.cpu","type":"gauge",""" +
                    """"host":"h1","points":[[1700000000,42.0]]}]}"""
            )
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v1 series returns 400 for bad payload`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { datadogMetricRoutes(allowingQuotaService) }
            }
            val response = client.post("/dd/api/v1/series") {
                header(DD_API_KEY_HEADER, TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("bad json")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST dd api v1 series bills infra namespaces separately from custom metrics`() = testApplication {
        val quotaService = mockk<BillingQuotaService>()
        val body =
            """{"series":[""" +
                """{"metric":"system.cpu","host":"h1","tags":["device:vda"],"points":""" +
                """[[1700000000,42.0],[1700000060,43.0]]},""" +
                """{"metric":"checkout.orders","host":"h1","points":[[1700000000,1.0],[1700000060,2.0]]}""" +
                """]}"""
        every { quotaService.isEnforcementEnabled() } returns true
        every {
            quotaService.reserveUnitsBatch(any(), any(), any())
        } returns QuotaReservationResult(
            allowed = true,
            usage = quotaUsage(),
        )

        application {
            install(ContentNegotiation) { json() }
            routing { datadogMetricRoutes(quotaService) }
        }
        val response = client.post("/dd/api/v1/series") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        verify {
            quotaService.reserveUnitsBatch(
                organizationId = TEST_ORG_ID,
                requestedUnitsByType = mapOf("infra_metric" to 1, "dd_metric" to 2),
                requestedBytesByType = any(),
            )
        }
    }

    @Test
    fun `POST dd api v2 series returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogMetricRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v2/series") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(
                """{"series":[{"metric":"sys.mem","type":"gauge",""" +
                    """"host":"h1","points":[[1700000000,80.0]]}]}"""
            )
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v3 series returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogMetricRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v3/series") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(
                """{"series":[{"metric":"sys.load","type":"gauge",""" +
                    """"host":"h1","points":[[1700000000,1.5]]}]}"""
            )
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v1 sketches returns 202 for empty body`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { datadogMetricRoutes(allowingQuotaService) }
            }
            val response = client.post("/dd/api/v1/sketches") {
                header(DD_API_KEY_HEADER, TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("")
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }

    @Test
    fun `POST dd api beta sketches returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogMetricRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/beta/sketches") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v1 sketches returns 202 for JSON payload`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { datadogMetricRoutes(allowingQuotaService) }
            }
            val response = client.post("/dd/api/v1/sketches") {
                header(DD_API_KEY_HEADER, TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("""{"sketches":[]}""")
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }

    // ──── DatadogEventRoutes ────

    @Test
    fun `POST dd api v1 check_run returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogEventRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v1/check_run") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(
                """[{"check":"cpu","host_name":"h1","status":0}]"""
            )
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v1 check_run returns 400 for bad JSON`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { datadogEventRoutes(allowingQuotaService) }
            }
            val response = client.post("/dd/api/v1/check_run") {
                header(DD_API_KEY_HEADER, TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("not json")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST dd api v2 events returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogEventRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v2/events") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"events":[{"title":"test","text":"hello"}]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 service_checks returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogEventRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v2/service_checks") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(
                """{"service_checks":[{"check":"disk","host_name":"h1","status":0}]}"""
            )
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 service_checks returns 400 for bad JSON`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { datadogEventRoutes(allowingQuotaService) }
            }
            val response = client.post("/dd/api/v2/service_checks") {
                header(DD_API_KEY_HEADER, TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("bad")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ──── DatadogLogRoutes ────

    @Test
    fun `POST dd api v2 logs returns 200`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogLogRoutes(allowingQuotaService) }
        }
        val response = client.post(DD_LOGS_PATH) {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(
                """[{"message":"test log","hostname":"h1","service":"svc","status":"info"}]"""
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST dd api v2 logs single object returns 200`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogLogRoutes(allowingQuotaService) }
        }
        val response = client.post(DD_LOGS_PATH) {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(
                """{"message":"single log","hostname":"h1"}"""
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST unprefixed api v2 logs returns 200`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogLogRoutes(allowingQuotaService) }
        }
        val response = client.post("/api/v2/logs") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(
                """[{"message":"test log","hostname":"h1","service":"svc","status":"info"}]"""
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST dd api v2 logs returns 400 for bad payload`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { datadogLogRoutes(allowingQuotaService) }
            }
            val response = client.post(DD_LOGS_PATH) {
                header(DD_API_KEY_HEADER, TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("not json at all")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ──── DatadogDogStatsDRoutes ────

    @Test
    fun `POST dd dogstatsd v2 proxy returns 200`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogDogStatsDRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/dogstatsd/v2/proxy") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.OctetStream)
            setBody("cpu.usage:42.5|g|#env:prod,host:h1\nmem.used:1024|c")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST dd dogstatsd v2 proxy handles empty body`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogDogStatsDRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/dogstatsd/v2/proxy") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.OctetStream)
            setBody("")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST dd dogstatsd v2 proxy bills infra namespaces separately`() = testApplication {
        val quotaService = mockk<BillingQuotaService>()
        val body = "system.cpu.user:42|g|#host:h1\napp.orders:1|c|#env:prod"
        every { quotaService.isEnforcementEnabled() } returns true
        every {
            quotaService.reserveUnitsBatch(any(), any(), any())
        } returns QuotaReservationResult(
            allowed = true,
            usage = quotaUsage(),
        )

        application {
            install(ContentNegotiation) { json() }
            routing { datadogDogStatsDRoutes(quotaService) }
        }
        val response = client.post("/dd/dogstatsd/v2/proxy") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.OctetStream)
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        verify {
            quotaService.reserveUnitsBatch(
                organizationId = TEST_ORG_ID,
                requestedUnitsByType = mapOf("infra_metric" to 1, "dd_metric" to 1),
                requestedBytesByType = any(),
            )
        }
    }

    @Test
    fun `PUT dd traces returns 429 when quota is exceeded`() = testApplication {
        val quotaService = rejectingQuotaService()
        val body = """[[{"trace_id":1,"span_id":2,"name":"web.request"}]]"""

        application {
            install(ContentNegotiation) { json() }
            routing { traceIngestRoutes(quotaService) }
        }

        val response = client.put("/dd/v0.4/traces") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        verify {
            quotaService.reserveUnits(
                organizationId = TEST_ORG_ID,
                requestedUnits = 1,
                eventType = "dd_trace",
                requestedBytes = body.toByteArray().size.toLong(),
            )
        }
    }

    @Test
    fun `POST unprefixed api traces returns 429 when quota is exceeded`() = testApplication {
        val quotaService = rejectingQuotaService()
        val body = """[[{"trace_id":1,"span_id":2,"name":"web.request"}]]"""

        application {
            install(ContentNegotiation) { json() }
            routing { traceIngestRoutes(quotaService) }
        }

        val response = client.post("/api/v0.2/traces") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
    }

    @Test
    fun `POST unprefixed api traces returns 400 for malformed JSON`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { traceIngestRoutes(allowingQuotaService) }
        }

        val response = client.post("/api/v0.2/traces") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("{")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Unparseable trace payload"))
    }

    @Test
    fun `POST unprefixed api traces returns 400 for invalid JSON shape`() = testApplication {
        val invalidBodies = listOf("{}", "[{}]")

        application {
            install(ContentNegotiation) { json() }
            routing { traceIngestRoutes(allowingQuotaService) }
        }

        invalidBodies.forEach { body ->
            val response = client.post("/api/v0.2/traces") {
                header(DD_API_KEY_HEADER, TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertTrue(response.bodyAsText().contains("Unparseable trace payload"), body)
        }
    }

    @Test
    fun `POST unprefixed api trace stats returns 429 when quota is exceeded`() = testApplication {
        val quotaService = rejectingQuotaService()
        val body = """
            {
              "Stats": [
                {
                  "Start": 1700000000000,
                  "Duration": 10000000000,
                  "Stats": [
                    {
                      "Name": "web.request",
                      "Service": "api",
                      "Resource": "GET /health",
                      "Type": "web",
                      "Hits": 1
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        application {
            install(ContentNegotiation) { json() }
            routing { traceIngestRoutes(quotaService) }
        }

        val response = client.post("/api/v0.6/stats") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
    }

    @Test
    fun `trace api aliases parse payloads before quota rejection`() = testApplication {
        val quotaService = rejectingQuotaService()
        val traceBody = """[[{"trace_id":1,"span_id":2,"name":"web.request"}]]"""
        val statsBody = """
            {
              "Stats": [
                {
                  "Start": 1700000000000,
                  "Duration": 10000000000,
                  "Stats": [
                    {
                      "Name": "web.request",
                      "Service": "api",
                      "Resource": "GET /health",
                      "Type": "web",
                      "Hits": 1
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val aliases = listOf(
            TraceAliasRequest(HttpMethod.Post, "/dd/api/v0.2/traces", traceBody),
            TraceAliasRequest(HttpMethod.Put, "/dd/api/v0.2/traces", traceBody),
            TraceAliasRequest(HttpMethod.Post, "/dd/api/v0.2/stats", statsBody),
            TraceAliasRequest(HttpMethod.Put, "/dd/api/v0.2/stats", statsBody),
            TraceAliasRequest(HttpMethod.Post, "/dd/api/v0.6/stats", statsBody),
            TraceAliasRequest(HttpMethod.Put, "/dd/api/v0.6/stats", statsBody),
            TraceAliasRequest(HttpMethod.Put, "/dd/v0.6/stats", statsBody),
            TraceAliasRequest(HttpMethod.Put, "/api/v0.2/traces", traceBody),
            TraceAliasRequest(HttpMethod.Put, "/api/v0.2/stats", statsBody),
            TraceAliasRequest(HttpMethod.Put, "/api/v0.6/stats", statsBody),
        )

        application {
            install(ContentNegotiation) { json() }
            routing { traceIngestRoutes(quotaService) }
        }

        aliases.forEach { alias ->
            val response = client.request(alias.path) {
                method = alias.method
                header(DD_API_KEY_HEADER, TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody(alias.body)
            }

            assertEquals(
                HttpStatusCode.TooManyRequests,
                response.status,
                "${alias.method.value} ${alias.path}",
            )
        }
    }

    @Test
    fun `POST unprefixed api v0_2 trace stats decodes msgpack and inserts`() = testApplication {
        val quotaService = mockk<BillingQuotaService>()
        val body = buildStatsMsgpackPayload()
        var capturedPayload: DdStatsPayload? = null
        every { quotaService.isEnforcementEnabled() } returns true
        every {
            quotaService.reserveUnits(any(), any(), any(), any())
        } returns QuotaReservationResult(
            allowed = true,
            reason = null,
            usage = quotaUsage(),
        )
        coEvery {
            TraceIngestionService.insertTraceStats(TEST_ORG_ID, any())
        } coAnswers {
            capturedPayload = secondArg()
        }

        application {
            install(ContentNegotiation) { json() }
            routing { traceIngestRoutes(quotaService) }
        }

        val response = client.post("/api/v0.2/stats") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.parse("application/msgpack"))
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = requireNotNull(capturedPayload)
        assertEquals(TEST_HOST, payload.hostname)
        assertEquals(1, payload.stats.single().stats.size)
        verify {
            quotaService.reserveUnits(
                organizationId = TEST_ORG_ID,
                requestedUnits = 1,
                eventType = "dd_trace",
                requestedBytes = body.size.toLong(),
            )
        }
    }

    @Test
    fun `GET dd trace health returns diagnostic compatibility response`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { traceIngestRoutes(allowingQuotaService) }
        }

        val response = client.get("/dd/_health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
    }

    @Test
    fun `HEAD dd trace health returns diagnostic compatibility response`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { traceIngestRoutes(allowingQuotaService) }
        }

        val response = client.head("/dd/_health")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET dd support flare returns diagnostic compatibility response`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { traceIngestRoutes(allowingQuotaService) }
        }

        val response = client.get("/dd/support/flare")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
    }

    @Test
    fun `HEAD dd support flare returns diagnostic compatibility response`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { traceIngestRoutes(allowingQuotaService) }
        }

        val response = client.head("/dd/support/flare")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST dd support flare returns diagnostic compatibility response`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { traceIngestRoutes(allowingQuotaService) }
        }

        val response = client.post("/dd/support/flare")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("agent-diagnostic"))
    }

    @Test
    fun `POST dd metrics returns 429 before enqueue when quota is exceeded`() = testApplication {
        val quotaService = rejectingQuotaService()
        val body = """{"series":[{"metric":"system.cpu","host":"h1","points":[[1700000000,42.0]]}]}"""

        application {
            install(ContentNegotiation) { json() }
            routing { datadogMetricRoutes(quotaService) }
        }

        val response = client.post("/dd/api/v1/series") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        verify {
            quotaService.reserveUnitsBatch(
                organizationId = TEST_ORG_ID,
                requestedUnitsByType = mapOf("infra_metric" to 1),
                requestedBytesByType = mapOf("infra_metric" to body.toByteArray().size.toLong()),
            )
        }
        verify(exactly = 0) { DatadogHostService.touchHostLastSeen(any(), any()) }
        coVerify(exactly = 0) { DatadogMetricService.enqueueMetrics(any(), any(), any()) }
    }

    @Test
    fun `POST dd metrics refunds quota when queue capacity rejects`() = testApplication {
        val quotaService = mockk<BillingQuotaService>(relaxed = true)
        val body = """{"series":[{"metric":"system.cpu","host":"h1","points":[[1700000000,42.0]]}]}"""
        val requestedBytes = mapOf("infra_metric" to body.toByteArray().size.toLong())
        every { quotaService.isEnforcementEnabled() } returns true
        every {
            quotaService.reserveUnitsBatch(TEST_ORG_ID, mapOf("infra_metric" to 1), requestedBytes)
        } returns QuotaReservationResult(allowed = true, usage = quotaUsage())
        coEvery { DatadogMetricService.enqueueMetrics(any(), any(), any()) } throws
            IngestionQueueCapacityException(IngestionPipeline.DD_METRICS, 100)

        application {
            install(ContentNegotiation) { json() }
            installErrorHandling()
            routing { datadogMetricRoutes(quotaService) }
        }

        val response = client.post("/dd/api/v1/series") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("5", response.headers[HttpHeaders.RetryAfter])
        verify {
            quotaService.refundUnitsBatch(TEST_ORG_ID, mapOf("infra_metric" to 1), requestedBytes)
        }
    }

    @Test
    fun `POST dd service checks rejects quota before enqueue`() = testApplication {
        val quotaService = rejectingQuotaService()
        val body = """{"service_checks":[{"check":"disk","host_name":"h1","status":0}]}"""

        application {
            install(ContentNegotiation) { json() }
            routing { datadogEventRoutes(quotaService) }
        }

        val response = client.post("/dd/api/v2/service_checks") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        verify {
            quotaService.reserveUnits(
                organizationId = TEST_ORG_ID,
                requestedUnits = 1,
                eventType = "dd_event",
                requestedBytes = body.toByteArray().size.toLong(),
            )
        }
        verify(exactly = 0) { DatadogHostService.touchHostLastSeen(any(), any()) }
        coVerify(exactly = 0) { DatadogEventService.enqueueServiceChecks(any(), any()) }
    }

    @Test
    fun `POST dd logs returns 429 before enqueue when quota is exceeded`() = testApplication {
        val quotaService = rejectingQuotaService()
        val body = """[{"message":"test log","hostname":"h1","service":"svc","status":"info"}]"""

        application {
            install(ContentNegotiation) { json() }
            routing { datadogLogRoutes(quotaService) }
        }

        val response = client.post(DD_LOGS_PATH) {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        verify {
            quotaService.reserveUnits(
                organizationId = TEST_ORG_ID,
                requestedUnits = 1,
                eventType = "dd_log",
                requestedBytes = body.toByteArray().size.toLong(),
            )
        }
        coVerify(exactly = 0) { DatadogLogService.enqueueLogs(any(), any()) }
    }

    // ──── DatadogValidateRoutes ────

    @Test
    fun `GET dd api v1 validate returns 200 with orgId`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogValidateRoutes() }
        }
        val response = client.get("/dd/api/v1/validate") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().filterNot(Char::isWhitespace).contains(""""valid":true"""))
    }

    @Test
    fun `GET unprefixed api v1 validate returns 200 with orgId`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogValidateRoutes() }
        }
        val response = client.get("/api/v1/validate") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().filterNot(Char::isWhitespace).contains(""""valid":true"""))
    }

    @Test
    fun `GET dd api v1 validate accepts trailing slash`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogValidateRoutes() }
        }
        val response = client.get("/dd/api/v1/validate/") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().filterNot(Char::isWhitespace).contains(""""valid":true"""))
    }

    @Test
    fun `HEAD dd api v1 validate returns 200`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogValidateRoutes() }
        }
        val response = client.head("/dd/api/v1/validate") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET dd api v1 validate returns 403 without key`() = testApplication {
        coEvery {
            DatadogAuthMiddleware.authenticate(any())
        } coAnswers {
            val call = firstArg<io.ktor.server.application.ApplicationCall>()
            with(call) {
                respond(
                    HttpStatusCode.Forbidden,
                    mapOf("errors" to listOf("missing key"))
                )
            }
            null
        }
        application {
            install(ContentNegotiation) { json() }
            routing { datadogValidateRoutes() }
        }
        val response = client.get("/dd/api/v1/validate")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ──── MiscIngestRoutes ────

    @Test
    fun `POST dd symdb v1 input returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/symdb/v1/input") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"service":"web","symbols":""}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 data_streams returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v2/data_streams") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"entries":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 contimage returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v2/contimage") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"images":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 sbom returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v2/sbom") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"packages":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd v0 1 pipeline_stats returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/v0.1/pipeline_stats") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"stats":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v1 lineage returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v1/lineage") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"nodes":[],"edges":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 synthetics returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v2/synthetics") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"results":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST api v2 contlcycle returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/api/v2/contlcycle") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST api v2 events management returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/api/v2/events") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ──── ProfileIngestRoutes ────

    @Test
    fun `POST profile v2 aliases ingest uploaded profile`() = testApplication {
        val aliases = listOf(
            "/api/v2/profile",
            "/dd/api/v2/profile",
            "/profiling/v1/input",
            "/dd/profiling/v1/input",
        )
        application {
            install(ContentNegotiation) { json() }
            routing { profileIngestRoutes(allowingQuotaService) }
        }

        aliases.forEach { path ->
            val response = client.post(path) {
                header(DD_API_KEY_HEADER, TEST_API_KEY)
                setBody(profileMultipartBody())
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("profile-test"))
        }

        coVerify(exactly = aliases.size) {
            ProfileIngestionService.ingestProfile(TEST_ORG_ID, any(), any(), "cpu")
        }
    }

    @Test
    fun `POST profile v2 returns 400 for non multipart payload`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { profileIngestRoutes(allowingQuotaService) }
        }

        val response = client.post("/api/v2/profile") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Multipart profile payload required"))
    }

    // ──── DatadogInfraRoutes ────

    @Test
    fun `POST api v1 discovery decodes and enqueues process discovery`() = testApplication {
        var capturedPayload: DatadogProcessPayload? = null
        val batch = QueuedInfraBatch(
            organizationId = TEST_ORG_ID.toLong(),
            type = "processes",
            processes = listOf(
                QueuedProcessEntry(
                    host = TEST_HOST,
                    pid = 4321,
                    name = "java",
                    command = "/usr/bin/java",
                    timestampMs = 1700000000000,
                )
            ),
        )
        every {
            DatadogInfraService.mapProcesses(TEST_ORG_ID.toLong(), any())
        } answers {
            capturedPayload = secondArg()
            batch
        }
        coEvery { DatadogInfraService.enqueueInfra(batch, any()) } returns 1

        application {
            install(ContentNegotiation) { json() }
            routing { datadogInfraRoutes(allowingQuotaService) }
        }
        val response = client.post("/api/v1/discovery") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.OctetStream)
            setBody(
                buildProcessAgentMessage(
                    ProcessAgentPayloadDecoder.TYPE_COLLECTOR_PROC_DISCOVERY,
                    buildDiscoveryPayload(),
                )
            )
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertProcessAgentCollectorResponse(response.bodyAsBytes())
        val payload = requireNotNull(capturedPayload)
        assertEquals(TEST_HOST, payload.host)
        assertEquals(4321, payload.processes.single().pid)
        assertEquals("java", payload.processes.single().name)
    }

    @Test
    fun `POST unprefixed api v1 collector returns process-agent collector response`() = testApplication {
        val batch = QueuedInfraBatch(
            organizationId = TEST_ORG_ID.toLong(),
            type = "processes",
            processes = listOf(
                QueuedProcessEntry(
                    host = TEST_HOST,
                    pid = 1234,
                    name = "nginx",
                    command = "/usr/bin/nginx",
                    timestampMs = 1700000000000,
                )
            ),
        )
        every {
            DatadogInfraService.mapProcesses(TEST_ORG_ID.toLong(), any())
        } returns batch
        coEvery { DatadogInfraService.enqueueInfra(batch, any()) } returns 1

        application {
            install(ContentNegotiation) { json() }
            routing { datadogInfraRoutes(allowingQuotaService) }
        }
        val response = client.post("/api/v1/collector") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.OctetStream)
            setBody(
                buildProcessAgentMessage(
                    ProcessAgentPayloadDecoder.TYPE_COLLECTOR_PROC,
                    buildProcessPayload(),
                )
            )
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertProcessAgentCollectorResponse(response.bodyAsBytes())
    }

    @Test
    fun `POST dd api v1 connections decodes and enqueues network connections`() = testApplication {
        var capturedPayload: DatadogConnectionsPayload? = null
        val batch = QueuedInfraBatch(
            organizationId = TEST_ORG_ID.toLong(),
            type = "connections",
            connections = listOf(
                QueuedConnectionEntry(
                    host = TEST_HOST,
                    pid = 1234,
                    localAddr = "10.0.0.5",
                    localPort = 8080,
                    remoteAddr = "203.0.113.20",
                    remotePort = 443,
                    protocol = "tcp",
                    family = "IPv4",
                    direction = "outgoing",
                    bytesSent = 5000,
                    bytesRecv = 10000,
                    timestampMs = 1700000000000,
                )
            ),
        )
        every {
            DatadogInfraService.mapConnections(TEST_ORG_ID.toLong(), any())
        } answers {
            capturedPayload = secondArg()
            batch
        }
        coEvery { DatadogInfraService.enqueueInfra(batch, any()) } returns 1

        application {
            install(ContentNegotiation) { json() }
            routing { datadogInfraRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v1/connections") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.OctetStream)
            setBody(
                buildProcessAgentMessage(
                    ProcessAgentPayloadDecoder.TYPE_COLLECTOR_CONNECTIONS,
                    buildConnectionsPayload(),
                )
            )
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertProcessAgentCollectorResponse(response.bodyAsBytes())
        val payload = checkNotNull(capturedPayload)
        assertEquals(TEST_HOST, payload.host)
        val decoded = payload.connections.single()
        assertEquals(1234, decoded.pid)
        assertEquals("10.0.0.5", decoded.localAddr)
        assertEquals(8080, decoded.localPort)
        assertEquals("203.0.113.20", decoded.remoteAddr)
        assertEquals(443, decoded.remotePort)
        assertEquals("tcp", decoded.protocol)
        assertEquals("outgoing", decoded.direction)
        assertEquals(5000, decoded.bytesSent)
        assertEquals(10000, decoded.bytesRecv)
        coVerify(exactly = 1) { DatadogInfraService.enqueueInfra(batch, any()) }
    }

    @Test
    fun `POST unprefixed api v1 collector parses process payload before quota rejection`() = testApplication {
        val quotaService = rejectingQuotaService()
        var capturedPayload: DatadogProcessPayload? = null
        every {
            DatadogInfraService.mapProcesses(TEST_ORG_ID.toLong(), any())
        } answers {
            capturedPayload = secondArg()
            QueuedInfraBatch(
                organizationId = TEST_ORG_ID.toLong(),
                type = "processes",
                processes = listOf(
                    QueuedProcessEntry(
                        host = TEST_HOST,
                        pid = 1234,
                        name = "nginx",
                        command = "/usr/bin/nginx",
                        timestampMs = 1700000000000,
                    )
                ),
            )
        }

        application {
            install(ContentNegotiation) { json() }
            routing { datadogInfraRoutes(quotaService) }
        }
        val response = client.post("/api/v1/collector") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.OctetStream)
            setBody(
                buildProcessAgentMessage(
                    ProcessAgentPayloadDecoder.TYPE_COLLECTOR_PROC,
                    buildProcessPayload(),
                )
            )
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        val payload = checkNotNull(capturedPayload)
        assertEquals(TEST_HOST, payload.host)
        val decoded = payload.processes.single()
        assertEquals(1234, decoded.pid)
        assertEquals("nginx", decoded.name)
    }

    @Test
    fun `POST unprefixed api v1 connections parses payload before quota rejection`() = testApplication {
        val quotaService = rejectingQuotaService()
        var capturedPayload: DatadogConnectionsPayload? = null
        every {
            DatadogInfraService.mapConnections(TEST_ORG_ID.toLong(), any())
        } answers {
            capturedPayload = secondArg()
            QueuedInfraBatch(
                organizationId = TEST_ORG_ID.toLong(),
                type = "connections",
                connections = listOf(
                    QueuedConnectionEntry(
                        host = TEST_HOST,
                        pid = 1234,
                        localAddr = "10.0.0.5",
                        localPort = 8080,
                        remoteAddr = "203.0.113.20",
                        remotePort = 443,
                        timestampMs = 1700000000000,
                    )
                ),
            )
        }

        application {
            install(ContentNegotiation) { json() }
            routing { datadogInfraRoutes(quotaService) }
        }
        val response = client.post("/api/v1/connections") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.OctetStream)
            setBody(
                buildProcessAgentMessage(
                    ProcessAgentPayloadDecoder.TYPE_COLLECTOR_CONNECTIONS,
                    buildConnectionsPayload(),
                )
            )
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        val payload = checkNotNull(capturedPayload)
        assertEquals(TEST_HOST, payload.host)
        assertEquals("203.0.113.20", payload.connections.single().remoteAddr)
    }

    @Test
    fun `POST dd api v1 connections rejects malformed process agent payload`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogInfraRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v1/connections") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.OctetStream)
            setBody(ByteArray(0))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ──── DbmIngestRoutes ────

    @Test
    fun `POST dd api v2 databasequery returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { dbmIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v2/databasequery") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(EMPTY_ROWS_JSON)
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 dbmmetrics returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { dbmIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v2/dbmmetrics") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(EMPTY_ROWS_JSON)
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 dbmactivity returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { dbmIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v2/dbmactivity") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody(EMPTY_ROWS_JSON)
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 dbmmetadata returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { dbmIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v2/dbmmetadata") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"entries":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 dbmhealth returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { dbmIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v2/dbmhealth") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"checks":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST api v2 databasequery without prefix returns 202`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { dbmIngestRoutes(allowingQuotaService) }
            }
            val response = client.post("/api/v2/databasequery") {
                header(DD_API_KEY_HEADER, TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody(EMPTY_ROWS_JSON)
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }

    @Test
    fun `POST dd api v2 databasequery returns 400 for bad payload`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { dbmIngestRoutes(allowingQuotaService) }
            }
            val response = client.post("/dd/api/v2/databasequery") {
                header(DD_API_KEY_HEADER, TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("invalid")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ──── OrchestratorIngestRoutes ────

    @Test
    fun `POST dd api v2 orch returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { orchestratorIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v2/orch") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"resources":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 orchmanif returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { orchestratorIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v2/orchmanif") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"manifests":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 orch returns 400 for bad payload`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { orchestratorIngestRoutes(allowingQuotaService) }
            }
            val response = client.post("/dd/api/v2/orch") {
                header(DD_API_KEY_HEADER, TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("bad")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ──── DebuggerIngestRoutes ────

    @Test
    fun `POST dd debugger v1 input returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { debuggerIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/debugger/v1/input") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""[{"probe_id":"p1","message":"snap"}]""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd debugger v2 input returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { debuggerIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/debugger/v2/input") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""[{"probe_id":"p2","message":"v2 snap"}]""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd debugger v1 diagnostics returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { debuggerIngestRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/debugger/v1/diagnostics") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""[{"probe_id":"d1","status":"ok"}]""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd debugger v1 input returns 400 for bad payload`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { debuggerIngestRoutes(allowingQuotaService) }
            }
            val response = client.post("/dd/debugger/v1/input") {
                header(DD_API_KEY_HEADER, TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("invalid")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ──── TelemetryProxyRoutes ────

    @Test
    fun `POST dd telemetry proxy returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { telemetryProxyRoutes() }
        }
        val response = client.post("/dd/telemetry/proxy/api/v2/apmtelemetry") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"payload":"test"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST unprefixed apm telemetry returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { telemetryProxyRoutes() }
        }
        val response = client.post("/api/v2/apmtelemetry") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"payload":"test"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ──── DatadogRoutes (API Key Management) ────

    @Test
    fun `GET v1 agent-api-keys returns 200`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        every { DatadogService.listApiKeys(orgId) } returns emptyList()
        application {
            installAuth()
            datadogRoutes()
        }
        val response = client.get(AGENT_API_KEYS_PATH) {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("keys"))
    }

    @Test
    fun `POST v1 agent-api-keys returns 201`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        every { DatadogService.createApiKey(orgId, "test key", userId, null) } returns
            com.moneat.datadog.models.CreateDdApiKeyResponse(
                id = "11111111-1111-1111-1111-111111111111",
                name = "test key",
                key = "TEST_API_KEY_RESPONSE_PLACEHOLDER",
                keyPrefix = "TEST_API_KEY",
            )
        application {
            installAuth()
            datadogRoutes()
        }
        val response = client.post(AGENT_API_KEYS_PATH) {
            withAuth(jwtToken(userId, orgId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"test key"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `DELETE v1 agent-api-keys id returns 204`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        val keyId = Uuid.parse("11111111-1111-1111-1111-111111111111")
        every { DatadogService.deleteApiKey(keyId, orgId) } returns true
        application {
            installAuth()
            datadogRoutes()
        }
        val response = client.delete("$AGENT_API_KEYS_PATH/$keyId") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `DELETE v1 agent-api-keys rejects numeric ids`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        application {
            installAuth()
            datadogRoutes()
        }
        val response = client.delete("$AGENT_API_KEYS_PATH/42") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `DELETE v1 agent-api-keys returns 404 for missing key`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val keyId = Uuid.parse("99999999-9999-9999-9999-999999999999")
            every { DatadogService.deleteApiKey(keyId, orgId) } returns false
            application {
                installAuth()
                datadogRoutes()
            }
            val response = client.delete("$AGENT_API_KEYS_PATH/$keyId") {
                withAuth(jwtToken(userId, orgId))
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET v1 agent-api-keys returns 401 without JWT`() = testApplication {
        application {
            installAuth()
            datadogRoutes()
        }
        val response = client.get(AGENT_API_KEYS_PATH)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ──── TraceDashboardRoutes (JWT) ────

    @Test
    fun `GET v1 traces resources returns 200`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        coEvery {
            TraceIngestionService.listResourceStats(orgId, any<DdResourceStatsQuery>())
        } returns DdResourceStatsResponse(emptyList(), 0L)
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val response = client.get("/v1/traces/resources") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET v1 traces returns 200`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        coEvery {
            TraceIngestionService.listTraces(
                organizationId = orgId,
                query = any<DdTraceListQuery>(),
                parentSpan = any(),
            )
        } returns DdTraceListResponse(emptyList(), 0L)
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val response = client.get("/v1/traces") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET v1 traces traceId returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            coEvery {
                TraceIngestionService.getTraceDetail(orgId, "12345")
            } returns null
            application {
                installAuth()
                routing { traceDashboardRoutes() }
            }
            val response = client.get("/v1/traces/12345") {
                withAuth(jwtToken(userId, orgId))
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET v1 traces traceId returns 400 for invalid id`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application {
                installAuth()
                routing { traceDashboardRoutes() }
            }
            val response = client.get("/v1/traces/not-a-number") {
                withAuth(jwtToken(userId, orgId))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET v1 services map returns 200`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        coEvery {
            TraceIngestionService.getServiceMap(any(), any(), any(), any(), any())
        } returns DdServiceMapResponse(emptyList())
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val response = client.get("/v1/services/map") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET v1 services map forwards scope filters`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        coEvery {
            TraceIngestionService.getServiceMap(orgId, any(), "prod", "otel", any())
        } returns DdServiceMapResponse(emptyList())
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val response = client.get("/v1/services/map?timeRange=6h&env=prod&source=otel") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) {
            TraceIngestionService.getServiceMap(orgId, any(), "prod", "otel", any())
        }
    }

    @Test
    fun `GET v1 services map rejects oversized scope filter`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val response = client.get("/v1/services/map?env=${"p".repeat(201)}") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET v1 services service latency returns percentiles`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        coEvery {
            TraceIngestionService.getServiceLatencyPercentiles(orgId, "checkout", any(), "prod", "otel", any())
        } returns DdServiceLatencyResponse(
            service = "checkout",
            p50DurationNs = 1_000L,
            p90DurationNs = 5_000L,
            p99DurationNs = 9_000L,
            sampleCount = 42L,
        )
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val response = client.get("/v1/services/checkout/latency?timeRange=24h&env=prod&source=otel") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"sampleCount\":42"))
    }

    @Test
    fun `GET v1 services service latency rejects oversized service`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val response = client.get("/v1/services/${"s".repeat(201)}/latency") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET v1 services service latency rejects oversized scope filter`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val response = client.get("/v1/services/checkout/latency?source=${"s".repeat(201)}") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET v1 apm-errors returns 200`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        coEvery {
            TraceIngestionService.getApmErrors(orgId, any(), any(), any(), any(), any())
        } returns DdApmErrorsResponse(emptyList(), 0L)
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val response = client.get("/v1/apm-errors") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET v1 apm-errors trims deduplicates and forwards service filters`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        coEvery {
            TraceIngestionService.getApmErrors(
                orgId,
                listOf("api", "worker"),
                any(),
                any(),
                any(),
                any(),
            )
        } returns DdApmErrorsResponse(emptyList(), 0L)
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val response = client.get("/v1/apm-errors?services=api,%20worker,api&service=ignored") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) {
            TraceIngestionService.getApmErrors(
                orgId,
                listOf("api", "worker"),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `GET v1 apm-errors falls back to legacy service when services is empty`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        coEvery {
            TraceIngestionService.getApmErrors(
                orgId,
                listOf("api"),
                any(),
                any(),
                any(),
                any(),
            )
        } returns DdApmErrorsResponse(emptyList(), 0L)
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val response = client.get("/v1/apm-errors?services=,%20&service=api") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) {
            TraceIngestionService.getApmErrors(
                orgId,
                listOf("api"),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `GET v1 apm-errors rejects too many service filters`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val services = (1..51).joinToString(",") { "service-$it" }
        val response = client.get("/v1/apm-errors?services=$services") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        coVerify(exactly = 0) {
            TraceIngestionService.getApmErrors(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `GET v1 apm-errors rejects too many raw service filters before dedupe`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val services = (1..51).joinToString(",") { "api" }
        val response = client.get("/v1/apm-errors?services=$services") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        coVerify(exactly = 0) {
            TraceIngestionService.getApmErrors(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `GET v1 apm-errors rejects oversized service filters`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val serviceName = "a".repeat(201)
        val response = client.get("/v1/apm-errors?services=$serviceName") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        coVerify(exactly = 0) {
            TraceIngestionService.getApmErrors(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `GET v1 traces returns 401 without JWT`() = testApplication {
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val response = client.get("/v1/traces")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ──── Helpers ────

    private fun profileMultipartBody(): MultiPartFormDataContent =
        MultiPartFormDataContent(
            formData {
                appendFile(
                    name = "event",
                    fileName = "event.json",
                    bytes = """{"start":"2026-06-03T03:45:00Z","end":"2026-06-03T03:46:00Z"}"""
                        .toByteArray(),
                    contentType = ContentType.Application.Json,
                )
                appendFile("chunk_data", "cpu.pprof", buildPprofProfilePayload())
            }
        )

    private fun FormBuilder.appendFile(
        name: String,
        fileName: String,
        bytes: ByteArray,
        contentType: ContentType = ContentType.Application.OctetStream,
    ) {
        append(
            name,
            bytes,
            Headers.build {
                append(
                    HttpHeaders.ContentDisposition,
                    "form-data; name=\"$name\"; filename=\"$fileName\""
                )
                append(HttpHeaders.ContentType, contentType.toString())
            }
        )
    }

    private fun buildPprofProfilePayload(): ByteArray =
        buildProto {
            writeByteArray(
                1,
                buildProto {
                    writeInt64(1, 1)
                    writeInt64(2, 2)
                }
            )
            writeByteArray(
                2,
                buildProto {
                    writeUInt64(1, 1)
                    writeInt64(2, 1)
                }
            )
            writeByteArray(
                4,
                buildProto {
                    writeUInt64(1, 1)
                    writeByteArray(
                        4,
                        buildProto {
                            writeUInt64(1, 1)
                            writeInt64(2, 1)
                        }
                    )
                }
            )
            writeByteArray(
                5,
                buildProto {
                    writeUInt64(1, 1)
                    writeInt64(2, 3)
                    writeInt64(3, 3)
                    writeInt64(4, 4)
                }
            )
            writeString(6, "")
            writeString(6, "samples")
            writeString(6, "count")
            writeString(6, "main")
            writeString(6, "main.go")
            writeInt64(14, 1)
        }

    private fun sampleHost(orgId: Int = TEST_ORG_ID) = DdHostInfo(
        id = HOST_RESOURCE_ID,
        internalId = 42,
        organizationId = orgId,
        hostname = TEST_HOST,
        os = "linux",
        platform = "ubuntu",
        processor = "x86_64",
        cpuCores = 4,
        memoryTotalKb = 8_000_000L,
        agentVersion = "7.52.0",
        tags = mapOf("env" to "prod"),
        firstSeenAt = "2024-01-01T00:00:00Z",
        lastSeenAt = "2024-06-01T12:00:00Z",
        isOnline = true,
    )
}

private fun buildProto(block: CodedOutputStream.() -> Unit): ByteArray {
    val buffer = ByteArray(PROTO_TEST_BUFFER_SIZE)
    val output = CodedOutputStream.newInstance(buffer)
    output.block()
    output.flush()
    return buffer.copyOf(output.totalBytesWritten)
}

private const val PROTO_TEST_BUFFER_SIZE = 512
