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
import com.moneat.synthetics.routes.CreateSyntheticTestRequest
import com.moneat.synthetics.routes.SyntheticAssertion
import com.moneat.synthetics.routes.SyntheticStep
import com.moneat.synthetics.routes.SyntheticTestResponse
import com.moneat.synthetics.routes.SyntheticTestSummary
import com.moneat.synthetics.routes.UpdateSyntheticTestRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val SYNTHETIC_ORG_ID = 42
private const val SYNTHETIC_USER_ID = 7
private const val SYNTHETIC_TOKEN_ID = 3
private const val SYNTHETIC_ORG_RESOURCE_ID = "00000000-0000-0000-0000-000000000042"
private const val CREATED_AT = 1_780_000_000_000L
private const val UPDATED_AT = 1_780_000_060_000L
private const val DEFAULT_INTERVAL_SECONDS = 300
private const val DEFAULT_TIMEOUT_SECONDS = 30
private const val DEFAULT_RETRY_INTERVAL_MS = 300
private const val SYNTHETIC_P95_MS = 180.0
private val SYNTHETIC_TEST_UUID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

class SyntheticToolTest {
    private val context = McpContext(
        organizationId = SYNTHETIC_ORG_ID,
        userId = SYNTHETIC_USER_ID,
        tokenId = SYNTHETIC_TOKEN_ID,
        scopes = setOf("project:read", "project:write"),
        sessionId = "synthetic-tool-test",
    )

    // ──── Read Tools ────

