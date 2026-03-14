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
import com.moneat.analytics.models.AnalyticsOverviewResponse
import com.moneat.analytics.models.BreakdownResponse
import com.moneat.analytics.models.BreakdownRow
import com.moneat.analytics.models.FunnelResponse
import com.moneat.analytics.models.FunnelStep
import com.moneat.analytics.models.RealtimeResponse
import com.moneat.analytics.models.TimeseriesPoint
import com.moneat.analytics.routes.analyticsRoutes
import com.moneat.analytics.services.AnalyticsService
import com.moneat.events.services.DashboardService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
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
import kotlin.test.assertTrue

class AnalyticsRoutesTest {
    companion object {
        private const val JWT_SECRET = "analytics-mock-secret"
        private const val PROJECT_ID = 1L
        private var db: Database? = null
    }

    private val mockAnalyticsService = mockk<AnalyticsService>(relaxed = true)
    private val mockDashboardService = mockk<DashboardService>(relaxed = true)

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_analytics_routes_mock;" +
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

    private fun Application.installAuth() {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT.require(Algorithm.HMAC256(JWT_SECRET))
                        .withIssuer("moneat")
                        .withAudience("moneat-users")
                        .build()
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
    }

    private fun token(userId: Int): String =
        JWT.create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun seedUser(): Int = transaction {
        Users.insert {
            it[email] = "analytics-${System.nanoTime()}@test.com"
            it[password_hash] = "hash"
            it[email_verified] = true
        } get Users.id
    }

    private fun seedOrg(): Int = transaction {
        Organizations.insert {
            it[name] = "Analytics Org"
            it[slug] = "analytics-org-${System.nanoTime()}"
        } get Organizations.id
    }

