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

package com.moneat.dashboards

import com.moneat.dashboards.models.AggFunction
import com.moneat.dashboards.models.CreateDashboardRequest
import com.moneat.dashboards.models.CreateFolderRequest
import com.moneat.dashboards.models.CreateWidgetRequest
import com.moneat.dashboards.models.DashboardVariable
import com.moneat.dashboards.models.FilterDef
import com.moneat.dashboards.models.FilterOp
import com.moneat.dashboards.models.MetricDef
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.UpdateDashboardRequest
import com.moneat.dashboards.models.UpdateFolderRequest
import com.moneat.dashboards.models.UpdateWidgetRequest
import com.moneat.dashboards.repositories.CreatedDashboardData
import com.moneat.dashboards.repositories.DashboardFolderRepository
import com.moneat.dashboards.repositories.DashboardRepository
import com.moneat.dashboards.repositories.DashboardWidgetRepository
import com.moneat.dashboards.repositories.DashboardWithFavoriteFlag
import com.moneat.dashboards.repositories.WidgetData
import com.moneat.dashboards.repositories.models.DashboardFolderRow
import com.moneat.dashboards.services.CustomDashboardService
import com.moneat.events.repositories.ProjectRepository
import com.moneat.shared.services.ProjectIdResolver
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class CustomDashboardServiceTest {

    private lateinit var folderRepository: DashboardFolderRepository
    private lateinit var dashboardRepository: DashboardRepository
    private lateinit var widgetRepository: DashboardWidgetRepository
    private lateinit var projectRepository: ProjectRepository
    private lateinit var projectIdResolver: ProjectIdResolver
    private lateinit var service: CustomDashboardService

    companion object {
        private const val ORG_ID = 1L
        private const val OTHER_ORG_ID = 999L
        private const val USER_ID_LONG = 100L
        private const val USER_ID_INT = 100
        private const val PROJECT_ID = 10L
        private const val DEFAULT_TIMESTAMP = "2025-01-01T00:00:00Z"
        private const val MY_DASHBOARD = "My Dashboard"
        private const val NEW_FOLDER = "New Folder"
        private const val COLOR_GREEN = "#00ff00"
        private const val COLOR_BLUE = "#0000ff"
        private const val SEARCH_PATTERN_HELLO_WORLD = "%hello world%"

        private fun resourceId(id: Long): String =
            "00000000-0000-0000-0000-${id.toString().padStart(12, '0')}"

        private fun resourceNumber(resourceId: String): Long? =
            resourceId.takeLast(12).toLongOrNull()
    }

    @BeforeTest
    fun setUp() {
        folderRepository = mockk(relaxed = true)
        dashboardRepository = mockk(relaxed = true)
        widgetRepository = mockk(relaxed = true)
        projectRepository = mockk(relaxed = true)
        projectIdResolver = mockk(relaxed = true)
        every { projectIdResolver.resolve(any()) } answers { resourceNumber(firstArg()) }
        service = CustomDashboardService(
            folderRepository = folderRepository,
            dashboardRepository = dashboardRepository,
            dashboardWidgetRepository = widgetRepository,
            projectRepository = projectRepository,
            projectIdResolver = projectIdResolver,
        )
    }

    private data class DashboardFlagParams(
        val id: Long = 1L,
        val resourceId: String = resourceId(id),
        val orgId: Long = ORG_ID,
        val projectId: Long? = PROJECT_ID,
        val projectResourceId: String? = projectId?.let(::resourceId),
        val folderId: Long? = null,
        val folderResourceId: String? = folderId?.let(::resourceId),
        val title: String = "Test Dashboard",
        val description: String? = "A description",
        val layoutType: String = "grid",
        val isDefault: Boolean = false,
        val isFavorited: Boolean = false,
        val variables: String = "[]",
        val createdBy: Long = USER_ID_LONG,
        val createdAt: String = DEFAULT_TIMESTAMP,
        val updatedAt: String = DEFAULT_TIMESTAMP,
        val ownerName: String? = null,
    )

    private fun buildDashboardFlag(p: DashboardFlagParams = DashboardFlagParams()): DashboardWithFavoriteFlag =
        DashboardWithFavoriteFlag(
            id = p.id,
            resourceId = p.resourceId,
            orgId = p.orgId,
            projectId = p.projectId,
            projectResourceId = p.projectResourceId,
            folderId = p.folderId,
            folderResourceId = p.folderResourceId,
            title = p.title,
            description = p.description,
            layoutType = p.layoutType,
            isDefault = p.isDefault,
            isFavorited = p.isFavorited,
            variables = p.variables,
            createdBy = p.createdBy,
            createdAt = p.createdAt,
            updatedAt = p.updatedAt,
            ownerName = p.ownerName,
        )

    private fun buildWidgetData(
        id: Long = 1L,
        dashboardId: Long = 1L,
        title: String? = "Widget",
        widgetType: String = "timeseries",
    ): WidgetData = WidgetData(
        id = id,
        resourceId = resourceId(id),
        dashboardId = dashboardId,
        dashboardResourceId = resourceId(dashboardId),
        title = title,
        widgetType = widgetType,
        gridX = 0,
        gridY = 0,
        gridW = 6,
        gridH = 4,
        queryConfig = "{}",
        queryConfigs = "[]",
        displayConfig = "{}",
        sortOrder = 0,
    )

    private data class CreatedDashboardParams(
        val id: Long = 1L,
        val title: String = "New Dashboard",
        val description: String? = null,
        val projectId: Long? = null,
        val folderId: Long? = null,
        val ownerName: String? = null,
    )

    private fun buildCreatedDashboardData(
        params: CreatedDashboardParams = CreatedDashboardParams(),
    ): CreatedDashboardData = CreatedDashboardData(
        id = params.id,
        resourceId = resourceId(params.id),
        orgId = ORG_ID,
        projectId = params.projectId,
        projectResourceId = params.projectId?.let(::resourceId),
        folderId = params.folderId,
        folderResourceId = params.folderId?.let(::resourceId),
        title = params.title,
        description = params.description,
        layoutType = "grid",
        isDefault = false,
        variables = "[]",
        createdBy = USER_ID_LONG,
        createdAt = DEFAULT_TIMESTAMP,
        updatedAt = DEFAULT_TIMESTAMP,
        ownerName = params.ownerName,
    )

    private fun buildFolderRow(
        id: Long = 1L,
        orgId: Long = ORG_ID,
        name: String = "Folder",
        color: String? = "#ff0000",
        sortOrder: Int = 0,
    ): DashboardFolderRow = DashboardFolderRow(
        id = id,
        resourceId = resourceId(id),
        orgId = orgId,
        name = name,
        color = color,
        sortOrder = sortOrder,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    // ──── listDashboards ────

    @Test
    fun `listDashboards returns mapped dashboards with widgets`() = runBlocking {
        val flag = buildDashboardFlag(DashboardFlagParams(id = 1L))
        val widget = buildWidgetData(id = 10L, dashboardId = 1L)
        every { dashboardRepository.list(ORG_ID, null, null) } returns listOf(flag)
        every { widgetRepository.listByDashboardId(1L) } returns listOf(widget)

        val result = service.listDashboards(ORG_ID)

        assertEquals(1, result.size)
        assertEquals("Test Dashboard", result[0].title)
        assertEquals(1, result[0].widgets.size)
        assertEquals("Widget", result[0].widgets[0].title)
    }

    @Test
    fun `listDashboards maps ownerName`() = runBlocking {
        val flag = buildDashboardFlag(
            DashboardFlagParams(id = 1L, ownerName = "Sam Lee")
        )
        every { dashboardRepository.list(ORG_ID, null, null) } returns listOf(flag)
        every { widgetRepository.listByDashboardId(1L) } returns emptyList()

        val result = service.listDashboards(ORG_ID)

        assertEquals("Sam Lee", result.single().ownerName)
    }

    @Test
    fun `listDashboards returns empty list when no dashboards exist`() = runBlocking {
        every { dashboardRepository.list(ORG_ID, null, null) } returns emptyList()

        val result = service.listDashboards(ORG_ID)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `listDashboards filters by projectId`() = runBlocking {
        val flag = buildDashboardFlag(DashboardFlagParams(id = 1L, projectId = PROJECT_ID))
        every {
            dashboardRepository.list(ORG_ID, PROJECT_ID, null)
        } returns listOf(flag)
        every { widgetRepository.listByDashboardId(1L) } returns emptyList()

        val result = service.listDashboards(ORG_ID, projectId = PROJECT_ID)

        assertEquals(1, result.size)
        verify { dashboardRepository.list(ORG_ID, PROJECT_ID, null) }
    }

    @Test
    fun `listDashboards filters by userId`() = runBlocking {
        every {
            dashboardRepository.list(ORG_ID, null, USER_ID_INT)
        } returns emptyList()

        val result = service.listDashboards(ORG_ID, userId = USER_ID_INT)

        assertTrue(result.isEmpty())
        verify { dashboardRepository.list(ORG_ID, null, USER_ID_INT) }
    }

    // ──── getDashboard ────

    @Test
    fun `getDashboard returns dashboard with widgets`() = runBlocking {
        val flag = buildDashboardFlag(DashboardFlagParams(id = 5L))
        val widget = buildWidgetData(id = 20L, dashboardId = 5L)
        every { dashboardRepository.getById(5L, ORG_ID, null) } returns flag
        every { widgetRepository.listByDashboardId(5L) } returns listOf(widget)

        val result = service.getDashboard(5L, ORG_ID)

        assertNotNull(result)
        assertEquals(resourceId(5), result.id)
        assertEquals(1, result.widgets.size)
    }

    @Test
    fun `getDashboard maps ownerName`() = runBlocking {
        val flag = buildDashboardFlag(
            DashboardFlagParams(id = 5L, ownerName = "Sam Lee")
        )
        every { dashboardRepository.getById(5L, ORG_ID, null) } returns flag
        every { widgetRepository.listByDashboardId(5L) } returns emptyList()

        val result = service.getDashboard(5L, ORG_ID)

        assertNotNull(result)
        assertEquals("Sam Lee", result.ownerName)
    }

    @Test
    fun `getDashboard returns null when dashboard not found`() = runBlocking {
        every { dashboardRepository.getById(999L, ORG_ID, null) } returns null

        val result = service.getDashboard(999L, ORG_ID)

        assertNull(result)
    }

    @Test
    fun `getDashboard passes userId for favorite flag`() = runBlocking {
        val flag = buildDashboardFlag(DashboardFlagParams(id = 5L, isFavorited = true))
        every {
            dashboardRepository.getById(5L, ORG_ID, USER_ID_INT)
        } returns flag
        every { widgetRepository.listByDashboardId(5L) } returns emptyList()

        val result = service.getDashboard(5L, ORG_ID, USER_ID_INT)

        assertNotNull(result)
        assertTrue(result.isFavorited)
    }

    // ──── createDashboard ────

    @Test
    fun `createDashboard returns response with correct fields`() = runBlocking {
        val request = CreateDashboardRequest(
            title = MY_DASHBOARD,
            description = "desc",
            widgets = emptyList(),
        )
        val data = buildCreatedDashboardData(
            CreatedDashboardParams(
                id = 7L,
                title = MY_DASHBOARD,
                description = "desc",
                ownerName = "Sam Lee",
            )
        )
        every { dashboardRepository.create(ORG_ID, USER_ID_LONG, request) } returns data
        every { dashboardRepository.getById(7L, ORG_ID, USER_ID_INT) } returns buildDashboardFlag(
            DashboardFlagParams(
                id = 7L,
                title = MY_DASHBOARD,
                description = "desc",
                ownerName = "Sam Lee",
            )
        )
        every { widgetRepository.listByDashboardId(7L) } returns emptyList()

        val result = service.createDashboard(ORG_ID, USER_ID_LONG, request)

        assertEquals(resourceId(7), result.id)
        assertEquals(MY_DASHBOARD, result.title)
        assertEquals("desc", result.description)
        assertEquals("Sam Lee", result.ownerName)
        assertTrue(result.widgets.isEmpty())
    }

    @Test
    fun `createDashboard creates widgets and returns them`() = runBlocking {
        val widgetReq = CreateWidgetRequest(
            title = "Error Count",
            widgetType = "timeseries",
            gridX = 0,
            gridY = 0,
            gridW = 8,
            gridH = 4,
            queryConfigs = listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                    filters = listOf(FilterDef("level", FilterOp.EQ, "error")),
                )
            ),
        )
        val request = CreateDashboardRequest(
            title = "Dashboard",
            widgets = listOf(widgetReq),
        )
        val data = buildCreatedDashboardData(CreatedDashboardParams(id = 10L, title = "Dashboard"))
        every { dashboardRepository.create(ORG_ID, USER_ID_LONG, request) } returns data
        every { widgetRepository.insert(10L, widgetReq, any(), any()) } returns 50L
        every { dashboardRepository.getById(10L, ORG_ID, USER_ID_INT) } returns buildDashboardFlag(
            DashboardFlagParams(id = 10L, title = "Dashboard")
        )
        every { widgetRepository.listByDashboardId(10L) } returns listOf(
            buildWidgetData(id = 50L, dashboardId = 10L, title = "Error Count")
        )

        val result = service.createDashboard(ORG_ID, USER_ID_LONG, request)

        assertEquals(resourceId(10), result.id)
        assertEquals(1, result.widgets.size)
        assertEquals(resourceId(50), result.widgets[0].id)
        assertEquals("Error Count", result.widgets[0].title)
        assertEquals("timeseries", result.widgets[0].widgetType)
    }

    @Test
    fun `createDashboard assigns sortOrder from index when widget sortOrder is zero`() =
        runBlocking {
            val widget0 = CreateWidgetRequest(
                title = "W0",
                widgetType = "stat",
                sortOrder = 0,
            )
            val widget1 = CreateWidgetRequest(
                title = "W1",
                widgetType = "bar",
                sortOrder = 0,
            )
            val request = CreateDashboardRequest(
                title = "D",
                widgets = listOf(widget0, widget1),
            )
            val data = buildCreatedDashboardData(CreatedDashboardParams(id = 1L))
            every {
                dashboardRepository.create(ORG_ID, USER_ID_LONG, request)
            } returns data
            every { widgetRepository.insert(1L, widget0, 0, any()) } returns 1L
            every { widgetRepository.insert(1L, widget1, 1, any()) } returns 2L

            service.createDashboard(ORG_ID, USER_ID_LONG, request)

            verify { widgetRepository.insert(1L, widget0, 0, any()) }
            verify { widgetRepository.insert(1L, widget1, 1, any()) }
        }

    @Test
    fun `createDashboard preserves explicit widget sortOrder`() = runBlocking {
        val widget = CreateWidgetRequest(
            title = "W",
            widgetType = "stat",
            sortOrder = 5,
        )
        val request = CreateDashboardRequest(
            title = "D",
            widgets = listOf(widget),
        )
        val data = buildCreatedDashboardData(CreatedDashboardParams(id = 1L))
        every {
            dashboardRepository.create(ORG_ID, USER_ID_LONG, request, null, null)
        } returns data
        every { dashboardRepository.getById(1L, ORG_ID, USER_ID_INT) } returns buildDashboardFlag(
            DashboardFlagParams(
                id = 1L,
                variables = """[{"name":"env","label":"Environment"}]""",
            )
        )
        every { widgetRepository.listByDashboardId(1L) } returns emptyList()
        every { widgetRepository.insert(1L, widget, 5, any()) } returns 1L

        service.createDashboard(ORG_ID, USER_ID_LONG, request)

        verify { widgetRepository.insert(1L, widget, 5, any()) }
    }

    @Test
    fun `createDashboard parses variables from created data`() = runBlocking {
        val vars = listOf(
            DashboardVariable(name = "env", label = "Environment"),
        )
        val request = CreateDashboardRequest(
            title = "D",
            variables = vars,
            widgets = emptyList(),
        )
        val data = buildCreatedDashboardData(CreatedDashboardParams(id = 1L)).copy(
            variables = """[{"name":"env","label":"Environment"}]""",
        )
        every {
            dashboardRepository.create(ORG_ID, USER_ID_LONG, request, null, null)
        } returns data
        every { dashboardRepository.getById(1L, ORG_ID, USER_ID_INT) } returns buildDashboardFlag(
            DashboardFlagParams(
                id = 1L,
                variables = """[{"name":"env","label":"Environment"}]""",
            )
        )
        every { widgetRepository.listByDashboardId(1L) } returns emptyList()

        val result = service.createDashboard(ORG_ID, USER_ID_LONG, request)

        assertEquals(1, result.variables.size)
        assertEquals("env", result.variables[0].name)
    }

    // ──── updateDashboard ────

    @Test
    fun `updateDashboard returns null when update fails`() = runBlocking {
        every {
            dashboardRepository.update(99L, ORG_ID, any())
        } returns false

        val result = service.updateDashboard(
            99L,
            ORG_ID,
            UpdateDashboardRequest(title = "X"),
        )

        assertNull(result)
    }

    @Test
    fun `updateDashboard returns updated dashboard`() = runBlocking {
        val updateReq = UpdateDashboardRequest(title = "Updated")
        every { dashboardRepository.update(1L, ORG_ID, updateReq) } returns true
        val flag = buildDashboardFlag(DashboardFlagParams(id = 1L, title = "Updated"))
        every { dashboardRepository.getById(1L, ORG_ID, null) } returns flag
        every { widgetRepository.listByDashboardId(1L) } returns emptyList()

        val result = service.updateDashboard(1L, ORG_ID, updateReq)

        assertNotNull(result)
        assertEquals("Updated", result.title)
    }

    @Test
    fun `updateDashboard with widgets performs bulkUpsert and deleteNotIn`() =
        runBlocking {
            val widgetReq = UpdateWidgetRequest(
                id = resourceId(10),
                title = "W",
                widgetType = "stat",
            )
            val updateReq = UpdateDashboardRequest(widgets = listOf(widgetReq))
            every { dashboardRepository.update(1L, ORG_ID, updateReq) } returns true
            every {
                widgetRepository.bulkUpsert(1L, listOf(widgetReq), any())
            } returns setOf(10L)
            every { widgetRepository.deleteNotIn(1L, setOf(10L)) } returns Unit
            val flag = buildDashboardFlag(DashboardFlagParams(id = 1L))
            every { dashboardRepository.getById(1L, ORG_ID, null) } returns flag
            every { widgetRepository.listByDashboardId(1L) } returns emptyList()

            service.updateDashboard(1L, ORG_ID, updateReq)

            verify { widgetRepository.bulkUpsert(1L, listOf(widgetReq), any()) }
            verify { widgetRepository.deleteNotIn(1L, setOf(10L)) }
        }

    @Test
    fun `updateDashboard without widgets skips widget operations`() = runBlocking {
        val updateReq = UpdateDashboardRequest(title = "No widgets")
        every { dashboardRepository.update(1L, ORG_ID, updateReq) } returns true
        val flag = buildDashboardFlag(DashboardFlagParams(id = 1L, title = "No widgets"))
        every { dashboardRepository.getById(1L, ORG_ID, null) } returns flag
        every { widgetRepository.listByDashboardId(1L) } returns emptyList()

        service.updateDashboard(1L, ORG_ID, updateReq)

        verify(exactly = 0) { widgetRepository.bulkUpsert(any(), any(), any()) }
        verify(exactly = 0) { widgetRepository.deleteNotIn(any(), any()) }
    }

    // ──── deleteDashboard ────

    @Test
    fun `deleteDashboard delegates to repository`() = runBlocking {
        every { dashboardRepository.delete(1L, ORG_ID) } returns true

        assertTrue(service.deleteDashboard(1L, ORG_ID))
        verify { dashboardRepository.delete(1L, ORG_ID) }
    }

    @Test
    fun `deleteDashboard returns false when not found`() = runBlocking {
        every { dashboardRepository.delete(999L, ORG_ID) } returns false

        assertFalse(service.deleteDashboard(999L, ORG_ID))
    }

    // ──── setDefaultDashboard ────

    @Test
    fun `setDefaultDashboard delegates to repository`() = runBlocking {
        every { dashboardRepository.setDefault(3L, ORG_ID) } returns true

        assertTrue(service.setDefaultDashboard(3L, ORG_ID))
        verify { dashboardRepository.setDefault(3L, ORG_ID) }
    }

    // ──── duplicateDashboard ────

    @Test
    fun `duplicateDashboard copies title with suffix and widgets`() = runBlocking {
        val flag = buildDashboardFlag(DashboardFlagParams(id = 7L, title = "Test Dashboard"))
        val widget = buildWidgetData(id = 70L, dashboardId = 7L)
        every { dashboardRepository.getById(7L, ORG_ID, USER_ID_INT) } returns flag
        every { widgetRepository.listByDashboardId(7L) } returns listOf(widget)
        val request = slot<CreateDashboardRequest>()
        every {
            dashboardRepository.create(ORG_ID, USER_ID_LONG, capture(request), PROJECT_ID, null)
        } returns buildCreatedDashboardData(
            CreatedDashboardParams(id = 99L, title = "Test Dashboard (Copy)")
        )
        every { dashboardRepository.getById(99L, ORG_ID, USER_ID_INT) } returns buildDashboardFlag(
            DashboardFlagParams(id = 99L, title = "Test Dashboard (Copy)")
        )
        every { widgetRepository.listByDashboardId(99L) } returns emptyList()

        val result = service.duplicateDashboard(7L, ORG_ID, USER_ID_LONG)

        assertNotNull(result)
        assertEquals("Test Dashboard (Copy)", request.captured.title)
        assertEquals(1, request.captured.widgets.size)
        assertFalse(request.captured.isDefault)
    }

    @Test
    fun `duplicateDashboard returns null when source missing`() = runBlocking {
        every { dashboardRepository.getById(404L, ORG_ID, USER_ID_INT) } returns null

        assertNull(service.duplicateDashboard(404L, ORG_ID, USER_ID_LONG))
    }

    // ──── moveDashboardToFolder ────

    @Test
    fun `moveDashboardToFolder delegates to repository`() = runBlocking {
        every { dashboardRepository.moveToFolder(1L, ORG_ID, 5L) } returns true

        assertTrue(service.moveDashboardToFolder(1L, ORG_ID, 5L))
        verify { dashboardRepository.moveToFolder(1L, ORG_ID, 5L) }
    }

    @Test
    fun `moveDashboardToFolder with null folderId removes from folder`() =
        runBlocking {
            every {
                dashboardRepository.moveToFolder(1L, ORG_ID, null)
            } returns true

            assertTrue(service.moveDashboardToFolder(1L, ORG_ID, null))
        }

    // ──── toggleFavorite ────

    @Test
    fun `toggleFavorite delegates to repository`() = runBlocking {
        every {
            dashboardRepository.toggleFavorite(USER_ID_INT, 1L, ORG_ID)
        } returns true

        assertTrue(service.toggleFavorite(USER_ID_INT, 1L, ORG_ID))
        verify { dashboardRepository.toggleFavorite(USER_ID_INT, 1L, ORG_ID) }
    }

    // ──── Folder CRUD ────

    @Test
    fun `listFolders returns mapped folder responses`() = runBlocking {
        val row = buildFolderRow(id = 1L, name = "General")
        every { folderRepository.listByOrgId(ORG_ID) } returns listOf(row)

        val result = service.listFolders(ORG_ID)

        assertEquals(1, result.size)
        assertEquals("General", result[0].name)
        assertEquals(ORG_ID, result[0].orgId)
    }

    @Test
    fun `listFolders returns empty list when no folders`() = runBlocking {
        every { folderRepository.listByOrgId(ORG_ID) } returns emptyList()

        assertTrue(service.listFolders(ORG_ID).isEmpty())
    }

    @Test
    fun `createFolder returns response with correct fields`() = runBlocking {
        val request = CreateFolderRequest(
            name = NEW_FOLDER,
            color = COLOR_GREEN,
            sortOrder = 1,
        )
        every {
            folderRepository.create(ORG_ID, NEW_FOLDER, COLOR_GREEN, 1)
        } returns 5L
        every { folderRepository.getByIdAndOrgId(5L, ORG_ID) } returns buildFolderRow(
            id = 5L,
            name = NEW_FOLDER,
            color = COLOR_GREEN,
            sortOrder = 1,
        )

        val result = service.createFolder(ORG_ID, request)

        assertEquals(resourceId(5), result.id)
        assertEquals(ORG_ID, result.orgId)
        assertEquals(NEW_FOLDER, result.name)
        assertEquals(COLOR_GREEN, result.color)
        assertEquals(1, result.sortOrder)
    }

    @Test
    fun `updateFolder returns updated response when folder exists`() = runBlocking {
        val row = buildFolderRow(
            id = 1L,
            name = "Old",
            color = "#ff0000",
            sortOrder = 0,
        )
        every { folderRepository.getByIdAndOrgId(1L, ORG_ID) } returns row

        val request = UpdateFolderRequest(name = "Renamed", color = COLOR_BLUE)
        val result = service.updateFolder(1L, ORG_ID, request)

        assertNotNull(result)
        assertEquals("Renamed", result.name)
        assertEquals(COLOR_BLUE, result.color)
        assertEquals(0, result.sortOrder)
        verify {
            folderRepository.update(1L, ORG_ID, "Renamed", COLOR_BLUE, null)
        }
    }

    @Test
    fun `updateFolder returns null when folder not found`() = runBlocking {
        every { folderRepository.getByIdAndOrgId(99L, ORG_ID) } returns null

        val result = service.updateFolder(
            99L,
            ORG_ID,
            UpdateFolderRequest(name = "X"),
        )

        assertNull(result)
    }

    @Test
    fun `updateFolder keeps original values when request fields are null`() =
        runBlocking {
            val row = buildFolderRow(
                id = 1L,
                name = "Original",
                color = "#aabbcc",
                sortOrder = 3,
            )
            every { folderRepository.getByIdAndOrgId(1L, ORG_ID) } returns row

            val request = UpdateFolderRequest()
            val result = service.updateFolder(1L, ORG_ID, request)

            assertNotNull(result)
            assertEquals("Original", result.name)
            assertEquals("#aabbcc", result.color)
            assertEquals(3, result.sortOrder)
        }

    @Test
    fun `deleteFolder returns true when rows deleted`() = runBlocking {
        every { folderRepository.delete(1L, ORG_ID) } returns 1

        assertTrue(service.deleteFolder(1L, ORG_ID))
    }

    @Test
    fun `deleteFolder returns false when no rows deleted`() = runBlocking {
        every { folderRepository.delete(99L, ORG_ID) } returns 0

        assertFalse(service.deleteFolder(99L, ORG_ID))
    }

    // ──── search ────

    @Test
    fun `search returns empty response for blank query`() = runBlocking {
        val result = service.search(ORG_ID, USER_ID_INT, "   ")

        assertTrue(result.dashboards.isEmpty())
        assertTrue(result.projects.isEmpty())
    }

    @Test
    fun `search returns matching dashboards and projects`() = runBlocking {
        val flag = buildDashboardFlag(DashboardFlagParams(id = 1L, title = "Error Dashboard"))
        every {
            dashboardRepository.search(ORG_ID, USER_ID_INT, "%error%")
        } returns listOf(flag)
        every { widgetRepository.listByDashboardId(1L) } returns emptyList()
        every {
            projectRepository.searchProjectsByName(
                ORG_ID.toInt(),
                "%error%",
                limit = 10,
            )
        } returns listOf(
            mockk {
                every { projectId } returns 42L
                every { resourceId } returns "018f4ce4-3f2a-7a67-a32b-0c1848f62b9d"
                every { name } returns "error-tracker"
            }
        )

        val result = service.search(ORG_ID, USER_ID_INT, " Error ")

        assertEquals(1, result.dashboards.size)
        assertEquals("Error Dashboard", result.dashboards[0].title)
        assertEquals(1, result.projects.size)
        assertEquals("018f4ce4-3f2a-7a67-a32b-0c1848f62b9d", result.projects[0].id)
        assertEquals("error-tracker", result.projects[0].name)
    }

    @Test
    fun `search trims and lowercases query`() = runBlocking {
        every {
            dashboardRepository.search(ORG_ID, null, SEARCH_PATTERN_HELLO_WORLD)
        } returns emptyList()
        every {
            projectRepository.searchProjectsByName(
                ORG_ID.toInt(),
                SEARCH_PATTERN_HELLO_WORLD,
                limit = 10,
            )
        } returns emptyList()

        service.search(ORG_ID, null, "  Hello World  ")

        verify { dashboardRepository.search(ORG_ID, null, SEARCH_PATTERN_HELLO_WORLD) }
        verify {
            projectRepository.searchProjectsByName(
                ORG_ID.toInt(),
                SEARCH_PATTERN_HELLO_WORLD,
                limit = 10,
            )
        }
    }

    // ──── Organization isolation ────

    @Test
    fun `getDashboard enforces orgId in repository call`() = runBlocking {
        every {
            dashboardRepository.getById(1L, OTHER_ORG_ID, null)
        } returns null

        val result = service.getDashboard(1L, OTHER_ORG_ID)

        assertNull(result)
        verify { dashboardRepository.getById(1L, OTHER_ORG_ID, null) }
    }

    @Test
    fun `deleteDashboard enforces orgId`() = runBlocking {
        every { dashboardRepository.delete(1L, OTHER_ORG_ID) } returns false

        assertFalse(service.deleteDashboard(1L, OTHER_ORG_ID))
    }

    @Test
    fun `deleteFolder enforces orgId`() = runBlocking {
        every { folderRepository.delete(1L, OTHER_ORG_ID) } returns 0

        assertFalse(service.deleteFolder(1L, OTHER_ORG_ID))
    }

    // ──── Widget loading / parsing ────

    @Test
    fun `widgets with valid queryConfigs JSON are parsed`() = runBlocking {
        val qJson = """[{"dataSource":"events","metrics":[],"groupBy":[],""" +
            """"filters":[],"limit":100,"timeRange":{"from":"now-24h","to":"now"}}]"""
        val widgetData = buildWidgetData(id = 1L, dashboardId = 1L).copy(
            queryConfigs = qJson,
        )
        val flag = buildDashboardFlag(DashboardFlagParams(id = 1L))
        every { dashboardRepository.getById(1L, ORG_ID, null) } returns flag
        every { widgetRepository.listByDashboardId(1L) } returns listOf(widgetData)

        val result = service.getDashboard(1L, ORG_ID)

        assertNotNull(result)
        assertEquals(1, result.widgets.size)
        assertEquals(1, result.widgets[0].queryConfigs.size)
        assertEquals("events", result.widgets[0].queryConfigs[0].dataSource)
    }

    @Test
    fun `widgets with invalid queryConfigs fallback to queryConfig`() =
        runBlocking {
            val singleJson = """{"dataSource":"spans","metrics":[],""" +
                """"groupBy":[],"filters":[],"limit":100,""" +
                """"timeRange":{"from":"now-24h","to":"now"}}"""
            val widgetData = buildWidgetData(id = 1L, dashboardId = 1L).copy(
                queryConfigs = "invalid",
                queryConfig = singleJson,
            )
            val flag = buildDashboardFlag(DashboardFlagParams(id = 1L))
            every { dashboardRepository.getById(1L, ORG_ID, null) } returns flag
            every {
                widgetRepository.listByDashboardId(1L)
            } returns listOf(widgetData)

            val result = service.getDashboard(1L, ORG_ID)

            assertNotNull(result)
            assertEquals(1, result.widgets[0].queryConfigs.size)
            assertEquals("spans", result.widgets[0].queryConfigs[0].dataSource)
        }

    @Test
    fun `widgets with invalid JSON return empty queryConfigs`() = runBlocking {
        val widgetData = buildWidgetData(id = 1L, dashboardId = 1L).copy(
            queryConfigs = "bad",
            queryConfig = "also-bad",
        )
        val flag = buildDashboardFlag(DashboardFlagParams(id = 1L))
        every { dashboardRepository.getById(1L, ORG_ID, null) } returns flag
        every { widgetRepository.listByDashboardId(1L) } returns listOf(widgetData)

        val result = service.getDashboard(1L, ORG_ID)

        assertNotNull(result)
        assertTrue(result.widgets[0].queryConfigs.isEmpty())
    }

    @Test
    fun `dashboard variables with invalid JSON return empty list`() =
        runBlocking {
            val flag = buildDashboardFlag(DashboardFlagParams(id = 1L, variables = "not-json"))
            every { dashboardRepository.getById(1L, ORG_ID, null) } returns flag
            every { widgetRepository.listByDashboardId(1L) } returns emptyList()

            val result = service.getDashboard(1L, ORG_ID)

            assertNotNull(result)
            assertTrue(result.variables.isEmpty())
        }

    @Test
    fun `displayConfig with invalid JSON returns empty map`() = runBlocking {
        val widgetData = buildWidgetData(id = 1L, dashboardId = 1L).copy(
            displayConfig = "not-json",
        )
        val flag = buildDashboardFlag(DashboardFlagParams(id = 1L))
        every { dashboardRepository.getById(1L, ORG_ID, null) } returns flag
        every { widgetRepository.listByDashboardId(1L) } returns listOf(widgetData)

        val result = service.getDashboard(1L, ORG_ID)

        assertNotNull(result)
        assertTrue(result.widgets[0].displayConfig.isEmpty())
    }

    // ──── Default dashboard templates ────

    @Test
    fun `getDefaultDashboardTemplates returns four templates`() = runBlocking {
        val templates = service.getDefaultDashboardTemplates()

        assertEquals(4, templates.size)
    }

    @Test
    fun `getDefaultDashboardTemplates includes expected template names`() =
        runBlocking {
            val templates = service.getDefaultDashboardTemplates()
            val titles = templates.map { it.title }

            assertTrue("Error Overview" in titles)
            assertTrue("Performance Overview" in titles)
            assertTrue("Log Analysis" in titles)
            assertTrue("System Health" in titles)
        }

    @Test
    fun `default templates contain non-empty widget lists`() = runBlocking {
        val templates = service.getDefaultDashboardTemplates()

        templates.forEach { template ->
            assertTrue(
                template.widgets.isNotEmpty(),
                "Template '${template.title}' should have widgets",
            )
        }
    }
}
