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

import com.moneat.dashboards.models.CreateDashboardRequest
import com.moneat.dashboards.models.CreateWidgetRequest
import com.moneat.dashboards.models.DashboardFavorites
import com.moneat.dashboards.models.DashboardFolders
import com.moneat.dashboards.models.DashboardWidgets
import com.moneat.dashboards.models.Dashboards
import com.moneat.dashboards.models.UpdateDashboardRequest
import com.moneat.dashboards.models.UpdateWidgetRequest
import com.moneat.dashboards.repositories.DashboardFolderRepositoryImpl
import com.moneat.dashboards.repositories.DashboardRepositoryImpl
import com.moneat.dashboards.repositories.DashboardWidgetRepositoryImpl
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

private const val CREATE_USERS_DDL = """
CREATE TABLE IF NOT EXISTS users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    resource_id UUID DEFAULT RANDOM_UUID() NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(255) NULL,
    email_verified BOOLEAN DEFAULT FALSE NOT NULL,
    is_admin BOOLEAN DEFAULT FALSE NOT NULL,
    email_verification_token VARCHAR(255) NULL,
    email_verification_expires_at BIGINT NULL,
    password_reset_token VARCHAR(255) NULL,
    password_reset_expires_at BIGINT NULL,
    onboarding_completed BOOLEAN DEFAULT FALSE NOT NULL,
    oauth_provider VARCHAR(20) NULL,
    oauth_provider_id VARCHAR(512) NULL,
    phone_number VARCHAR(20) NULL,
    oncall_phone_opt_in BOOLEAN DEFAULT FALSE NOT NULL,
    oncall_phone_consented_at TIMESTAMP NULL,
    oncall_phone_consent_version VARCHAR(50) NULL,
    oncall_phone_consent_ip VARCHAR(45) NULL,
    oncall_phone_consent_user_agent TEXT NULL,
    oncall_phone_opted_out_at TIMESTAMP NULL,
    timezone VARCHAR(64) NULL,
    deleted_at TIMESTAMP NULL
)"""

private const val CREATE_ORGANIZATIONS_DDL = """
CREATE TABLE IF NOT EXISTS organizations (
    id INT AUTO_INCREMENT PRIMARY KEY,
    resource_id UUID DEFAULT RANDOM_UUID() NOT NULL,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    company_size VARCHAR(50) NULL,
    referral_source VARCHAR(100) NULL,
    utm_source VARCHAR(255) NULL,
    utm_medium VARCHAR(255) NULL,
    utm_campaign VARCHAR(255) NULL,
    utm_content VARCHAR(255) NULL,
    utm_term VARCHAR(255) NULL,
    deleted_at TIMESTAMP NULL,
    deleted_by INT NULL
)"""

private const val CREATE_FOLDERS_DDL = """
CREATE TABLE IF NOT EXISTS dashboard_folders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id UUID DEFAULT RANDOM_UUID() NOT NULL,
    org_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    color VARCHAR(7) NULL,
    sort_order INT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP(9) NOT NULL,
    updated_at TIMESTAMP(9) NOT NULL
)"""

private const val CREATE_DASHBOARDS_DDL = """
CREATE TABLE IF NOT EXISTS dashboards (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id UUID DEFAULT RANDOM_UUID() NOT NULL,
    org_id BIGINT NOT NULL,
    project_id BIGINT NULL,
    folder_id BIGINT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    layout_type VARCHAR(20) DEFAULT 'grid' NOT NULL,
    is_default BOOLEAN DEFAULT FALSE NOT NULL,
    variables TEXT DEFAULT '[]' NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(9) NOT NULL,
    updated_at TIMESTAMP(9) NOT NULL,
    CONSTRAINT fk_dashboards_folder_id FOREIGN KEY (folder_id)
        REFERENCES dashboard_folders(id)
)"""

