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

package com.moneat.logs.routes

import com.moneat.auth.currentOrgIdOrNull
import com.moneat.dashboards.models.CreateDashboardAlertRequest
import com.moneat.dashboards.services.DashboardAlertService
import com.moneat.enterprise.FeatureRegistry
import com.moneat.logs.LogPermissions
import com.moneat.logs.models.CreateLogMetricRuleRequest
import com.moneat.logs.models.CreateLogMonitorRequest
import com.moneat.logs.models.LogAggregateResponse
import com.moneat.logs.models.LogMonitorDraftRequest
import com.moneat.logs.models.LogMonitorDraftResponse
import com.moneat.logs.models.LogPipelinePreviewRequest
import com.moneat.logs.models.UpdateLogMetricRuleRequest
import com.moneat.logs.models.UpdateLogMonitorRequest
import com.moneat.logs.models.UpdateLogPipelineRequest
import com.moneat.logs.models.UpdateLogSavedViewRequest
import com.moneat.logs.services.LogIndexService
import com.moneat.logs.services.LogManagementService
import com.moneat.logs.services.LogService
import com.moneat.org.services.OrgMembershipService
import com.moneat.org.services.OrgRole
import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private const val LOG_FORBIDDEN_MESSAGE = "Insufficient permissions"
private const val PIPELINE_NOT_FOUND_MESSAGE = "Pipeline not found"
private const val SAVED_VIEW_NOT_FOUND_MESSAGE = "Saved view not found"
private const val METRIC_RULE_NOT_FOUND_MESSAGE = "Metric rule not found"
private const val LOG_MONITOR_NOT_FOUND_MESSAGE = "Log monitor not found"
private const val INVALID_TOKEN_MESSAGE = "Invalid token"
private const val DEFAULT_METRIC_ROLLUP_MINUTES = 5L
private const val MAX_METRIC_ROLLUP_DAYS = 1L

fun Route.registerLogManagementRoutes(
    logManagementService: LogManagementService,
    logIndexService: LogIndexService,
    logService: LogService,
    membershipService: OrgMembershipService,
    dashboardAlertService: DashboardAlertService
) {
    registerLogIndexManagementRoutes(logIndexService, membershipService)
    registerLogPipelineManagementRoutes(logManagementService, membershipService)
    registerLogSavedViewManagementRoutes(logManagementService, membershipService)
    registerLogMetricManagementRoutes(logManagementService, logService, membershipService)
    registerLogMonitorManagementRoutes(logManagementService, membershipService, dashboardAlertService)
}

private fun Route.registerLogIndexManagementRoutes(
    logIndexService: LogIndexService,
    membershipService: OrgMembershipService
) {
    get("/logs/permissions") {
        call.respondLogPermissions(membershipService)
    }
    get("/logs/indexes/usage") {
        val orgId = call.logOrgIdOrRespond() ?: return@get
        call.respond(HttpStatusCode.OK, mapOf("usage" to logIndexService.usageStats(orgId)))
    }
    post("/logs/indexes/retention/run") {
        if (!call.ensureLogAccess(membershipService, LogPermissions.MANAGE)) return@post
        val orgId = call.logOrgIdOrRespond() ?: return@post
        val applied = logIndexService.enforceRetention(orgId)
        call.respond(HttpStatusCode.OK, mapOf("indexes_processed" to applied))
    }
}

