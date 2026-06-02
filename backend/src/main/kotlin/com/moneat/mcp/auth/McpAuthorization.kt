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

import com.moneat.dashboards.models.CustomDataSources
import com.moneat.dashboards.models.Dashboards
import com.moneat.events.repositories.IssueRepositoryImpl
import com.moneat.events.services.DashboardQueryHelper
import com.moneat.events.services.IssueService
import com.moneat.events.services.TransactionService
import com.moneat.mcp.models.McpContext
import com.moneat.security.detection.DetectionRules
import com.moneat.security.signals.SecuritySignals
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.Projects
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.statuspage.models.StatusPages
import com.moneat.synthetics.routes.SyntheticTests
import com.moneat.uptime.models.UptimeMonitors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

private const val AUTHORIZATION_ERROR = "MCP authorization failed"

class McpAuthorizationException(message: String) : RuntimeException(message)

object McpAuthorization {
    private val queryHelper = DashboardQueryHelper()
    private val issueService = IssueService(IssueRepositoryImpl(queryHelper), queryHelper)
    private val transactionService = TransactionService(queryHelper)
    private val projectIdResolver = ProjectIdResolver()

    fun requireScopes(context: McpContext, requiredScopes: Set<String>) {
        val missingScopes = requiredScopes.filterNot { it in context.scopes }
        ensureAuthorized(
            missingScopes.isEmpty(),
            "$AUTHORIZATION_ERROR: missing scope ${missingScopes.joinToString(", ")}"
        )
    }

    suspend fun requireObjectAccess(args: JsonObject, context: McpContext) {
        args.projectIdValue("project_id")?.let { requireProjectAccess(it, context) }
        args.stringValue("issue_id")?.let { requireIssueAccess(it, context) }
        args.stringValue("event_id")?.let { requireTransactionAccess(it, context) }
        args.stringValue("host_id")?.toIntOrNull()?.let { requireHostAccess(it, context) }
        args.longValue("dashboard_id")?.let { requireDashboardAccess(it, context) }
        args.uuidValue("monitor_id")?.let { requireUptimeMonitorAccess(it, context) }
        args.uuidValue("synthetic_test_id")?.let { requireSyntheticTestAccess(it, context) }
        args.uuidValue("page_id")?.let { requireStatusPageAccess(it, context) }
        args.uuidValue("status_page_id")?.let { requireStatusPageAccess(it, context) }
        args.longValue("data_source_id")?.let { requireDataSourceAccess(it, context) }
        args.intValue("security_signal_id")?.let { requireSecuritySignalAccess(it, context) }
        args.intValue("detection_rule_id")?.let { requireDetectionRuleAccess(it, context) }
    }

    fun requireProjectAccess(projectId: Long, context: McpContext) {
        val hasAccess = transaction {
            Projects
                .selectAll()
                .where {
                    (Projects.id eq projectId) and
                        (Projects.organization_id eq context.organizationId)
                }
                .count() > 0
        }
        ensureAuthorized(hasAccess, "$AUTHORIZATION_ERROR: project not found")
    }

    fun resolveProjectId(value: String): Long? = projectIdResolver.resolve(value)

    private suspend fun requireIssueAccess(issueId: String, context: McpContext) {
        val projectId = issueService.getProjectIdForIssue(issueId)
            ?: throw McpAuthorizationException("$AUTHORIZATION_ERROR: issue not found")
        requireProjectAccess(projectId, context)
    }

    private suspend fun requireTransactionAccess(eventId: String, context: McpContext) {
        val projectId = transactionService.getProjectIdForTransaction(eventId)
            ?: throw McpAuthorizationException("$AUTHORIZATION_ERROR: event not found")
        requireProjectAccess(projectId, context)
    }

    private fun requireHostAccess(hostId: Int, context: McpContext) {
        val hasAccess = transaction {
            Hosts
                .selectAll()
                .where {
                    (Hosts.id eq hostId) and
                        (Hosts.organization_id eq context.organizationId)
                }
                .count() > 0
        }
        ensureAuthorized(hasAccess, "$AUTHORIZATION_ERROR: host not found")
    }

