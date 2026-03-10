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
import com.moneat.events.models.CreateProjectRequest
import com.moneat.events.models.ProjectKeyResponse
import com.moneat.events.models.UpdateProjectRequest
import com.moneat.events.repositories.IssueRepository
import com.moneat.events.repositories.ProjectRepository
import com.moneat.events.repositories.models.ProjectRow
import com.moneat.events.services.DashboardService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for DashboardService project-related operations.
 *
 * H2 setup is retained for tests exercising internal services that bypass the repository:
 *  - AccessService.hasProjectAccess queries Projects/Memberships directly.
 *  - PricingTierService.getPrimaryOrganizationIdForUser queries Memberships directly.
 * All project-level operations (create/update/delete/list) go through the mocked ProjectRepository.
 */
class DashboardServiceProjectTest {

    private val mockProjectRepo = mockk<ProjectRepository>(relaxed = true)
    private val mockIssueRepo = mockk<IssueRepository>(relaxed = true)

    companion object {
        private const val PROJECT_NAME = "My App"
        private const val PROJECT_SLUG = "my-app"
        private const val MULTI_TARGET_NAME = "Multi Target"
        private const val MULTI_TARGET_SLUG = "multi-target"
        private const val SINGLE_KEY_NAME = "Single Key App"
        private const val SINGLE_KEY_SLUG = "single-key-app"
        private const val SPECIAL_CHARS_NAME = "My App v2.0!"
        private const val SPECIAL_CHARS_SLUG = "my-app-v2-0"
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

        fun seedMembership(userId: Int, orgId: Int) = transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
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
                url = "jdbc:h2:mem:moneat_dashboard_project;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db
        // Subscriptions/PricingTierConfigs needed by PricingTierService.getEffectiveTierForOrganization
        TestDatabaseHelper.resetSchema(
            Organizations,
            Users,
            Memberships,
            Projects,
            Subscriptions,
            PricingTierConfigs
        )
    }

    private fun makeDashboardService() = DashboardService(mockProjectRepo, mockIssueRepo)

    // ===================== hasProjectAccess =====================
    // AccessService.hasProjectAccess queries Projects + Memberships directly (bypasses repo).
    // H2 seed data is required for these tests.

    @Test
    fun `hasProjectAccess returns true when user is member of project org`() {
        val userId = seedUser()
        val orgId = seedOrg()
        seedMembership(userId, orgId)
        val projectId = seedProject(orgId)
        assertTrue(makeDashboardService().hasProjectAccess(userId, projectId))
    }

    @Test
    fun `hasProjectAccess returns false when user is not a member of project org`() {
        val userId = seedUser("other@test.com")
        val orgId = seedOrg()
        val projectId = seedProject(orgId)
        assertFalse(makeDashboardService().hasProjectAccess(userId, projectId))
    }

    @Test
    fun `hasProjectAccess returns false for non-existent project`() {
        val userId = seedUser()
        val orgId = seedOrg()
        seedMembership(userId, orgId)
        assertFalse(makeDashboardService().hasProjectAccess(userId, 999999L))
    }

    @Test
    fun `hasProjectAccess returns false when user is member of different org`() {
        val userId = seedUser()
        val orgA = seedOrg("Org A")
        val orgB = seedOrg("Org B")
        seedMembership(userId, orgA)
        val projectInB = seedProject(orgB)
        assertFalse(makeDashboardService().hasProjectAccess(userId, projectInB))
    }

    // ===================== createProject =====================
    // PricingTierService.getPrimaryOrganizationIdForUser queries Memberships directly.
    // H2 seed data is required for org/membership setup; all repo calls are mocked.

