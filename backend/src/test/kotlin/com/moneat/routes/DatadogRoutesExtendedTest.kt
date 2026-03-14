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

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
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
import com.moneat.datadog.services.TelemetryProxyService
import com.moneat.datadog.services.TraceIngestionService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
        private const val JWT_SECRET = "dd-routes-test-secret"
        private const val TEST_ORG_ID = 1
        private const val TEST_API_KEY = "test-dd-api-key-abc123"
        private var db: Database? = null
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
        install(ContentNegotiation) { json() }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT.require(Algorithm.HMAC256(JWT_SECRET))
                        .withIssuer("moneat")
                        .withAudience("moneat-users")
                        .build()
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
    }

    private fun jwtToken(
        userId: Int = 1,
        orgId: Int = TEST_ORG_ID,
    ): String =
        JWT.create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .withClaim("orgId", orgId)
            .sign(Algorithm.HMAC256(JWT_SECRET))

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

    // ─── DatadogHostRoutes: Ingest ─────────────────────────────────────────

    @Test
    fun `POST dd api v1 metadata returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogHostIngestRoutes() }
        }
        val response = client.post("/dd/api/v1/metadata") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"hostname":"test-host","agent_version":"7.0"}""")
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
            val response = client.post("/dd/api/v1/metadata") {
                header("DD-API-KEY", TEST_API_KEY)
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
            header("DD-API-KEY", TEST_API_KEY)
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
            header("DD-API-KEY", TEST_API_KEY)
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
                header("DD-API-KEY", TEST_API_KEY)
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
            val response = client.post("/dd/api/v1/metadata") {
                contentType(ContentType.Application.Json)
                setBody("""{"hostname":"test"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // ─── DatadogHostRoutes: Query (JWT) ────────────────────────────────────

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
            header(HttpHeaders.Authorization, "Bearer ${jwtToken(userId, orgId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("test-host"))
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
            header(HttpHeaders.Authorization, "Bearer ${jwtToken(userId, orgId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("test-host"))
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
            header(HttpHeaders.Authorization, "Bearer ${jwtToken(userId, orgId)}")
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
            header(HttpHeaders.Authorization, "Bearer ${jwtToken(userId, orgId)}")
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
            header(HttpHeaders.Authorization, "Bearer ${jwtToken(userId, orgId)}")
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
            header(HttpHeaders.Authorization, "Bearer ${jwtToken(userId, orgId)}")
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

    // ─── DatadogMetricRoutes ───────────────────────────────────────────────

    @Test
    fun `POST dd api v1 series returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogMetricRoutes() }
        }
        val response = client.post("/dd/api/v1/series") {
            header("DD-API-KEY", TEST_API_KEY)
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
                routing { datadogMetricRoutes() }
            }
            val response = client.post("/dd/api/v1/series") {
                header("DD-API-KEY", TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("bad json")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST dd api v2 series returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogMetricRoutes() }
        }
        val response = client.post("/dd/api/v2/series") {
            header("DD-API-KEY", TEST_API_KEY)
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
            routing { datadogMetricRoutes() }
        }
        val response = client.post("/dd/api/v3/series") {
            header("DD-API-KEY", TEST_API_KEY)
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
                routing { datadogMetricRoutes() }
            }
            val response = client.post("/dd/api/v1/sketches") {
                header("DD-API-KEY", TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("")
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }

    @Test
    fun `POST dd api beta sketches returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogMetricRoutes() }
        }
        val response = client.post("/dd/api/beta/sketches") {
            header("DD-API-KEY", TEST_API_KEY)
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
                routing { datadogMetricRoutes() }
            }
            val response = client.post("/dd/api/v1/sketches") {
                header("DD-API-KEY", TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("""{"sketches":[]}""")
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }

    // ─── DatadogEventRoutes ────────────────────────────────────────────────

    @Test
    fun `POST dd api v1 check_run returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogEventRoutes() }
        }
        val response = client.post("/dd/api/v1/check_run") {
            header("DD-API-KEY", TEST_API_KEY)
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
                routing { datadogEventRoutes() }
            }
            val response = client.post("/dd/api/v1/check_run") {
                header("DD-API-KEY", TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("not json")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST dd api v2 events returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogEventRoutes() }
        }
        val response = client.post("/dd/api/v2/events") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"events":[{"title":"test","text":"hello"}]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 service_checks returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogEventRoutes() }
        }
        val response = client.post("/dd/api/v2/service_checks") {
            header("DD-API-KEY", TEST_API_KEY)
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
                routing { datadogEventRoutes() }
            }
            val response = client.post("/dd/api/v2/service_checks") {
                header("DD-API-KEY", TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("bad")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ─── DatadogLogRoutes ──────────────────────────────────────────────────

    @Test
    fun `POST dd api v2 logs returns 200`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogLogRoutes() }
        }
        val response = client.post("/dd/api/v2/logs") {
            header("DD-API-KEY", TEST_API_KEY)
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
            routing { datadogLogRoutes() }
        }
        val response = client.post("/dd/api/v2/logs") {
            header("DD-API-KEY", TEST_API_KEY)
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
                routing { datadogLogRoutes() }
            }
            val response = client.post("/dd/api/v2/logs") {
                header("DD-API-KEY", TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("not json at all")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ─── DatadogDogStatsDRoutes ────────────────────────────────────────────

    @Test
    fun `POST dd dogstatsd v2 proxy returns 200`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogDogStatsDRoutes() }
        }
        val response = client.post("/dd/dogstatsd/v2/proxy") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.OctetStream)
            setBody("cpu.usage:42.5|g|#env:prod,host:h1\nmem.used:1024|c")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST dd dogstatsd v2 proxy handles empty body`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogDogStatsDRoutes() }
        }
        val response = client.post("/dd/dogstatsd/v2/proxy") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.OctetStream)
            setBody("")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ─── DatadogValidateRoutes ─────────────────────────────────────────────

    @Test
    fun `GET dd api v1 validate returns 200 with orgId`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogValidateRoutes() }
        }
        val response = client.get("/dd/api/v1/validate") {
            header("DD-API-KEY", TEST_API_KEY)
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

    // ─── MiscIngestRoutes ──────────────────────────────────────────────────

    @Test
    fun `POST dd symdb v1 input returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes() }
        }
        val response = client.post("/dd/symdb/v1/input") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"service":"web","symbols":""}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 data_streams returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes() }
        }
        val response = client.post("/dd/api/v2/data_streams") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"entries":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 contimage returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes() }
        }
        val response = client.post("/dd/api/v2/contimage") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"images":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 sbom returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes() }
        }
        val response = client.post("/dd/api/v2/sbom") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"packages":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd v0 1 pipeline_stats returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes() }
        }
        val response = client.post("/dd/v0.1/pipeline_stats") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"stats":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v1 lineage returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes() }
        }
        val response = client.post("/dd/api/v1/lineage") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"nodes":[],"edges":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 synthetics returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes() }
        }
        val response = client.post("/dd/api/v2/synthetics") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"results":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST api v2 contlcycle returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes() }
        }
        val response = client.post("/api/v2/contlcycle") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST api v2 events management returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { miscIngestRoutes() }
        }
        val response = client.post("/api/v2/events") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ─── DatadogInfraRoutes ────────────────────────────────────────────────

    @Test
    fun `POST api v1 discovery returns 200`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogInfraRoutes() }
        }
        val response = client.post("/api/v1/discovery") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.OctetStream)
            setBody(ByteArray(0))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST dd api v1 connections returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { datadogInfraRoutes() }
        }
        val response = client.post("/dd/api/v1/connections") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.OctetStream)
            setBody(ByteArray(0))
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ─── DbmIngestRoutes ──────────────────────────────────────────────────

    @Test
    fun `POST dd api v2 databasequery returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { dbmIngestRoutes() }
        }
        val response = client.post("/dd/api/v2/databasequery") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"rows":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 dbmmetrics returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { dbmIngestRoutes() }
        }
        val response = client.post("/dd/api/v2/dbmmetrics") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"rows":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 dbmactivity returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { dbmIngestRoutes() }
        }
        val response = client.post("/dd/api/v2/dbmactivity") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"rows":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 dbmmetadata returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { dbmIngestRoutes() }
        }
        val response = client.post("/dd/api/v2/dbmmetadata") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"entries":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 dbmhealth returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { dbmIngestRoutes() }
        }
        val response = client.post("/dd/api/v2/dbmhealth") {
            header("DD-API-KEY", TEST_API_KEY)
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
                routing { dbmIngestRoutes() }
            }
            val response = client.post("/api/v2/databasequery") {
                header("DD-API-KEY", TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("""{"rows":[]}""")
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }

    @Test
    fun `POST dd api v2 databasequery returns 400 for bad payload`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { dbmIngestRoutes() }
            }
            val response = client.post("/dd/api/v2/databasequery") {
                header("DD-API-KEY", TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("invalid")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ─── OrchestratorIngestRoutes ──────────────────────────────────────────

    @Test
    fun `POST dd api v2 orch returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { orchestratorIngestRoutes() }
        }
        val response = client.post("/dd/api/v2/orch") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"resources":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd api v2 orchmanif returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { orchestratorIngestRoutes() }
        }
        val response = client.post("/dd/api/v2/orchmanif") {
            header("DD-API-KEY", TEST_API_KEY)
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
                routing { orchestratorIngestRoutes() }
            }
            val response = client.post("/dd/api/v2/orch") {
                header("DD-API-KEY", TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("bad")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ─── DebuggerIngestRoutes ──────────────────────────────────────────────

    @Test
    fun `POST dd debugger v1 input returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { debuggerIngestRoutes() }
        }
        val response = client.post("/dd/debugger/v1/input") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""[{"probe_id":"p1","message":"snap"}]""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd debugger v2 input returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { debuggerIngestRoutes() }
        }
        val response = client.post("/dd/debugger/v2/input") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""[{"probe_id":"p2","message":"v2 snap"}]""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `POST dd debugger v1 diagnostics returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { debuggerIngestRoutes() }
        }
        val response = client.post("/dd/debugger/v1/diagnostics") {
            header("DD-API-KEY", TEST_API_KEY)
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
                routing { debuggerIngestRoutes() }
            }
            val response = client.post("/dd/debugger/v1/input") {
                header("DD-API-KEY", TEST_API_KEY)
                contentType(ContentType.Application.Json)
                setBody("invalid")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ─── TelemetryProxyRoutes ──────────────────────────────────────────────

    @Test
    fun `POST dd telemetry proxy returns 202`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { telemetryProxyRoutes() }
        }
        val response = client.post("/dd/telemetry/proxy/api/v2/apmtelemetry") {
            header("DD-API-KEY", TEST_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"payload":"test"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ─── DatadogRoutes (API Key Management) ────────────────────────────────

    @Test
    fun `GET v1 agent-api-keys returns 200`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        every { DatadogService.listApiKeys(orgId) } returns emptyList()
        application {
            installAuth()
            datadogRoutes()
        }
        val response = client.get("/v1/agent-api-keys") {
            header(HttpHeaders.Authorization, "Bearer ${jwtToken(userId, orgId)}")
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
                key = "magt_testkey12345678901234567890",
                keyPrefix = "magt_testke",
            )
        application {
            installAuth()
            datadogRoutes()
        }
        val response = client.post("/v1/agent-api-keys") {
            header(HttpHeaders.Authorization, "Bearer ${jwtToken(userId, orgId)}")
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
        val response = client.delete("/v1/agent-api-keys/42") {
            header(HttpHeaders.Authorization, "Bearer ${jwtToken(userId, orgId)}")
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
            val response = client.delete("/v1/agent-api-keys/999") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${jwtToken(userId, orgId)}"
                )
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET v1 agent-api-keys returns 401 without JWT`() = testApplication {
        application {
            installAuth()
            datadogRoutes()
        }
        val response = client.get("/v1/agent-api-keys")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ─── TraceDashboardRoutes (JWT) ────────────────────────────────────────

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
            header(HttpHeaders.Authorization, "Bearer ${jwtToken(userId, orgId)}")
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
            header(HttpHeaders.Authorization, "Bearer ${jwtToken(userId, orgId)}")
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
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${jwtToken(userId, orgId)}"
                )
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
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${jwtToken(userId, orgId)}"
                )
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
            header(HttpHeaders.Authorization, "Bearer ${jwtToken(userId, orgId)}")
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
            header(HttpHeaders.Authorization, "Bearer ${jwtToken(userId, orgId)}")
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

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun sampleHost(orgId: Int = TEST_ORG_ID) = DdHostInfo(
        id = 42,
        organizationId = orgId,
        hostname = "test-host",
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
