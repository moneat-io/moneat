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
import com.moneat.dashboards.models.CustomDataSourceResponse
import com.moneat.dashboards.models.DashboardAlertResponse
import com.moneat.dashboards.models.DashboardResponse
import com.moneat.dashboards.models.FolderResponse
import com.moneat.dashboards.models.NotificationChannels
import com.moneat.dashboards.models.SearchResponse
import com.moneat.dashboards.models.TestConnectionResult
import com.moneat.dashboards.routes.customDashboardRoutes
import com.moneat.dashboards.services.CustomDashboardService
import com.moneat.dashboards.services.CustomDataSourceExecutor
import com.moneat.dashboards.services.CustomDataSourceService
import com.moneat.dashboards.services.DashboardAlertService
import com.moneat.dashboards.services.DashboardQueryEngine
import com.moneat.dashboards.translation.DataDogTranslator
import com.moneat.dashboards.translation.GrafanaTranslator
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashboardRoutesTest {
    companion object {
        private const val JWT_SECRET = "dashboard-mock-secret"
        private var dbInitialized = false
    }

    private val mockDashboardService =
        mockk<CustomDashboardService>(relaxed = true)
    private val mockQueryEngine =
        mockk<DashboardQueryEngine>(relaxed = true)
    private val mockRetentionService =
        mockk<RetentionPolicyService>(relaxed = true)
    private val mockDDTranslator =
        mockk<DataDogTranslator>(relaxed = true)
    private val mockGrafanaTranslator =
        mockk<GrafanaTranslator>(relaxed = true)
    private val mockDataSourceService =
        mockk<CustomDataSourceService>(relaxed = true)
    private val mockDataSourceExecutor =
        mockk<CustomDataSourceExecutor>(relaxed = true)
    private val mockAlertService =
        mockk<DashboardAlertService>(relaxed = true)

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_dashboard_mock;" +
                    "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                    "DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }
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
            it[email] = "dash-${System.nanoTime()}@test.com"
            it[password_hash] = "hash"
            it[email_verified] = true
        } get Users.id
    }

    private fun seedOrg(): Int = transaction {
        Organizations.insert {
            it[name] = "Dash Org"
            it[slug] = "dash-org-${System.nanoTime()}"
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

    private fun makeDashboard(
        id: Long = 1L,
        orgId: Long = 1L
    ) = DashboardResponse(
        id = id, orgId = orgId, projectId = null,
        folderId = null, title = "Test Dashboard",
        description = null, layoutType = "grid",
        isDefault = false, isFavorited = false,
        variables = emptyList(), createdBy = 1L,
        createdAt = "2024-01-01T00:00:00",
        updatedAt = "2024-01-01T00:00:00",
        widgets = emptyList()
    )

    private fun makeFolder(
        id: Long = 1L,
        orgId: Long = 1L
    ) = FolderResponse(
        id = id,
        orgId = orgId,
        name = "Test Folder",
        color = "#FF0000",
        sortOrder = 0,
        createdAt = "2024-01-01T00:00:00",
        updatedAt = "2024-01-01T00:00:00"
    )

    private fun makeAlert(
        id: Long = 1L,
        dashboardId: Long = 1L
    ) = DashboardAlertResponse(
        id = id, widgetId = 1L, dashboardId = dashboardId,
        name = "Test Alert", condition = "gt",
        threshold = 90.0, metricIndex = 0,
        durationSeconds = 60, incidentSeverity = null,
        enabled = true,
        notificationChannels = NotificationChannels(),
        lastTriggeredAt = null, lastValue = null,
        createdAt = "2024-01-01T00:00:00",
        updatedAt = "2024-01-01T00:00:00"
    )

    private fun makeDataSource(
        id: Long = 1L,
        orgId: Long = 1L
    ) = CustomDataSourceResponse(
        id = id, orgId = orgId, name = "Test DS",
        description = null, sourceType = "postgresql",
        host = "localhost", port = 5432,
        databaseName = "testdb", extraConfig = emptyMap(),
        enabled = true, createdBy = 1L,
        createdAt = "2024-01-01T00:00:00",
        updatedAt = "2024-01-01T00:00:00",
        hasCredentials = true
    )

    private fun installRoutes(app: Application) {
        app.installAuth()
        app.routing {
            customDashboardRoutes(
                dashboardService = mockDashboardService,
                queryEngine = mockQueryEngine,
                retentionPolicyService = mockRetentionService,
                dataDogTranslator = mockDDTranslator,
                grafanaTranslator = mockGrafanaTranslator,
                dataSourceService = mockDataSourceService,
                dataSourceExecutor = mockDataSourceExecutor,
                dashboardAlertService = mockAlertService,
            )
        }
    }

    // ─── Auth ──────────────────────────────────────────────────

    @Test
    fun `GET dashboards returns 401 when unauthenticated`() =
        testApplication {
            application { installRoutes(this) }
            val r = client.get("/v1/dashboards")
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    @Test
    fun `GET dashboards returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installRoutes(this) }
            val r = client.get("/v1/dashboards") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    // ─── Dashboard CRUD ────────────────────────────────────────

    @Test
    fun `GET dashboards returns 200 with list`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val dash = makeDashboard(orgId = orgId.toLong())
            every {
                mockDashboardService.listDashboards(
                    orgId.toLong(), any(), any()
                )
            } returns listOf(dash)
            application { installRoutes(this) }

            val r = client.get("/v1/dashboards") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("Test Dashboard"))
        }

    @Test
    fun `POST dashboards returns 201 on create`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val dash = makeDashboard(orgId = orgId.toLong())
            every {
                mockDashboardService.createDashboard(
                    orgId.toLong(), any(), any()
                )
            } returns dash
            application { installRoutes(this) }

            val r = client.post("/v1/dashboards") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody("""{"title":"New Dash"}""")
            }
            assertEquals(HttpStatusCode.Created, r.status)
        }

    @Test
    fun `GET dashboard by id returns 200`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        val dash = makeDashboard(orgId = orgId.toLong())
        every {
            mockDashboardService.getDashboard(
                1L, orgId.toLong(), any()
            )
        } returns dash
        application { installRoutes(this) }

        val r = client.get("/v1/dashboards/1") {
            header(
                HttpHeaders.Authorization,
                "Bearer ${token(userId)}"
            )
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("Test Dashboard"))
    }

    @Test
    fun `GET dashboard by invalid id returns 400`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.get("/v1/dashboards/abc") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `GET dashboard by id returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockDashboardService.getDashboard(
                    99L, orgId.toLong(), any()
                )
            } returns null
            application { installRoutes(this) }

            val r = client.get("/v1/dashboards/99") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `PUT dashboard returns 200 on update`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val dash = makeDashboard(orgId = orgId.toLong())
            every {
                mockDashboardService.updateDashboard(
                    1L, orgId.toLong(), any()
                )
            } returns dash
            application { installRoutes(this) }

            val r = client.put("/v1/dashboards/1") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody("""{"title":"Updated"}""")
            }
            assertEquals(HttpStatusCode.OK, r.status)
        }

    @Test
    fun `PUT dashboard returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockDashboardService.updateDashboard(
                    99L, orgId.toLong(), any()
                )
            } returns null
            application { installRoutes(this) }

            val r = client.put("/v1/dashboards/99") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody("""{"title":"X"}""")
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `DELETE dashboard returns 204 on success`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockDashboardService.deleteDashboard(
                    1L, orgId.toLong()
                )
            } returns true
            application { installRoutes(this) }

            val r = client.delete("/v1/dashboards/1") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.NoContent, r.status)
        }

    @Test
    fun `DELETE dashboard returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockDashboardService.deleteDashboard(
                    99L, orgId.toLong()
                )
            } returns false
            application { installRoutes(this) }

            val r = client.delete("/v1/dashboards/99") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    // ─── Folder management ─────────────────────────────────────

    @Test
    fun `GET folders returns 200 with list`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val folder = makeFolder(orgId = orgId.toLong())
            every {
                mockDashboardService.listFolders(orgId.toLong())
            } returns listOf(folder)
            application { installRoutes(this) }

            val r = client.get("/v1/dashboards/folders") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("Test Folder"))
        }

    @Test
    fun `POST folder returns 201 on create`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val folder = makeFolder(orgId = orgId.toLong())
            every {
                mockDashboardService.createFolder(
                    orgId.toLong(), any()
                )
            } returns folder
            application { installRoutes(this) }

            val r = client.post("/v1/dashboards/folders") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody("""{"name":"New Folder"}""")
            }
            assertEquals(HttpStatusCode.Created, r.status)
        }

    @Test
    fun `PUT folder returns 200 on update`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val folder = makeFolder(orgId = orgId.toLong())
            every {
                mockDashboardService.updateFolder(
                    1L, orgId.toLong(), any()
                )
            } returns folder
            application { installRoutes(this) }

            val r = client.put("/v1/dashboards/folders/1") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Renamed"}""")
            }
            assertEquals(HttpStatusCode.OK, r.status)
        }

    @Test
    fun `PUT folder returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockDashboardService.updateFolder(
                    99L, orgId.toLong(), any()
                )
            } returns null
            application { installRoutes(this) }

            val r = client.put("/v1/dashboards/folders/99") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody("""{"name":"X"}""")
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `PUT folder returns 400 for invalid id`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.put("/v1/dashboards/folders/abc") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody("""{"name":"X"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `DELETE folder returns 204 on success`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockDashboardService.deleteFolder(
                    1L, orgId.toLong()
                )
            } returns true
            application { installRoutes(this) }

            val r = client.delete("/v1/dashboards/folders/1") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.NoContent, r.status)
        }

    @Test
    fun `DELETE folder returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockDashboardService.deleteFolder(
                    99L, orgId.toLong()
                )
            } returns false
            application { installRoutes(this) }

            val r = client.delete("/v1/dashboards/folders/99") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    // ─── Favorites and folder move ─────────────────────────────

    @Test
    fun `POST favorite returns 200`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        every {
            mockDashboardService.toggleFavorite(
                userId, 1L, orgId.toLong()
            )
        } returns true
        application { installRoutes(this) }

        val r = client.post("/v1/dashboards/1/favorite") {
            header(
                HttpHeaders.Authorization,
                "Bearer ${token(userId)}"
            )
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("is_favorited"))
    }

    @Test
    fun `PUT dashboard folder returns 200 on move`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockDashboardService.moveDashboardToFolder(
                    1L, orgId.toLong(), any()
                )
            } returns true
            application { installRoutes(this) }

            val r = client.put("/v1/dashboards/1/folder") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody("""{"folder_id":2}""")
            }
            assertEquals(HttpStatusCode.OK, r.status)
        }

    @Test
    fun `PUT dashboard folder returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockDashboardService.moveDashboardToFolder(
                    99L, orgId.toLong(), any()
                )
            } returns false
            application { installRoutes(this) }

            val r = client.put("/v1/dashboards/99/folder") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody("""{"folder_id":2}""")
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    // ─── Query endpoints ───────────────────────────────────────

    @Test
    fun `POST query returns 400 for invalid dashboard id`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.post("/v1/dashboards/abc/query") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody("""{"queryConfig":{}}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `POST batch query returns 400 for invalid id`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installRoutes(this) }

            val r =
                client.post("/v1/dashboards/abc/query/batch") {
                    header(
                        HttpHeaders.Authorization,
                        "Bearer ${token(userId)}"
                    )
                    contentType(ContentType.Application.Json)
                    setBody("""{"queries":[]}""")
                }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `POST variables resolve returns 400 for invalid id`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.post(
                "/v1/dashboards/abc/variables/resolve"
            ) {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    // ─── Import / Export ───────────────────────────────────────

    @Test
    fun `POST import returns 400 for invalid JSON`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.post("/v1/dashboards/import") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody(
                    """{"format":"datadog","json":"not-json"}"""
                )
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `POST import returns 400 for unsupported format`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.post("/v1/dashboards/import") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody(
                    """{"format":"unknown","json":"{}"}"""
                )
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `GET export moneat format returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val dash = makeDashboard(orgId = orgId.toLong())
            every {
                mockDashboardService.getDashboard(
                    1L, orgId.toLong()
                )
            } returns dash
            application { installRoutes(this) }

            val r = client.get(
                "/v1/dashboards/1/export/moneat"
            ) {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.OK, r.status)
        }

    @Test
    fun `GET export returns 404 when dashboard missing`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockDashboardService.getDashboard(
                    99L, orgId.toLong()
                )
            } returns null
            application { installRoutes(this) }

            val r = client.get(
                "/v1/dashboards/99/export/moneat"
            ) {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `GET export returns 400 for unsupported format`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val dash = makeDashboard(orgId = orgId.toLong())
            every {
                mockDashboardService.getDashboard(
                    1L, orgId.toLong()
                )
            } returns dash
            application { installRoutes(this) }

            val r = client.get(
                "/v1/dashboards/1/export/xml"
            ) {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `GET export returns 400 for invalid dashboard id`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.get(
                "/v1/dashboards/abc/export/moneat"
            ) {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    // ─── Dashboard alerts ──────────────────────────────────────

    @Test
    fun `GET dashboard alerts returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val alert = makeAlert()
            every {
                mockAlertService.listAlerts(1L, orgId.toLong())
            } returns listOf(alert)
            application { installRoutes(this) }

            val r = client.get("/v1/dashboards/1/alerts") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("Test Alert"))
        }

    @Test
    fun `POST dashboard alert returns 201`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val alert = makeAlert()
            every {
                mockAlertService.createAlert(
                    1L, orgId.toLong(), any(), any()
                )
            } returns alert
            application { installRoutes(this) }

            val body = """
                {"widget_id":1,"name":"A","condition":"gt",
                 "threshold":90.0,"metric_index":0,
                 "duration_seconds":60}
            """.trimIndent()
            val r = client.post("/v1/dashboards/1/alerts") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Created, r.status)
        }

    @Test
    fun `PUT dashboard alert returns 200 on update`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val alert = makeAlert()
            every {
                mockAlertService.updateAlert(
                    1L, 1L, orgId.toLong(), any()
                )
            } returns alert
            application { installRoutes(this) }

            val r =
                client.put("/v1/dashboards/1/alerts/1") {
                    header(
                        HttpHeaders.Authorization,
                        "Bearer ${token(userId)}"
                    )
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled":false}""")
                }
            assertEquals(HttpStatusCode.OK, r.status)
        }

    @Test
    fun `PUT dashboard alert returns 404 when missing`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockAlertService.updateAlert(
                    99L, 1L, orgId.toLong(), any()
                )
            } returns null
            application { installRoutes(this) }

            val r =
                client.put("/v1/dashboards/1/alerts/99") {
                    header(
                        HttpHeaders.Authorization,
                        "Bearer ${token(userId)}"
                    )
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled":false}""")
                }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `DELETE dashboard alert returns 204`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockAlertService.deleteAlert(
                    1L, 1L, orgId.toLong()
                )
            } returns true
            application { installRoutes(this) }

            val r =
                client.delete("/v1/dashboards/1/alerts/1") {
                    header(
                        HttpHeaders.Authorization,
                        "Bearer ${token(userId)}"
                    )
                }
            assertEquals(HttpStatusCode.NoContent, r.status)
        }

    @Test
    fun `DELETE dashboard alert returns 404 when missing`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockAlertService.deleteAlert(
                    99L, 1L, orgId.toLong()
                )
            } returns false
            application { installRoutes(this) }

            val r =
                client.delete("/v1/dashboards/1/alerts/99") {
                    header(
                        HttpHeaders.Authorization,
                        "Bearer ${token(userId)}"
                    )
                }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `GET dashboard alerts returns 400 for bad id`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installRoutes(this) }

            val r =
                client.get("/v1/dashboards/abc/alerts") {
                    header(
                        HttpHeaders.Authorization,
                        "Bearer ${token(userId)}"
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    // ─── Datasources + templates ───────────────────────────────

    @Test
    fun `GET dashboard datasources returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockDataSourceService.listDataSources(
                    orgId.toLong()
                )
            } returns emptyList()
            every {
                mockQueryEngine.getDataSources(any())
            } returns emptyList()
            application { installRoutes(this) }

            val r =
                client.get("/v1/dashboards/datasources") {
                    header(
                        HttpHeaders.Authorization,
                        "Bearer ${token(userId)}"
                    )
                }
            assertEquals(HttpStatusCode.OK, r.status)
        }

    @Test
    fun `GET dashboard templates returns 200`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            every {
                mockDashboardService.getDefaultDashboardTemplates()
            } returns emptyList()
            application { installRoutes(this) }

            val r =
                client.get("/v1/dashboards/templates") {
                    header(
                        HttpHeaders.Authorization,
                        "Bearer ${token(userId)}"
                    )
                }
            assertEquals(HttpStatusCode.OK, r.status)
        }

    // ─── Search ────────────────────────────────────────────────

    @Test
    fun `GET search returns 200 with results`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val result = SearchResponse(
                dashboards = emptyList(),
                projects = emptyList()
            )
            every {
                mockDashboardService.search(
                    orgId.toLong(), any(), any()
                )
            } returns result
            application { installRoutes(this) }

            val r = client.get("/v1/search?q=test") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.OK, r.status)
        }

    // ─── Custom data source management ─────────────────────────

    @Test
    fun `GET custom datasources returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val ds = makeDataSource(orgId = orgId.toLong())
            every {
                mockDataSourceService.listDataSources(
                    orgId.toLong()
                )
            } returns listOf(ds)
            application { installRoutes(this) }

            val r = client.get("/v1/datasources") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("Test DS"))
        }

    @Test
    fun `POST custom datasource returns 201`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val ds = makeDataSource(orgId = orgId.toLong())
            every {
                mockDataSourceService.createDataSource(
                    orgId.toLong(), any(), any()
                )
            } returns ds
            application { installRoutes(this) }

            val body = """
                {"name":"New DS","source_type":"postgresql",
                 "host":"localhost","port":5432}
            """.trimIndent()
            val r = client.post("/v1/datasources") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Created, r.status)
        }

    @Test
    fun `POST test connection returns 200`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            val result = TestConnectionResult(
                success = true,
                message = "OK"
            )
            coEvery {
                mockDataSourceExecutor.testConnection(any())
            } returns result
            application { installRoutes(this) }

            val body = """
                {"source_type":"postgresql",
                 "host":"localhost","port":5432,
                 "username":"u","password":"p"}
            """.trimIndent()
            val r = client.post("/v1/datasources/test") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("OK"))
        }

    @Test
    fun `GET custom datasource by id returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val ds = makeDataSource(orgId = orgId.toLong())
            every {
                mockDataSourceService.getDataSource(
                    1L, orgId.toLong()
                )
            } returns ds
            application { installRoutes(this) }

            val r = client.get("/v1/datasources/1") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("Test DS"))
        }

    @Test
    fun `GET custom datasource returns 404 when missing`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockDataSourceService.getDataSource(
                    99L, orgId.toLong()
                )
            } returns null
            application { installRoutes(this) }

            val r = client.get("/v1/datasources/99") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `PUT custom datasource returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val ds = makeDataSource(orgId = orgId.toLong())
            every {
                mockDataSourceService.updateDataSource(
                    1L, orgId.toLong(), any()
                )
            } returns ds
            application { installRoutes(this) }

            val r = client.put("/v1/datasources/1") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Updated DS"}""")
            }
            assertEquals(HttpStatusCode.OK, r.status)
        }

    @Test
    fun `PUT custom datasource returns 404 when missing`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockDataSourceService.updateDataSource(
                    99L, orgId.toLong(), any()
                )
            } returns null
            application { installRoutes(this) }

            val r = client.put("/v1/datasources/99") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
                contentType(ContentType.Application.Json)
                setBody("""{"name":"X"}""")
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `DELETE custom datasource returns 204`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockDataSourceService.deleteDataSource(
                    1L, orgId.toLong()
                )
            } returns true
            application { installRoutes(this) }

            val r = client.delete("/v1/datasources/1") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.NoContent, r.status)
        }

    @Test
    fun `DELETE custom datasource returns 404`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockDataSourceService.deleteDataSource(
                    99L, orgId.toLong()
                )
            } returns false
            application { installRoutes(this) }

            val r = client.delete("/v1/datasources/99") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `GET custom datasource returns 400 for bad id`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.get("/v1/datasources/abc") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${token(userId)}"
                )
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }
}
