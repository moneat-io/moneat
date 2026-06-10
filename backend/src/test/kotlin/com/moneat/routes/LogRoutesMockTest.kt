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
import com.moneat.logs.models.LogAggregateResponse
import com.moneat.logs.models.LogFilterOptionsWithCountsResponse
import com.moneat.logs.models.LogQueryResponse
import com.moneat.logs.models.LogTagValuesResponse
import com.moneat.logs.models.LogTopResponse
import com.moneat.logs.routes.logRoutes
import com.moneat.logs.services.LogService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
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
import io.mockk.coEvery
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogRoutesMockTest {
    companion object {
        private var dbInitialized = false
    }

    private val mockLogService = mockk<LogService>(relaxed = true)

    @BeforeTest
    fun setupDatabase() {
        startTestKoin()
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_log_mock;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, Projects)
    }

    private fun Application.installAuth() {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT.require(Algorithm.HMAC256(RouteTestSupport.TEST_JWT_SECRET))
                        .withIssuer("moneat").withAudience("moneat-users").build()
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
    }

    private fun token(userId: Int, orgId: Int): String =
        JWT.create().withIssuer("moneat").withAudience("moneat-users")
            .withClaim("userId", userId)
            .withClaim("orgId", orgId)
            .sign(Algorithm.HMAC256(RouteTestSupport.TEST_JWT_SECRET))

    /** Seed user + org + membership + project, returning (userId, orgId) */
    private fun seedUserAndOrg(): Pair<Int, Int> {
        val orgId = transaction {
            Organizations.insert {
                it[name] = "Log Mock Org"
                it[slug] = "log-mock-org-${System.nanoTime()}"
            } get Organizations.id
        }
        val userId = transaction {
            Users.insert {
                it[email] = "log-mock-${System.nanoTime()}@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            } get Users.id
        }
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
        }
        transaction {
            Projects.insert {
                it[organization_id] = orgId
                it[name] = "Test Project"
                it[slug] = "test-project-${System.nanoTime()}"
            }
        }
        return Pair(userId, orgId)
    }

    // ──── GET /logs (org-scoped) ────

    @Test
    fun `GET project logs returns 200 with empty result`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val logResponse = LogQueryResponse(logs = emptyList(), hasMore = false, totalCount = 0L)
            coEvery { mockLogService.queryLogs(orgId.toLong(), any()) } returns logResponse

            application {
                installAuth()
                routing { logRoutes(logService = mockLogService) }
            }

            val response = client.get("/v1/logs") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("logs"))
        }

    @Test
    fun `GET logs returns 401 when unauthenticated`() =
        testApplication {
            // Request without auth header → 401 (or 403 if auth rejects)
            application {
                installAuth()
                routing { logRoutes(logService = mockLogService) }
            }

            val response = client.get("/v1/logs")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // ──── GET /logs/tag-values ────

    @Test
    fun `GET log tag values returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val tagValuesResponse = LogTagValuesResponse(key = "service", values = listOf("api", "worker"))
            coEvery {
                mockLogService.getTagValues(orgId.toLong(), "service", null, null, 50)
            } returns tagValuesResponse

            application {
                installAuth()
                routing { logRoutes(logService = mockLogService) }
            }

            val response = client.get("/v1/logs/tag-values?key=service") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("service"))
        }

    @Test
    fun `GET log tag values returns 400 when key is missing`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { logRoutes(logService = mockLogService) }
            }

            val response = client.get("/v1/logs/tag-values") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ──── GET /logs/filters ────

    @Test
    fun `GET log filters returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val filtersResponse = LogFilterOptionsWithCountsResponse(
                services = emptyList(),
                environments = emptyList(),
                levels = emptyList(),
                tagKeys = emptyList()
            )
            coEvery {
                mockLogService.getFilterOptionsWithCounts(orgId.toLong(), null, null)
            } returns filtersResponse

            application {
                installAuth()
                routing { logRoutes(logService = mockLogService) }
            }

            val response = client.get("/v1/logs/filters") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    // ──── GET /logs/aggregate ────

    @Test
    fun `GET log aggregate returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val aggregateResponse = LogAggregateResponse(buckets = emptyList(), totalCount = 0L, interval = "1h")
            coEvery {
                mockLogService.aggregateLogs(
                    organizationId = eq(orgId.toLong()),
                    filters = any(),
                    interval = any(),
                    groupBy = any()
                )
            } returns aggregateResponse

            application {
                installAuth()
                routing { logRoutes(logService = mockLogService) }
            }

            val response = client.get("/v1/logs/aggregate") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("buckets"))
        }

    // ──── GET /logs/top ────

    @Test
    fun `GET log top values returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val topResponse = LogTopResponse(field = "service", values = emptyList(), totalCount = 0L)
            coEvery {
                mockLogService.topValues(
                    organizationId = eq(orgId.toLong()),
                    field = eq("service"),
                    limit = any(),
                    filters = any()
                )
            } returns topResponse

            application {
                installAuth()
                routing { logRoutes(logService = mockLogService) }
            }

            val response = client.get("/v1/logs/top?field=service") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("service"))
        }

    @Test
    fun `GET log top values returns 400 when field missing`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { logRoutes(logService = mockLogService) }
            }

            val response = client.get("/v1/logs/top") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @AfterTest
    fun teardownKoin() {
        stopTestKoin()
    }
}
