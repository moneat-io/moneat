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

import com.moneat.config.ClickHouseClient
import com.moneat.config.ClickHouseQueryException
import com.moneat.config.isClickHouseError
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.security.detection.CreateDetectionRuleRequest
import com.moneat.security.detection.DetectionCoverageResponse
import com.moneat.security.detection.DetectionImportService
import com.moneat.security.detection.DetectionPreviewResponse
import com.moneat.security.detection.DetectionRuleListResponse
import com.moneat.security.detection.DetectionRuleResponse
import com.moneat.security.detection.DetectionRuleService
import com.moneat.security.detection.DetectionTemplateListResponse
import com.moneat.security.detection.MitreCoverageService
import com.moneat.security.detection.UpdateDetectionRuleRequest
import com.moneat.security.signals.SignalDetailResponse
import com.moneat.security.signals.SignalFilters
import com.moneat.security.signals.SignalListResponse
import com.moneat.security.signals.SignalResponse
import com.moneat.security.signals.SignalService
import com.moneat.security.signals.TriageRequest
import com.moneat.security.signals.TriageResult
import com.moneat.security.vulnerabilities.InventoryFilters
import com.moneat.security.vulnerabilities.SbomFormat
import com.moneat.security.vulnerabilities.VulnerabilityFindingListResponse
import com.moneat.security.vulnerabilities.VulnerabilityInventoryResponse
import com.moneat.security.vulnerabilities.VulnerabilityService
import com.moneat.security.vulnerabilities.VulnerabilitySummaryResponse
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import mu.KotlinLogging
import java.util.Locale

private val securityLogger = KotlinLogging.logger {}
private val defaultSecurityGateway = DefaultSecurityMcpGateway()

private const val DEFAULT_SECURITY_LIMIT = 50
private const val DEFAULT_INVENTORY_LIMIT = 100
private const val MAX_SECURITY_LIMIT = 200
private const val MAX_VULNERABILITY_LIMIT = 500
private const val SECURITY_QUERY_LOG_SQL_MAX_LEN = 200
private const val SECURITY_QUERY_LOG_BODY_MAX_LEN = 300
private const val COMPLIANCE_LOOKBACK_DAYS = 14

private const val SECURITY_SIGNAL_ID = "security_signal_id"
private const val DETECTION_RULE_ID = "detection_rule_id"
private const val SECURITY_EVENT_ID = "security_event_id"
private const val TEMPLATE_ID = "template_id"
private const val SECURITY_LIMIT_DESCRIPTION = "Max results (default 50, max 200)"
private const val RESULT_OFFSET_DESCRIPTION = "Result offset for pagination"
private const val DETECTION_RULE_ID_DESCRIPTION = "Detection rule ID"

interface SecurityMcpGateway {
    suspend fun listSignals(
        organizationId: Int,
        filters: SignalFilters,
        limit: Int,
        offset: Int,
    ): SignalListResponse

    suspend fun getSignal(organizationId: Int, signalId: Int): SignalDetailResponse?

    suspend fun triageSignal(
        organizationId: Int,
        signalId: Int,
        actorUserId: Int,
        request: TriageRequest,
    ): TriageResult

    suspend fun listDetectionRules(organizationId: Int): DetectionRuleListResponse

    suspend fun getDetectionRule(organizationId: Int, ruleId: Int): DetectionRuleResponse?

    suspend fun createDetectionRule(
        organizationId: Int,
        request: CreateDetectionRuleRequest,
    ): DetectionRuleResponse

    suspend fun updateDetectionRule(
        organizationId: Int,
        ruleId: Int,
        request: UpdateDetectionRuleRequest,
    ): DetectionRuleResponse?

    suspend fun deleteDetectionRule(organizationId: Int, ruleId: Int): Boolean

    suspend fun previewDetectionRule(organizationId: Int, ruleId: Int): DetectionPreviewResponse?

    suspend fun detectionCoverage(organizationId: Int): DetectionCoverageResponse

    suspend fun listDetectionTemplates(): DetectionTemplateListResponse

    suspend fun installDetectionTemplate(organizationId: Int, templateId: String): DetectionRuleResponse?

    suspend fun vulnerabilitySummary(organizationId: Int): VulnerabilitySummaryResponse

    suspend fun listVulnerabilityInventory(
        organizationId: Int,
        filters: InventoryFilters,
    ): VulnerabilityInventoryResponse

    suspend fun listVulnerabilityFindings(
        organizationId: Int,
        severity: String?,
        status: String?,
        packageName: String?,
        search: String?,
        limit: Int,
        offset: Int,
    ): VulnerabilityFindingListResponse

