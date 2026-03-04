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

package com.moneat.datadog.services

import com.moneat.datadog.models.DdApiKeys
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatadogServiceTest {

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:test_dd_service;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver"
        )
        transaction {
            exec("DROP ALL OBJECTS")
            SchemaUtils.create(DdApiKeys)
        }
    }

    @Test
    fun `createApiKey generates key and stores hash`() {
        val result = DatadogService.createApiKey(
            organizationId = 1,
            name = "Test Key",
            userId = 1
        )

        assertNotNull(result.key)
        assertTrue(result.key.isNotBlank())
        assertEquals("Test Key", result.name)
        assertNotNull(result.id)
    }

    @Test
    fun `createApiKey stores hash not raw key`() {
        val result = DatadogService.createApiKey(
            organizationId = 1,
            name = "Hash Key",
            userId = 1
        )

        val stored = transaction {
            DdApiKeys.selectAll()
                .where { DdApiKeys.id eq result.id }
                .firstOrNull()
        }

        assertNotNull(stored)
        assertEquals("Hash Key", stored[DdApiKeys.name])
        assertTrue(stored[DdApiKeys.keyHash] != result.key)
    }

    @Test
    fun `validateApiKey returns orgId for valid key`() {
        val created = DatadogService.createApiKey(
            organizationId = 42,
            name = "Valid Key",
            userId = 1
        )

        val orgId = DatadogService.validateApiKey(created.key)

        assertNotNull(orgId)
        assertEquals(42, orgId)
    }

    @Test
    fun `validateApiKey returns null for invalid key`() {
        val orgId = DatadogService.validateApiKey("nonexistent-key")
        assertNull(orgId)
    }

    @Test
    fun `validateApiKey returns null for empty key`() {
        val orgId = DatadogService.validateApiKey("")
        assertNull(orgId)
    }

    @Test
    fun `validateApiKey updates last_used_at`() {
        val created = DatadogService.createApiKey(
            organizationId = 1,
            name = "Usage Key",
            userId = 1
        )

        val beforeValidation = transaction {
            DdApiKeys.selectAll()
                .where { DdApiKeys.id eq created.id }
                .first()[DdApiKeys.lastUsedAt]
        }
        assertNull(beforeValidation)

        DatadogService.validateApiKey(created.key)

        val afterValidation = transaction {
            DdApiKeys.selectAll()
                .where { DdApiKeys.id eq created.id }
                .first()[DdApiKeys.lastUsedAt]
        }
        assertNotNull(afterValidation)
    }

    @Test
    fun `listApiKeys returns active keys for org`() {
        DatadogService.createApiKey(1, "Key 1", 1)
        DatadogService.createApiKey(1, "Key 2", 1)

        val keys = DatadogService.listApiKeys(1)
        assertEquals(2, keys.size)
    }

    @Test
    fun `listApiKeys does not return other org keys`() {
        DatadogService.createApiKey(1, "Org 1 Key", 1)
        DatadogService.createApiKey(2, "Org 2 Key", 1)

        val keys = DatadogService.listApiKeys(1)
        assertEquals(1, keys.size)
        assertEquals("Org 1 Key", keys[0].name)
    }

    @Test
    fun `deleteApiKey removes the key`() {
        val created = DatadogService.createApiKey(1, "Delete Me", 1)

        val deleted = DatadogService.deleteApiKey(created.id, 1)
        assertTrue(deleted)

        val orgId = DatadogService.validateApiKey(created.key)
        assertNull(orgId)
    }

    @Test
    fun `deleteApiKey returns false for wrong org`() {
        val created = DatadogService.createApiKey(1, "My Key", 1)
        val deleted = DatadogService.deleteApiKey(created.id, 2)
        assertTrue(!deleted)
    }

    @Test
    fun `revokeApiKey soft-deletes the key`() {
        val created = DatadogService.createApiKey(1, "Revoke Me", 1)

        val revoked = DatadogService.revokeApiKey(created.id, 1)
        assertTrue(revoked)

        val orgId = DatadogService.validateApiKey(created.key)
        assertNull(orgId)
    }

    @Test
    fun `createApiKey with projectId stores it`() {
        val result = DatadogService.createApiKey(
            organizationId = 1,
            name = "Project Key",
            userId = 1,
            projectId = 10
        )

        val stored = transaction {
            DdApiKeys.selectAll()
                .where { DdApiKeys.id eq result.id }
                .first()
        }
        assertEquals(10, stored[DdApiKeys.projectId])
    }

    @Test
    fun `keyPrefix matches beginning of key`() {
        val result = DatadogService.createApiKey(
            organizationId = 1,
            name = "Prefix Key",
            userId = 1
        )

        assertTrue(result.key.startsWith(result.keyPrefix))
    }
}
