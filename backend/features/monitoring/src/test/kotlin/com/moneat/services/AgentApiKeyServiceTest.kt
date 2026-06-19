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

import com.moneat.monitor.services.AgentApiKeyService
import com.moneat.shared.models.AgentApiKeys
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AgentApiKeyServiceTest {
    private val agentApiKeyService = AgentApiKeyService()

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_agent_api_key_service;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, AgentApiKeys)
    }

    @Test
    fun `createKey returns key with prefix`() {
        val orgId = seedOrg()
        val userId = seedUser()

        val result = agentApiKeyService.createKey(orgId, "test-key", userId)

        assertTrue(result.key.startsWith("magt_"))
        assertEquals("test-key", result.name)
        assertEquals(result.id, Uuid.parse(result.id).toString())
        assertEquals(result.key.take(DISPLAY_PREFIX_LENGTH), result.keyPrefix)
    }

    @Test
    fun `validateKey returns orgId for valid key`() {
        val orgId = seedOrg()
        val userId = seedUser()
        val created = agentApiKeyService.createKey(orgId, "validate-key", userId)

        val result = agentApiKeyService.validateKey(created.key)

        assertEquals(orgId, result)
    }

    @Test
    fun `validateKey returns null for invalid key`() {
        assertNull(agentApiKeyService.validateKey("invalid-key"))
    }

    @Test
    fun `validateKey returns null for key without prefix`() {
        assertNull(agentApiKeyService.validateKey("short"))
    }

    @Test
    fun `validateKey returns null for deleted key`() {
        val orgId = seedOrg()
        val userId = seedUser()
        val created = agentApiKeyService.createKey(orgId, "to-delete", userId)

        agentApiKeyService.deleteKey(orgId, created.id)

        assertNull(agentApiKeyService.validateKey(created.key))
    }

    @Test
    fun `listKeys returns active keys`() {
        val orgId = seedOrg()
        val userId = seedUser()

        agentApiKeyService.createKey(orgId, "key-1", userId)
        agentApiKeyService.createKey(orgId, "key-2", userId)

        val keys = agentApiKeyService.listKeys(orgId)

        assertEquals(2, keys.size)
        assertTrue(keys.any { it.name == "key-1" })
        assertTrue(keys.any { it.name == "key-2" })
    }

    @Test
    fun `listKeys does not return deleted keys`() {
        val orgId = seedOrg()
        val userId = seedUser()

        agentApiKeyService.createKey(orgId, "active", userId)
        val key2 = agentApiKeyService.createKey(orgId, "deleted", userId)
        agentApiKeyService.deleteKey(orgId, key2.id)

        val keys = agentApiKeyService.listKeys(orgId)

        assertEquals(1, keys.size)
        assertEquals("active", keys[0].name)
    }

    @Test
    fun `listKeys only returns keys for given org`() {
        val org1 = seedOrg("Org A")
        val org2 = seedOrg("Org B")
        val userId = seedUser()

        agentApiKeyService.createKey(org1, ORG1_KEY, userId)
        agentApiKeyService.createKey(org2, "org2-key", userId)

        assertEquals(1, agentApiKeyService.listKeys(org1).size)
        assertEquals(ORG1_KEY, agentApiKeyService.listKeys(org1)[0].name)
    }

    @Test
    fun `deleteKey returns true for existing key`() {
        val orgId = seedOrg()
        val userId = seedUser()
        val created = agentApiKeyService.createKey(orgId, "to-delete", userId)

        assertTrue(agentApiKeyService.deleteKey(orgId, created.id))
    }

    @Test
    fun `deleteKey returns false for non-existent key`() {
        val orgId = seedOrg()

        assertFalse(agentApiKeyService.deleteKey(orgId, "99999999-9999-9999-9999-999999999999"))
    }

    @Test
    fun `deleteKey cannot delete key from other org`() {
        val org1 = seedOrg("Org 1")
        val org2 = seedOrg("Org 2")
        val userId = seedUser()
        val created = agentApiKeyService.createKey(org1, ORG1_KEY, userId)

        assertFalse(agentApiKeyService.deleteKey(org2, created.id))
        assertEquals(org1, agentApiKeyService.validateKey(created.key))
    }

    private fun seedOrg(name: String = "Key Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedUser(email: String = "test@moneat.io"): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hash"
            } get Users.id
        }

    private companion object {
        private var db: Database? = null
        private const val DISPLAY_PREFIX_LENGTH = 12
        private const val ORG1_KEY = "org1-key"
    }
}