    @Test
    fun `createProject creates project with generated slug and DSN`() = runBlocking {
        val userId = seedUser()
        seedMembership(userId, seedOrg())

        every { mockProjectRepo.getProjectCountForOrganization(any()) } returns 0
        every { mockProjectRepo.findProjectByNameOrSlug(any(), PROJECT_NAME, PROJECT_SLUG) } returns null
        every { mockProjectRepo.createProject(any(), PROJECT_NAME, PROJECT_SLUG, "kotlin") } returns 42L
        every { mockProjectRepo.findProjectKeyByTarget(42L, null) } returns false
        every { mockProjectRepo.createProjectKey(42L, any(), any(), null) } just runs
        every { mockProjectRepo.getProjectById(42L) } returns ProjectRow(
            projectId = 42L, name = PROJECT_NAME, slug = PROJECT_SLUG, framework = "kotlin",
            keys = listOf(ProjectKeyResponse(null, "http://testkey@test/42")),
            dsn = "http://testkey@test/42"
        )
        coEvery { mockProjectRepo.getIssueCountForProject(42L, any(), null) } returns 0L

        val result = makeDashboardService().createProject(
            userId,
            CreateProjectRequest(name = PROJECT_NAME, framework = "kotlin")
        )

        assertEquals(PROJECT_NAME, result.name)
        assertEquals(PROJECT_SLUG, result.slug)
        assertEquals("kotlin", result.framework)
        assertTrue(result.keys.isNotEmpty())
        assertTrue(result.dsn.isNotEmpty())
    }

    @Test
    fun `createProject throws when user has no organization`() = runBlocking {
        val ex = assertFailsWith<IllegalStateException> {
            makeDashboardService().createProject(999, CreateProjectRequest(name = "No Org Project"))
        }
        assertTrue(ex.message?.contains("no organization") == true)
    }

    @Test
    fun `createProject throws when project with same name already exists`() = runBlocking {
        val userId = seedUser()
        seedMembership(userId, seedOrg())

        every { mockProjectRepo.getProjectCountForOrganization(any()) } returns 0
        every { mockProjectRepo.findProjectByNameOrSlug(any(), "Duplicate", "duplicate") } returns
            ProjectRow(1L, "Duplicate", "duplicate", null, emptyList(), "")

        val ex = assertFailsWith<IllegalStateException> {
            makeDashboardService().createProject(userId, CreateProjectRequest(name = "Duplicate"))
        }
        assertTrue(ex.message?.contains("already exists") == true)
    }

    @Test
    fun `createProject with multiple targets creates multiple keys`() = runBlocking {
        val userId = seedUser()
        seedMembership(userId, seedOrg())

        every { mockProjectRepo.getProjectCountForOrganization(any()) } returns 0
        every { mockProjectRepo.findProjectByNameOrSlug(any(), MULTI_TARGET_NAME, MULTI_TARGET_SLUG) } returns null
        every { mockProjectRepo.createProject(any(), MULTI_TARGET_NAME, MULTI_TARGET_SLUG, null) } returns 100L
        every { mockProjectRepo.findProjectKeyByTarget(100L, any()) } returns false
        every { mockProjectRepo.createProjectKey(100L, any(), any(), any()) } just runs
        every { mockProjectRepo.getProjectById(100L) } returns ProjectRow(
            projectId = 100L, name = MULTI_TARGET_NAME, slug = MULTI_TARGET_SLUG, framework = null,
            keys = listOf(
                ProjectKeyResponse("android", "http://k1@test/100"),
                ProjectKeyResponse("ios", "http://k2@test/100"),
            ),
            dsn = "http://k1@test/100"
        )
        coEvery { mockProjectRepo.getIssueCountForProject(100L, any(), null) } returns 0L

        val result = makeDashboardService().createProject(
            userId,
            CreateProjectRequest(name = MULTI_TARGET_NAME, targets = listOf("android", "ios"))
        )

        assertEquals(2, result.keys.size)
        val targets = result.keys.map { it.platformTarget }
        assertTrue(targets.contains("android"))
        assertTrue(targets.contains("ios"))
    }

