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
import com.moneat.synthetics.routes.SyntheticTestResponse
import com.moneat.synthetics.routes.SyntheticTestSummary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ORG_ID = 7
private const val OTHER_ORG_ID = 8

class SyntheticsToolTest {
    private val testId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val context = McpContext(
        organizationId = ORG_ID,
        userId = 2,
        tokenId = 3,
        scopes = setOf("project:read", "project:write"),
        sessionId = "synthetics-tool-test",
    )

    @Test
    fun `list synthetic tests returns tests for context organization`() = runBlocking {
        val service = FakeSyntheticsMcpService(
            tests = listOf(
                syntheticTest(testId, ORG_ID, "API health"),
                syntheticTest(UUID.randomUUID(), OTHER_ORG_ID, "Other org"),
            )
        )

        val result = ListSyntheticTestsTool(service).execute(emptyArgs(), context)

        assertFalse(result.isError)
        val tests = toolJson.decodeFromString<List<SyntheticTestResponse>>(result.text())
        assertEquals(listOf("API health"), tests.map { it.name })
    }

    @Test
    fun `get synthetic test returns one test`() = runBlocking {
        val service = FakeSyntheticsMcpService(
            tests = listOf(syntheticTest(testId, ORG_ID, "Checkout check"))
        )

        val result = GetSyntheticTestTool(service).execute(testIdArgs(testId), context)

        assertFalse(result.isError)
        val test = toolJson.decodeFromString<SyntheticTestResponse>(result.text())
        assertEquals("Checkout check", test.name)
        assertEquals(testId.toString(), test.id)
    }

    @Test
    fun `get synthetic test validates test id`() = runBlocking {
        val result = GetSyntheticTestTool(FakeSyntheticsMcpService()).execute(emptyArgs(), context)

        assertTrue(result.isError)
        assertTrue(result.text().contains("test_id is required"))
    }

    @Test
    fun `get synthetic test rejects malformed test id`() = runBlocking {
        val result = GetSyntheticTestTool(FakeSyntheticsMcpService())
            .execute(stringArgs("test_id", "not-a-uuid"), context)

        assertTrue(result.isError)
        assertTrue(result.text().contains("Invalid test_id format"))
    }

    @Test
    fun `run synthetic test starts an immediate run`() = runBlocking {
        val service = FakeSyntheticsMcpService(runStarted = true)
        val tool = RunSyntheticTestTool(service)

        val result = tool.execute(testIdArgs(testId), context)

        assertFalse(tool.readOnly)
        assertFalse(result.isError)
        assertEquals(testId to ORG_ID, service.lastRunRequest)
        val response = toolJson.decodeFromString<RunStartedResponse>(result.text())
        assertTrue(response.ok)
        assertEquals(testId.toString(), response.testId)
    }

    @Test
    fun `run synthetic test returns not found when service cannot start`() = runBlocking {
        val result = RunSyntheticTestTool(FakeSyntheticsMcpService())
            .execute(testIdArgs(testId), context)

        assertTrue(result.isError)
        assertTrue(result.text().contains("Synthetic test not found"))
    }

    @Test
    fun `get synthetic test summary returns summary`() = runBlocking {
        val service = FakeSyntheticsMcpService(
            summary = SyntheticTestSummary(
                testId = testId.toString(),
                uptimePercent = 99.5,
                avgResponseMs = 120.0,
                p95ResponseMs = 180.0,
                totalRuns = 200,
                failureCount = 1,
            )
        )

        val result = GetSyntheticTestSummaryTool(service).execute(testIdArgs(testId), context)

        assertFalse(result.isError)
        val summary = toolJson.decodeFromString<SyntheticTestSummary>(result.text())
        assertEquals(99.5, summary.uptimePercent)
        assertEquals(200, summary.totalRuns)
    }

    @Test
    fun `get synthetic test summary returns not found when no summary exists`() = runBlocking {
        val result = GetSyntheticTestSummaryTool(FakeSyntheticsMcpService())
            .execute(testIdArgs(testId), context)

        assertTrue(result.isError)
        assertTrue(result.text().contains("Synthetic test summary not found"))
    }

    private fun emptyArgs() = kotlinx.serialization.json.JsonObject(emptyMap())

    private fun testIdArgs(id: UUID) = kotlinx.serialization.json.JsonObject(
        mapOf("test_id" to kotlinx.serialization.json.JsonPrimitive(id.toString()))
    )

    private fun stringArgs(
        name: String,
        value: String,
    ) = kotlinx.serialization.json.JsonObject(
        mapOf(name to kotlinx.serialization.json.JsonPrimitive(value))
    )

    private fun syntheticTest(
        id: UUID,
        organizationId: Int,
        name: String,
    ): SyntheticTestResponse {
        return SyntheticTestResponse(
            id = id.toString(),
            organizationId = organizationId,
            name = name,
            testType = "api",
            active = true,
            intervalSeconds = 60,
            timeoutSeconds = 10,
            url = "https://example.com/health",
            method = "GET",
            assertions = emptyList(),
            steps = emptyList(),
            status = "pending",
            tags = emptyList(),
            createdAt = 0,
            updatedAt = 0,
        )
    }
}

private class FakeSyntheticsMcpService(
    private val tests: List<SyntheticTestResponse> = emptyList(),
    private val summary: SyntheticTestSummary? = null,
    private val runStarted: Boolean = false,
) : SyntheticsMcpService {
    var lastRunRequest: Pair<UUID, Int>? = null
        private set

    override fun listTests(organizationId: Int): List<SyntheticTestResponse> {
        return tests.filter { it.organizationId == organizationId }
    }

    override fun getTest(testId: UUID, organizationId: Int): SyntheticTestResponse? {
        return tests.singleOrNull {
            it.id == testId.toString() && it.organizationId == organizationId
        }
    }

    override fun runTestNow(testId: UUID, organizationId: Int): Boolean {
        lastRunRequest = testId to organizationId
        return runStarted
    }

    override suspend fun getTestSummary(
        testId: String,
        orgIds: List<Int>,
    ): SyntheticTestSummary? {
        val orgAllowed = ORG_ID in orgIds
        return summary.takeIf { orgAllowed && it?.testId == testId }
    }
}

@Serializable
private data class RunStartedResponse(
    val ok: Boolean,
    val testId: String,
)

private fun com.moneat.mcp.protocol.ToolCallResult.text(): String =
    content.first().text.orEmpty()
