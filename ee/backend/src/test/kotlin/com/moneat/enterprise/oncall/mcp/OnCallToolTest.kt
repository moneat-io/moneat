// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.mcp

import com.moneat.enterprise.oncall.services.OnCallAlertService
import com.moneat.mcp.models.McpContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnCallToolTest {
    private val context = McpContext(
        organizationId = 42,
        userId = 7,
        tokenId = 1,
        scopes = emptySet(),
        sessionId = "test-session",
    )

    @Test
    fun `list on-call alerts initializes its service without scheduler jobs`() {
        val service = mockk<OnCallAlertService>()
        every {
            service.listAlerts(
                organizationId = 42,
                status = "triggered",
                priority = null,
                limit = 50,
                currentUserId = 7,
            )
        } returns emptyList()
        var serviceInitialized = false
        val tool = ListOnCallAlertsTool {
            serviceInitialized = true
            service
        }

        assertEquals("list_on_call_alerts", tool.name)
        assertFalse(serviceInitialized)

        val result = runBlocking {
            tool.execute(
                JsonObject(mapOf("status" to JsonPrimitive("triggered"))),
                context,
            )
        }

        assertTrue(serviceInitialized)
        assertFalse(result.isError)
        assertEquals("[]", result.content.single().text)
    }

    @Test
    fun `get on-call alert validates its id before initializing the service`() {
        var serviceInitialized = false
        val tool = GetOnCallAlertTool {
            serviceInitialized = true
            mockk()
        }

        assertEquals("get_on_call_alert", tool.name)
        val result = runBlocking {
            tool.execute(JsonObject(emptyMap()), context)
        }

        assertFalse(serviceInitialized)
        assertTrue(result.isError)
        assertEquals("alert_id is required", result.content.single().text)
    }
}