    @Test
    fun `createProject with no targets creates single key`() = runBlocking {
        val userId = seedUser()
        seedMembership(userId, seedOrg())

        every { mockProjectRepo.getProjectCountForOrganization(any()) } returns 0
        every { mockProjectRepo.findProjectByNameOrSlug(any(), SINGLE_KEY_NAME, SINGLE_KEY_SLUG) } returns null
        every { mockProjectRepo.createProject(any(), SINGLE_KEY_NAME, SINGLE_KEY_SLUG, null) } returns 200L
        every { mockProjectRepo.findProjectKeyByTarget(200L, null) } returns false
        every { mockProjectRepo.createProjectKey(200L, any(), any(), null) } just runs
        every { mockProjectRepo.getProjectById(200L) } returns ProjectRow(
            projectId = 200L, name = SINGLE_KEY_NAME, slug = SINGLE_KEY_SLUG, framework = null,
            keys = listOf(ProjectKeyResponse(null, "http://k@test/200")),
            dsn = "http://k@test/200"
        )
        coEvery { mockProjectRepo.getIssueCountForProject(200L, any(), null) } returns 0L

        val result = makeDashboardService().createProject(userId, CreateProjectRequest(name = SINGLE_KEY_NAME))

        assertEquals(1, result.keys.size)
        assertNull(result.keys.first().platformTarget)
    }

    @Test
    fun `createProject normalizes special characters in slug`() = runBlocking {
        val userId = seedUser()
        seedMembership(userId, seedOrg())

        every { mockProjectRepo.getProjectCountForOrganization(any()) } returns 0
        every { mockProjectRepo.findProjectByNameOrSlug(any(), SPECIAL_CHARS_NAME, SPECIAL_CHARS_SLUG) } returns null
        every { mockProjectRepo.createProject(any(), SPECIAL_CHARS_NAME, SPECIAL_CHARS_SLUG, null) } returns 300L
        every { mockProjectRepo.findProjectKeyByTarget(300L, null) } returns false
        every { mockProjectRepo.createProjectKey(300L, any(), any(), null) } just runs
        every { mockProjectRepo.getProjectById(300L) } returns ProjectRow(
            projectId = 300L, name = SPECIAL_CHARS_NAME, slug = SPECIAL_CHARS_SLUG, framework = null,
            keys = listOf(ProjectKeyResponse(null, "http://k@test/300")),
            dsn = "http://k@test/300"
        )
        coEvery { mockProjectRepo.getIssueCountForProject(300L, any(), null) } returns 0L

        val result = makeDashboardService().createProject(userId, CreateProjectRequest(name = SPECIAL_CHARS_NAME))
        assertEquals(SPECIAL_CHARS_SLUG, result.slug)
    }

    // ===================== addProjectTarget =====================
    // Pure mock tests — no H2 or ClickHouse required.

    @Test
    fun `addProjectTarget adds new platform key`() {
        every { mockProjectRepo.findProjectKeyByTarget(1L, "web") } returns false
        every { mockProjectRepo.createProjectKey(1L, any(), any(), "web") } just runs

        val result = makeDashboardService().addProjectTarget(1L, "web")
        assertEquals("web", result.platformTarget)
        assertTrue(result.dsn.isNotEmpty())
    }

    @Test
    fun `addProjectTarget throws when target already exists`() {
        every { mockProjectRepo.findProjectKeyByTarget(1L, "android") } returns true

        val ex = assertFailsWith<IllegalStateException> {
            makeDashboardService().addProjectTarget(1L, "android")
        }
        assertTrue(ex.message?.contains("android") == true)
    }

    @Test
    fun `addProjectTarget null target does not conflict with android target`() {
        every { mockProjectRepo.findProjectKeyByTarget(2L, "android") } returns false
        every { mockProjectRepo.createProjectKey(2L, any(), any(), "android") } just runs

        val result = makeDashboardService().addProjectTarget(2L, "android")
        assertEquals("android", result.platformTarget)
    }