    @Test
    fun `list and get synthetic tests return gateway data`() = runBlocking {
        val gateway = FakeSyntheticsGateway()
        gateway.tests[SYNTHETIC_TEST_UUID] = syntheticTestResponse(name = "Checkout API")

        val listResult = ListSyntheticTestsTool(gateway).execute(JsonObject(emptyMap()), context)
        val getResult = GetSyntheticTestTool(gateway).execute(testIdArgs(SYNTHETIC_TEST_UUID), context)

        assertFalse(listResult.isError, listResult.content.first().text.orEmpty())
        assertFalse(getResult.isError, getResult.content.first().text.orEmpty())
        val tests = decodeObject(listResult)["tests"]!!.jsonArray
        assertEquals("Checkout API", tests.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Checkout API", decodeObject(getResult)["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get synthetic test summary returns uptime and latency metrics`() = runBlocking {
        val gateway = FakeSyntheticsGateway()
        gateway.summary = SyntheticTestSummary(
            testId = SYNTHETIC_TEST_UUID.toString(),
            uptimePercent = 99.9,
            avgResponseMs = 120.0,
            p95ResponseMs = SYNTHETIC_P95_MS,
            totalRuns = 100,
            failureCount = 1,
        )

        val result = GetSyntheticTestSummaryTool(gateway).execute(testIdArgs(SYNTHETIC_TEST_UUID), context)

        assertFalse(result.isError, result.content.first().text.orEmpty())
        assertEquals(SYNTHETIC_P95_MS.toString(), decodeObject(result)["p95ResponseMs"]!!.jsonPrimitive.content)
    }

    // ──── Write Tools ────

    @Test
    fun `create synthetic test decodes request body`() = runBlocking {
        val gateway = FakeSyntheticsGateway()
        val args = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Checkout API"),
                "testType" to JsonPrimitive("api"),
                "url" to JsonPrimitive("https://moneat.io/checkout"),
                "method" to JsonPrimitive("POST"),
                "assertions" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("status_code"),
                                "operator" to JsonPrimitive("equals"),
                                "value" to JsonPrimitive("200"),
                            )
                        )
                    )
                ),
                "tags" to JsonArray(listOf(JsonPrimitive("checkout"), JsonPrimitive("critical"))),
                "alertOnFailure" to JsonPrimitive(true),
            )
        )

        val result = CreateSyntheticTestTool(gateway).execute(args, context)

        assertFalse(result.isError, result.content.first().text.orEmpty())
        assertEquals("Checkout API", decodeObject(result)["name"]!!.jsonPrimitive.content)
        val request = assertNotNull(gateway.createdRequest)
        assertEquals("POST", request.method)
        assertEquals("200", request.assertions.single().value)
        assertEquals(listOf("checkout", "critical"), request.tags)
        assertTrue(request.alertOnFailure)
    }

    @Test
    fun `update synthetic test decodes partial request`() = runBlocking {
        val gateway = FakeSyntheticsGateway()
        gateway.tests[SYNTHETIC_TEST_UUID] = syntheticTestResponse(active = true)
        val args = JsonObject(
            mapOf(
                "synthetic_test_id" to JsonPrimitive(SYNTHETIC_TEST_UUID.toString()),
                "active" to JsonPrimitive(false),
                "retryCount" to JsonPrimitive(2),
            )
        )

        val result = UpdateSyntheticTestTool(gateway).execute(args, context)

        assertFalse(result.isError, result.content.first().text.orEmpty())
        val request = assertNotNull(gateway.updatedRequest)
        assertEquals(false, request.active)
        assertEquals(2, request.retryCount)
        assertEquals(false.toString(), decodeObject(result)["active"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete and run synthetic test call gateway`() = runBlocking {
        val gateway = FakeSyntheticsGateway()
        gateway.tests[SYNTHETIC_TEST_UUID] = syntheticTestResponse()

        val runResult = RunSyntheticTestTool(gateway).execute(testIdArgs(SYNTHETIC_TEST_UUID), context)
        val deleteResult = DeleteSyntheticTestTool(gateway).execute(testIdArgs(SYNTHETIC_TEST_UUID), context)

        assertFalse(runResult.isError, runResult.content.first().text.orEmpty())
        assertTrue(decodeObject(runResult)["started"]!!.jsonPrimitive.boolean)
        assertFalse(deleteResult.isError, deleteResult.content.first().text.orEmpty())
        assertEquals(SYNTHETIC_TEST_UUID, gateway.lastRunId)
        assertEquals(SYNTHETIC_TEST_UUID, gateway.lastDeletedId)
    }

    @Test
    fun `tools reject malformed synthetic test ids`() = runBlocking {
        val result = GetSyntheticTestTool(FakeSyntheticsGateway()).execute(
            JsonObject(mapOf("synthetic_test_id" to JsonPrimitive("not-a-uuid"))),
            context,
        )

        assertTrue(result.isError)
        assertTrue(result.content.first().text.orEmpty().contains("valid UUID"))
    }

    @Test
    fun `create synthetic test returns validation errors`() = runBlocking {
        val gateway = FakeSyntheticsGateway(createFailure = IllegalStateException("synthetic test quota exceeded"))

        val result = CreateSyntheticTestTool(gateway).execute(
            JsonObject(mapOf("name" to JsonPrimitive("Overflow"))),
            context,
        )

        assertTrue(result.isError)
        assertTrue(result.content.first().text.orEmpty().contains("quota exceeded"))
    }
}

private class FakeSyntheticsGateway(
    private val createFailure: RuntimeException? = null,
) : SyntheticsMcpGateway {
    val tests = mutableMapOf<UUID, SyntheticTestResponse>()
    var summary: SyntheticTestSummary? = null
    var createdRequest: CreateSyntheticTestRequest? = null
    var updatedRequest: UpdateSyntheticTestRequest? = null
    var lastDeletedId: UUID? = null
    var lastRunId: UUID? = null

    override suspend fun listTests(organizationId: Int): List<SyntheticTestResponse> =
        tests.values.toList()

    override suspend fun getTest(testId: UUID, organizationId: Int): SyntheticTestResponse? =
        tests[testId]

    override suspend fun createTest(
        organizationId: Int,
        request: CreateSyntheticTestRequest,
    ): SyntheticTestResponse {
        createFailure?.let { throw it }
        createdRequest = request
        val response = syntheticTestResponse(
            name = request.name,
            testType = request.testType,
            url = request.url,
            method = request.method,
            assertions = request.assertions,
            tags = request.tags,
            alertOnFailure = request.alertOnFailure,
        )
        tests[SYNTHETIC_TEST_UUID] = response
        return response
    }

    override suspend fun updateTest(
        testId: UUID,
        organizationId: Int,
        request: UpdateSyntheticTestRequest,
    ): SyntheticTestResponse? {
        updatedRequest = request
        val existing = tests[testId] ?: return null
        val response = existing.copy(
            name = request.name ?: existing.name,
            active = request.active ?: existing.active,
            retryCount = request.retryCount ?: existing.retryCount,
        )
        tests[testId] = response
        return response
    }

    override suspend fun deleteTest(testId: UUID, organizationId: Int): Boolean {
        lastDeletedId = testId
        return tests.remove(testId) != null
    }

    override suspend fun runTestNow(testId: UUID, organizationId: Int): Boolean {
        lastRunId = testId
        return testId in tests
    }

    override suspend fun getTestSummary(testId: UUID, organizationId: Int): SyntheticTestSummary? =
        summary?.takeIf { it.testId == testId.toString() }
}

private fun testIdArgs(testId: UUID): JsonObject =
    JsonObject(mapOf("synthetic_test_id" to JsonPrimitive(testId.toString())))

private fun decodeObject(result: com.moneat.mcp.protocol.ToolCallResult): JsonObject =
    toolJson.parseToJsonElement(result.content.first().text.orEmpty()).jsonObject

private fun syntheticTestResponse(
    name: String = "Synthetic Test",
    active: Boolean = true,
    testType: String = "api",
    url: String? = "https://moneat.io/health",
    method: String = "GET",
    assertions: List<SyntheticAssertion> = listOf(SyntheticAssertion(type = "status_code", value = "200")),
    tags: List<String> = emptyList(),
    alertOnFailure: Boolean = false,
): SyntheticTestResponse =
    SyntheticTestResponse(
        id = SYNTHETIC_TEST_UUID.toString(),
        organizationId = SYNTHETIC_ORG_RESOURCE_ID,
        name = name,
        testType = testType,
        active = active,
        intervalSeconds = DEFAULT_INTERVAL_SECONDS,
        timeoutSeconds = DEFAULT_TIMEOUT_SECONDS,
        url = url,
        method = method,
        headers = null,
        body = null,
        authMethod = null,
        authUser = null,
        assertions = assertions,
        steps = listOf(SyntheticStep(name = "open", url = "https://moneat.io")),
        status = "passing",
        lastRunAt = null,
        lastStatus = null,
        tags = tags,
        retryCount = 0,
        retryIntervalMs = DEFAULT_RETRY_INTERVAL_MS,
        alertOnFailure = alertOnFailure,
        alertChannels = emptyList(),
        config = null,
        createdAt = CREATED_AT,
        updatedAt = UPDATED_AT,
    )
