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
import com.moneat.security.detection.CreateDetectionRuleRequest
import com.moneat.security.detection.DetectionRuleResponse
import com.moneat.security.signals.SignalFilters
import com.moneat.security.signals.SignalListResponse
import com.moneat.security.signals.SignalResponse
import com.moneat.security.signals.TriageRequest
import com.moneat.security.signals.TriageResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SecurityToolTest {
    private val context = McpContext(
        organizationId = 42,
        userId = 7,
        tokenId = 1,
        scopes = setOf("security:read", "security:write"),
        sessionId = "security-test",
    )

    @Test
    fun `list security signals forwards filters and bounds pagination`() = runBlocking {
        val gateway = RecordingSecurityGateway()
        val result = ListSecuritySignalsTool(gateway).execute(
            JsonObject(
                mapOf(
                    "status" to JsonPrimitive("open"),
                    "severity" to JsonPrimitive("critical"),
                    "source" to JsonPrimitive("detection"),
                    "limit" to JsonPrimitive(999),
                    "offset" to JsonPrimitive(-5),
                )
            ),
            context,
        )

        assertFalse(result.isError)
        assertEquals(SignalFilters(status = "open", severity = "critical", source = "detection"), gateway.filters)
        assertEquals(200, gateway.signalLimit)
        assertEquals(0, gateway.signalOffset)
        val body = toolJson.decodeFromString<SignalListResponse>(result.content.single().text!!)
        assertEquals(1, body.totalCount)
        assertEquals("Suspicious exec", body.signals.single().ruleName)
    }

    @Test
    fun `triage security signal sends actor and triage request`() = runBlocking {
        val gateway = RecordingSecurityGateway()
        val result = TriageSecuritySignalTool(gateway).execute(
            JsonObject(
                mapOf(
                    "security_signal_id" to JsonPrimitive(12),
                    "status" to JsonPrimitive("under_review"),
                    "assignee_user_id" to JsonPrimitive(9),
                    "note" to JsonPrimitive("checking host"),
                )
            ),
            context,
        )

        assertFalse(result.isError)
        assertEquals(42, gateway.triageOrgId)
        assertEquals(12, gateway.triageSignalId)
        assertEquals(7, gateway.triageActorUserId)
        assertEquals("under_review", gateway.triageRequest?.status)
        assertEquals(9, gateway.triageRequest?.assigneeUserId)
        val body = toolJson.decodeFromString<SignalResponse>(result.content.single().text!!)
        assertEquals("under_review", body.status)
    }

    @Test
    fun `create detection rule decodes structured rule request`() = runBlocking {
        val gateway = RecordingSecurityGateway()
        val result = CreateDetectionRuleTool(gateway).execute(
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive("Failed auth burst"),
                    "filter" to JsonPrimitive("*:\"failed password\""),
                    "group_by" to JsonArray(listOf(JsonPrimitive("host"))),
                    "threshold_count" to JsonPrimitive(5),
                    "severity" to JsonPrimitive("high"),
                    "tags" to JsonArray(listOf(JsonPrimitive("mitre:T1110"))),
                )
            ),
            context,
        )

        assertFalse(result.isError)
        assertNotNull(gateway.createdRule)
        assertEquals("Failed auth burst", gateway.createdRule?.name)
        assertEquals(listOf("host"), gateway.createdRule?.groupBy)
        assertEquals(5, gateway.createdRule?.thresholdCount)
        val body = toolJson.decodeFromString<DetectionRuleResponse>(result.content.single().text!!)
        assertEquals("Failed auth burst", body.name)
    }

    @Test
    fun `export vulnerability sbom rejects unsupported format`() = runBlocking {
        val result = ExportVulnerabilitySbomTool(RecordingSecurityGateway()).execute(
            JsonObject(mapOf("format" to JsonPrimitive("xml"))),
            context,
        )

        assertTrue(result.isError)
        assertTrue(result.content.single().text!!.contains("format must be cyclonedx or spdx"))
    }
}

private class RecordingSecurityGateway : SecurityMcpGateway {
    var filters: SignalFilters? = null
    var signalLimit: Int? = null
    var signalOffset: Int? = null
    var triageOrgId: Int? = null
    var triageSignalId: Int? = null
    var triageActorUserId: Int? = null
    var triageRequest: TriageRequest? = null
    var createdRule: CreateDetectionRuleRequest? = null

    override suspend fun listSignals(
        organizationId: Int,
        filters: SignalFilters,
        limit: Int,
        offset: Int,
    ): SignalListResponse {
        this.filters = filters
        signalLimit = limit
        signalOffset = offset
        return SignalListResponse(signals = listOf(signal()), totalCount = 1)
    }

    override suspend fun triageSignal(
        organizationId: Int,
        signalId: Int,
        actorUserId: Int,
        request: TriageRequest,
    ): TriageResult {
        triageOrgId = organizationId
        triageSignalId = signalId
        triageActorUserId = actorUserId
        triageRequest = request
        return TriageResult.Ok(signal(status = request.status ?: "open", assigneeUserId = request.assigneeUserId))
    }

    override suspend fun createDetectionRule(
        organizationId: Int,
        request: CreateDetectionRuleRequest,
    ): DetectionRuleResponse {
        createdRule = request
        return detectionRule(request)
    }
}

private fun signal(
    status: String = "open",
    assigneeUserId: Int? = null,
): SignalResponse =
    SignalResponse(
        id = 12,
        source = "detection",
        ruleId = "detection-12",
        ruleName = "Suspicious exec",
        severity = "critical",
        status = status,
        archiveReason = null,
        dedupKey = "detection-12|host=web-01",
        entities = mapOf("host" to "web-01"),
        sampleCount = 3,
        assigneeUserId = assigneeUserId,
        tags = listOf("mitre:T1059"),
        firstSeen = "2026-01-01T00:00:00Z",
        lastSeen = "2026-01-01T00:05:00Z",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:05:00Z",
    )

private fun detectionRule(request: CreateDetectionRuleRequest): DetectionRuleResponse =
    DetectionRuleResponse(
        id = 77,
        name = request.name,
        description = request.description,
        source = request.source,
        filter = request.filter,
        groupBy = request.groupBy,
        windowSeconds = request.windowSeconds,
        type = request.type,
        thresholdCount = request.thresholdCount,
        severity = request.severity,
        signalTitle = request.signalTitle,
        signalMessage = request.signalMessage,
        suppressions = request.suppressions,
        enabled = request.enabled,
        tags = request.tags,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )
