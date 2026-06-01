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
import com.moneat.security.detection.DetectionCoverageResponse
import com.moneat.security.detection.DetectionMatchSample
import com.moneat.security.detection.DetectionPreviewResponse
import com.moneat.security.detection.DetectionRuleListResponse
import com.moneat.security.detection.DetectionRuleResponse
import com.moneat.security.detection.DetectionTemplateListResponse
import com.moneat.security.detection.DetectionTemplateSummary
import com.moneat.security.detection.MitreCoveredRuleResponse
import com.moneat.security.detection.MitreTacticCoverageResponse
import com.moneat.security.detection.MitreTechniqueCoverageResponse
import com.moneat.security.detection.UpdateDetectionRuleRequest
import com.moneat.security.signals.SignalDetailResponse
import com.moneat.security.signals.SignalFilters
import com.moneat.security.signals.SignalListResponse
import com.moneat.security.signals.SignalResponse
import com.moneat.security.signals.TriageRequest
import com.moneat.security.signals.TriageResult
import com.moneat.security.vulnerabilities.InventoryFilters
import com.moneat.security.vulnerabilities.SbomFormat
import com.moneat.security.vulnerabilities.VulnerabilityFindingListResponse
import com.moneat.security.vulnerabilities.VulnerabilityFindingResponse
import com.moneat.security.vulnerabilities.VulnerabilityInventoryItem
import com.moneat.security.vulnerabilities.VulnerabilityInventoryResponse
import com.moneat.security.vulnerabilities.VulnerabilitySummaryResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
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
    fun `detection rule tools forward reads writes and missing ids`() = runBlocking {
        val gateway = RecordingSecurityGateway()

        val list = ListDetectionRulesTool(gateway).execute(JsonObject(emptyMap()), context)
        val get = GetDetectionRuleTool(gateway).execute(
            JsonObject(mapOf("detection_rule_id" to JsonPrimitive(77))),
            context,
        )
        val update = UpdateDetectionRuleTool(gateway).execute(
            JsonObject(
                mapOf(
                    "detection_rule_id" to JsonPrimitive(77),
                    "name" to JsonPrimitive("Updated rule"),
                    "enabled" to JsonPrimitive(true),
                )
            ),
            context,
        )
        val preview = PreviewDetectionRuleTool(gateway).execute(
            JsonObject(mapOf("detection_rule_id" to JsonPrimitive(77))),
            context,
        )
        val delete = DeleteDetectionRuleTool(gateway).execute(
            JsonObject(mapOf("detection_rule_id" to JsonPrimitive(77))),
            context,
        )

        assertFalse(list.isError)
        assertFalse(get.isError)
        assertFalse(update.isError)
        assertFalse(preview.isError)
        assertFalse(delete.isError)
        assertEquals(77, gateway.lastRuleId)
        assertEquals("Updated rule", gateway.updatedRule?.name)
        assertTrue(delete.content.single().text!!.contains("deleted"))
    }

    @Test
    fun `security catalog tools return coverage templates and installed rules`() = runBlocking {
        val gateway = RecordingSecurityGateway()

        val coverage = GetDetectionCoverageTool(gateway).execute(JsonObject(emptyMap()), context)
        val templates = ListDetectionTemplatesTool(gateway).execute(JsonObject(emptyMap()), context)
        val install = InstallDetectionTemplateTool(gateway).execute(
            JsonObject(mapOf("template_id" to JsonPrimitive("failed-auth"))),
            context,
        )

        assertFalse(coverage.isError)
        assertFalse(templates.isError)
        assertFalse(install.isError)
        assertEquals("failed-auth", gateway.installedTemplateId)
        assertTrue(templates.content.single().text!!.contains("Failed auth"))
    }

    @Test
    fun `vulnerability tools forward filters and export format`() = runBlocking {
        val gateway = RecordingSecurityGateway()

        val summary = GetVulnerabilitySummaryTool(gateway).execute(JsonObject(emptyMap()), context)
        val inventory = ListVulnerabilityInventoryTool(gateway).execute(
            JsonObject(
                mapOf(
                    "search" to JsonPrimitive("openssl"),
                    "package" to JsonPrimitive("openssl"),
                    "target" to JsonPrimitive("api"),
                    "limit" to JsonPrimitive(999),
                    "offset" to JsonPrimitive(3),
                )
            ),
            context,
        )
        val findings = ListVulnerabilityFindingsTool(gateway).execute(
            JsonObject(
                mapOf(
                    "severity" to JsonPrimitive("critical"),
                    "status" to JsonPrimitive("open"),
                    "package" to JsonPrimitive("openssl"),
                    "search" to JsonPrimitive("CVE"),
                )
            ),
            context,
        )
        val export = ExportVulnerabilitySbomTool(gateway).execute(
            JsonObject(mapOf("format" to JsonPrimitive("spdx"))),
            context,
        )

        assertFalse(summary.isError)
        assertFalse(inventory.isError)
        assertFalse(findings.isError)
        assertFalse(export.isError)
        assertEquals("openssl", gateway.inventoryFilters?.packageName)
        assertEquals(500, gateway.inventoryFilters?.limit)
        assertEquals("critical", gateway.findingSeverity)
        assertEquals(SbomFormat.SPDX, gateway.exportFormat)
    }

    @Test
    fun `security event and compliance tools forward filters`() = runBlocking {
        val gateway = RecordingSecurityGateway()

        val events = ListSecurityEventsTool(gateway).execute(
            JsonObject(
                mapOf(
                    "severity" to JsonPrimitive("high"),
                    "host" to JsonPrimitive("web"),
                    "rule_id" to JsonPrimitive("exec"),
                    "limit" to JsonPrimitive(2),
                )
            ),
            context,
        )
        val event = GetSecurityEventTool(gateway).execute(
            JsonObject(mapOf("security_event_id" to JsonPrimitive("evt-1"))),
            context,
        )
        val summary = GetComplianceSummaryTool(gateway).execute(JsonObject(emptyMap()), context)
        val trends = GetComplianceTrendsTool(gateway).execute(JsonObject(emptyMap()), context)
        val findings = ListComplianceFindingsTool(gateway).execute(
            JsonObject(
                mapOf(
                    "framework" to JsonPrimitive("cis"),
                    "status" to JsonPrimitive("failed"),
                    "offset" to JsonPrimitive(4),
                )
            ),
            context,
        )

        assertFalse(events.isError)
        assertFalse(event.isError)
        assertFalse(summary.isError)
        assertFalse(trends.isError)
        assertFalse(findings.isError)
        assertEquals("exec", gateway.securityEventFilters?.ruleId)
        assertEquals("evt-1", gateway.securityEventId)
        assertEquals("cis", gateway.complianceFilters?.framework)
        assertEquals(4, gateway.complianceFilters?.offset)
    }

    @Test
    fun `query service maps security and compliance rows`() = runBlocking {
        val executor = RecordingSecurityQueryExecutor()
        val service = SecurityMcpQueryService(executor::execute)

        val events = service.listSecurityEvents(42, SecurityEventFilters(severity = "high", host = "web"))
        val event = service.getSecurityEvent(42, "evt-1")
        val findings = service.listComplianceFindings(42, ComplianceFindingFilters(framework = "cis"))
        val summary = service.complianceSummary(42)
        val trends = service.complianceTrends(42)

        assertEquals(10, events["totalCount"]?.jsonPrimitive?.content?.toInt())
        assertTrue(events.toString().contains("evt-1"))
        assertNotNull(event)
        assertTrue(findings.toString().contains("finding-1"))
        assertTrue(summary.toString().contains("passed"))
        assertTrue(trends.toString().contains("passRate"))
        assertTrue(executor.sql.any { it.contains("organization_id = 42") })
        assertTrue(executor.operations.contains("executeCount"))
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

private class RecordingSecurityGateway : TestSecurityGateway() {
    var filters: SignalFilters? = null
    var signalLimit: Int? = null
    var signalOffset: Int? = null
    var triageOrgId: Int? = null
    var triageSignalId: Int? = null
    var triageActorUserId: Int? = null
    var triageRequest: TriageRequest? = null
    var createdRule: CreateDetectionRuleRequest? = null
    var updatedRule: UpdateDetectionRuleRequest? = null
    var lastRuleId: Int? = null
    var installedTemplateId: String? = null
    var inventoryFilters: InventoryFilters? = null
    var findingSeverity: String? = null
    var exportFormat: SbomFormat? = null
    var securityEventFilters: SecurityEventFilters? = null
    var securityEventId: String? = null
    var complianceFilters: ComplianceFindingFilters? = null

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

    override suspend fun listDetectionRules(organizationId: Int): DetectionRuleListResponse =
        DetectionRuleListResponse(
            rules = listOf(detectionRule(CreateDetectionRuleRequest("Failed auth"))),
            totalCount = 1,
        )

    override suspend fun getDetectionRule(organizationId: Int, ruleId: Int): DetectionRuleResponse? {
        lastRuleId = ruleId
        return detectionRule(CreateDetectionRuleRequest("Failed auth"))
    }

    override suspend fun updateDetectionRule(
        organizationId: Int,
        ruleId: Int,
        request: UpdateDetectionRuleRequest,
    ): DetectionRuleResponse? {
        lastRuleId = ruleId
        updatedRule = request
        return detectionRule(CreateDetectionRuleRequest(name = request.name ?: "Updated rule"))
    }

    override suspend fun deleteDetectionRule(organizationId: Int, ruleId: Int): Boolean {
        lastRuleId = ruleId
        return true
    }

    override suspend fun previewDetectionRule(organizationId: Int, ruleId: Int): DetectionPreviewResponse? {
        lastRuleId = ruleId
        return DetectionPreviewResponse(
            matchCount = 1,
            samples = listOf(DetectionMatchSample(mapOf("host" to "web-01"), 5)),
            windowSeconds = 300,
        )
    }

    override suspend fun detectionCoverage(organizationId: Int): DetectionCoverageResponse =
        DetectionCoverageResponse(
            enabledRuleCount = 1,
            tactics = listOf(MitreTacticCoverageResponse("credential-access", 1, 1)),
            techniques = listOf(
                MitreTechniqueCoverageResponse(
                    techniqueId = "T1110",
                    tactics = listOf("credential-access"),
                    ruleCount = 1,
                    rules = listOf(MitreCoveredRuleResponse(77, "Failed auth", true)),
                )
            ),
        )

    override suspend fun listDetectionTemplates(): DetectionTemplateListResponse =
        DetectionTemplateListResponse(
            templates = listOf(
                DetectionTemplateSummary(
                    id = "failed-auth",
                    name = "Failed auth",
                    description = "Failed auth template",
                    severity = "high",
                    type = "threshold",
                )
            ),
            totalCount = 1,
        )

    override suspend fun installDetectionTemplate(
        organizationId: Int,
        templateId: String,
    ): DetectionRuleResponse? {
        installedTemplateId = templateId
        return detectionRule(CreateDetectionRuleRequest("Failed auth"))
    }

    override suspend fun vulnerabilitySummary(organizationId: Int): VulnerabilitySummaryResponse =
        VulnerabilitySummaryResponse(packageCount = 3, findingCount = 1, criticalCount = 1, highCount = 0)

    override suspend fun listVulnerabilityInventory(
        organizationId: Int,
        filters: InventoryFilters,
    ): VulnerabilityInventoryResponse {
        inventoryFilters = filters
        return VulnerabilityInventoryResponse(inventory = listOf(vulnerabilityInventory()), totalCount = 1)
    }

    override suspend fun listVulnerabilityFindings(
        organizationId: Int,
        severity: String?,
        status: String?,
        packageName: String?,
        search: String?,
        limit: Int,
        offset: Int,
    ): VulnerabilityFindingListResponse {
        findingSeverity = severity
        return VulnerabilityFindingListResponse(findings = listOf(vulnerabilityFinding()), totalCount = 1)
    }

    override suspend fun exportVulnerabilitySbom(organizationId: Int, format: SbomFormat): String {
        exportFormat = format
        return """{"spdxVersion":"SPDX-2.3"}"""
    }

    override suspend fun listSecurityEvents(
        organizationId: Int,
        filters: SecurityEventFilters,
    ): JsonObject {
        securityEventFilters = filters
        return JsonObject(mapOf("events" to JsonArray(listOf(JsonPrimitive("evt-1")))))
    }

    override suspend fun getSecurityEvent(organizationId: Int, eventId: String): JsonObject? {
        securityEventId = eventId
        return JsonObject(mapOf("eventId" to JsonPrimitive(eventId)))
    }

    override suspend fun complianceSummary(organizationId: Int): JsonObject =
        JsonObject(mapOf("summary" to JsonArray(listOf(JsonPrimitive("cis")))))

    override suspend fun complianceTrends(organizationId: Int): JsonObject =
        JsonObject(mapOf("frameworks" to JsonArray(listOf(JsonPrimitive("cis")))))

    override suspend fun listComplianceFindings(
        organizationId: Int,
        filters: ComplianceFindingFilters,
    ): JsonObject {
        complianceFilters = filters
        return JsonObject(mapOf("findings" to JsonArray(listOf(JsonPrimitive("finding-1")))))
    }
}

private abstract class TestSecurityGateway : SecurityMcpGateway {
    override suspend fun listSignals(
        organizationId: Int,
        filters: SignalFilters,
        limit: Int,
        offset: Int,
    ): SignalListResponse = unsupported()

    override suspend fun getSignal(organizationId: Int, signalId: Int): SignalDetailResponse? = unsupported()

    override suspend fun triageSignal(
        organizationId: Int,
        signalId: Int,
        actorUserId: Int,
        request: TriageRequest,
    ): TriageResult = unsupported()

    override suspend fun listDetectionRules(organizationId: Int): DetectionRuleListResponse = unsupported()

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

private fun vulnerabilityInventory(): VulnerabilityInventoryItem =
    VulnerabilityInventoryItem(
        packageName = "openssl",
        packageVersion = "3.0.0",
        packageType = "deb",
        ecosystem = "debian",
        purl = "pkg:deb/debian/openssl@3.0.0",
        targetType = "host",
        targetName = "api",
        host = "api-01",
        imageName = "",
        containerId = "",
        lastSeen = "2026-01-01T00:00:00Z",
        findingCount = 1,
    )

private fun vulnerabilityFinding(): VulnerabilityFindingResponse =
    VulnerabilityFindingResponse(
        signalId = 22,
        advisoryId = "GHSA-1",
        cveId = "CVE-2026-0001",
        packageName = "openssl",
        packageVersion = "3.0.0",
        packageType = "deb",
        ecosystem = "debian",
        targetName = "api",
        severity = "critical",
        cvssScore = 9.8,
        fixedVersion = "3.0.1",
        link = "https://osv.dev/vulnerability/GHSA-1",
        status = "open",
        lastSeen = "2026-01-01T00:00:00Z",
    )

private class RecordingSecurityQueryExecutor {
    val sql = mutableListOf<String>()
    val operations = mutableListOf<String>()

    suspend fun execute(operation: String, sql: String): String {
        operations += operation
        this.sql += sql
        return when {
            "GROUP BY framework, status" in sql -> """{"framework":"cis","status":"passed","cnt":"3"}"""
            "GROUP BY framework, bucket" in sql -> complianceTrendRow()
            "count() as cnt" in sql -> """{"cnt":"10"}"""
            "security_events" in sql && "LIMIT 1" in sql -> securityEventRow()
            "security_events" in sql -> securityEventRow()
            "compliance_findings" in sql -> complianceFindingRow()
            else -> ""
        }
    }

    private fun securityEventRow(): String =
        """
        {"event_id":"evt-1","rule_id":"exec","rule_name":"Exec","rule_category":"runtime","severity":"high",
        "agent_rule_version":"1.0","event_type":"process","process_name":"bash","file_path":"/bin/bash",
        "host":"web-01","env":"prod","tags":["security"],"ts":"2026-01-01T00:00:00.000Z"}
        """.trimIndent().replace("\n", "")

    private fun complianceFindingRow(): String =
        """
        {"finding_id":"finding-1","framework":"cis","rule_id":"cis-1","rule_name":"CIS 1","status":"failed",
        "resource_type":"host","resource_id":"host-1","resource_name":"web-01","tags":["cis"],
        "ts":"2026-01-01T00:00:00.000Z"}
        """.trimIndent().replace("\n", "")

    private fun complianceTrendRow(): String =
        """
        {"framework":"cis","bucket":"2026-01-01T00:00:00.000Z","passed":"8","failed":"2",
        "skipped":"1","error":"0","total":"11"}
        """.trimIndent().replace("\n", "")
}