    suspend fun exportVulnerabilitySbom(organizationId: Int, format: SbomFormat): String

    suspend fun listSecurityEvents(
        organizationId: Int,
        filters: SecurityEventFilters,
    ): JsonObject

    suspend fun getSecurityEvent(organizationId: Int, eventId: String): JsonObject?

    suspend fun complianceSummary(organizationId: Int): JsonObject

    suspend fun complianceTrends(organizationId: Int): JsonObject

    suspend fun listComplianceFindings(
        organizationId: Int,
        filters: ComplianceFindingFilters,
    ): JsonObject
}

data class SecurityEventFilters(
    val severity: String? = null,
    val host: String? = null,
    val ruleId: String? = null,
    val limit: Int = DEFAULT_SECURITY_LIMIT,
    val offset: Int = 0,
)

data class ComplianceFindingFilters(
    val framework: String? = null,
    val status: String? = null,
    val limit: Int = DEFAULT_SECURITY_LIMIT,
    val offset: Int = 0,
)

class DefaultSecurityMcpGateway(
    private val signalService: SignalService = SignalService(),
    private val ruleService: DetectionRuleService = DetectionRuleService(),
    private val importService: DetectionImportService = DetectionImportService(ruleService),
    private val coverageService: MitreCoverageService = MitreCoverageService(),
    private val vulnerabilityService: VulnerabilityService = VulnerabilityService(),
    private val queryService: SecurityMcpQueryService = SecurityMcpQueryService(),
) : SecurityMcpGateway {
    override suspend fun listSignals(
        organizationId: Int,
        filters: SignalFilters,
        limit: Int,
        offset: Int,
    ): SignalListResponse = signalService.list(organizationId, filters, limit, offset)

    override suspend fun getSignal(organizationId: Int, signalId: Int): SignalDetailResponse? =
        signalService.get(organizationId, signalId)

    override suspend fun triageSignal(
        organizationId: Int,
        signalId: Int,
        actorUserId: Int,
        request: TriageRequest,
    ): TriageResult = signalService.triage(organizationId, signalId, actorUserId, request)

    override suspend fun listDetectionRules(organizationId: Int): DetectionRuleListResponse =
        ruleService.list(organizationId)

    override suspend fun getDetectionRule(organizationId: Int, ruleId: Int): DetectionRuleResponse? =
        ruleService.get(organizationId, ruleId)

    override suspend fun createDetectionRule(
        organizationId: Int,
        request: CreateDetectionRuleRequest,
    ): DetectionRuleResponse = ruleService.create(organizationId, request)

    override suspend fun updateDetectionRule(
        organizationId: Int,
        ruleId: Int,
        request: UpdateDetectionRuleRequest,
    ): DetectionRuleResponse? = ruleService.update(organizationId, ruleId, request)

    override suspend fun deleteDetectionRule(organizationId: Int, ruleId: Int): Boolean =
        ruleService.delete(organizationId, ruleId)

    override suspend fun previewDetectionRule(organizationId: Int, ruleId: Int): DetectionPreviewResponse? =
        ruleService.preview(organizationId, ruleId)

    override suspend fun detectionCoverage(organizationId: Int): DetectionCoverageResponse =
        coverageService.coverage(organizationId)

    override suspend fun listDetectionTemplates(): DetectionTemplateListResponse =
        importService.listTemplates()

    override suspend fun installDetectionTemplate(
        organizationId: Int,
        templateId: String,
    ): DetectionRuleResponse? = importService.installTemplate(organizationId, templateId)

    override suspend fun vulnerabilitySummary(organizationId: Int): VulnerabilitySummaryResponse =
        vulnerabilityService.summary(organizationId)

    override suspend fun listVulnerabilityInventory(
        organizationId: Int,
        filters: InventoryFilters,
    ): VulnerabilityInventoryResponse = vulnerabilityService.listInventory(organizationId, filters)

    override suspend fun listVulnerabilityFindings(
        organizationId: Int,
        severity: String?,
        status: String?,
        packageName: String?,
        search: String?,
        limit: Int,
        offset: Int,
    ): VulnerabilityFindingListResponse =
        vulnerabilityService.listFindings(
            orgId = organizationId,
            severity = severity,
            status = status,
            packageName = packageName,
            search = search,
            limit = limit,
            offset = offset,
        )

    override suspend fun exportVulnerabilitySbom(organizationId: Int, format: SbomFormat): String =
        vulnerabilityService.exportSbom(organizationId, format)

    override suspend fun listSecurityEvents(
        organizationId: Int,
        filters: SecurityEventFilters,
    ): JsonObject = queryService.listSecurityEvents(organizationId, filters)

    override suspend fun getSecurityEvent(organizationId: Int, eventId: String): JsonObject? =
        queryService.getSecurityEvent(organizationId, eventId)

    override suspend fun complianceSummary(organizationId: Int): JsonObject =
        queryService.complianceSummary(organizationId)

    override suspend fun complianceTrends(organizationId: Int): JsonObject =
        queryService.complianceTrends(organizationId)

    override suspend fun listComplianceFindings(
        organizationId: Int,
        filters: ComplianceFindingFilters,
    ): JsonObject = queryService.listComplianceFindings(organizationId, filters)
}

class ListSecuritySignalsTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "list_security_signals"
    override val description = "List security signals with optional status, severity, source, and time filters"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "status" to schemaEnum("Signal status", listOf("open", "under_review", "archived")),
                "severity" to schemaEnum("Signal severity", listOf("info", "low", "medium", "high", "critical")),
                "source" to schemaString("Signal source, for example detection or vulnerability"),
                "from" to schemaString("First-seen or last-seen lower bound as an ISO-8601 instant"),
                "to" to schemaString("First-seen or last-seen upper bound as an ISO-8601 instant"),
                "limit" to schemaInteger(SECURITY_LIMIT_DESCRIPTION),
                "offset" to schemaInteger(RESULT_OFFSET_DESCRIPTION),
            )
        )
    )

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val filters = SignalFilters(
            status = args.stringArg("status"),
            severity = args.stringArg("severity"),
            source = args.stringArg("source"),
            from = args.stringArg("from"),
            to = args.stringArg("to"),
        )
        return jsonResult(
            gateway.listSignals(
                context.organizationId,
                filters,
                args.limitArg(DEFAULT_SECURITY_LIMIT, MAX_SECURITY_LIMIT),
                args.offsetArg(),
            )
        )
    }
}

class GetSecuritySignalTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "get_security_signal"
    override val description = "Get one security signal with evidence, audit trail, samples, and threat intel"
    override val inputSchema = InputSchema(
        properties = JsonObject(mapOf(SECURITY_SIGNAL_ID to schemaInteger("Security signal ID"))),
        required = listOf(SECURITY_SIGNAL_ID)
    )

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val signalId = args.intArg(SECURITY_SIGNAL_ID) ?: return errorResult("$SECURITY_SIGNAL_ID is required")
        val signal = gateway.getSignal(context.organizationId, signalId)
            ?: return errorResult("Security signal not found: $signalId")
        return jsonResult(signal)
    }
}

class TriageSecuritySignalTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "triage_security_signal"
    override val description = "Triage a security signal by changing status, assignment, or adding a note"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                SECURITY_SIGNAL_ID to schemaInteger("Security signal ID"),
                "status" to schemaEnum("New status", listOf("open", "under_review", "archived")),
                "reason" to schemaEnum("Archive reason", listOf("true_positive", "false_positive", "benign")),
                "assignee_user_id" to schemaInteger("User ID to assign"),
                "clear_assignee" to schemaBoolean("Clear the current assignee"),
                "note" to schemaString("Triage note"),
            )
        ),
        required = listOf(SECURITY_SIGNAL_ID)
    )

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val signalId = args.intArg(SECURITY_SIGNAL_ID) ?: return errorResult("$SECURITY_SIGNAL_ID is required")
        val request = TriageRequest(
            status = args.stringArg("status"),
            reason = args.stringArg("reason"),
            assigneeUserId = args.intArg("assignee_user_id"),
            clearAssignee = args.booleanArg("clear_assignee") ?: false,
            note = args.stringArg("note"),
        )
        return when (val result = gateway.triageSignal(context.organizationId, signalId, context.userId, request)) {
            is TriageResult.Ok -> jsonResult<SignalResponse>(result.signal)
            is TriageResult.NotFound -> errorResult("Security signal not found: $signalId")
            is TriageResult.Invalid -> errorResult(result.message)
        }
    }
}

class ListDetectionRulesTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "list_detection_rules"
    override val description = "List security detection rules"
    override val inputSchema = InputSchema()

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult =
        jsonResult(gateway.listDetectionRules(context.organizationId))
}

class GetDetectionRuleTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "get_detection_rule"
    override val description = "Get one security detection rule"
    override val inputSchema = InputSchema(
        properties = JsonObject(mapOf(DETECTION_RULE_ID to schemaInteger(DETECTION_RULE_ID_DESCRIPTION))),
        required = listOf(DETECTION_RULE_ID)
    )

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val ruleId = args.intArg(DETECTION_RULE_ID) ?: return errorResult("$DETECTION_RULE_ID is required")
        val rule = gateway.getDetectionRule(context.organizationId, ruleId)
            ?: return errorResult("Detection rule not found: $ruleId")
        return jsonResult(rule)
    }
}

class CreateDetectionRuleTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "create_detection_rule"
    override val description = "Create a security detection rule; created rules are compiler-validated"
    override val readOnly = false
    override val inputSchema = detectionRuleInputSchema(required = listOf("name"))

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val request = when (val parsed = decodeSecurityArgs<CreateDetectionRuleRequest>(args)) {
            is SecurityParseResult.Failure -> return errorResult(parsed.message)
            is SecurityParseResult.Success -> parsed.value
        }
        return try {
            jsonResult(gateway.createDetectionRule(context.organizationId, request))
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid detection rule")
        }
    }
}

class UpdateDetectionRuleTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "update_detection_rule"
    override val description = "Update a security detection rule; the merged rule is compiler-validated"
    override val readOnly = false
    override val inputSchema = detectionRuleInputSchema(required = listOf(DETECTION_RULE_ID), includeId = true)

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val ruleId = args.intArg(DETECTION_RULE_ID) ?: return errorResult("$DETECTION_RULE_ID is required")
        val request = when (val parsed = decodeSecurityArgs<UpdateDetectionRuleRequest>(args)) {
            is SecurityParseResult.Failure -> return errorResult(parsed.message)
            is SecurityParseResult.Success -> parsed.value
        }
        return try {
            val updated = gateway.updateDetectionRule(context.organizationId, ruleId, request)
                ?: return errorResult("Detection rule not found: $ruleId")
            jsonResult(updated)
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid detection rule")
        }
    }
}

class DeleteDetectionRuleTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "delete_detection_rule"
    override val description = "Delete a security detection rule"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(mapOf(DETECTION_RULE_ID to schemaInteger(DETECTION_RULE_ID_DESCRIPTION))),
        required = listOf(DETECTION_RULE_ID)
    )

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val ruleId = args.intArg(DETECTION_RULE_ID) ?: return errorResult("$DETECTION_RULE_ID is required")
        return if (gateway.deleteDetectionRule(context.organizationId, ruleId)) {
            textResult("Detection rule $ruleId deleted")
        } else {
            errorResult("Detection rule not found: $ruleId")
        }
    }
}

class PreviewDetectionRuleTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "preview_detection_rule"
    override val description = "Preview matches for a persisted security detection rule without writing signals"
    override val inputSchema = InputSchema(
        properties = JsonObject(mapOf(DETECTION_RULE_ID to schemaInteger(DETECTION_RULE_ID_DESCRIPTION))),
        required = listOf(DETECTION_RULE_ID)
    )

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val ruleId = args.intArg(DETECTION_RULE_ID) ?: return errorResult("$DETECTION_RULE_ID is required")
        val preview = gateway.previewDetectionRule(context.organizationId, ruleId)
            ?: return errorResult("Detection rule not found: $ruleId")
        return jsonResult(preview)
    }
}

class GetDetectionCoverageTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "get_detection_coverage"
    override val description = "Get MITRE ATT&CK coverage from enabled security detection rules"
    override val inputSchema = InputSchema()

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult =
        jsonResult(gateway.detectionCoverage(context.organizationId))
}

class ListDetectionTemplatesTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "list_detection_templates"
    override val description = "List installable starter-pack security detection templates"
    override val inputSchema = InputSchema()

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult =
        jsonResult(gateway.listDetectionTemplates())
}

class InstallDetectionTemplateTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "install_detection_template"
    override val description = "Install a starter-pack security detection template as a disabled rule"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(mapOf(TEMPLATE_ID to schemaString("Detection template ID"))),
        required = listOf(TEMPLATE_ID)
    )

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val templateId = args.stringArg(TEMPLATE_ID) ?: return errorResult("$TEMPLATE_ID is required")
        return try {
            val rule = gateway.installDetectionTemplate(context.organizationId, templateId)
                ?: return errorResult("Detection template not found: $templateId")
            jsonResult(rule)
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid detection template")
        }
    }
}

class GetVulnerabilitySummaryTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "get_vulnerability_summary"
    override val description = "Get package inventory and vulnerability finding counts"
    override val inputSchema = InputSchema()

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult =
        jsonResult(gateway.vulnerabilitySummary(context.organizationId))
}

class ListVulnerabilityInventoryTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "list_vulnerability_inventory"
    override val description = "List SBOM package inventory with finding counts"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "search" to schemaString("Search package, version, target, host, image, or purl"),
                "package" to schemaString("Exact package name filter"),
                "target" to schemaString("Target name filter"),
                "limit" to schemaInteger("Max results (default 100, max 500)"),
                "offset" to schemaInteger(RESULT_OFFSET_DESCRIPTION),
            )
        )
    )

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult =
        jsonResult(
            gateway.listVulnerabilityInventory(
                context.organizationId,
                InventoryFilters(
                    search = args.stringArg("search"),
                    packageName = args.stringArg("package"),
                    target = args.stringArg("target"),
                    limit = args.limitArg(DEFAULT_INVENTORY_LIMIT, MAX_VULNERABILITY_LIMIT),
                    offset = args.offsetArg(),
                )
            )
        )
}

class ListVulnerabilityFindingsTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "list_vulnerability_findings"
    override val description = "List vulnerability findings derived from SBOM inventory"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "search" to schemaString("Search advisory, CVE, package, target, or fixed version"),
                "package" to schemaString("Exact package name filter"),
                "severity" to schemaEnum("Finding severity", listOf("info", "low", "medium", "high", "critical")),
                "status" to schemaEnum("Signal status", listOf("open", "under_review", "archived")),
                "limit" to schemaInteger("Max results (default 100, max 500)"),
                "offset" to schemaInteger(RESULT_OFFSET_DESCRIPTION),
            )
        )
    )

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult =
        jsonResult(
            gateway.listVulnerabilityFindings(
                organizationId = context.organizationId,
                severity = args.stringArg("severity"),
                status = args.stringArg("status"),
                packageName = args.stringArg("package"),
                search = args.stringArg("search"),
                limit = args.limitArg(DEFAULT_INVENTORY_LIMIT, MAX_VULNERABILITY_LIMIT),
                offset = args.offsetArg(),
            )
        )
}

class ExportVulnerabilitySbomTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "export_vulnerability_sbom"
    override val description = "Export current package inventory as CycloneDX or SPDX JSON"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("format" to schemaEnum("SBOM export format", listOf("cyclonedx", "spdx")))
        )
    )

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val format = args.sbomFormat() ?: return errorResult("format must be cyclonedx or spdx")
        return textResult(gateway.exportVulnerabilitySbom(context.organizationId, format))
    }
}

class ListSecurityEventsTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "list_security_events"
    override val description = "List runtime security events"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "severity" to schemaString("Security event severity"),
                "host" to schemaString("Host substring filter"),
                "rule_id" to schemaString("Runtime security rule ID"),
                "limit" to schemaInteger(SECURITY_LIMIT_DESCRIPTION),
                "offset" to schemaInteger(RESULT_OFFSET_DESCRIPTION),
            )
        )
    )

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult =
        jsonResult(
            gateway.listSecurityEvents(
                context.organizationId,
                SecurityEventFilters(
                    severity = args.stringArg("severity"),
                    host = args.stringArg("host"),
                    ruleId = args.stringArg("rule_id"),
                    limit = args.limitArg(DEFAULT_SECURITY_LIMIT, MAX_SECURITY_LIMIT),
                    offset = args.offsetArg(),
                )
            )
        )
}

class GetSecurityEventTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "get_security_event"
    override val description = "Get one runtime security event by ID"
    override val inputSchema = InputSchema(
        properties = JsonObject(mapOf(SECURITY_EVENT_ID to schemaString("Runtime security event ID"))),
        required = listOf(SECURITY_EVENT_ID)
    )

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
        val eventId = args.stringArg(SECURITY_EVENT_ID) ?: return errorResult("$SECURITY_EVENT_ID is required")
        val event = gateway.getSecurityEvent(context.organizationId, eventId)
            ?: return errorResult("Security event not found: $eventId")
        return jsonResult(event)
    }
}

class GetComplianceSummaryTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "get_compliance_summary"
    override val description = "Get compliance finding counts by framework and status"
    override val inputSchema = InputSchema()

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult =
        jsonResult(gateway.complianceSummary(context.organizationId))
}

class GetComplianceTrendsTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "get_compliance_trends"
    override val description = "Get $COMPLIANCE_LOOKBACK_DAYS-day compliance pass/fail trends by framework"
    override val inputSchema = InputSchema()

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult =
        jsonResult(gateway.complianceTrends(context.organizationId))
}

