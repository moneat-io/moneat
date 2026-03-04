// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.mcp.protocol

import com.moneat.enterprise.mcp.models.McpContext
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
