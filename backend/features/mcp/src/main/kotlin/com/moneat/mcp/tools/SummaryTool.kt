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

import com.moneat.enterprise.FeatureRegistry
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.mcp.services.IncidentContextResponse
import com.moneat.mcp.services.InfrastructureSummaryResponse
import com.moneat.mcp.services.OvernightSummaryResponse
import com.moneat.mcp.services.SummaryService
import com.moneat.mcp.services.WeeklyReportResponse
import com.moneat.shared.services.toUuidOrNull
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

class GetIncidentContextTool(
    private val nativeIncidentEntitlement: (Int) -> Boolean = FeatureRegistry::isNativeIncidentResponseEntitled,
) : McpTool {
    override val name = "get_incident_context"
    override val description =
        "Get correlated context for an incident " +
            "(alerts, metrics, logs, deploys)"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "incident_id" to schemaString("Incident UUID")
            )
        ),
        required = listOf("incident_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        if (!nativeIncidentEntitlement(context.organizationId)) {
            return errorResult("Native incident response is not enabled for this organization")
        }
        val incidentId = args["incident_id"]?.jsonPrimitive
            ?.content
            ?.toUuidOrNull()
            ?: return errorResult("incident_id is required")
        val result = summaryService.getIncidentContext(
            context.organizationId,
            incidentId,
            context.userId
        )
        return jsonResult<IncidentContextResponse>(result)
    }
}