    private fun seedMembership(userId: Int, orgId: Int) {
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
        }
    }

    private fun seedUserAndOrg(): Pair<Int, Int> {
        val orgId = seedOrg()
        val userId = seedUser()
        seedMembership(userId, orgId)
        return Pair(userId, orgId)
    }

    private fun installRoutes(app: Application) {
        app.installAuth()
        app.routing {
            analyticsRoutes(
                analyticsService = mockAnalyticsService,
                dashboardService = mockDashboardService,
            )
        }
    }

    private fun authedGet(path: String) = "/v1/analytics/$PROJECT_ID$path"

    private fun stubAccess(userId: Int) {
        every { mockDashboardService.hasProjectAccess(userId, PROJECT_ID) } returns true
    }

    private fun stubNoAccess(userId: Int) {
        every { mockDashboardService.hasProjectAccess(userId, PROJECT_ID) } returns false
    }

    private val overviewResponse = AnalyticsOverviewResponse(
        visitors = 100,
        pageviews = 250,
        bounceRate = 45.0,
        avgVisitDuration = 120.0,
        viewsPerVisit = 2.5,
    )

    private val timeseriesResponse = listOf(
        TimeseriesPoint(date = "2024-01-01", visitors = 10, pageviews = 20),
        TimeseriesPoint(date = "2024-01-02", visitors = 15, pageviews = 30),
    )

    private val breakdownResponse = BreakdownResponse(
        results = listOf(
            BreakdownRow(name = "/home", visitors = 50, pageviews = 100),
            BreakdownRow(name = "/about", visitors = 30, pageviews = 60),
        )
    )

    private val realtimeResponse = RealtimeResponse(visitors = 5)

    private val funnelResponse = FunnelResponse(
        steps = listOf(
            FunnelStep(name = "/home", visitors = 100, dropoff = 0.0),
            FunnelStep(name = "/signup", visitors = 40, dropoff = 60.0),
        )
    )

    // ─── Auth ──────────────────────────────────────────────────

    @Test
    fun `returns 401 when unauthenticated`() = testApplication {
        application { installRoutes(this) }
        val r = client.get(authedGet("/overview?period=30d"))
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `returns 401 for invalid token`() = testApplication {
        application { installRoutes(this) }
        val badToken = JWT.create()
            .withIssuer("wrong-issuer")
            .withClaim("userId", 1)
            .sign(Algorithm.HMAC256("wrong-secret"))
        val r = client.get(authedGet("/overview?period=30d")) {
            header(HttpHeaders.Authorization, "Bearer $badToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `returns 403 when user has no project access`() = testApplication {
        val userId = seedUser()
        stubNoAccess(userId)
        application { installRoutes(this) }
        val r = client.get(authedGet("/overview?period=30d")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `returns 400 for invalid project ID`() = testApplication {
        val userId = seedUser()
        application { installRoutes(this) }
        val r = client.get("/v1/analytics/invalid/overview?period=30d") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    // ─── Overview ──────────────────────────────────────────────

    @Test
    fun `GET overview returns 200 with default period`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getOverview(PROJECT_ID, any(), any(), any(), any(), any())
        } returns overviewResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/overview?period=30d")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("\"visitors\""))
        assertTrue(body.contains("\"pageviews\""))
    }

    @Test
    fun `GET overview returns 200 with comparison`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getOverview(PROJECT_ID, any(), any(), any(), any(), any())
        } returns overviewResponse.copy(compVisitors = 80, compPageviews = 200)
        application { installRoutes(this) }
        val r = client.get(authedGet("/overview?period=30d&comparison=previous_period")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("\"compVisitors\""))
    }

    @Test
    fun `GET overview returns 400 for invalid custom date range`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        application { installRoutes(this) }
        val r = client.get(authedGet("/overview?period=custom")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `GET overview returns 200 with custom date range`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getOverview(PROJECT_ID, any(), any(), any(), any(), any())
        } returns overviewResponse
        application { installRoutes(this) }
        val r = client.get(
            authedGet("/overview?period=custom&date_from=2024-01-01&date_to=2024-01-31")
        ) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // ─── Timeseries ────────────────────────────────────────────

    @Test
    fun `GET timeseries returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getTimeseries(PROJECT_ID, any(), any(), any())
        } returns timeseriesResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/timeseries?period=7d")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("\"date\""))
    }

    @Test
    fun `GET timeseries returns 400 for invalid custom range`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        application { installRoutes(this) }
        val r = client.get(authedGet("/timeseries?period=custom&from=bad")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    // ─── Pages ─────────────────────────────────────────────────

    @Test
    fun `GET pages returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getPages(PROJECT_ID, any(), any(), any(), any())
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/pages?period=30d")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"results\""))
    }

    @Test
    fun `GET pages respects limit param`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getPages(PROJECT_ID, any(), any(), any(), eq(10))
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/pages?period=30d&limit=10")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        coVerify(exactly = 1) {
            mockAnalyticsService.getPages(PROJECT_ID, any(), any(), any(), 10)
        }
    }

    // ─── Entry Pages ───────────────────────────────────────────

    @Test
    fun `GET entry-pages returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getEntryPages(PROJECT_ID, any(), any(), any(), any())
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/entry-pages?period=30d")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // ─── Exit Pages ────────────────────────────────────────────

    @Test
    fun `GET exit-pages returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getExitPages(PROJECT_ID, any(), any(), any(), any())
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/exit-pages?period=30d")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // ─── Sources ───────────────────────────────────────────────

    @Test
    fun `GET sources returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), eq("referrer_source"), any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/sources?period=30d")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // ─── UTM ───────────────────────────────────────────────────

    @Test
    fun `GET utm source returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), eq("utm_source"), any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/utm/source?period=30d")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `GET utm medium returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), eq("utm_medium"), any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/utm/medium?period=30d")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `GET utm campaign returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), eq("utm_campaign"), any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/utm/campaign?period=30d")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // ─── Locations ─────────────────────────────────────────────

    @Test
    fun `GET locations returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), eq("country_code"), any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/locations?period=30d")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // ─── Devices ───────────────────────────────────────────────

    @Test
    fun `GET devices returns 200 with default type`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), eq("device_type"), any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/devices?period=30d")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `GET devices with browser type returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), eq("browser"), any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/devices?period=30d&type=browser")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `GET devices with os type returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), eq("os"), any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/devices?period=30d&type=os")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // ─── Events ────────────────────────────────────────────────

    @Test
    fun `GET events returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getEvents(PROJECT_ID, any(), any(), any(), any())
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/events?period=30d")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // ─── Realtime ──────────────────────────────────────────────

    @Test
    fun `GET realtime returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        every { mockAnalyticsService.getRealtime(PROJECT_ID) } returns realtimeResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/realtime")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"visitors\""))
    }

    // ─── Funnel ────────────────────────────────────────────────

    @Test
    fun `GET funnel returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getFunnel(PROJECT_ID, any(), any(), any())
        } returns funnelResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/funnel?period=30d&steps=/home&steps=/signup")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("\"steps\""))
    }

    @Test
    fun `GET funnel returns 400 when fewer than 2 steps`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        application { installRoutes(this) }
        val r = client.get(authedGet("/funnel?period=30d&steps=/home")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `GET funnel returns 400 with no steps`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        application { installRoutes(this) }
        val r = client.get(authedGet("/funnel?period=30d")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    // ─── Date periods ──────────────────────────────────────────

    @Test
    fun `GET overview with today period returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getOverview(PROJECT_ID, any(), any(), any(), any(), any())
        } returns overviewResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/overview?period=today")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `GET overview with 7d period returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getOverview(PROJECT_ID, any(), any(), any(), any(), any())
        } returns overviewResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/overview?period=7d")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `GET overview with 6mo period returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getOverview(PROJECT_ID, any(), any(), any(), any(), any())
        } returns overviewResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/overview?period=6mo")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `GET overview with 12mo period returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getOverview(PROJECT_ID, any(), any(), any(), any(), any())
        } returns overviewResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/overview?period=12mo")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `GET overview with year_over_year comparison returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getOverview(PROJECT_ID, any(), any(), any(), any(), any())
        } returns overviewResponse.copy(compVisitors = 90, compPageviews = 210)
        application { installRoutes(this) }
        val r = client.get(authedGet("/overview?period=30d&comparison=year_over_year")) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // ─── Filters ───────────────────────────────────────────────

    @Test
    fun `GET pages with filters returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getPages(PROJECT_ID, any(), any(), any(), any())
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(
            authedGet("/pages?period=30d&filters=country_code:is:US&filters=browser:is:Chrome")
        ) {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }
}
