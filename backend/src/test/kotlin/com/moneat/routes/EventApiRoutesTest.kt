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
import com.moneat.events.models.EventResponse
import com.moneat.events.models.IssueDetailResponse
import com.moneat.events.models.IssueResponse
import com.moneat.events.models.IssueTransactionResponse
import com.moneat.events.models.PerformanceStatsResponse
import com.moneat.events.models.SlowTransactionResponse
import com.moneat.events.models.TimelinePoint
import com.moneat.events.models.TransactionDetailResponse
import com.moneat.events.models.TransactionSummaryResponse
import com.moneat.events.models.TransactionWithSpansResponse
import com.moneat.events.models.SpanResponse
import com.moneat.events.routes.apiRoutes
import com.moneat.events.services.DashboardService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class EventApiRoutesTest {
    companion object {
        private const val JWT_SECRET = "event-api-test-secret"
        private var dbInitialized = false
    }

    private val mockDashboardService = mockk<DashboardService>(relaxed = true)

    @BeforeTest
    fun setup() {
        startTestKoin()
        loadKoinModules(
            module { single<DashboardService> { mockDashboardService } }
        )
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_event_api;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, Projects)
    }

    @AfterTest
    fun teardown() {
        stopTestKoin()
    }

    private fun Application.installTestApp() {
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
        install(RateLimit) {
            register(RateLimitName("api")) {
                requestKey { "test-user" }
                rateLimiter(limit = 1000, refillPeriod = 1.seconds)
            }
        }
        routing { apiRoutes() }
    }

    private fun token(userId: Int): String =
        JWT.create().withIssuer("moneat").withAudience("moneat-users")
            .withClaim("userId", userId)
            .withClaim("email", "user$userId@test.com")
            .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun demoToken(): String =
        JWT.create().withIssuer("moneat").withAudience("moneat-users")
            .withClaim("userId", -1)
            .withClaim("email", "demo@moneat.dev")
            .withClaim("isDemo", true)
            .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun seedUserWithProject(): Pair<Int, Long> {
        val orgId = transaction {
            Organizations.insert {
                it[name] = "Event API Test Org"
                it[slug] = "event-api-org-${System.nanoTime()}"
            } get Organizations.id
        }
        val userId = transaction {
            Users.insert {
                it[email] = "event-api-${System.nanoTime()}@test.com"
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

    // ─── Authentication ──────────────────────────────────────────────────────

    @Test
    fun `GET issues returns 401 without auth token`() = testApplication {
        application { installTestApp() }
        val response = client.get("/v1/projects/1/issues")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET issue detail returns 401 without auth token`() = testApplication {
        application { installTestApp() }
        val response = client.get("/v1/issues/issue-1")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET transaction detail returns 401 without auth token`() = testApplication {
        application { installTestApp() }
        val response = client.get("/v1/transactions/txn-1")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ─── Access control ──────────────────────────────────────────────────────

    @Test
    fun `GET issues returns 403 when user lacks project access`() = testApplication {
        val (userId, _) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, 999L) } returns false

        application { installTestApp() }
        val response = client.get("/v1/projects/999/issues") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET issue detail returns 403 when user lacks issue access`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasIssueAccess(userId, "issue-no-access") } returns false

        application { installTestApp() }
        val response = client.get("/v1/issues/issue-no-access") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET transaction detail returns 403 when user lacks access`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasTransactionAccess(userId, "txn-no-access") } returns false

        application { installTestApp() }
        val response = client.get("/v1/transactions/txn-no-access") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ─── GET /v1/projects/{projectId}/issues ─────────────────────────────────

    @Test
    fun `GET issues returns 200 with issue list`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery {
            mockDashboardService.getIssues(projectId, 1, 25, null, any())
        } returns listOf(sampleIssue(projectId))

        application { installTestApp() }
        val response = client.get("/v1/projects/$projectId/issues") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("issue-1"))
    }

    @Test
    fun `GET issues forwards pagination and status params`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery {
            mockDashboardService.getIssues(projectId, 3, 10, "resolved", any())
        } returns emptyList()

        application { installTestApp() }
        val response = client.get("/v1/projects/$projectId/issues?page=3&limit=10&status=resolved") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        coVerify { mockDashboardService.getIssues(projectId, 3, 10, "resolved", any()) }
    }

    @Test
    fun `GET issues returns 400 for invalid project id`() = testApplication {
        val (userId, _) = seedUserWithProject()

        application { installTestApp() }
        val response = client.get("/v1/projects/not-a-number/issues") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ─── GET /v1/issues/{issueId} ────────────────────────────────────────────

    @Test
    fun `GET issue detail returns 200 with issue data`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasIssueAccess(userId, "issue-detail-1") } returns true
        coEvery {
            mockDashboardService.getIssue("issue-detail-1", any())
        } returns sampleIssueDetail()

        application { installTestApp() }
        val response = client.get("/v1/issues/issue-detail-1") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("NullPointerException"))
    }

    @Test
    fun `GET issue detail returns 404 when issue not found`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasIssueAccess(userId, "missing") } returns true
        coEvery { mockDashboardService.getIssue("missing", any()) } returns null

        application { installTestApp() }
        val response = client.get("/v1/issues/missing") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ─── GET /v1/issues/{issueId}/events ─────────────────────────────────────

    @Test
    fun `GET issue events returns 200 with event list`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasIssueAccess(userId, "issue-ev-1") } returns true
        coEvery {
            mockDashboardService.getIssueEvents("issue-ev-1", 50, any())
        } returns listOf(sampleEvent())

        application { installTestApp() }
        val response = client.get("/v1/issues/issue-ev-1/events") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("evt-1"))
    }

    @Test
    fun `GET issue events respects limit parameter`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasIssueAccess(userId, "issue-ev-2") } returns true
        coEvery {
            mockDashboardService.getIssueEvents("issue-ev-2", 5, any())
        } returns emptyList()

        application { installTestApp() }
        val response = client.get("/v1/issues/issue-ev-2/events?limit=5") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        coVerify { mockDashboardService.getIssueEvents("issue-ev-2", 5, any()) }
    }

    // ─── GET /v1/issues/{issueId}/transactions ───────────────────────────────

    @Test
    fun `GET issue transactions returns 200`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasIssueAccess(userId, "issue-txn-1") } returns true
        coEvery {
            mockDashboardService.getIssueTransactions("issue-txn-1", 20, any())
        } returns listOf(sampleIssueTransaction())

        application { installTestApp() }
        val response = client.get("/v1/issues/issue-txn-1/transactions") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("GET /api/users"))
    }

    @Test
    fun `GET issue transactions respects limit parameter`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasIssueAccess(userId, "issue-txn-2") } returns true
        coEvery {
            mockDashboardService.getIssueTransactions("issue-txn-2", 5, any())
        } returns emptyList()

        application { installTestApp() }
        val response = client.get("/v1/issues/issue-txn-2/transactions?limit=5") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        coVerify { mockDashboardService.getIssueTransactions("issue-txn-2", 5, any()) }
    }

    // ─── GET /v1/projects/{projectId}/transactions ───────────────────────────

    @Test
    fun `GET project transactions returns 200`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery {
            mockDashboardService.getTransactions(projectId, "7d", null, null, any())
        } returns listOf(sampleTransactionSummary())

        application { installTestApp() }
        val response = client.get("/v1/projects/$projectId/transactions") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("GET /api/users"))
    }

    @Test
    fun `GET project transactions forwards query params`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery {
            mockDashboardService.getTransactions(projectId, "30d", "production", "http.server", any())
        } returns emptyList()

        application { installTestApp() }
        val url = "/v1/projects/$projectId/transactions?period=30d&environment=production&operation=http.server"
        val response = client.get(url) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        coVerify {
            mockDashboardService.getTransactions(projectId, "30d", "production", "http.server", any())
        }
    }

    // ─── GET /v1/projects/{projectId}/transactions/stats ─────────────────────

    @Test
    fun `GET performance stats returns 200`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery {
            mockDashboardService.getPerformanceStats(projectId, "7d", null, null, any())
        } returns samplePerformanceStats()

        application { installTestApp() }
        val response = client.get("/v1/projects/$projectId/transactions/stats") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("apdex"))
    }

    @Test
    fun `GET performance stats forwards query params`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery {
            mockDashboardService.getPerformanceStats(projectId, "24h", "staging", "db.query", any())
        } returns samplePerformanceStats()

        application { installTestApp() }
        val url = "/v1/projects/$projectId/transactions/stats?period=24h&environment=staging&operation=db.query"
        val response = client.get(url) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        coVerify {
            mockDashboardService.getPerformanceStats(projectId, "24h", "staging", "db.query", any())
        }
    }

    // ─── GET /v1/transactions/{eventId} ──────────────────────────────────────

    @Test
    fun `GET transaction detail returns 200`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasTransactionAccess(userId, "txn-detail-1") } returns true
        coEvery {
            mockDashboardService.getTransaction("txn-detail-1")
        } returns sampleTransactionDetail()

        application { installTestApp() }
        val response = client.get("/v1/transactions/txn-detail-1") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("txn-detail-1"))
    }

    @Test
    fun `GET transaction detail returns 404 when not found`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasTransactionAccess(userId, "txn-missing") } returns true
        coEvery { mockDashboardService.getTransaction("txn-missing") } returns null

        application { installTestApp() }
        val response = client.get("/v1/transactions/txn-missing") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ─── GET /v1/transactions/{eventId}/spans ────────────────────────────────

    @Test
    fun `GET transaction spans returns 200`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasTransactionAccess(userId, "txn-span-1") } returns true
        coEvery {
            mockDashboardService.getTransactionSpans("txn-span-1")
        } returns sampleTransactionWithSpans()

        application { installTestApp() }
        val response = client.get("/v1/transactions/txn-span-1/spans") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("db.query"))
    }

    @Test
    fun `GET transaction spans returns 404 when not found`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasTransactionAccess(userId, "txn-span-missing") } returns true
        coEvery { mockDashboardService.getTransactionSpans("txn-span-missing") } returns null

        application { installTestApp() }
        val response = client.get("/v1/transactions/txn-span-missing/spans") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ─── GET /v1/transactions/{eventId}/related-errors ───────────────────────

    @Test
    fun `GET related errors returns 200`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasTransactionAccess(userId, "txn-rel-1") } returns true
        coEvery {
            mockDashboardService.getRelatedErrorsForTransaction("txn-rel-1", 20)
        } returns listOf(sampleEvent())

        application { installTestApp() }
        val response = client.get("/v1/transactions/txn-rel-1/related-errors") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("evt-1"))
    }

    @Test
    fun `GET related errors respects limit parameter`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasTransactionAccess(userId, "txn-rel-2") } returns true
        coEvery {
            mockDashboardService.getRelatedErrorsForTransaction("txn-rel-2", 5)
        } returns emptyList()

        application { installTestApp() }
        val response = client.get("/v1/transactions/txn-rel-2/related-errors?limit=5") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        coVerify { mockDashboardService.getRelatedErrorsForTransaction("txn-rel-2", 5) }
    }

    // ─── GET /v1/events/{eventId}/issue ──────────────────────────────────────

    @Test
    fun `GET event issue returns 200 with issueId`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        coEvery { mockDashboardService.getProjectIdForEvent("evt-lookup-1") } returns projectId
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery { mockDashboardService.getIssueIdForEvent("evt-lookup-1") } returns "issue-found-1"

        application { installTestApp() }
        val response = client.get("/v1/events/evt-lookup-1/issue") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("issue-found-1"))
    }

    @Test
    fun `GET event issue returns 403 when no project access`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.getProjectIdForEvent("evt-no-access") } returns null

        application { installTestApp() }
        val response = client.get("/v1/events/evt-no-access/issue") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET event issue returns 404 when no issue found`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        coEvery { mockDashboardService.getProjectIdForEvent("evt-no-issue") } returns projectId
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery { mockDashboardService.getIssueIdForEvent("evt-no-issue") } returns null

        application { installTestApp() }
        val response = client.get("/v1/events/evt-no-issue/issue") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ─── PATCH /v1/issues/{issueId} ──────────────────────────────────────────

    @Test
    fun `PATCH issue returns 204 on success`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasIssueAccess(userId, "issue-patch-1") } returns true

        application { installTestApp() }
        val response = client.patch("/v1/issues/issue-patch-1") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"resolved"}""")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `PATCH issue returns 403 when user lacks access`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasIssueAccess(userId, "issue-patch-no") } returns false

        application { installTestApp() }
        val response = client.patch("/v1/issues/issue-patch-no") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"resolved"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ─── Demo user access ────────────────────────────────────────────────────

    @Test
    fun `demo user can access issues without explicit project membership`() = testApplication {
        coEvery {
            mockDashboardService.getIssues(any(), any(), any(), any(), any())
        } returns emptyList()

        application { installTestApp() }
        val response = client.get("/v1/projects/1/issues") {
            header(HttpHeaders.Authorization, "Bearer ${demoToken()}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun sampleIssue(projectId: Long = 1L) = IssueResponse(
        id = "issue-1",
        projectId = projectId,
        title = "NullPointerException",
        culprit = "com.app.Main",
        level = "error",
        platform = "java",
        firstSeen = "2026-01-01T00:00:00Z",
        lastSeen = "2026-01-02T00:00:00Z",
        eventCount = 10,
        userCount = 3,
        status = "unresolved"
    )

    private fun sampleIssueDetail() = IssueDetailResponse(
        id = "issue-detail-1",
        projectId = 1L,
        projectName = "Test Project",
        title = "NullPointerException",
        culprit = "com.app.Main",
        level = "error",
        platform = "java",
        firstSeen = "2026-01-01T00:00:00Z",
        lastSeen = "2026-01-02T00:00:00Z",
        eventCount = 10,
        userCount = 3,
        status = "unresolved",
        fingerprint = listOf("abc123"),
        latestEvent = sampleEvent()
    )

    private fun sampleEvent() = EventResponse(
        eventId = "evt-1",
        timestamp = "2026-01-02T00:00:00Z",
        message = "NullPointerException in Main",
        platform = "java",
        level = "error",
        environment = "production",
        release = "1.0.0",
        user = null,
        contexts = "{}",
        exception = null,
        breadcrumbs = null
    )

    private fun sampleIssueTransaction() = IssueTransactionResponse(
        eventId = "txn-1",
        name = "GET /api/users",
        op = "http.server",
        duration = 150.0,
        timestamp = "2026-01-02T00:00:00Z",
        status = "ok",
        traceId = "trace-1"
    )

    private fun sampleTransactionSummary() = TransactionSummaryResponse(
        name = "GET /api/users",
        op = "http.server",
        latestEventId = "txn-1",
        count = 100,
        p50 = 50.0,
        p75 = 75.0,
        p95 = 150.0,
        failureRate = 0.02,
        tpm = 10.0
    )

    private fun sampleTransactionDetail() = TransactionDetailResponse(
        eventId = "txn-detail-1",
        name = "GET /api/users",
        op = "http.server",
        startTimestamp = 1704067200.0,
        duration = 150.0,
        traceId = "trace-1",
        timestamp = "2026-01-01T00:00:00Z",
        environment = "production",
        release = "1.0.0",
        status = "ok",
        tags = mapOf("http.method" to "GET"),
        contexts = "{}"
    )

    private fun sampleTransactionWithSpans() = TransactionWithSpansResponse(
        transaction = sampleTransactionDetail(),
        spans = listOf(
            SpanResponse(
                spanId = "span-1",
                op = "db.query",
                description = "SELECT * FROM users",
                startTimestamp = 1704067200.0,
                endTimestamp = 1704067200.05,
                duration = 50.0
            )
        )
    )

    private fun samplePerformanceStats() = PerformanceStatsResponse(
        apdex = 0.95,
        throughput = listOf(TimelinePoint("2026-01-01T00:00:00Z", 100)),
        slowestTransactions = listOf(
            SlowTransactionResponse(
                eventId = "slow-1",
                name = "GET /api/heavy",
                op = "http.server",
                duration = 5000.0,
                timestamp = "2026-01-01T00:00:00Z"
            )
        ),
        totalTransactions = 1000,
        avgDuration = 120.0
    )
}
