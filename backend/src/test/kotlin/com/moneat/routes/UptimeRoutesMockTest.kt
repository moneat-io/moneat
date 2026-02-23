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

import com.moneat.uptime.routes.uptimeRoutes
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.uptime.services.UptimeService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.uptime.models.UptimeMonitorResponse
import com.moneat.shared.models.Users
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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UptimeRoutesMockTest {
    companion object {
        private const val JWT_SECRET = "uptime-mock-secret"
        private var dbInitialized = false
    }

    private val mockUptimeService = mockk<UptimeService>(relaxed = true)

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_uptime_mock;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }

        // Ensure schema exists (idempotent in H2) and clean between tests
        transaction {
            SchemaUtils.drop(Memberships, Organizations, Users)
            SchemaUtils.create(Users, Organizations, Memberships)
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
                it[email] = "uptime-mock-${System.nanoTime()}@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            } get Users.id
        }

    private fun seedOrg(): Int =
        transaction {
            Organizations.insert {
                it[name] = "Uptime Mock Org"
                it[slug] = "uptime-mock-org-${System.nanoTime()}"
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

    private fun makeMonitorResponse(orgId: Int): UptimeMonitorResponse {
        val now = System.currentTimeMillis()
        return UptimeMonitorResponse(
            id = UUID.randomUUID().toString(),
            organizationId = orgId,
            name = "test-monitor",
            type = "http",
            active = true,
            url = "https://example.com",
            intervalSeconds = 60,
            timeoutSeconds = 30,
            retries = 3,
            retryIntervalSeconds = 10,
            status = "up",
            createdAt = now,
            updatedAt = now
        )
    }

    // ─── GET /monitors ────────────────────────────────────────────────────────

    @Test
    fun `GET monitors returns 200 with list`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val monitor = makeMonitorResponse(orgId)

            every { mockUptimeService.listMonitors(orgId) } returns listOf(monitor)

            application {
                installAuth()
                routing { uptimeRoutes(uptimeService = mockUptimeService) }
            }

            val response = client.get("/v1/uptime/monitors") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("test-monitor"))
        }

    // ─── POST /monitors ───────────────────────────────────────────────────────

    @Test
    fun `POST monitors returns 201 with created monitor`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val monitor = makeMonitorResponse(orgId)

            every { mockUptimeService.createMonitor(orgId, any()) } returns monitor

            application {
                installAuth()
                routing { uptimeRoutes(uptimeService = mockUptimeService) }
            }

            val response = client.post("/v1/uptime/monitors") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"test-monitor","type":"http","url":"https://example.com",""" +
                        """"intervalSeconds":60,"timeoutSeconds":30,"retries":3,"retryIntervalSeconds":10}"""
                )
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.bodyAsText().contains("test-monitor"))
        }

    // ─── GET /monitors/{id} ───────────────────────────────────────────────────

    @Test
    fun `GET monitor by id returns 200`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val monitor = makeMonitorResponse(orgId)
            val monitorId = UUID.fromString(monitor.id)

            every { mockUptimeService.getMonitor(monitorId, orgId) } returns monitor

            application {
                installAuth()
                routing { uptimeRoutes(uptimeService = mockUptimeService) }
            }

            val response = client.get("/v1/uptime/monitors/$monitorId") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("test-monitor"))
        }

    @Test
    fun `GET monitor by id returns 404 when not found`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val monitorId = UUID.randomUUID()

            every { mockUptimeService.getMonitor(monitorId, orgId) } returns null

            application {
                installAuth()
                routing { uptimeRoutes(uptimeService = mockUptimeService) }
            }

            val response = client.get("/v1/uptime/monitors/$monitorId") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // ─── PUT /monitors/{id} ───────────────────────────────────────────────────

    @Test
    fun `PUT monitor returns 200 when updated`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val monitor = makeMonitorResponse(orgId)
            val monitorId = UUID.fromString(monitor.id)

            every { mockUptimeService.updateMonitor(monitorId, orgId, any()) } returns monitor

            application {
                installAuth()
                routing { uptimeRoutes(uptimeService = mockUptimeService) }
            }

            val response = client.put("/v1/uptime/monitors/$monitorId") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"updated-monitor"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `PUT monitor returns 404 when not found`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val monitorId = UUID.randomUUID()

            every { mockUptimeService.updateMonitor(monitorId, orgId, any()) } returns null

            application {
                installAuth()
                routing { uptimeRoutes(uptimeService = mockUptimeService) }
            }

            val response = client.put("/v1/uptime/monitors/$monitorId") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"updated-monitor"}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // ─── DELETE /monitors/{id} ────────────────────────────────────────────────

    @Test
    fun `DELETE monitor returns 200 when deleted`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val monitorId = UUID.randomUUID()

            every { mockUptimeService.deleteMonitor(monitorId, orgId) } returns true

            application {
                installAuth()
                routing { uptimeRoutes(uptimeService = mockUptimeService) }
            }

            val response = client.delete("/v1/uptime/monitors/$monitorId") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `DELETE monitor returns 404 when not found`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val monitorId = UUID.randomUUID()

            every { mockUptimeService.deleteMonitor(monitorId, orgId) } returns false

            application {
                installAuth()
                routing { uptimeRoutes(uptimeService = mockUptimeService) }
            }

            val response = client.delete("/v1/uptime/monitors/$monitorId") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // ─── POST /monitors/{id}/pause ─────────────────────────────────────────────

    @Test
    fun `POST pause monitor returns 200`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val monitorId = UUID.randomUUID()

            every { mockUptimeService.pauseMonitor(monitorId, orgId) } returns true

            application {
                installAuth()
                routing { uptimeRoutes(uptimeService = mockUptimeService) }
            }

            val response = client.post("/v1/uptime/monitors/$monitorId/pause") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    // ─── POST /monitors/{id}/resume ────────────────────────────────────────────

    @Test
    fun `POST resume monitor returns 200`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val monitorId = UUID.randomUUID()

            every { mockUptimeService.resumeMonitor(monitorId, orgId) } returns true

            application {
                installAuth()
                routing { uptimeRoutes(uptimeService = mockUptimeService) }
            }

            val response = client.post("/v1/uptime/monitors/$monitorId/resume") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    // ─── GET /monitors/{id}/heartbeats ────────────────────────────────────────

    @Test
    fun `GET monitor heartbeats returns 200`() =
        testApplication {
            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val monitor = makeMonitorResponse(orgId)
            val monitorId = UUID.fromString(monitor.id)

            every { mockUptimeService.getMonitor(monitorId, orgId) } returns monitor
            coEvery { mockUptimeService.getHeartbeats(monitorId, any(), any()) } returns emptyList()

            application {
                installAuth()
                routing { uptimeRoutes(uptimeService = mockUptimeService) }
            }

            val response = client.get("/v1/uptime/monitors/$monitorId/heartbeats") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
}
