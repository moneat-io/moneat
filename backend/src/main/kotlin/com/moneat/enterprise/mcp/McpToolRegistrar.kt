// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.mcp

import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.McpToolRegistry
import mu.KotlinLogging
import com.moneat.enterprise.mcp.tools.AddStatusPageMonitorTool
import com.moneat.enterprise.mcp.tools.AggregateLogsTool
import com.moneat.enterprise.mcp.tools.CreateAlertTool
import com.moneat.enterprise.mcp.tools.CreateDashboardAlertTool
import com.moneat.enterprise.mcp.tools.CreateDashboardTool
import com.moneat.enterprise.mcp.tools.CreateDataSourceTool
import com.moneat.enterprise.mcp.tools.CreateHostTool
import com.moneat.enterprise.mcp.tools.CreateProjectTool
import com.moneat.enterprise.mcp.tools.CreateSilencePeriodTool
import com.moneat.enterprise.mcp.tools.CreateStatusPageIncidentTool
import com.moneat.enterprise.mcp.tools.CreateStatusPageTool
import com.moneat.enterprise.mcp.tools.CreateUptimeMonitorTool
import com.moneat.enterprise.mcp.tools.DeleteAlertTool
import com.moneat.enterprise.mcp.tools.DeleteDashboardAlertTool
import com.moneat.enterprise.mcp.tools.DeleteDashboardTool
import com.moneat.enterprise.mcp.tools.DeleteHostTool
import com.moneat.enterprise.mcp.tools.DeleteSilencePeriodTool
import com.moneat.enterprise.mcp.tools.DeleteUptimeMonitorTool
import com.moneat.enterprise.mcp.tools.GetAlertConfigTool
import com.moneat.enterprise.mcp.tools.ExecuteDataSourceQueryTool
import com.moneat.enterprise.mcp.tools.ExecuteDashboardQueryTool
import com.moneat.enterprise.mcp.tools.GetAlertNotificationChannelsTool
import com.moneat.enterprise.mcp.tools.GetContainerMetricsTool
import com.moneat.enterprise.mcp.tools.GetDashboardTemplatesTool
import com.moneat.enterprise.mcp.tools.GetDashboardTool
import com.moneat.enterprise.mcp.tools.GetDataSourceSchemaTool
import com.moneat.enterprise.mcp.tools.GetDbmQueriesTool
import com.moneat.enterprise.mcp.tools.GetHostLogsTool
import com.moneat.enterprise.mcp.tools.GetHostMetricsTool
import com.moneat.enterprise.mcp.tools.GetHostStatusTool
import com.moneat.enterprise.mcp.tools.GetIncidentContextTool

import com.moneat.enterprise.mcp.tools.GetInfrastructureSummaryTool
import com.moneat.enterprise.mcp.tools.GetIssueEventsTool
import com.moneat.enterprise.mcp.tools.GetIssueTransactionsTool
import com.moneat.enterprise.mcp.tools.GetIssueTool
import com.moneat.enterprise.mcp.tools.GetK8sResourcesTool
import com.moneat.enterprise.mcp.tools.GetLogFiltersTool
import com.moneat.enterprise.mcp.tools.GetLogTopValuesTool
import com.moneat.enterprise.mcp.tools.GetMonitorHeartbeatsTool
import com.moneat.enterprise.mcp.tools.GetNetworkConnectionsTool
import com.moneat.enterprise.mcp.tools.GetNotificationPreferencesTool
import com.moneat.enterprise.mcp.tools.GetOvernightSummaryTool
import com.moneat.enterprise.mcp.tools.GetProjectStatsTool
import com.moneat.enterprise.mcp.tools.GetProjectTool
import com.moneat.enterprise.mcp.tools.GetRelatedErrorsTool
import com.moneat.enterprise.mcp.tools.GetReleaseStatsTool
import com.moneat.enterprise.mcp.tools.GetSpanDetailsTool
import com.moneat.enterprise.mcp.tools.GetStatusPageTool
import com.moneat.enterprise.mcp.tools.GetTransactionStatsTool
import com.moneat.enterprise.mcp.tools.GetTraceTool
import com.moneat.enterprise.mcp.tools.GetWeeklyReportTool
import com.moneat.enterprise.mcp.tools.GlobalSearchTool
import com.moneat.enterprise.mcp.tools.ImportDashboardTool
import com.moneat.enterprise.mcp.tools.ListAlertsTool
import com.moneat.enterprise.mcp.tools.ListContainersTool
import com.moneat.enterprise.mcp.tools.ListDashboardsTool
import com.moneat.enterprise.mcp.tools.ListDataSourcesTool
import com.moneat.enterprise.mcp.tools.ListFeedbackTool
import com.moneat.enterprise.mcp.tools.ListHostsTool