    private fun requireDashboardAccess(dashboardId: Long, context: McpContext) {
        val hasAccess = transaction {
            Dashboards
                .selectAll()
                .where {
                    (Dashboards.id eq dashboardId) and
                        (Dashboards.orgId eq context.organizationId.toLong())
                }
                .count() > 0
        }
        ensureAuthorized(hasAccess, "$AUTHORIZATION_ERROR: dashboard not found")
    }

    private fun requireUptimeMonitorAccess(monitorId: UUID, context: McpContext) {
        val hasAccess = transaction {
            UptimeMonitors
                .selectAll()
                .where {
                    (UptimeMonitors.id eq monitorId) and
                        (UptimeMonitors.organizationId eq context.organizationId)
                }
                .count() > 0
        }
        ensureAuthorized(hasAccess, "$AUTHORIZATION_ERROR: uptime monitor not found")
    }

    private fun requireSyntheticTestAccess(testId: UUID, context: McpContext) {
        val hasAccess = transaction {
            SyntheticTests
                .select(SyntheticTests.id)
                .where {
                    (SyntheticTests.id eq testId) and
                        (SyntheticTests.organizationId eq context.organizationId)
                }
                .count() > 0
        }
        ensureAuthorized(hasAccess, "$AUTHORIZATION_ERROR: synthetic test not found")
    }

    private fun requireStatusPageAccess(pageId: UUID, context: McpContext) {
        val hasAccess = transaction {
            StatusPages
                .selectAll()
                .where {
                    (StatusPages.id eq pageId) and
                        (StatusPages.organizationId eq context.organizationId)
                }
                .count() > 0
        }
        ensureAuthorized(hasAccess, "$AUTHORIZATION_ERROR: status page not found")
    }

    private fun requireDataSourceAccess(dataSourceId: Long, context: McpContext) {
        val hasAccess = transaction {
            CustomDataSources
                .selectAll()
                .where {
                    (CustomDataSources.id eq dataSourceId) and
                        (CustomDataSources.orgId eq context.organizationId.toLong())
                }
                .count() > 0
        }
        ensureAuthorized(hasAccess, "$AUTHORIZATION_ERROR: data source not found")
    }

    private fun requireSecuritySignalAccess(signalId: Int, context: McpContext) {
        val hasAccess = transaction {
            SecuritySignals
                .selectAll()
                .where {
                    (SecuritySignals.id eq signalId) and
                        (SecuritySignals.organizationId eq context.organizationId)
                }
                .count() > 0
        }
        ensureAuthorized(hasAccess, "$AUTHORIZATION_ERROR: security signal not found")
    }

    private fun requireDetectionRuleAccess(ruleId: Int, context: McpContext) {
        val hasAccess = transaction {
            DetectionRules
                .selectAll()
                .where {
                    (DetectionRules.id eq ruleId) and
                        (DetectionRules.organizationId eq context.organizationId)
                }
                .count() > 0
        }
        ensureAuthorized(hasAccess, "$AUTHORIZATION_ERROR: detection rule not found")
    }
}

private fun ensureAuthorized(condition: Boolean, message: String) {
    condition.takeIf { it } ?: throw McpAuthorizationException(message)
}

private fun JsonObject.stringValue(name: String): String? =
    this[name]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.longValue(name: String): Long? {
    val value = this[name] ?: return null
    return when (value) {
        is JsonPrimitive -> value.longOrNull ?: value.content.toLongOrNull()
        else -> null
    }
}

private fun JsonObject.intValue(name: String): Int? =
    longValue(name)
        ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
        ?.toInt()

private fun JsonObject.projectIdValue(name: String): Long? =
    stringValue(name)?.let(McpAuthorization::resolveProjectId)

private fun JsonObject.uuidValue(name: String): UUID? =
    stringValue(name)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
