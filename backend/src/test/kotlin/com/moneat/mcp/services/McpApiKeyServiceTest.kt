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

package com.moneat.mcp.services

import com.moneat.shared.models.McpApiKeys
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlin.time.Clock

class McpApiKeyServiceTest {
    companion object {
        private var db: Database? = null
    }

    private val service = McpApiKeyService()
    private var organizationId = 0
    private var userId = 0

    @BeforeEach
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_mcp_api_key_service;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, McpApiKeys)
        transaction {
            organizationId = Organizations.insert {
                it[name] = "MCP Org"
                it[slug] = "mcp-org"
            }[Organizations.id]
            userId = Users.insert {
                it[email] = "mcp-key@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            }[Users.id]
        }
    }

    @Test
    fun `createKey normalizes selected tools and does not expose stored hash`() {
        val created = service.createKey(
            organizationId = organizationId,
            userId = userId,
            name = "  Production MCP  ",
            enabledTools = listOf("search_logs", "get_issue", "search_logs"),
            enabledResources = listOf("moneat://project", "moneat://org", "moneat://org"),
            expiresInDays = 7,
        )

        assertTrue(created.key.startsWith("mmcp_"))
        assertEquals(created.key.take(12), created.keyPrefix)
        assertEquals("Production MCP", created.name)
        assertEquals(listOf("get_issue", "search_logs"), created.enabledTools)
        assertEquals(listOf("moneat://org", "moneat://project"), created.enabledResources)
        assertNotNull(created.expiresAt)

        val row = transaction {
            McpApiKeys
                .selectAll()
                .where { McpApiKeys.resource_id eq Uuid.parse(created.id) }
                .single()
        }
        assertNotEquals(created.key, row[McpApiKeys.key_hash])
        assertEquals(created.keyPrefix, row[McpApiKeys.key_prefix])
    }

    @Test
    fun `listKeys returns only active keys for the requested organization`() {
        val otherOrganizationId = transaction {
            Organizations.insert {
                it[name] = "Other MCP Org"
                it[slug] = "other-mcp-org"
            }[Organizations.id]
        }
        val active = service.createKey(organizationId, userId, "active", listOf("search_logs"), emptyList())
        val revoked = service.createKey(organizationId, userId, "revoked", listOf("get_issue"), emptyList())
        service.createKey(otherOrganizationId, userId, "other-org", listOf("list_projects"), emptyList())

        assertTrue(service.revokeKey(organizationId, revoked.id))

        val keys = service.listKeys(organizationId)
        assertEquals(1, keys.size)
        assertEquals(active.id, keys.single().id)
        assertEquals("active", keys.single().name)
        assertNull(keys.single().lastUsedAt)
    }

    @Test
    fun `validateKey returns context and records last usage for active key`() {
        val created = service.createKey(
            organizationId = organizationId,
            userId = userId,
            name = "validate",
            enabledTools = listOf("search_logs", "get_issue"),
            enabledResources = listOf("moneat://org"),
        )

        val result = service.validateKey(created.key)

        assertNotNull(result)
        assertEquals(internalKeyId(created.id), result.keyId)
        assertEquals(organizationId, result.organizationId)
        assertEquals(userId, result.userId)
        assertEquals(setOf("get_issue", "search_logs"), result.enabledTools)
        assertEquals(setOf("moneat://org"), result.enabledResources)
        assertNotNull(service.listKeys(organizationId).single().lastUsedAt)
    }

    @Test
    fun `validateKey returns null for invalid revoked and expired keys`() {
        val revoked = service.createKey(organizationId, userId, "revoked", listOf("search_logs"), emptyList())
        val expired = service.createKey(organizationId, userId, "expired", listOf("get_issue"), emptyList())
        service.revokeKey(organizationId, revoked.id)
        transaction {
            McpApiKeys.update({ McpApiKeys.resource_id eq Uuid.parse(expired.id) }) {
                it[expires_at] = Clock.System.now().minus(86_400, DateTimeUnit.SECOND)
            }
        }

        assertNull(service.validateKey("invalid-key"))
        assertNull(service.validateKey("mmcp_short"))
        assertNull(service.validateKey(revoked.key))
        assertNull(service.validateKey(expired.key))
    }

    @Test
    fun `updateKey changes active key fields and rejects invalid requests`() {
        val created = service.createKey(organizationId, userId, "original", listOf("search_logs"), emptyList())

        val updated = service.updateKey(
            organizationId = organizationId,
            keyId = created.id,
            update = McpApiKeyUpdate(
                name = "  renamed  ",
                enabledTools = listOf("get_issue", "search_logs", "get_issue"),
                enabledResources = listOf("moneat://org", "moneat://org"),
                expiresInDays = 30,
            ),
        )

        assertTrue(updated)
        val key = service.listKeys(organizationId).single()
        assertEquals("renamed", key.name)
        assertEquals(listOf("get_issue", "search_logs"), key.enabledTools)
        assertEquals(listOf("moneat://org"), key.enabledResources)
        assertNotNull(key.expiresAt)

        assertFalse(
            service.updateKey(
                organizationId = organizationId + 1,
                keyId = created.id,
                update = McpApiKeyUpdate(
                    name = "wrong org",
                    enabledTools = null,
                    enabledResources = null,
                ),
            ),
        )
        assertFalse(
            service.updateKey(
                organizationId = organizationId,
                keyId = "99999999-9999-9999-9999-999999999999",
                update = McpApiKeyUpdate(
                    name = "missing",
                    enabledTools = null,
                    enabledResources = null,
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            service.updateKey(organizationId, created.id, McpApiKeyUpdate("   ", null, null))
        }
        assertFailsWith<IllegalArgumentException> {
            service.updateKey(organizationId, created.id, McpApiKeyUpdate("a".repeat(256), null, null))
        }.also {
            assertEquals("Name must be at most 255 characters", it.message)
        }
        assertFailsWith<IllegalArgumentException> {
            service.updateKey(organizationId, created.id, McpApiKeyUpdate(null, null, null, expiresInDays = 0))
        }
        assertFailsWith<IllegalArgumentException> {
            service.createKey(organizationId, userId, "   ", listOf("search_logs"), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            service.createKey(organizationId, userId, "a".repeat(256), listOf("search_logs"), emptyList())
        }
    }

    private fun internalKeyId(resourceId: String): Int =
        transaction {
            McpApiKeys
                .selectAll()
                .where { McpApiKeys.resource_id eq Uuid.parse(resourceId) }
                .single()[McpApiKeys.id]
        }
}
