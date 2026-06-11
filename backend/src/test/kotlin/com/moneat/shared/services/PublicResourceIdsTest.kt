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
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val ORG_RESOURCE_ID = Uuid.parse("11111111-1111-4111-8111-111111111111")
private val USER_RESOURCE_ID = Uuid.parse("22222222-2222-4222-8222-222222222222")
private val SCOPED_RESOURCE_ID = Uuid.parse("33333333-3333-4333-8333-333333333333")
private val GLOBAL_RESOURCE_ID = Uuid.parse("44444444-4444-4444-8444-444444444444")
private val COLUMN_RESOURCE_ID = Uuid.parse("55555555-5555-4555-8555-555555555555")
private val MISSING_RESOURCE_ID = Uuid.parse("66666666-6666-4666-8666-666666666666")

class PublicResourceIdsTest {
    companion object {
        private var db: Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        db = db ?: Database.connect(
            url = "jdbc:h2:mem:moneat_public_resource_ids;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Organizations,
            Users,
            ResourceIdRows,
            ResourceIdColumnRows,
        )
    }

    @Test
    fun `organization and user helpers return public resource ids`() {
        val seeded = seedIdentity()

        assertEquals(ORG_RESOURCE_ID.toString(), organizationResourceId(seeded.organizationId))
        assertEquals(ORG_RESOURCE_ID.toString(), organizationResourceId(seeded.organizationId.toLong()))
        assertEquals(
            mapOf(seeded.organizationId to ORG_RESOURCE_ID.toString()),
            organizationResourceIds(setOf(1, 1)),
        )
        assertEquals(USER_RESOURCE_ID.toString(), userResourceId(seeded.userId))
        assertEquals(USER_RESOURCE_ID.toString(), userResourceId(seeded.userId.toLong()))
        assertEquals(USER_RESOURCE_ID.toString(), userResourceIdOrNull(seeded.userId))
        assertEquals(
            mapOf(seeded.userId to USER_RESOURCE_ID.toString()),
            userResourceIds(listOf(seeded.userId)),
        )
        assertEquals(
            USER_RESOURCE_ID.toString(),
            mapOf(seeded.userId to USER_RESOURCE_ID.toString()).requireResourceId(1, "user"),
        )
    }

    @Test
    fun `resource id helpers handle empty and missing values`() {
        assertTrue(organizationResourceIds(emptyList()).isEmpty())
        assertTrue(userResourceIds(emptyList()).isEmpty())
        assertNull(userResourceIdOrNull(null))

        val missingOrg = assertFailsWith<IllegalStateException> { organizationResourceId(99) }
        val missingUser = assertFailsWith<IllegalStateException> { userResourceId(99) }
        val missingMapValue = assertFailsWith<IllegalStateException> {
            emptyMap<Int, String>().requireResourceId(99, "resource")
        }

        assertTrue(missingOrg.message.orEmpty().contains("organization 99"))
        assertTrue(missingUser.message.orEmpty().contains("user 99"))
        assertTrue(missingMapValue.message.orEmpty().contains("resource 99"))
    }

    @Test
    fun `resource id resolvers scope numeric ids`() {
        val scopedId = transaction {
            val scopedId = ResourceIdRows.insertAndGetId {
                it[resourceId] = SCOPED_RESOURCE_ID
                it[scopeId] = 7
            }.value
            ResourceIdRows.insertAndGetId {
                it[resourceId] = GLOBAL_RESOURCE_ID
                it[scopeId] = 8
            }
            ResourceIdColumnRows.insert {
                it[id] = 501
                it[resourceId] = COLUMN_RESOURCE_ID
                it[scopeId] = 7
            }
            scopedId
        }

        assertEquals(
            scopedId,
            resolveScopedIntResourceId(
                ResourceIdRows,
                ResourceIdRows.resourceId,
                ResourceIdRows.scopeId,
                7,
                SCOPED_RESOURCE_ID,
            ),
        )
        assertNull(
            resolveScopedIntResourceId(
                ResourceIdRows,
                ResourceIdRows.resourceId,
                ResourceIdRows.scopeId,
                8,
                SCOPED_RESOURCE_ID,
            ),
        )
        assertEquals(
            scopedId,
            resolveGlobalIntResourceId(
                ResourceIdRows,
                ResourceIdRows.resourceId,
                SCOPED_RESOURCE_ID,
            ),
        )
        assertNull(
            resolveGlobalIntResourceId(
                ResourceIdRows,
                ResourceIdRows.resourceId,
                MISSING_RESOURCE_ID,
            ),
        )

        transaction {
            val table = ScopedIntColumnResourceTable(
                table = ResourceIdColumnRows,
                idColumn = ResourceIdColumnRows.id,
                resourceIdColumn = ResourceIdColumnRows.resourceId,
                scopeColumn = ResourceIdColumnRows.scopeId,
            )
            assertEquals(501, resolveScopedIntColumnResourceId(table, 7, COLUMN_RESOURCE_ID))
            assertNull(resolveScopedIntColumnResourceId(table, 8, COLUMN_RESOURCE_ID))
        }
    }

    private fun seedIdentity(): IdentityFixture =
        transaction {
            val organizationId = Organizations.insert {
                it[id] = 1
                it[resource_id] = ORG_RESOURCE_ID
                it[name] = "Public Resource Org"
                it[slug] = "public-resource-org"
            } get Organizations.id
            val userId = Users.insert {
                it[id] = 1
                it[resource_id] = USER_RESOURCE_ID
                it[email] = "public-resource@test.com"
                it[password_hash] = "password-hash"
                it[name] = "Public Resource User"
            } get Users.id

            IdentityFixture(organizationId = organizationId, userId = userId)
        }

    private data class IdentityFixture(
        val organizationId: Int,
        val userId: Int,
    )
}

private object ResourceIdRows : IntIdTable("resource_id_rows") {
    val resourceId = uuid("resource_id")
    val scopeId = integer("scope_id")
}

private object ResourceIdColumnRows : Table("resource_id_column_rows") {
    val id = integer("id")
    val resourceId = uuid("resource_id")
    val scopeId = integer("scope_id")
    override val primaryKey = PrimaryKey(id)
}