    // ===================== updateProject =====================
    // Pure mock tests — no H2 required. Delegation to repo is verified via mock verification.

    @Test
    fun `updateProject delegates to repository with correct arguments`() {
        val request = UpdateProjectRequest(name = "New Name")
        makeDashboardService().updateProject(1L, request)
        verify { mockProjectRepo.updateProject(1L, request) }
    }

    @Test
    fun `updateProject framework change delegates to repository`() {
        val request = UpdateProjectRequest(framework = "kotlin")
        makeDashboardService().updateProject(1L, request)
        verify { mockProjectRepo.updateProject(1L, request) }
    }

    // ===================== deleteProject =====================
    // Pure mock tests — no H2 required. Delegation to repo is verified via mock verification.

    @Test
    fun `deleteProject delegates to repository`() {
        makeDashboardService().deleteProject(1L)
        verify { mockProjectRepo.deleteProject(1L) }
    }

    @Test
    fun `deleteProject scopes deletion to given project id`() {
        makeDashboardService().deleteProject(99L)
        verify { mockProjectRepo.deleteProject(99L) }
    }

    // ===================== getProjects =====================
    // Pure mock tests — no H2 or ClickHouse required.

    @Test
    fun `getProjects returns projects with issue counts`() = runBlocking {
        every { mockProjectRepo.getOrganizationIdsForUser(1) } returns listOf(10)
        every { mockProjectRepo.getProjectsForOrganizations(listOf(10)) } returns listOf(
            ProjectRow(1L, PROJECT_NAME, PROJECT_SLUG, null, emptyList(), "http://k@host/1")
        )
        coEvery { mockProjectRepo.getIssueCountForProject(1L, any(), null) } returns 42L

        val projects = makeDashboardService().getProjects(1)

        assertEquals(1, projects.size)
        assertEquals(PROJECT_NAME, projects.first().name)
        assertEquals(42, projects.first().issueCount)
    }

    @Test
    fun `getProjects returns empty list when user has no orgs`() = runBlocking {
        every { mockProjectRepo.getOrganizationIdsForUser(1) } returns emptyList()
        assertTrue(makeDashboardService().getProjects(1).isEmpty())
    }

    @Test
    fun `getProjects returns only projects for user org`() = runBlocking {
        every { mockProjectRepo.getOrganizationIdsForUser(1) } returns listOf(10)
        every { mockProjectRepo.getProjectsForOrganizations(listOf(10)) } returns listOf(
            ProjectRow(1L, "Org1 Project", "org1-project", null, emptyList(), "")
        )
        coEvery { mockProjectRepo.getIssueCountForProject(any(), any(), null) } returns 0L

        val projects = makeDashboardService().getProjects(1)
        assertEquals(1, projects.size)
        assertEquals("Org1 Project", projects.first().name)
    }

    // ===================== getProject =====================
    // Pure mock tests — no H2 or ClickHouse required.

    @Test
    fun `getProject returns project detail with issue count`() = runBlocking {
        val key = ProjectKeyResponse(null, "http://testpubkey123@host/1")
        every { mockProjectRepo.getProjectById(1L) } returns
            ProjectRow(1L, "Detail App", "detail-app", "react", listOf(key), key.dsn)
        coEvery { mockProjectRepo.getIssueCountForProject(1L, any(), null) } returns 7L

        val project = makeDashboardService().getProject(1L)

        assertNotNull(project)
        assertEquals("Detail App", project.name)
        assertEquals("react", project.framework)
        assertEquals(7, project.issueCount)
        assertTrue(project.dsn.contains("testpubkey123"))
    }

    @Test
    fun `getProject returns null for non-existent project`() = runBlocking {
        every { mockProjectRepo.getProjectById(999999L) } returns null
        assertNull(makeDashboardService().getProject(999999L))
    }
}
