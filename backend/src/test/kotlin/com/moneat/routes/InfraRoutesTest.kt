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
import com.moneat.monitor.routes.infraRoutes
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfraRoutesTest {
    companion object {
        private const val JWT_SECRET = "infra-mock-secret"
    }

    @BeforeTest
    fun setup() {
        val db = Database.connect(
            url = "jdbc:h2:mem:moneat_infra_routes;" +
                "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships)

        mockkObject(ClickHouseClient)
        mockkStatic(HttpResponse::bodyAsText)
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ClickHouseClient)
        unmockkStatic(HttpResponse::bodyAsText)
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

    private fun token(userId: Int): String =
        JWT.create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun seedUser(): Int = transaction {
        Users.insert {
            it[email] = "infra-${System.nanoTime()}@test.com"
            it[password_hash] = "hash"
            it[email_verified] = true
        } get Users.id
    }

    private fun seedOrg(): Int = transaction {
        Organizations.insert {
            it[name] = "Infra Org"
            it[slug] = "infra-org-${System.nanoTime()}"
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

    private fun seedUserAndOrg(): Pair<Int, Int> {
        val orgId = seedOrg()
        val userId = seedUser()
        seedMembership(userId, orgId)
        return Pair(userId, orgId)
    }

    private fun stubClickHouseOk(body: String) {
        val mockResponse = mockk<HttpResponse>()
        every { mockResponse.status } returns HttpStatusCode.OK
        coEvery { mockResponse.bodyAsText(any()) } returns body
        coEvery { ClickHouseClient.execute(any(), any()) } returns mockResponse
    }

    private fun stubClickHouseError() {
        val mockResponse = mockk<HttpResponse>()
        every { mockResponse.status } returns HttpStatusCode.InternalServerError
        coEvery { ClickHouseClient.execute(any(), any()) } returns mockResponse
    }

    // ─── Unauthenticated requests ──────────────────────────────────────────

    @Test
    fun `GET infra events without auth returns 401`() = testApplication {
        application {
            installAuth()
            routing { infraRoutes() }
        }
        val response = client.get("/v1/infra/events")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ─── No membership (empty org list) ────────────────────────────────────

    @Test
    fun `GET infra events with no membership returns empty list`() =
        testApplication {
            val userId = seedUser()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/events") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"events\":[]"))
        }

    // ─── GET /infra/events ─────────────────────────────────────────────────

    @Test
    fun `GET infra events returns 200 with data`() = testApplication {
        val (userId, _) = seedUserAndOrg()
        stubClickHouseOk("""{"host":"web-01","alert_type":"cpu_high"}""")

        application {
            installAuth()
            routing { infraRoutes() }
        }

        val response = client.get("/v1/infra/events") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("web-01"))
    }

    @Test
    fun `GET infra events returns empty when CH errors`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            stubClickHouseError()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/events") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"events\":[]"))
        }

    // ─── GET /infra/service-checks ─────────────────────────────────────────

    @Test
    fun `GET infra service-checks returns 200 with data`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            stubClickHouseOk(
                """{"check_name":"http_check","status":"ok"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/service-checks") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("http_check"))
        }

    @Test
    fun `GET service-checks with no membership returns empty`() =
        testApplication {
            val userId = seedUser()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/service-checks") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.bodyAsText().contains("\"serviceChecks\":[]")
            )
        }

    // ─── GET /infra/processes ──────────────────────────────────────────────

    @Test
    fun `GET infra processes returns 200 with data`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            stubClickHouseOk("""{"process_name":"nginx","pid":"1234"}""")

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/processes") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("nginx"))
        }

    @Test
    fun `GET processes with no membership returns empty`() =
        testApplication {
            val userId = seedUser()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/processes") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"processes\":[]"))
        }

    // ─── GET /infra/containers ─────────────────────────────────────────────

    @Test
    fun `GET infra containers returns 200 with data`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            stubClickHouseOk(
                """{"container_name":"redis","status":"running"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/containers") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("redis"))
        }

    @Test
    fun `GET containers with no membership returns empty`() =
        testApplication {
            val userId = seedUser()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/containers") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"containers\":[]"))
        }

    // ─── GET /infra/connections ────────────────────────────────────────────

    @Test
    fun `GET infra connections returns 200 with data`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            stubClickHouseOk(
                """{"source_host":"web-01","dest_host":"db-01"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/connections") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("web-01"))
        }

    @Test
    fun `GET connections with no membership returns empty`() =
        testApplication {
            val userId = seedUser()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/connections") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.bodyAsText().contains("\"connections\":[]")
            )
        }

    // ─── GET /infra/k8s-resources ──────────────────────────────────────────

    @Test
    fun `GET infra k8s-resources returns 200 with data`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            stubClickHouseOk(
                """{"resource_type":"pod","name":"api-server"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/k8s-resources") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("api-server"))
        }

    @Test
    fun `GET k8s-resources with no membership returns empty`() =
        testApplication {
            val userId = seedUser()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/k8s-resources") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.bodyAsText().contains("\"resources\":[]")
            )
        }

    // ─── GET /infra/dbm/queries ────────────────────────────────────────────

    @Test
    fun `GET infra dbm queries returns 200 with data`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            stubClickHouseOk(
                """{"query_text":"SELECT 1","duration_ms":"42"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/dbm/queries") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("SELECT 1"))
        }

    @Test
    fun `GET dbm queries with no membership returns empty`() =
        testApplication {
            val userId = seedUser()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/dbm/queries") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"queries\":[]"))
        }

    // ─── GET /infra/debugger/logs ──────────────────────────────────────────

    @Test
    fun `GET infra debugger logs returns 200 with data`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            stubClickHouseOk(
                """{"probe_id":"probe-1","message":"breakpoint hit"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/debugger/logs") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("probe-1"))
        }

    @Test
    fun `GET debugger logs with no membership returns empty`() =
        testApplication {
            val userId = seedUser()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/debugger/logs") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"logs\":[]"))
        }

    // ─── GET /infra/debugger/diagnostics ───────────────────────────────────

    @Test
    fun `GET infra debugger diagnostics returns 200 with data`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            stubClickHouseOk(
                """{"probe_id":"diag-1","status":"ok"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response =
                client.get("/v1/infra/debugger/diagnostics") {
                    header(
                        HttpHeaders.Authorization,
                        "Bearer ${token(userId)}"
                    )
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("diag-1"))
        }

    @Test
    fun `GET debugger diagnostics with no membership returns empty`() =
        testApplication {
            val userId = seedUser()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response =
                client.get("/v1/infra/debugger/diagnostics") {
                    header(
                        HttpHeaders.Authorization,
                        "Bearer ${token(userId)}"
                    )
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.bodyAsText().contains("\"diagnostics\":[]")
            )
        }

    // ─── GET /infra/sbom ───────────────────────────────────────────────────

    @Test
    fun `GET infra sbom returns 200 with data`() = testApplication {
        val (userId, _) = seedUserAndOrg()
        stubClickHouseOk(
            """{"package_name":"openssl","version":"3.0.1"}"""
        )

        application {
            installAuth()
            routing { infraRoutes() }
        }

        val response = client.get("/v1/infra/sbom") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("openssl"))
    }

    @Test
    fun `GET sbom with no membership returns empty`() =
        testApplication {
            val userId = seedUser()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/sbom") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.bodyAsText().contains("\"packages\":[]")
            )
        }

    // ─── GET /network-devices ──────────────────────────────────────────────

    @Test
    fun `GET network-devices returns 200 with data`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            stubClickHouseOk(
                """{"device_ip":"10.0.0.1","vendor":"cisco"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("10.0.0.1"))
        }

    @Test
    fun `GET network-devices with no membership returns empty`() =
        testApplication {
            val userId = seedUser()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"devices\":[]"))
        }

    // ─── GET /network-devices/flows ────────────────────────────────────────

    @Test
    fun `GET network-devices flows returns 200 with data`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            stubClickHouseOk(
                """{"source_ip":"10.0.0.1","dest_ip":"10.0.0.2"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices/flows") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("10.0.0.1"))
        }

    @Test
    fun `GET network-devices flows with no membership returns empty`() =
        testApplication {
            val userId = seedUser()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices/flows") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"flows\":[]"))
        }

    // ─── GET /network-devices/traps ────────────────────────────────────────

    @Test
    fun `GET network-devices traps returns 200 with data`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            stubClickHouseOk(
                """{"trap_oid":"1.3.6.1","source_ip":"10.0.0.5"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices/traps") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("10.0.0.5"))
        }

    @Test
    fun `GET network-devices traps with no membership returns empty`() =
        testApplication {
            val userId = seedUser()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices/traps") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"traps\":[]"))
        }

    // ─── GET /network-devices/paths ────────────────────────────────────────

    @Test
    fun `GET network-devices paths returns 200 with data`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            stubClickHouseOk(
                """{"destination":"8.8.8.8","hop_count":"5"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices/paths") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("8.8.8.8"))
        }

    @Test
    fun `GET network-devices paths with no membership returns empty`() =
        testApplication {
            val userId = seedUser()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices/paths") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"paths\":[]"))
        }
}
