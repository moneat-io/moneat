// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import com.moneat.enterprise.mcp.services.IncidentContextResponse
import com.moneat.enterprise.mcp.services.InfrastructureSummaryResponse
import com.moneat.enterprise.mcp.services.OvernightSummaryResponse
import com.moneat.enterprise.mcp.services.SummaryService
import com.moneat.enterprise.mcp.services.WeeklyReportResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val summaryService = SummaryService()
private val summaryLogger = mu.KotlinLogging.logger {}

class GetInfrastructureSummaryTool : McpTool {
    override val name = "get_infrastructure_summary"
    override val description =
        "Get aggregated infrastructure health summary"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "period" to schemaEnum(
                    "Time period",
                    listOf("24h", "7d", "30d")
                )
            )
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val allowedPeriods = setOf("24h", "7d", "30d")
        val period = args["period"]?.jsonPrimitive?.content ?: "24h"
        if (period !in allowedPeriods) {
            return errorResult(
                "period must be one of: " +
                    allowedPeriods.joinToString(", ")
            )
        }
        return try {
            val summary = summaryService.getInfrastructureSummary(
                context.organizationId,
                period
            )
            jsonResult<InfrastructureSummaryResponse>(summary)
        } catch (e: Exception) {
            summaryLogger.error(e) {
                "Failed to get infrastructure summary"
            }
            errorResult("Failed to get infrastructure summary")
        }
    }
}

class GetOvernightSummaryTool : McpTool {
    override val name = "get_overnight_summary"
    override val description =
        "Get overnight summary (10pm-8am) for triage"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "timezone" to schemaString(
                    "IANA timezone (default America/New_York)"
                )
            )
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val timezone = args["timezone"]?.jsonPrimitive?.content
            ?: "America/New_York"
        return try {
            val summary = summaryService.getOvernightSummary(
                context.organizationId,
                timezone
            )
            jsonResult<OvernightSummaryResponse>(summary)
        } catch (e: java.time.zone.ZoneRulesException) {
            errorResult("Invalid timezone: $timezone")
        } catch (e: Exception) {
            summaryLogger.error(e) {
                "Failed to get overnight summary"
            }
            errorResult("Failed to get overnight summary")
        }
    }
}

class GetWeeklyReportTool : McpTool {
    override val name = "get_weekly_report"
    override val description =
        "Get 7-day infrastructure health digest"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val report = summaryService.getWeeklyReport(
            context.organizationId
        )
        return jsonResult<WeeklyReportResponse>(report)
    }
}

class GetIncidentContextTool : McpTool {
    override val name = "get_incident_context"
    override val description =
        "Get correlated context for an incident " +
            "(alerts, metrics, logs, deploys)"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "incident_id" to schemaNumber("Incident ID")
            )
        ),
        required = listOf("incident_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val incidentId = args["incident_id"]?.jsonPrimitive
            ?.content?.toLongOrNull()
            ?: return errorResult("incident_id is required")
        val result = summaryService.getIncidentContext(
            context.organizationId,
            incidentId,
            context.userId
        )
        return jsonResult<IncidentContextResponse>(result)
    }
}
