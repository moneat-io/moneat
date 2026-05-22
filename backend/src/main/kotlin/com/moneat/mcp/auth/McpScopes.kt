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

    private val telemetryReadTools = setOf(
        "query_logs",
        "aggregate_logs",
        "get_log_top_values",
        "get_log_filters",
        "list_transactions",
        "get_trace",
        "get_transaction_stats",
        "get_related_errors",
        "get_span_details",
        "list_profiles",
        "list_issues",
        "get_issue",
        "get_issue_events",
        "get_issue_transactions",
        "list_feedback",
    )

    private val releaseReadTools = setOf(
        "list_releases",
        "get_release_stats",
    )

    private val orgReadTools = setOf(
        "get_notification_preferences",
        "get_infrastructure_summary",
        "get_overnight_summary",
        "get_weekly_report",
        "get_incident_context",
    )

    private val statusPageReadTools = setOf(
        "list_status_pages",
        "get_status_page",
    )

    fun requiredScopesFor(tool: McpTool): Set<String> {
        if (!tool.readOnly) {
            return if (tool.name.contains("release")) {
                setOf(RELEASES_WRITE)
            } else {
                setOf(PROJECT_WRITE)
            }
        }

        return when (tool.name) {
            in telemetryReadTools -> setOf(EVENT_READ)
            in releaseReadTools -> setOf(RELEASES_READ)
            in orgReadTools -> setOf(ORG_READ)
            in statusPageReadTools -> setOf(PROJECT_READ)
            else -> setOf(PROJECT_READ)
        }
    }
}
