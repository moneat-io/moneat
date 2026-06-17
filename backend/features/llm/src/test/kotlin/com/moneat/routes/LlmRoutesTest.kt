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
import com.moneat.llm.routes.llmRoutes
import com.moneat.llm.services.LlmDashboardService
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LlmRoutesTest {
    private val jwtSecret = "llm-routes-secret"

    @BeforeTest
    fun setupKoin() {
        startTestKoin()
    }

    @Test
    fun `overview endpoint returns 404 when organization access is missing`() =
        testApplication {
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
                install(RateLimit) {
                    register(RateLimitName("api")) {
                        requestKey { "test-user" }
                        rateLimiter(limit = 100, refillPeriod = 1.seconds)
                    }
                }
                routing { llmRoutes(llmService = LlmDashboardService()) }
            }

            val response =
                client.get("/v1/llm/overview") {
                    header(HttpHeaders.Authorization, "Bearer ${tokenForUser(12)}")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("No organization access"))
        }

    private fun tokenForUser(userId: Int): String {
        return JWT
            .create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .sign(Algorithm.HMAC256(jwtSecret))
    }

    @AfterTest
    fun teardownKoin() {
        stopTestKoin()
    }
}
