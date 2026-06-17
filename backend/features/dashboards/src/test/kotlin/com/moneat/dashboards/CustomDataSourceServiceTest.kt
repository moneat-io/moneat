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

import com.moneat.dashboards.models.UpdateCustomDataSourceRequest
import com.moneat.dashboards.services.CustomDataSourceService
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CustomDataSourceServiceTest {
    private lateinit var service: CustomDataSourceService

    companion object {
        private const val ORG_ID = 1L
        private const val CREATED_BY_ID = 100L
        private var db: Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_custom_datasource_service;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        transaction {
            exec("DROP ALL OBJECTS")
            SchemaUtils.create(Users, Organizations)
            seedPrincipals()
            exec(CREATE_CUSTOM_DATA_SOURCES)
            exec(CREATE_DASHBOARDS)
            exec(CREATE_DASHBOARD_WIDGETS)
        }
        service = CustomDataSourceService()
    }

    @Test
    fun `listDataSources counts distinct dashboards using direct custom references`() {
        seedDataSource(id = 10, name = "Prometheus", sourceType = "prometheus")
        seedDataSource(id = 11, name = "Postgres", sourceType = "postgresql")
        val firstDashboard = seedDashboard(id = 100)
        val secondDashboard = seedDashboard(id = 101)
        seedWidget(id = 1000, dashboardId = firstDashboard, queryConfigs = queryConfigs("custom:${resourceId(10)}"))
        seedWidget(id = 1001, dashboardId = firstDashboard, queryConfigs = queryConfigs("custom:${resourceId(10)}"))
        seedWidget(id = 1002, dashboardId = secondDashboard, queryConfigs = queryConfigs("custom:${resourceId(10)}"))

        val counts = service.listDataSources(ORG_ID).associate { it.id to it.usedByDashboardCount }

        assertEquals(2, counts.getValue(resourceId(10)))
        assertEquals(0, counts.getValue(resourceId(11)))
    }

    @Test
    fun `listDataSources counts template markers for first enabled matching source only`() {
        seedDataSource(id = 10, name = "A Prometheus", sourceType = "prometheus")
        seedDataSource(id = 11, name = "B Prometheus", sourceType = "prometheus")
        seedDataSource(id = 12, name = "Disabled Prometheus", sourceType = "prometheus", enabled = false)
        val dashboard = seedDashboard(id = 100)
        seedWidget(id = 1000, dashboardId = dashboard, queryConfigs = queryConfigs("__prometheus"))

        val counts = service.listDataSources(ORG_ID).associate { it.id to it.usedByDashboardCount }

        assertEquals(1, counts.getValue(resourceId(10)))
        assertEquals(0, counts.getValue(resourceId(11)))
        assertEquals(0, counts.getValue(resourceId(12)))
    }

    @Test
    fun `listDataSources ignores invalid widget JSON and references from other organizations`() {
        seedDataSource(id = 10, name = "Prometheus", sourceType = "prometheus")
        val localDashboard = seedDashboard(id = 100)
        val otherDashboard = seedDashboard(id = 200, orgId = 2)
        seedWidget(id = 1000, dashboardId = localDashboard, queryConfigs = "not-json")
        seedWidget(id = 2000, dashboardId = otherDashboard, queryConfigs = queryConfigs("custom:${resourceId(10)}"))

        val source = service.listDataSources(ORG_ID).single()

        assertEquals(0, source.usedByDashboardCount)
    }

    @Test
    fun `updateDataSource clears a stored port when clearPort is set`() {
        seedDataSource(id = 20, name = "Edge Prom", sourceType = "prometheus")

        val updated = service.updateDataSource(
            id = 20,
            orgId = ORG_ID,
            request = UpdateCustomDataSourceRequest(clearPort = true),
        )

        assertNull(updated?.port)
    }

    @Test
    fun `updateDataSource keeps the existing port when port is omitted`() {
        seedDataSource(id = 21, name = "Edge Prom", sourceType = "prometheus")

        val updated = service.updateDataSource(
            id = 21,
            orgId = ORG_ID,
            request = UpdateCustomDataSourceRequest(name = "Renamed"),
        )

        assertEquals("Renamed", updated?.name)
        assertEquals(9090, updated?.port)
    }

    @Test
    fun `updateDataSource sets an explicit port and does not clear it`() {
        seedDataSource(id = 22, name = "Edge Prom", sourceType = "prometheus")

        val updated = service.updateDataSource(
            id = 22,
            orgId = ORG_ID,
            request = UpdateCustomDataSourceRequest(port = 9443, clearPort = true),
        )

        // An explicit port wins over the clear flag.
        assertEquals(9443, updated?.port)
    }

    private fun seedDataSource(
        id: Long,
        name: String,
        sourceType: String,
        enabled: Boolean = true,
    ) {
        transaction {
            exec(
                """
                INSERT INTO custom_data_sources (
                    id, resource_id, org_id, name, description, source_type, host, port, database_name,
                    encrypted_credentials, extra_config, enabled, created_by, created_at, updated_at
                ) VALUES (
                    $id, '${resourceId(id)}', $ORG_ID, '$name', NULL, '$sourceType', 'localhost', 9090, NULL,
                    'encrypted', '{}', $enabled, $CREATED_BY_ID, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """.trimIndent()
            )
        }
    }

    private fun seedDashboard(id: Long, orgId: Long = ORG_ID): Long {
        transaction {
            exec(
                """
                INSERT INTO dashboards (
                    id, resource_id, org_id, project_id, folder_id, title, description, layout_type,
                    is_default, variables, created_by, created_at, updated_at
                ) VALUES (
                    $id, '${resourceId(id)}', $orgId, NULL, NULL, 'Dashboard $id', NULL, 'grid',
                    FALSE, '[]', 100, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """.trimIndent()
            )
        }
        return id
    }

    private fun seedWidget(id: Long, dashboardId: Long, queryConfigs: String) {
        val escaped = queryConfigs.replace("'", "''")
        transaction {
            exec(
                """
                INSERT INTO dashboard_widgets (
                    id, resource_id, dashboard_id, title, widget_type, grid_x, grid_y, grid_w, grid_h,
                    query_config, query_configs, display_config, sort_order, created_at, updated_at
                ) VALUES (
                    $id, '${resourceId(id)}', $dashboardId, 'Widget $id', 'timeseries', 0, 0, 6, 4,
                    '{}', '$escaped', '{}', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """.trimIndent()
            )
        }
    }

    private fun queryConfigs(dataSource: String): String =
        """[{"dataSource":"$dataSource","metrics":[],"groupBy":[],"filters":[],"limit":100,""" +
            """"timeRange":{"from":"now-1h","to":"now"}}]"""

    private fun seedPrincipals() {
        Users.insert {
            it[id] = CREATED_BY_ID.toInt()
            it[resource_id] = Uuid.parse(resourceId(CREATED_BY_ID))
            it[email] = "user@example.com"
            it[password_hash] = "hash"
            it[name] = "Dashboard User"
        }
        Organizations.insert {
            it[id] = ORG_ID.toInt()
            it[resource_id] = Uuid.parse(resourceId(ORG_ID))
            it[name] = "Dashboard Org"
            it[slug] = "dashboard-org"
        }
    }

    private fun resourceId(id: Long): String =
        "00000000-0000-0000-0000-${id.toString().padStart(12, '0')}"
}

