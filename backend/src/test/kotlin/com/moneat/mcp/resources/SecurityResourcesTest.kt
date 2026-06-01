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

package com.moneat.mcp.resources

import com.moneat.mcp.auth.McpScopes
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.tools.SecurityMcpGateway
import com.moneat.security.signals.SignalFilters
import com.moneat.security.signals.SignalListResponse
import com.moneat.security.signals.SignalResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecurityResourcesTest {
    private val context = McpContext(
        organizationId = 42,
        userId = 7,
        tokenId = 1,
        scopes = setOf("security:read"),
        sessionId = "security-resource-test",
    )

    @Test
    fun `open security signals resource uses security read scope and returns open signals`() = runBlocking {
        val resource = OpenSecuritySignalsResource(SecurityResourceGateway())
        val content = resource.read(context)

        assertEquals(setOf(McpScopes.SECURITY_READ), resource.requiredScopes)
        assertEquals("moneat://security/signals/open", content.uri)
        assertTrue(content.text!!.contains("Suspicious exec"))
    }
}

private class SecurityResourceGateway : SecurityMcpGateway {
    override suspend fun listSignals(
        organizationId: Int,
        filters: SignalFilters,
        limit: Int,
        offset: Int,
    ): SignalListResponse =
        SignalListResponse(
            signals = listOf(
                SignalResponse(
                    id = 12,
                    source = "detection",
                    ruleId = "detection-12",
                    ruleName = "Suspicious exec",
                    severity = "high",
                    status = "open",
                    dedupKey = "detection-12|host=web-01",
                    entities = mapOf("host" to "web-01"),
                    sampleCount = 1,
                    tags = emptyList(),
                    firstSeen = "2026-01-01T00:00:00Z",
                    lastSeen = "2026-01-01T00:00:00Z",
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                )
            ),
            totalCount = 1,
        )
}
