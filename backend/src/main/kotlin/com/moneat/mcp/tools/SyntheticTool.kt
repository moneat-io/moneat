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
import com.moneat.synthetics.routes.CreateSyntheticTestRequest
import com.moneat.synthetics.routes.SyntheticTestResponse
import com.moneat.synthetics.routes.SyntheticTestSummary
import com.moneat.synthetics.routes.SyntheticsService
import com.moneat.synthetics.routes.UpdateSyntheticTestRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.UUID

private val defaultSyntheticsGateway = DefaultSyntheticsMcpGateway()

private const val SYNTHETIC_TEST_ID = "synthetic_test_id"

interface SyntheticsMcpGateway {
    suspend fun listTests(organizationId: Int): List<SyntheticTestResponse>

    suspend fun getTest(testId: UUID, organizationId: Int): SyntheticTestResponse?

    suspend fun createTest(organizationId: Int, request: CreateSyntheticTestRequest): SyntheticTestResponse

    suspend fun updateTest(
        testId: UUID,
        organizationId: Int,
        request: UpdateSyntheticTestRequest,
    ): SyntheticTestResponse?

    suspend fun deleteTest(testId: UUID, organizationId: Int): Boolean

    suspend fun runTestNow(testId: UUID, organizationId: Int): Boolean

    suspend fun getTestSummary(testId: UUID, organizationId: Int): SyntheticTestSummary?
}

class DefaultSyntheticsMcpGateway(
    private val syntheticsService: SyntheticsService = SyntheticsService(),
) : SyntheticsMcpGateway {
    override suspend fun listTests(organizationId: Int): List<SyntheticTestResponse> =
        syntheticsService.listTests(organizationId)

    override suspend fun getTest(testId: UUID, organizationId: Int): SyntheticTestResponse? =
        syntheticsService.getTest(testId, organizationId)

    override suspend fun createTest(
        organizationId: Int,
        request: CreateSyntheticTestRequest,
    ): SyntheticTestResponse = syntheticsService.createTest(organizationId, request)

    override suspend fun updateTest(
        testId: UUID,
        organizationId: Int,
        request: UpdateSyntheticTestRequest,
    ): SyntheticTestResponse? = syntheticsService.updateTest(testId, organizationId, request)

    override suspend fun deleteTest(testId: UUID, organizationId: Int): Boolean =
        syntheticsService.deleteTest(testId, organizationId)

    override suspend fun runTestNow(testId: UUID, organizationId: Int): Boolean =
        syntheticsService.runTestNow(testId, organizationId)

    override suspend fun getTestSummary(testId: UUID, organizationId: Int): SyntheticTestSummary? =
        syntheticsService.getTestSummary(testId.toString(), listOf(organizationId))
}

class ListSyntheticTestsTool(
    private val gateway: SyntheticsMcpGateway = defaultSyntheticsGateway,
) : McpTool {
    override val name = "list_synthetic_tests"
    override val description = "List synthetic browser and API tests"
    override val inputSchema = InputSchema()

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult =
        jsonResult(mapOf("tests" to gateway.listTests(context.organizationId)))
}

class GetSyntheticTestTool(
    private val gateway: SyntheticsMcpGateway = defaultSyntheticsGateway,
) : McpTool {
    override val name = "get_synthetic_test"
    override val description = "Get one synthetic test by ID"
    override val inputSchema = syntheticTestIdSchema()

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val testId = when (val result = args.syntheticTestIdArg()) {
            is SyntheticParseResult.Failure -> return errorResult(result.message)
            is SyntheticParseResult.Success -> result.value
        }
        val test = gateway.getTest(testId, context.organizationId)
            ?: return errorResult("Synthetic test not found: $testId")
        return jsonResult(test)
    }
}

class CreateSyntheticTestTool(
    private val gateway: SyntheticsMcpGateway = defaultSyntheticsGateway,
) : McpTool {
    override val name = "create_synthetic_test"
    override val description = "Create a synthetic browser or API test"
    override val readOnly = false
    override val inputSchema = syntheticTestInputSchema(required = listOf("name"))

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val request = when (val result = decodeSyntheticArgs<CreateSyntheticTestRequest>(args)) {
            is SyntheticParseResult.Failure -> return errorResult(result.message)
            is SyntheticParseResult.Success -> result.value
        }
        return try {
            jsonResult(gateway.createTest(context.organizationId, request))
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid synthetic test")
        } catch (e: IllegalStateException) {
            errorResult(e.message ?: "Synthetic test could not be created")
        }
    }
}

class UpdateSyntheticTestTool(
    private val gateway: SyntheticsMcpGateway = defaultSyntheticsGateway,
) : McpTool {
    override val name = "update_synthetic_test"
    override val description = "Update a synthetic browser or API test"
    override val readOnly = false
    override val inputSchema = syntheticTestInputSchema(required = listOf(SYNTHETIC_TEST_ID), includeId = true)

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val testId = when (val result = args.syntheticTestIdArg()) {
            is SyntheticParseResult.Failure -> return errorResult(result.message)
            is SyntheticParseResult.Success -> result.value
        }
        val request = when (val result = decodeSyntheticArgs<UpdateSyntheticTestRequest>(args)) {
            is SyntheticParseResult.Failure -> return errorResult(result.message)
            is SyntheticParseResult.Success -> result.value
        }
        val test = try {
            gateway.updateTest(testId, context.organizationId, request)
                ?: return errorResult("Synthetic test not found: $testId")
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "Invalid synthetic test")
        }
        return jsonResult(test)
    }
}

