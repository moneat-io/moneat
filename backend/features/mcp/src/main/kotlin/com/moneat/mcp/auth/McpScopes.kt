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

package com.moneat.mcp.auth

import com.moneat.mcp.protocol.McpTool

object McpScopes {
    const val EVENT_READ = "event:read"
    const val ORG_READ = "org:read"
    const val PROJECT_READ = "project:read"
    const val PROJECT_WRITE = "project:write"
    const val RELEASES_READ = "releases:read"
    const val RELEASES_WRITE = "releases:write"
    const val WORKFLOW_READ = "workflow:read"
    const val WORKFLOW_WRITE = "workflow:write"
    const val WORKFLOW_RUN = "workflow:run"
    const val SECURITY_READ = "security:read"
    const val SECURITY_WRITE = "security:write"

    private val telemetryReadTools = listOf(
        "aggregate_logs",
        "get_feature_flag",
        "get_feature_flag_analytics",
        "get_issue",
        "get_issue_events",
        "get_issue_transactions",
        "get_log_filters",
        "get_log_top_values",
        "get_product_events",
        "get_product_funnel",
        "get_product_retention",
        "get_related_errors",
        "get_replay",
        "get_replay_recording_summary",
        "get_replay_timeline",
        "get_span_details",
        "get_trace",
        "get_transaction_stats",
        "list_feedback",
        "list_issues",
        "list_profiles",
        "list_replays",
        "list_transactions",
        "query_logs",
    ).associateWith { setOf(EVENT_READ) }

    private val projectReadTools = listOf(
        "get_alert_config",
        "get_alert_notification_channels",
        "get_container_metrics",
        "get_dashboard",
        "get_dashboard_templates",
        "get_datasource_schema",
        "get_dbm_queries",
        "get_host_logs",
        "get_host_metrics",
        "get_host_status",
        "get_k8s_resources",
        "get_monitor_heartbeats",
        "get_network_connections",
        "get_project",
        "get_project_stats",
        "get_status_page",
        "get_synthetic_test",
        "get_synthetic_test_summary",
        "global_search",
        "list_feature_flag_audit_events",
        "list_feature_flag_environments",
        "list_feature_flag_sdk_keys",
        "list_feature_flag_segments",
        "list_feature_flags",
        "list_alerts",
        "list_containers",
        "list_dashboards",
        "list_datasources",
        "list_hosts",
        "list_processes",
        "list_projects",
        "list_silence_periods",
        "list_status_pages",
        "list_synthetic_tests",
        "list_uptime_monitors",
        "preview_dashboard_widget_query",
    ).associateWith { setOf(PROJECT_READ) }

    private val releaseReadTools = listOf(
        "get_release_stats",
        "list_releases",
    ).associateWith { setOf(RELEASES_READ) }

    private val workflowReadTools = listOf(
        "get_workflow",
        "get_workflow_blueprint",
        "get_workflow_catalog",
        "get_workflow_run",
        "list_workflow_audit",
        "list_workflow_blueprints",
        "list_workflow_runs",
        "list_workflows",
    ).associateWith { setOf(WORKFLOW_READ) }

    private val securityReadTools = listOf(
        "export_vulnerability_sbom",
        "get_compliance_summary",
        "get_compliance_trends",
        "get_detection_coverage",
        "get_detection_rule",
        "get_security_event",
        "get_security_signal",
        "get_vulnerability_summary",
        "list_compliance_findings",
        "list_detection_rules",
        "list_detection_templates",
        "list_security_events",
        "list_security_signals",
        "list_vulnerability_findings",
        "list_vulnerability_inventory",
        "preview_detection_rule",
    ).associateWith { setOf(SECURITY_READ) }

    private val orgReadTools = listOf(
        "get_incident",
        "get_incident_context",
        "get_infrastructure_summary",
        "get_notification_preferences",
        "get_overnight_summary",
        "get_weekly_report",
        "list_incidents",
        "list_schedules",
    ).associateWith { setOf(ORG_READ) }

    private val writeTools = listOf(
        "add_status_page_monitor",
        "create_agent_key",
        "create_alert",
        "create_dashboard",
        "create_dashboard_alert",
        "create_dashboard_widget",
        "create_datasource",
        "create_feature_flag_environment",
        "create_feature_flag_sdk_key",
        "create_feature_flag",
        "create_project",
        "create_silence_period",
        "create_status_page",
        "create_status_page_incident",
        "create_synthetic_test",
        "create_uptime_monitor",
        "delete_alert",
        "delete_dashboard",
        "delete_dashboard_alert",
        "delete_dashboard_widget",
        "delete_feature_flag",
        "delete_feature_flag_segment",
        "delete_host",
        "delete_silence_period",
        "delete_synthetic_test",
        "delete_uptime_monitor",
        "execute_dashboard_query",
        "execute_datasource_query",
        "import_dashboard",
        "pause_uptime_monitor",
        "post_incident_update",
        "revoke_feature_flag_sdk_key",
        "resume_uptime_monitor",
        "run_synthetic_test",
        "update_alert",
        "update_alert_notification_channels",
        "update_dashboard",
        "update_dashboard_alert",
        "update_dashboard_widget",
        "update_feature_flag",
        "update_feature_flag_config",
        "update_issue_status",
        "update_notification_preferences",
        "update_status_page",
        "update_status_page_incident",
        "update_synthetic_test",
        "update_uptime_monitor",
        "upsert_feature_flag_segment",
        "replace_dashboard_widgets",
    ).associateWith { setOf(PROJECT_WRITE) }

    private val workflowWriteTools = listOf(
        "create_workflow",
        "delete_workflow",
        "get_workflow_webhook_signing",
        "publish_workflow",
        "unpublish_workflow",
        "update_workflow",
    ).associateWith { setOf(WORKFLOW_WRITE) }

    private val workflowRunTools = listOf(
        "cancel_workflow_run",
        "create_workflow_instance",
        "run_workflow",
    ).associateWith { setOf(WORKFLOW_RUN) }

    private val securityWriteTools = listOf(
        "create_detection_rule",
        "delete_detection_rule",
        "install_detection_template",
        "triage_security_signal",
        "update_detection_rule",
    ).associateWith { setOf(SECURITY_WRITE) }

    private val readScopesByTool =
        telemetryReadTools +
            projectReadTools +
            releaseReadTools +
            workflowReadTools +
            securityReadTools +
            orgReadTools
    private val writeScopesByTool = writeTools + workflowWriteTools + workflowRunTools + securityWriteTools

    fun requiredScopesFor(tool: McpTool): Set<String> {
        val scopesByTool = if (tool.readOnly) readScopesByTool else writeScopesByTool
        return requireNotNull(scopesByTool[tool.name]) {
            "No MCP scope mapping registered for tool: ${tool.name}"
        }
    }
}
