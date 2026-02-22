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

package com.moneat.services

import com.moneat.billing.models.PricingTierConfigs
import com.moneat.config.ClickHouseClient
import com.moneat.events.models.CreateProjectRequest
import com.moneat.events.models.UpdateProjectRequest
import com.moneat.events.services.DashboardService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.ProjectKeys
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.respond
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardServiceProjectTest {
    companion object {
        private var db: Database? = null

        fun seedUser(email: String = "user@test.com"): Int = transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hash"
            } get Users.id
        }

        fun seedOrg(name: String = "Test Org"): Int = transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

        fun seedMembership(userId: Int, orgId: Int): Int = transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            } get Memberships.id
        }

        fun seedProSubscription(orgId: Int): Int = transaction {
            Subscriptions.insert {
                it[organization_id] = orgId
                it[plan] = "PRO"
                it[status] = "active"
            } get Subscriptions.id
        }

        fun seedProject(orgId: Int, name: String = "Test Project", framework: String? = null): Long = transaction {
            Projects.insert {
                it[organization_id] = orgId
                it[Projects.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
                it[Projects.framework] = framework
            } get Projects.id
        }
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_dashboard_project;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction(db!!) {
                SchemaUtils.create(
                    Organizations,
                    Users,
                    Memberships,
                    Projects,
                    ProjectKeys,
                    Subscriptions,
                    PricingTierConfigs
                )
            }
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db
        // Clean between tests
        transaction {
            ProjectKeys.deleteAll()
            Projects.deleteAll()
            Subscriptions.deleteAll()
            Memberships.deleteAll()
            Organizations.deleteAll()
            Users.deleteAll()
        }
    }

    // ===================== hasProjectAccess =====================

    @Test
    fun `hasProjectAccess returns true when user is member of project org`() {
        val userId = seedUser()
        val orgId = seedOrg()
        seedMembership(userId, orgId)
        val projectId = seedProject(orgId)

        val service = DashboardService()
        assertTrue(service.hasProjectAccess(userId, projectId))
    }

    @Test
    fun `hasProjectAccess returns false when user is not a member of project org`() {
        val userId = seedUser("other@test.com")
        val orgId = seedOrg()
        val projectId = seedProject(orgId)

        val service = DashboardService()
        assertFalse(service.hasProjectAccess(userId, projectId))
    }

    @Test
    fun `hasProjectAccess returns false for non-existent project`() {
        val userId = seedUser()
        val orgId = seedOrg()
        seedMembership(userId, orgId)

        val service = DashboardService()
        assertFalse(service.hasProjectAccess(userId, 999999L))
    }

    @Test
    fun `hasProjectAccess returns false when user is member of different org`() {
        val userId = seedUser()
        val orgA = seedOrg("Org A")
        val orgB = seedOrg("Org B")
        seedMembership(userId, orgA)
        val projectInB = seedProject(orgB)

        val service = DashboardService()
        assertFalse(service.hasProjectAccess(userId, projectInB))
    }

    // ===================== createProject =====================

    @Test
    fun `createProject creates project with generated slug and DSN`() {
        val userId = seedUser()
        val orgId = seedOrg()
        seedMembership(userId, orgId)

        val service = DashboardService()
        val result = service.createProject(userId, CreateProjectRequest(name = "My App", framework = "kotlin"))

        assertEquals("My App", result.name)
        assertEquals("my-app", result.slug)
        assertEquals("kotlin", result.framework)
        assertTrue(result.keys.isNotEmpty())
        assertTrue(result.dsn.isNotEmpty())
    }

    @Test
    fun `createProject throws when user has no organization`() {
        val userId = seedUser()

        val service = DashboardService()
        val ex = kotlin.test.assertFailsWith<IllegalStateException> {
            service.createProject(userId, CreateProjectRequest(name = "No Org Project"))
        }
        assertEquals(ex.message?.contains("no organization"), true)
    }

    @Test
    fun `createProject throws when project with same name already exists`() {
        val userId = seedUser()
        val orgId = seedOrg()
        seedMembership(userId, orgId)
        seedProSubscription(orgId)
        seedProject(orgId, "Duplicate")

        val service = DashboardService()
        val ex = kotlin.test.assertFailsWith<IllegalStateException> {
            service.createProject(userId, CreateProjectRequest(name = "Duplicate"))
        }
        // Service message: "A project with this name already exists"
        assertEquals(ex.message?.contains("already exists"), true)
    }

    @Test
    fun `createProject with multiple targets creates multiple keys`() {
        val userId = seedUser()
        val orgId = seedOrg()
        seedMembership(userId, orgId)

        val service = DashboardService()
        val result = service.createProject(
            userId,
            CreateProjectRequest(name = "Multi Target", targets = listOf("android", "ios"))
        )

        assertEquals(2, result.keys.size)
        val targets = result.keys.map { it.platformTarget }
        assertTrue(targets.contains("android"))
        assertTrue(targets.contains("ios"))
    }

    @Test
    fun `createProject with no targets creates single key`() {
        val userId = seedUser()
        val orgId = seedOrg()
        seedMembership(userId, orgId)

        val service = DashboardService()
        val result = service.createProject(userId, CreateProjectRequest(name = "Single Key App"))

        assertEquals(1, result.keys.size)
        assertNull(result.keys.first().platformTarget)
    }

    @Test
    fun `createProject normalizes special characters in slug`() {
        val userId = seedUser()
        val orgId = seedOrg()
        seedMembership(userId, orgId)

        val service = DashboardService()
        val result = service.createProject(userId, CreateProjectRequest(name = "My App v2.0!"))

        assertEquals("my-app-v2-0", result.slug)
    }

    // ===================== addProjectTarget =====================

    @Test
    fun `addProjectTarget adds new platform key`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId)

        val service = DashboardService()
        val result = service.addProjectTarget(projectId, "web")

        assertEquals("web", result.platformTarget)
        assertTrue(result.dsn.isNotEmpty())
    }

    @Test
    fun `addProjectTarget throws when target already exists`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId)
        transaction {
            ProjectKeys.insert {
                it[project_id] = projectId
                it[public_key] = "existingkey"
                it[secret_key] = "secretkey"
                it[platform_target] = "android"
                it[is_active] = true
            }
        }

        val service = DashboardService()
        val ex = kotlin.test.assertFailsWith<IllegalStateException> {
            service.addProjectTarget(projectId, "android")
        }
        assertEquals(ex.message?.contains("android"), true)
    }

    @Test
    fun `addProjectTarget null target does not conflict with android target`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId)
        transaction {
            ProjectKeys.insert {
                it[project_id] = projectId
                it[public_key] = "nullkey"
                it[secret_key] = "secret"
                it[platform_target] = null
                it[is_active] = true
            }
        }

        val service = DashboardService()
        // Should NOT throw - null and "android" are different targets
        val result = service.addProjectTarget(projectId, "android")
        assertEquals("android", result.platformTarget)
    }

    // ===================== updateProject =====================

    @Test
    fun `updateProject updates project name and slug`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId, "Old Name")

        val service = DashboardService()
        service.updateProject(projectId, UpdateProjectRequest(name = "New Name"))

        val updated = transaction {
            Projects.selectAll().where { Projects.id eq projectId }.firstOrNull()
        }
        assertNotNull(updated)
        assertEquals("New Name", updated[Projects.name])
        assertEquals("new-name", updated[Projects.slug])
    }

    @Test
    fun `updateProject updates framework`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId, "App", "java")

        val service = DashboardService()
        service.updateProject(projectId, UpdateProjectRequest(framework = "kotlin"))

        val updated = transaction {
            Projects.selectAll().where { Projects.id eq projectId }.firstOrNull()
        }
        assertNotNull(updated)
        assertEquals("kotlin", updated[Projects.framework])
    }

    @Test
    fun `updateProject with no fields does not affect existing name`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId, "Stable App")

        val service = DashboardService()
        service.updateProject(projectId, UpdateProjectRequest(name = "Stable App"))

        // Project unchanged
        val row = transaction {
            Projects.selectAll().where { Projects.id eq projectId }.firstOrNull()
        }
        assertEquals("Stable App", row!![Projects.name])
    }

    // ===================== deleteProject =====================

    @Test
    fun `deleteProject removes the project`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId, "To Delete")

        val service = DashboardService()
        service.deleteProject(projectId)

        val row = transaction {
            Projects.selectAll().where { Projects.id eq projectId }.firstOrNull()
        }
        assertNull(row)
    }

    @Test
    fun `deleteProject does not affect other projects`() {
        val orgId = seedOrg()
        val projectId1 = seedProject(orgId, "Keep Me")
        val projectId2 = seedProject(orgId, "Delete Me")

        val service = DashboardService()
        service.deleteProject(projectId2)

        val remaining = transaction {
            Projects.selectAll().where { Projects.id eq projectId1 }.firstOrNull()
        }
        assertNotNull(remaining)
    }

    // ===================== getProjects (with MockHttpServer) =====================

    @Test
    fun `getProjects returns projects with issue counts from ClickHouse`() = runBlocking {
        val userId = seedUser()
        val orgId = seedOrg()
        seedMembership(userId, orgId)
        val projectId = seedProject(orgId, "ClickHouse App")

        MockHttpServer { exchange ->
            exchange.respond(
                200,
                """{"project_id":$projectId,"count":42}""",
                contentType = "text/plain"
            )
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val service = DashboardService()
            val projects = service.getProjects(userId)

            assertEquals(1, projects.size)
            assertEquals("ClickHouse App", projects.first().name)
            assertEquals(42, projects.first().issueCount)
        }
    }

    @Test
    fun `getProjects returns empty list when user has no orgs`() = runBlocking {
        val userId = seedUser()

        MockHttpServer { exchange ->
            exchange.respond(200, "", contentType = "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val service = DashboardService()
            val projects = service.getProjects(userId)

            assertTrue(projects.isEmpty())
        }
    }

    @Test
    fun `getProjects returns only projects for user org`() = runBlocking {
        val userId1 = seedUser("u1@test.com")
        val userId2 = seedUser("u2@test.com")
        val orgId1 = seedOrg("Org 1")
        val orgId2 = seedOrg("Org 2")
        seedMembership(userId1, orgId1)
        seedMembership(userId2, orgId2)
        seedProject(orgId1, "Org1 Project")
        seedProject(orgId2, "Org2 Project")

        MockHttpServer { exchange ->
            exchange.respond(200, "", contentType = "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val service = DashboardService()
            val projects = service.getProjects(userId1)

            assertEquals(1, projects.size)
            assertEquals("Org1 Project", projects.first().name)
        }
    }

    // ===================== getProject (with MockHttpServer) =====================

    @Test
    fun `getProject returns project detail with issue count`() = runBlocking {
        val orgId = seedOrg()
        val projectId = seedProject(orgId, "Detail App", "react")
        transaction {
            ProjectKeys.insert {
                it[project_id] = projectId
                it[public_key] = "testpubkey123"
                it[secret_key] = "testseckey123"
                it[platform_target] = null
                it[is_active] = true
            }
        }

        MockHttpServer { exchange ->
            exchange.respond(200, """{"count":7}""", contentType = "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val service = DashboardService()
            val project = service.getProject(projectId)

            assertNotNull(project)
            assertEquals("Detail App", project.name)
            assertEquals("react", project.framework)
            assertEquals(7, project.issueCount)
            assertTrue(project.dsn.contains("testpubkey123"))
        }
    }

    @Test
    fun `getProject returns null for non-existent project`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, "", contentType = "text/plain")
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            val service = DashboardService()
            val project = service.getProject(999999L)

            assertNull(project)
        }
    }
}
