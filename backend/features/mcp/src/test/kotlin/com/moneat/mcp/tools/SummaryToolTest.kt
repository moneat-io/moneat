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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummaryToolTest {
    @Test
    fun `incident context fails closed when native incident response is disabled`() = runBlocking {
        val tool = GetIncidentContextTool(nativeIncidentEntitlement = { false })
        val context =
            McpContext(
                organizationId = 42,
                userId = 7,
                tokenId = 3,
                scopes = emptySet(),
                sessionId = "test-session",
            )

        val result =
            tool.execute(
                JsonObject(mapOf("incident_id" to JsonPrimitive("11111111-1111-4111-8111-111111111111"))),
                context,
            )

        assertTrue(result.isError)
        assertEquals("Native incident response is not enabled for this organization", result.content.single().text)
    }
}
