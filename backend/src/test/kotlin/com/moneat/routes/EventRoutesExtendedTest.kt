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
import com.moneat.events.models.AlertNotificationPreference
import com.moneat.events.models.FeedbackDetailResponse
import com.moneat.events.models.FeedbackListItem
import com.moneat.events.models.ProjectKeyResponse
import com.moneat.events.models.ProjectResponse
import com.moneat.events.models.ProjectStatsResponse
import com.moneat.events.models.ReleaseDetailStats
import com.moneat.events.models.ReleaseListResponse
import com.moneat.events.models.ReplayDetailResponse
import com.moneat.events.models.ReplayListItem
import com.moneat.events.models.ReplayRecordingResponse
import com.moneat.events.models.ReplayTimelineResponse
import com.moneat.events.models.SpanDetailResponse
import com.moneat.events.models.SpanResponse
import com.moneat.events.models.TimelinePoint
import com.moneat.events.models.TopIssue
import com.moneat.events.models.TraceDetailResponse
import com.moneat.events.models.TransactionDetailResponse
import com.moneat.events.routes.apiRoutes
import com.moneat.events.services.DashboardService
import com.moneat.notifications.services.AlertNotificationPreferencesService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.NotificationPreferences
import com.moneat.shared.models.OnCallPhoneConsentEvents
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installApiRouteRateLimits
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventRoutesExtendedTest {
    companion object {
        private var db: Database? = null
        private const val TEST_PROJECT_NAME = "Test Project"
        private const val SENTINEL_ID = 999L
        private const val ISSUE_ALERTS_FALSE = """{"issueAlerts":false}"""
        private const val DEFAULT_PAGE = 1
        private const val DEFAULT_PAGE_SIZE = 25
        private const val V1_PROJECTS = "/v1/projects"
        private const val DSN_KEY_AT_HOST = "https://key@host/1"
        private const val TRACE_ABC = "trace-abc"
        private const val REPLAY_D1 = "replay-d1"
        private const val TIMESTAMP_2026_01_01 = "2026-01-01T00:00:00Z"
        private const val URL_EXAMPLE_COM = "https://example.com"
        private const val TEST_PROJECT_RESOURCE_ID = "018f4ce4-3f2a-7a67-a32b-0c1848f62b9d"
    }

    private val mockDashboardService = mockk<DashboardService>(relaxed = true)
    private val mockAlertPrefsService = mockk<AlertNotificationPreferencesService>(relaxed = true)

    private data class SeededUserProject(
        val userId: Int,
        val orgId: Int,
        val projectId: Long
    )

    @BeforeTest
    fun setup() {
        startTestKoin()
        loadKoinModules(
            module {
                single<DashboardService> { mockDashboardService }
                single<AlertNotificationPreferencesService> { mockAlertPrefsService }
            }
        )
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_ext_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            Projects,
            NotificationPreferences,
            OnCallPhoneConsentEvents
        )
    }

    @AfterTest
    fun teardown() {
        stopTestKoin()
    }

    private fun Application.installTestApp() {
        installJwtAuth()
        installApiRouteRateLimits("test-user")
        routing { apiRoutes() }
    }

    private fun token(userId: Int): String =
        RouteTestSupport.createToken(userId = userId, orgId = orgIdForUser(userId))

    private fun token(
        userId: Int,
        orgId: Int
    ): String =
        RouteTestSupport.createToken(userId = userId, orgId = orgId)

    private fun orgIdForUser(userId: Int): Int? =
        transaction {
            Memberships
                .selectAll()
                .where { Memberships.user_id eq userId }
                .firstOrNull()
                ?.get(Memberships.organization_id)
        }

    private fun demoToken(): String =
        JWT.create().withIssuer("moneat").withAudience("moneat-users")
            .withClaim("userId", -1)
            .withClaim("email", "demo@moneat.dev")
            .withClaim("isDemo", true)
            .sign(Algorithm.HMAC256(RouteTestSupport.TEST_JWT_SECRET))

    private fun seedUserWithProject(): Pair<Int, Long> =
        seedUserProject().let { it.userId to it.projectId }

    private fun seedUserProject(): SeededUserProject {
        val orgId = transaction {
            Organizations.insert {
                it[name] = "Ext Test Org"
                it[slug] = "ext-org-${System.nanoTime()}"
            } get Organizations.id
        }
        val userId = transaction {
            Users.insert {
                it[email] = "ext-${System.nanoTime()}@test.com"
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
                it[name] = "Ext Test Project"
                it[slug] = "ext-project-${System.nanoTime()}"
            } get Projects.id
        }
        return SeededUserProject(userId = userId, orgId = orgId, projectId = projectId)
    }

    private fun projectResourceId(projectId: Long): String = transaction {
        Projects
            .selectAll()
            .where { Projects.id eq projectId }
            .first()[Projects.resource_id]
            .toString()
    }

    private fun organizationResourceId(orgId: Int): String = transaction {
        Organizations
            .selectAll()
            .where { Organizations.id eq orgId }
            .first()[Organizations.resource_id]
            .toString()
    }

    private fun projectApiPath(projectId: Long, suffix: String = ""): String =
        "$V1_PROJECTS/${projectResourceId(projectId)}$suffix"

    private fun serviceIdsQuery(vararg projectIds: Long): String =
        projectIds.joinToString(",") { projectResourceId(it) }

    // ──── GET /v1/projects ────

    @Test
    fun `GET projects returns 200 with project list`() = testApplication {
        val seeded = seedUserProject()
        coEvery {
            mockDashboardService.getProjects(seeded.orgId, any())
        } returns listOf(sampleProject())

        application { installTestApp() }
        val response = client.get(V1_PROJECTS) {
            withAuth(token(seeded.userId, seeded.orgId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(TEST_PROJECT_NAME))
    }

    @Test
    fun `GET projects returns 401 without auth`() = testApplication {
        application { installTestApp() }
        val response = client.get(V1_PROJECTS)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ──── POST /v1/projects ────

    @Test
    fun `POST projects returns 201 on success`() = testApplication {
        val seeded = seedUserProject()
        coEvery {
            mockDashboardService.createProject(seeded.orgId, any())
        } returns sampleProject()

        application { installTestApp() }
        val response = client.post(V1_PROJECTS) {
            withAuth(token(seeded.userId, seeded.orgId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"New Project"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.bodyAsText().contains(TEST_PROJECT_NAME))
    }

    @Test
    fun `POST projects returns 403 when project limit reached`() = testApplication {
        val seeded = seedUserProject()
        coEvery {
            mockDashboardService.createProject(seeded.orgId, any())
        } throws IllegalStateException("project_limit_reached")

        application { installTestApp() }
        val response = client.post(V1_PROJECTS) {
            withAuth(token(seeded.userId, seeded.orgId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"New Project"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("project_limit_reached"))
    }

    @Test
    fun `POST projects returns 400 on other errors`() = testApplication {
        val seeded = seedUserProject()
        coEvery {
            mockDashboardService.createProject(seeded.orgId, any())
        } throws IllegalStateException("Invalid project name")

        application { installTestApp() }
        val response = client.post(V1_PROJECTS) {
            withAuth(token(seeded.userId, seeded.orgId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ──── GET /v1/projects/{projectId} ────

    @Test
    fun `GET project detail returns 200`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery { mockDashboardService.getProject(projectId) } returns sampleProject()

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId)) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET project detail returns 403 without access`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns false

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId)) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET project detail returns 404 when not found`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery { mockDashboardService.getProject(projectId) } returns null

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId)) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET project detail returns 400 for invalid id`() = testApplication {
        val (userId, _) = seedUserWithProject()

        application { installTestApp() }
        val response = client.get("/v1/projects/abc") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ──── PUT /v1/projects/{projectId} ────

    @Test
    fun `PUT project returns 200 on success`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        every { mockDashboardService.updateProject(projectId, any()) } just runs

        application { installTestApp() }
        val response = client.put(projectApiPath(projectId)) {
            withAuth(token(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Updated Name"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `PUT project returns 403 without access`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns false

        application { installTestApp() }
        val response = client.put(projectApiPath(projectId)) {
            withAuth(token(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Updated Name"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ──── DELETE /v1/projects/{projectId} ────

    @Test
    fun `DELETE project returns 204 on success`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        every { mockDashboardService.deleteProject(projectId) } just runs

        application { installTestApp() }
        val response = client.delete(projectApiPath(projectId)) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `DELETE project returns 403 without access`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns false

        application { installTestApp() }
        val response = client.delete(projectApiPath(projectId)) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ──── POST /v1/projects/{projectId}/targets ────

    @Test
    fun `POST project target returns 201 on success`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        every {
            mockDashboardService.addProjectTarget(projectId, "flutter")
        } returns ProjectKeyResponse(platformTarget = "flutter", dsn = DSN_KEY_AT_HOST)

        application { installTestApp() }
        val response = client.post(projectApiPath(projectId, "/targets")) {
            withAuth(token(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"target":"flutter"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.bodyAsText().contains("flutter"))
    }

    @Test
    fun `POST project target returns 409 on duplicate`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        every {
            mockDashboardService.addProjectTarget(projectId, "flutter")
        } throws IllegalStateException("Target already exists")

        application { installTestApp() }
        val response = client.post(projectApiPath(projectId, "/targets")) {
            withAuth(token(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"target":"flutter"}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    // ──── GET /v1/projects/{projectId}/stats ────

    @Test
    fun `GET project stats returns 200`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery {
            mockDashboardService.getProjectStats(projectId, "7d", any(), any())
        } returns sampleProjectStats()

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/stats")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("totalEvents"))
    }

    @Test
    fun `GET project stats returns 403 without access`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns false

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/stats")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET project stats forwards period param`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery {
            mockDashboardService.getProjectStats(projectId, "24h", any(), any())
        } returns sampleProjectStats()

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/stats?period=24h")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        coVerify { mockDashboardService.getProjectStats(projectId, "24h", any(), any()) }
    }

    // ──── Trace routes ────

    @Test
    fun `GET trace detail returns 200`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        coEvery { mockDashboardService.hasTraceAccess(userId, projectId) } returns true
        coEvery {
            mockDashboardService.getTraceDetails(projectId, TRACE_ABC)
        } returns sampleTraceDetail(projectId)

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/traces/$TRACE_ABC")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(TRACE_ABC))
    }

    @Test
    fun `GET trace detail returns 403 without access`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        coEvery { mockDashboardService.hasTraceAccess(userId, projectId) } returns false

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/traces/$TRACE_ABC")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET trace detail returns 404 when not found`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        coEvery { mockDashboardService.hasTraceAccess(userId, projectId) } returns true
        coEvery { mockDashboardService.getTraceDetails(projectId, "missing") } returns null

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/traces/missing")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ──── Span routes ────

    @Test
    fun `GET span detail returns 200`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        coEvery { mockDashboardService.hasSpanAccess(userId, projectId) } returns true
        coEvery {
            mockDashboardService.getSpanDetails(projectId, "span-abc")
        } returns sampleSpanDetail()

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/spans/span-abc")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("db.query"))
    }

    @Test
    fun `GET span detail returns 403 without access`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        coEvery { mockDashboardService.hasSpanAccess(userId, projectId) } returns false

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/spans/span-abc")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET span detail returns 404 when not found`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        coEvery { mockDashboardService.hasSpanAccess(userId, projectId) } returns true
        coEvery { mockDashboardService.getSpanDetails(projectId, "missing") } returns null

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/spans/missing")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ──── Replay routes ────

    @Test
    fun `GET replays returns 200`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery {
            mockDashboardService.getReplays(projectId, DEFAULT_PAGE, DEFAULT_PAGE_SIZE, null, "7d", any())
        } returns listOf(sampleReplayListItem(projectId))

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/replays")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("replay-1"))
    }

    @Test
    fun `GET replays returns 403 without access`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns false

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/replays")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET replays forwards pagination params`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery {
            mockDashboardService.getReplays(projectId, 2, 10, "production", "30d", any())
        } returns emptyList()

        application { installTestApp() }
        val url = projectApiPath(projectId, "/replays?page=2&limit=10&environment=production&period=30d")
        val response = client.get(url) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        coVerify {
            mockDashboardService.getReplays(projectId, 2, 10, "production", "30d", any())
        }
    }

    @Test
    fun `GET org replays filters to requested services`() = testApplication {
        val seed = seedUserProject()
        every { mockDashboardService.getServiceIdsForOrganization(seed.orgId) } returns
            listOf(seed.projectId, SENTINEL_ID)
        coEvery {
            mockDashboardService.getReplaysForServices(
                seed.orgId,
                listOf(seed.projectId),
                2,
                10,
                "production",
                "30d",
                any()
            )
        } returns listOf(sampleReplayListItem(seed.projectId))

        application { installTestApp() }
        val serviceId = projectResourceId(seed.projectId)
        val url = "/v1/replays?page=2&limit=10&environment=production&period=30d&serviceId=$serviceId"
        val response = client.get(url) {
            withAuth(token(seed.userId, seed.orgId))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("replay-1"))
        coVerify {
            mockDashboardService.getReplaysForServices(
                seed.orgId,
                listOf(seed.projectId),
                2,
                10,
                "production",
                "30d",
                any()
            )
        }
    }

    @Test
    fun `GET org replays returns 400 for invalid service id`() = testApplication {
        val seed = seedUserProject()

        application { installTestApp() }
        val response = client.get("/v1/replays?serviceIds=not-a-service") {
            withAuth(token(seed.userId, seed.orgId))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET replay detail returns 200`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasReplayAccess(userId, REPLAY_D1) } returns true
        coEvery {
            mockDashboardService.getReplay(REPLAY_D1, any())
        } returns sampleReplayDetail()

        application { installTestApp() }
        val response = client.get("/v1/replays/$REPLAY_D1") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(REPLAY_D1))
    }

    @Test
    fun `GET replay detail returns 403 without access`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasReplayAccess(userId, "replay-no") } returns false

        application { installTestApp() }
        val response = client.get("/v1/replays/replay-no") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET replay detail returns 404 when not found`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasReplayAccess(userId, "replay-miss") } returns true
        coEvery { mockDashboardService.getReplay("replay-miss", any()) } returns null

        application { installTestApp() }
        val response = client.get("/v1/replays/replay-miss") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET replay recording returns 200`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasReplayAccess(userId, "replay-rec") } returns true
        coEvery {
            mockDashboardService.getReplayRecording("replay-rec")
        } returns ReplayRecordingResponse(events = emptyList<JsonElement>())

        application { installTestApp() }
        val response = client.get("/v1/replays/replay-rec/recording") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET replay recording returns 404 when not found`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasReplayAccess(userId, "replay-rec-m") } returns true
        coEvery { mockDashboardService.getReplayRecording("replay-rec-m") } returns null

        application { installTestApp() }
        val response = client.get("/v1/replays/replay-rec-m/recording") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET replay timeline returns 200`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasReplayAccess(userId, "replay-tl") } returns true
        coEvery {
            mockDashboardService.getReplayTimeline("replay-tl", any())
        } returns ReplayTimelineResponse(items = emptyList(), replayStartMs = 0L)

        application { installTestApp() }
        val response = client.get("/v1/replays/replay-tl/timeline") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET issue replays returns 200`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasIssueAccess(userId, "issue-rep-1") } returns true
        coEvery {
            mockDashboardService.getReplaysForIssue("issue-rep-1", 10)
        } returns emptyList()

        application { installTestApp() }
        val response = client.get("/v1/issues/issue-rep-1/replays") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET issue replays returns 403 without access`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasIssueAccess(userId, "issue-rep-no") } returns false

        application { installTestApp() }
        val response = client.get("/v1/issues/issue-rep-no/replays") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ──── Feedback routes ────

    @Test
    fun `GET feedback list returns 200`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery {
            mockDashboardService.getFeedback(projectId, 1, 25, null, any())
        } returns listOf(sampleFeedbackListItem())

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/feedback")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("fb-1"))
    }

    @Test
    fun `GET org feedback resolves service names`() = testApplication {
        val seed = seedUserProject()
        every { mockDashboardService.resolveServiceId(seed.orgId, "checkout") } returns seed.projectId
        every { mockDashboardService.getServiceIdsForOrganization(seed.orgId) } returns listOf(seed.projectId)
        coEvery {
            mockDashboardService.getFeedbackForServices(
                seed.orgId,
                listOf(seed.projectId),
                1,
                25,
                "resolved",
                any()
            )
        } returns listOf(sampleFeedbackListItem())

        application { installTestApp() }
        val response = client.get("/v1/feedback?services=checkout&status=resolved") {
            withAuth(token(seed.userId, seed.orgId))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("fb-1"))
        coVerify {
            mockDashboardService.getFeedbackForServices(
                seed.orgId,
                listOf(seed.projectId),
                1,
                25,
                "resolved",
                any()
            )
        }
    }

    @Test
    fun `GET feedback list returns 403 without access`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns false

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/feedback")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET feedback detail returns 200`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasFeedbackAccess(userId, "fb-d1") } returns true
        coEvery {
            mockDashboardService.getFeedbackDetail("fb-d1")
        } returns sampleFeedbackDetail()

        application { installTestApp() }
        val response = client.get("/v1/feedback/fb-d1") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("fb-d1"))
    }

    @Test
    fun `GET feedback detail returns 403 without access`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasFeedbackAccess(userId, "fb-no") } returns false

        application { installTestApp() }
        val response = client.get("/v1/feedback/fb-no") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET feedback detail returns 404 when not found`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasFeedbackAccess(userId, "fb-miss") } returns true
        coEvery { mockDashboardService.getFeedbackDetail("fb-miss") } returns null

        application { installTestApp() }
        val response = client.get("/v1/feedback/fb-miss") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `PATCH feedback returns 200 on success`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasFeedbackAccess(userId, "fb-patch") } returns true
        coEvery { mockDashboardService.updateFeedback("fb-patch", any()) } just runs

        application { installTestApp() }
        val response = client.patch("/v1/feedback/fb-patch") {
            withAuth(token(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"status":"resolved"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `PATCH feedback returns 403 without access`() = testApplication {
        val (userId, _) = seedUserWithProject()
        coEvery { mockDashboardService.hasFeedbackAccess(userId, "fb-p-no") } returns false

        application { installTestApp() }
        val response = client.patch("/v1/feedback/fb-p-no") {
            withAuth(token(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"status":"resolved"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ──── Release routes ────

    @Test
    fun `GET releases returns 200`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery {
            mockDashboardService.getReleases(projectId, any())
        } returns listOf(sampleRelease())

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/releases")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("1.0.0"))
    }

    @Test
    fun `GET org releases uses all organization services`() = testApplication {
        val seed = seedUserProject()
        every { mockDashboardService.getServiceIdsForOrganization(seed.orgId) } returns listOf(seed.projectId)
        coEvery {
            mockDashboardService.getReleasesForServices(seed.orgId, listOf(seed.projectId), any())
        } returns listOf(sampleRelease())

        application { installTestApp() }
        val response = client.get("/v1/releases") {
            withAuth(token(seed.userId, seed.orgId))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("1.0.0"))
        coVerify {
            mockDashboardService.getReleasesForServices(seed.orgId, listOf(seed.projectId), any())
        }
    }

    @Test
    fun `GET releases returns 403 without access`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns false

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/releases")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET release stats returns 200`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery {
            mockDashboardService.getReleaseStats(projectId, "1.0.0")
        } returns sampleReleaseStats()

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/releases/1.0.0/stats")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("1.0.0"))
    }

    @Test
    fun `GET org release stats filters service ids to organization services`() = testApplication {
        val seed = seedUserProject()
        every { mockDashboardService.getServiceIdsForOrganization(seed.orgId) } returns listOf(seed.projectId)
        coEvery {
            mockDashboardService.getReleaseStatsForServices(seed.orgId, listOf(seed.projectId), "1.0.0")
        } returns sampleReleaseStats()

        application { installTestApp() }
        val serviceIds = serviceIdsQuery(seed.projectId)
        val response = client.get("/v1/releases/1.0.0/stats?serviceIds=$serviceIds") {
            withAuth(token(seed.userId, seed.orgId))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("1.0.0"))
        coVerify {
            mockDashboardService.getReleaseStatsForServices(seed.orgId, listOf(seed.projectId), "1.0.0")
        }
    }

    @Test
    fun `GET org release stats rejects service ids outside organization`() = testApplication {
        val seed = seedUserProject()
        val other = seedUserProject()

        application { installTestApp() }
        val response = client.get("/v1/releases/1.0.0/stats?serviceIds=${projectResourceId(other.projectId)}") {
            withAuth(token(seed.userId, seed.orgId))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        coVerify(exactly = 0) {
            mockDashboardService.getReleaseStatsForServices(seed.orgId, any(), "1.0.0")
        }
    }

    @Test
    fun `GET org release stats returns 404 when not found`() = testApplication {
        val seed = seedUserProject()
        every { mockDashboardService.getServiceIdsForOrganization(seed.orgId) } returns listOf(seed.projectId)
        coEvery {
            mockDashboardService.getReleaseStatsForServices(seed.orgId, listOf(seed.projectId), "9.9.9")
        } returns null

        application { installTestApp() }
        val response = client.get("/v1/releases/9.9.9/stats") {
            withAuth(token(seed.userId, seed.orgId))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET release stats returns 404 when not found`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true
        coEvery { mockDashboardService.getReleaseStats(projectId, "9.9.9") } returns null

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/releases/9.9.9/stats")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET release stats returns 403 without access`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns false

        application { installTestApp() }
        val response = client.get(projectApiPath(projectId, "/releases/1.0.0/stats")) {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ──── GET /v1/user ────

    @Test
    fun `GET user returns 200 with user data`() = testApplication {
        val seeded = seedUserProject()

        application { installTestApp() }
        val response = client.get("/v1/user") {
            withAuth(token(seeded.userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("email"))
        val userJson = Json.parseToJsonElement(body).jsonObject
        assertEquals(
            organizationResourceId(seeded.orgId),
            userJson["orgId"]?.jsonPrimitive?.content,
            "Response should include orgId: $body"
        )
    }

    @Test
    fun `GET user returns 401 without auth`() = testApplication {
        application { installTestApp() }
        val response = client.get("/v1/user")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ──── Notification preferences ────

    @Test
    fun `GET notification-preferences returns 200 with defaults`() = testApplication {
        val (userId, _) = seedUserWithProject()

        application { installTestApp() }
        val response = client.get("/v1/notification-preferences") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("issueAlerts"))
    }

    @Test
    fun `GET notification-preferences returns project resource IDs`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        val resourceId = projectResourceId(projectId)
        transaction {
            NotificationPreferences.insert {
                it[user_id] = userId
                it[project_id] = projectId
                it[issue_alerts] = false
            }
        }

        application { installTestApp() }
        val response = client.get("/v1/notification-preferences") {
            withAuth(token(userId))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains(resourceId))
        assertTrue(body.contains("Ext Test Project"))
    }

    @Test
    fun `PUT notification-preferences returns 401 without auth`() = testApplication {
        application { installTestApp() }
        val response = client.put("/v1/notification-preferences") {
            contentType(ContentType.Application.Json)
            setBody(ISSUE_ALERTS_FALSE)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `PUT project notification-preferences returns 401 without auth`() = testApplication {
        application { installTestApp() }
        val response = client.put("/v1/notification-preferences/1") {
            contentType(ContentType.Application.Json)
            setBody(ISSUE_ALERTS_FALSE)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `PUT project notification-preferences returns 403 without access`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns false

        application { installTestApp() }
        val response = client.put("/v1/notification-preferences/${projectResourceId(projectId)}") {
            withAuth(token(userId))
            contentType(ContentType.Application.Json)
            setBody(ISSUE_ALERTS_FALSE)
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `DELETE project notification-preferences returns 204`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns true

        application { installTestApp() }
        val response = client.delete("/v1/notification-preferences/${projectResourceId(projectId)}") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `DELETE project notification-preferences returns 403 without access`() = testApplication {
        val (userId, projectId) = seedUserWithProject()
        every { mockDashboardService.hasProjectAccess(userId, projectId) } returns false

        application { installTestApp() }
        val response = client.delete("/v1/notification-preferences/${projectResourceId(projectId)}") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ──── Alert notification preferences ────

    @Test
    fun `GET alert-notification-preferences returns 200`() = testApplication {
        val (userId, _) = seedUserWithProject()
        every {
            mockAlertPrefsService.getPreferences(userId, any())
        } returns emptyList()

        application { installTestApp() }
        val response = client.get("/v1/alert-notification-preferences") {
            withAuth(token(userId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("preferences"))
    }

    @Test
    fun `PUT alert-notification-preferences uses JWT org`() = testApplication {
        val seeded = seedUserProject()
        every {
            mockAlertPrefsService.updatePreference(
                userId = seeded.userId,
                organizationId = seeded.orgId,
                alertSource = "uptime",
                emailEnabled = false,
                slackEnabled = true,
                discordEnabled = false
            )
        } returns AlertNotificationPreference(
            alertSource = "uptime",
            emailEnabled = false,
            slackEnabled = true,
            discordEnabled = false
        )

        application { installTestApp() }
        val response =
            client.put("/v1/alert-notification-preferences/uptime") {
                withAuth(token(seeded.userId, seeded.orgId))
                contentType(ContentType.Application.Json)
                setBody("""{"emailEnabled":false,"slackEnabled":true,"discordEnabled":false}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("uptime"))
    }

    // ──── Demo user access ────

    @Test
    fun `demo user can access replays`() = testApplication {
        val projectId = seedUserWithProject().second
        val resourceId = projectResourceId(projectId)
        coEvery {
            mockDashboardService.getReplays(any(), any(), any(), any(), any(), any())
        } returns emptyList()

        application { installTestApp() }
        val response = client.get("/v1/projects/$resourceId/replays") {
            withAuth(demoToken())
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `demo user can access feedback`() = testApplication {
        val projectId = seedUserWithProject().second
        val resourceId = projectResourceId(projectId)
        coEvery {
            mockDashboardService.getFeedback(any(), any(), any(), any(), any())
        } returns emptyList()

        application { installTestApp() }
        val response = client.get("/v1/projects/$resourceId/feedback") {
            withAuth(demoToken())
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `demo user can access project stats`() = testApplication {
        val projectId = seedUserWithProject().second
        val resourceId = projectResourceId(projectId)
        coEvery {
            mockDashboardService.getProjectStats(any(), any(), any(), any())
        } returns sampleProjectStats()

        application { installTestApp() }
        val response = client.get("/v1/projects/$resourceId/stats") {
            withAuth(demoToken())
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `demo user can access releases`() = testApplication {
        val projectId = seedUserWithProject().second
        val resourceId = projectResourceId(projectId)
        coEvery {
            mockDashboardService.getReleases(any(), any())
        } returns emptyList()

        application { installTestApp() }
        val response = client.get("/v1/projects/$resourceId/releases") {
            withAuth(demoToken())
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ──── Helpers ────

    private fun sampleProject() = ProjectResponse(
        id = "018f4ce4-3f2a-7a67-a32b-0c1848f62b9d",
        name = TEST_PROJECT_NAME,
        slug = "test-project",
        framework = "kotlin",
        keys = listOf(ProjectKeyResponse(platformTarget = null, dsn = DSN_KEY_AT_HOST)),
        dsn = DSN_KEY_AT_HOST
    )

    private fun sampleProjectStats() = ProjectStatsResponse(
        totalEvents = 500,
        totalIssues = 20,
        unresolvedIssues = 10,
        affectedUsers = 50,
        eventsTimeline = listOf(TimelinePoint(TIMESTAMP_2026_01_01, 100)),
        eventsByLevel = mapOf("error" to 400L, "warning" to 100L),
        eventsByPlatform = mapOf("java" to 500L),
        eventsByBrowser = emptyMap(),
        eventsByEnvironment = mapOf("production" to 500L),
        issuesByStatus = mapOf("unresolved" to 10L, "resolved" to 10L),
        topIssues = listOf(TopIssue("issue-1", "NPE", 100)),
        usersTimeline = listOf(TimelinePoint(TIMESTAMP_2026_01_01, 50))
    )

    private fun sampleTraceDetail(projectId: Long) = TraceDetailResponse(
        traceId = TRACE_ABC,
        projectId = projectResourceId(projectId),
        spans = listOf(
            SpanResponse(
                spanId = "span-1",
                op = "http.server",
                description = "GET /api",
                startTimestamp = 1704067200.0,
                endTimestamp = 1704067200.1,
                duration = 100.0
            )
        ),
        startTimestamp = 1704067200.0,
        endTimestamp = 1704067200.1,
        duration = 100.0
    )

    private fun sampleSpanDetail() = SpanDetailResponse(
        span = SpanResponse(
            spanId = "span-abc",
            op = "db.query",
            description = "SELECT * FROM users",
            startTimestamp = 1704067200.0,
            endTimestamp = 1704067200.05,
            duration = 50.0
        ),
        transaction = TransactionDetailResponse(
            eventId = "txn-1",
            name = "GET /api",
            op = "http.server",
            startTimestamp = 1704067200.0,
            duration = 100.0,
            traceId = "trace-1",
            timestamp = TIMESTAMP_2026_01_01,
            environment = "production",
            release = "1.0.0",
            status = "ok",
            tags = emptyMap(),
            contexts = "{}"
        )
    )

    private fun sampleReplayListItem(projectId: Long = 1L) = ReplayListItem(
        replayId = "replay-1",
        projectId = projectResourceId(projectId),
        startedAt = TIMESTAMP_2026_01_01,
        finishedAt = "2026-01-01T00:05:00Z",
        durationMs = 300000.0,
        urls = listOf(URL_EXAMPLE_COM),
        errorCount = 2,
        user = null,
        browserName = "Chrome",
        browserVersion = "120",
        osName = "macOS",
        osVersion = "14.0",
        activity = 5
    )

    private fun sampleReplayDetail() = ReplayDetailResponse(
        replayId = REPLAY_D1,
        projectId = TEST_PROJECT_RESOURCE_ID,
        startedAt = TIMESTAMP_2026_01_01,
        finishedAt = "2026-01-01T00:05:00Z",
        durationMs = 300000.0,
        urls = listOf(URL_EXAMPLE_COM),
        errorCount = 2,
        errorIds = listOf("err-1"),
        traceIds = listOf("trace-1"),
        segmentCount = 10,
        environment = "production",
        release = "1.0.0",
        platform = "javascript",
        user = null,
        browserName = "Chrome",
        browserVersion = "120",
        osName = "macOS",
        osVersion = "14.0",
        activity = 5,
        tags = emptyMap()
    )

    private fun sampleFeedbackListItem() = FeedbackListItem(
        feedbackId = "fb-1",
        message = "Great app!",
        contactEmail = "user@test.com",
        name = "Test User",
        url = URL_EXAMPLE_COM,
        status = "new",
        timestamp = TIMESTAMP_2026_01_01,
        environment = "production",
        release = "1.0.0",
        platform = "javascript",
        user = null,
        associatedEventId = null,
        replayId = null
    )

    private fun sampleFeedbackDetail() = FeedbackDetailResponse(
        feedbackId = "fb-d1",
        message = "Bug report",
        contactEmail = "user@test.com",
        name = "Test User",
        url = URL_EXAMPLE_COM,
        status = "new",
        timestamp = TIMESTAMP_2026_01_01,
        environment = "production",
        release = "1.0.0",
        platform = "javascript",
        user = null,
        associatedEventId = null,
        replayId = null,
        tags = mapOf("browser" to "Chrome"),
        sdkName = "sentry.javascript.browser",
        sdkVersion = "7.0.0"
    )

    private fun sampleRelease() = ReleaseListResponse(
        version = "1.0.0",
        firstSeen = TIMESTAMP_2026_01_01,
        lastSeen = "2026-01-02T00:00:00Z",
        eventCount = 100,
        newIssueCount = 5,
        crashFreeRate = 0.98,
        userCount = 50
    )

    private fun sampleReleaseStats() = ReleaseDetailStats(
        version = "1.0.0",
        firstSeen = TIMESTAMP_2026_01_01,
        lastSeen = "2026-01-02T00:00:00Z",
        totalEvents = 100,
        newIssues = 5,
        resolvedIssues = 3,
        crashFreeSessionRate = 0.98,
        crashFreeUserRate = 0.99,
        userCount = 50,
        eventsTimeline = listOf(TimelinePoint(TIMESTAMP_2026_01_01, 100)),
        eventsByLevel = mapOf("error" to 80L, "warning" to 20L),
        topIssues = listOf(TopIssue("issue-1", "NPE", 50))
    )
}
