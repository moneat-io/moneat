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
import com.moneat.org.routes.adminRoutes
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import kotlin.test.AfterTest

class AdminRoutesTest {
    private val jwtSecret = "test-secret-for-admin-routes"

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        startTestKoin()
        if (!dbInitialized) {
            Database.connect(
                url =
                "jdbc:h2:mem:moneat_admin_routes;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships)
    }

    private fun Application.installAuth() {
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT
                        .require(Algorithm.HMAC256(jwtSecret))
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
            .sign(Algorithm.HMAC256(jwtSecret))

    private fun seedUser(
        email: String = "user@test.com",
        isAdmin: Boolean = false
    ): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hash"
                it[email_verified] = true
                it[is_admin] = isAdmin
            } get Users.id
        }

    @Test
    fun `admin endpoint returns 401 when not authenticated`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { adminRoutes() }
            }

            val response = client.get("/v1/admin/organizations")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `admin endpoint returns 403 for non-admin user`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { adminRoutes() }
            }

            val userId = seedUser(isAdmin = false)
            val response =
                client.get("/v1/admin/organizations") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("Admin access required"))
        }

    @Test
    fun `admin users endpoint returns 403 for non-admin user`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { adminRoutes() }
            }

            val userId = seedUser(isAdmin = false)
            val response =
                client.get("/v1/admin/users") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `admin billing tiers endpoint returns 403 for non-admin user`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { adminRoutes() }
            }

            val userId = seedUser(isAdmin = false)
            val response =
                client.get("/v1/admin/billing/tiers") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @AfterTest
    fun teardownKoin() {
        stopTestKoin()
    }
}
