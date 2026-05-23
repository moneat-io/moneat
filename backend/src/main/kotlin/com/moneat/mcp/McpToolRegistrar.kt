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

package com.moneat.mcp

import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.mcp.tools.AddStatusPageMonitorTool
import com.moneat.mcp.tools.AggregateLogsTool
import com.moneat.mcp.tools.CreateAlertTool
import com.moneat.mcp.tools.CreateDashboardAlertTool
import com.moneat.mcp.tools.CreateDashboardTool
import com.moneat.mcp.tools.CreateDataSourceTool
import com.moneat.mcp.tools.CreateAgentKeyTool
import com.moneat.mcp.tools.CreateProjectTool
import com.moneat.mcp.tools.CreateSilencePeriodTool
import com.moneat.mcp.tools.CreateStatusPageIncidentTool
import com.moneat.mcp.tools.CreateStatusPageTool
import com.moneat.mcp.tools.CreateUptimeMonitorTool
import com.moneat.mcp.tools.DeleteAlertTool
import com.moneat.mcp.tools.DeleteDashboardAlertTool
import com.moneat.mcp.tools.DeleteDashboardTool
import com.moneat.mcp.tools.DeleteHostTool
import com.moneat.mcp.tools.DeleteSilencePeriodTool
import com.moneat.mcp.tools.DeleteUptimeMonitorTool
import com.moneat.mcp.tools.GetAlertConfigTool
import com.moneat.mcp.tools.ExecuteDataSourceQueryTool
import com.moneat.mcp.tools.ExecuteDashboardQueryTool
import com.moneat.mcp.tools.GetAlertNotificationChannelsTool
import com.moneat.mcp.tools.GetContainerMetricsTool
import com.moneat.mcp.tools.GetDashboardTemplatesTool
import com.moneat.mcp.tools.GetDashboardTool
import com.moneat.mcp.tools.GetDataSourceSchemaTool
import com.moneat.mcp.tools.GetDbmQueriesTool
import com.moneat.mcp.tools.GetHostLogsTool
import com.moneat.mcp.tools.GetHostMetricsTool
import com.moneat.mcp.tools.GetHostStatusTool
import com.moneat.mcp.tools.GetIncidentContextTool
import com.moneat.mcp.tools.GetInfrastructureSummaryTool
import com.moneat.mcp.tools.GetIssueEventsTool
import com.moneat.mcp.tools.GetIssueTransactionsTool
import com.moneat.mcp.tools.GetIssueTool
import com.moneat.mcp.tools.GetK8sResourcesTool
import com.moneat.mcp.tools.GetLogFiltersTool
import com.moneat.mcp.tools.GetLogTopValuesTool
import com.moneat.mcp.tools.GetMonitorHeartbeatsTool
import com.moneat.mcp.tools.GetNetworkConnectionsTool
import com.moneat.mcp.tools.GetNotificationPreferencesTool
import com.moneat.mcp.tools.GetOvernightSummaryTool
import com.moneat.mcp.tools.GetProjectStatsTool
import com.moneat.mcp.tools.GetProjectTool
import com.moneat.mcp.tools.GetRelatedErrorsTool
import com.moneat.mcp.tools.GetReleaseStatsTool
import com.moneat.mcp.tools.GetSpanDetailsTool
import com.moneat.mcp.tools.GetStatusPageTool
import com.moneat.mcp.tools.GetTransactionStatsTool
import com.moneat.mcp.tools.GetTraceTool
import com.moneat.mcp.tools.GetWeeklyReportTool
import com.moneat.mcp.tools.GlobalSearchTool
import com.moneat.mcp.tools.ImportDashboardTool
import com.moneat.mcp.tools.ListAlertsTool
import com.moneat.mcp.tools.ListContainersTool
import com.moneat.mcp.tools.ListDashboardsTool
import com.moneat.mcp.tools.ListDataSourcesTool
import com.moneat.mcp.tools.ListFeedbackTool
import com.moneat.mcp.tools.ListHostsTool
import com.moneat.mcp.tools.ListIssuesTool
import com.moneat.mcp.tools.ListProjectsTool
import com.moneat.mcp.tools.ListProcessesTool
import com.moneat.mcp.tools.ListProfilesTool
import com.moneat.mcp.tools.ListReleasesTool
import com.moneat.mcp.tools.ListSilencePeriodsTool
import com.moneat.mcp.tools.ListStatusPagesTool
import com.moneat.mcp.tools.ListTransactionsTool
import com.moneat.mcp.tools.ListUptimeMonitorsTool
import com.moneat.mcp.tools.PauseUptimeMonitorTool
import com.moneat.mcp.tools.PostIncidentUpdateTool
import com.moneat.mcp.tools.QueryLogsTool
import com.moneat.mcp.tools.ResumeUptimeMonitorTool
import com.moneat.mcp.tools.UpdateAlertNotificationChannelsTool
import com.moneat.mcp.tools.UpdateAlertTool
import com.moneat.mcp.tools.UpdateDashboardAlertTool
import com.moneat.mcp.tools.UpdateDashboardTool
import com.moneat.mcp.tools.UpdateIssueStatusTool
import com.moneat.mcp.tools.UpdateNotificationPreferencesTool
import com.moneat.mcp.tools.UpdateStatusPageIncidentTool
import com.moneat.mcp.tools.UpdateStatusPageTool
import com.moneat.mcp.tools.UpdateUptimeMonitorTool

object McpToolRegistrar {
    // Keep registration in one ordered catalog so core and contributor MCP tools are easy to audit.
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
        toolRegistry.register(CreateAgentKeyTool())
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