private fun Route.registerLogPipelineManagementRoutes(
    logManagementService: LogManagementService,
    membershipService: OrgMembershipService
) {
    get("/logs/pipelines") {
        val orgId = call.logOrgIdOrRespond() ?: return@get
        call.respond(HttpStatusCode.OK, mapOf("pipelines" to logManagementService.listPipelines(orgId)))
    }
    post("/logs/pipelines") {
        if (!call.ensureLogAccess(membershipService, LogPermissions.MANAGE)) return@post
        val orgId = call.logOrgIdOrRespond() ?: return@post
        call.respondCreatedOrBadRequest {
            logManagementService.createPipeline(orgId, call.logUserId(), call.receive())
        }
    }
    put("/logs/pipelines/{id}") {
        if (!call.ensureLogAccess(membershipService, LogPermissions.MANAGE)) return@put
        val orgId = call.logOrgIdOrRespond() ?: return@put
        val id = call.logPathId("id") ?: return@put
        val updated = logManagementService.updatePipeline(orgId, id, call.receive<UpdateLogPipelineRequest>())
        call.respondNullable(updated, PIPELINE_NOT_FOUND_MESSAGE)
    }
    delete("/logs/pipelines/{id}") {
        if (!call.ensureLogAccess(membershipService, LogPermissions.MANAGE)) return@delete
        val orgId = call.logOrgIdOrRespond() ?: return@delete
        val id = call.logPathId("id") ?: return@delete
        call.respondDeleted(logManagementService.deletePipeline(orgId, id), PIPELINE_NOT_FOUND_MESSAGE)
    }
    post("/logs/pipelines/preview") {
        if (!call.ensureLogAccess(membershipService, LogPermissions.MANAGE)) return@post
        val result = logManagementService.previewPipeline(call.receive<LogPipelinePreviewRequest>())
        call.respond(HttpStatusCode.OK, mapOf("results" to result))
    }
}

private fun Route.registerLogSavedViewManagementRoutes(
    logManagementService: LogManagementService,
    membershipService: OrgMembershipService
) {
    get("/logs/saved-views") {
        val orgId = call.logOrgIdOrRespond() ?: return@get
        call.respond(
            HttpStatusCode.OK,
            mapOf("views" to logManagementService.listSavedViews(orgId, call.logUserId()))
        )
    }
    post("/logs/saved-views") {
        if (!call.ensureLogAccess(membershipService, LogPermissions.MANAGE)) return@post
        val orgId = call.logOrgIdOrRespond() ?: return@post
        call.respondCreatedOrBadRequest {
            logManagementService.createSavedView(orgId, call.logUserId(), call.receive())
        }
    }
    put("/logs/saved-views/{id}") {
        if (!call.ensureLogAccess(membershipService, LogPermissions.MANAGE)) return@put
        val orgId = call.logOrgIdOrRespond() ?: return@put
        val id = call.logPathId("id") ?: return@put
        val updated = logManagementService.updateSavedView(
            orgId,
            id,
            call.logUserId(),
            call.receive<UpdateLogSavedViewRequest>()
        )
        call.respondNullable(updated, SAVED_VIEW_NOT_FOUND_MESSAGE)
    }
    delete("/logs/saved-views/{id}") {
        if (!call.ensureLogAccess(membershipService, LogPermissions.MANAGE)) return@delete
        val orgId = call.logOrgIdOrRespond() ?: return@delete
        val id = call.logPathId("id") ?: return@delete
        call.respondDeleted(
            logManagementService.deleteSavedView(orgId, id, call.logUserId()),
            SAVED_VIEW_NOT_FOUND_MESSAGE
        )
    }
}

private fun Route.registerLogMetricManagementRoutes(
    logManagementService: LogManagementService,
    logService: LogService,
    membershipService: OrgMembershipService
) {
    get("/logs/metrics/rules") { handleListMetricRules(logManagementService) }
    post("/logs/metrics/rules") { handleCreateMetricRule(logManagementService, membershipService) }
    put("/logs/metrics/rules/{id}") { handleUpdateMetricRule(logManagementService, membershipService) }
    delete("/logs/metrics/rules/{id}") { handleDeleteMetricRule(logManagementService, membershipService) }
    post("/logs/metrics/preview") { handlePreviewMetricRule(logService, membershipService) }
    post("/logs/metrics/rules/{id}/rollup") {
        handleRollupMetricRule(logManagementService, logService, membershipService)
    }
}

private suspend fun RoutingContext.handleListMetricRules(logManagementService: LogManagementService) {
    val orgId = call.logOrgIdOrRespond() ?: return
    call.respond(HttpStatusCode.OK, mapOf("rules" to logManagementService.listMetricRules(orgId)))
}