import com.moneat.enterprise.mcp.tools.ListIssuesTool
import com.moneat.enterprise.mcp.tools.ListProjectsTool
import com.moneat.enterprise.mcp.tools.ListProcessesTool
import com.moneat.enterprise.mcp.tools.ListProfilesTool
import com.moneat.enterprise.mcp.tools.ListReleasesTool

import com.moneat.enterprise.mcp.tools.ListSilencePeriodsTool
import com.moneat.enterprise.mcp.tools.ListStatusPagesTool
import com.moneat.enterprise.mcp.tools.ListTransactionsTool
import com.moneat.enterprise.mcp.tools.ListUptimeMonitorsTool
import com.moneat.enterprise.mcp.tools.PauseUptimeMonitorTool
import com.moneat.enterprise.mcp.tools.PostIncidentUpdateTool
import com.moneat.enterprise.mcp.tools.QueryLogsTool
import com.moneat.enterprise.mcp.tools.ResumeUptimeMonitorTool
import com.moneat.enterprise.mcp.tools.UpdateAlertNotificationChannelsTool
import com.moneat.enterprise.mcp.tools.UpdateAlertTool
import com.moneat.enterprise.mcp.tools.UpdateDashboardAlertTool
import com.moneat.enterprise.mcp.tools.UpdateDashboardTool
import com.moneat.enterprise.mcp.tools.UpdateIssueStatusTool
import com.moneat.enterprise.mcp.tools.UpdateNotificationPreferencesTool
import com.moneat.enterprise.mcp.tools.UpdateStatusPageIncidentTool
import com.moneat.enterprise.mcp.tools.UpdateStatusPageTool
import com.moneat.enterprise.mcp.tools.UpdateUptimeMonitorTool

private val logger = KotlinLogging.logger {}

object McpToolRegistrar {
    private fun tryRegisterTool(registry: McpToolRegistry, className: String) {
        try {
            val clazz = Class.forName(className)
            val tool = clazz.getDeclaredConstructor().newInstance() as McpTool
            registry.register(tool)
        } catch (_: ClassNotFoundException) {
            logger.debug { "MCP tool not available (ee/ module not loaded): $className" }
        }
    }

    @Suppress("LongMethod")
    fun registerAll(toolRegistry: McpToolRegistry) {
        // Issue tools
        toolRegistry.register(ListIssuesTool())
        toolRegistry.register(GetIssueTool())
        toolRegistry.register(GetIssueEventsTool())
        toolRegistry.register(UpdateIssueStatusTool())

        // Log tools
        toolRegistry.register(QueryLogsTool())

        // Monitor tools
        toolRegistry.register(ListHostsTool())
        toolRegistry.register(GetHostStatusTool())
        toolRegistry.register(CreateHostTool())
        toolRegistry.register(DeleteHostTool())

        // APM tools
        toolRegistry.register(ListTransactionsTool())
        toolRegistry.register(GetTraceTool())

        // Dashboard tools
        toolRegistry.register(ListDashboardsTool())
        toolRegistry.register(GetDashboardTool())
        toolRegistry.register(CreateDashboardTool())
        toolRegistry.register(UpdateDashboardTool())
        toolRegistry.register(DeleteDashboardTool())

        // Alert tools
        toolRegistry.register(ListAlertsTool())
        toolRegistry.register(ListSilencePeriodsTool())
        toolRegistry.register(CreateAlertTool())
        toolRegistry.register(UpdateAlertTool())
        toolRegistry.register(DeleteAlertTool())
        toolRegistry.register(CreateSilencePeriodTool())
        toolRegistry.register(DeleteSilencePeriodTool())

        // Infrastructure tools
        toolRegistry.register(ListContainersTool())
        toolRegistry.register(ListProcessesTool())
        toolRegistry.register(GetK8sResourcesTool())
        toolRegistry.register(GetNetworkConnectionsTool())
        toolRegistry.register(GetDbmQueriesTool())

        // Uptime tools
        toolRegistry.register(ListUptimeMonitorsTool())
        toolRegistry.register(GetMonitorHeartbeatsTool())
        toolRegistry.register(CreateUptimeMonitorTool())
        toolRegistry.register(UpdateUptimeMonitorTool())
        toolRegistry.register(DeleteUptimeMonitorTool())
        toolRegistry.register(PauseUptimeMonitorTool())
        toolRegistry.register(ResumeUptimeMonitorTool())

        // On-Call tools (loaded from ee/ via reflection when available)
        tryRegisterTool(toolRegistry, "com.moneat.enterprise.mcp.tools.ListIncidentsTool")
        tryRegisterTool(toolRegistry, "com.moneat.enterprise.mcp.tools.GetIncidentTool")
        tryRegisterTool(toolRegistry, "com.moneat.enterprise.mcp.tools.ListSchedulesTool")

        // Profile tools
        toolRegistry.register(ListProfilesTool())

        // Release tools
        toolRegistry.register(ListReleasesTool())
        toolRegistry.register(GetReleaseStatsTool())

        // Search tools
        toolRegistry.register(GlobalSearchTool())

        // Project tools (Phase 1)
        toolRegistry.register(ListProjectsTool())
        toolRegistry.register(CreateProjectTool())
        toolRegistry.register(GetProjectTool())
        toolRegistry.register(GetProjectStatsTool())

        // Dashboard alert tools (Phase 1)
        toolRegistry.register(CreateDashboardAlertTool())
        toolRegistry.register(UpdateDashboardAlertTool())
        toolRegistry.register(DeleteDashboardAlertTool())

        // Status page tools (Phase 2)
        toolRegistry.register(ListStatusPagesTool())
        toolRegistry.register(CreateStatusPageTool())
        toolRegistry.register(GetStatusPageTool())
        toolRegistry.register(UpdateStatusPageTool())
        toolRegistry.register(AddStatusPageMonitorTool())
        toolRegistry.register(CreateStatusPageIncidentTool())
        toolRegistry.register(UpdateStatusPageIncidentTool())
        toolRegistry.register(PostIncidentUpdateTool())

        // Data source tools (Phase 2)
        toolRegistry.register(ListDataSourcesTool())
        toolRegistry.register(CreateDataSourceTool())
        toolRegistry.register(GetDataSourceSchemaTool())
        toolRegistry.register(ExecuteDataSourceQueryTool())

        // Notification tools (Phase 2)
        toolRegistry.register(GetAlertNotificationChannelsTool())
        toolRegistry.register(UpdateAlertNotificationChannelsTool())
        toolRegistry.register(GetNotificationPreferencesTool())
        toolRegistry.register(UpdateNotificationPreferencesTool())

        // Dashboard template tools (Phase 2)
        toolRegistry.register(GetDashboardTemplatesTool())
        toolRegistry.register(ImportDashboardTool())
        toolRegistry.register(ExecuteDashboardQueryTool())

        // Metrics & infrastructure tools (Phase 3)
        toolRegistry.register(GetHostMetricsTool())
        toolRegistry.register(GetContainerMetricsTool())
        toolRegistry.register(GetHostLogsTool())
        toolRegistry.register(GetAlertConfigTool())

        // Log analytics tools (Phase 3)
        toolRegistry.register(AggregateLogsTool())
        toolRegistry.register(GetLogTopValuesTool())
        toolRegistry.register(GetLogFiltersTool())

        // APM deep dive tools (Phase 3)
        toolRegistry.register(GetTransactionStatsTool())
        toolRegistry.register(GetRelatedErrorsTool())
        toolRegistry.register(GetSpanDetailsTool())

        // Correlated context tools (Phase 3)
        toolRegistry.register(GetIssueTransactionsTool())
        toolRegistry.register(ListFeedbackTool())

        // Summary tools (Phase 4)
        toolRegistry.register(GetInfrastructureSummaryTool())
        toolRegistry.register(GetOvernightSummaryTool())
        toolRegistry.register(GetWeeklyReportTool())
        toolRegistry.register(GetIncidentContextTool())
    }
}
