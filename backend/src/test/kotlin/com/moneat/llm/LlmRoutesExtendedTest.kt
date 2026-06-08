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

package com.moneat.llm

import com.moneat.llm.routes.llmRoutes
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
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

class LlmRoutesExtendedTest {

    @BeforeTest
    fun setup() {
        startTestKoin()
    }

    @AfterTest
    fun teardown() {
        stopTestKoin()
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.setupApp() {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "test-user" }
                    rateLimiter(limit = 100, refillPeriod = 1.seconds)
                }
            }
            routing { llmRoutes() }
        }
    }

    // ──── Auth checks (401 without JWT) ────

    @Test
    fun `overview returns 401 without JWT`() =
        testApplication {
            setupApp()
            val response = client.get("/v1/llm/overview")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `generations returns 401 without JWT`() =
        testApplication {
            setupApp()
            val response = client.get("/v1/llm/generations")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `generation detail returns 401 without JWT`() =
        testApplication {
            setupApp()
            val response = client.get("/v1/llm/generations/abc-123")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `traces returns 401 without JWT`() =
        testApplication {
            setupApp()
            val response = client.get("/v1/llm/traces/some-trace-id")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `models returns 401 without JWT`() =
        testApplication {
            setupApp()
            val response = client.get("/v1/llm/models")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `costs returns 401 without JWT`() =
        testApplication {
            setupApp()
            val response = client.get("/v1/llm/costs")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // ──── Missing organization access (404) ────

    @Test
    fun `generations returns 404 when organization access is missing`() =
        testApplication {
            setupApp()
            val token = RouteTestSupport.createToken(userId = 1)
            val response = client.get("/v1/llm/generations") {
                withAuth(token)
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("No organization access"))
        }

    @Test
    fun `models returns 404 when organization access is missing`() =
        testApplication {
            setupApp()
            val token = RouteTestSupport.createToken(userId = 1)
            val response = client.get("/v1/llm/models") {
                withAuth(token)
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("No organization access"))
        }

    @Test
    fun `costs returns 404 when organization access is missing`() =
        testApplication {
            setupApp()
            val token = RouteTestSupport.createToken(userId = 1)
            val response = client.get("/v1/llm/costs") {
                withAuth(token)
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("No organization access"))
        }

    @Test
    fun `generation detail returns 404 when organization access is missing`() =
        testApplication {
            setupApp()
            val token = RouteTestSupport.createToken(userId = 1)
            val response = client.get("/v1/llm/generations/abc-123") {
                withAuth(token)
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("No organization access"))
        }

    @Test
    fun `traces returns 404 when organization access is missing`() =
        testApplication {
            setupApp()
            val token = RouteTestSupport.createToken(userId = 1)
            val response = client.get("/v1/llm/traces/some-trace-id") {
                withAuth(token)
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("No organization access"))
        }
}
