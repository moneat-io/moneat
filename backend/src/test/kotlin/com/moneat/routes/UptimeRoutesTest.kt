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
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import com.moneat.uptime.models.UptimeMonitors
import com.moneat.uptime.routes.uptimeRoutes
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class UptimeRoutesTest {
    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        startTestKoin()
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_uptime_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, UptimeMonitors)
    }

    private fun Application.installAuth() {
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT
                        .require(Algorithm.HMAC256("test-secret"))
                        .withIssuer("moneat")
                        .withAudience("moneat-users")
                        .build()
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
    }

    private fun token(userId: Int): String =
        JWT
            .create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .sign(Algorithm.HMAC256("test-secret"))

    private fun seedUser(): Int =
        transaction {
            Users.insert {
                it[email] = "uptime-test-${System.nanoTime()}@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            } get Users.id
        }

    private fun seedOrgWithMembership(userId: Int): Int {
        val orgId =
            transaction {
                Organizations.insert {
                    it[name] = "Uptime Test Org"
                    it[slug] = "uptime-test-org-${System.nanoTime()}"
                } get Organizations.id
            }
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
        }
        return orgId
    }

    private fun seedPushMonitor(token: String = "token-1234"): UUID {
        val orgId =
            transaction {
                Organizations.insert {
                    it[name] = "Uptime Routes Org"
                    it[slug] = "uptime-routes-org"
                } get Organizations.id
            }

        val monitorId = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            UptimeMonitors.insert {
                it[id] = monitorId
                it[organizationId] = orgId
                it[name] = "Push monitor"
                it[type] = "push"
                it[active] = true
                it[method] = "GET"
                it[maxRedirects] = 10
                it[ignoreTls] = false
                it[keywordInverse] = false
                it[sslExpiryWarnDays] = 30
                it[intervalSeconds] = 60
                it[timeoutSeconds] = 10
                it[retries] = 0
                it[retryIntervalSeconds] = 60
                it[status] = "pending"
                it[lastStatusChangeAt] = now
                it[consecutiveFailures] = 0
                it[pushToken] = token
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        return monitorId
    }

    @Test
    fun `push endpoint returns not found for invalid token`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val response = client.post("/v1/uptime/push/does-not-exist")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `push endpoint records heartbeat and updates monitor status`() =
        testApplication {
            val monitorId = seedPushMonitor(token = "ok-token")

            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val response =
                client.post("/v1/uptime/push/ok-token") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"0","msg":"probe failed","ping":45}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("true", body["ok"]?.jsonPrimitive?.content)

            val status =
                transaction {
                    UptimeMonitors.selectAll().first { it[UptimeMonitors.id] == monitorId }[UptimeMonitors.status]
                }
            assertEquals("down", status)
        }

    // ──── Authenticated endpoints: no org membership → 403 ────

    @Test
    fun `list monitors returns 403 when user has no organization`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            val response =
                client.get("/v1/uptime/monitors") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `create monitor returns 403 when user has no organization`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            val response =
                client.post("/v1/uptime/monitors") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"name":"test","type":"http","url":"https://example.com",""" +
                            """"intervalSeconds":60,"timeoutSeconds":30}"""
                    )
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `get monitor returns 403 when user has no organization`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            val response =
                client.get("/v1/uptime/monitors/${UUID.randomUUID()}") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `update monitor returns 403 when user has no organization`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            val response =
                client.put("/v1/uptime/monitors/${UUID.randomUUID()}") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"updated"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `delete monitor returns 403 when user has no organization`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            val response =
                client.delete("/v1/uptime/monitors/${UUID.randomUUID()}") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `pause monitor returns 403 when user has no organization`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            val response =
                client.post("/v1/uptime/monitors/${UUID.randomUUID()}/pause") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `resume monitor returns 403 when user has no organization`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            val response =
                client.post("/v1/uptime/monitors/${UUID.randomUUID()}/resume") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `heartbeats returns 403 when user has no organization`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            val response =
                client.get("/v1/uptime/monitors/${UUID.randomUUID()}/heartbeats") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // ──── Authenticated endpoints: invalid UUID → 400 ────

    @Test
    fun `get monitor returns 400 for invalid monitor id`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            seedOrgWithMembership(userId)
            val response =
                client.get("/v1/uptime/monitors/not-a-uuid") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `update monitor returns 400 for invalid monitor id`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            seedOrgWithMembership(userId)
            val response =
                client.put("/v1/uptime/monitors/not-a-uuid") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"updated"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `delete monitor returns 400 for invalid monitor id`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            seedOrgWithMembership(userId)
            val response =
                client.delete("/v1/uptime/monitors/not-a-uuid") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `pause monitor returns 400 for invalid monitor id`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            seedOrgWithMembership(userId)
            val response =
                client.post("/v1/uptime/monitors/not-a-uuid/pause") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `resume monitor returns 400 for invalid monitor id`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            seedOrgWithMembership(userId)
            val response =
                client.post("/v1/uptime/monitors/not-a-uuid/resume") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `heartbeats returns 400 for invalid monitor id`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            seedOrgWithMembership(userId)
            val response =
                client.get("/v1/uptime/monitors/not-a-uuid/heartbeats") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ──── Monitor request validation ────

    @Test
    fun `create monitor returns 400 for blank name`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            seedOrgWithMembership(userId)
            val response =
                client.post("/v1/uptime/monitors") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"name":"","type":"http","url":"https://example.com",""" +
                            """"intervalSeconds":60,"timeoutSeconds":30}"""
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("name is required"))
        }

    @Test
    fun `create monitor returns 400 for http type without url`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            seedOrgWithMembership(userId)
            val response =
                client.post("/v1/uptime/monitors") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"my-monitor","type":"http","intervalSeconds":60,"timeoutSeconds":30}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("URL is required"))
        }

    @Test
    fun `create monitor returns 400 for tcp type without hostname`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            seedOrgWithMembership(userId)
            val response =
                client.post("/v1/uptime/monitors") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"name":"my-monitor","type":"tcp","port":443,"intervalSeconds":60,"timeoutSeconds":30}"""
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Hostname is required"))
        }

    @Test
    fun `create monitor returns 400 for tcp internal hostname`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            seedOrgWithMembership(userId)
            withSelfHosted("false") {
                val response =
                    client.post("/v1/uptime/monitors") {
                        header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"name":"my-monitor","type":"tcp","hostname":"127.0.0.1","port":443,""" +
                                """"intervalSeconds":60,"timeoutSeconds":30}"""
                        )
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertTrue(response.bodyAsText().contains("Blocked"))
            }
        }

    @Test
    fun `create monitor returns 400 for interval less than 10 seconds`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            seedOrgWithMembership(userId)
            val response =
                client.post("/v1/uptime/monitors") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"name":"my-monitor","type":"http","url":"https://example.com",""" +
                            """"intervalSeconds":5,"timeoutSeconds":30}"""
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("at least 10 seconds"))
        }

    @Test
    fun `create monitor returns 400 for unknown monitor type`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            seedOrgWithMembership(userId)
            val response =
                client.post("/v1/uptime/monitors") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"my-monitor","type":"unknown-type","intervalSeconds":60,"timeoutSeconds":30}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Unknown monitor type"))
        }

    // ──── Authenticated endpoints: not found ────

    @Test
    fun `get monitor returns 404 for non-existent monitor`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            seedOrgWithMembership(userId)
            val response =
                client.get("/v1/uptime/monitors/${UUID.randomUUID()}") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `delete monitor returns 404 for non-existent monitor`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            seedOrgWithMembership(userId)
            val response =
                client.delete("/v1/uptime/monitors/${UUID.randomUUID()}") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `list monitors returns 200 with empty list for org with no monitors`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { uptimeRoutes() }
            }

            val userId = seedUser()
            seedOrgWithMembership(userId)
            val response =
                client.get("/v1/uptime/monitors") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @AfterTest
    fun teardownKoin() {
        stopTestKoin()
    }

    private suspend fun <T> withSelfHosted(value: String, block: suspend () -> T): T {
        val previous = System.getProperty("SELF_HOSTED")
        System.setProperty("SELF_HOSTED", value)
        return try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty("SELF_HOSTED")
            } else {
                System.setProperty("SELF_HOSTED", previous)
            }
        }
    }
}
