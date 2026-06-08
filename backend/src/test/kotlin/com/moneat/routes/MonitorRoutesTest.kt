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
import com.moneat.monitor.routes.monitorRoutes
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MonitorRoutesTest {
    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        startTestKoin()
        if (!dbInitialized) {
            Database.connect(
                url =
                "jdbc:h2:mem:moneat_monitor_routes;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, Hosts)
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

    private fun token(userId: Int, orgId: Int? = null): String =
        JWT
            .create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .apply { if (orgId != null) withClaim("orgId", orgId) }
            .sign(Algorithm.HMAC256("test-secret"))

    private fun seedUser(): Int =
        transaction {
            Users.insert {
                it[email] = "monitor-test-${System.nanoTime()}@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            } get Users.id
        }

    private fun seedOrg(): Int =
        transaction {
            Organizations.insert {
                it[name] = "Monitor Test Org"
                it[slug] = "monitor-test-org-${System.nanoTime()}"
            } get Organizations.id
        }

    private fun seedMembership(
        userId: Int,
        orgId: Int,
    ) {
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
        }
    }

    // ──── GET /systems ────

    @Test
    fun `GET systems returns 403 when user has no org`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { monitorRoutes() }
            }

            val userId = seedUser()
            val response =
                client.get("/v1/monitor/hosts") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `GET systems returns 200 empty list when user has org but no systems`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { monitorRoutes() }
            }

            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val response =
                client.get("/v1/monitor/hosts") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("[]", response.bodyAsText())
        }

    // ──── POST /systems ────

    // ──── GET /systems/{id} ────

    @Test
    fun `GET systems id returns 400 for invalid uuid`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { monitorRoutes() }
            }

            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val response =
                client.get("/v1/monitor/hosts/not-a-uuid") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET systems id returns 403 when user has no org`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { monitorRoutes() }
            }

            val userId = seedUser()
            val response =
                client.get("/v1/monitor/hosts/${UUID.randomUUID()}") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `GET systems id returns 404 when system not found`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { monitorRoutes() }
            }

            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val response =
                client.get("/v1/monitor/hosts/2147483647") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // ──── DELETE /systems/{id} ────

    @Test
    fun `DELETE systems id returns 400 for invalid uuid`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { monitorRoutes() }
            }

            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val response =
                client.delete("/v1/monitor/hosts/bad-uuid") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `DELETE systems id returns 403 when user has no org`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { monitorRoutes() }
            }

            val userId = seedUser()
            val response =
                client.delete("/v1/monitor/hosts/${UUID.randomUUID()}") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // ──── GET /systems/{id}/metrics ────

    @Test
    fun `GET systems id metrics returns 400 for invalid uuid`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { monitorRoutes() }
            }

            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val response =
                client.get("/v1/monitor/hosts/not-a-uuid/metrics") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET systems id metrics returns 403 when user has no org`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { monitorRoutes() }
            }

            val userId = seedUser()
            val response =
                client.get("/v1/monitor/hosts/${UUID.randomUUID()}/metrics") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // ──── GET /systems/{id}/containers ────

    @Test
    fun `GET systems id containers returns 400 for invalid uuid`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { monitorRoutes() }
            }

            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId)
            val response =
                client.get("/v1/monitor/hosts/not-a-uuid/containers") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ──── GET /silence-periods ────

    @Test
    fun `GET silence-periods returns 403 when user has no org`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { monitorRoutes() }
            }

            val userId = seedUser()
            val response =
                client.get("/v1/monitor/silence-periods") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // ──── POST /silence-periods ────

    @Test
    fun `POST silence-periods returns 403 when user has no org`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { monitorRoutes() }
            }

            val userId = seedUser()
            val response =
                client.post("/v1/monitor/silence-periods") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @AfterTest
    fun teardownKoin() {
        stopTestKoin()
    }
}