class ListComplianceFindingsTool(
    private val gateway: SecurityMcpGateway = defaultSecurityGateway,
) : McpTool {
    override val name = "list_compliance_findings"
    override val description = "List compliance findings"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "framework" to schemaString("Compliance framework filter"),
                "status" to schemaEnum("Compliance status", listOf("passed", "failed", "skipped", "error")),
                "limit" to schemaInteger(SECURITY_LIMIT_DESCRIPTION),
                "offset" to schemaInteger(RESULT_OFFSET_DESCRIPTION),
            )
        )
    )

    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult =
        jsonResult(
            gateway.listComplianceFindings(
                context.organizationId,
                ComplianceFindingFilters(
                    framework = args.stringArg("framework"),
                    status = args.stringArg("status"),
                    limit = args.limitArg(DEFAULT_SECURITY_LIMIT, MAX_SECURITY_LIMIT),
                    offset = args.offsetArg(),
                )
            )
        )
}

private suspend fun executeSecurityClickHouseQuery(operation: String, sql: String): String {
    val resp = ClickHouseClient.execute(sql)
    val body = resp.bodyAsText()
    if (resp.isClickHouseError(body)) {
        throw securityQueryError(operation, sql, body)
    }
    return body
}

class SecurityMcpQueryService(
    private val queryExecutor: suspend (operation: String, sql: String) -> String = ::executeSecurityClickHouseQuery,
) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    suspend fun listSecurityEvents(organizationId: Int, filters: SecurityEventFilters): JsonObject {
        val db = ClickHouseClient.getDatabase()
        val where = securityEventConditions(organizationId, filters).joinToString(" AND ")
        val totalCount = executeCount(
            "SELECT count() as cnt FROM `$db`.security_events WHERE $where FORMAT JSONEachRow"
        )
        val rows = executeRows(
            """
            SELECT event_id, rule_id, rule_name, rule_category, severity, event_type, process_name,
                file_path, host, env, tags,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts
            FROM `$db`.security_events WHERE $where
            ORDER BY timestamp DESC LIMIT ${filters.limit} OFFSET ${filters.offset}
            FORMAT JSONEachRow
            """.trimIndent(),
            ::securityEventSummary,
        )
        return buildJsonObject {
            putJsonArray("events") { rows.forEach { add(it) } }
            put("totalCount", totalCount)
        }
    }

    suspend fun getSecurityEvent(organizationId: Int, eventId: String): JsonObject? {
        val db = ClickHouseClient.getDatabase()
        val where = "${ClickHouseQueryUtils.orgIdClause(organizationId.toLong())} " +
            "AND event_id = '${escapeSql(eventId)}'"
        return executeRows(
            """
            SELECT event_id, rule_id, rule_name, rule_category, severity, agent_rule_version,
                event_type, process_name, file_path, host, env, tags,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts
            FROM `$db`.security_events WHERE $where LIMIT 1
            FORMAT JSONEachRow
            """.trimIndent(),
            ::securityEventDetail,
        ).firstOrNull()
    }

    suspend fun listComplianceFindings(
        organizationId: Int,
        filters: ComplianceFindingFilters,
    ): JsonObject {
        val db = ClickHouseClient.getDatabase()
        val where = complianceConditions(organizationId, filters).joinToString(" AND ")
        val totalCount = executeCount(
            "SELECT count() as cnt FROM `$db`.compliance_findings WHERE $where FORMAT JSONEachRow"
        )
        val rows = executeRows(
            """
            SELECT finding_id, framework, rule_id, rule_name, status, resource_type, resource_id,
                resource_name, tags,
                formatDateTime(evaluated_at, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts
            FROM `$db`.compliance_findings WHERE $where
            ORDER BY evaluated_at DESC LIMIT ${filters.limit} OFFSET ${filters.offset}
            FORMAT JSONEachRow
            """.trimIndent(),
            ::complianceFinding,
        )
        return buildJsonObject {
            putJsonArray("findings") { rows.forEach { add(it) } }
            put("totalCount", totalCount)
        }
    }

    suspend fun complianceSummary(organizationId: Int): JsonObject {
        val db = ClickHouseClient.getDatabase()
        val where = ClickHouseQueryUtils.orgIdClause(organizationId.toLong())
        val rows = executeRows(
            """
            SELECT framework, status, count() as cnt
            FROM `$db`.compliance_findings WHERE $where
            GROUP BY framework, status
            ORDER BY framework, status
            FORMAT JSONEachRow
            """.trimIndent()
        ) { obj ->
            buildJsonObject {
                put("framework", obj.s("framework"))
                put("status", obj.s("status"))
                obj["cnt"]?.let { put("count", it) }
            }
        }
        return buildJsonObject {
            putJsonArray("summary") { rows.forEach { add(it) } }
        }
    }

    suspend fun complianceTrends(organizationId: Int): JsonObject {
        val db = ClickHouseClient.getDatabase()
        val where = ClickHouseQueryUtils.orgIdClause(organizationId.toLong())
        val rows = executeRows(
            """
            SELECT framework,
                formatDateTime(toStartOfDay(evaluated_at), '%Y-%m-%dT00:00:00.000Z', 'UTC') as bucket,
                countIf(status = 'passed') as passed,
                countIf(status = 'failed') as failed,
                countIf(status = 'skipped') as skipped,
                countIf(status = 'error') as error,
                count() as total
            FROM `$db`.compliance_findings
            WHERE $where AND evaluated_at >= now() - INTERVAL $COMPLIANCE_LOOKBACK_DAYS DAY
            GROUP BY framework, bucket
            ORDER BY framework, bucket
            FORMAT JSONEachRow
            """.trimIndent()
        ) { obj ->
            val passed = obj.l("passed")
            val failed = obj.l("failed")
            val errors = obj.l("error")
            val evaluated = passed + failed + errors
            val passRate = if (evaluated > 0) passed.toDouble() / evaluated.toDouble() else 0.0
            buildJsonObject {
                put("framework", obj.s("framework"))
                put("bucketStart", obj.s("bucket"))
                put("passed", passed)
                put("failed", failed)
                put("skipped", obj.l("skipped"))
                put("error", errors)
                put("total", obj.l("total"))
                put("passRate", passRate)
            }
        }
        return buildJsonObject {
            putJsonArray("frameworks") {
                rows.groupBy { it.s("framework") }.forEach { (framework, buckets) ->
                    add(
                        buildJsonObject {
                            put("framework", framework)
                            putJsonArray("buckets") { buckets.forEach { add(it) } }
                        }
                    )
                }
            }
        }
    }

    private fun securityEventConditions(
        organizationId: Int,
        filters: SecurityEventFilters,
    ): List<String> {
        val conditions = mutableListOf(ClickHouseQueryUtils.orgIdClause(organizationId.toLong()))
        filters.severity?.let { conditions.add("severity = '${escapeSql(it)}'") }
        filters.host?.let { conditions.add("position(host, '${escapeSql(it)}') > 0") }
        filters.ruleId?.let { conditions.add("rule_id = '${escapeSql(it)}'") }
        return conditions
    }

    private fun complianceConditions(
        organizationId: Int,
        filters: ComplianceFindingFilters,
    ): List<String> {
        val conditions = mutableListOf(ClickHouseQueryUtils.orgIdClause(organizationId.toLong()))
        filters.framework?.let { conditions.add("framework = '${escapeSql(it)}'") }
        filters.status?.let { conditions.add("status = '${escapeSql(it)}'") }
        return conditions
    }

    private suspend fun executeCount(sql: String): Long {
        val body = queryExecutor("executeCount", sql)
        return body.trim().lines().firstOrNull()?.let {
            json.parseToJsonElement(it).jsonObject["cnt"]?.jsonPrimitive?.content?.toLongOrNull()
        } ?: 0L
    }

    private suspend fun executeRows(
        sql: String,
        mapper: (JsonObject) -> JsonObject,
    ): List<JsonObject> {
        val body = queryExecutor("executeRows", sql)
        return body.trim().lines()
            .filter { it.isNotBlank() }
            .map { line -> mapper(json.parseToJsonElement(line).jsonObject) }
    }
}

