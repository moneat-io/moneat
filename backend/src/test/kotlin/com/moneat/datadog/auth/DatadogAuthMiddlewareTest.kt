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

package com.moneat.datadog.auth

import com.moneat.datadog.models.DdApiKeys
import com.moneat.datadog.services.DatadogService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DatadogAuthMiddlewareTest {

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:test_dd_auth;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver"
        )
        transaction {
            exec("DROP ALL OBJECTS")
            SchemaUtils.create(DdApiKeys)
        }
        DatadogAuthMiddleware.clearCache()
    }

    @Test
    fun `validateApiKey caching returns same org after first call`() {
        val created = DatadogService.createApiKey(
            organizationId = 1,
            name = "Cache Key",
            userId = 1
        )

        // First validation hits DB
        val orgId1 = DatadogService.validateApiKey(created.key)
        assertNotNull(orgId1)
        assertEquals(1, orgId1)

        // Second should also work (verifies key is still valid)
        val orgId2 = DatadogService.validateApiKey(created.key)
        assertNotNull(orgId2)
        assertEquals(1, orgId2)
    }

    @Test
    fun `validateApiKey returns null for revoked key`() {
        val created = DatadogService.createApiKey(
            organizationId = 1,
            name = "Revoke Cache",
            userId = 1
        )

        val orgId1 = DatadogService.validateApiKey(created.key)
        assertNotNull(orgId1)

        DatadogService.revokeApiKey(created.id, 1)

        // After clearing cache, revoked key should fail
        DatadogAuthMiddleware.clearCache()
        val orgId2 = DatadogService.validateApiKey(created.key)
        assertNull(orgId2)
    }

    @Test
    fun `clearCache empties the cache`() {
        val created = DatadogService.createApiKey(
            organizationId = 1,
            name = "Clear Key",
            userId = 1
        )

        // Populate
        DatadogService.validateApiKey(created.key)

        // Clear
        DatadogAuthMiddleware.clearCache()

        // Should still work (re-validates from DB)
        val orgId = DatadogService.validateApiKey(created.key)
        assertNotNull(orgId)
    }
}
