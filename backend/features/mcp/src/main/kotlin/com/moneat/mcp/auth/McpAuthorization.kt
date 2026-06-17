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
import com.moneat.shared.services.toUuidOrNull
import com.moneat.statuspage.models.StatusPages
import com.moneat.synthetics.routes.SyntheticTests
import com.moneat.uptime.models.UptimeMonitors
import kotlinx.serialization.json.JsonObject
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
        args.stringValue("project_id")?.let { requireProjectResourceAccess(it, context) }
        args.stringValue("issue_id")?.let { requireIssueAccess(it, context) }
        args.stringValue("event_id")?.let { requireTransactionAccess(it, context) }
        args.uuidValue("host_id")?.let { requireHostAccess(it, context) }
        args.stringValue("dashboard_id")?.let { requireDashboardAccess(it, context) }
        args.uuidValue("monitor_id")?.let { requireUptimeMonitorAccess(it, context) }
        args.uuidValue("synthetic_test_id")?.let { requireSyntheticTestAccess(it, context) }
        args.uuidValue("page_id")?.let { requireStatusPageAccess(it, context) }
        args.uuidValue("status_page_id")?.let { requireStatusPageAccess(it, context) }
        args.stringValue("data_source_id")?.let { requireDataSourceAccess(it, context) }
        args.stringValue("security_signal_id")?.let { requireSecuritySignalAccess(it, context) }
        args.stringValue("detection_rule_id")?.let { requireDetectionRuleAccess(it, context) }
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

    private fun requireProjectResourceAccess(projectResourceId: String, context: McpContext) {
        val projectId = resolveProjectId(projectResourceId, context)
            ?: throw McpAuthorizationException("$AUTHORIZATION_ERROR: project not found")
        requireProjectAccess(projectId, context)
    }

    fun resolveProjectId(value: String, context: McpContext): Long? =
        projectIdResolver.resolve(value, context.organizationId)

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

    private fun requireHostAccess(hostResourceId: UUID, context: McpContext) {
        val hostPublicId = hostResourceId.toString().toUuidOrNull()
            ?: throw McpAuthorizationException("$AUTHORIZATION_ERROR: invalid host id")
        val hasAccess = transaction {
            Hosts
                .selectAll()
                .where {
                    (Hosts.resource_id eq hostPublicId) and
                        (Hosts.organization_id eq context.organizationId)
                }
                .count() > 0
        }
        ensureAuthorized(hasAccess, "$AUTHORIZATION_ERROR: host not found")
    }

    private fun requireDashboardAccess(dashboardResourceId: String, context: McpContext) {
        val resourceId = parseKotlinUuid(dashboardResourceId)
            ?: throw McpAuthorizationException("$AUTHORIZATION_ERROR: invalid dashboard ID")
        val hasAccess = transaction {
            Dashboards
                .selectAll()
                .where {
                    (Dashboards.resourceId eq resourceId) and
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

    private fun requireDataSourceAccess(dataSourceResourceId: String, context: McpContext) {
        val resourceId = parseKotlinUuid(dataSourceResourceId)
            ?: throw McpAuthorizationException("$AUTHORIZATION_ERROR: invalid data source ID")
        val hasAccess = transaction {
            CustomDataSources
                .selectAll()
                .where {
                    (CustomDataSources.resourceId eq resourceId) and
                        (CustomDataSources.orgId eq context.organizationId.toLong())
                }
                .count() > 0
        }
        ensureAuthorized(hasAccess, "$AUTHORIZATION_ERROR: data source not found")
    }

    private fun requireSecuritySignalAccess(signalResourceId: String, context: McpContext) {
        val resourceId = parseKotlinUuid(signalResourceId)
            ?: throw McpAuthorizationException("$AUTHORIZATION_ERROR: invalid security signal ID")
        val hasAccess = transaction {
            SecuritySignals
                .selectAll()
                .where {
                    (SecuritySignals.resourceId eq resourceId) and
                        (SecuritySignals.organizationId eq context.organizationId)
                }
                .count() > 0
        }
        ensureAuthorized(hasAccess, "$AUTHORIZATION_ERROR: security signal not found")
    }

    private fun requireDetectionRuleAccess(ruleResourceId: String, context: McpContext) {
        val resourceId = parseKotlinUuid(ruleResourceId)
            ?: throw McpAuthorizationException("$AUTHORIZATION_ERROR: invalid detection rule ID")
        val hasAccess = transaction {
            DetectionRules
                .selectAll()
                .where {
                    (DetectionRules.resourceId eq resourceId) and
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

private fun JsonObject.uuidValue(name: String): UUID? =
    stringValue(name)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun parseKotlinUuid(value: String): kotlin.uuid.Uuid? =
    value.toUuidOrNull()
