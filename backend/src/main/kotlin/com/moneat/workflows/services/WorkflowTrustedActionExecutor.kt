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

package com.moneat.workflows.services

import com.moneat.alerts.models.IncidentSeverity
import com.moneat.enterprise.FeatureRegistry
import com.moneat.enterprise.OnCallIncidentDeclaration
import com.moneat.enterprise.OnCallIncidentActionRequest
import com.moneat.events.services.DashboardService
import com.moneat.logs.models.LogAnalyticsFilters
import com.moneat.logs.models.LogQueryRequest
import com.moneat.logs.repositories.LogRepositoryImpl
import com.moneat.logs.services.LogService
import com.moneat.monitor.models.CreateSilencePeriodRequest
import com.moneat.monitor.repositories.HostAlertRepositoryImpl
import com.moneat.monitor.repositories.HostRepositoryImpl
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Projects
import com.moneat.shared.services.toUuidOrNull
import com.moneat.statuspage.models.CreateIncidentRequest
import com.moneat.statuspage.models.UpdateStatusPageRequest
import com.moneat.statuspage.services.StatusPageService
import com.moneat.workflows.engine.temporal.CONNECTOR_GITHUB_CREATE_ISSUE_ACTION
import com.moneat.workflows.engine.temporal.CONNECTOR_JIRA_CREATE_ISSUE_ACTION
import com.moneat.workflows.engine.temporal.CONNECTOR_PAGERDUTY_TRIGGER_INCIDENT_ACTION
import com.moneat.workflows.engine.temporal.CONNECTOR_SERVICENOW_CREATE_INCIDENT_ACTION
import com.moneat.workflows.engine.temporal.WORKFLOW_PREMIUM_CONNECTOR_ACTIONS
import com.moneat.workflows.models.workflowJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.uuid.Uuid

private const val DEFAULT_LOG_LIMIT = 100
private const val MAX_LOG_LIMIT = 1_000
private const val DEFAULT_LOOKBACK_HOURS = 24L
private const val DEFAULT_ISSUE_PAGE = 1
private const val DEFAULT_ISSUE_LIMIT = 25
private const val MAX_ISSUE_LIMIT = 250
private const val DEFAULT_METRIC_HOURS = 24
private const val MAX_METRIC_HOURS = 168
private const val MILLIS_PER_HOUR = 3_600_000L
private const val DEFAULT_SILENCE_MINUTES = 60L
private const val MILLIS_PER_MINUTE = 60_000L

