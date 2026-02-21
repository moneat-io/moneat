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
import com.moneat.models.AlertConfigResponse
import com.moneat.models.AlertResponse
import com.moneat.models.ContainerStats
import com.moneat.models.HistoricalMetricsResponse
import com.moneat.models.LatestMetrics
import com.moneat.models.LogQueryResponse
import com.moneat.models.Memberships
import com.moneat.models.Organizations
import com.moneat.models.SystemData
import com.moneat.models.Systems
import com.moneat.models.Users
import com.moneat.services.BillingQuotaService
import com.moneat.services.LogService
import com.moneat.services.MonitorService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class MonitorRoutesMockTest {
    companion object {
        private const val JWT_SECRET = "monitor-mock-secret"
        private var dbInitialized = false
    }

    private val mockMonitorService = mockk<MonitorService>(relaxed = true)
    private val mockLogService = mockk<LogService>(relaxed = true)
    private val mockQuotaService = mockk<BillingQuotaService>(relaxed = true)

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_monitor_mock;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(Users, Organizations, Memberships, Systems)
            }
            dbInitialized = true
        }
        transaction {
            Memberships.deleteAll()
            Users.deleteAll()
            Systems.deleteAll()
            Organizations.deleteAll()
        }
    }

    private fun Application.installAuth() {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT.require(Algorithm.HMAC256(JWT_SECRET))
                        .withIssuer("moneat").withAudience("moneat-users").build()
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
    }

    private fun token(userId: Int): String =
        JWT.create().withIssuer("moneat").withAudience("moneat-users")
            .withClaim("userId", userId).sign(Algorithm.HMAC256(JWT_SECRET))

    private fun seedUser(): Int =
        transaction {
            Users.insert {
                it[email] = "mock-monitor-${System.nanoTime()}@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            } get Users.id
        }

    private fun seedOrg(): Int =
        transaction {
            Organizations.insert {
                it[name] = "Mock Monitor Org"
                it[slug] = "mock-monitor-org-${System.nanoTime()}"
            } get Organizations.id
        }

    private fun seedMembership(userId: Int, orgId: Int) {
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
        }
    }

    private fun makeSystemData(organizationId: Int): SystemData {
        val now = Clock.System.now()
        return SystemData(
            id = UUID.randomUUID(),
            organizationId = organizationId,
            name = "test-system",
            host = "localhost",
            agentKeyHash = "hash",
            status = "online",
            lastSeenAt = now,
            agentVersion = "1.0.0",
            os = "linux",
            arch = "amd64",
            createdAt = now,
            updatedAt = now
        )
    }

    // ─── GET /systems ─────────────────────────────────────────────────────────

    @Test
    fun `GET systems returns 200 with system list`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)

            every { mockMonitorService.listSystems(orgId) } returns listOf(system)
            coEvery { mockMonitorService.getLatestMetrics(system.id) } returns null

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.get("/v1/monitor/systems") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("test-system"))
        }

    // ─── POST /systems ─────────────────────────────────────────────────────────

    @Test
    fun `POST systems returns 201 with created system`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)

            every { mockMonitorService.checkSystemQuota(orgId) } returns true
            every { mockMonitorService.createSystem(orgId, "new-system") } returns Pair(system, "agent-key-123")

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.post("/v1/monitor/systems") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"new-system"}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.bodyAsText().contains("agent-key-123"))
        }

    @Test
    fun `POST systems returns 403 when quota exceeded`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)

            every { mockMonitorService.checkSystemQuota(orgId) } returns false

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.post("/v1/monitor/systems") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"new-system"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // ─── GET /systems/{id} ────────────────────────────────────────────────────

    @Test
    fun `GET systems by id returns 200 with system details`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)

            every { mockMonitorService.getSystemById(system.id) } returns system
            coEvery { mockMonitorService.getLatestMetrics(system.id) } returns null

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.get("/v1/monitor/systems/${system.id}") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("test-system"))
        }

    @Test
    fun `GET systems by id returns 404 when not found`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val systemId = UUID.randomUUID()

            every { mockMonitorService.getSystemById(systemId) } returns null

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.get("/v1/monitor/systems/$systemId") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // ─── DELETE /systems/{id} ─────────────────────────────────────────────────

    @Test
    fun `DELETE systems by id returns 204 on success`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)

            every { mockMonitorService.getSystemById(system.id) } returns system
            every { mockMonitorService.deleteSystem(system.id, orgId) } returns true

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.delete("/v1/monitor/systems/${system.id}") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    // ─── GET /systems/{id}/metrics ─────────────────────────────────────────────

    @Test
    fun `GET systems metrics returns 200`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)
            val metricsResponse = HistoricalMetricsResponse(
                system_id = system.id.toString(),
                from = 1000L,
                to = 2000L,
                interval_seconds = 60,
                data_points = emptyList()
            )

            every { mockMonitorService.getSystemById(system.id) } returns system
            coEvery { mockMonitorService.getHistoricalMetrics(system.id, 1000L, 2000L, null) } returns metricsResponse

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.get("/v1/monitor/systems/${system.id}/metrics?from=1000&to=2000") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    // ─── GET /systems/{id}/containers ─────────────────────────────────────────

    @Test
    fun `GET systems containers returns 200`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)
            val container = ContainerStats(
                name = "my-container",
                id = "abc123",
                image = "nginx:latest",
                status = "running",
                cpu_percent = 1.5f,
                mem_used = 100L,
                mem_limit = 512L,
                net_recv_bytes = 0L,
                net_sent_bytes = 0L,
                mem_percent = 0.2f
            )

            every { mockMonitorService.getSystemById(system.id) } returns system
            coEvery { mockMonitorService.getLatestContainers(system.id) } returns listOf(container)

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.get("/v1/monitor/systems/${system.id}/containers") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("my-container"))
        }

    // ─── GET /systems/{id}/logs ────────────────────────────────────────────────

    @Test
    fun `GET systems logs returns 200`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)
            val logResponse = LogQueryResponse(logs = emptyList(), hasMore = false, totalCount = 0L)

            every { mockMonitorService.getSystemById(system.id) } returns system
            coEvery { mockLogService.queryLogs(0L, any()) } returns logResponse

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.get("/v1/monitor/systems/${system.id}/logs") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    // ─── GET /systems/{id}/alerts ─────────────────────────────────────────────

    @Test
    fun `GET systems alerts returns 200`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)
            val alert = AlertResponse(
                id = 1,
                systemId = system.id.toString(),
                scope = "system",
                metric = "cpu_percent",
                condition = "gt",
                threshold = 90.0,
                durationSeconds = 60,
                enabled = true,
                lastTriggeredAt = null,
                createdAt = System.currentTimeMillis()
            )

            every { mockMonitorService.getSystemById(system.id) } returns system
            every { mockMonitorService.listAlerts(system.id) } returns listOf(alert)

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.get("/v1/monitor/systems/${system.id}/alerts") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("cpu_percent"))
        }

    // ─── GET /systems/{id}/alerts/config ──────────────────────────────────────

    @Test
    fun `GET systems alert config returns 200`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)
            val config = AlertConfigResponse(
                scope = "global",
                globalAlerts = emptyList(),
                systemAlerts = emptyList(),
                effectiveAlerts = emptyList()
            )

            every { mockMonitorService.getSystemById(system.id) } returns system
            every { mockMonitorService.getAlertConfig(system.id, orgId) } returns config

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.get("/v1/monitor/systems/${system.id}/alerts/config") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("global"))
        }

    // ─── Error path: bad system UUID ──────────────────────────────────────────

    @Test
    fun `GET systems by invalid UUID returns 400`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.get("/v1/monitor/systems/not-a-uuid") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ─── PUT /systems/{id}/alerts/scope ───────────────────────────────────────

    @Test
    fun `PUT systems alert scope returns 204`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)

            every { mockMonitorService.getSystemById(system.id) } returns system
            every { mockMonitorService.updateAlertScope(system.id, orgId, "system") } returns true

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.put("/v1/monitor/systems/${system.id}/alerts/scope") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"scope":"system"}""")
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `PUT systems alert scope returns 400 for invalid scope`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)

            every { mockMonitorService.getSystemById(system.id) } returns system

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.put("/v1/monitor/systems/${system.id}/alerts/scope") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"scope":"invalid"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ─── POST /systems/{id}/alerts ────────────────────────────────────────────

    @Test
    fun `POST systems alerts returns 201 with alert`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)
            val alert = AlertResponse(
                id = 1,
                systemId = system.id.toString(),
                scope = "system",
                metric = "mem_percent",
                condition = "gt",
                threshold = 80.0,
                durationSeconds = 60,
                enabled = true,
                lastTriggeredAt = null,
                createdAt = System.currentTimeMillis()
            )

            every { mockMonitorService.getSystemById(system.id) } returns system
            every { mockMonitorService.createAlert(system.id, orgId, any(), "system") } returns alert

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.post("/v1/monitor/systems/${system.id}/alerts") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"metric":"mem_percent","condition":"gt","threshold":80.0,"duration_seconds":60}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.bodyAsText().contains("mem_percent"))
        }

    // ─── PUT /systems/{systemId}/alerts/{alertId} ─────────────────────────────

    @Test
    fun `PUT alert returns 204 when updated`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)

            every { mockMonitorService.getSystemById(system.id) } returns system
            every { mockMonitorService.updateAlert(1, system.id, orgId, any(), "system") } returns true

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.put("/v1/monitor/systems/${system.id}/alerts/1") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":false}""")
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `PUT alert returns 404 when not found`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)

            every { mockMonitorService.getSystemById(system.id) } returns system
            every { mockMonitorService.updateAlert(99, system.id, orgId, any(), "system") } returns false

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.put("/v1/monitor/systems/${system.id}/alerts/99") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":false}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // ─── DELETE /systems/{systemId}/alerts/{alertId} ──────────────────────────

    @Test
    fun `DELETE alert returns 204 when deleted`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)

            every { mockMonitorService.getSystemById(system.id) } returns system
            every { mockMonitorService.deleteAlert(1, system.id, orgId, "system") } returns true

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.delete("/v1/monitor/systems/${system.id}/alerts/1") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    // ─── GET /systems/{id}/containers/{name}/metrics ───────────────────────────

    @Test
    fun `GET container metrics returns 200`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val system = makeSystemData(orgId)
            val containerMetrics = com.moneat.models.ContainerMetricsResponse(
                container_name = "my-container",
                from = 1000L,
                to = 2000L,
                interval_seconds = 60,
                data_points = emptyList()
            )

            every { mockMonitorService.getSystemById(system.id) } returns system
            coEvery {
                mockMonitorService.getContainerHistoricalMetrics(system.id, "my-container", 1000L, 2000L, null)
            } returns containerMetrics

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.get(
                "/v1/monitor/systems/${system.id}/containers/my-container/metrics?from=1000&to=2000"
            ) {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    // ─── POST /ingest (agent-facing) ──────────────────────────────────────────

    @Test
    fun `POST ingest returns 401 when no auth header`() =
        testApplication {
            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.post("/v1/monitor/ingest") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `POST ingest returns 401 for invalid agent key`() =
        testApplication {
            every { mockMonitorService.validateAgentKey(any()) } returns null

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.post("/v1/monitor/ingest") {
                header(HttpHeaders.Authorization, "Bearer invalid-key")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // ─── POST /logs (agent-facing) ────────────────────────────────────────────

    @Test
    fun `POST logs returns 401 when no auth header`() =
        testApplication {
            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.post("/v1/monitor/logs") {
                contentType(ContentType.Application.Json)
                setBody("""{"logs":[]}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `POST logs returns 401 for invalid agent key`() =
        testApplication {
            every { mockMonitorService.validateAgentKey(any()) } returns null

            application {
                installAuth()
                routing {
                    monitorRoutes(
                        monitorService = mockMonitorService,
                        logService = mockLogService,
                        quotaService = mockQuotaService
                    )
                }
            }

            val response = client.post("/v1/monitor/logs") {
                header(HttpHeaders.Authorization, "Bearer invalid-key")
                contentType(ContentType.Application.Json)
                setBody("""{"logs":[]}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
