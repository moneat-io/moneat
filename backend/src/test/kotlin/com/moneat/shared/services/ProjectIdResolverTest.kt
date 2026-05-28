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

package com.moneat.shared.services

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectIdResolverTest {
    @Test
    fun `resolve returns legacy numeric project IDs without lookup`() {
        var lookupCalls = 0
        val resolver = ProjectIdResolver(
            lookupProjectIdByResourceId = {
                lookupCalls++
                10L
            }
        )

        assertEquals(42L, resolver.resolve("42"))
        assertEquals(0, lookupCalls)
    }

    @Test
    fun `resolve looks up UUID resource IDs and caches results`() {
        val resourceId = Uuid.parse("018f4ce4-3f2a-7a67-a32b-0c1848f62b9d")
        var lookupCalls = 0
        val resolver = ProjectIdResolver(
            lookupProjectIdByResourceId = {
                lookupCalls++
                if (it == resourceId) 99L else null
            }
        )

        assertEquals(99L, resolver.resolve(resourceId.toString()))
        assertEquals(99L, resolver.resolve(resourceId.toString()))
        assertEquals(1, lookupCalls)
    }

    @Test
    fun `resolve returns null for invalid identifiers`() {
        val resolver = ProjectIdResolver(
            lookupProjectIdByResourceId = { error("lookup should not be called") }
        )

        assertNull(resolver.resolve(""))
        assertNull(resolver.resolve("   "))
        assertNull(resolver.resolve("not-a-project-id"))
    }

    @Test
    fun `resolve returns null when UUID resource ID is unknown`() {
        val resolver = ProjectIdResolver(
            lookupProjectIdByResourceId = { null }
        )

        assertNull(resolver.resolve("218f4ce4-3f2a-7a67-a32b-0c1848f62b9d"))
    }

    @Test
    fun `resolve refreshes expired cache entries`() {
        val resourceId = Uuid.parse("318f4ce4-3f2a-7a67-a32b-0c1848f62b9d")
        var now = 0L
        var projectId = 10L
        var lookupCalls = 0
        val resolver = ProjectIdResolver(
            nowMs = { now },
            lookupProjectIdByResourceId = {
                lookupCalls++
                projectId
            }
        )

        assertEquals(10L, resolver.resolve(resourceId.toString()))
        projectId = 11L
        now = CACHE_TTL_MS_FOR_TEST + 1

        assertEquals(11L, resolver.resolve(resourceId.toString()))
        assertEquals(2, lookupCalls)
    }

    @Test
    fun `resourceIdFor looks up and caches project resource IDs`() {
        val resourceId = Uuid.parse("118f4ce4-3f2a-7a67-a32b-0c1848f62b9d")
        var lookupCalls = 0
        val resolver = ProjectIdResolver(
            lookupResourceIdByProjectId = {
                lookupCalls++
                if (it == 17L) resourceId else null
            }
        )

        assertEquals(resourceId.toString(), resolver.resourceIdFor(17L))
        assertEquals(resourceId.toString(), resolver.resourceIdFor(17L))
        assertEquals(1, lookupCalls)
    }

    @Test
    fun `resourceIdFor returns null when project ID is unknown`() {
        val resolver = ProjectIdResolver(
            lookupResourceIdByProjectId = { null }
        )

        assertNull(resolver.resourceIdFor(404L))
    }

    @Test
    fun `resourceIdFor refreshes expired cache entries`() {
        val firstResourceId = Uuid.parse("418f4ce4-3f2a-7a67-a32b-0c1848f62b9d")
        val secondResourceId = Uuid.parse("518f4ce4-3f2a-7a67-a32b-0c1848f62b9d")
        var now = 0L
        var resourceId = firstResourceId
        var lookupCalls = 0
        val resolver = ProjectIdResolver(
            nowMs = { now },
            lookupResourceIdByProjectId = {
                lookupCalls++
                resourceId
            }
        )

        assertEquals(firstResourceId.toString(), resolver.resourceIdFor(17L))
        resourceId = secondResourceId
        now = CACHE_TTL_MS_FOR_TEST + 1

        assertEquals(secondResourceId.toString(), resolver.resourceIdFor(17L))
        assertEquals(2, lookupCalls)
    }

    @Test
    fun `resolve uses the default database lookup when no lookup is injected`() {
        resetProjectTables()
        val resourceId = Uuid.parse("618f4ce4-3f2a-7a67-a32b-0c1848f62b9d")
        val projectId = seedProject(resourceId)

        assertEquals(projectId, ProjectIdResolver().resolve(resourceId.toString()))
    }

    @Test
    fun `resourceIdFor uses the default database lookup when no lookup is injected`() {
        resetProjectTables()
        val resourceId = Uuid.parse("718f4ce4-3f2a-7a67-a32b-0c1848f62b9d")
        val projectId = seedProject(resourceId)

        assertEquals(resourceId.toString(), ProjectIdResolver().resourceIdFor(projectId))
    }

    private fun resetProjectTables() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_project_id_resolver;MODE=MYSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Organizations, Projects)
    }

    private fun seedProject(resourceId: Uuid): Long = transaction {
        val organizationId = Organizations.insert {
            it[name] = "Resolver Org"
            it[slug] = "resolver-org"
        } get Organizations.id
        Projects.insert {
            it[organization_id] = organizationId
            it[Projects.resource_id] = resourceId
            it[name] = "Resolver Project"
            it[slug] = "resolver-project"
            it[framework] = "otel"
        } get Projects.id
    }

    private companion object {
        private const val CACHE_TTL_MS_FOR_TEST = 30 * 60 * 1_000L
        private var db: Database? = null
    }
}
