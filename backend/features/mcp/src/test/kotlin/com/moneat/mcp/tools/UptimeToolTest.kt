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

package com.moneat.mcp.tools

import com.moneat.mcp.models.McpContext
import com.moneat.uptime.models.CreateUptimeMonitorRequest
import com.moneat.uptime.models.UpdateUptimeMonitorRequest
import com.moneat.uptime.models.UptimeMonitorResponse
import com.moneat.uptime.services.UptimeService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UptimeToolTest {
    private val context = McpContext(
        organizationId = 42,
        userId = 7,
        tokenId = 3,
        scopes = setOf("project:write"),
        sessionId = "uptime-tool-test",
    )

    @Test
    fun `create uptime monitor applies retry defaults`() = runBlocking {
        val service = mockk<UptimeService>()
        val response = sampleResponse()
        every { service.createMonitor(42, any<CreateUptimeMonitorRequest>()) } answers {
            val request = secondArg<CreateUptimeMonitorRequest>()
            assertEquals(1, request.retries)
            assertEquals(60, request.retryIntervalSeconds)
            response
        }

        val result = CreateUptimeMonitorTool(service).execute(
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive("Checkout API"),
                    "url" to JsonPrimitive("https://example.com/health"),
                    "type" to JsonPrimitive("http"),
                )
            ),
            context,
        )

        assertFalse(result.isError, result.content.first().text.orEmpty())
        assertTrue(result.content.first().text?.contains(response.id) == true)
    }

    @Test
    fun `update uptime monitor accepts omitted and explicit retry settings`() = runBlocking {
        val service = mockk<UptimeService>()
        val response = sampleResponse()
        val monitorId = UUID.fromString(response.id)
        every { service.updateMonitor(monitorId, 42, any<UpdateUptimeMonitorRequest>()) } answers {
            val request = thirdArg<UpdateUptimeMonitorRequest>()
            assertEquals(2, request.retries)
            assertEquals(120, request.retryIntervalSeconds)
            response
        }

        val result = UpdateUptimeMonitorTool(service).execute(
            JsonObject(
                mapOf(
                    "monitor_id" to JsonPrimitive(response.id),
                    "retries" to JsonPrimitive(2),
                    "retry_interval_seconds" to JsonPrimitive(120),
                )
            ),
            context,
        )

        assertFalse(result.isError, result.content.first().text.orEmpty())
    }

    private fun sampleResponse(): UptimeMonitorResponse = UptimeMonitorResponse(
        id = "11111111-1111-1111-1111-111111111111",
        organizationId = context.organizationId.toString(),
        name = "Checkout API",
        type = "http",
        active = true,
        url = "https://example.com/health",
        intervalSeconds = 60,
        timeoutSeconds = 30,
        retries = 1,
        retryIntervalSeconds = 60,
        status = "pending",
        createdAt = 1L,
        updatedAt = 1L,
    )
}
