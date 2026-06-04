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

@file:Suppress("UNNECESSARY_NOT_NULL_ASSERTION")

package com.moneat.events.repositories

import com.moneat.events.models.UpdateProjectRequest
import com.moneat.events.services.DashboardQueryHelper
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OtelServiceProjectMappings
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.ProjectKeys
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class ProjectRepositoryTest {

    private var db: Database? = null
    private lateinit var repository: ProjectRepository

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_project_repo;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            Projects,
            ProjectKeys,
            OtelServiceProjectMappings
        )
        val queryHelper = DashboardQueryHelper()
        repository = ProjectRepositoryImpl(
            timestampRetentionClause = { col, days, _ ->
                queryHelper.timestampRetentionClause(col, days, null)
            }
        )
    }

    @Test
    fun getOrganizationIdsForUserReturnsDistinctOrgIds() {
        val userId = transaction {
            Users.insert {
                it[email] = "u@t.com"
                it[password_hash] = "h"
            } get Users.id
        }
        transaction {
            val org1 = Organizations.insert {
                it[name] = "O1"
                it[slug] = "o1"
            } get Organizations.id
            val org2 = Organizations.insert {
                it[name] = "O2"
                it[slug] = "o2"
            } get Organizations.id
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = org1
                it[role] = "owner"
            }
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = org2
                it[role] = "member"
            }
        }
        val orgIds = repository.getOrganizationIdsForUser(userId)
        assertEquals(2, orgIds.size)
        assertEquals(orgIds.toSet().size, orgIds.size) // distinct
    }

    @Test
    fun getProjectByIdReturnsProjectWithKeys() {
        val projectId = transaction {
            val orgId = Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
            val pid = Projects.insert {
                it[organization_id] = orgId
                it[name] = "Proj"
                it[slug] = "proj"
            } get Projects.id
            ProjectKeys.insert {
                it[project_id] = pid
                it[public_key] = "pk"
                it[secret_key] = "sk"
                it[is_active] = true
            }
            pid
        }
        val row = repository.getProjectById(projectId)
        assertNotNull(row)
        assertEquals("Proj", row!!.name)
        assertEquals(1, row.keys.size)
    }

    @Test
    fun getProjectByIdReturnsNullForMissingProject() {
        val row = repository.getProjectById(99999)
        assertNull(row)
    }

    @Test
    fun getServiceNameForProjectReturnsSlugAlias() {
        val projectId = transaction {
            val orgId = Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
            Projects.insert {
                it[organization_id] = orgId
                it[name] = "Checkout API"
                it[slug] = "checkout-api"
            } get Projects.id
        }

        assertEquals("checkout-api", repository.getServiceNameForProject(projectId))
    }

    @Test
    fun resolveServiceIdUsesMappedServiceNameWithinOrganization() {
        val now = Clock.System.now()
        val (orgId, mappedProjectId, fallbackProjectId) = transaction {
            val oid = Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
            val mapped = Projects.insert {
                it[organization_id] = oid
                it[name] = "Checkout"
                it[slug] = "checkout"
            } get Projects.id
            val fallback = Projects.insert {
                it[organization_id] = oid
                it[name] = "Checkout API"
                it[slug] = "checkout-api"
            } get Projects.id
            OtelServiceProjectMappings.insert {
                it[organization_id] = oid
                it[service_namespace] = ""
                it[service_name] = "checkout-api"
                it[project_id] = mapped
                it[created_at] = now
                it[updated_at] = now
            }
            Triple(oid, mapped, fallback)
        }

        val resolved = repository.resolveServiceId(orgId, "checkout-api")

        assertEquals(mappedProjectId, resolved)
        assertTrue(fallbackProjectId != resolved)
    }

    @Test
    fun resolveServiceIdFallsBackToProjectSlugWithinOrganization() {
        val (orgId, projectId) = transaction {
            val oid = Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
            val pid = Projects.insert {
                it[organization_id] = oid
                it[name] = "Checkout API"
                it[slug] = "checkout-api"
            } get Projects.id
            Pair(oid, pid)
        }

        assertEquals(projectId, repository.resolveServiceId(orgId, "checkout-api"))
        assertNull(repository.resolveServiceId(orgId + 1, "checkout-api"))
    }

    @Test
    fun resolveServiceIdFallsBackToProjectNameWithinOrganization() {
        val (orgId, projectId) = transaction {
            val oid = Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
            val pid = Projects.insert {
                it[organization_id] = oid
                it[name] = "Checkout API"
                it[slug] = "checkout"
            } get Projects.id
            Pair(oid, pid)
        }

        assertEquals(projectId, repository.resolveServiceId(orgId, "checkout api"))
        assertNull(repository.resolveServiceId(orgId, " "))
    }

    @Test
    fun resolveServiceIdTrimsNamespaceBeforeMappingLookup() {
        val now = Clock.System.now()
        val (orgId, projectId) = transaction {
            val oid = Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
            val pid = Projects.insert {
                it[organization_id] = oid
                it[name] = "Checkout"
                it[slug] = "checkout"
            } get Projects.id
            OtelServiceProjectMappings.insert {
                it[organization_id] = oid
                it[service_namespace] = "payments"
                it[service_name] = "checkout-api"
                it[project_id] = pid
                it[created_at] = now
                it[updated_at] = now
            }
            Pair(oid, pid)
        }

        assertEquals(projectId, repository.resolveServiceId(orgId, " checkout-api ", " payments "))
    }

    @Test
    fun resolveServiceNamesReturnsProjectSlugAliases() {
        val (checkoutId, workerId) = transaction {
            val orgId = Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
            val checkout = Projects.insert {
                it[organization_id] = orgId
                it[name] = "Checkout API"
                it[slug] = "checkout-api"
            } get Projects.id
            val worker = Projects.insert {
                it[organization_id] = orgId
                it[name] = "Worker"
                it[slug] = "worker"
            } get Projects.id
            Pair(checkout, worker)
        }

        assertEquals(emptyMap(), repository.resolveServiceNames(emptyList()))
        assertEquals(
            mapOf(
                checkoutId to "checkout-api",
                workerId to "worker"
            ),
            repository.resolveServiceNames(listOf(checkoutId, workerId))
        )
    }

    @Test
    fun getProjectCountForOrganizationReturnsCorrectCount() {
        val orgId = transaction {
            val oid = Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
            Projects.insert {
                it[organization_id] = oid
                it[name] = "P1"
                it[slug] = "p1"
            }
            Projects.insert {
                it[organization_id] = oid
                it[name] = "P2"
                it[slug] = "p2"
            }
            oid
        }
        assertEquals(2, repository.getProjectCountForOrganization(orgId))
    }

    @Test
    fun createProject() = runBlocking {
        val orgId = transaction {
            Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
        }
        val projectId = repository.createProject(orgId, "NewProj", "newproj", "kotlin")
        assertTrue(projectId > 0)
        val row = repository.getProjectById(projectId)
        assertNotNull(row)
        assertEquals("NewProj", row!!.name)
    }

    @Test
    fun getProjectsForOrganizationsReturnsAllProjects() = runBlocking {
        val (orgId1, orgId2) = transaction {
            val o1 = Organizations.insert {
                it[name] = "OrgA"
                it[slug] = "orga"
            } get Organizations.id
            val o2 = Organizations.insert {
                it[name] = "OrgB"
                it[slug] = "orgb"
            } get Organizations.id
            Pair(o1, o2)
        }
        val pid1 = repository.createProject(orgId1, "Proj1", "proj1", null)
        val pid2 = repository.createProject(orgId2, "Proj2", "proj2", null)
        val rows = repository.getProjectsForOrganizations(listOf(orgId1, orgId2))
        assertEquals(2, rows.size)
        val ids = rows.map { it.projectId }
        assertTrue(pid1 in ids)
        assertTrue(pid2 in ids)
    }

    @Test
    fun findProjectByNameOrSlugFindsExistingProject() = runBlocking {
        val orgId = transaction {
            Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
        }
        repository.createProject(orgId, "Unique", "unique", null)
        val byName = repository.findProjectByNameOrSlug(orgId, "Unique", "other")
        assertNotNull(byName)
        assertEquals("Unique", byName!!.name)
        val bySlug = repository.findProjectByNameOrSlug(orgId, "other", "unique")
        assertNotNull(bySlug)
        val missing = repository.findProjectByNameOrSlug(orgId, "Missing", "missing")
        assertNull(missing)
    }

    @Test
    fun createProjectKeyAndFindByTarget() = runBlocking {
        val orgId = transaction {
            Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
        }
        val projectId = repository.createProject(orgId, "Proj", "proj", null)
        repository.createProjectKey(projectId, "pub1", "sec1", "android")
        assertTrue(repository.findProjectKeyByTarget(projectId, "android"))
        assertFalse(repository.findProjectKeyByTarget(projectId, "ios"))
        // null target = no specific platform; key with "android" target doesn't match null
        repository.createProjectKey(projectId, "pub2", "sec2", null)
        assertTrue(repository.findProjectKeyByTarget(projectId, null))
    }

    @Test
    fun updateProjectChangesNameAndFramework() = runBlocking {
        val orgId = transaction {
            Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
        }
        val projectId = repository.createProject(orgId, "Old", "old", "java")
        repository.updateProject(
            projectId,
            UpdateProjectRequest(name = "New", framework = "kotlin")
        )
        val row = repository.getProjectById(projectId)
        assertNotNull(row)
        assertEquals("New", row!!.name)
    }

    @Test
    fun deleteProjectRemovesIt() = runBlocking {
        val orgId = transaction {
            Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
        }
        val projectId = repository.createProject(orgId, "ToDelete", "todelete", null)
        assertNotNull(repository.getProjectById(projectId))
        repository.deleteProject(projectId)
        assertNull(repository.getProjectById(projectId))
    }

    @Test
    fun searchProjectsByNameFiltersCorrectly() = runBlocking {
        val orgId = transaction {
            Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
        }
        repository.createProject(orgId, "Alpha Backend", "alpha-backend", null)
        repository.createProject(orgId, "Alpha Frontend", "alpha-frontend", null)
        repository.createProject(orgId, "Beta Service", "beta-service", null)
        val results = repository.searchProjectsByName(orgId, "%alpha%", 10)
        assertEquals(2, results.size)
        assertTrue(results.all { it.name.contains("Alpha", ignoreCase = true) })
    }
}