private suspend fun RoutingContext.handleCreateMetricRule(
    logManagementService: LogManagementService,
    membershipService: OrgMembershipService
) {
    if (!call.ensureLogAccess(membershipService, LogPermissions.METRICS)) return
    val orgId = call.logOrgIdOrRespond() ?: return
    call.respondCreatedOrBadRequest {
        logManagementService.createMetricRule(orgId, call.logUserId(), call.receive())
    }
}

private suspend fun RoutingContext.handleUpdateMetricRule(
    logManagementService: LogManagementService,
    membershipService: OrgMembershipService
) {
    if (!call.ensureLogAccess(membershipService, LogPermissions.METRICS)) return
    val orgId = call.logOrgIdOrRespond() ?: return
    val id = call.logPathId("id") ?: return
    val updated = logManagementService.updateMetricRule(
        orgId,
        id,
        call.receive<UpdateLogMetricRuleRequest>()
    )
    call.respondNullable(updated, METRIC_RULE_NOT_FOUND_MESSAGE)
}

private suspend fun RoutingContext.handleDeleteMetricRule(
    logManagementService: LogManagementService,
    membershipService: OrgMembershipService
) {
    if (!call.ensureLogAccess(membershipService, LogPermissions.METRICS)) return
    val orgId = call.logOrgIdOrRespond() ?: return
    val id = call.logPathId("id") ?: return
    call.respondDeleted(logManagementService.deleteMetricRule(orgId, id), METRIC_RULE_NOT_FOUND_MESSAGE)
}

private suspend fun RoutingContext.handlePreviewMetricRule(
    logService: LogService,
    membershipService: OrgMembershipService
) {
    if (!call.ensureLogAccess(membershipService, LogPermissions.METRICS)) return
    val orgId = call.logOrgIdOrRespond() ?: return
    val request = call.receive<CreateLogMetricRuleRequest>()
    val aggregate = logService.aggregateForMetricPreview(orgId.toLong(), request)
    call.respond(HttpStatusCode.OK, aggregate)
}

private suspend fun RoutingContext.handleRollupMetricRule(
    logManagementService: LogManagementService,
    logService: LogService,
    membershipService: OrgMembershipService
) {
    if (!call.ensureLogAccess(membershipService, LogPermissions.METRICS)) return
    val orgId = call.logOrgIdOrRespond() ?: return
    val id = call.logPathId("id") ?: return
    val rule = logManagementService.getMetricRule(orgId, id)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(METRIC_RULE_NOT_FOUND_MESSAGE))
    val aggregate = logService.aggregateForMetricPreview(orgId.toLong(), rule.toCreateRequest())
    val inserted = logManagementService.recordMetricPoints(orgId.toLong(), rule, aggregate)
    call.respond(HttpStatusCode.OK, mapOf("points_inserted" to inserted))
}

private fun Route.registerLogMonitorManagementRoutes(
    logManagementService: LogManagementService,
    membershipService: OrgMembershipService,
    dashboardAlertService: DashboardAlertService
) {
    get("/logs/monitors") {
        val orgId = call.logOrgIdOrRespond() ?: return@get
        call.respond(HttpStatusCode.OK, mapOf("monitors" to logManagementService.listLogMonitors(orgId)))
    }
    post("/logs/monitors") {
        if (!call.ensureLogAccess(membershipService, LogPermissions.MONITORS)) return@post
        val orgId = call.logOrgIdOrRespond() ?: return@post
        call.respondCreatedOrBadRequest {
            logManagementService.createLogMonitor(
                orgId,
                call.logUserId(),
                call.receive<CreateLogMonitorRequest>()
            )
        }
    }
    put("/logs/monitors/{id}") {
        if (!call.ensureLogAccess(membershipService, LogPermissions.MONITORS)) return@put
        val orgId = call.logOrgIdOrRespond() ?: return@put
        val id = call.logPathId("id") ?: return@put
        call.respondNullableOrBadRequest(LOG_MONITOR_NOT_FOUND_MESSAGE) {
            logManagementService.updateLogMonitor(
                orgId,
                id,
                call.receive<UpdateLogMonitorRequest>()
            )
        }
    }
    delete("/logs/monitors/{id}") {
        if (!call.ensureLogAccess(membershipService, LogPermissions.MONITORS)) return@delete
        val orgId = call.logOrgIdOrRespond() ?: return@delete
        val id = call.logPathId("id") ?: return@delete
        call.respondDeleted(logManagementService.deleteLogMonitor(orgId, id), LOG_MONITOR_NOT_FOUND_MESSAGE)
    }
    post("/logs/monitors/from-query") {
        if (!call.ensureLogAccess(membershipService, LogPermissions.MONITORS)) return@post
        val request = call.receive<LogMonitorDraftRequest>()
        val response = call.createLogMonitorDraftOrBadRequest(request, dashboardAlertService) ?: return@post
        call.respond(HttpStatusCode.OK, response)
    }
}

