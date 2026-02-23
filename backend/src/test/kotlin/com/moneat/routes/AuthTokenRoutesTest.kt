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
import com.moneat.auth.routes.authTokenRoutes
import com.moneat.shared.models.AuthTokens
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
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
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthTokenRoutesTest {
    private val jwtSecret = "test-secret-for-auth-token-routes"

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url =
                "jdbc:h2:mem:moneat_auth_token_routes;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }

        // Ensure schema exists (idempotent in H2) and clean between tests
        transaction {
            SchemaUtils.drop(AuthTokens, Memberships, Organizations, Users)
            SchemaUtils.create(Users, Organizations, Memberships, AuthTokens)
        }
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

    private fun seedUser(email: String = "user@test.com"): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hash"
                it[email_verified] = true
            } get Users.id
        }

    private fun seedOrgWithMembership(userId: Int): Int {
        val orgId =
            transaction {
                Organizations.insert {
                    it[name] = "Test Org"
                    it[slug] = "test-org"
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

    @Test
    fun `create token returns 201 with token value`() {
        val userId = seedUser()
        seedOrgWithMembership(userId)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { authTokenRoutes() }
            }

            val response =
                client.post("/v1/auth-tokens") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"ci-token","scopes":["project:read"]}""")
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("ci-token"))
            assertTrue(body.contains("sntrys_"))
        }
    }

    @Test
    fun `create token returns 400 for invalid scope`() {
        val userId = seedUser()

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { authTokenRoutes() }
            }

            val response =
                client.post("/v1/auth-tokens") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"bad","scopes":["invalid:scope"]}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid scopes"))
        }
    }

    @Test
    fun `create token returns 400 for blank name`() {
        val userId = seedUser()

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { authTokenRoutes() }
            }

            val response =
                client.post("/v1/auth-tokens") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"","scopes":["project:read"]}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `list tokens returns empty list when none exist`() {
        val userId = seedUser()

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { authTokenRoutes() }
            }

            val response =
                client.get("/v1/auth-tokens") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("[]", response.bodyAsText().trim())
        }
    }

    @Test
    fun `list tokens returns created tokens`() {
        val userId = seedUser()
        seedOrgWithMembership(userId)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { authTokenRoutes() }
            }

            client.post("/v1/auth-tokens") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"my-ci-token","scopes":["project:read","project:write"]}""")
            }

            val response =
                client.get("/v1/auth-tokens") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("my-ci-token"))
        }
    }

    @Test
    fun `delete token returns 400 for invalid token id`() {
        val userId = seedUser()

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { authTokenRoutes() }
            }

            val response =
                client.delete("/v1/auth-tokens/not-a-number") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid token ID"))
        }
    }

    @Test
    fun `delete token returns 404 for non-existent token`() {
        val userId = seedUser()

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { authTokenRoutes() }
            }

            val response =
                client.delete("/v1/auth-tokens/9999") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `delete token removes existing token`() {
        val userId = seedUser()
        seedOrgWithMembership(userId)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { authTokenRoutes() }
            }

            // Create a token
            client.post("/v1/auth-tokens") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"to-delete","scopes":["project:read"]}""")
            }

            // Get its ID
            val listResponse =
                client.get("/v1/auth-tokens") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            val tokenId = Regex(""""id"\s*:\s*(\d+)""").find(listResponse.bodyAsText())!!.groupValues[1]

            // Delete it
            val deleteResponse =
                client.delete("/v1/auth-tokens/$tokenId") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

            // Verify gone
            val afterList =
                client.get("/v1/auth-tokens") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertTrue(!afterList.bodyAsText().contains("to-delete"))
        }
    }

    @Test
    fun `update token returns 400 for invalid token id`() {
        val userId = seedUser()

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { authTokenRoutes() }
            }

            val response =
                client.put("/v1/auth-tokens/not-a-number") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"new-name"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid token ID"))
        }
    }

    @Test
    fun `update token returns 404 for non-existent token`() {
        val userId = seedUser()

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { authTokenRoutes() }
            }

            val response =
                client.put("/v1/auth-tokens/9999") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"new-name"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `update token returns 400 for invalid scopes`() {
        val userId = seedUser()
        seedOrgWithMembership(userId)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { authTokenRoutes() }
            }

            client.post("/v1/auth-tokens") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"original","scopes":["project:read"]}""")
            }

            val listBody =
                client.get("/v1/auth-tokens") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }.bodyAsText()
            val tokenId = Regex(""""id"\s*:\s*(\d+)""").find(listBody)!!.groupValues[1]

            val response =
                client.put("/v1/auth-tokens/$tokenId") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"scopes":["bad:scope"]}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid scopes"))
        }
    }

    @Test
    fun `update token succeeds with valid name and scopes`() {
        val userId = seedUser()
        seedOrgWithMembership(userId)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { authTokenRoutes() }
            }

            client.post("/v1/auth-tokens") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"original","scopes":["project:read"]}""")
            }

            val listBody =
                client.get("/v1/auth-tokens") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }.bodyAsText()
            val tokenId = Regex(""""id"\s*:\s*(\d+)""").find(listBody)!!.groupValues[1]

            val response =
                client.put("/v1/auth-tokens/$tokenId") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"updated-name","scopes":["project:read","project:write"]}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }
}
