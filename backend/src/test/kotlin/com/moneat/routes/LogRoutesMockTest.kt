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

import com.moneat.billing.services.BillingQuotaService
import com.moneat.logs.services.LogService
import com.moneat.logs.routes.logRoutes
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.logs.models.LogAggregateResponse
import com.moneat.logs.models.LogFilterOptionsWithCountsResponse
import com.moneat.logs.models.LogQueryResponse
import com.moneat.logs.models.LogTagValuesResponse
import com.moneat.logs.models.LogTopResponse
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
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
import io.mockk.coEvery
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogRoutesMockTest {
    companion object {
        private const val JWT_SECRET = "log-mock-secret"
        private var dbInitialized = false
    }

    private val mockLogService = mockk<LogService>(relaxed = true)
    private val mockQuotaService = mockk<BillingQuotaService>(relaxed = true)

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_log_mock;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }

        // Ensure schema exists (idempotent in H2) and clean between tests
        transaction {
            SchemaUtils.drop(Projects, Memberships, Organizations, Users)
            SchemaUtils.create(Users, Organizations, Memberships, Projects)
        }
    }

    private fun Application.installAuth() {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT.require(Algorithm.HMAC256(JWT_SECRET))
                        .withIssuer("moneat").withAudience("moneat-users").build()
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
    }

    private fun token(userId: Int): String =
        JWT.create().withIssuer("moneat").withAudience("moneat-users")
            .withClaim("userId", userId).sign(Algorithm.HMAC256(JWT_SECRET))

    /** Seed user + org + membership + project, returning (userId, projectId) */
    private fun seedUserAndProject(): Pair<Int, Long> {
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
        val projectId = transaction {
            Projects.insert {
                it[organization_id] = orgId
                it[name] = "Test Project"
                it[slug] = "test-project-${System.nanoTime()}"
            } get Projects.id
        }
        return Pair(userId, projectId)
    }

    // ─── GET /projects/{id}/logs ──────────────────────────────────────────────

    @Test
    fun `GET project logs returns 200 with empty result`() =
        testApplication {
            val (userId, projectId) = seedUserAndProject()
            val logResponse = LogQueryResponse(logs = emptyList(), hasMore = false, totalCount = 0L)
            coEvery { mockLogService.queryLogs(projectId, any()) } returns logResponse

            application {
                installAuth()
                routing { logRoutes(logService = mockLogService, quotaService = mockQuotaService) }
            }

            val response = client.get("/v1/projects/$projectId/logs") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("logs"))
        }

    @Test
    fun `GET project logs returns 403 when no project access`() =
        testApplication {
            // User with no org membership → no project access
            val userId = transaction {
                Users.insert {
                    it[email] = "no-access-${System.nanoTime()}@test.com"
                    it[password_hash] = "hash"
                    it[email_verified] = true
                } get Users.id
            }

            application {
                installAuth()
                routing { logRoutes(logService = mockLogService, quotaService = mockQuotaService) }
            }

            val response = client.get("/v1/projects/999/logs") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // ─── GET /projects/{id}/logs/tag-values ───────────────────────────────────

    @Test
    fun `GET log tag values returns 200`() =
        testApplication {
            val (userId, projectId) = seedUserAndProject()
            val tagValuesResponse = LogTagValuesResponse(key = "service", values = listOf("api", "worker"))
            coEvery { mockLogService.getTagValues(projectId, "service", null, null, 50) } returns tagValuesResponse

            application {
                installAuth()
                routing { logRoutes(logService = mockLogService, quotaService = mockQuotaService) }
            }

            val response = client.get("/v1/projects/$projectId/logs/tag-values?key=service") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("service"))
        }

    @Test
    fun `GET log tag values returns 400 when key is missing`() =
        testApplication {
            val (userId, projectId) = seedUserAndProject()

            application {
                installAuth()
                routing { logRoutes(logService = mockLogService, quotaService = mockQuotaService) }
            }

            val response = client.get("/v1/projects/$projectId/logs/tag-values") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ─── GET /projects/{id}/logs/filters ──────────────────────────────────────

    @Test
    fun `GET log filters returns 200`() =
        testApplication {
            val (userId, projectId) = seedUserAndProject()
            val filtersResponse = LogFilterOptionsWithCountsResponse(
                services = emptyList(),
                environments = emptyList(),
                levels = emptyList(),
                tagKeys = emptyList()
            )
            coEvery { mockLogService.getFilterOptionsWithCounts(projectId, null, null) } returns filtersResponse

            application {
                installAuth()
                routing { logRoutes(logService = mockLogService, quotaService = mockQuotaService) }
            }

            val response = client.get("/v1/projects/$projectId/logs/filters") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    // ─── GET /projects/{id}/logs/aggregate ────────────────────────────────────

    @Test
    fun `GET log aggregate returns 200`() =
        testApplication {
            val (userId, projectId) = seedUserAndProject()
            val aggregateResponse = LogAggregateResponse(buckets = emptyList(), totalCount = 0L, interval = "1h")
            coEvery {
                mockLogService.aggregateLogs(
                    eq(projectId), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any()
                )
            } returns aggregateResponse

            application {
                installAuth()
                routing { logRoutes(logService = mockLogService, quotaService = mockQuotaService) }
            }

            val response = client.get("/v1/projects/$projectId/logs/aggregate") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("buckets"))
        }

    // ─── GET /projects/{id}/logs/top ──────────────────────────────────────────

    @Test
    fun `GET log top values returns 200`() =
        testApplication {
            val (userId, projectId) = seedUserAndProject()
            val topResponse = LogTopResponse(field = "service", values = emptyList(), totalCount = 0L)
            coEvery {
                mockLogService.topValues(
                    eq(projectId), eq("service"), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any()
                )
            } returns topResponse

            application {
                installAuth()
                routing { logRoutes(logService = mockLogService, quotaService = mockQuotaService) }
            }

            val response = client.get("/v1/projects/$projectId/logs/top?field=service") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("service"))
        }

    @Test
    fun `GET log top values returns 400 when field missing`() =
        testApplication {
            val (userId, projectId) = seedUserAndProject()

            application {
                installAuth()
                routing { logRoutes(logService = mockLogService, quotaService = mockQuotaService) }
            }

            val response = client.get("/v1/projects/$projectId/logs/top") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}