private const val CREATE_CUSTOM_DATA_SOURCES = """
CREATE TABLE custom_data_sources (
    id BIGINT PRIMARY KEY,
    resource_id UUID NOT NULL,
    org_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    source_type VARCHAR(50) NOT NULL,
    host VARCHAR(512) NOT NULL,
    port INT,
    database_name VARCHAR(255),
    encrypted_credentials TEXT NOT NULL,
    extra_config TEXT NOT NULL,
    enabled BOOLEAN DEFAULT TRUE NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)"""

private const val CREATE_DASHBOARDS = """
CREATE TABLE dashboards (
    id BIGINT PRIMARY KEY,
    resource_id UUID NOT NULL,
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
)"""

private const val CREATE_DASHBOARD_WIDGETS = """
CREATE TABLE dashboard_widgets (
    id BIGINT PRIMARY KEY,
    resource_id UUID NOT NULL,
    dashboard_id BIGINT NOT NULL,
    title VARCHAR(255),
    widget_type VARCHAR(50) NOT NULL,
    grid_x INT DEFAULT 0 NOT NULL,
    grid_y INT DEFAULT 0 NOT NULL,
    grid_w INT DEFAULT 6 NOT NULL,
    grid_h INT DEFAULT 4 NOT NULL,
    query_config TEXT NOT NULL,
    query_configs TEXT NOT NULL,
    display_config TEXT NOT NULL,
    sort_order INT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)"""
