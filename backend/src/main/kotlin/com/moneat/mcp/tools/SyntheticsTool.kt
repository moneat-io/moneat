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
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.synthetics.routes.SyntheticTestResponse
import com.moneat.synthetics.routes.SyntheticTestSummary
import com.moneat.synthetics.routes.SyntheticsService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

private const val TEST_ID_FIELD = "test_id"
private const val TEST_ID_REQUIRED_ERROR = "test_id is required"
private const val INVALID_TEST_ID_ERROR = "Invalid test_id format"

private val defaultSyntheticsMcpService = DefaultSyntheticsMcpService()

interface SyntheticsMcpService {
    fun listTests(organizationId: Int): List<SyntheticTestResponse>
    fun getTest(testId: UUID, organizationId: Int): SyntheticTestResponse?
    fun runTestNow(testId: UUID, organizationId: Int): Boolean
    suspend fun getTestSummary(testId: String, orgIds: List<Int>): SyntheticTestSummary?
}

private class DefaultSyntheticsMcpService(
    private val service: SyntheticsService = SyntheticsService(),
) : SyntheticsMcpService {
    override fun listTests(organizationId: Int): List<SyntheticTestResponse> {
        return service.listTests(organizationId)
    }

    override fun getTest(testId: UUID, organizationId: Int): SyntheticTestResponse? {
        return service.getTest(testId, organizationId)
    }

    override fun runTestNow(testId: UUID, organizationId: Int): Boolean {
        return service.runTestNow(testId, organizationId)
    }

    override suspend fun getTestSummary(testId: String, orgIds: List<Int>): SyntheticTestSummary? {
        return service.getTestSummary(testId, orgIds)
    }
}

class ListSyntheticTestsTool(
    private val service: SyntheticsMcpService = defaultSyntheticsMcpService,
) : McpTool {
    override val name = "list_synthetic_tests"
    override val description = "List synthetic tests for the organization"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        return jsonResult(service.listTests(context.organizationId))
    }
}

class GetSyntheticTestTool(
    private val service: SyntheticsMcpService = defaultSyntheticsMcpService,
) : McpTool {
    override val name = "get_synthetic_test"
    override val description = "Get a synthetic test by ID"
    override val inputSchema = syntheticTestIdSchema("Synthetic test UUID")

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val testId = when (val parsed = args.requiredTestId()) {
            is TestIdResult.Invalid -> return errorResult(parsed.message)
            is TestIdResult.Valid -> parsed.value
        }
        val test = service.getTest(testId, context.organizationId)
            ?: return errorResult("Synthetic test not found: $testId")
        return jsonResult(test)
    }
}

class RunSyntheticTestTool(
    private val service: SyntheticsMcpService = defaultSyntheticsMcpService,
) : McpTool {
    override val name = "run_synthetic_test"
    override val description = "Run a synthetic test immediately"
    override val readOnly = false
    override val inputSchema = syntheticTestIdSchema("Synthetic test UUID")

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val testId = when (val parsed = args.requiredTestId()) {
            is TestIdResult.Invalid -> return errorResult(parsed.message)
            is TestIdResult.Valid -> parsed.value
        }
        val started = service.runTestNow(testId, context.organizationId)
        if (!started) {
            return errorResult("Synthetic test not found: $testId")
        }
        return jsonResult(SyntheticRunStartedResponse(ok = true, testId = testId.toString()))
    }
}

class GetSyntheticTestSummaryTool(
    private val service: SyntheticsMcpService = defaultSyntheticsMcpService,
) : McpTool {
    override val name = "get_synthetic_test_summary"
    override val description = "Get 30-day synthetic test uptime and latency summary"
    override val inputSchema = syntheticTestIdSchema("Synthetic test UUID")

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val testId = when (val parsed = args.requiredTestId()) {
            is TestIdResult.Invalid -> return errorResult(parsed.message)
            is TestIdResult.Valid -> parsed.value
        }
        val summary = service.getTestSummary(testId.toString(), listOf(context.organizationId))
            ?: return errorResult("Synthetic test summary not found: $testId")
        return jsonResult(summary)
    }
}

private sealed class TestIdResult {
    data class Valid(val value: UUID) : TestIdResult()
    data class Invalid(val message: String) : TestIdResult()
}

@Serializable
private data class SyntheticRunStartedResponse(
    val ok: Boolean,
    val testId: String,
)

private fun JsonObject.requiredTestId(): TestIdResult {
    val value = this[TEST_ID_FIELD] ?: return TestIdResult.Invalid(TEST_ID_REQUIRED_ERROR)
    val rawValue = (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        ?: return TestIdResult.Invalid(INVALID_TEST_ID_ERROR)
    val testId = runCatching { UUID.fromString(rawValue) }.getOrNull()
        ?: return TestIdResult.Invalid(INVALID_TEST_ID_ERROR)
    return TestIdResult.Valid(testId)
}

private fun syntheticTestIdSchema(description: String): InputSchema {
    return InputSchema(
        properties = JsonObject(
            mapOf(TEST_ID_FIELD to schemaString(description))
        ),
        required = listOf(TEST_ID_FIELD)
    )
}