private fun securityQueryError(operation: String, sql: String, body: String): ClickHouseQueryException {
    securityLogger.error {
        "ClickHouse error in $operation. SQL: ${sql.take(SECURITY_QUERY_LOG_SQL_MAX_LEN)} " +
            "Body: ${body.take(SECURITY_QUERY_LOG_BODY_MAX_LEN)}"
    }
    return ClickHouseQueryException(
        isTimeout = false,
        internalDetail = "Security query failed in $operation: ${body.take(SECURITY_QUERY_LOG_BODY_MAX_LEN)}",
    )
}

private fun securityEventSummary(obj: JsonObject): JsonObject =
    buildJsonObject {
        put("eventId", obj.s("event_id"))
        put("ruleId", obj.s("rule_id"))
        put("ruleName", obj.s("rule_name"))
        put("ruleCategory", obj.s("rule_category"))
        put("severity", obj.s("severity"))
        put("eventType", obj.s("event_type"))
        put("processName", obj.s("process_name"))
        put("filePath", obj.s("file_path"))
        put("host", obj.s("host"))
        put("env", obj.s("env"))
        obj["tags"]?.let { put("tags", it) }
        put("timestamp", obj.s("ts"))
    }

private fun securityEventDetail(obj: JsonObject): JsonObject =
    buildJsonObject {
        securityEventSummary(obj).forEach { (key, value) -> put(key, value) }
        put("agentRuleVersion", obj.s("agent_rule_version"))
    }

