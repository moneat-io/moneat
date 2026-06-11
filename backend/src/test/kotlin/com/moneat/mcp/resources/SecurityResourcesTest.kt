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
import com.moneat.mcp.tools.ComplianceFindingFilters
import com.moneat.mcp.tools.SecurityEventFilters
import com.moneat.mcp.tools.SecurityMcpGateway
import com.moneat.security.detection.CreateDetectionRuleRequest
import com.moneat.security.detection.DetectionCoverageResponse
import com.moneat.security.detection.DetectionRuleListResponse
import com.moneat.security.detection.DetectionRuleResponse
import com.moneat.security.detection.DetectionTemplateListResponse
import com.moneat.security.detection.MitreTacticCoverageResponse
import com.moneat.security.detection.UpdateDetectionRuleRequest
import com.moneat.security.detection.DetectionPreviewResponse
import com.moneat.security.signals.SignalFilters
import com.moneat.security.signals.SignalDetailResponse
import com.moneat.security.signals.SignalListResponse
import com.moneat.security.signals.SignalResponse
import com.moneat.security.signals.TriageRequest
import com.moneat.security.signals.TriageResult
import com.moneat.security.vulnerabilities.InventoryFilters
import com.moneat.security.vulnerabilities.SbomFormat
import com.moneat.security.vulnerabilities.VulnerabilityFindingListResponse
import com.moneat.security.vulnerabilities.VulnerabilityInventoryResponse
import com.moneat.security.vulnerabilities.VulnerabilitySummaryResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SIGNAL_RESOURCE_ID = "11111111-1111-1111-1111-111111111111"
private const val DETECTION_RULE_RESOURCE_ID = "22222222-2222-2222-2222-222222222222"

// ──── Resources tests ────

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
        assertTrue(requireNotNull(content.text).contains("Suspicious exec"))
    }

    @Test
    fun `security summary resource aggregates security sections`() = runBlocking {
        val resource = SecuritySummaryResource(SecurityResourceGateway())
        val content = resource.read(context)

        assertEquals(setOf(McpScopes.SECURITY_READ), resource.requiredScopes)
        val text = requireNotNull(content.text)
        assertTrue(text.contains("openSignalCount"))
        assertTrue(text.contains("package_count"))
        assertTrue(text.contains("compliance"))
    }

    @Test
    fun `detection coverage resource returns coverage json`() = runBlocking {
        val resource = SecurityDetectionCoverageResource(SecurityResourceGateway())
        val content = resource.read(context)

        assertEquals("moneat://security/detection/coverage", content.uri)
        assertTrue(requireNotNull(content.text).contains("credential-access"))
    }
}

// ──── Gateway stub ────

private class SecurityResourceGateway : SecurityResourceGatewayBase() {
    override suspend fun listSignals(
        organizationId: Int,
        filters: SignalFilters,
        limit: Int,
        offset: Int,
    ): SignalListResponse =
        SignalListResponse(
            signals = listOf(
                SignalResponse(
                    id = SIGNAL_RESOURCE_ID,
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

    override suspend fun detectionCoverage(organizationId: Int): DetectionCoverageResponse =
        DetectionCoverageResponse(
            enabledRuleCount = 1,
            tactics = listOf(MitreTacticCoverageResponse("credential-access", 1, 1)),
            techniques = emptyList(),
        )

    override suspend fun vulnerabilitySummary(organizationId: Int): VulnerabilitySummaryResponse =
        VulnerabilitySummaryResponse(packageCount = 2, findingCount = 1, criticalCount = 1, highCount = 0)

    override suspend fun complianceSummary(organizationId: Int): JsonObject =
        JsonObject(mapOf("failed" to JsonPrimitive(1)))
}

// ──── Gateway base stub ────

private abstract class SecurityResourceGatewayBase : SecurityMcpGateway {
    override suspend fun listSignals(
        organizationId: Int,
        filters: SignalFilters,
        limit: Int,
        offset: Int,
    ): SignalListResponse = unsupported()

    override fun resolveSignalId(organizationId: Int, signalResourceId: String): Int? =
        if (signalResourceId == SIGNAL_RESOURCE_ID) 12 else null

    override suspend fun getSignal(organizationId: Int, signalId: Int): SignalDetailResponse? = unsupported()

    override suspend fun triageSignal(
        organizationId: Int,
        signalId: Int,
        actorUserId: Int,
        request: TriageRequest,
    ): TriageResult = unsupported()

    override suspend fun listDetectionRules(organizationId: Int): DetectionRuleListResponse = unsupported()

    override fun resolveDetectionRuleId(organizationId: Int, ruleResourceId: String): Int? =
        if (ruleResourceId == DETECTION_RULE_RESOURCE_ID) 77 else null

    override suspend fun getDetectionRule(organizationId: Int, ruleId: Int): DetectionRuleResponse? = unsupported()

    override suspend fun createDetectionRule(
        organizationId: Int,
        request: CreateDetectionRuleRequest,
    ): DetectionRuleResponse = unsupported()

    override suspend fun updateDetectionRule(
        organizationId: Int,
        ruleId: Int,
        request: UpdateDetectionRuleRequest,
    ): DetectionRuleResponse? = unsupported()

    override suspend fun deleteDetectionRule(organizationId: Int, ruleId: Int): Boolean = unsupported()

    override suspend fun previewDetectionRule(organizationId: Int, ruleId: Int): DetectionPreviewResponse? =
        unsupported()

    override suspend fun detectionCoverage(organizationId: Int): DetectionCoverageResponse = unsupported()

    override suspend fun listDetectionTemplates(): DetectionTemplateListResponse = unsupported()

    override suspend fun installDetectionTemplate(organizationId: Int, templateId: String): DetectionRuleResponse? =
        unsupported()

    override suspend fun vulnerabilitySummary(organizationId: Int): VulnerabilitySummaryResponse = unsupported()

    override suspend fun listVulnerabilityInventory(
        organizationId: Int,
        filters: InventoryFilters,
    ): VulnerabilityInventoryResponse = unsupported()

    override suspend fun listVulnerabilityFindings(
        organizationId: Int,
        severity: String?,
        status: String?,
        packageName: String?,
        search: String?,
        limit: Int,
        offset: Int,
    ): VulnerabilityFindingListResponse = unsupported()

    override suspend fun exportVulnerabilitySbom(organizationId: Int, format: SbomFormat): String = unsupported()

    override suspend fun listSecurityEvents(organizationId: Int, filters: SecurityEventFilters): JsonObject =
        unsupported()

    override suspend fun getSecurityEvent(organizationId: Int, eventId: String): JsonObject? = unsupported()

    override suspend fun complianceSummary(organizationId: Int): JsonObject = unsupported()

    override suspend fun complianceTrends(organizationId: Int): JsonObject = unsupported()

    override suspend fun listComplianceFindings(
        organizationId: Int,
        filters: ComplianceFindingFilters,
    ): JsonObject = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not implemented for this test")
}
