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
import com.moneat.auth.routes.authRoutes
import com.moneat.auth.services.AuthService
import com.moneat.auth.services.OAuthService
import com.moneat.events.models.AuthResponse
import com.moneat.events.models.LoginRequest
import com.moneat.events.models.UserResponse
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.respond
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import com.moneat.workflows.services.WorkflowService
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
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

    private fun ApplicationTestBuilder.installPlugins() {
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

    private fun ApplicationTestBuilder.noRedirectClient() = createClient { followRedirects = false }

    @BeforeTest
    fun setupDatabase() {
        startTestKoin()
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_auth_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships)
    }

    @Test
    fun `mobile login returns refresh token without browser cookies`() = testApplication {
        installPlugins()
        val authService = mockk<AuthService>()
        every {
            authService.login(LoginRequest("oncall@example.com", "correct-password"))
        } returns authResponse("access-token", "refresh-token")
        routing {
            authRoutes(
                authService = authService,
                oauthService = mockk(relaxed = true)
            )
        }

        val response =
            client.post("/auth/mobile/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"oncall@example.com","password":"correct-password"}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"token\":\"access-token\""))
        assertTrue(response.bodyAsText().contains("\"refreshToken\":\"refresh-token\""))
        assertTrue(response.headers.getAll(HttpHeaders.SetCookie).isNullOrEmpty())
    }

    @Test
    fun `mobile login rejects invalid credentials`() = testApplication {
        installPlugins()
        val authService = mockk<AuthService>()
        every { authService.login(any()) } returns null
        routing {
            authRoutes(
                authService = authService,
                oauthService = mockk(relaxed = true)
            )
        }

        val response =
            client.post("/auth/mobile/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"oncall@example.com","password":"wrong-password"}""")
            }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("Invalid credentials"))
    }

    @Test
    fun `mobile refresh rotates token pair without browser cookies`() = testApplication {
        installPlugins()
        val authService = mockk<AuthService>()
        every { authService.refreshToken("refresh-token") } returns
            authResponse("rotated-access-token", "rotated-refresh-token")
        routing {
            authRoutes(
                authService = authService,
                oauthService = mockk(relaxed = true)
            )
        }

        val response =
            client.post("/auth/mobile/refresh") {
                contentType(ContentType.Application.Json)
                setBody("""{"refreshToken":"refresh-token"}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"token\":\"rotated-access-token\""))
        assertTrue(response.bodyAsText().contains("\"refreshToken\":\"rotated-refresh-token\""))
        assertTrue(response.headers.getAll(HttpHeaders.SetCookie).isNullOrEmpty())
    }

    @Test
    fun `mobile refresh rejects invalid refresh token`() = testApplication {
        installPlugins()
        val authService = mockk<AuthService>()
        every { authService.refreshToken("invalid-refresh-token") } returns null
        routing {
            authRoutes(
                authService = authService,
                oauthService = mockk(relaxed = true)
            )
        }

        val response =
            client.post("/auth/mobile/refresh") {
                contentType(ContentType.Application.Json)
                setBody("""{"refreshToken":"invalid-refresh-token"}""")
            }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("Invalid or expired refresh token"))
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
                assertEquals(
                    "https://dashboard.test.local/login?error=oauth_failed",
                    location
                )
                // Error messages should not be leaked in redirect URLs
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
                // Error messages should not be leaked in redirect URLs
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
                    routing {
                        authRoutes(
                            oauthService = OAuthService(
                                workflowService = mockk<WorkflowService>(relaxed = true)
                            )
                        )
                    }

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

    private fun authResponse(token: String, refreshToken: String) =
        AuthResponse(
            token = token,
            refreshToken = refreshToken,
            expiresIn = 3600,
            user = UserResponse(
                id = "019f8f42-0000-7000-8000-000000000001",
                email = "oncall@example.com",
                name = "On Call"
            )
        )

    @AfterTest
    fun teardownKoin() {
        stopTestKoin()
    }
}