private fun complianceFinding(obj: JsonObject): JsonObject =
    buildJsonObject {
        put("findingId", obj.s("finding_id"))
        put("framework", obj.s("framework"))
        put("ruleId", obj.s("rule_id"))
        put("ruleName", obj.s("rule_name"))
        put("status", obj.s("status"))
        put("resourceType", obj.s("resource_type"))
        put("resourceId", obj.s("resource_id"))
        put("resourceName", obj.s("resource_name"))
        obj["tags"]?.let { put("tags", it) }
        put("evaluatedAt", obj.s("ts"))
    }

private fun detectionRuleInputSchema(
    required: List<String>,
    includeId: Boolean = false,
): InputSchema {
    val properties = mutableMapOf(
        "name" to schemaString("Detection rule name"),
        "description" to schemaString("Detection rule description"),
        "source" to schemaEnum("Telemetry source", listOf("logs")),
        "filter" to schemaString("Log query filter expression"),
        "group_by" to schemaStringArray("Group-by columns such as host, service, or tags['user']"),
        "window_seconds" to schemaInteger("Evaluation window in seconds"),
        "type" to schemaEnum("Rule type", listOf("threshold", "new_value", "rate_anomaly")),
        "threshold_count" to schemaInteger("Threshold count for threshold/rate rules"),
        "severity" to schemaEnum("Signal severity", listOf("info", "low", "medium", "high", "critical")),
        "signal_title" to schemaString("Signal title template"),
        "signal_message" to schemaString("Signal message template"),
        "suppressions" to schemaStringArray("Suppression keys"),
        "enabled" to schemaBoolean("Enable the rule"),
        "tags" to schemaStringArray("Rule tags such as mitre:T1059"),
    )
    if (includeId) {
        properties[DETECTION_RULE_ID] = schemaInteger(DETECTION_RULE_ID_DESCRIPTION)
    }
    return InputSchema(properties = JsonObject(properties), required = required)
}

private fun schemaStringArray(description: String): JsonObject =
    JsonObject(
        mapOf(
            "type" to JsonPrimitive("array"),
            "description" to JsonPrimitive(description),
            "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
        )
    )

private sealed interface SecurityParseResult<out T> {
    data class Success<T>(val value: T) : SecurityParseResult<T>
    data class Failure(val message: String) : SecurityParseResult<Nothing>
}

private inline fun <reified T> decodeSecurityArgs(args: JsonObject): SecurityParseResult<T> =
    try {
        SecurityParseResult.Success(toolJson.decodeFromJsonElement(args))
    } catch (e: SerializationException) {
        SecurityParseResult.Failure(e.message ?: "Invalid JSON arguments")
    } catch (e: IllegalArgumentException) {
        SecurityParseResult.Failure(e.message ?: "Invalid arguments")
    }

private fun JsonObject.stringArg(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.intArg(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull ?: this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

private fun JsonObject.booleanArg(name: String): Boolean? =
    this[name]?.jsonPrimitive?.booleanOrNull ?: this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.limitArg(defaultValue: Int, maxValue: Int): Int =
    (intArg("limit") ?: defaultValue).coerceIn(0, maxValue)

private fun JsonObject.offsetArg(): Int =
    (intArg("offset") ?: 0).coerceAtLeast(0)

private fun JsonObject.sbomFormat(): SbomFormat? =
    when (stringArg("format")?.lowercase(Locale.ROOT) ?: SbomFormat.CYCLONEDX.wire) {
        SbomFormat.CYCLONEDX.wire -> SbomFormat.CYCLONEDX
        SbomFormat.SPDX.wire -> SbomFormat.SPDX
        else -> null
    }

private fun JsonObject.s(key: String): String {
    val element: JsonElement = this[key] ?: return ""
    return if (element is JsonPrimitive) element.content else element.toString()
}

private fun JsonObject.l(key: String): Long = s(key).toLongOrNull() ?: 0L
