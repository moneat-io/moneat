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
import com.moneat.models.Memberships
import com.moneat.models.Organizations
import com.moneat.models.Users
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.respond
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthRoutesTest {
    private val jwtSecret = "test-secret-for-unit-tests"

    companion object {
        private var dbInitialized = false
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installPlugins() {
        application {
            install(ContentNegotiation) { json() }
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
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.noRedirectClient() = createClient { followRedirects = false }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_auth_routes;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(Users, Organizations, Memberships)
            }
            dbInitialized = true
        }

        transaction {
            Memberships.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
        }
    }

    @Test
    fun `github oauth init sets state cookie and redirects to provider`() {
        MockHttpServer { exchange ->
            exchange.respond(404, """{"error":"not used"}""")
        }.use { server ->
            withProperties(
                mapOf(
                    "GITHUB_OAUTH_CLIENT_ID" to "cid",
                    "GITHUB_OAUTH_CLIENT_SECRET" to "csecret",
                    "BACKEND_URL" to "https://api.test.local",
                    "FRONTEND_URL" to "https://dashboard.test.local",
                    "GITHUB_OAUTH_BASE_URL" to server.baseUrl
                )
            ) {
                testApplication {
                    installPlugins()
                    routing { authRoutes() }

                    val response = noRedirectClient().get("/auth/github")
                    assertEquals(HttpStatusCode.Found, response.status)

                    val location = response.headers[HttpHeaders.Location]
                    assertNotNull(location)
                    assertTrue(location.startsWith("${server.baseUrl}/login/oauth/authorize?"))

                    val cookie =
                        response.headers
                            .getAll(HttpHeaders.SetCookie)
                            ?.firstOrNull { it.startsWith("oauth_state=") }
                    assertNotNull(cookie)
                    assertTrue(cookie.contains("HttpOnly", ignoreCase = true))
                    assertTrue(cookie.contains("Path=/auth"))
                    assertTrue(cookie.contains("SameSite=Lax"))
                }
            }
        }
    }

    @Test
    fun `github callback with missing parameters redirects with oauth failure`() {
        withProperties(
            mapOf("FRONTEND_URL" to "https://dashboard.test.local")
        ) {
            testApplication {
                installPlugins()
                routing { authRoutes() }

                val response = noRedirectClient().get("/auth/github/callback")
                assertEquals(HttpStatusCode.Found, response.status)

                val location = response.headers[HttpHeaders.Location]
                assertNotNull(location)
                assertTrue(location.startsWith("https://dashboard.test.local/login?error=oauth_failed"))
                assertTrue(location.contains("message="))
            }
        }
    }

    @Test
    fun `github callback rejects mismatched state cookie`() {
        withProperties(
            mapOf("FRONTEND_URL" to "https://dashboard.test.local")
        ) {
            testApplication {
                installPlugins()
                routing { authRoutes() }

                val response =
                    noRedirectClient().get("/auth/github/callback?code=test-code&state=expected") {
                        cookie("oauth_state", "different")
                    }
                assertEquals(HttpStatusCode.Found, response.status)

                val location = response.headers[HttpHeaders.Location]
                assertNotNull(location)
                assertTrue(location.contains("error=oauth_failed"))
                assertTrue(location.contains("Invalid OAuth state"))
            }
        }
    }

    @Test
    fun `github callback success clears state cookie sets auth cookie and redirects`() {
        MockHttpServer { exchange ->
            when (exchange.requestURI.path) {
                "/login/oauth/access_token" -> {
                    exchange.respond(
                        200,
                        """{"access_token":"token-ok","token_type":"bearer","scope":"user:email"}"""
                    )
                }

                "/user" -> {
                    exchange.respond(
                        200,
                        """{"id":111,"login":"tester","email":"oauth@test.com","name":"OAuth Tester"}"""
                    )
                }

                "/user/emails" -> {
                    exchange.respond(200, """[]""")
                }

                else -> {
                    exchange.respond(404, """{"error":"not found"}""")
                }
            }
        }.use { server ->
            withProperties(
                mapOf(
                    "GITHUB_OAUTH_CLIENT_ID" to "cid",
                    "GITHUB_OAUTH_CLIENT_SECRET" to "csecret",
                    "FRONTEND_URL" to "https://dashboard.test.local",
                    "GITHUB_OAUTH_BASE_URL" to server.baseUrl,
                    "GITHUB_API_BASE_URL" to server.baseUrl
                )
            ) {
                testApplication {
                    installPlugins()
                    routing { authRoutes() }

                    val response =
                        noRedirectClient().get("/auth/github/callback?code=test-code&state=good-state") {
                            cookie("oauth_state", "good-state")
                        }
                    assertEquals(HttpStatusCode.Found, response.status)
                    assertEquals(
                        "https://dashboard.test.local/auth/oauth/callback",
                        response.headers[HttpHeaders.Location]
                    )

                    val setCookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                    assertTrue(setCookies.any { it.startsWith("auth_token=") }, "Expected auth_token cookie")
                    val oauthStateCleared =
                        setCookies.any {
                            it.startsWith("oauth_state=") && (it.contains("Max-Age=0") || it.contains("Expires="))
                        }
                    assertTrue(oauthStateCleared, "Expected oauth_state clear cookie")
                }

                transaction {
                    val user = Users.selectAll().where { Users.email eq "oauth@test.com" }.firstOrNull()
                    assertNotNull(user)
                    assertEquals("github", user[Users.oauth_provider])
                    assertEquals("111", user[Users.oauth_provider_id])
                }
            }
        }
    }

    private fun <T> withProperties(
        properties: Map<String, String>,
        block: () -> T
    ): T {
        val previous = properties.keys.associateWith { System.getProperty(it) }
        properties.forEach { (k, v) -> System.setProperty(k, v) }
        return try {
            block()
        } finally {
            previous.forEach { (k, v) ->
                if (v == null) {
                    System.clearProperty(k)
                } else {
                    System.setProperty(k, v)
                }
            }
        }
    }
}