private const val CREATE_FAVORITES_DDL = """
CREATE TABLE IF NOT EXISTS dashboard_favorites (
    user_id INT NOT NULL,
    dashboard_id BIGINT NOT NULL,
    created_at TIMESTAMP(9) NOT NULL,
    PRIMARY KEY (user_id, dashboard_id),
    CONSTRAINT fk_fav_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_fav_dash FOREIGN KEY (dashboard_id) REFERENCES dashboards(id)
)"""

private const val CREATE_WIDGETS_DDL = """
CREATE TABLE IF NOT EXISTS dashboard_widgets (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id UUID DEFAULT RANDOM_UUID() NOT NULL,
    dashboard_id BIGINT NOT NULL,
    title VARCHAR(255) NULL,
    widget_type VARCHAR(50) NOT NULL,
    grid_x INT DEFAULT 0 NOT NULL,
    grid_y INT DEFAULT 0 NOT NULL,
    grid_w INT DEFAULT 6 NOT NULL,
    grid_h INT DEFAULT 4 NOT NULL,
    query_config TEXT NOT NULL,
    query_configs TEXT NOT NULL,
    display_config TEXT NOT NULL,
    sort_order INT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP(9) NOT NULL,
    updated_at TIMESTAMP(9) NOT NULL,
    CONSTRAINT fk_widgets_dash FOREIGN KEY (dashboard_id) REFERENCES dashboards(id)
)"""

class DashboardRepositoryTest {

    private var db: Database? = null
    private lateinit var repository: DashboardRepositoryImpl
    private lateinit var folderRepository: DashboardFolderRepositoryImpl
    private lateinit var widgetRepository: DashboardWidgetRepositoryImpl

