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

package com.moneat.testsupport

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Shared H2 setup for service tests that need [Organizations] / [Projects] rows
 * so Exposed lookups (e.g. organization_id for a project) succeed.
 */
object OrgProjectTestFixtures {

    /**
     * Connects to a named in-memory H2 database (reusing [cached] when non-null),
     * resets org/project tables, and seeds org id=1 and project id=1.
     */
    fun connectResetAndSeedDefaultOrgProject(cached: Database?, memDbName: String): Database {
        val db =
            cached
                ?: Database.connect(
                    url = "jdbc:h2:mem:$memDbName;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver",
                )
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Organizations, Projects)
        transaction {
            Organizations.insert {
                it[id] = 1
                it[name] = "Test Org"
                it[slug] = "test-org"
            }
            Projects.insert {
                it[id] = 1
                it[organization_id] = 1
                it[name] = "Test Project"
                it[slug] = "test-project"
            }
        }
        return db
    }
}
