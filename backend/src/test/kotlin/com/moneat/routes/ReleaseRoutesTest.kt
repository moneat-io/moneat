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

import com.moneat.auth.services.AuthTokenService
import com.moneat.events.routes.releaseRoutes
import com.moneat.shared.models.AuthTokens
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.plugins.AuthTokenPrincipal
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
import io.ktor.server.auth.bearer
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

class ReleaseRoutesTest {
    private val testBearerToken = "test-bearer-token-releases"
    private val testCombinedToken = "test-combined-token-releases"

    companion object {
        private var dbInitialized = false
        private var testUserId = -1
    }

    @BeforeTest
    fun setupDatabase() {
        startTestKoin()
        if (!dbInitialized) {
            Database.connect(
                url =
                "jdbc:h2:mem:moneat_release_routes;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, AuthTokens)

        testUserId =
            transaction {
                Users.insert {
                    it[email] = "release-test@test.com"
                    it[password_hash] = "hash"
                    it[email_verified] = true
                } get Users.id
            }
    }

    private fun Application.installAuth() {
        install(Authentication) {
            bearer("auth-bearer") {
                authenticate { credential ->
                    if (credential.token == testBearerToken) {
                        AuthTokenPrincipal(
                            userId = testUserId,
                            scopes = AuthTokenService.VALID_SCOPES.toList(),
                            tokenId = 1
                        )
                    } else {
                        null
                    }
                }
            }
            bearer("auth-combined") {
                authenticate { credential ->
                    if (credential.token == testCombinedToken) {
                        AuthTokenPrincipal(
                            userId = testUserId,
                            scopes = listOf("project:read"), // intentionally missing releases:write
                            tokenId = 1
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    @Test
    fun `GET api 0 returns 200 with auth info for valid bearer token`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { releaseRoutes() }
            }

            val response =
                client.get("/api/0/") {
                    header(HttpHeaders.Authorization, "Bearer $testBearerToken")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("release-test@test.com"))
        }

    @Test
    fun `GET api 0 returns 401 without authentication`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { releaseRoutes() }
            }

            val response = client.get("/api/0/")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `POST releases returns 403 when releases write scope is missing`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { releaseRoutes() }
            }

            val response =
                client.post("/api/0/organizations/my-org/releases/") {
                    header(HttpHeaders.Authorization, "Bearer $testCombinedToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"version":"1.0.0"}""")
                }

            // Token has project:read only, not releases:write → 403
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("releases:write"))
        }

    @Test
    fun `POST releases returns 401 without authentication`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { releaseRoutes() }
            }

            val response =
                client.post("/api/0/organizations/my-org/releases/") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"version":"1.0.0"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @AfterTest
    fun teardownKoin() {
        stopTestKoin()
    }
}
