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

package com.moneat.monitor.repositories

import com.moneat.monitor.models.CatalogOwner
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceOwnershipRepositoryTest {
    private companion object {
        private var db: Database? = null
        private const val ORG_ID = 7
        private const val OTHER_ORG_ID = 8
        private const val RESOURCE_ID = "host:7:42"
    }

    private val repository = ResourceOwnershipRepositoryImpl()

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_resource_ownership;MODE=MYSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(ResourceOwnership)
        transaction {
            exec(
                "CREATE UNIQUE INDEX idx_resource_ownership_org_resource " +
                    "ON resource_ownership (organization_id, resource_id)",
            )
        }
    }

    @Test
    fun `upsert inserts a claim and list returns it for the owning organization`() {
        val owner = CatalogOwner(team = "Payments", oncall = "Dana", slack = "#pay", repo = "moneat/pay")

        repository.upsert(ORG_ID, RESOURCE_ID, owner, updatedBy = "owner@moneat.test")

        assertEquals(mapOf(RESOURCE_ID to owner), repository.listByOrganization(ORG_ID))
        assertTrue(repository.listByOrganization(OTHER_ORG_ID).isEmpty())
    }

    @Test
    fun `upsert replaces the same organization resource without touching other organizations`() {
        val original = CatalogOwner(team = "Payments", oncall = "Dana", slack = "#pay", repo = "moneat/pay")
        val replacement = CatalogOwner(team = "Platform", oncall = "Riley", slack = "#plat", repo = "moneat/app")
        val otherOrgOwner = CatalogOwner(team = "Security", oncall = "", slack = "#sec", repo = "moneat/sec")

        repository.upsert(ORG_ID, RESOURCE_ID, original, updatedBy = "first@moneat.test")
        repository.upsert(OTHER_ORG_ID, RESOURCE_ID, otherOrgOwner, updatedBy = "other@moneat.test")
        repository.upsert(ORG_ID, RESOURCE_ID, replacement, updatedBy = "second@moneat.test")

        assertEquals(mapOf(RESOURCE_ID to replacement), repository.listByOrganization(ORG_ID))
        assertEquals(mapOf(RESOURCE_ID to otherOrgOwner), repository.listByOrganization(OTHER_ORG_ID))
    }

    @Test
    fun `noop repository ignores claims`() {
        val owner = CatalogOwner(team = "Payments", oncall = "Dana", slack = "#pay", repo = "moneat/pay")

        NoopResourceOwnershipRepository.upsert(ORG_ID, RESOURCE_ID, owner, updatedBy = "owner@moneat.test")

        assertTrue(NoopResourceOwnershipRepository.listByOrganization(ORG_ID).isEmpty())
    }
}