    companion object {
        private const val ORG_ID = 1L
        private const val OTHER_ORG_ID = 999L
        private const val USER_ID = 1
    }

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_dashboard_repo;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        transaction {
            exec("DROP ALL OBJECTS")
            exec(CREATE_USERS_DDL)
            exec(CREATE_ORGANIZATIONS_DDL)
            exec(CREATE_FOLDERS_DDL)
            exec(CREATE_DASHBOARDS_DDL)
            exec(CREATE_FAVORITES_DDL)
            exec(CREATE_WIDGETS_DDL)
        }
        patchJsonbColumns()
        transaction {
            Users.insert {
                it[id] = USER_ID
                it[email] = "test@moneat.io"
                it[password_hash] = "hashed"
                it[name] = "Test User"
            }
            Organizations.insert {
                it[id] = ORG_ID.toInt()
                it[name] = "Test Org"
                it[slug] = "test-org"
            }
            Organizations.insert {
                it[id] = OTHER_ORG_ID.toInt()
                it[name] = "Other Org"
                it[slug] = "other-org"
            }
        }
        repository = DashboardRepositoryImpl()
        folderRepository = DashboardFolderRepositoryImpl()
        widgetRepository = DashboardWidgetRepositoryImpl()
    }

    private fun patchJsonbColumns() {
        val h2TextJson = object : ColumnType<String>() {
            override fun sqlType(): String = "TEXT"
            override fun valueFromDB(value: Any): String = when (value) {
                is String -> value
                else -> value.toString()
            }
            override fun notNullValueToDB(value: String): Any = value
        }
        val field = Column::class.java.getDeclaredField("columnType")
        field.isAccessible = true
        val jsonbClassNames = listOf(
            "com.moneat.dashboards.models.JsonbColumnType",
            "com.moneat.shared.models.JsonbColumnType"
        )
        val allTables = arrayOf(
            Users,
            Organizations,
            DashboardFolders,
            Dashboards,
            DashboardFavorites,
            DashboardWidgets
        )
        allTables.forEach { table ->
            table.columns.forEach { col ->
                if (col.columnType is AutoIncColumnType) {
                    col.columnType.nullable = false
                }
                val qn = col.columnType::class.qualifiedName
                if (qn != null && jsonbClassNames.any { qn == it }) {
                    field.set(col, h2TextJson)
                }
            }
        }
    }

    private fun createDashboard(
        title: String = "Test Dashboard",
        description: String? = null
    ) = repository.create(
        orgId = ORG_ID,
        userId = USER_ID.toLong(),
        request = CreateDashboardRequest(
            title = title,
            description = description
        )
    )

    // ──── Dashboard Create ────

    @Test
    fun `create returns dashboard with correct title and orgId`() {
        val result = createDashboard(title = "My Dashboard")
        assertEquals("My Dashboard", result.title)
        assertEquals(ORG_ID, result.orgId)
        assertEquals(USER_ID.toLong(), result.createdBy)
    }

    @Test
    fun `create returns owner name from creating user`() {
        val result = createDashboard(title = "Owned Dashboard")
        assertEquals("Test User", result.ownerName)
    }

    @Test
    fun `create with description stores description`() {
        val result = createDashboard(title = "WithDesc", description = "A detailed description")
        assertEquals("A detailed description", result.description)
    }

    @Test
    fun `create assigns unique ids to each dashboard`() {
        val first = createDashboard(title = "First")
        val second = createDashboard(title = "Second")
        assertTrue(first.id != second.id)
    }

    @Test
    fun `create stores layout type`() {
        val result = repository.create(
            orgId = ORG_ID,
            userId = USER_ID.toLong(),
            request = CreateDashboardRequest(title = "Custom", layoutType = "free")
        )
        assertEquals("free", result.layoutType)
    }

    @Test
    fun `create defaults isDefault to false`() {
        val result = createDashboard()
        assertFalse(result.isDefault)
    }

    // ──── Dashboard List ────

    @Test
    fun `list returns created dashboards for org`() {
        createDashboard(title = "Dash A")
        createDashboard(title = "Dash B")

        val dashboards = repository.list(orgId = ORG_ID)
        assertEquals(2, dashboards.size)
    }

    @Test
    fun `list returns dashboard owner names`() {
        createDashboard(title = "Owned List")

        val dashboards = repository.list(orgId = ORG_ID)

        assertEquals("Test User", dashboards.single().ownerName)
    }

    @Test
    fun `list returns empty for different org`() {
        createDashboard()
        val dashboards = repository.list(orgId = 999L)
        assertTrue(dashboards.isEmpty())
    }

    @Test
    fun `list with userId shows favorite status`() {
        val dash = createDashboard(title = "Favable")
        repository.toggleFavorite(USER_ID, dash.id, ORG_ID)

        val dashboards = repository.list(orgId = ORG_ID, userId = USER_ID)
        val found = dashboards.first { it.id == dash.id }
        assertTrue(found.isFavorited)
    }

    @Test
    fun `list without userId does not set favorite flag`() {
        val dash = createDashboard(title = "NoPref")
        repository.toggleFavorite(USER_ID, dash.id, ORG_ID)

        val dashboards = repository.list(orgId = ORG_ID, userId = null)
        val found = dashboards.first { it.id == dash.id }
        assertFalse(found.isFavorited)
    }

    // ──── Dashboard GetById ────

    @Test
    fun `getById returns dashboard when it exists`() {
        val created = createDashboard(title = "FindMe")
        val found = repository.getById(created.id, ORG_ID)
        assertNotNull(found)
        assertEquals("FindMe", found.title)
    }

    @Test
    fun `getById returns dashboard owner name`() {
        val created = createDashboard(title = "FindOwner")

        val found = repository.getById(created.id, ORG_ID)

        assertNotNull(found)
        assertEquals("Test User", found.ownerName)
    }

    @Test
    fun `getById returns null for wrong org`() {
        val created = createDashboard()
        val found = repository.getById(created.id, 999L)
        assertNull(found)
    }

    @Test
    fun `getById returns null for non-existent id`() {
        val found = repository.getById(9999L, ORG_ID)
        assertNull(found)
    }

    @Test
    fun `getById with userId shows favorite status`() {
        val dash = createDashboard(title = "ByIdFav")
        repository.toggleFavorite(USER_ID, dash.id, ORG_ID)

        val found = repository.getById(dash.id, ORG_ID, USER_ID)
        assertNotNull(found)
        assertTrue(found.isFavorited)
    }

    @Test
    fun `getById without userId shows not favorited`() {
        val dash = createDashboard(title = "ByIdNoFav")
        repository.toggleFavorite(USER_ID, dash.id, ORG_ID)

        val found = repository.getById(dash.id, ORG_ID)
        assertNotNull(found)
        assertFalse(found.isFavorited)
    }

    // ──── Dashboard Update ────

    @Test
    fun `update changes title`() {
        val created = createDashboard(title = "Original")
        val updated = repository.update(created.id, ORG_ID, UpdateDashboardRequest(title = "Renamed"))
        assertTrue(updated)
        val found = repository.getById(created.id, ORG_ID)
        assertEquals("Renamed", found?.title)
    }

    @Test
    fun `update changes description`() {
        val created = createDashboard(title = "UpdDesc")
        repository.update(created.id, ORG_ID, UpdateDashboardRequest(description = "New desc"))
        val found = repository.getById(created.id, ORG_ID)
        assertEquals("New desc", found?.description)
    }

    @Test
    fun `update returns false for non-existent dashboard`() {
        val updated = repository.update(9999L, ORG_ID, UpdateDashboardRequest(title = "No"))
        assertFalse(updated)
    }

    @Test
    fun `update changes isDefault flag`() {
        val created = createDashboard(title = "DefFlag")
        repository.update(created.id, ORG_ID, UpdateDashboardRequest(isDefault = true))
        val found = repository.getById(created.id, ORG_ID)
        assertNotNull(found)
        assertTrue(found.isDefault)
    }

    @Test
    fun `update changes layoutType`() {
        val created = createDashboard(title = "Layout")
        repository.update(created.id, ORG_ID, UpdateDashboardRequest(layoutType = "free"))
        val found = repository.getById(created.id, ORG_ID)
        assertEquals("free", found?.layoutType)
    }

    // ──── setDefault ────

    @Test
    fun `setDefault marks one dashboard and clears the others`() {
        val first = createDashboard(title = "First")
        val second = createDashboard(title = "Second")

        assertTrue(repository.setDefault(first.id, ORG_ID))
        assertTrue(repository.getById(first.id, ORG_ID)!!.isDefault)

        assertTrue(repository.setDefault(second.id, ORG_ID))
        assertTrue(repository.getById(second.id, ORG_ID)!!.isDefault)
        assertFalse(repository.getById(first.id, ORG_ID)!!.isDefault)
    }

    @Test
    fun `setDefault returns false for a non-existent dashboard`() {
        assertFalse(repository.setDefault(999_999L, ORG_ID))
    }

    // ──── Dashboard Delete ────

    @Test
    fun `delete removes dashboard`() {
        val created = createDashboard()
        val deleted = repository.delete(created.id, ORG_ID)
        assertTrue(deleted)
        assertNull(repository.getById(created.id, ORG_ID))
    }

    @Test
    fun `delete returns false for non-existent dashboard`() {
        assertFalse(repository.delete(9999L, ORG_ID))
    }

    @Test
    fun `delete returns false for wrong org`() {
        val created = createDashboard()
        assertFalse(repository.delete(created.id, 999L))
        assertNotNull(repository.getById(created.id, ORG_ID))
    }

    // ──── Dashboard Favorites ────

    @Test
    fun `toggleFavorite adds then removes favorite`() {
        val created = createDashboard()

        val favorited = repository.toggleFavorite(USER_ID, created.id, ORG_ID)
        assertTrue(favorited)

        val dash = repository.getById(created.id, ORG_ID, USER_ID)
        assertNotNull(dash)
        assertTrue(dash.isFavorited)

        val unfavorited = repository.toggleFavorite(USER_ID, created.id, ORG_ID)
        assertFalse(unfavorited)

        val dash2 = repository.getById(created.id, ORG_ID, USER_ID)
        assertNotNull(dash2)
        assertFalse(dash2.isFavorited)
    }

    @Test
    fun `toggleFavorite returns false for non-existent dashboard`() {
        assertFalse(repository.toggleFavorite(USER_ID, 9999L, ORG_ID))
    }

    // ──── Dashboard MoveToFolder ────

    @Test
    fun `moveToFolder returns false for non-existent dashboard`() {
        assertFalse(repository.moveToFolder(9999L, ORG_ID, null))
    }

    @Test
    fun `moveToFolder moves dashboard to folder`() {
        val created = createDashboard(title = "Movable")
        val folderId = folderRepository.create(ORG_ID, "Folder A", null, 0)
        assertTrue(repository.moveToFolder(created.id, ORG_ID, folderId))
        val found = repository.getById(created.id, ORG_ID)
        assertEquals(folderId, found?.folderId)
    }

    @Test
    fun `moveToFolder clears folder when given null`() {
        val created = createDashboard(title = "Unfile")
        val folderId = folderRepository.create(ORG_ID, "Folder B", null, 0)
        repository.moveToFolder(created.id, ORG_ID, folderId)
        repository.moveToFolder(created.id, ORG_ID, null)
        val found = repository.getById(created.id, ORG_ID)
        assertNull(found?.folderId)
    }

    // ──── Dashboard Search ────

    @Test
    fun `search finds dashboards by title pattern`() {
        createDashboard(title = "CPU Metrics")
        createDashboard(title = "Memory Stats")

        val results = repository.search(ORG_ID, USER_ID, "%cpu%")
        assertEquals(1, results.size)
        assertEquals("CPU Metrics", results.first().title)
    }

    @Test
    fun `search returns empty for no match`() {
        createDashboard(title = "Something")
        val results = repository.search(ORG_ID, USER_ID, "%nonexistent%")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search finds dashboards by description pattern`() {
        repository.create(
            orgId = ORG_ID,
            userId = USER_ID.toLong(),
            request = CreateDashboardRequest(title = "X", description = "network traffic overview")
        )
        createDashboard(title = "Y")

        val results = repository.search(ORG_ID, USER_ID, "%network%")
        assertEquals(1, results.size)
        assertEquals("X", results.first().title)
    }

    @Test
    fun `search includes favorite status`() {
        val dash = createDashboard(title = "SearchFav")
        repository.toggleFavorite(USER_ID, dash.id, ORG_ID)

        val results = repository.search(ORG_ID, USER_ID, "%searchfav%")
        assertEquals(1, results.size)
        assertTrue(results.first().isFavorited)
    }

    // ──── Folder Repository ────

    @Test
    fun `folder create and list`() {
        folderRepository.create(ORG_ID, "Prod", "#ff0000", 0)
        folderRepository.create(ORG_ID, "Staging", "#00ff00", 1)

        val folders = folderRepository.listByOrgId(ORG_ID)
        assertEquals(2, folders.size)
        assertEquals("Prod", folders[0].name)
        assertEquals("Staging", folders[1].name)
    }

    @Test
    fun `folder getByIdAndOrgId returns folder`() {
        val folderId = folderRepository.create(ORG_ID, "GetMe", "#0000ff", 0)
        val found = folderRepository.getByIdAndOrgId(folderId, ORG_ID)
        assertNotNull(found)
        assertEquals("GetMe", found.name)
        assertEquals("#0000ff", found.color)
    }

    @Test
    fun `folder getByIdAndOrgId returns null for wrong org`() {
        val folderId = folderRepository.create(ORG_ID, "WrongOrg", null, 0)
        assertNull(folderRepository.getByIdAndOrgId(folderId, 999L))
    }

    @Test
    fun `folder update changes name`() {
        val folderId = folderRepository.create(ORG_ID, "Old", null, 0)
        folderRepository.update(folderId, ORG_ID, name = "New", color = null, sortOrder = null)
        val found = folderRepository.getByIdAndOrgId(folderId, ORG_ID)
        assertEquals("New", found?.name)
    }

    @Test
    fun `folder update changes color`() {
        val folderId = folderRepository.create(ORG_ID, "Color", null, 0)
        folderRepository.update(folderId, ORG_ID, name = null, color = "#abcdef", sortOrder = null)
        val found = folderRepository.getByIdAndOrgId(folderId, ORG_ID)
        assertEquals("#abcdef", found?.color)
    }

    @Test
    fun `folder delete removes folder`() {
        val folderId = folderRepository.create(ORG_ID, "DelMe", null, 0)
        val deleted = folderRepository.delete(folderId, ORG_ID)
        assertEquals(1, deleted)
        assertNull(folderRepository.getByIdAndOrgId(folderId, ORG_ID))
    }

    @Test
    fun `folder delete returns 0 for non-existent folder`() {
        assertEquals(0, folderRepository.delete(9999L, ORG_ID))
    }

    @Test
    fun `folder listByOrgId returns empty for different org`() {
        folderRepository.create(ORG_ID, "OnlyHere", null, 0)
        assertTrue(folderRepository.listByOrgId(999L).isEmpty())
    }

    // ──── Widget Repository ────

    @Test
    fun `widget insert and list`() {
        val dash = createDashboard(title = "WidgetDash")
        val now = Clock.System.now()
        val widgetId = widgetRepository.insert(
            dashboardId = dash.id,
            widget = CreateWidgetRequest(
                title = "CPU Chart",
                widgetType = "timeseries"
            ),
            sortOrder = 0,
            now = now
        )
        assertTrue(widgetId > 0)

        val widgets = widgetRepository.listByDashboardId(dash.id)
        assertEquals(1, widgets.size)
        assertEquals("CPU Chart", widgets[0].title)
        assertEquals("timeseries", widgets[0].widgetType)
    }

    @Test
    fun `widget listByDashboardId returns empty for no widgets`() {
        val dash = createDashboard(title = "EmptyWidgets")
        assertTrue(widgetRepository.listByDashboardId(dash.id).isEmpty())
    }

    @Test
    fun `widget bulkUpsert inserts new widgets`() {
        val dash = createDashboard(title = "BulkDash")
        val now = Clock.System.now()
        val keptIds = widgetRepository.bulkUpsert(
            dashboardId = dash.id,
            widgets = listOf(
                UpdateWidgetRequest(widgetType = "timeseries", title = "W1"),
                UpdateWidgetRequest(widgetType = "bar", title = "W2")
            ),
            now = now
        )
        assertEquals(2, keptIds.size)
        assertEquals(2, widgetRepository.listByDashboardId(dash.id).size)
    }

    @Test
    fun `widget deleteNotIn removes orphaned widgets`() {
        val dash = createDashboard(title = "DeleteWidgets")
        val now = Clock.System.now()
        val id1 = widgetRepository.insert(
            dash.id,
            CreateWidgetRequest(title = "Keep", widgetType = "timeseries"),
            0,
            now
        )
        widgetRepository.insert(
            dash.id,
            CreateWidgetRequest(title = "Remove", widgetType = "bar"),
            1,
            now
        )
        widgetRepository.deleteNotIn(dash.id, setOf(id1))
        val remaining = widgetRepository.listByDashboardId(dash.id)
        assertEquals(1, remaining.size)
        assertEquals("Keep", remaining[0].title)
    }

    @Test
    fun `widget deleteNotIn with empty set removes all`() {
        val dash = createDashboard(title = "ClearWidgets")
        val now = Clock.System.now()
        widgetRepository.insert(
            dash.id,
            CreateWidgetRequest(title = "A", widgetType = "timeseries"),
            0,
            now
        )
        widgetRepository.insert(
            dash.id,
            CreateWidgetRequest(title = "B", widgetType = "bar"),
            1,
            now
        )
        widgetRepository.deleteNotIn(dash.id, emptySet())
        assertTrue(widgetRepository.listByDashboardId(dash.id).isEmpty())
    }
}