private suspend fun ApplicationCall.respondLogPermissions(membershipService: OrgMembershipService) {
    val orgId = logOrgIdOrRespond() ?: return
    val userId = logUserId()
    respond(
        HttpStatusCode.OK,
        mapOf(
            "can_manage" to isLogAllowed(membershipService, orgId, userId, LogPermissions.MANAGE),
            "can_live_tail" to isLogAllowed(membershipService, orgId, userId, LogPermissions.LIVE_TAIL),
            "can_create_metrics" to isLogAllowed(membershipService, orgId, userId, LogPermissions.METRICS),
            "can_create_monitors" to isLogAllowed(membershipService, orgId, userId, LogPermissions.MONITORS)
        )
    )
}

private suspend fun ApplicationCall.createLogMonitorDraft(
    request: LogMonitorDraftRequest,
    dashboardAlertService: DashboardAlertService
): LogMonitorDraftResponse? {
    val dashboardId = request.dashboardId
    val widgetId = request.widgetId
    if (dashboardId != null && widgetId != null) {
        val orgId = logOrgIdOrRespond() ?: return null
        val alert = dashboardAlertService.createAlert(
            dashboardId = dashboardId,
            orgId = orgId.toLong(),
            createdBy = logUserId().toLong(),
            request = CreateDashboardAlertRequest(
                widgetId = widgetId,
                name = request.name,
                condition = request.condition,
                threshold = request.threshold,
                warningThreshold = request.warningThreshold,
                durationSeconds = request.durationSeconds
            )
        )
        return request.toDraftResponse(dashboardAlertCreated = true, dashboardAlertId = alert.id)
    }
    return request.toDraftResponse(dashboardAlertCreated = false, dashboardAlertId = null)
}

private suspend fun ApplicationCall.createLogMonitorDraftOrBadRequest(
    request: LogMonitorDraftRequest,
    dashboardAlertService: DashboardAlertService
): LogMonitorDraftResponse? =
    suspendRunCatching {
        createLogMonitorDraft(request, dashboardAlertService)
    }.getOrElse { error ->
        if (error is IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid monitor draft"))
            null
        } else {
            throw error
        }
    }

private suspend fun ApplicationCall.ensureLogAccess(
    membershipService: OrgMembershipService,
    permission: String
): Boolean {
    val orgId = logOrgIdOrRespond() ?: return false
    val userId = logUserId()
    val allowed = isLogAllowed(membershipService, orgId, userId, permission)
    if (!allowed) {
        respond(HttpStatusCode.Forbidden, ErrorResponse(LOG_FORBIDDEN_MESSAGE))
    }
    return allowed
}

private suspend fun isLogAllowed(
    membershipService: OrgMembershipService,
    orgId: Int,
    userId: Int,
    permission: String
): Boolean {
    val granular = FeatureRegistry.getPermissionBridge()?.hasPermission(orgId, userId, permission)
    return granular ?: suspendRunCatching {
        membershipService.requireRole(orgId, userId, OrgRole.ADMIN)
        true
    }.getOrElse { false }
}

