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

import com.moneat.billing.models.BillingUsageResponse
import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.QuotaReservationResult
import com.moneat.config.ClickHouseClient
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.models.DdApiKeys
import com.moneat.datadog.models.DdApmErrorsResponse
import com.moneat.datadog.models.DdResourceStatsResponse
import com.moneat.datadog.models.DdServiceMapResponse
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
import com.moneat.datadog.services.DebuggerIngestionService
import com.moneat.datadog.services.MiscIngestionService
import com.moneat.datadog.services.OrchestratorIngestionService
import com.moneat.datadog.services.QueuedServiceCheckBatch
import com.moneat.datadog.services.QueuedServiceCheckEntry
import com.moneat.datadog.services.TelemetryProxyService
import com.moneat.datadog.services.TraceIngestionService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatadogRoutesExtendedTest {

    companion object {
        private const val TEST_ORG_ID = 1
        private const val TEST_API_KEY = "TEST_API_KEY_PLACEHOLDER"
        private const val DD_API_KEY_HEADER = "DD-API-KEY"
        private const val DD_METADATA_PATH = "/dd/api/v1/metadata"
        private const val TEST_HOST = "test-host"
        private const val DD_LOGS_PATH = "/dd/api/v2/logs"
        private const val EMPTY_ROWS_JSON = """{"rows":[]}"""
        private const val AGENT_API_KEYS_PATH = "/v1/agent-api-keys"
        private var db: Database? = null
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

        mockkObject(DatadogAuthMiddleware)
        mockkObject(DatadogHostService)
        mockkObject(DatadogMetricService)
        mockkObject(DatadogEventService)
        mockkObject(DatadogLogService)
        mockkObject(DatadogInfraService)
        mockkObject(MiscIngestionService)
        mockkObject(DbmIngestionService)
        mockkObject(OrchestratorIngestionService)
        mockkObject(DebuggerIngestionService)
        mockkObject(TelemetryProxyService)
        mockkObject(DatadogService)
        mockkObject(TraceIngestionService)
        mockkObject(ClickHouseClient)

        coEvery {
            DatadogAuthMiddleware.authenticate(any())
        } returns TEST_ORG_ID

        // Default stubs for ingest services
        every { DatadogHostService.upsertFromMetadata(any(), any()) } just Runs
        every { DatadogHostService.upsertFromIntake(any(), any()) } just Runs
        every { DatadogHostService.touchHostLastSeen(any(), any()) } just Runs
        coEvery { DatadogMetricService.enqueueMetrics(any(), any()) } returns 1
        every { DatadogMetricService.mapSketches(any(), any()) } returns
            com.moneat.datadog.services.QueuedSketchBatch(1L, emptyList())
        coEvery { DatadogMetricService.insertSketchBatch(any()) } just Runs
        every { DatadogEventService.mapServiceChecks(any(), any()) } returns
            com.moneat.datadog.services.QueuedServiceCheckBatch(1L, emptyList())
        coEvery { DatadogEventService.insertServiceCheckBatch(any()) } just Runs
        coEvery { DatadogEventService.enqueueEvents(any(), any()) } returns 1
        coEvery { DatadogLogService.enqueueLogs(any(), any()) } returns 1
        every { MiscIngestionService.enqueueSymbolDb(any(), any()) } just Runs
        every { MiscIngestionService.enqueuePipelineStats(any(), any()) } returns 1
        every { MiscIngestionService.enqueueDataLineage(any(), any()) } just Runs
        every { MiscIngestionService.enqueueDataStreams(any(), any()) } returns 1
        every { MiscIngestionService.enqueueSynthetics(any(), any()) } returns 1
        every { MiscIngestionService.enqueueContainerImage(any(), any()) } just Runs
        every { MiscIngestionService.enqueueSbom(any(), any()) } returns 1
        every { DbmIngestionService.enqueueQueries(any(), any()) } returns 1
        every { DbmIngestionService.enqueueMetrics(any(), any()) } returns 1
        every { DbmIngestionService.enqueueActivity(any(), any()) } returns 1
        every { DbmIngestionService.enqueueMetadata(any(), any()) } returns 1
        every { DbmIngestionService.enqueueHealth(any(), any()) } returns 1
        every { OrchestratorIngestionService.enqueueResources(any(), any()) } returns 1
        every { OrchestratorIngestionService.enqueueManifests(any(), any()) } returns 1
        every { DebuggerIngestionService.enqueueDebuggerLogs(any(), any()) } returns 1
        every { DebuggerIngestionService.enqueueDiagnostics(any(), any()) } returns 1
        every { TelemetryProxyService.acknowledge(any(), any(), any()) } just Runs
    }

    @AfterTest
    fun teardown() {
        unmockkObject(DatadogAuthMiddleware)
        unmockkObject(DatadogHostService)
        unmockkObject(DatadogMetricService)
        unmockkObject(DatadogEventService)
        unmockkObject(DatadogLogService)
        unmockkObject(DatadogInfraService)
        unmockkObject(MiscIngestionService)
        unmockkObject(DbmIngestionService)
        unmockkObject(OrchestratorIngestionService)
        unmockkObject(DebuggerIngestionService)
        unmockkObject(TelemetryProxyService)
        unmockkObject(DatadogService)
        unmockkObject(TraceIngestionService)
        unmockkObject(ClickHouseClient)
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
            organizationId = TEST_ORG_ID,
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
        every { DatadogHostService.getHost(orgId, 42) } returns sampleHost(orgId)
        application {
            installAuth()
            routing { datadogHostQueryRoutes() }
        }
        val response = client.get("/v1/hosts/42") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(TEST_HOST))
    }

    @Test
    fun `GET v1 hosts hostId returns 404 when missing`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        every { DatadogHostService.getHost(orgId, 999) } returns null
        application {
            installAuth()
            routing { datadogHostQueryRoutes() }
        }
        val response = client.get("/v1/hosts/999") {
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
        every { DatadogHostService.deleteHost(orgId, 42) } returns true
        application {
            installAuth()
            routing { datadogHostQueryRoutes() }
        }
        val response = client.delete("/v1/hosts/42") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `DELETE v1 hosts hostId returns 404 when missing`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        every { DatadogHostService.deleteHost(orgId, 99) } returns false
        application {
            installAuth()
            routing { datadogHostQueryRoutes() }
        }
        val response = client.delete("/v1/hosts/99") {
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
        verify { DatadogHostService.touchHostLastSeen(TEST_ORG_ID, setOf("h1")) }
        coVerify(exactly = 0) { DatadogMetricService.enqueueMetrics(any(), any()) }
    }

    @Test
    fun `POST dd service checks touches host before quota rejection`() = testApplication {
        val quotaService = rejectingQuotaService()
        val body = """{"service_checks":[{"check":"disk","host_name":"h1","status":0}]}"""
        every {
            DatadogEventService.mapServiceChecks(any(), any())
        } returns QueuedServiceCheckBatch(
            organizationId = TEST_ORG_ID.toLong(),
            serviceChecks = listOf(
                QueuedServiceCheckEntry(
                    checkName = "disk",
                    host = "h1",
                    timestampMs = 0L
                )
            )
        )

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
        verify { DatadogHostService.touchHostLastSeen(TEST_ORG_ID, setOf("h1")) }
        coVerify(exactly = 0) { DatadogEventService.insertServiceCheckBatch(any()) }
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
        assertTrue(response.bodyAsText().contains("valid"))
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

    // ──── DatadogInfraRoutes ────

    @Test
    fun `POST api v1 discovery returns 200`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogInfraRoutes(allowingQuotaService) }
        }
        val response = client.post("/api/v1/discovery") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.OctetStream)
            setBody(ByteArray(0))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST dd api v1 connections returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogInfraRoutes(allowingQuotaService) }
        }
        val response = client.post("/dd/api/v1/connections") {
            header(DD_API_KEY_HEADER, TEST_API_KEY)
            contentType(ContentType.Application.OctetStream)
            setBody(ByteArray(0))
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
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
                id = 1,
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
        every { DatadogService.deleteApiKey(42, orgId) } returns true
        application {
            installAuth()
            datadogRoutes()
        }
        val response = client.delete("$AGENT_API_KEYS_PATH/42") {
            withAuth(jwtToken(userId, orgId))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `DELETE v1 agent-api-keys returns 404 for missing key`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { DatadogService.deleteApiKey(999, orgId) } returns false
            application {
                installAuth()
                datadogRoutes()
            }
            val response = client.delete("$AGENT_API_KEYS_PATH/999") {
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
            TraceIngestionService.listResourceStats(orgId, null, any(), any())
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
            TraceIngestionService.listTraces(orgId, null, null, any(), any())
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
            TraceIngestionService.getServiceMap(orgId)
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
    fun `GET v1 apm-errors returns 200`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        coEvery {
            TraceIngestionService.getApmErrors(orgId, null, any(), any())
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
    fun `GET v1 traces returns 401 without JWT`() = testApplication {
        application {
            installAuth()
            routing { traceDashboardRoutes() }
        }
        val response = client.get("/v1/traces")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ──── Helpers ────

    private fun sampleHost(orgId: Int = TEST_ORG_ID) = DdHostInfo(
        id = 42,
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
