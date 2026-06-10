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

import com.moneat.dashboards.models.AggFunction
import com.moneat.dashboards.models.CustomDataSourceResponse
import com.moneat.dashboards.models.CreateDashboardRequest
import com.moneat.dashboards.models.DashboardAlertResponse
import com.moneat.dashboards.models.DashboardResponse
import com.moneat.dashboards.models.DashboardVariable
import com.moneat.dashboards.models.Dashboards
import com.moneat.dashboards.models.FolderResponse
import com.moneat.dashboards.models.MetricDef
import com.moneat.dashboards.models.NotificationChannels
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.SearchResponse
import com.moneat.dashboards.models.TestConnectionResult
import com.moneat.dashboards.routes.DashboardCoreRouteDependencies
import com.moneat.dashboards.routes.DashboardDataSourceRouteDependencies
import com.moneat.dashboards.routes.DashboardRouteDependencies
import com.moneat.dashboards.routes.DashboardTranslators
import com.moneat.dashboards.routes.customDashboardRoutes
import com.moneat.dashboards.services.CustomDashboardService
import com.moneat.dashboards.services.CustomDataSourceExecutor
import com.moneat.dashboards.services.CustomDataSourceService
import com.moneat.dashboards.services.DataSourceCredentials
import com.moneat.dashboards.services.DashboardAlertService
import com.moneat.dashboards.services.DashboardQueryEngine
import com.moneat.dashboards.services.DashboardTemplateCatalogService
import com.moneat.dashboards.translation.DataDogTranslator
import com.moneat.dashboards.translation.GrafanaTranslator
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class DashboardRoutesTest {
    companion object {
        private const val TEST_DASHBOARD = "Test Dashboard"
        private const val DEFAULT_TIMESTAMP = "2024-01-01T00:00:00"
        private const val TEST_DS = "Test DS"
        private const val DASHBOARDS_PATH = "/v1/dashboards"
        private const val DASHBOARD_RESOURCE_ID = "00000000-0000-0000-0000-000000000001"
        private const val MISSING_DASHBOARD_RESOURCE_ID = "00000000-0000-0000-0000-000000000099"
        private const val FOLDER_RESOURCE_ID = "00000000-0000-0000-0000-000000000001"
        private const val MISSING_FOLDER_RESOURCE_ID = "00000000-0000-0000-0000-000000000099"
        private const val ALERT_RESOURCE_ID = "00000000-0000-0000-0000-000000000001"
        private const val MISSING_ALERT_RESOURCE_ID = "00000000-0000-0000-0000-000000000099"
        private const val DATA_SOURCE_RESOURCE_ID = "00000000-0000-0000-0000-000000000001"
        private const val MISSING_DATA_SOURCE_RESOURCE_ID = "00000000-0000-0000-0000-000000000099"
        private const val WIDGET_RESOURCE_ID = "00000000-0000-0000-0000-000000000010"
        private const val DASHBOARDS_1 = "/v1/dashboards/$DASHBOARD_RESOURCE_ID"
        private const val DASHBOARDS_99 = "/v1/dashboards/$MISSING_DASHBOARD_RESOURCE_ID"
        private const val BODY_NAME_X = """{"name":"X"}"""
        private const val DATASOURCES_1 = "/v1/datasources/$DATA_SOURCE_RESOURCE_ID"
        private const val DATASOURCES_99 = "/v1/datasources/$MISSING_DATA_SOURCE_RESOURCE_ID"
        private var db: Database? = null
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
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_dashboard_mock;" +
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
        every { mockDashboardService.isValidResourceId(any()) } answers {
            isUuid(firstArg())
        }
        every { mockDashboardService.resolveDashboardId(any(), any()) } answers {
            resourceNumber(firstArg())
        }
        every { mockDashboardService.resolveFolderId(any(), any()) } answers {
            resourceNumber(firstArg())
        }
        every { mockDataSourceService.isValidResourceId(any()) } answers {
            isUuid(firstArg())
        }
        every { mockDataSourceService.resolveDataSourceId(any(), any()) } answers {
            resourceNumber(firstArg())
        }
        every { mockAlertService.isValidResourceId(any()) } answers {
            isUuid(firstArg())
        }
        every { mockAlertService.resolveAlertId(any(), any(), any()) } answers {
            resourceNumber(firstArg())
        }
    }

    private fun isUuid(value: String?): Boolean =
        value?.let {
            runCatching { java.util.UUID.fromString(it) }.isSuccess
        } ?: false

    private fun resourceNumber(value: String): Long? =
        value.takeIf(::isUuid)?.takeLast(12)?.toLongOrNull()

    private fun Application.installAuth() {
        installJwtAuth()
    }

    private fun token(userId: Int, orgId: Int? = null): String =
        RouteTestSupport.createToken(userId, orgId)

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

    private fun seedProject(orgId: Int): Long = transaction {
        Projects.insert {
            it[organization_id] = orgId
            it[name] = "Test Project"
            it[slug] = "test-project-${System.nanoTime()}"
        } get Projects.id
    }

    private fun projectResourceId(projectId: Long): String = transaction {
        Projects
            .selectAll()
            .where { Projects.id eq projectId }
            .first()[Projects.resource_id]
            .toString()
    }

    private fun seedDashboardScope(orgId: Long): Long = transaction {
        exec(
            """
            CREATE TABLE IF NOT EXISTS dashboards (
                id BIGSERIAL PRIMARY KEY,
                resource_id UUID DEFAULT RANDOM_UUID() NOT NULL,
                org_id BIGINT NOT NULL,
                project_id BIGINT,
                folder_id BIGINT,
                title VARCHAR(255) NOT NULL,
                description TEXT,
                layout_type VARCHAR(20) DEFAULT 'grid' NOT NULL,
                is_default BOOLEAN DEFAULT FALSE NOT NULL,
                variables TEXT DEFAULT '[]' NOT NULL,
                created_by BIGINT NOT NULL,
                created_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP NOT NULL
            )
            """.trimIndent()
        )
        Dashboards.insert {
            it[Dashboards.orgId] = orgId
            it[title] = TEST_DASHBOARD
            it[description] = null
            it[createdBy] = 1L
            it[createdAt] = Clock.System.now()
            it[updatedAt] = Clock.System.now()
        }[Dashboards.id]
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
        id = resourceId(id), orgId = orgId, projectId = null,
        folderId = null, title = TEST_DASHBOARD,
        description = null, layoutType = "grid",
        isDefault = false, isFavorited = false,
        variables = emptyList(), createdBy = 1L,
        createdAt = DEFAULT_TIMESTAMP,
        updatedAt = DEFAULT_TIMESTAMP,
        widgets = emptyList()
    )

    private fun makeFolder(
        id: Long = 1L,
        orgId: Long = 1L
    ) = FolderResponse(
        id = resourceId(id),
        orgId = orgId,
        name = "Test Folder",
        color = "#FF0000",
        sortOrder = 0,
        createdAt = DEFAULT_TIMESTAMP,
        updatedAt = DEFAULT_TIMESTAMP
    )

    private fun makeAlert(
        id: Long = 1L,
        dashboardId: Long = 1L
    ) = DashboardAlertResponse(
        id = resourceId(id), widgetId = WIDGET_RESOURCE_ID, dashboardId = resourceId(dashboardId),
        name = "Test Alert", condition = "gt",
        threshold = 90.0, metricIndex = 0,
        durationSeconds = 60, alertPriority = null,
        enabled = true,
        notificationChannels = NotificationChannels(),
        lastTriggeredAt = null, lastValue = null,
        createdAt = DEFAULT_TIMESTAMP,
        updatedAt = DEFAULT_TIMESTAMP
    )

    private fun makeDataSource(
        id: Long = 1L,
        orgId: Long = 1L
    ) = CustomDataSourceResponse(
        id = resourceId(id), orgId = orgId, name = TEST_DS,
        description = null, sourceType = "postgresql",
        host = "localhost", port = 5432,
        databaseName = "testdb", extraConfig = emptyMap(),
        enabled = true, createdBy = 1L,
        createdAt = DEFAULT_TIMESTAMP,
        updatedAt = DEFAULT_TIMESTAMP,
        numericId = id,
        hasCredentials = true
    )

    private fun resourceId(id: Long): String =
        "00000000-0000-0000-0000-${id.toString().padStart(12, '0')}"

    private fun installRoutes(app: Application) {
        app.installAuth()
        app.routing {
            customDashboardRoutes(
                DashboardRouteDependencies(
                    core = DashboardCoreRouteDependencies(
                        dashboardService = mockDashboardService,
                        queryEngine = mockQueryEngine,
                        retentionPolicyService = mockRetentionService,
                    ),
                    translators = DashboardTranslators(mockDDTranslator, mockGrafanaTranslator),
                    dataSources = DashboardDataSourceRouteDependencies(
                        dataSourceService = mockDataSourceService,
                        dataSourceExecutor = mockDataSourceExecutor,
                    ),
                    dashboardAlertService = mockAlertService,
                    templateCatalogService = DashboardTemplateCatalogService(),
                )
            )
        }
    }

    // ──── Auth ────

    @Test
    fun `GET dashboards returns 401 when unauthenticated`() =
        testApplication {
            application { installRoutes(this) }
            val r = client.get(DASHBOARDS_PATH)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    @Test
    fun `GET dashboards returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installRoutes(this) }
            val r = client.get(DASHBOARDS_PATH) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    // ──── Dashboard CRUD ────

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

            val r = client.get(DASHBOARDS_PATH) {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains(TEST_DASHBOARD))
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

            val r = client.post(DASHBOARDS_PATH) {
                withAuth(token(userId, orgId))
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

        val r = client.get(DASHBOARDS_1) {
            withAuth(token(userId, orgId))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains(TEST_DASHBOARD))
    }

    @Test
    fun `GET dashboard by invalid id returns 400`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.get("/v1/dashboards/abc") {
                withAuth(token(userId, orgId))
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

            val r = client.get(DASHBOARDS_99) {
                withAuth(token(userId, orgId))
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

            val r = client.put(DASHBOARDS_1) {
                withAuth(token(userId, orgId))
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

            val r = client.put(DASHBOARDS_99) {
                withAuth(token(userId, orgId))
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

            val r = client.delete(DASHBOARDS_1) {
                withAuth(token(userId, orgId))
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

            val r = client.delete(DASHBOARDS_99) {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    // ──── Folder management ────

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
                withAuth(token(userId, orgId))
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
                withAuth(token(userId, orgId))
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

            val r = client.put("/v1/dashboards/folders/$FOLDER_RESOURCE_ID") {
                withAuth(token(userId, orgId))
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

            val r = client.put("/v1/dashboards/folders/$MISSING_FOLDER_RESOURCE_ID") {
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody(BODY_NAME_X)
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `PUT folder returns 400 for invalid id`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.put("/v1/dashboards/folders/abc") {
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody(BODY_NAME_X)
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

            val r = client.delete("/v1/dashboards/folders/$FOLDER_RESOURCE_ID") {
                withAuth(token(userId, orgId))
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

            val r = client.delete("/v1/dashboards/folders/$MISSING_FOLDER_RESOURCE_ID") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    // ──── Favorites and folder move ────

    @Test
    fun `POST favorite returns 200`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        every {
            mockDashboardService.toggleFavorite(
                userId, 1L, orgId.toLong()
            )
        } returns true
        application { installRoutes(this) }

        val r = client.post("$DASHBOARDS_1/favorite") {
            withAuth(token(userId, orgId))
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

            val r = client.put("$DASHBOARDS_1/folder") {
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody("""{"folder_id":"$FOLDER_RESOURCE_ID"}""")
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

            val r = client.put("$DASHBOARDS_99/folder") {
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody("""{"folder_id":"$FOLDER_RESOURCE_ID"}""")
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    // ──── Query endpoints ────

    @Test
    fun `POST query returns 400 for invalid dashboard id`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.post("/v1/dashboards/abc/query") {
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody("""{"queryConfig":{}}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `POST batch query returns 400 for invalid id`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installRoutes(this) }

            val r =
                client.post("/v1/dashboards/abc/query/batch") {
                    withAuth(token(userId, orgId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"queries":[]}""")
                }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `POST batch query normalizes response refs and includes original ref metadata`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val projectId = seedProject(orgId)
            val dashboardId = seedDashboardScope(orgId.toLong())
            val query = QueryDsl(
                dataSource = "events",
                metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                refId = "RabbitMQ 4.2+"
            )
            coEvery { mockRetentionService.getRetentionDaysForProject(projectId) } returns null
            every { mockQueryEngine.applyVariables(any(), any()) } answers { firstArg() }
            every {
                mockQueryEngine.resolvePrometheusDataSource(any(), orgId.toLong(), any())
            } answers { firstArg() }
            every { mockQueryEngine.isCustomDataSource("events") } returns false
            coEvery {
                mockQueryEngine.executeQuery(any(), projectId, any(), any(), orgId.toLong())
            } returns listOf(
                mapOf(
                    "timestamp" to JsonPrimitive("2026-06-09T00:00:00Z"),
                    "count" to JsonPrimitive(1),
                )
            )
            application { installRoutes(this) }

            val projectResourceIdValue = projectResourceId(projectId)
            val r =
                client.post("/v1/dashboards/${resourceId(dashboardId)}/query/batch?projectId=$projectResourceIdValue") {
                    withAuth(token(userId, orgId))
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "queries": [
                            {
                              "dataSource": "events",
                              "metrics": [{"function": "count", "alias": "count"}],
                              "ref_id": "RabbitMQ 4.2+"
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                }

            val body = r.bodyAsText()
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(body.contains(""""A""""))
            assertTrue(body.contains(""""original_ref_id":"RabbitMQ 4.2+""""))
            assertTrue(body.contains(""""query_index":0"""))
            assertTrue(!body.contains(""""RabbitMQ 4.2+":"""))
        }

    @Test
    fun `POST variables resolve returns 400 for invalid id`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.post(
                "/v1/dashboards/abc/variables/resolve"
            ) {
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `POST variables resolve returns label values from matching sources`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val dashboardId = seedDashboardScope(orgId.toLong())
            val dashboard = makeDashboard(id = dashboardId, orgId = orgId.toLong()).copy(
                variables = listOf(
                    DashboardVariable(
                        name = "namespace",
                        query = "label_values(up{job=\"\$job\"}, namespace)",
                        datasource = "__prometheus",
                    ),
                    DashboardVariable(
                        name = "pod",
                        query = "label_values({namespace=\"\$namespace\"}, pod)",
                        datasource = "__loki",
                    ),
                    DashboardVariable(
                        name = "cache",
                        query = "label_values(redis_up, instance)",
                        datasource = "__redis",
                    ),
                    DashboardVariable(name = "static"),
                    DashboardVariable(name = "ignored", query = "up"),
                )
            )
            val prometheus = makeDataSource(id = 10L, orgId = orgId.toLong()).copy(
                name = "Prometheus",
                sourceType = "prometheus",
                port = 9090,
            )
            val loki = makeDataSource(id = 11L, orgId = orgId.toLong()).copy(
                name = "Loki",
                sourceType = "loki",
                port = 3100,
            )
            val redis = makeDataSource(id = 12L, orgId = orgId.toLong()).copy(
                name = "Redis",
                sourceType = "redis",
                port = 6379,
            )
            every { mockDashboardService.getDashboard(dashboardId, orgId.toLong()) } returns dashboard
            every { mockDataSourceService.listDataSources(orgId.toLong()) } returns listOf(prometheus, loki, redis)
            every {
                mockDataSourceService.getDecryptedCredentials(prometheus.numericId, orgId.toLong())
            } returns DataSourceCredentials(apiKey = "prom-token")
            every {
                mockDataSourceService.getDecryptedCredentials(loki.numericId, orgId.toLong())
            } returns DataSourceCredentials(apiKey = "loki-token")
            every {
                mockDataSourceService.getDecryptedCredentials(redis.numericId, orgId.toLong())
            } returns DataSourceCredentials(password = "redis-token")
            coEvery {
                mockDataSourceExecutor.executeLabelValuesQuery(
                    any(),
                    prometheus.host,
                    prometheus.port,
                    any(),
                    "label_values(up{job=\"api\"}, namespace)",
                )
            } returns listOf("default")
            coEvery {
                mockDataSourceExecutor.executeLabelValuesQuery(
                    any(),
                    loki.host,
                    loki.port,
                    any(),
                    "label_values({namespace=\"default\"}, pod)",
                )
            } returns listOf("api-0")
            coEvery {
                mockDataSourceExecutor.executeLabelValuesQuery(
                    any(),
                    redis.host,
                    redis.port,
                    any(),
                    "label_values(redis_up, instance)",
                )
            } returns listOf("cache-0")
            application { installRoutes(this) }

            val r = client.post("/v1/dashboards/${resourceId(dashboardId)}/variables/resolve") {
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody("""{"job":"api","namespace":"default"}""")
            }

            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("namespace"))
            assertTrue(r.bodyAsText().contains("default"))
            assertTrue(r.bodyAsText().contains("api-0"))
            assertTrue(r.bodyAsText().contains("cache-0"))
        }

    // ──── Import / Export ────

    @Test
    fun `POST import returns 400 for invalid JSON`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.post("/v1/dashboards/import") {
                withAuth(token(userId, orgId))
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
            val (userId, orgId) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.post("/v1/dashboards/import") {
                withAuth(token(userId, orgId))
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
                "$DASHBOARDS_1/export/moneat"
            ) {
                withAuth(token(userId, orgId))
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
                "$DASHBOARDS_99/export/moneat"
            ) {
                withAuth(token(userId, orgId))
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
                "$DASHBOARDS_1/export/xml"
            ) {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `GET export returns 400 for invalid dashboard id`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.get(
                "/v1/dashboards/abc/export/moneat"
            ) {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    // ──── Dashboard alerts ────

    @Test
    fun `GET dashboard alerts returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val alert = makeAlert()
            every {
                mockAlertService.listAlerts(1L, orgId.toLong())
            } returns listOf(alert)
            application { installRoutes(this) }

            val r = client.get("$DASHBOARDS_1/alerts") {
                withAuth(token(userId, orgId))
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
                {"widget_id":"$WIDGET_RESOURCE_ID","name":"A","condition":"gt",
                 "threshold":90.0,"metric_index":0,
                 "duration_seconds":60}
            """.trimIndent()
            val r = client.post("$DASHBOARDS_1/alerts") {
                withAuth(token(userId, orgId))
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
                client.put("$DASHBOARDS_1/alerts/$ALERT_RESOURCE_ID") {
                    withAuth(token(userId, orgId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled":false}""")
                }
            assertEquals(HttpStatusCode.OK, r.status)
        }

    @Test
    fun `PUT dashboard alert returns 400 for malformed JSON`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installRoutes(this) }

            val r =
                client.put("$DASHBOARDS_1/alerts/$ALERT_RESOURCE_ID") {
                    withAuth(token(userId, orgId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled":""")
                }
            assertEquals(HttpStatusCode.BadRequest, r.status)
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
                client.put("$DASHBOARDS_1/alerts/$MISSING_ALERT_RESOURCE_ID") {
                    withAuth(token(userId, orgId))
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
                client.delete("$DASHBOARDS_1/alerts/$ALERT_RESOURCE_ID") {
                    withAuth(token(userId, orgId))
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
                client.delete("$DASHBOARDS_1/alerts/$MISSING_ALERT_RESOURCE_ID") {
                    withAuth(token(userId, orgId))
                }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `GET dashboard alerts returns 400 for bad id`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installRoutes(this) }

            val r =
                client.get("/v1/dashboards/abc/alerts") {
                    withAuth(token(userId, orgId))
                }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    // ──── Datasources + templates ────

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
                    withAuth(token(userId, orgId))
                }
            assertEquals(HttpStatusCode.OK, r.status)
        }

    @Test
    fun `GET dashboard templates returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installRoutes(this) }

            val r =
                client.get("/v1/dashboards/templates") {
                    withAuth(token(userId, orgId))
                }
            assertEquals(HttpStatusCode.OK, r.status)
        }

    @Test
    fun `GET dashboard template detail returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installRoutes(this) }

            val r =
                client.get("/v1/dashboards/templates/001-1860-node-exporter-full") {
                    withAuth(token(userId, orgId))
                }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("Node Exporter Full"))
        }

    @Test
    fun `GET dashboard template detail returns 404 when missing`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installRoutes(this) }

            val r =
                client.get("/v1/dashboards/templates/does-not-exist") {
                    withAuth(token(userId, orgId))
                }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `POST dashboard template creates dashboard with overrides`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val dash = makeDashboard(orgId = orgId.toLong())
            val requestSlot = slot<CreateDashboardRequest>()
            every {
                mockDashboardService.createDashboard(
                    orgId.toLong(), userId.toLong(), capture(requestSlot)
                )
            } returns dash
            application { installRoutes(this) }

            val r =
                client.post("/v1/dashboards/templates/001-1860-node-exporter-full") {
                    withAuth(token(userId, orgId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"project_id":"project-77","folder_id":"folder-88"}""")
                }

            assertEquals(HttpStatusCode.Created, r.status)
            assertEquals("project-77", requestSlot.captured.projectId)
            assertEquals("folder-88", requestSlot.captured.folderId)
            assertEquals("Node Exporter Full", requestSlot.captured.title)
            assertTrue(requestSlot.captured.widgets.isNotEmpty())
        }

    @Test
    fun `POST dashboard template returns 400 for malformed payload`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installRoutes(this) }

            val r =
                client.post("/v1/dashboards/templates/001-1860-node-exporter-full") {
                    withAuth(token(userId, orgId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"project_id":""")
                }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    // ──── Search ────

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
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, r.status)
        }

    // ──── Custom data source management ────

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
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains(TEST_DS))
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
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Created, r.status)
        }

    @Test
    fun `POST test connection returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
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
                withAuth(token(userId, orgId))
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

            val r = client.get(DATASOURCES_1) {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains(TEST_DS))
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

            val r = client.get(DATASOURCES_99) {
                withAuth(token(userId, orgId))
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

            val r = client.put(DATASOURCES_1) {
                withAuth(token(userId, orgId))
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

            val r = client.put(DATASOURCES_99) {
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody(BODY_NAME_X)
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

            val r = client.delete(DATASOURCES_1) {
                withAuth(token(userId, orgId))
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

            val r = client.delete(DATASOURCES_99) {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `GET custom datasource returns 400 for bad id`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installRoutes(this) }

            val r = client.get("/v1/datasources/abc") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }
}
