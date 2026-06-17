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

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourceOwnershipRepositoryTest {
    private companion object {
        private var db: Database? = null
        private const val ORG_ID = 7
        private const val OTHER_ORG_ID = 8
        private const val RESOURCE_ID = "host:7:42"
        private const val TEAM_ID = 101
        private const val OTHER_TEAM_ID = 202
        private const val REPLACEMENT_TEAM_ID = 303
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
        transaction {
            exec("DROP ALL OBJECTS")
            exec(
                """
                CREATE TABLE resource_ownership (
                    id INTEGER AUTO_INCREMENT PRIMARY KEY,
                    organization_id INTEGER NOT NULL,
                    resource_id VARCHAR(512) NOT NULL,
                    team_id INTEGER NOT NULL,
                    updated_by VARCHAR(320) NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """.trimIndent(),
            )
            exec(
                "CREATE UNIQUE INDEX idx_resource_ownership_org_resource " +
                    "ON resource_ownership (organization_id, resource_id)",
            )
            exec(
                """
                CREATE TABLE organization_teams (
                    id INTEGER PRIMARY KEY,
                    resource_id UUID NOT NULL,
                    organization_id INTEGER NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    slug VARCHAR(120) NOT NULL,
                    description TEXT,
                    slack_channel VARCHAR(200),
                    repository VARCHAR(300),
                    on_call_schedule_id INTEGER,
                    escalation_policy_id INTEGER,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """.trimIndent(),
            )
            insertTeam(TEAM_ID, ORG_ID, "platform")
            insertTeam(REPLACEMENT_TEAM_ID, ORG_ID, "platform-core")
            insertTeam(OTHER_TEAM_ID, OTHER_ORG_ID, "payments")
        }
    }

    @Test
    fun `upsert inserts a claim and list returns it for the owning organization`() {
        repository.upsert(ORG_ID, RESOURCE_ID, TEAM_ID, updatedBy = "owner@moneat.test")

        assertEquals(mapOf(RESOURCE_ID to TEAM_ID), repository.listByOrganization(ORG_ID))
        assertTrue(repository.listByOrganization(OTHER_ORG_ID).isEmpty())
    }

    @Test
    fun `upsert replaces the same organization resource without touching other organizations`() {
        repository.upsert(ORG_ID, RESOURCE_ID, TEAM_ID, updatedBy = "first@moneat.test")
        repository.upsert(OTHER_ORG_ID, RESOURCE_ID, OTHER_TEAM_ID, updatedBy = "other@moneat.test")
        repository.upsert(ORG_ID, RESOURCE_ID, REPLACEMENT_TEAM_ID, updatedBy = "second@moneat.test")

        assertEquals(mapOf(RESOURCE_ID to REPLACEMENT_TEAM_ID), repository.listByOrganization(ORG_ID))
        assertEquals(mapOf(RESOURCE_ID to OTHER_TEAM_ID), repository.listByOrganization(OTHER_ORG_ID))
    }

    @Test
    fun `upsert rejects a team from another organization`() {
        assertFailsWith<IllegalArgumentException> {
            repository.upsert(ORG_ID, RESOURCE_ID, OTHER_TEAM_ID, updatedBy = "owner@moneat.test")
        }

        assertTrue(repository.listByOrganization(ORG_ID).isEmpty())
    }

    @Test
    fun `delete removes only the scoped organization claim`() {
        repository.upsert(ORG_ID, RESOURCE_ID, TEAM_ID, updatedBy = "owner@moneat.test")
        repository.upsert(OTHER_ORG_ID, RESOURCE_ID, OTHER_TEAM_ID, updatedBy = "other@moneat.test")

        assertTrue(repository.delete(ORG_ID, RESOURCE_ID))
        assertFalse(repository.delete(ORG_ID, RESOURCE_ID))
        assertTrue(repository.listByOrganization(ORG_ID).isEmpty())
        assertEquals(mapOf(RESOURCE_ID to OTHER_TEAM_ID), repository.listByOrganization(OTHER_ORG_ID))
    }

    @Test
    fun `noop repository ignores claims`() {
        NoopResourceOwnershipRepository.upsert(ORG_ID, RESOURCE_ID, TEAM_ID, updatedBy = "owner@moneat.test")

        assertTrue(NoopResourceOwnershipRepository.listByOrganization(ORG_ID).isEmpty())
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.insertTeam(
        teamId: Int,
        organizationId: Int,
        slug: String,
    ) {
        exec(
            """
            INSERT INTO organization_teams (
                id,
                resource_id,
                organization_id,
                name,
                slug,
                created_at,
                updated_at
            ) VALUES (
                $teamId,
                '00000000-0000-4000-8000-${teamId.toString().padStart(12, '0')}',
                $organizationId,
                '$slug',
                '$slug',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP
            )
            """.trimIndent(),
        )
    }
}