private suspend fun <T> ApplicationCall.respondCreatedOrBadRequest(block: suspend () -> T) {
    suspendRunCatching {
        val value = block()
        respond(HttpStatusCode.Created, value as Any)
    }.getOrElse { error ->
        if (error is IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid request"))
        } else {
            throw error
        }
    }
}

private suspend fun ApplicationCall.respondNullable(
    value: Any?,
    missingMessage: String
) {
    if (value == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse(missingMessage))
    } else {
        respond(HttpStatusCode.OK, value)
    }
}

private suspend fun ApplicationCall.respondNullableOrBadRequest(
    missingMessage: String,
    block: suspend () -> Any?
) {
    suspendRunCatching {
        respondNullable(block(), missingMessage)
    }.getOrElse { error ->
        if (error is IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid request"))
        } else {
            throw error
        }
    }
}

private suspend fun ApplicationCall.respondDeleted(
    deleted: Boolean,
    missingMessage: String
) {
    if (deleted) {
        respond(HttpStatusCode.NoContent)
    } else {
        respond(HttpStatusCode.NotFound, ErrorResponse(missingMessage))
    }
}

private suspend fun ApplicationCall.logPathId(name: String): Int? {
    val id = parameters[name]?.toIntOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid ID"))
        return null
    }
    return id
}

private fun ApplicationCall.logUserId(): Int =
    principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()

private suspend fun ApplicationCall.logOrgIdOrRespond(): Int? {
    val orgId = principal<JWTPrincipal>()?.currentOrgIdOrNull()
    if (orgId == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
    }
    return orgId
}

private suspend fun LogService.aggregateForMetricPreview(
    organizationId: Long,
    request: CreateLogMetricRuleRequest
): LogAggregateResponse {
    val (from, to) = metricRollupWindow(request.interval)
    return aggregateLogs(
        organizationId = organizationId,
        from = from,
        to = to,
        interval = request.interval,
        query = request.query,
        levels = request.levels,
        service = null,
        environment = null,
        tags = emptyMap(),
        excludeService = null,
        excludeEnvironment = null,
        excludeContainerName = null,
        excludeTags = emptyMap(),
        groupBy = LogManagementService.normalizeMetricGroupBy(request.groupBy)
    )
}

private fun metricRollupWindow(interval: String): Pair<String, String> {
    val to = Clock.System.now()
    val from = to - metricIntervalDuration(interval)
    return from.toString() to to.toString()
}

private fun metricIntervalDuration(interval: String): Duration {
    val trimmed = interval.trim().lowercase()
    val amount = trimmed.dropLast(1).toLongOrNull() ?: DEFAULT_METRIC_ROLLUP_MINUTES
    val duration = when (trimmed.lastOrNull()) {
        'm' -> amount.minutes
        'h' -> amount.hours
        'd' -> amount.days
        else -> DEFAULT_METRIC_ROLLUP_MINUTES.minutes
    }
    val bounded = duration.coerceAtMost(MAX_METRIC_ROLLUP_DAYS.days)
    return if (bounded > Duration.ZERO) bounded else DEFAULT_METRIC_ROLLUP_MINUTES.minutes
}

private fun com.moneat.logs.models.LogMetricRuleResponse.toCreateRequest(): CreateLogMetricRuleRequest =
    CreateLogMetricRuleRequest(
        name = name,
        query = query,
        levels = levels,
        groupBy = groupBy,
        interval = interval,
        isActive = isActive
    )

private fun LogMonitorDraftRequest.toDraftResponse(
    dashboardAlertCreated: Boolean,
    dashboardAlertId: Long?
): LogMonitorDraftResponse =
    LogMonitorDraftResponse(
        name = name,
        query = query,
        levels = levels,
        groupBy = groupBy,
        condition = condition,
        threshold = threshold,
        warningThreshold = warningThreshold,
        dashboardAlertCreated = dashboardAlertCreated,
        dashboardAlertId = dashboardAlertId
    )
