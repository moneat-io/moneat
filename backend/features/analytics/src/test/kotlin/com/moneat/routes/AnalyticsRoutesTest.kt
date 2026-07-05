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
import com.moneat.analytics.models.ProductActivityResponse
import com.moneat.analytics.models.ProductActivitySeries
import com.moneat.analytics.models.ProductAnalyticsSummary
import com.moneat.analytics.models.ProductFeatureAdoptionItem
import com.moneat.analytics.models.ProductKpiMetric
import com.moneat.analytics.models.ProductMover
import com.moneat.analytics.models.ProductRetentionCohortRow
import com.moneat.analytics.models.ProductRetentionGrid
import com.moneat.analytics.models.ProductSegmentRow
import com.moneat.analytics.models.ProductSegmentation
import com.moneat.analytics.models.RealtimeResponse
import com.moneat.analytics.models.RetentionResponse
import com.moneat.analytics.models.TimeseriesPoint
import com.moneat.analytics.routes.analyticsRoutes
import com.moneat.analytics.services.AnalyticsEventsQuery
import com.moneat.analytics.services.AnalyticsFunnelQuery
import com.moneat.analytics.services.AnalyticsQueryScope
import com.moneat.analytics.services.AnalyticsService
import com.moneat.analytics.services.ProductRetentionRequest
import com.moneat.events.services.DashboardService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
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
import kotlin.uuid.Uuid
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsRoutesTest {
    companion object {
        private const val PROJECT_ID = 1L
        private const val SECOND_PROJECT_ID = 2L
        private const val PROJECT_RESOURCE_ID = "018f4ce4-3f2a-7a67-a32b-0c1848f62b9d"
        private const val SECOND_PROJECT_RESOURCE_ID = "118f4ce4-3f2a-7a67-a32b-0c1848f62b9d"
        private const val OVERVIEW_PERIOD_30D = "/overview?period=30d"
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
        seedRouteProjects()
    }

    private fun Application.installAuth() {
        installJwtAuth()
    }

    private fun token(userId: Int, orgId: Int? = null): String =
        RouteTestSupport.createToken(userId, orgId)

    private fun seedUser(): Int = transaction {
        Users.insert {
            it[email] = "analytics-${System.nanoTime()}@test.com"
            it[password_hash] = "hash"
            it[email_verified] = true
        } get Users.id
    }

    private fun seedOrganizationMembership(): Pair<Int, Int> {
        val userId = seedUser()
        val orgId = transaction {
            Organizations.insert {
                it[name] = "Analytics Org"
                it[slug] = "analytics-org-${System.nanoTime()}"
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

    private fun installRoutes(app: Application) {
        app.installAuth()
        app.routing {
            analyticsRoutes(
                analyticsService = mockAnalyticsService,
                dashboardService = mockDashboardService,
            )
        }
    }

    private fun seedRouteProjects() {
        seedProject(PROJECT_ID, PROJECT_RESOURCE_ID, "analytics-primary")
        seedProject(SECOND_PROJECT_ID, SECOND_PROJECT_RESOURCE_ID, "analytics-secondary")
    }

    private fun seedProject(projectId: Long, resourceId: String, slug: String) = transaction {
        Projects.insert {
            it[id] = projectId
            it[resource_id] = Uuid.parse(resourceId)
            it[organization_id] = 1
            it[name] = slug
            it[Projects.slug] = slug
            it[framework] = "otel"
        }
    }

    private fun authedGet(path: String) = "/v1/analytics/$PROJECT_RESOURCE_ID$path"

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
            FunnelStep(name = "/home", visitors = 100, dropoff = 0.0, conversionRate = 100.0),
            FunnelStep(name = "/signup", visitors = 40, dropoff = 60.0, conversionRate = 40.0),
        ),
        overallConversion = 40.0,
    )

    private val retentionResponse = RetentionResponse(
        startEvent = "signup.completed",
        returnEvent = "recording.started",
        cohorts = emptyList(),
    )

    private val productSummaryResponse = ProductAnalyticsSummary(
        weeklyActiveUsers = ProductKpiMetric(value = 120.0, previous = 100.0),
        dailyActiveUsers = 30,
        newUsers = ProductKpiMetric(value = 25.0, previous = 20.0),
        activationRate = ProductKpiMetric(value = 48.0, previous = 42.0),
        stickiness = ProductKpiMetric(value = 35.0, previous = 32.0),
        week1Retention = ProductKpiMetric(value = 44.0, previous = 41.0),
        powerUsers = ProductKpiMetric(value = 12.0, previous = 10.0),
    )

    private val productActivityResponse = ProductActivityResponse(
        series = listOf(ProductActivitySeries(metric = "active", points = emptyList())),
    )

    private val productMoverResponse = listOf(
        ProductMover(
            name = "activated",
            category = "event",
            detail = "40 this period",
            change = "+25%",
            tone = "good",
        )
    )

    private val productFeatureAdoptionResponse = listOf(
        ProductFeatureAdoptionItem(name = "Search", adoptionRate = 38.0)
    )

    private val productSegmentationResponse = ProductSegmentation(
        plan = listOf(
            ProductSegmentRow(
                name = "pro",
                users = 20,
                activationRate = 55.0,
                week1Retention = 40.0,
                stickiness = 30.0,
            )
        ),
        platform = emptyList(),
        country = emptyList(),
    )

    private val productRetentionResponse = ProductRetentionGrid(
        mode = "key_action",
        periods = listOf(0, 1),
        cohorts = listOf(
            ProductRetentionCohortRow(
                cohort = "2026-01-05 00:00:00",
                users = 10,
                values = listOf(100.0, 50.0),
            )
        ),
    )

    // ──── Auth ────

    @Test
    fun `returns 401 when unauthenticated`() = testApplication {
        application { installRoutes(this) }
        val r = client.get(authedGet(OVERVIEW_PERIOD_30D))
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `returns 401 for invalid token`() = testApplication {
        application { installRoutes(this) }
        val badToken = JWT.create()
            .withIssuer("wrong-issuer")
            .withClaim("userId", 1)
            .sign(Algorithm.HMAC256("wrong-secret"))
        val r = client.get(authedGet(OVERVIEW_PERIOD_30D)) {
            header(HttpHeaders.Authorization, "Bearer $badToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `returns 403 when user has no project access`() = testApplication {
        val userId = seedUser()
        stubNoAccess(userId)
        application { installRoutes(this) }
        val r = client.get(authedGet(OVERVIEW_PERIOD_30D)) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `returns 400 for invalid project ID`() = testApplication {
        val userId = seedUser()
        application { installRoutes(this) }
        val r = client.get("/v1/analytics/invalid/overview?period=30d") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `GET org overview forwards service id and name filters`() = testApplication {
        val (userId, orgId) = seedOrganizationMembership()
        every { mockDashboardService.getServiceIdsForOrganization(orgId) } returns
            listOf(PROJECT_ID, SECOND_PROJECT_ID)
        every { mockDashboardService.resolveServiceId(orgId, "API") } returns SECOND_PROJECT_ID
        coEvery {
            mockAnalyticsService.getOverview(
                match<AnalyticsQueryScope> { it.serviceIds == listOf(PROJECT_ID, SECOND_PROJECT_ID) },
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns overviewResponse

        application { installRoutes(this) }
        val route = "/v1/analytics/overview?period=30d&serviceIds=$PROJECT_RESOURCE_ID&services=API"
        val r = client.get(route) {
            withAuth(token(userId, orgId))
        }

        assertEquals(HttpStatusCode.OK, r.status)
        coVerify(exactly = 1) {
            mockAnalyticsService.getOverview(
                match<AnalyticsQueryScope> { it.serviceIds == listOf(PROJECT_ID, SECOND_PROJECT_ID) },
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `GET org pages uses all organization services when filters are absent`() = testApplication {
        val (userId, orgId) = seedOrganizationMembership()
        every { mockDashboardService.getServiceIdsForOrganization(orgId) } returns
            listOf(PROJECT_ID, SECOND_PROJECT_ID)
        coEvery {
            mockAnalyticsService.getPages(
                match<AnalyticsQueryScope> { it.serviceIds == listOf(PROJECT_ID, SECOND_PROJECT_ID) },
                any(),
                any(),
                any(),
                10,
            )
        } returns breakdownResponse

        application { installRoutes(this) }
        val r = client.get("/v1/analytics/pages?period=30d&limit=10") {
            withAuth(token(userId, orgId))
        }

        assertEquals(HttpStatusCode.OK, r.status)
        coVerify(exactly = 1) {
            mockAnalyticsService.getPages(
                match<AnalyticsQueryScope> { it.serviceIds == listOf(PROJECT_ID, SECOND_PROJECT_ID) },
                any(),
                any(),
                any(),
                10,
            )
        }
    }

    @Test
    fun `GET org overview returns 400 for invalid service id filter`() = testApplication {
        val (userId, orgId) = seedOrganizationMembership()

        application { installRoutes(this) }
        val r = client.get("/v1/analytics/overview?serviceIds=not-a-service-id") {
            withAuth(token(userId, orgId))
        }

        assertEquals(HttpStatusCode.BadRequest, r.status)
        coVerify(exactly = 0) {
            mockAnalyticsService.getOverview(
                any<AnalyticsQueryScope>(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `GET org overview returns 404 when user lacks organization access`() = testApplication {
        val userId = seedUser()

        application { installRoutes(this) }
        val r = client.get("/v1/analytics/overview") {
            withAuth(token(userId, 99999))
        }

        assertEquals(HttpStatusCode.NotFound, r.status)
        coVerify(exactly = 0) {
            mockAnalyticsService.getOverview(
                any<AnalyticsQueryScope>(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    // ──── Overview ────

    @Test
    fun `GET overview returns 200 with default period`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getOverview(PROJECT_ID, any(), any(), any(), any(), any())
        } returns overviewResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/overview")) {
            withAuth(token(userId))
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
            withAuth(token(userId))
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
            withAuth(token(userId))
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
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // ──── Timeseries ────

    @Test
    fun `GET timeseries returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getTimeseries(PROJECT_ID, any(), any(), any())
        } returns timeseriesResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/timeseries?period=7d")) {
            withAuth(token(userId))
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
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    // ──── Pages ────

    @Test
    fun `GET pages returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getPages(PROJECT_ID, any(), any(), any(), any())
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/pages?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"results\""))
    }

    @Test
    fun `GET pages respects limit param`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getPages(PROJECT_ID, any(), any(), any(), 10)
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/pages?period=30d&limit=10")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        coVerify(exactly = 1) {
            mockAnalyticsService.getPages(PROJECT_ID, any(), any(), any(), 10)
        }
    }

    // ──── Entry Pages ────

    @Test
    fun `GET entry-pages returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getEntryPages(PROJECT_ID, any(), any(), any(), any())
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/entry-pages?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // ──── Exit Pages ────

    @Test
    fun `GET exit-pages returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getExitPages(PROJECT_ID, any(), any(), any(), any())
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/exit-pages?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // ──── Sources ────

    @Test
    fun `GET sources returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), "referrer_source", any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/sources?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        coVerify(exactly = 1) {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID,
                any(),
                any(),
                any(),
                "referrer_source",
                any()
            )
        }
    }

    // ──── UTM ────

    @Test
    fun `GET utm source returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), "utm_source", any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/utm/source?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        coVerify(exactly = 1) {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID,
                any(),
                any(),
                any(),
                "utm_source",
                any()
            )
        }
    }

    @Test
    fun `GET utm medium returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), "utm_medium", any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/utm/medium?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        coVerify(exactly = 1) {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID,
                any(),
                any(),
                any(),
                "utm_medium",
                any()
            )
        }
    }

    @Test
    fun `GET utm campaign returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), "utm_campaign", any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/utm/campaign?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        coVerify(exactly = 1) {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID,
                any(),
                any(),
                any(),
                "utm_campaign",
                any()
            )
        }
    }

    // ──── Locations ────

    @Test
    fun `GET locations returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), "country_code", any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/locations?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        coVerify(exactly = 1) {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID,
                any(),
                any(),
                any(),
                "country_code",
                any()
            )
        }
    }

    // ──── Devices ────

    @Test
    fun `GET devices returns 200 with default type`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), "device_type", any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/devices?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        coVerify(exactly = 1) {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID,
                any(),
                any(),
                any(),
                "device_type",
                any()
            )
        }
    }

    @Test
    fun `GET devices with browser type returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), "browser", any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/devices?period=30d&type=browser")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        coVerify(exactly = 1) {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID,
                any(),
                any(),
                any(),
                "browser",
                any()
            )
        }
    }

    @Test
    fun `GET devices with os type returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID, any(), any(), any(), "os", any()
            )
        } returns breakdownResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/devices?period=30d&type=os")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        coVerify(exactly = 1) {
            mockAnalyticsService.getBreakdown(
                PROJECT_ID,
                any(),
                any(),
                any(),
                "os",
                any()
            )
        }
    }

    // ──── Events ────

    @Test
    fun `GET events forwards product grouping params`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        var eventsQuery: AnalyticsEventsQuery? = null
        coEvery {
            mockAnalyticsService.getEvents(PROJECT_ID, any<AnalyticsEventsQuery>())
        } answers {
            eventsQuery = invocation.args[1] as AnalyticsEventsQuery
            breakdownResponse
        }
        application { installRoutes(this) }
        val r = client.get(authedGet("/events?period=30d&limit=25&group_by=user_id&source=server")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        coVerify(exactly = 1) {
            mockAnalyticsService.getEvents(PROJECT_ID, any<AnalyticsEventsQuery>())
        }
        val capturedQuery = checkNotNull(eventsQuery)
        assertEquals(25, capturedQuery.limit)
        assertEquals("user_id", capturedQuery.groupBy)
        assertEquals("server", capturedQuery.source)
    }

    // ──── Realtime ────

    @Test
    fun `GET realtime returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        every { mockAnalyticsService.getRealtime(PROJECT_ID) } returns realtimeResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/realtime")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"visitors\""))
    }

    // ──── Funnel ────

    @Test
    fun `GET funnel returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getFunnel(PROJECT_ID, any<AnalyticsFunnelQuery>())
        } returns funnelResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/funnel?period=30d&steps=/home&steps=/signup")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("\"steps\""))
    }

    @Test
    fun `GET funnel forwards product grouping params`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        var funnelQuery: AnalyticsFunnelQuery? = null
        coEvery {
            mockAnalyticsService.getFunnel(PROJECT_ID, any<AnalyticsFunnelQuery>())
        } answers {
            funnelQuery = invocation.args[1] as AnalyticsFunnelQuery
            funnelResponse
        }
        application { installRoutes(this) }
        val path = "/funnel?period=30d&steps[]=signup.completed" +
            "&steps[]=recording.started&group_by=user_id&source=server"
        val r = client.get(authedGet(path)) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        coVerify(exactly = 1) {
            mockAnalyticsService.getFunnel(
                PROJECT_ID,
                any<AnalyticsFunnelQuery>(),
            )
        }
        val capturedQuery = checkNotNull(funnelQuery)
        assertEquals(listOf("signup.completed", "recording.started"), capturedQuery.steps)
        assertEquals("user_id", capturedQuery.groupBy)
        assertEquals("server", capturedQuery.source)
    }

    @Test
    fun `GET retention returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getRetention(
                PROJECT_ID,
                any(),
                any(),
                "signup.completed",
                "recording.started",
                listOf(1, 7, 30),
            )
        } returns retentionResponse
        application { installRoutes(this) }
        val path = "/retention?period=30d&start_event=signup.completed" +
            "&return_event=recording.started&periods[]=1&periods[]=7&periods[]=30"
        val r = client.get(
            authedGet(path)
        ) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"cohorts\""))
    }

    @Test
    fun `GET retention returns 400 without events`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        application { installRoutes(this) }
        val r = client.get(authedGet("/retention?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `GET org product summary forwards organization service scope`() = testApplication {
        val (userId, orgId) = seedOrganizationMembership()
        every { mockDashboardService.getServiceIdsForOrganization(orgId) } returns
            listOf(PROJECT_ID, SECOND_PROJECT_ID)
        coEvery {
            mockAnalyticsService.getProductAnalyticsSummary(
                match<AnalyticsQueryScope> { it.serviceIds == listOf(PROJECT_ID, SECOND_PROJECT_ID) },
                any(),
                any(),
                any(),
            )
        } returns productSummaryResponse

        application { installRoutes(this) }
        val r = client.get("/v1/analytics/product/summary?period=30d") {
            withAuth(token(userId, orgId))
        }

        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"weeklyActiveUsers\""))
    }

    @Test
    fun `GET product summary returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getProductAnalyticsSummary(PROJECT_ID, any(), any(), any())
        } returns productSummaryResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/product/summary?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"activationRate\""))
    }

    @Test
    fun `GET product activity returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getProductActivity(PROJECT_ID, any(), any(), any())
        } returns productActivityResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/product/activity?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"series\""))
    }

    @Test
    fun `GET product movers returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getProductMovers(PROJECT_ID, any(), any(), any())
        } returns productMoverResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/product/movers?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"change\""))
    }

    @Test
    fun `GET product feature adoption returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getProductFeatureAdoption(PROJECT_ID, any(), any(), any())
        } returns productFeatureAdoptionResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/product/feature-adoption?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"adoptionRate\""))
    }

    @Test
    fun `GET product segmentation returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getProductSegmentation(PROJECT_ID, any(), any(), any())
        } returns productSegmentationResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/product/segmentation?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"plan\""))
    }

    @Test
    fun `GET product retention returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getProductRetention(
                PROJECT_ID,
                match<ProductRetentionRequest> { it.mode == "key_action" && it.periodCount == 2 },
            )
        } returns productRetentionResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/product/retention?period=30d&mode=key_action&periods=2")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"cohorts\""))
    }

    @Test
    fun `GET product retention requires custom event for custom mode`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        application { installRoutes(this) }
        val r = client.get(authedGet("/product/retention?period=30d&mode=custom")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `GET funnel returns 400 when fewer than 2 steps`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        application { installRoutes(this) }
        val r = client.get(authedGet("/funnel?period=30d&steps=/home")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `GET funnel returns 400 with no steps`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        application { installRoutes(this) }
        val r = client.get(authedGet("/funnel?period=30d")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    // ──── Date periods ────

    @Test
    fun `GET overview with today period returns 200`() = testApplication {
        val userId = seedUser()
        stubAccess(userId)
        coEvery {
            mockAnalyticsService.getOverview(PROJECT_ID, any(), any(), any(), any(), any())
        } returns overviewResponse
        application { installRoutes(this) }
        val r = client.get(authedGet("/overview?period=today")) {
            withAuth(token(userId))
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
            withAuth(token(userId))
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
            withAuth(token(userId))
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
            withAuth(token(userId))
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
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // ──── Filters ────

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
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }
}
