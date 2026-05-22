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

package com.moneat.mcp.protocol

import com.moneat.mcp.models.McpContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpResourceRegistryTest {

    private lateinit var registry: McpResourceRegistry
    private val testContext = McpContext(
        organizationId = 1,
        userId = 1,
        tokenId = 1,
        scopes = setOf("org:read"),
        sessionId = "test-session"
    )

    @BeforeEach
    fun setup() {
        registry = McpResourceRegistry()
    }

    @Test
    fun `register and list resources`() {
        val resource = object : McpResource {
            override val uri = "test://resource"
            override val name = "Test Resource"
            override val description = "A test resource"
            override suspend fun read(
                context: McpContext
            ): ResourceContent {
                return ResourceContent(
                    uri = uri,
                    text = """{"test": true}"""
                )
            }
        }

        registry.register(resource)
        val resources = registry.listResources()

        assertEquals(1, resources.size)
        assertEquals("test://resource", resources[0].uri)
        assertEquals("Test Resource", resources[0].name)
    }

    @Test
    fun `hasResource returns correct values`() {
        val resource = object : McpResource {
            override val uri = "test://exists"
            override val name = "Exists"
            override val description = "exists"
            override suspend fun read(
                context: McpContext
            ): ResourceContent {
                return ResourceContent(uri = uri, text = "{}")
            }
        }

        registry.register(resource)

        assertTrue(registry.hasResource("test://exists"))
        assertFalse(registry.hasResource("test://nope"))
    }

    @Test
    fun `readResource returns error for unknown URI`() = runBlocking {
        val result = registry.readResource("test://unknown", testContext)

        assertEquals(1, result.contents.size)
        assertTrue(result.contents[0].text!!.contains("Unknown resource"))
    }

    @Test
    fun `readResource returns content from provider`() = runBlocking {
        val resource = object : McpResource {
            override val uri = "test://data"
            override val name = "Data"
            override val description = "test data"
            override suspend fun read(
                context: McpContext
            ): ResourceContent {
                return ResourceContent(
                    uri = uri,
                    text = """{"orgId": ${context.organizationId}}"""
                )
            }
        }

        registry.register(resource)
        val result = registry.readResource("test://data", testContext)

        assertEquals(1, result.contents.size)
        assertTrue(result.contents[0].text!!.contains("\"orgId\": 1"))
    }

    @Test
    fun `readResource handles exceptions gracefully`() = runBlocking {
        val resource = object : McpResource {
            override val uri = "test://failing"
            override val name = "Failing"
            override val description = "fails"
            override suspend fun read(
                context: McpContext
            ): ResourceContent {
                throw RuntimeException("Read failed")
            }
        }

        registry.register(resource)
        val result = registry.readResource("test://failing", testContext)

        assertEquals(1, result.contents.size)
        assertTrue(result.contents[0].text!!.contains("error"))
    }
}
