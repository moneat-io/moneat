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

import com.moneat.events.services.DashboardService
import com.moneat.llm.models.LlmGenerationDetailResponse
import com.moneat.llm.models.LlmGenerationsListResponse
import com.moneat.llm.models.LlmOverviewResponse
import com.moneat.llm.models.LlmTraceResponse
import com.moneat.llm.routes.llmRoutes
import com.moneat.llm.services.LlmDashboardService
import com.moneat.llm.services.LlmGenerationsQuery
import com.moneat.llm.services.LlmQueryScope
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
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
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class LlmScopedRoutesTest {
    companion object {
        private const val PROJECT_ID = 1L
        private const val SECOND_PROJECT_ID = 2L
        private var db: Database? = null
    }

    private val mockDashboardService = mockk<DashboardService>(relaxed = true)
    private val mockLlmService = mockk<LlmDashboardService>(relaxed = true)

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_llm_routes_mock;" +
                    "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                    "DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            Projects
        )
    }

    private fun installRoutes(app: Application) {
        app.installJwtAuth()
        app.install(RateLimit) {
            register(RateLimitName("api")) {
                requestKey { "test-user" }
                rateLimiter(limit = 100, refillPeriod = 1.seconds)
            }
        }
        app.routing {
            llmRoutes(
                dashboardService = mockDashboardService,
                llmService = mockLlmService,
            )
        }
    }

    private fun token(userId: Int, orgId: Int? = null): String =
        RouteTestSupport.createToken(userId, orgId)

    private fun seedUser(): Int = transaction {
        Users.insert {
            it[email] = "llm-${System.nanoTime()}@test.com"
            it[password_hash] = "hash"
            it[email_verified] = true
        } get Users.id
    }

    private fun seedOrganizationMembership(): Pair<Int, Int> {
        val userId = seedUser()
        val orgId = transaction {
            Organizations.insert {
                it[name] = "LLM Org"
                it[slug] = "llm-org-${System.nanoTime()}"
            } get Organizations.id
        }
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
        }
        return userId to orgId
    }

    private fun LlmGenerationsQuery.hasExpectedFilters(): Boolean =
        range == "7d" &&
            filters.model == "gpt-4o" &&
            filters.provider == "openai" &&
            filters.type == "chat" &&
            filters.status == "error" &&
            page == 2 &&
            pageSize == 10 &&
            demoEpochMs == null

    @Test
    fun `overview uses all organization services when filters are absent`() = testApplication {
        val (userId, orgId) = seedOrganizationMembership()
        every { mockDashboardService.getServiceIdsForOrganization(orgId) } returns
            listOf(PROJECT_ID, SECOND_PROJECT_ID)
        coEvery {
            mockLlmService.getOverview(
                match<LlmQueryScope> { it.serviceIds == listOf(PROJECT_ID, SECOND_PROJECT_ID) },
                "24h",
                null,
            )
        } returns LlmOverviewResponse(
            totalGenerations = 3,
            totalTokens = 10,
            totalCost = 0.25,
            avgDurationMs = 100.0,
            errorRate = 0.0,
            timeline = emptyList(),
            topModels = emptyList(),
        )

        application { installRoutes(this) }
        val response = client.get("/v1/llm/overview") {
            withAuth(token(userId, orgId))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) {
            mockLlmService.getOverview(
                match<LlmQueryScope> { it.serviceIds == listOf(PROJECT_ID, SECOND_PROJECT_ID) },
                "24h",
                null,
            )
        }
    }

    @Test
    fun `generations forwards service id and service name filters`() = testApplication {
        val (userId, orgId) = seedOrganizationMembership()
        every { mockDashboardService.getServiceIdsForOrganization(orgId) } returns
            listOf(PROJECT_ID, SECOND_PROJECT_ID)
        every { mockDashboardService.resolveServiceId(orgId, "API") } returns SECOND_PROJECT_ID
        coEvery {
            mockLlmService.getGenerations(
                match<LlmQueryScope> { it.serviceIds == listOf(PROJECT_ID, SECOND_PROJECT_ID) },
                match<LlmGenerationsQuery> { it.hasExpectedFilters() },
            )
        } returns LlmGenerationsListResponse(emptyList(), total = 0, page = 2, pageSize = 10)

        application { installRoutes(this) }
        val response = client.get(
            "/v1/llm/generations?range=7d&serviceIds=$PROJECT_ID&services=API" +
                "&model=gpt-4o&provider=openai&type=chat&status=error&page=2&pageSize=10"
        ) {
            withAuth(token(userId, orgId))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) {
            mockLlmService.getGenerations(
                match<LlmQueryScope> { it.serviceIds == listOf(PROJECT_ID, SECOND_PROJECT_ID) },
                match<LlmGenerationsQuery> { it.hasExpectedFilters() },
            )
        }
    }

    @Test
    fun `invalid service id filter returns 400 without calling service`() = testApplication {
        val (userId, orgId) = seedOrganizationMembership()

        application { installRoutes(this) }
        val response = client.get("/v1/llm/overview?serviceIds=not-a-service-id") {
            withAuth(token(userId, orgId))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        coVerify(exactly = 0) {
            mockLlmService.getOverview(any<LlmQueryScope>(), any(), any())
        }
    }

    @Test
    fun `overview returns 404 when user lacks organization access`() = testApplication {
        val userId = seedUser()

        application { installRoutes(this) }
        val response = client.get("/v1/llm/overview") {
            withAuth(token(userId, 99999))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        coVerify(exactly = 0) {
            mockLlmService.getOverview(any<LlmQueryScope>(), any(), any())
        }
    }

    @Test
    fun `generation detail uses scoped services and returns not found when absent`() = testApplication {
        val (userId, orgId) = seedOrganizationMembership()
        every { mockDashboardService.getServiceIdsForOrganization(orgId) } returns listOf(PROJECT_ID)
        coEvery {
            mockLlmService.getGenerationDetail(
                match<LlmQueryScope> { it.serviceIds == listOf(PROJECT_ID) },
                "gen-1",
            )
        } returns null

        application { installRoutes(this) }
        val response = client.get("/v1/llm/generations/gen-1") {
            withAuth(token(userId, orgId))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        coVerify(exactly = 1) {
            mockLlmService.getGenerationDetail(
                match<LlmQueryScope> { it.serviceIds == listOf(PROJECT_ID) },
                "gen-1",
            )
        }
    }

    @Test
    fun `trace uses scoped services`() = testApplication {
        val (userId, orgId) = seedOrganizationMembership()
        every { mockDashboardService.getServiceIdsForOrganization(orgId) } returns listOf(PROJECT_ID)
        coEvery {
            mockLlmService.getTrace(
                match<LlmQueryScope> { it.serviceIds == listOf(PROJECT_ID) },
                "trace-1",
            )
        } returns LlmTraceResponse(
            traceId = "trace-1",
            generations = emptyList<LlmGenerationDetailResponse>(),
            totalDurationMs = 0.0,
            totalTokens = 0,
            totalCost = 0.0,
        )

        application { installRoutes(this) }
        val response = client.get("/v1/llm/traces/trace-1") {
            withAuth(token(userId, orgId))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) {
            mockLlmService.getTrace(
                match<LlmQueryScope> { it.serviceIds == listOf(PROJECT_ID) },
                "trace-1",
            )
        }
    }
}