class DeleteSyntheticTestTool(
    private val gateway: SyntheticsMcpGateway = defaultSyntheticsGateway,
) : McpTool {
    override val name = "delete_synthetic_test"
    override val description = "Delete a synthetic test"
    override val readOnly = false
    override val inputSchema = syntheticTestIdSchema()

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val testId = when (val result = args.syntheticTestIdArg()) {
            is SyntheticParseResult.Failure -> return errorResult(result.message)
            is SyntheticParseResult.Success -> result.value
        }
        return if (gateway.deleteTest(testId, context.organizationId)) {
            textResult("Synthetic test $testId deleted")
        } else {
            errorResult("Synthetic test not found: $testId")
        }
    }
}

class RunSyntheticTestTool(
    private val gateway: SyntheticsMcpGateway = defaultSyntheticsGateway,
) : McpTool {
    override val name = "run_synthetic_test"
    override val description = "Run a synthetic test immediately"
    override val readOnly = false
    override val inputSchema = syntheticTestIdSchema()

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val testId = when (val result = args.syntheticTestIdArg()) {
            is SyntheticParseResult.Failure -> return errorResult(result.message)
            is SyntheticParseResult.Success -> result.value
        }
        return if (gateway.runTestNow(testId, context.organizationId)) {
            jsonResult(
                JsonObject(
                    mapOf(
                        "started" to JsonPrimitive(true),
                        SYNTHETIC_TEST_ID to JsonPrimitive(testId.toString()),
                    )
                )
            )
        } else {
            errorResult("Synthetic test not found: $testId")
        }
    }
}

class GetSyntheticTestSummaryTool(
    private val gateway: SyntheticsMcpGateway = defaultSyntheticsGateway,
) : McpTool {
    override val name = "get_synthetic_test_summary"
    override val description = "Get 30-day synthetic test uptime and latency summary"
    override val inputSchema = syntheticTestIdSchema()

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val testId = when (val result = args.syntheticTestIdArg()) {
            is SyntheticParseResult.Failure -> return errorResult(result.message)
            is SyntheticParseResult.Success -> result.value
        }
        val summary = gateway.getTestSummary(testId, context.organizationId)
            ?: return errorResult("Synthetic test summary not found: $testId")
        return jsonResult(summary)
    }
}

private fun syntheticTestIdSchema(): InputSchema =
    InputSchema(
        properties = JsonObject(mapOf(SYNTHETIC_TEST_ID to schemaString("Synthetic test UUID"))),
        required = listOf(SYNTHETIC_TEST_ID),
    )

private fun syntheticTestInputSchema(
    required: List<String>,
    includeId: Boolean = false,
): InputSchema {
    val properties = mutableMapOf(
        "name" to schemaString("Synthetic test name"),
        "testType" to schemaString("Test type, for example api or browser"),
        "active" to schemaBoolean("Enable or disable the test"),
        "intervalSeconds" to schemaInteger("Run interval in seconds"),
        "timeoutSeconds" to schemaInteger("Timeout in seconds"),
        "url" to schemaString("Target URL"),
        "method" to schemaString("HTTP method"),
        "headers" to schemaObject("HTTP headers object"),
        "body" to schemaString("Request body"),
        "authMethod" to schemaString("Authentication method"),
        "authUser" to schemaString("Authentication username"),
        "authPass" to schemaString("Authentication password"),
        "assertions" to schemaObjectArray("Synthetic assertions"),
        "steps" to schemaObjectArray("Multistep browser or API steps"),
        "tags" to schemaStringArray("Synthetic test tags"),
        "retryCount" to schemaInteger("Retry count"),
        "retryIntervalMs" to schemaInteger("Retry interval in milliseconds"),
        "alertOnFailure" to schemaBoolean("Send alerts when the test transitions to failed"),
        "alertChannels" to schemaStringArray("Alert channel identifiers"),
        "config" to schemaObject("Protocol-specific test configuration"),
    )
    if (includeId) {
        properties[SYNTHETIC_TEST_ID] = schemaString("Synthetic test UUID")
    }
    return InputSchema(properties = JsonObject(properties), required = required)
}

private fun schemaStringArray(description: String): JsonObject =
    schemaArray(description, JsonObject(mapOf("type" to JsonPrimitive("string"))))

private fun schemaObjectArray(description: String): JsonObject =
    schemaArray(description, JsonObject(mapOf("type" to JsonPrimitive("object"))))

private fun schemaArray(description: String, items: JsonObject): JsonObject =
    JsonObject(
        mapOf(
            "type" to JsonPrimitive("array"),
            "description" to JsonPrimitive(description),
            "items" to items,
        )
    )

private fun JsonObject.syntheticTestIdArg(): SyntheticParseResult<UUID> {
    val primitive = this[SYNTHETIC_TEST_ID] as? JsonPrimitive
        ?: return SyntheticParseResult.Failure("$SYNTHETIC_TEST_ID is required")
    val rawId = primitive.contentOrNull
        ?: return SyntheticParseResult.Failure("$SYNTHETIC_TEST_ID must be a valid UUID")
    val testId = runCatching { UUID.fromString(rawId) }.getOrNull()
        ?: return SyntheticParseResult.Failure("$SYNTHETIC_TEST_ID must be a valid UUID")
    return SyntheticParseResult.Success(testId)
}

private sealed interface SyntheticParseResult<out T> {
    data class Success<T>(val value: T) : SyntheticParseResult<T>
    data class Failure(val message: String) : SyntheticParseResult<Nothing>
}

private inline fun <reified T> decodeSyntheticArgs(args: JsonObject): SyntheticParseResult<T> =
    try {
        SyntheticParseResult.Success(toolJson.decodeFromJsonElement(args))
    } catch (e: SerializationException) {
        SyntheticParseResult.Failure(e.message ?: "Invalid JSON arguments")
    } catch (e: IllegalArgumentException) {
        SyntheticParseResult.Failure(e.message ?: "Invalid arguments")
    }
