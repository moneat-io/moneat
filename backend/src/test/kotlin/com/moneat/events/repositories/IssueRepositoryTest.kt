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

package com.moneat.events.repositories

import com.moneat.events.services.DashboardQueryHelper
import com.moneat.shared.models.IssueStatuses
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IssueRepositoryTest {

    companion object {
        private const val ISSUE_ID = "issue-x"
    }

    private var db: Database? = null
    private lateinit var repository: IssueRepository
    private var projectId: Long = 0L

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_issue_repo;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Organizations, Projects, IssueStatuses)
        repository = IssueRepositoryImpl(DashboardQueryHelper())
        projectId = transaction {
            val orgId = Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
            Projects.insert {
                it[organization_id] = orgId
                it[name] = "Project"
                it[slug] = "project"
            } get Projects.id
        }
    }

    // ──── getIssueStatusOverrides ────

    @Test
    fun `getIssueStatusOverrides returns empty map when no overrides exist`() {
        val overrides = repository.getIssueStatusOverrides(projectId)
        assertTrue(overrides.isEmpty())
    }

    @Test
    fun `getIssueStatusOverrides returns status for upserted issues`() {
        repository.upsertIssueStatus("issue-A", projectId, "resolved")
        repository.upsertIssueStatus("issue-B", projectId, "ignored")
        val overrides = repository.getIssueStatusOverrides(projectId)
        assertEquals(2, overrides.size)
        assertEquals("resolved", overrides["issue-A"])
        assertEquals("ignored", overrides["issue-B"])
    }

    @Test
    fun `getIssueStatusOverrides returns empty map for non-positive projectId`() {
        // projectId <= 0 is a sentinel for demo projects; should return empty without hitting DB
        val overrides = repository.getIssueStatusOverrides(-1L)
        assertTrue(overrides.isEmpty())
    }

    // ──── getIssueStatus ────

    @Test
    fun `getIssueStatus returns null when issue has no status row`() {
        assertNull(repository.getIssueStatus("no-issue", projectId))
    }

    @Test
    fun `getIssueStatus returns status after upsert`() {
        repository.upsertIssueStatus("issue-1", projectId, "resolved")
        assertEquals("resolved", repository.getIssueStatus("issue-1", projectId))
    }

    // ──── upsertIssueStatus ────

    @Test
    fun `upsertIssueStatus inserts new status row`() {
        repository.upsertIssueStatus("new-issue", projectId, "unresolved")
        assertEquals("unresolved", repository.getIssueStatus("new-issue", projectId))
    }

    @Test
    fun `upsertIssueStatus updates existing status row`() {
        repository.upsertIssueStatus(ISSUE_ID, projectId, "unresolved")
        repository.upsertIssueStatus(ISSUE_ID, projectId, "resolved")
        assertEquals("resolved", repository.getIssueStatus(ISSUE_ID, projectId))
    }

    @Test
    fun `upsertIssueStatus is isolated per project`() {
        val otherProjectId = transaction {
            val orgId = Organizations.insert {
                it[name] = "Org2"
                it[slug] = "org2"
            } get Organizations.id
            Projects.insert {
                it[organization_id] = orgId
                it[name] = "Other"
                it[slug] = "other"
            } get Projects.id
        }
        repository.upsertIssueStatus("shared-issue", projectId, "resolved")
        assertNull(repository.getIssueStatus("shared-issue", otherProjectId))
    }

    // ──── getProjectName ────

    @Test
    fun `getProjectName returns project name for known projectId`() {
        val name = repository.getProjectName(projectId)
        assertNotNull(name)
        assertEquals("Project", name)
    }

    @Test
    fun `getProjectName returns Unknown for non-existent project`() {
        val name = repository.getProjectName(99999L)
        assertEquals("Unknown", name)
    }
}