class WorkflowTrustedActionExecutor(
    private val logService: LogService = LogService(LogRepositoryImpl()),
    private val dashboardService: DashboardService = DashboardService.create(),
    private val monitorService: MonitorService = MonitorService(HostRepositoryImpl(), HostAlertRepositoryImpl()),
    private val monitorAlertServiceProvider: () -> MonitorAlertService = { MonitorAlertService() },
    private val statusPageService: StatusPageService = StatusPageService(),
    private val nativeIncidentEntitlement: (Int) -> Boolean = FeatureRegistry::isNativeIncidentResponseEntitled,
) {
    private val json = workflowJson

    suspend fun execute(
        organizationId: Int,
        stepName: String,
        params: Map<String, String>,
        actorUserId: Int? = null,
        idempotencyKey: String? = null,
    ): Map<String, JsonElement> {
        if (stepName in WORKFLOW_PREMIUM_CONNECTOR_ACTIONS) {
            return executePremiumConnector(organizationId, stepName, params, actorUserId)
        }
        return executeBuiltIn(organizationId, stepName, params, actorUserId, idempotencyKey)
    }

    private suspend fun executeBuiltIn(
        organizationId: Int,
        stepName: String,
        params: Map<String, String>,
        actorUserId: Int?,
        idempotencyKey: String?,
    ): Map<String, JsonElement> {
        executeOnCallBuiltInOrNull(organizationId, stepName, params, actorUserId, idempotencyKey)?.let { return it }
        return when (stepName) {
            LOGS_SEARCH_STEP -> executeLogSearch(organizationId, params)
            LOGS_AGGREGATE_STEP -> executeLogAggregate(organizationId, params)
            METRICS_QUERY_STEP -> executeMetricsQuery(organizationId, params)
            TRACES_SEARCH_STEP -> executeTraceSearch(organizationId, params)
            SPAN_GET_STEP -> executeSpanGet(organizationId, params)
            ISSUES_LIST_STEP -> executeIssuesList(organizationId, params)
            ISSUES_GET_STEP -> executeIssueGet(organizationId, params)
            STATUS_PAGE_UPDATE_STEP -> executeStatusPageUpdate(organizationId, params)
            STATUS_PAGE_INCIDENT_CREATE_STEP -> executeStatusPageIncidentCreate(organizationId, params)
            ALERT_SILENCE_STEP -> executeAlertSilence(organizationId, params, actorUserId)
            else -> throw IllegalArgumentException("Unknown workflow step $stepName")
        }
    }

    private suspend fun executeOnCallBuiltInOrNull(
        organizationId: Int,
        stepName: String,
        params: Map<String, String>,
        actorUserId: Int?,
        idempotencyKey: String?,
    ): Map<String, JsonElement>? = when (stepName) {
        ON_CALL_PAGE_STEP -> executeOnCallPage(organizationId, params)
        ON_CALL_DECLARE_INCIDENT_STEP ->
            executeOnCallDeclareIncident(organizationId, params, actorUserId, idempotencyKey)
        ON_CALL_CREATE_INCIDENT_ACTION_STEP ->
            executeOnCallCreateIncidentAction(organizationId, params, actorUserId, idempotencyKey)
        else -> null
    }

    fun supports(stepName: String): Boolean =
        stepName in SUPPORTED_ACTIONS

    private suspend fun executeLogSearch(
        organizationId: Int,
        params: Map<String, String>
    ): Map<String, JsonElement> {
        val now = Instant.now()
        val request =
            LogQueryRequest(
                limit = intParam(params, "limit", DEFAULT_LOG_LIMIT, 1, MAX_LOG_LIMIT),
                query = params.optional("query"),
                levels = csvParam(params, "levels"),
                service = params.optional("service"),
                environment = params.optional("environment"),
                from = params.optional("from") ?: now.minus(DEFAULT_LOOKBACK_HOURS, ChronoUnit.HOURS).toString(),
                to = params.optional("to") ?: now.toString(),
                traceId = params.optional("trace_id"),
                systemId = params.optional("system_id"),
                containerName = params.optional("container_name")
            )
        return result("logs", logService.queryLogs(organizationId.toLong(), request))
    }

    private suspend fun executeLogAggregate(
        organizationId: Int,
        params: Map<String, String>
    ): Map<String, JsonElement> =
        result(
            "aggregate",
            logService.aggregateLogs(
                organizationId = organizationId.toLong(),
                filters = LogAnalyticsFilters(
                    from = params.optional("from"),
                    to = params.optional("to"),
                    query = params.optional("query"),
                    levels = csvParam(params, "levels"),
                    service = params.optional("service"),
                    environment = params.optional("environment")
                ),
                interval = params.optional("interval"),
                groupBy = params.optional("group_by")
            )
        )

    private suspend fun executeMetricsQuery(
        organizationId: Int,
        params: Map<String, String>
    ): Map<String, JsonElement> {
        val hostId = resolveHostId(organizationId, params.required("host_id"))
        val hours = intParam(params, "hours", DEFAULT_METRIC_HOURS, 1, MAX_METRIC_HOURS)
        val now = System.currentTimeMillis()
        return result(
            "metrics",
            monitorService.getHistoricalMetrics(hostId, now - hours * MILLIS_PER_HOUR, now, intervalSeconds = null)
        )
    }

    private suspend fun executeTraceSearch(
        organizationId: Int,
        params: Map<String, String>
    ): Map<String, JsonElement> {
        val projectId = resolveProjectId(organizationId, params.required("project_id"))
        return result(
            "traces",
            dashboardService.getTransactions(
                projectId = projectId,
                period = params.optional("period") ?: "24h",
                environment = params.optional("environment"),
                operation = params.optional("operation")
            )
        )
    }

    private suspend fun executeSpanGet(
        organizationId: Int,
        params: Map<String, String>
    ): Map<String, JsonElement> {
        val projectId = resolveProjectId(organizationId, params.required("project_id"))
        val spanId = params.required("span_id")
        return result("span", dashboardService.getSpanDetails(projectId, spanId))
    }

    private suspend fun executeIssuesList(
        organizationId: Int,
        params: Map<String, String>
    ): Map<String, JsonElement> {
        val projectId = resolveProjectId(organizationId, params.required("project_id"))
        val page = intParam(params, "page", DEFAULT_ISSUE_PAGE, 1, Int.MAX_VALUE)
        val limit = intParam(params, "limit", DEFAULT_ISSUE_LIMIT, 1, MAX_ISSUE_LIMIT)
        return result("issues", dashboardService.getIssues(projectId, page, limit, params.optional("status")))
    }

    private suspend fun executeIssueGet(
        organizationId: Int,
        params: Map<String, String>
    ): Map<String, JsonElement> {
        val projectId = resolveProjectId(organizationId, params.required("project_id"))
        return result("issue", dashboardService.getIssue(params.required("issue_id"), projectId = projectId))
    }

    private fun executeStatusPageUpdate(
        organizationId: Int,
        params: Map<String, String>
    ): Map<String, JsonElement> {
        val pageId = UUID.fromString(params.required("status_page_id"))
        val response =
            statusPageService.updateStatusPage(
                pageId = pageId,
                organizationId = organizationId,
                request = UpdateStatusPageRequest(
                    name = params.optional("name"),
                    description = params.optional("description"),
                    isPublic = params.optionalBoolean("is_public")
                )
            ) ?: throw IllegalArgumentException("Status page not found")
        return result("status_page", response)
    }

    private fun executeStatusPageIncidentCreate(
        organizationId: Int,
        params: Map<String, String>
    ): Map<String, JsonElement> {
        val pageId = UUID.fromString(params.required("status_page_id"))
        val response =
            statusPageService.createIncident(
                pageId = pageId,
                organizationId = organizationId,
                request = CreateIncidentRequest(
                    title = params.required("title"),
                    status = params.optional("status") ?: "investigating",
                    type = params.optional("type") ?: "incident",
                    impact = params.optional("impact") ?: "minor",
                    message = params.required("message")
                )
            )
        return result("incident", response)
    }

    private fun executeAlertSilence(
        organizationId: Int,
        params: Map<String, String>,
        actorUserId: Int?
    ): Map<String, JsonElement> {
        val now = System.currentTimeMillis()
        val startsAt = params.optionalLong("starts_at") ?: now
        val endsAt = params.optionalLong("ends_at")
            ?: now + DEFAULT_SILENCE_MINUTES * MILLIS_PER_MINUTE
        require(startsAt < endsAt) { "Silence starts_at must be before ends_at" }
        val period =
            monitorAlertServiceProvider().createSilencePeriod(
                organizationId = organizationId,
                userId = actorUserId ?: workflowActorUserId(organizationId),
                request = CreateSilencePeriodRequest(
                    reason = params.optional("reason") ?: "Workflow automation",
                    startsAt = startsAt,
                    endsAt = endsAt
                )
            )
        return result("silence", period)
    }

    private suspend fun executeOnCallPage(
        organizationId: Int,
        params: Map<String, String>
    ): Map<String, JsonElement> {
        val bridge = FeatureRegistry.getOnCallBridge()
            ?: return mapOf(
                "requires_enterprise" to JsonPrimitive(true),
                "message" to JsonPrimitive("On-call paging requires an enabled on-call bridge")
            )
        val escalationPolicyId =
            bridge.resolveEscalationPolicyId(organizationId, params.required("escalation_policy_id"))
                ?: throw IllegalArgumentException("Escalation policy not found for organization")
        val alertId =
            bridge.triggerEscalation(
                organizationId = organizationId,
                escalationPolicyId = escalationPolicyId,
                title = params.required("title"),
                description = params.optional("description"),
                priority = params.optional("alert_priority") ?: params.optional("priority_level") ?: "P2",
                alertSource = params.optional("alert_source") ?: "WORKFLOW",
                deduplicationKey = params.optional("deduplication_key"),
                metadata = params.optional("metadata")
            )
        return mapOf(
            "alert_id" to JsonPrimitive(alertId),
            "incident_id" to JsonPrimitive(alertId)
        )
    }

    private suspend fun executeOnCallDeclareIncident(
        organizationId: Int,
        params: Map<String, String>,
        actorUserId: Int?,
        idempotencyKey: String?,
    ): Map<String, JsonElement> {
        if (!nativeIncidentEntitlement(organizationId)) {
            throw IllegalStateException("Native incident response is not enabled for this organization")
        }
        val bridge = FeatureRegistry.getOnCallBridge()
            ?: return mapOf(
                "requires_enterprise" to JsonPrimitive(true),
                "message" to JsonPrimitive("On-call incident declaration requires an enabled on-call bridge")
            )
        val severity = IncidentSeverity.wireValue(params.required("incident_severity"))
            ?: throw IllegalArgumentException("Parameter incident_severity must be SEV-0 through SEV-4")
        val alertId = params.optional("alert_id")?.let { alertResourceId ->
            bridge.resolveAlertId(organizationId, alertResourceId)
                ?: throw IllegalArgumentException("On-call alert not found for organization")
        }
        val incidentId =
            bridge.declareIncident(
                OnCallIncidentDeclaration(
                    organizationId = organizationId,
                    userId = actorUserId ?: workflowActorUserId(organizationId),
                    alertId = alertId,
                    title = params.required("title"),
                    description = params.optional("description"),
                    severity = severity,
                    commandKey = idempotencyKey ?: "workflow:${UUID.randomUUID()}",
                ),
            ) ?: throw IllegalStateException("On-call incident declaration did not return an incident ID")
        return mapOf("incident_id" to JsonPrimitive(incidentId))
    }

    private suspend fun executeOnCallCreateIncidentAction(
        organizationId: Int,
        params: Map<String, String>,
        actorUserId: Int?,
        idempotencyKey: String?,
    ): Map<String, JsonElement> {
        if (!nativeIncidentEntitlement(organizationId)) {
            throw IllegalStateException("Native incident response is not enabled for this organization")
        }
        val bridge = FeatureRegistry.getOnCallBridge()
            ?: return mapOf(
                "requires_enterprise" to JsonPrimitive(true),
                "message" to JsonPrimitive("On-call incident actions require an enabled on-call bridge"),
            )
        val actionResourceId = bridge.createIncidentAction(
            OnCallIncidentActionRequest(
                organizationId = organizationId,
                incidentResourceId = params.required("incident_id"),
                userId = actorUserId ?: workflowActorUserId(organizationId),
                description = params.required("description"),
                assigneeUserResourceId = params.optional("assignee_user_id"),
                source = "WORKFLOW",
                commandKey = idempotencyKey ?: "workflow:${UUID.randomUUID()}:incident-action",
            ),
        ) ?: throw IllegalStateException("On-call incident action was not created")
        return mapOf("action_id" to JsonPrimitive(actionResourceId))
    }

    private suspend fun executePremiumConnector(
        organizationId: Int,
        stepName: String,
        params: Map<String, String>,
        actorUserId: Int?
    ): Map<String, JsonElement> {
        val bridge = FeatureRegistry.getWorkflowPremiumConnectorBridge()
            ?: return mapOf(
                "requires_enterprise" to JsonPrimitive(true),
                "message" to JsonPrimitive("Premium workflow connectors require workflows_advanced")
            )
        return bridge.executeConnectorAction(organizationId, stepName, params, actorUserId)
    }

    private fun resolveProjectId(
        organizationId: Int,
        projectResourceId: String
    ): Long {
        val parsedResourceId = parseResourceId(projectResourceId)
            ?: throw IllegalArgumentException("Parameter project_id must be a UUID")
        return transaction {
            Projects
                .selectAll()
                .where {
                    (Projects.resource_id eq parsedResourceId) and
                        (Projects.organization_id eq organizationId)
                }
                .firstOrNull()
                ?.get(Projects.id)
        } ?: throw IllegalArgumentException("Project not found for organization")
    }

    private fun resolveHostId(
        organizationId: Int,
        hostResourceId: String
    ): Int {
        val parsedResourceId = parseResourceId(hostResourceId)
            ?: throw IllegalArgumentException("Parameter host_id must be a UUID")
        return transaction {
            Hosts
                .selectAll()
                .where {
                    (Hosts.resource_id eq parsedResourceId) and
                        (Hosts.organization_id eq organizationId)
                }
                .firstOrNull()
                ?.get(Hosts.id)
        } ?: throw IllegalArgumentException("Host not found for organization")
    }

    private fun parseResourceId(resourceId: String): Uuid? =
        resourceId.toUuidOrNull()

    // Deterministic fallback for event-driven runs (alert/incident/security) that have no
    // human actor. Manual and API runs thread their real caller id in via [execute].
    private fun workflowActorUserId(organizationId: Int): Int =
        transaction {
            Memberships
                .selectAll()
                .where { Memberships.organization_id eq organizationId }
                .orderBy(Memberships.id to SortOrder.ASC)
                .firstOrNull()
                ?.get(Memberships.user_id)
        } ?: throw IllegalArgumentException("Organization has no members for workflow audit attribution")

    private inline fun <reified T> result(
        key: String,
        value: T
    ): Map<String, JsonElement> =
        mapOf(key to json.encodeToJsonElement(value))

    private fun Map<String, String>.required(name: String): String =
        optional(name) ?: throw IllegalArgumentException("Missing required parameter $name")

    private fun Map<String, String>.optional(name: String): String? =
        this[name]?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, String>.optionalBoolean(name: String): Boolean? =
        optional(name)?.let {
            it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("Parameter $name must be true or false")
        }

    private fun Map<String, String>.optionalLong(name: String): Long? =
        optional(name)?.let {
            it.toLongOrNull() ?: throw IllegalArgumentException("Parameter $name must be a number")
        }

    private fun intParam(
        params: Map<String, String>,
        name: String,
        defaultValue: Int,
        min: Int,
        max: Int
    ): Int =
        params.optional(name)
            ?.let { value ->
                (value.toIntOrNull() ?: throw IllegalArgumentException("Parameter $name must be an integer"))
                    .coerceIn(min, max)
            }
            ?: defaultValue

    private fun csvParam(
        params: Map<String, String>,
        name: String
    ): List<String> =
        params.optional(name)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    companion object {
        const val LOGS_SEARCH_STEP = "moneat.logs.search"
        const val LOGS_AGGREGATE_STEP = "moneat.logs.aggregate"
        const val METRICS_QUERY_STEP = "moneat.metrics.query"
        const val TRACES_SEARCH_STEP = "moneat.traces.search"
        const val SPAN_GET_STEP = "moneat.span.get"
        const val ISSUES_LIST_STEP = "moneat.issues.list"
        const val ISSUES_GET_STEP = "moneat.issues.get"
        const val STATUS_PAGE_UPDATE_STEP = "statuspage.update"
        const val STATUS_PAGE_INCIDENT_CREATE_STEP = "statuspage.incident.create"
        const val ALERT_SILENCE_STEP = "alert.silence"
        const val ON_CALL_PAGE_STEP = "oncall.page"
        const val ON_CALL_DECLARE_INCIDENT_STEP = "oncall.incident.declare"
        const val ON_CALL_CREATE_INCIDENT_ACTION_STEP = "oncall.incident.action.create"
        const val JIRA_CREATE_ISSUE_STEP = CONNECTOR_JIRA_CREATE_ISSUE_ACTION
        const val PAGERDUTY_TRIGGER_INCIDENT_STEP = CONNECTOR_PAGERDUTY_TRIGGER_INCIDENT_ACTION
        const val GITHUB_CREATE_ISSUE_STEP = CONNECTOR_GITHUB_CREATE_ISSUE_ACTION
        const val SERVICENOW_CREATE_INCIDENT_STEP = CONNECTOR_SERVICENOW_CREATE_INCIDENT_ACTION

        private val SUPPORTED_ACTIONS =
            setOf(
                LOGS_SEARCH_STEP,
                LOGS_AGGREGATE_STEP,
                METRICS_QUERY_STEP,
                TRACES_SEARCH_STEP,
                SPAN_GET_STEP,
                ISSUES_LIST_STEP,
                ISSUES_GET_STEP,
                STATUS_PAGE_UPDATE_STEP,
                STATUS_PAGE_INCIDENT_CREATE_STEP,
                ALERT_SILENCE_STEP,
                ON_CALL_PAGE_STEP,
                ON_CALL_DECLARE_INCIDENT_STEP,
                ON_CALL_CREATE_INCIDENT_ACTION_STEP,
            ) + WORKFLOW_PREMIUM_CONNECTOR_ACTIONS
    }
}
